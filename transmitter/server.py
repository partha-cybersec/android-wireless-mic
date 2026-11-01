"""Honor 8 Wireless Mic - transmitter.

Captures the phone microphone (built-in or a TRRS lavalier in the 3.5 mm jack) through
PulseAudio, cleans it up, records a backup WAV with a -6 dB safety track, and streams it
to any browser on the network (the receiver page) over WebSocket.

    http(s)://<phone-ip>:8080   receiver page (open on the OPPO / PC); plain HTTP and HTTPS
                                are both answered on the same port
    ws://<phone-ip>:8081        audio + control socket (for http pages)
    wss://<phone-ip>:8082       same, encrypted (for https pages)
"""
import asyncio
import datetime
import http.server
import json
import os
import signal
import socket
import ssl
import struct
import subprocess
import threading
import time

import numpy as np
from websockets.asyncio.server import broadcast, serve
from websockets.exceptions import ConnectionClosed

RATE = 48000
FRAME = 480                      # 10 ms per frame
HTTP_PORT, WS_PORT, WSS_PORT = 8080, 8081, 8082
SOURCE = os.environ.get("WMIC_SOURCE", "OpenSL_ES_source")
HERE = os.path.dirname(os.path.abspath(__file__))
REC_DIR = "/storage/emulated/0/Music/WirelessMic"
CERT, KEY = os.path.join(HERE, "cert.pem"), os.path.join(HERE, "key.pem")
ICON_SVG = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96"><rect width="96" height="96" rx="22" '
            'fill="#2ecc71"/><rect x="36" y="16" width="24" height="40" rx="12" fill="#06200f"/><path d="M26 46a22 '
            '22 0 0 0 44 0" fill="none" stroke="#06200f" stroke-width="6" stroke-linecap="round"/><path d="M48 68v12" '
            'stroke="#06200f" stroke-width="6" stroke-linecap="round"/></svg>')


def tls_context():
    if not (os.path.isfile(CERT) and os.path.isfile(KEY)):
        return None
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(CERT, KEY)
    return ctx


TLS = tls_context()

state = {
    "gain_db": 6.0,              # input gain
    "mute": False,
    "ns": "low",                 # noise suppression: off | low | high
    "gate": False,               # noise gate
    "hpf": True,                 # 80 Hz high-pass (wind / handling rumble)
    "rec": False,
}
clients = set()


class NoiseSuppressor:
    """Spectral noise suppression with minimum-statistics noise tracking.

    20 ms sqrt-Hann windows at 50 % overlap (10 ms hop) -> perfect reconstruction,
    10 ms extra latency. The noise floor per FFT bin follows the quietest recent level,
    so steady hiss/fan/AC noise is learned automatically and subtracted.
    """

    def __init__(self, hop=FRAME):
        self.hop, self.n = hop, 2 * hop
        self.win = np.sqrt(0.5 - 0.5 * np.cos(2 * np.pi * np.arange(self.n) / self.n)).astype(np.float32)
        self.prev_in = np.zeros(hop, np.float32)
        self.prev_out = np.zeros(hop, np.float32)
        bins = self.n // 2 + 1
        self.psd = np.zeros(bins, np.float32)
        self.noise = None
        self.gain = np.ones(bins, np.float32)
        self.hpf_bins = int(80 / (RATE / self.n)) + 1

    def process(self, x, strength, hpf):
        frame = np.concatenate([self.prev_in, x])
        self.prev_in = x
        spec = np.fft.rfft(frame * self.win)
        p = (spec.real ** 2 + spec.imag ** 2).astype(np.float32)
        self.psd = 0.8 * self.psd + 0.2 * p
        if self.noise is None:
            self.noise = self.psd.copy() + 1e-3
        # follow drops immediately, rise slowly (~ +0.5 dB/s) so speech is not learned as noise
        self.noise = np.minimum(self.noise * 1.0012, np.maximum(self.psd, 1e-3))
        if strength == "off":
            g = np.ones_like(p)
        else:
            over, floor = (1.6, 0.18) if strength == "low" else (3.0, 0.06)
            g = np.clip(1.0 - over * self.noise / (self.psd + 1e-9), floor, 1.0)
        self.gain = 0.55 * self.gain + 0.45 * g
        g = self.gain.copy()
        if hpf:
            g[: self.hpf_bins] = 0.0
        y = np.fft.irfft(spec * g, self.n).astype(np.float32) * self.win
        out = self.prev_out + y[: self.hop]
        self.prev_out = y[self.hop:]
        return out


