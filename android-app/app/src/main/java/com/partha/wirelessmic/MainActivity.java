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
    private LinearLayout txPanel, rxPanel, camPanel, foundList;
    private Button tabTx, tabRx, tabCam, camBtn, torchBtn, startBtn, airBtn, recBtn, hpfBtn, gateBtn, rxRecBtn, rxMuteBtn, connectBtn;
    private final Button[] nsBtns = new Button[3];
    private final Button[] targetBtns = new Button[3];      // record on: mic phone / this phone / both
    private TextView txFolder, rxFolder, srcInfo;
    private final Button[] srcBtns = new Button[2];         // microphone: phone / Bluetooth headset
    private final Button[] engBtns = new Button[3];         // NC engine: app DSP / dual-mic / phone
    private TextView ncInfo, camInfo;
    private final Button[] faceBtns = new Button[2];        // webcam: back / front
    private final Button[] resBtns = new Button[2];         // webcam: 1080p / 720p
    private static final int PICK_FOLDER = 42;
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
        showTab(prefs.getInt("tab", prefs.getBoolean("rx", false) ? 1 : 0));
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
        tabTx = button("MIC", v -> showTab(0));
        tabRx = button("RECEIVE", v -> showTab(1));
        tabCam = button("WEBCAM", v -> showTab(2));
        tabs.addView(tabTx, weight());
        tabs.addView(space(6));
        tabs.addView(tabRx, weight());
        tabs.addView(space(6));
        tabs.addView(tabCam, weight());
        root.addView(tabs, margins(0, 12, 0, 12));

        // --- transmitter ---
        txPanel = col();
        startBtn = button("START MIC", v -> toggleTx());
        startBtn.setTextSize(20);
        startBtn.setPadding(0, dp(22), 0, dp(22));
        txPanel.addView(startBtn);

        LinearLayout srcCard = col();
        srcCard.addView(text("Microphone", 13, DIM));
        LinearLayout srcRow = row();
        String[] sn = {"Phone mic", "Bluetooth headset"};
        for (int i = 0; i < 2; i++) {
            final int s = i;
            srcBtns[i] = button(sn[i], v -> {
                prefs.edit().putInt("mic_src", s).apply();
                TxService t = TxService.instance;
                if (t != null) t.source = s;
            });
            srcRow.addView(srcBtns[i], weight());
            if (i == 0) srcRow.addView(space(8));
        }
        srcCard.addView(srcRow, margins(0, 4, 0, 0));
        srcInfo = text("", 12, DIM);
        srcCard.addView(srcInfo, margins(0, 6, 0, 0));
        txPanel.addView(card(srcCard), margins(0, 12, 0, 0));
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
        txFolder = text("", 13, TEXT);
        txPanel.addView(folderCard(txFolder), margins(0, 12, 0, 0));
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
        ctl.addView(text("Noise cancellation engine", 13, DIM), margins(0, 12, 0, 4));
        LinearLayout engRow = row();
        String[] en = {"App DSP", "Dual-mic", "Phone"};
        for (int i = 0; i < 3; i++) {
            final int e = i;
            engBtns[i] = button(en[i], v -> {
                prefs.edit().putInt("nc_engine", e).apply();
                TxService t = TxService.instance;
                if (t != null) t.engine = e;
            });
            engRow.addView(engBtns[i], weight());
            if (i < 2) engRow.addView(space(6));
        }
        ctl.addView(engRow);
        ncInfo = text("", 12, DIM);
        ctl.addView(ncInfo, margins(0, 6, 0, 0));
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
        rxMuteBtn = button("Mute mic", v -> remote("mute"));
        rctl.addView(rxMuteBtn, margins(0, 12, 0, 0));
        rxPanel.addView(card(rctl), margins(0, 12, 0, 0));

        LinearLayout recCard = col();
        recCard.addView(text("Record on", 13, DIM));
        LinearLayout targets = row();
        String[] tn = {"Mic phone", "This phone", "Both"};
        for (int i = 0; i < 3; i++) {
            final int t = i;
            targetBtns[i] = button(tn[i], v -> prefs.edit().putInt("rec_target", t).apply());
            targets.addView(targetBtns[i], weight());
            if (i < 2) targets.addView(space(6));
        }
        recCard.addView(targets, margins(0, 4, 0, 0));
        rxRecBtn = button("● RECORD", v -> toggleRxRecording());
        rxRecBtn.setPadding(0, dp(14), 0, dp(14));
        recCard.addView(rxRecBtn, margins(0, 10, 0, 0));
        rxPanel.addView(card(recCard), margins(0, 12, 0, 0));
        rxFolder = text("", 13, TEXT);
        rxPanel.addView(folderCard(rxFolder), margins(0, 12, 0, 0));
        Button disc = button("Disconnect", v -> RxService.stop(this));
        rxPanel.addView(disc, margins(0, 12, 0, 0));
        rxPanel.addView(text("Camera: headphone jack → TRRS-to-TRS cable → camera MIC input.", 12, DIM), margins(0, 12, 0, 0));
        root.addView(rxPanel);

        // --- webcam ---
        camPanel = col();
        camBtn = button("START WEBCAM", v -> toggleCam());
        camBtn.setTextSize(20);
        camBtn.setPadding(0, dp(22), 0, dp(22));
        camPanel.addView(camBtn);
        LinearLayout camCard = col();
        camCard.addView(text("Camera", 13, DIM));
        LinearLayout faceRow = row();
        String[] fn = {"Back (12 MP)", "Front (8 MP)"};
        for (int i = 0; i < 2; i++) {
            final String f = i == 0 ? "back" : "front";
            faceBtns[i] = button(fn[i], v -> camSet("camera", f));
            faceRow.addView(faceBtns[i], weight());
            if (i == 0) faceRow.addView(space(8));
        }
        camCard.addView(faceRow, margins(0, 4, 0, 0));
        camCard.addView(text("Resolution", 13, DIM), margins(0, 12, 0, 4));
        LinearLayout resRow = row();
        for (int i = 0; i < 2; i++) {
            final int h = i == 0 ? 1080 : 720;
            resBtns[i] = button(h + "p 30 fps", v -> camSet("res", String.valueOf(h)));
            resRow.addView(resBtns[i], weight());
            if (i == 0) resRow.addView(space(8));
        }
        camCard.addView(resRow);
        torchBtn = button("Light", v -> { CamService c = CamService.instance; if (c != null) camSet("torch", c.torch ? "off" : "on"); });
        camCard.addView(torchBtn, margins(0, 12, 0, 0));
        camPanel.addView(card(camCard), margins(0, 12, 0, 0));
        camInfo = text("", 14, TEXT);
        camPanel.addView(card(camInfo), margins(0, 12, 0, 0));
        camPanel.addView(text("On the PC: open OBS → Sources → + → Media Source (untick Local File, input http://127.0.0.1:"
                + CamService.PORT + "/video) → Start Virtual Camera. Then pick \"OBS Virtual Camera\" in Zoom / Meet / Teams.\n"
                + "USB (lowest delay): adb forward tcp:" + CamService.PORT + " tcp:" + CamService.PORT
                + "\nKeep the screen on or plug in the charger for long calls.", 12, DIM), margins(0, 12, 0, 0));
        root.addView(camPanel);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(root);
        return sv;
    }

    private void showTab(int tab) {
        prefs.edit().putInt("tab", tab).apply();
        txPanel.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        rxPanel.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        camPanel.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        Button[] t = {tabTx, tabRx, tabCam};
        for (int i = 0; i < 3; i++) style(t[i], i == tab ? BLUE : BTN, i == tab ? 0xFF001629 : TEXT);
        if (tab == 1) startDiscovery(); else stopDiscovery();
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
        ui.postDelayed(() -> { TxService t = TxService.instance; if (t != null) t.source = prefs.getInt("mic_src", 0); }, 200);
    }

    private void toggleCam() {
        if (CamService.instance != null) { CamService.stop(this); return; }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 2);
            return;
        }
        CamService.start(this);
    }

    /** Webcam setting: applied live when running, otherwise saved for the next start. */
    private void camSet(String key, String value) {
        CamService c = CamService.instance;
        if (c != null) { c.control("/ctl?" + key + "=" + value); return; }
        if (key.equals("camera")) prefs.edit().putString("cam_facing", value).apply();
        if (key.equals("res")) prefs.edit().putInt("cam_height", Integer.parseInt(value)).apply();
    }

    private void renderCam() {
        CamService c = CamService.instance;
        boolean on = c != null;
        String face = on ? c.facing : prefs.getString("cam_facing", "back");
        int h = on ? c.height : prefs.getInt("cam_height", 1080);
        camBtn.setText(on ? "STOP WEBCAM" : "START WEBCAM");
        style(camBtn, on ? RED : GREEN, on ? 0xFFFFFFFF : 0xFF06200F);
        for (int i = 0; i < 2; i++) {
            boolean sel = (i == 0) == "back".equals(face);
            style(faceBtns[i], sel ? BLUE : BTN, sel ? 0xFF001629 : TEXT);
            sel = (i == 0) == (h == 1080);
            style(resBtns[i], sel ? BLUE : BTN, sel ? 0xFF001629 : TEXT);
        }
        boolean lit = on && c.torch;
        torchBtn.setText(lit ? "Light: on" : "Light: off");
        style(torchBtn, lit ? BLUE : BTN, lit ? 0xFF001629 : TEXT);
        if (!on) { camInfo.setText("Webcam is off."); return; }
        String ip = TxService.wifiIp();
        camInfo.setText(String.format(java.util.Locale.US, "%s camera · %dp · %.0f fps · %d viewer%s%s\nWi-Fi: http://%s:%d/video\nUSB:   http://127.0.0.1:%d/video",
                face, h, c.fps, c.clients, c.clients == 1 ? "" : "s", c.error != null ? "\n⚠ " + c.error : "",
                ip == null ? "(no Wi-Fi)" : ip, CamService.PORT, CamService.PORT));
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

    /** Start/stop recording on the mic phone, this phone, or both (receiver's "Record on" choice). */
    private void toggleRxRecording() {
        RxService r = RxService.instance;
        if (r == null) return;
        boolean micRec = r.txStatus != null && r.txStatus.optBoolean("rec"), localRec = r.rec.active();
        if (micRec || localRec) {                                   // stop whatever is running
            if (micRec) r.send("{\"rec\":false}");
            r.setLocalRecording(false);
            return;
        }
        int target = prefs.getInt("rec_target", 0);
        if (target != 1) r.send("{\"rec\":true}");
        if (target != 0) {
            String err = r.setLocalRecording(true);
            if (err != null) rxStatus.setText("Cannot record here: " + err);
        }
    }

    private View folderCard(TextView label) {
        LinearLayout c = col();
        c.addView(text("Recordings are saved to", 13, DIM));
        c.addView(label, margins(0, 4, 0, 0));
        LinearLayout r = row();
        r.addView(button("Change folder", v -> {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE);
            try { startActivityForResult(i, PICK_FOLDER); } catch (Exception e) { label.setText("No folder picker on this phone"); }
        }), weight());
        r.addView(space(8));
        r.addView(button("Default", v -> RecStore.setTree(this, null)), weight());
        c.addView(r, margins(0, 10, 0, 0));
        return card(c);
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_FOLDER || res != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri tree = data.getData();
        getContentResolver().takePersistableUriPermission(tree,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        RecStore.setTree(this, tree);
    }

    private void remote(String what) {
        RxService r = RxService.instance;
        if (r == null || r.txStatus == null) return;
        r.send("{\"" + what + "\":" + !r.txStatus.optBoolean(what) + "}");
    }

    private void askPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.BLUETOOTH_CONNECT}, 1);
        else if (Build.VERSION.SDK_INT >= 31)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT}, 1);
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
            String folder = RecStore.describe(MainActivity.this);
            txFolder.setText(folder);
            rxFolder.setText(folder);
            renderTx();
            renderRx();
            renderCam();
            ui.postDelayed(this, 150);
        }
    };

    private void renderTx() {
        TxService t = TxService.instance;
        boolean on = t != null;
        startBtn.setText(on ? "■ STOP MIC" : "▶ START MIC");
        style(startBtn, on ? BTN : GREEN, on ? RED : DARK_GREEN);
        airBtn.setVisibility(on ? View.VISIBLE : View.GONE);
        int want = on ? t.source : prefs.getInt("mic_src", 0);
        for (int i = 0; i < 2; i++) style(srcBtns[i], want == i ? BLUE : BTN, want == i ? 0xFF001629 : TEXT);
        if (!on) srcInfo.setText(want == 1 ? "Pair & connect the headset in Android Bluetooth settings first. Voice quality: 16 kHz (mSBC) or 8 kHz." : "Built-in microphone (or a wired TRRS lav mic), 48 kHz.");
        else if (t.activeSource == TxService.SRC_BT) srcInfo.setText("● Bluetooth headset mic " + t.btState + " — wear it like a lav mic");
        else if (want == 1) srcInfo.setText("Bluetooth: " + t.btState + "…");
        else srcInfo.setText("Using the phone's microphone");
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
        recInfo.setText(r ? t.rec.name() + "  (L main · R safety −6 dB)" : "");
        int rx = t.receivers();
        txStats.setText(String.format(Locale.US, "%s   ·   Wi-Fi %s   ·   %d receiver%s   ·   %s on air\nLevel %s",
                t.battery < 0 ? "–" : t.battery + "%" + (t.charging ? "⚡" : ""), t.rssi == 0 ? "–" : t.rssi + " dBm",
                rx, rx == 1 ? "" : "s", mmss((System.currentTimeMillis() - t.startedAt) / 1000.0),
                t.mute ? "muted" : Math.round(t.peakDb) + " dBFS" + (t.clip ? "  CLIP – lower gain" : "")));
        if (!gainBar.isPressed()) gainBar.setProgress(Math.round(t.gainDb) + 12);
        gainLabel.setText("Mic gain " + Math.round(t.gainDb) + " dB");
        for (int i = 0; i < 3; i++) style(nsBtns[i], t.ns == i ? GREEN : BTN, t.ns == i ? DARK_GREEN : TEXT);
        for (int i = 0; i < 3; i++) style(engBtns[i], t.engine == i ? BLUE : BTN, t.engine == i ? 0xFF001629 : TEXT);
        String eng = t.activeSource == TxService.SRC_BT ? "Bluetooth headset (its own NC) + App DSP"
                : t.activeEngine == TxService.ENGINE_DUAL ? (t.dualOk ? "Dual-mic active · primary: " + t.primaryMic + " mic · mics differ "
                        + String.format(Locale.US, "%.1f dB", t.micDiffDb) : "Dual-mic: waiting for two different mic signals…")
                : t.activeEngine == TxService.ENGINE_PHONE ? "Phone engine: " + t.engineNote : "App DSP (single mic)";
        ncInfo.setText(eng + String.format(Locale.US, "\nNoise floor %d dBFS · removing %.1f dB right now", Math.round(t.noiseFloorDb), -t.reductionDb));
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
        boolean local = r.rec.active();
        String where = rec && local ? "both phones" : rec ? "mic phone" : "this phone";
        rxRecBtn.setText(rec || local ? "■ STOP RECORDING (" + where + ")  " + mmss(local ? r.rec.seconds() : s.optDouble("rec_secs"))
                : "● RECORD");
        style(rxRecBtn, rec || local ? RED : BTN, rec || local ? Color.WHITE : TEXT);
        int target = prefs.getInt("rec_target", 0);
        for (int i = 0; i < 3; i++) style(targetBtns[i], target == i ? BLUE : BTN, target == i ? 0xFF001629 : TEXT);
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
