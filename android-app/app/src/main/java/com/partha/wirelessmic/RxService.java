package com.partha.wirelessmic;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.util.ArrayDeque;

/** Receiver: WebSocket client -> jitter buffer -> AudioTrack (headphone jack / speaker / camera cable). */
public class RxService extends Service {
    static volatile RxService instance;
    static final String EXTRA_HOST = "host";

    volatile String host;
    volatile String state = "idle";                 // idle | connecting | live | reconnecting
    volatile String lastError;                      // why the last connection attempt failed
    volatile JSONObject txStatus;                   // latest status from the transmitter
    volatile int targetMs = 80;
    volatile long rttMs = -1;
    volatile int received, lost;
    final WavRecorder rec = new WavRecorder();      // optional copy of the received audio on this phone

    private final ArrayDeque<short[]> queue = new ArrayDeque<>();
    private int queuedSamples;
    private volatile boolean running;
    private Net.Client client;
    private Thread playThread, pingThread;
    private int lastSeq = -1;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    static void start(Context c, String host) {
        Intent i = new Intent(c, RxService.class).putExtra(EXTRA_HOST, host);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) { c.stopService(new Intent(c, RxService.class)); }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String h = intent == null ? null : intent.getStringExtra(EXTRA_HOST);
        Notification n = TxService.notification(this, "Wireless Mic receiver", "Listening to " + h);
        if (Build.VERSION.SDK_INT >= 29) startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(3, n);
        instance = this;
        if (running && h != null && h.equals(host)) return START_NOT_STICKY;
        host = h;
        if (!running) {
            running = true;
            wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wmic:rx");
            wakeLock.acquire();
            wifiLock = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE))
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wmic:rx");
            wifiLock.acquire();
            playThread = new Thread(this::playLoop, "rx-play");
            playThread.start();
            pingThread = new Thread(this::pingLoop, "rx-ping");
            pingThread.start();
        }
        connect();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        rec.stop();
        if (client != null) client.close();
        synchronized (queue) { queue.notifyAll(); }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    private void connect() {
        if (client != null) client.close();
        state = "connecting";
        lastSeq = -1;
        final Net.Client c = new Net.Client();
        client = c;
        c.connect(host, TxService.WS_PORT, new Net.Client.Callback() {
            @Override public void onBinary(byte[] d) {
                if (d.length < 6) return;
                int seq = (d[0] & 0xFF) | (d[1] & 0xFF) << 8 | (d[2] & 0xFF) << 16 | (d[3] & 0xFF) << 24;
                if (lastSeq != -1 && seq != lastSeq + 1) lost += Math.max(0, seq - lastSeq - 1);
                lastSeq = seq;
                received++;
                short[] pcm = new short[(d.length - 4) / 2];
                for (int i = 0; i < pcm.length; i++) pcm[i] = (short) ((d[4 + 2 * i] & 0xFF) | (d[5 + 2 * i] << 8));
                push(pcm);
                rec.writePcm(pcm);
                state = "live";
                lastError = null;
            }
            @Override public void onText(String t) {
                try {
                    JSONObject j = new JSONObject(t);
                    String type = j.optString("t");
                    if ("status".equals(type)) txStatus = j;
                    else if ("pong".equals(type)) rttMs = System.currentTimeMillis() - j.optLong("id");
                } catch (Exception ignored) { }
            }
            @Override public void onClosed(String why) {
                if (!running || client != c) return;
                state = "reconnecting";
                lastError = why;
                new Thread(() -> {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
                    if (running && client == c) connect();
                }).start();
            }
        });
    }

    /** Record the received audio on this phone. Returns an error message, or null on success. */
    String setLocalRecording(boolean on) {
        if (!on) { rec.stop(); return null; }
        try { rec.start(this, "RX"); return null; } catch (Exception e) { return e.getMessage(); }
    }

    /** Send a control message (mute, rec, gain_db, ns, gate, hpf) to the transmitter. */
    void send(String json) { if (client != null) client.sendText(json); }

    int bufferedMs() { synchronized (queue) { return queuedSamples * 1000 / Dsp.RATE; } }

    private void push(short[] pcm) {
        synchronized (queue) {
            queue.addLast(pcm);
            queuedSamples += pcm.length;
            // latency control: never let the queue grow past target + 60 ms
            int max = (targetMs + 60) * Dsp.RATE / 1000, keep = targetMs * Dsp.RATE / 1000;
            if (queuedSamples > max) while (queuedSamples > keep && !queue.isEmpty()) queuedSamples -= queue.removeFirst().length;
            queue.notifyAll();
        }
    }

    private void playLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        int min = AudioTrack.getMinBufferSize(Dsp.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack.Builder b = null;
        AudioTrack at;
        if (Build.VERSION.SDK_INT >= 23) {
            b = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(Dsp.RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, Dsp.HOP * 4)).setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            at = b.build();
        } else {
            at = new AudioTrack(android.media.AudioManager.STREAM_MUSIC, Dsp.RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, Dsp.HOP * 4), AudioTrack.MODE_STREAM);
        }
        at.play();
        boolean primed = false;
        short[] silence = new short[Dsp.HOP];
        while (running) {
            short[] frame;
            synchronized (queue) {
                if (!primed && queuedSamples < targetMs * Dsp.RATE / 1000) {
                    try { queue.wait(20); } catch (InterruptedException e) { break; }
                    continue;
                }
                primed = true;
                frame = queue.pollFirst();
                if (frame != null) queuedSamples -= frame.length;
                else primed = false;                       // underrun: re-buffer to the target
            }
            at.write(frame != null ? frame : silence, 0, frame != null ? frame.length : silence.length);
        }
        at.stop();
        at.release();
    }

    private void pingLoop() {
        while (running) {
            send("{\"t\":\"ping\",\"id\":" + System.currentTimeMillis() + "}");
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
    }
}