class Gate:
    def __init__(self, open_db=-48.0, close_db=-54.0):
        self.open_t, self.close_t = 10 ** (open_db / 20), 10 ** (close_db / 20)
        self.g, self.is_open = 0.0, False

    def process(self, x):
        level = float(np.sqrt(np.mean(x * x))) / 32768
        if level > self.open_t:
            self.is_open = True
        elif level < self.close_t:
            self.is_open = False
        target = 1.0 if self.is_open else 0.05
        ramp = np.linspace(self.g, target, x.size, dtype=np.float32)
        self.g = target
        return x * ramp


class Recorder:
    """Stereo 48 kHz / 16-bit WAV: L = main, R = safety track at -6 dB."""

    def __init__(self):
        self.f, self.path, self.frames, self.started = None, None, 0, 0.0

    def start(self):
        os.makedirs(REC_DIR, exist_ok=True)
        name = datetime.datetime.now().strftime("WMIC_%Y%m%d_%H%M%S.wav")
        self.path = os.path.join(REC_DIR, name)
        self.f = open(self.path, "wb")
        self.f.write(self._header(0))
        self.frames, self.started = 0, time.time()

    def _header(self, n):
        data = n * 4
        return (b"RIFF" + struct.pack("<I", 36 + data) + b"WAVEfmt " +
                struct.pack("<IHHIIHH", 16, 1, 2, RATE, RATE * 4, 4, 16) +
                b"data" + struct.pack("<I", data))

    def write(self, main_f32):
        if not self.f:
            return
        st = np.empty(main_f32.size * 2, np.int16)
        st[0::2] = np.clip(main_f32, -32768, 32767).astype(np.int16)
        st[1::2] = np.clip(main_f32 * 0.5, -32768, 32767).astype(np.int16)
        self.f.write(st.tobytes())
        self.frames += main_f32.size
        if self.frames % RATE < FRAME:          # rewrite header ~once a second: crash-safe file
            self._fix()

    def _fix(self):
        pos = self.f.tell()
        self.f.seek(0)
        self.f.write(self._header(self.frames))
        self.f.seek(pos)
        self.f.flush()

    def stop(self):
        if self.f:
            self._fix()
            self.f.close()
        self.f = None


ns, gate, rec = NoiseSuppressor(), Gate(), Recorder()
meter = {"peak": -90.0, "rms": -90.0, "clip": False}
info = {"battery": None, "charging": None, "wifi_rssi": None, "started": time.time(), "seq": 0}


def db(v):
    return 20 * np.log10(max(v, 1e-9))


async def capture():
    cmd = ["parec", f"--device={SOURCE}", "--format=s16le", f"--rate={RATE}", "--channels=1",
           "--latency-msec=10", "--raw"]
    while True:
        proc = await asyncio.create_subprocess_exec(*cmd, stdout=asyncio.subprocess.PIPE,
                                                    stderr=asyncio.subprocess.DEVNULL)
        try:
            while True:
                raw = await proc.stdout.readexactly(FRAME * 2)
                handle(np.frombuffer(raw, "<i2").astype(np.float32))
        except (asyncio.IncompleteReadError, ConnectionError):
            await asyncio.sleep(0.5)          # parec died (e.g. pulse restart): reconnect


