# Android Wireless Mic

Turn an old Android phone into a **Hollyland-style wireless microphone**. One phone is the
transmitter; the receiver is a second phone running the same app, or any browser on the same
Wi-Fi (PC, OBS).

Two ways to run it:
- **Native Android app** (`android-app/`, recommended): one 45 KB APK with Transmit and Receive
  modes, automatic pairing over Wi-Fi, and foreground services so it keeps working with the screen off.
  **[Download the APK from Releases](https://github.com/partha-cybersec/android-wireless-mic/releases/latest)**
- **Termux version** (`transmitter/`): Python transmitter for phones where you'd rather not install an APK.

Built and tested on a **Honor 8 (FRD-L02, Android 7.0)** with a phone hotspot as the network.

## Native app (android-app/)

| | |
|---|---|
| Transmit mode | Mic → spectral noise suppression → gate → gain → backup WAV (main + safety −6 dB) → stream |
| Receive mode | Finds transmitters automatically (mDNS/NSD), jitter buffer, low-latency AudioTrack playback, remote mute/record |
| Browser receiver | Still built in: open `http://<mic-phone-ip>:8080` on a PC or in OBS |
| Share the app | A receiver phone can download the APK straight from the mic phone's receiver page |
| Recording | Receiver chooses **Record on: Mic phone / This phone / Both**; files end in `_MIC.wav` or `_RX.wav` |
| Save folder | Each phone: **Change folder** (any folder, including a microSD card) or **Default** (app folder); full path shown |
| Noise cancellation | 3 engines: **App DSP** (Wiener filter + speech-presence noise tracking), **Dual-mic** (bottom + top mic, power-level-difference), **Phone** (Huawei/Android call processing). Measured with pink noise on the Honor 8: App DSP removes ~11.5 dB; Dual-mic High removes ~22 dB |
| Bluetooth headset mic | **Microphone: Phone mic / Bluetooth headset**: use any paired hands-free headset or neckband as a wireless lav (Bluetooth SCO, 16 kHz mSBC wideband when supported, 8 kHz otherwise; falls back to the phone mic if no headset). Tested with OnePlus Bullets Wireless Z2: wideband mSBC, 0 packets lost |
| Size / deps | 45 KB, pure Java, no libraries, minSdk 24 (Android 7.0), targetSdk 34 |
| Tested | Honor 8: 0 packets lost, about 91 ms latency, 6 % CPU, safety track exactly −6.02 dB |

**Use:** install the APK on both phones, then: mic phone **TRANSMIT → START MIC**; other phone
**RECEIVE →** tap the mic's name. For a camera, connect the receiver's headphone jack to the camera's
MIC input with a TRRS-to-TRS cable.

**Build it yourself:** JDK 17+ and the Android SDK (platform 34, build-tools 34), then
`cd android-app && ./gradlew assembleRelease` (Windows: `gradlew.bat`). Output:
`app/build/outputs/apk/release/app-release.apk`. The release is signed with the debug key for
easy side-loading; use your own keystore for a store release.

## Smart Key app (android-app/smartkey/)

Honor/Huawei phones with a clickable fingerprint sensor (Honor 8 and similar, EMUI 5) send every
press as a `com.huawei.smartkey` broadcast (`command=Click | DoubleClick | LongPress`), but some
firmware builds ship without the app that handles it. **Smart Key** (18 KB) restores it:

| Press | Default | Choices |
|---|---|---|
| Single | Flashlight on/off | Flashlight, camera, play/pause, Wi-Fi on/off, **Wireless Mic start / mute / record**, or open any app |
| Double | Open camera | same |
| Press & hold | Wireless Mic mute/unmute | same |

Wireless Mic control uses a signature-protected permission, so only apps signed with the same key
can start, mute or record the mic. Build: `./gradlew :smartkey:assembleRelease`.

---

## Termux version

<p>
  <img src="docs/transmitter-app.png" width="280" alt="Transmitter app screen">
  <img src="docs/transmitter-qr.png" width="280" alt="Controls and receiver QR code">
</p>

## Features

| Hollyland-style feature | This project |
|---|---|
| Wireless TX → RX | WebSocket over Wi-Fi, 10 ms audio frames, ~70–120 ms end to end (adjustable buffer 30–300 ms) |
| Noise cancellation | Spectral noise suppression (Off / Low / High) with minimum-statistics noise tracking |
| Low-cut | 80 Hz high-pass for wind and handling rumble |
| Onboard backup recording | 48 kHz / 16-bit WAV on the phone (`Music/WirelessMic/`), crash-safe header |
| Safety channel | WAV right channel = main at −6 dB, which rescues clipped takes |
| Gain / mute | From the phone or remotely from any receiver (−12…+36 dB) |
| Status | TX battery, TX Wi-Fi RSSI, level meter with peak hold and clip warning, latency, packet loss |
| Lavalier mic | Any TRRS lav mic in the 3.5 mm jack |
| Home-screen app | One-tap **Wireless Mic** / **Stop Mic** icons (Termux:Widget) |
| Easy pairing | QR code on the transmitter screen opens the receiver page |

Measured on the Honor 8: 100 packets/s with 0 lost, about 2 % CPU.

## How it works

```
Phone mic / TRRS lav ─► PulseAudio (OpenSL ES source) ─► parec 48 kHz mono
   ─► server.py: noise suppression ─► gate ─► gain ─► WAV recorder (main + safety −6 dB)
   ─► WebSocket broadcast (ws :8081, wss :8082) ─► receiver.html in any browser (Web Audio)
HTTP and HTTPS are both served on :8080 (Chrome often upgrades http:// to https://).
```

## Install (on the transmitter phone)

You need these apps, all from the same source (F-Droid or GitHub, because their signing keys must match):
**Termux**, **Termux:API**, **Termux:Widget**.

```bash
pkg install git
git clone https://github.com/partha-cybersec/android-wireless-mic
cd android-wireless-mic
bash install.sh
```

Then give **Termux:API** microphone permission, and add the **Termux shortcut** widget to the home
screen twice: once for *Wireless Mic*, once for *Stop Mic*.

## Use

1. Tap **Wireless Mic** on the home screen, or type `wmic` in Termux. The transmitter screen opens.
2. On the receiver phone, scan the QR code (or open `http://<phone-ip>:8080`) and tap **Start listening**.
   - If Chrome shows *Your connection is not private*, that's the phone's self-signed certificate:
     tap **Advanced → Proceed**.
3. **Into a camera:** receiver phone headphone jack → **TRRS-to-TRS** cable → camera MIC input.
4. **PC over USB (no Wi-Fi):** `adb forward tcp:8080 tcp:8080 && adb forward tcp:8081 tcp:8081`,
   then open `http://localhost:8080`.
5. **OBS:** add a Browser source with the receiver URL and tick *Control audio via OBS*.
6. Tap **Stop Mic** when done. A running recording is closed cleanly.

Backup recordings can be downloaded from the receiver page.

## Limitations

- Latency is about 70–150 ms (Wi-Fi + browser audio), versus about 20 ms on dedicated 2.4 GHz systems.
  That's fine for recording and vlogging; for live lip-sync, shift the audio in your editor.
- Range is Wi-Fi range. A phone hotspot or a 5 GHz router works best.
- On Huawei/EMUI, enable *Phone Manager → Protected apps → Termux* for long sessions.

## Files

| Path | Purpose |
|---|---|
| `transmitter/server.py` | Capture, DSP, recorder, HTTP/HTTPS + WebSocket server |
| `transmitter/receiver.html` | Receiver / remote-control page |
| `transmitter/tx.html` | Transmitter app screen (ON AIR, REC, meters, QR) |
| `transmitter/wmic` | Launcher: PulseAudio, mic source, certificate, wake lock |
| `shortcuts/` | Termux:Widget home-screen shortcuts and icons |
| `install.sh` | One-shot installer for Termux |

## License

MIT
