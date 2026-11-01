package com.partha.wirelessmic;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Range;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

/**
 * Webcam: Camera2 -> hardware JPEG (1080p30 / 720p30) -> MJPEG over HTTP on port 8084.
 *   /video     multipart MJPEG stream (OBS, VLC, browsers)
 *   /snap.jpg  single frame
 *   /          full-screen viewer page (use as an OBS Browser source)
 *   /ctl?...   camera=back|front, res=1080|720, quality=50..95, torch=on|off
 * Works over Wi-Fi, or over USB with `adb forward tcp:8084 tcp:8084`.
 */
public class CamService extends Service {
    static final int PORT = 8084;
    static volatile CamService instance;

    volatile String facing = "back";
    volatile int height = 1080, quality = 85;
    volatile boolean torch;
    volatile float fps;
    volatile int clients;
    volatile String error;

    private final Object frameLock = new Object();
    private byte[] frame;                      // latest JPEG
    private long frameNo;

    private HandlerThread camThread;
    private Handler camHandler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private ServerSocket server;
    private volatile boolean running;
    private PowerManager.WakeLock wakeLock;
    private long fpsT0;
    private int fpsN;

    static void start(Context c) {
        Intent i = new Intent(c, CamService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) { c.stopService(new Intent(c, CamService.class)); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_NOT_STICKY;
        Notification n = TxService.notification(this, "Webcam is on", "Streaming the camera on port " + PORT);
        if (Build.VERSION.SDK_INT >= 30) startForeground(4, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        else startForeground(4, n);
        instance = this;
        running = true;
        facing = getSharedPreferences("wmic", MODE_PRIVATE).getString("cam_facing", "back");
        height = getSharedPreferences("wmic", MODE_PRIVATE).getInt("cam_height", 1080);
        quality = getSharedPreferences("wmic", MODE_PRIVATE).getInt("cam_quality", 85);
        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wmic:cam");
        wakeLock.acquire();
        camThread = new HandlerThread("cam");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
        new Thread(this::serve, "cam-http").start();
        openCamera();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        closeCamera();
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        synchronized (frameLock) { frameLock.notifyAll(); }
        if (camThread != null) camThread.quitSafely();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    // ---- camera -------------------------------------------------------------------------------

    /** Re-opens the camera with the current facing/resolution (called after a /ctl change). */
    void restart() { camHandler.post(() -> { closeCamera(); openCamera(); }); }

    private void openCamera() {
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            String id = null;
            int want = "front".equals(facing) ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            for (String c : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == want) { id = c; break; }
            }
            if (id == null) { error = "no " + facing + " camera"; return; }
            int w = height == 720 ? 1280 : 1920, h = height == 720 ? 720 : 1080;
            reader = ImageReader.newInstance(w, h, ImageFormat.JPEG, 3);
            reader.setOnImageAvailableListener(this::onImage, camHandler);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { device = d; startSession(); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); device = null; error = "camera taken by another app"; }
                @Override public void onError(CameraDevice d, int e) { d.close(); device = null; error = "camera error " + e; }
            }, camHandler);
            error = null;
        } catch (SecurityException e) {
            error = "camera permission not granted";
        } catch (CameraAccessException e) {
            error = e.getMessage();
        }
    }

    private void startSession() {
        try {
            device.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) { session = s; repeat(); }
                @Override public void onConfigureFailed(CameraCaptureSession s) { error = "camera session failed"; }
            }, camHandler);
        } catch (CameraAccessException e) { error = e.getMessage(); }
    }

    /** (Re)issues the repeating request; also used for torch/quality changes without reopening. */
    void repeat() {
        if (session == null || device == null) return;
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(15, 30));
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
            b.set(CaptureRequest.JPEG_QUALITY, (byte) quality);
            b.set(CaptureRequest.JPEG_ORIENTATION, 0);      // landscape, like a webcam
            b.set(CaptureRequest.FLASH_MODE, torch && "back".equals(facing)
                    ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            session.setRepeatingRequest(b.build(), null, camHandler);
        } catch (CameraAccessException | IllegalStateException e) { error = e.getMessage(); }
    }

    private void onImage(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            byte[] jpg = new byte[buf.remaining()];
            buf.get(jpg);
            synchronized (frameLock) { frame = jpg; frameNo++; frameLock.notifyAll(); }
            fpsN++;
            long now = System.currentTimeMillis();
            if (now - fpsT0 >= 1000) { fps = fpsN * 1000f / (now - fpsT0); fpsN = 0; fpsT0 = now; }
        } finally {
            img.close();
        }
    }

    private void closeCamera() {
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        try { if (device != null) device.close(); } catch (Exception ignored) { }
        try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        session = null; device = null; reader = null;
    }

    // ---- HTTP / MJPEG -----------------------------------------------------------------------

    private void serve() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
            while (running) {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                new Thread(() -> handle(s), "cam-client").start();
            }
        } catch (IOException e) {
            if (running) error = "port " + PORT + " busy";
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();
            String line = Net.readLine(in);
            Net.readHeaders(in);
            String path = line == null ? "/" : line.split(" ").length > 1 ? line.split(" ")[1] : "/";
            if (path.startsWith("/video")) stream(out);
            else if (path.startsWith("/snap")) snap(out);
            else if (path.startsWith("/ctl")) { control(path); text(out, "text/plain", "ok"); }
            else if (path.startsWith("/status")) text(out, "application/json", statusJson());
            else text(out, "text/html; charset=utf-8", PAGE);
        } catch (Exception ignored) { }
    }

    private void stream(OutputStream out) throws IOException, InterruptedException {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n"
                + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes());
        clients++;
        try {
            long last = -1;
            while (running) {
                byte[] f;
                synchronized (frameLock) {
                    while (running && frameNo == last) frameLock.wait(1000);
                    f = frame; last = frameNo;
                }
                if (f == null) continue;
                out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.length + "\r\n\r\n").getBytes());
                out.write(f);
                out.write("\r\n".getBytes());
                out.flush();
            }
        } finally {
            clients--;
        }
    }

    private void snap(OutputStream out) throws IOException {
        byte[] f;
        synchronized (frameLock) { f = frame; }
        if (f == null) { text(out, "text/plain", "no frame yet"); return; }
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.length + "\r\nConnection: close\r\n\r\n").getBytes());
        out.write(f);
    }

    void control(String path) {
        int q = path.indexOf('?');
        if (q < 0) return;
        boolean reopen = false;
        for (String kv : path.substring(q + 1).split("&")) {
            String[] p = kv.split("=", 2);
            if (p.length < 2) continue;
            switch (p[0]) {
                case "camera": if (!p[1].equals(facing)) { facing = "front".equals(p[1]) ? "front" : "back"; reopen = true; } break;
                case "res": int h = "720".equals(p[1]) ? 720 : 1080; if (h != height) { height = h; reopen = true; } break;
                case "quality": try { quality = Math.max(50, Math.min(95, Integer.parseInt(p[1]))); } catch (NumberFormatException ignored) { } break;
                case "torch": torch = "on".equals(p[1]); break;
            }
        }
        getSharedPreferences("wmic", MODE_PRIVATE).edit().putString("cam_facing", facing)
                .putInt("cam_height", height).putInt("cam_quality", quality).apply();
        if (reopen) restart(); else camHandler.post(this::repeat);
    }

    String statusJson() {
        return String.format(Locale.US, "{\"camera\":\"%s\",\"res\":%d,\"quality\":%d,\"torch\":%b,\"fps\":%.1f,\"clients\":%d,\"error\":%s}",
                facing, height, quality, torch, fps, clients, error == null ? "null" : "\"" + error.replace("\"", "'") + "\"");
    }

    private static void text(OutputStream out, String type, String body) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes());
        out.write(b);
    }

    /** Full-screen viewer (also the OBS Browser source); controls appear on hover / ?controls. */
    private static final String PAGE = "<!doctype html><html><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><title>Honor Webcam</title>"
            + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}img{width:100%;height:100%;object-fit:contain}"
            + "#c{position:fixed;left:8px;bottom:8px;font:14px sans-serif;color:#fff;background:rgba(0,0,0,.6);padding:8px;border-radius:8px;display:none}"
            + "body:hover #c,body.ctl #c{display:block}button{margin:2px}</style></head><body>"
            + "<img src='/video'><div id=c><span id=s></span><br>"
            + "<button onclick=\"x('camera=back')\">Back</button><button onclick=\"x('camera=front')\">Front</button>"
            + "<button onclick=\"x('res=1080')\">1080p</button><button onclick=\"x('res=720')\">720p</button>"
            + "<button onclick=\"x('torch=on')\">Light on</button><button onclick=\"x('torch=off')\">Light off</button></div>"
            + "<script>if(location.search.indexOf('controls')>=0)document.body.className='ctl';"
            + "function x(q){var r=new XMLHttpRequest();r.open('GET','/ctl?'+q);r.send()}"
            + "setInterval(function(){var r=new XMLHttpRequest();r.open('GET','/status');r.onload=function(){var j=JSON.parse(r.responseText);"
            + "document.getElementById('s').textContent=j.camera+' '+j.res+'p '+j.fps+' fps'+(j.error?' - '+j.error:'')};r.send()},1000)</script>"
            + "</body></html>";
}