def handle(x):
    y = ns.process(x, state["ns"], state["hpf"])
    if state["gate"]:
        y = gate.process(y)
    y = y * (10 ** (state["gain_db"] / 20))
    if state["mute"]:
        y = np.zeros_like(y)
    a = np.abs(y)
    pk = float(a.max()) / 32768
    meter["peak"] = max(db(pk), meter["peak"] - 1.5)        # peak hold with decay
    meter["rms"] = db(float(np.sqrt(np.mean(y * y))) / 32768)
    meter["clip"] = meter["clip"] or pk >= 0.999
    rec.write(y)
    info["seq"] = (info["seq"] + 1) & 0xFFFFFFFF
    pcm = np.clip(y, -32768, 32767).astype("<i2").tobytes()
    if clients:
        broadcast(clients, struct.pack("<I", info["seq"]) + pcm)


def status():
    return {
        "t": "status", **state, **meter,
        "rec_file": os.path.basename(rec.path) if rec.f else None,
        "rec_secs": round(rec.frames / RATE, 1) if rec.f else 0,
        "clients": len(clients), "battery": info["battery"], "charging": info["charging"],
        "wifi_rssi": info["wifi_rssi"], "uptime": int(time.time() - info["started"]),
        "rate": RATE, "frame": FRAME,
    }


async def status_loop():
    while True:
        await asyncio.sleep(0.2)
        if clients:
            broadcast(clients, json.dumps(status()))
        meter["clip"] = False


async def phone_info_loop():
    while True:
        for cmd, fn in ((["termux-battery-status"], _bat), (["termux-wifi-connectioninfo"], _wifi)):
            try:
                p = await asyncio.create_subprocess_exec(*cmd, stdout=asyncio.subprocess.PIPE,
                                                         stderr=asyncio.subprocess.DEVNULL)
                out, _ = await asyncio.wait_for(p.communicate(), 15)
                fn(json.loads(out or b"{}"))
            except Exception:
                pass
        await asyncio.sleep(20)


def _bat(j):
    info["battery"], info["charging"] = j.get("percentage"), j.get("plugged") not in (None, "UNPLUGGED")


def _wifi(j):
    info["wifi_rssi"] = j.get("rssi")


def apply(msg):
    if "gain_db" in msg:
        state["gain_db"] = float(min(36, max(-12, msg["gain_db"])))
    for k in ("mute", "gate", "hpf"):
        if k in msg:
            state[k] = bool(msg[k])
    if msg.get("ns") in ("off", "low", "high"):
        state["ns"] = msg["ns"]
    if "rec" in msg:
        if msg["rec"] and not rec.f:
            rec.start()
        elif not msg["rec"] and rec.f:
            rec.stop()
        state["rec"] = bool(rec.f)


def shutdown():
    """Stop button on the phone: close the recording cleanly, release the wake lock, exit."""
    rec.stop()
    try:
        subprocess.run(["termux-wake-unlock"], timeout=5)
    except Exception:
        pass
    asyncio.get_running_loop().call_later(0.3, os._exit, 0)


async def ws_handler(ws):
    clients.add(ws)
    try:
        await ws.send(json.dumps({"t": "hello", "rate": RATE, "frame": FRAME}))
        async for m in ws:
            if isinstance(m, str):
                msg = json.loads(m)
                if msg.get("t") == "ping":
                    await ws.send(json.dumps({"t": "pong", "id": msg.get("id")}))
                elif msg.get("t") == "shutdown":
                    if ws.remote_address[0] in ("127.0.0.1", "::1", "::ffff:127.0.0.1"):   # phone only
                        shutdown()
                else:
                    apply(msg)
    except (ConnectionClosed, json.JSONDecodeError):
        pass                                   # receiver reloaded / Wi-Fi blip: normal
    finally:
        clients.discard(ws)


