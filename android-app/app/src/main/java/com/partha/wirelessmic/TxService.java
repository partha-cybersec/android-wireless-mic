package com.partha.wirelessmic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

/** Transmitter: mic capture -> noise suppression -> gate -> gain -> WAV backup + WebSocket stream. */
public class TxService extends Service {
    static final int HTTP_PORT = 8080, WS_PORT = 8081;
    static final String SERVICE_TYPE = "_wmic._tcp.";
    static volatile TxService instance;

    // controls (changed from the UI or any receiver)
    volatile float gainDb = 6f;
    volatile boolean mute, gate, hpf = true;
    volatile int ns = 1;                       // 0 off, 1 low, 2 high

    // meters / status
    volatile float peakDb = -90, rmsDb = -90;
    volatile boolean clip;
    volatile int battery = -1, rssi = 0;
    volatile boolean charging;
    final long startedAt = System.currentTimeMillis();
    final WavRecorder rec = new WavRecorder();

    private Net.Server server;
    private volatile boolean running;
    private Thread captureThread, statusThread;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private NsdManager nsd;
    private NsdManager.RegistrationListener nsdListener;

    static void start(Context c) {
        Intent i = new Intent(c, TxService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) { c.stopService(new Intent(c, TxService.class)); }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_STICKY;
        Notification n = notification(this, "Wireless Mic is on air", "Tap to open controls");
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(1, n);
        instance = this;
        running = true;

        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wmic:tx");
        wakeLock.acquire();
        wifiLock = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wmic:tx");
        wifiLock.acquire();

        server = new Net.Server(this::route, new Net.Listener() {
            @Override public void onOpen(Net.Session s) {
                s.sendText("{\"t\":\"hello\",\"rate\":" + Dsp.RATE + ",\"frame\":" + Dsp.HOP + "}");
            }
            @Override public void onText(Net.Session s, String text) { control(s, text); }
            @Override public void onClose(Net.Session s) { }
        });
        try {
            server.listen(HTTP_PORT);
            server.listen(WS_PORT);
        } catch (IOException e) {
            notifyError("Port " + HTTP_PORT + " busy - is another mic app running?");
        }

        captureThread = new Thread(this::captureLoop, "tx-capture");
        captureThread.setPriority(Thread.MAX_PRIORITY);
        captureThread.start();
        statusThread = new Thread(this::statusLoop, "tx-status");
        statusThread.start();
        registerNsd();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        rec.stop();
        if (server != null) server.stop();
        if (nsd != null && nsdListener != null) try { nsd.unregisterService(nsdListener); } catch (Exception ignored) { }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    // ---- audio ------------------------------------------------------------------------------

    private void captureLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        int min = AudioRecord.getMinBufferSize(Dsp.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar;
        try {
            // VOICE_RECOGNITION = unprocessed mic on most phones (no AGC pumping); our DSP does the rest
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Dsp.RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, Dsp.HOP * 8));
            ar.startRecording();
        } catch (SecurityException | IllegalStateException e) {
            notifyError("Microphone not available: " + e.getMessage());
            return;
        }
        Dsp.NoiseSuppressor nsp = new Dsp.NoiseSuppressor();
        Dsp.Gate gt = new Dsp.Gate();
        short[] pcm = new short[Dsp.HOP];
        float[] x = new float[Dsp.HOP], y = new float[Dsp.HOP];
        byte[] packet = new byte[4 + Dsp.HOP * 2];
        int seq = 0;
        while (running) {
            int got = 0;
            while (got < Dsp.HOP && running) {
                int n = ar.read(pcm, got, Dsp.HOP - got);
                if (n <= 0) break;
                got += n;
            }
            if (got < Dsp.HOP) continue;
            for (int i = 0; i < Dsp.HOP; i++) x[i] = pcm[i];
            nsp.process(x, y, ns, hpf);
            if (gate) gt.process(y);
            float g = mute ? 0f : (float) Math.pow(10, gainDb / 20.0);
            float pk = 0; double sum = 0;
            for (int i = 0; i < Dsp.HOP; i++) {
                y[i] *= g;
                float a = Math.abs(y[i]);
                if (a > pk) pk = a;
                sum += y[i] * y[i];
            }
            float pkN = pk / 32768f;
            peakDb = Math.max(db(pkN), peakDb - 1.5f);
            rmsDb = db((float) Math.sqrt(sum / Dsp.HOP) / 32768f);
            if (pkN >= 0.999f) clip = true;
            rec.write(y);
            seq++;
            packet[0] = (byte) seq; packet[1] = (byte) (seq >> 8); packet[2] = (byte) (seq >> 16); packet[3] = (byte) (seq >> 24);
            for (int i = 0, o = 4; i < Dsp.HOP; i++) {
                int v = y[i] > 32767 ? 32767 : (y[i] < -32768 ? -32768 : (int) y[i]);
                packet[o++] = (byte) v; packet[o++] = (byte) (v >> 8);
            }
            if (server != null && !server.sessions.isEmpty()) server.broadcastBinary(packet.clone());
        }
        ar.stop();
        ar.release();
    }

    private static float db(float v) { return (float) (20 * Math.log10(Math.max(v, 1e-9f))); }

    // ---- status + control -----------------------------------------------------------------

    private void statusLoop() {
        long lastPhone = 0;
        while (running) {
            if (System.currentTimeMillis() - lastPhone > 10000) { readPhone(); lastPhone = System.currentTimeMillis(); }
            if (server != null && !server.sessions.isEmpty()) server.broadcastText(statusJson());
            clip = false;
            try { Thread.sleep(200); } catch (InterruptedException e) { return; }
        }
    }

    private void readPhone() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            battery = lvl < 0 ? -1 : lvl * 100 / sc;
            charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        }
        try {
            rssi = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE)).getConnectionInfo().getRssi();
        } catch (Exception ignored) { }
    }

    int receivers() { return server == null ? 0 : server.sessions.size(); }

    String statusJson() {
        try {
            JSONObject j = new JSONObject();
            j.put("t", "status").put("gain_db", gainDb).put("mute", mute).put("gate", gate).put("hpf", hpf)
             .put("ns", ns == 0 ? "off" : ns == 1 ? "low" : "high").put("rec", rec.active())
             .put("peak", peakDb).put("rms", rmsDb).put("clip", clip)
             .put("rec_file", rec.active() ? rec.name() : JSONObject.NULL)
             .put("rec_secs", Math.round(rec.seconds() * 10) / 10.0)
             .put("clients", receivers()).put("battery", battery < 0 ? JSONObject.NULL : battery)
             .put("charging", charging).put("wifi_rssi", rssi == 0 ? JSONObject.NULL : rssi)
             .put("uptime", (System.currentTimeMillis() - startedAt) / 1000).put("rate", Dsp.RATE).put("frame", Dsp.HOP);
            return j.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private void control(Net.Session s, String text) {
        try {
            JSONObject m = new JSONObject(text);
            if ("ping".equals(m.optString("t"))) {
                s.sendText("{\"t\":\"pong\",\"id\":" + m.opt("id") + "}");
                return;
            }
            apply(m);
        } catch (Exception ignored) { }
    }

    /** Same control messages as the browser receiver uses. */
    void apply(JSONObject m) {
        if (m.has("gain_db")) gainDb = (float) Math.max(-12, Math.min(36, m.optDouble("gain_db", gainDb)));
        if (m.has("mute")) mute = m.optBoolean("mute");
        if (m.has("gate")) gate = m.optBoolean("gate");
        if (m.has("hpf")) hpf = m.optBoolean("hpf");
        if (m.has("ns")) { String v = m.optString("ns"); ns = "off".equals(v) ? 0 : "high".equals(v) ? 2 : 1; }
        if (m.has("rec")) setRecording(m.optBoolean("rec"));
    }

    void setRecording(boolean on) {
        if (on && !rec.active()) {
            try { rec.start(recDir(this)); } catch (IOException e) { notifyError("Cannot record: " + e.getMessage()); }
        } else if (!on) {
            rec.stop();
        }
    }

    static File recDir(Context c) {
        File base = c.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        return new File(base != null ? base : c.getFilesDir(), "WirelessMic");
    }

    // ---- HTTP routes (browser receiver for PC / OBS) ---------------------------------------

    private Net.Response route(String path) {
        try {
            if (path.equals("/") || path.startsWith("/?") || path.equals("/index.html"))
                return new Net.Response("text/html; charset=utf-8", asset("receiver.html"));
            if (path.equals("/info.json")) {
                String ip = wifiIp();
                return new Net.Response("application/json", ("{\"receiver\":" + (ip == null ? "null" : "\"http://" + ip + ":" + HTTP_PORT + "\"") + "}").getBytes());
            }
            if (path.equals("/recordings.json")) {
                JSONArray a = new JSONArray();
                File[] files = recDir(this).listFiles();
                if (files != null) {
                    Arrays.sort(files, Collections.reverseOrder());
                    for (File f : files) if (f.getName().endsWith(".wav"))
                        a.put(new JSONObject().put("name", f.getName()).put("mb", Math.round(f.length() / 1e5) / 10.0));
                }
                return new Net.Response("application/json", a.toString().getBytes());
            }
            if (path.equals("/wirelessmic.apk"))          // share this app with the receiver phone
                return new Net.Response("application/vnd.android.package-archive", new File(getPackageCodePath()));
            if (path.startsWith("/rec/")) {
                String name = new File(java.net.URLDecoder.decode(path.substring(5), "UTF-8")).getName();
                File f = new File(recDir(this), name);
                if (name.endsWith(".wav") && f.isFile()) return new Net.Response("audio/wav", f);
            }
        } catch (Exception ignored) { }
        return null;
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    /** IPv4 address on Wi-Fi (or hotspot), for display and the browser URL. */
    static String wifiIp() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    String n = ni.getName().toLowerCase(Locale.US);
                    if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- auto-pairing (NSD / mDNS) ----------------------------------------------------------

    private void registerNsd() {
        try {
            nsd = (NsdManager) getSystemService(NSD_SERVICE);
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("Wireless Mic (" + Build.MODEL + ")");
            info.setServiceType(SERVICE_TYPE);
            info.setPort(WS_PORT);
            nsdListener = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo i) { }
                @Override public void onRegistrationFailed(NsdServiceInfo i, int e) { }
                @Override public void onServiceUnregistered(NsdServiceInfo i) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo i, int e) { }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Exception ignored) { }
    }

    // ---- notifications ------------------------------------------------------------------------

    static Notification notification(Context c, String title, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("wmic", "Wireless Mic", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(c, "wmic");
        } else {
            b = new Notification.Builder(c);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), flags);
        return b.setContentTitle(title).setContentText(text).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(open).setOngoing(true).build();
    }

    private void notifyError(String msg) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(2, notification(this, "Wireless Mic problem", msg));
    }
}
