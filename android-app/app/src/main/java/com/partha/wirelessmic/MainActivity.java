package com.partha.wirelessmic;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    // palette
    static final int BG = 0xFF0E1116, CARD = 0xFF171B22, LINE = 0xFF262C36, TEXT = 0xFFE8ECF2, DIM = 0xFF8B95A5,
            GREEN = 0xFF2ECC71, RED = 0xFFFF4D4F, BLUE = 0xFF3EA6FF, BTN = 0xFF232A35, DARK_GREEN = 0xFF06200F;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout txPanel, rxPanel, foundList;
    private Button tabTx, tabRx, startBtn, airBtn, recBtn, hpfBtn, gateBtn, rxRecBtn, rxMuteBtn, connectBtn;
    private final Button[] nsBtns = new Button[3];
    private TextView txStats, txInfo, rxStatus, rxStats, gainLabel, bufLabel, recInfo;
    private SeekBar gainBar, bufBar;
    private Meter txMeter, rxMeter;
    private EditText ipField;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private final Map<String, String> found = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("wmic", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        showTab(prefs.getBoolean("rx", false));
        askPermissions();
    }

    @Override protected void onResume() { super.onResume(); ui.post(refresh); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(refresh); stopDiscovery(); }

    // ---- layout ---------------------------------------------------------------------------------

    private View buildUi() {
        LinearLayout root = col();
        root.setPadding(dp(16), dp(14), dp(16), dp(24));
        root.setBackgroundColor(BG);

        TextView title = text("Wireless Mic", 22, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        LinearLayout tabs = row();
        tabTx = button("TRANSMIT", v -> showTab(false));
        tabRx = button("RECEIVE", v -> showTab(true));
        tabs.addView(tabTx, weight());
        tabs.addView(space(8));
        tabs.addView(tabRx, weight());
        root.addView(tabs, margins(0, 12, 0, 12));

        // --- transmitter ---
        txPanel = col();
        startBtn = button("START MIC", v -> toggleTx());
        startBtn.setTextSize(20);
        startBtn.setPadding(0, dp(22), 0, dp(22));
        txPanel.addView(startBtn);
        airBtn = button("ON AIR", v -> { TxService t = TxService.instance; if (t != null) t.mute = !t.mute; });
        airBtn.setTextSize(30);
        airBtn.setTypeface(Typeface.DEFAULT_BOLD);
        airBtn.setPadding(0, dp(26), 0, dp(26));
        txPanel.addView(airBtn, margins(0, 10, 0, 0));
        txMeter = new Meter(this);
        txPanel.addView(txMeter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)));
        ((LinearLayout.LayoutParams) txMeter.getLayoutParams()).topMargin = dp(12);
        recBtn = button("● RECORD BACKUP", v -> { TxService t = TxService.instance; if (t != null) t.setRecording(!t.rec.active()); });
        recBtn.setPadding(0, dp(16), 0, dp(16));
        txPanel.addView(recBtn, margins(0, 12, 0, 0));
        recInfo = text("", 12, DIM);
        recInfo.setGravity(Gravity.CENTER);
        txPanel.addView(recInfo, margins(0, 6, 0, 0));
        txStats = text("", 15, TEXT);
        txPanel.addView(card(txStats), margins(0, 12, 0, 0));

        LinearLayout ctl = col();
        gainLabel = text("Mic gain", 13, DIM);
        ctl.addView(gainLabel);
        gainBar = seek(48, (int) (6 + 12), p -> { TxService t = TxService.instance; if (t != null) t.gainDb = p - 12; });
        ctl.addView(gainBar);
        ctl.addView(text("Noise cancellation", 13, DIM), margins(0, 12, 0, 4));
        LinearLayout nsRow = row();
        String[] names = {"Off", "Low", "High"};
        for (int i = 0; i < 3; i++) {
            final int level = i;
            nsBtns[i] = button(names[i], v -> { TxService t = TxService.instance; if (t != null) t.ns = level; });
            nsRow.addView(nsBtns[i], weight());
            if (i < 2) nsRow.addView(space(6));
        }
        ctl.addView(nsRow);
        LinearLayout tg = row();
        hpfBtn = button("Low-cut 80 Hz", v -> { TxService t = TxService.instance; if (t != null) t.hpf = !t.hpf; });
        gateBtn = button("Noise gate", v -> { TxService t = TxService.instance; if (t != null) t.gate = !t.gate; });
        tg.addView(hpfBtn, weight());
        tg.addView(space(8));
        tg.addView(gateBtn, weight());
        ctl.addView(tg, margins(0, 10, 0, 0));
        txPanel.addView(card(ctl), margins(0, 12, 0, 0));
        txInfo = text("", 13, DIM);
        txPanel.addView(card(txInfo), margins(0, 12, 0, 0));
        root.addView(txPanel);

        // --- receiver ---
        rxPanel = col();
        LinearLayout pick = col();
        pick.addView(text("Transmitters on this Wi-Fi", 13, DIM));
        foundList = col();
        pick.addView(foundList);
        LinearLayout manual = row();
        ipField = new EditText(this);
        ipField.setHint("or type IP, e.g. 10.17.18.78");
        ipField.setHintTextColor(DIM);
        ipField.setTextColor(TEXT);
        ipField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ipField.setText(cleanHost(prefs.getString("host", "")));
        manual.addView(ipField, weight());
        connectBtn = button("Connect", v -> connectTo(ipField.getText().toString().trim()));
        manual.addView(connectBtn);
        pick.addView(manual, margins(0, 8, 0, 0));
        rxPanel.addView(card(pick));

        rxStatus = text("Not connected", 18, TEXT);
        rxStatus.setTypeface(Typeface.DEFAULT_BOLD);
        rxPanel.addView(rxStatus, margins(0, 14, 0, 0));
        rxMeter = new Meter(this);
        rxPanel.addView(rxMeter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)));
        ((LinearLayout.LayoutParams) rxMeter.getLayoutParams()).topMargin = dp(10);
        rxStats = text("", 15, TEXT);
        rxPanel.addView(card(rxStats), margins(0, 12, 0, 0));

        LinearLayout rctl = col();
        bufLabel = text("Buffer", 13, DIM);
        rctl.addView(bufLabel);
        bufBar = seek(270, prefs.getInt("buf", 80) - 30, p -> {
            RxService r = RxService.instance; if (r != null) r.targetMs = p + 30;
            prefs.edit().putInt("buf", p + 30).apply();
        });
        rctl.addView(bufBar);
        rctl.addView(text("Lower = less delay, higher = fewer dropouts on weak Wi-Fi", 12, DIM));
        LinearLayout remote = row();
        rxMuteBtn = button("Mute mic", v -> remote("mute"));
        rxRecBtn = button("● Rec on mic", v -> remote("rec"));
        remote.addView(rxMuteBtn, weight());
        remote.addView(space(8));
        remote.addView(rxRecBtn, weight());
        rctl.addView(remote, margins(0, 12, 0, 0));
        rxPanel.addView(card(rctl), margins(0, 12, 0, 0));
        Button disc = button("Disconnect", v -> RxService.stop(this));
        rxPanel.addView(disc, margins(0, 12, 0, 0));
        rxPanel.addView(text("Camera: headphone jack → TRRS-to-TRS cable → camera MIC input.", 12, DIM), margins(0, 12, 0, 0));
        root.addView(rxPanel);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(root);
        return sv;
    }

    private void showTab(boolean rx) {
        prefs.edit().putBoolean("rx", rx).apply();
        txPanel.setVisibility(rx ? View.GONE : View.VISIBLE);
        rxPanel.setVisibility(rx ? View.VISIBLE : View.GONE);
        style(tabTx, !rx ? BLUE : BTN, !rx ? 0xFF001629 : TEXT);
        style(tabRx, rx ? BLUE : BTN, rx ? 0xFF001629 : TEXT);
        if (rx) startDiscovery(); else stopDiscovery();
    }

    // ---- actions --------------------------------------------------------------------------------

    private void toggleTx() {
        if (TxService.instance != null) { TxService.stop(this); return; }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions();
            return;
        }
        RxService.stop(this);
        TxService.start(this);
    }

    /** Accepts "10.17.18.78", "http://10.17.18.78:8080", "10.17.18.78:8080/" etc. and returns just the host. */
    static String cleanHost(String s) {
        s = s.trim().replaceFirst("(?i)^[a-z]+://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) s = s.substring(0, colon);   // strip port (not for IPv6)
        return s;
    }

    private void connectTo(String host) {
        host = cleanHost(host);
        if (host.isEmpty()) return;
        ipField.setText(host);
        prefs.edit().putString("host", host).apply();
        TxService.stop(this);
        RxService.start(this, host);
        ui.postDelayed(() -> { RxService r = RxService.instance; if (r != null) r.targetMs = bufBar.getProgress() + 30; }, 300);
    }

    private void remote(String what) {
        RxService r = RxService.instance;
        if (r == null || r.txStatus == null) return;
        r.send("{\"" + what + "\":" + !r.txStatus.optBoolean(what) + "}");
    }

    private void askPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 1);
        else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
    }

    // ---- auto-discovery -------------------------------------------------------------------------

    private void startDiscovery() {
        if (discovery != null) return;
        nsd = (NsdManager) getSystemService(NSD_SERVICE);
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { discovery = null; }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceLost(NsdServiceInfo s) { ui.post(() -> { found.remove(s.getServiceName()); renderFound(); }); }
            @Override public void onServiceFound(NsdServiceInfo s) {
                nsd.resolveService(s, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo i, int e) { }
                    @Override public void onServiceResolved(NsdServiceInfo i) {
                        String ip = i.getHost() == null ? null : i.getHost().getHostAddress();
                        if (ip != null && !ip.contains(":"))
                            ui.post(() -> { found.put(i.getServiceName(), ip); renderFound(); });
                    }
                });
            }
        };
        try { nsd.discoverServices(TxService.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery); }
        catch (Exception e) { discovery = null; }
        renderFound();
    }

    private void stopDiscovery() {
        if (nsd != null && discovery != null) try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) { }
        discovery = null;
    }

    private void renderFound() {
        foundList.removeAllViews();
        if (found.isEmpty()) {
            foundList.addView(text("Searching… start the mic on the other phone (same Wi-Fi).", 13, DIM), margins(0, 6, 0, 0));
            return;
        }
        for (Map.Entry<String, String> e : found.entrySet()) {
            final String ip = e.getValue();
            Button bt = button("🎙  " + e.getKey() + "   " + ip, v -> connectTo(ip));
            style(bt, GREEN, DARK_GREEN);
            foundList.addView(bt, margins(0, 8, 0, 0));
        }
    }

    // ---- live refresh ---------------------------------------------------------------------------

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            renderTx();
            renderRx();
            ui.postDelayed(this, 150);
        }
    };

    private void renderTx() {
        TxService t = TxService.instance;
        boolean on = t != null;
        startBtn.setText(on ? "■ STOP MIC" : "▶ START MIC");
        style(startBtn, on ? BTN : GREEN, on ? RED : DARK_GREEN);
        airBtn.setVisibility(on ? View.VISIBLE : View.GONE);
        recBtn.setEnabled(on);
        String ip = TxService.wifiIp();
        if (!on) {
            txMeter.set(-90, -90);
            txStats.setText("Mic is off");
            recInfo.setText("");
            txInfo.setText("Tap START MIC. Then on the other phone open Wireless Mic → RECEIVE and tap this phone's name."
                    + (ip != null ? "\n\nPC / OBS browser: http://" + ip + ":" + TxService.HTTP_PORT : "\n\nNot connected to Wi-Fi."));
            return;
        }
        airBtn.setText(t.mute ? "MUTED\n(tap to go live)" : "ON AIR\n(tap to mute)");
        style(airBtn, t.mute ? 0xFF3A1F22 : GREEN, t.mute ? 0xFFFFB3B5 : DARK_GREEN);
        txMeter.set(t.rmsDb, t.peakDb);
        boolean r = t.rec.active();
        recBtn.setText(r ? "■ STOP RECORDING  " + mmss(t.rec.seconds()) : "● RECORD BACKUP");
        style(recBtn, r ? RED : BTN, r ? Color.WHITE : TEXT);
        recInfo.setText(r ? t.rec.name() + "  (L main · R safety −6 dB)" : "Saved to " + TxService.recDir(this).getAbsolutePath());
        int rx = t.receivers();
        txStats.setText(String.format(Locale.US, "%s   ·   Wi-Fi %s   ·   %d receiver%s   ·   %s on air\nLevel %s",
                t.battery < 0 ? "–" : t.battery + "%" + (t.charging ? "⚡" : ""), t.rssi == 0 ? "–" : t.rssi + " dBm",
                rx, rx == 1 ? "" : "s", mmss((System.currentTimeMillis() - t.startedAt) / 1000.0),
                t.mute ? "muted" : Math.round(t.peakDb) + " dBFS" + (t.clip ? "  CLIP – lower gain" : "")));
        if (!gainBar.isPressed()) gainBar.setProgress(Math.round(t.gainDb) + 12);
        gainLabel.setText("Mic gain " + Math.round(t.gainDb) + " dB");
        for (int i = 0; i < 3; i++) style(nsBtns[i], t.ns == i ? GREEN : BTN, t.ns == i ? DARK_GREEN : TEXT);
        style(hpfBtn, t.hpf ? GREEN : BTN, t.hpf ? DARK_GREEN : TEXT);
        style(gateBtn, t.gate ? GREEN : BTN, t.gate ? DARK_GREEN : TEXT);
        txInfo.setText("Receivers: open Wireless Mic → RECEIVE on the other phone; this mic appears automatically."
                + (ip != null ? "\n\nPC / OBS browser: http://" + ip + ":" + TxService.HTTP_PORT : ""));
    }

    private void renderRx() {
        bufLabel.setText("Buffer " + (bufBar.getProgress() + 30) + " ms");
        RxService r = RxService.instance;
        if (r == null) {
            rxStatus.setText("Not connected");
            rxStatus.setTextColor(DIM);
            rxMeter.set(-90, -90);
            rxStats.setText("Pick a transmitter above.");
            return;
        }
        JSONObject s = r.txStatus;
        boolean live = "live".equals(r.state);
        rxStatus.setText(live ? "● Live from " + r.host : r.state.substring(0, 1).toUpperCase(Locale.US) + r.state.substring(1) + " to " + r.host + "…");
        rxStatus.setTextColor(live ? GREEN : 0xFFF5B041);
        if (!live && r.lastError != null)
            rxStatus.append("\n" + (r.lastError.contains("ECONNREFUSED") || r.lastError.contains("refused")
                    ? "Mic phone found, but the mic is not started. Tap START MIC there."
                    : r.lastError.contains("timed out") || r.lastError.contains("EHOSTUNREACH") || r.lastError.contains("ENETUNREACH")
                    ? "Can't reach " + r.host + ". Check both phones are on the same Wi-Fi/hotspot."
                    : r.lastError));
        if (s != null) rxMeter.set((float) s.optDouble("rms", -90), (float) s.optDouble("peak", -90));
        long lat = r.rttMs < 0 ? -1 : r.rttMs / 2 + r.bufferedMs() + 25;
        rxStats.setText(String.format(Locale.US, "Latency %s   ·   buffer %d ms\nMic battery %s   ·   mic Wi-Fi %s\n%d packets, %d lost%s",
                lat < 0 ? "–" : lat + " ms", r.bufferedMs(),
                s == null || s.isNull("battery") ? "–" : s.optInt("battery") + "%" + (s.optBoolean("charging") ? "⚡" : ""),
                s == null || s.isNull("wifi_rssi") ? "–" : s.optInt("wifi_rssi") + " dBm",
                r.received, r.lost,
                s != null && s.optBoolean("rec") ? "\n● Mic is recording " + mmss(s.optDouble("rec_secs")) : ""));
        boolean muted = s != null && s.optBoolean("mute"), rec = s != null && s.optBoolean("rec");
        rxMuteBtn.setText(muted ? "Unmute mic" : "Mute mic");
        style(rxMuteBtn, muted ? 0xFF3A1F22 : BTN, muted ? 0xFFFFB3B5 : TEXT);
        rxRecBtn.setText(rec ? "■ Stop rec" : "● Rec on mic");
        style(rxRecBtn, rec ? RED : BTN, rec ? Color.WHITE : TEXT);
    }

    // ---- small UI helpers -----------------------------------------------------------------------

    private static String mmss(double s) {
        int t = (int) s;
        return t >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60)
                         : String.format(Locale.US, "%d:%02d", t / 60, t % 60);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private LinearLayout col() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private View space(int w) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(dp(w), 1)); return v; }

    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setOnClickListener(l);
        style(b, BTN, TEXT);
        return b;
    }

    private void style(Button b, int bg, int fg) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(14));
        d.setStroke(dp(1), bg == BTN ? LINE : bg);
        b.setBackground(d);
        b.setTextColor(fg);
    }

    private View card(View content) {
        LinearLayout c = col();
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable d = new GradientDrawable();
        d.setColor(CARD);
        d.setCornerRadius(dp(14));
        d.setStroke(dp(1), LINE);
        c.setBackground(d);
        c.addView(content);
        return c;
    }

    interface IntConsumer { void accept(int v); }

    private SeekBar seek(int max, int value, IntConsumer onChange) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(value);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) { if (user) onChange.accept(p); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        return s;
    }

    /** Level meter: RMS bar (green/amber/red zones) with a white peak marker. */
    static final class Meter extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float rms = -90, peak = -90;

        Meter(Context c) { super(c); }

        void set(float rmsDb, float peakDb) { rms = rmsDb; peak = peakDb; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2;
            p.setColor(0xFF0A0D11);
            c.drawRoundRect(0, 0, w, h, r, r, p);
            float lv = Math.max(0, Math.min(1, (rms + 60) / 60)) * w;
            float[] stops = {0.70f, 0.88f, 1f};
            int[] cols = {GREEN, 0xFFF5B041, RED};
            float from = 0;
            for (int i = 0; i < 3; i++) {
                float to = Math.min(lv, stops[i] * w);
                if (to > from) { p.setColor(cols[i]); c.drawRoundRect(from, 0, to, h, r, r, p); }
                from = stops[i] * w;
            }
            p.setColor(Color.WHITE);
            float pk = Math.max(0, Math.min(1, (peak + 60) / 60)) * w;
            c.drawRect(pk - 2, 0, pk + 1, h, p);
        }
    }
}