class Page(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=HERE, **kw)

    def do_GET(self):
        if self.path.startswith("/rec/"):
            name = os.path.basename(self.path[5:].split("?")[0])
            path = os.path.join(REC_DIR, name)
            if name.endswith(".wav") and os.path.isfile(path):
                self.send_response(200)
                self.send_header("Content-Type", "audio/wav")
                self.send_header("Content-Length", str(os.path.getsize(path)))
                self.send_header("Content-Disposition", f'attachment; filename="{name}"')
                self.end_headers()
                with open(path, "rb") as f:
                    while chunk := f.read(1 << 16):
                        self.wfile.write(chunk)
                return
            self.send_error(404)
            return
        if self.path == "/recordings.json":
            files = sorted((f for f in os.listdir(REC_DIR) if f.endswith(".wav")), reverse=True) \
                if os.path.isdir(REC_DIR) else []
            body = json.dumps([{"name": f, "mb": round(os.path.getsize(os.path.join(REC_DIR, f)) / 1e6, 1)}
                               for f in files]).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path == "/info.json":
            ips = local_ips()
            body = json.dumps({"receiver": f"http://{ips[0]}:{HTTP_PORT}" if ips else None}).encode()
            return self._send(body, "application/json")
        if self.path.startswith("/qr.svg"):
            from urllib.parse import parse_qs, urlparse
            import qrcode
            import qrcode.image.svg
            data = parse_qs(urlparse(self.path).query).get("u", [""])[0][:200]
            img = qrcode.make(data, image_factory=qrcode.image.svg.SvgPathImage, box_size=10, border=1)
            return self._send(img.to_string(), "image/svg+xml")
        if self.path == "/manifest.json":
            body = json.dumps({"name": "Wireless Mic", "short_name": "Wireless Mic", "start_url": "/tx",
                               "display": "standalone", "background_color": "#0e1116", "theme_color": "#0e1116",
                               "icons": [{"src": "/icon.svg", "sizes": "any", "type": "image/svg+xml"}]}).encode()
            return self._send(body, "application/manifest+json")
        if self.path == "/icon.svg":
            return self._send(ICON_SVG.encode(), "image/svg+xml")
        if self.path.startswith("/tx") or self.path.startswith("/?tx"):
            self.path = "/tx.html"
        elif self.path in ("/", "/index.html") or self.path.startswith("/?"):
            self.path = "/receiver.html"
        return super().do_GET()

    def _send(self, body, ctype):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


class DualServer(http.server.ThreadingHTTPServer):
    """Answers plain HTTP and HTTPS on one port: Chrome often upgrades http:// to https://."""

    def get_request(self):
        sock, addr = super().get_request()
        if TLS:
            sock.settimeout(5)
            if sock.recv(1, socket.MSG_PEEK) == b"\x16":      # TLS handshake record
                sock = TLS.wrap_socket(sock, server_side=True)
            sock.settimeout(None)
        return sock, addr


def local_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(ips)


async def main():
    # Stop Mic shortcut sends SIGTERM (background jobs ignore Ctrl+C / SIGINT)
    asyncio.get_running_loop().add_signal_handler(signal.SIGTERM, shutdown)
    srv = DualServer(("0.0.0.0", HTTP_PORT), Page)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    print("Honor 8 Wireless Mic - transmitter running")
    for ip in local_ips():
        print(f"  Receiver:  http://{ip}:{HTTP_PORT}")
        if TLS:
            print(f"        or:  https://{ip}:{HTTP_PORT}  (tap Advanced -> Proceed once)")
    print(f"  On this phone (app screen): http://127.0.0.1:{HTTP_PORT}/tx")
    print("  Ctrl+C to stop", flush=True)
    async with serve(ws_handler, "0.0.0.0", WS_PORT, max_queue=4, compression=None):
        if TLS:
            async with serve(ws_handler, "0.0.0.0", WSS_PORT, max_queue=4, compression=None, ssl=TLS):
                await asyncio.gather(capture(), status_loop(), phone_info_loop())
        else:
            await asyncio.gather(capture(), status_loop(), phone_info_loop())


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
    except OSError as e:
        if e.errno == 98:
            print(f"Ports {HTTP_PORT}/{WS_PORT} are busy - another transmitter is running. "
                  "Run 'wmic' again (it stops the old one) or: pkill -f wireless-mic/server.py")
        else:
            raise
    finally:
        rec.stop()
