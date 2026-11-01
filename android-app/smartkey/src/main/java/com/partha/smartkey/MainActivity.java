package com.partha.smartkey;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Settings screen: pick an action for single press, double press and press-and-hold. */
public class MainActivity extends Activity {
    static final int BG = 0xFF0E1116, CARD = 0xFF171B22, LINE = 0xFF262C36, TEXT = 0xFFE8ECF2, DIM = 0xFF8B95A5,
            BLUE = 0xFF3EA6FF;
    private static final String[][] PRESSES = {
            {Actions.CLICK, "Single press"}, {Actions.DOUBLE, "Double press"}, {Actions.LONG, "Press & hold"}};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Button[] pick = new Button[3];
    private TextView last, powerStatus;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(Actions.PREFS, MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        root.setBackgroundColor(BG);
        TextView title = text("Smart Key", 24, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(text("Press the fingerprint sensor (it clicks) to run an action, even with the screen locked.", 14, DIM),
                lp(0, 6));

        for (int i = 0; i < 3; i++) {
            final String press = PRESSES[i][0];
            LinearLayout c = card();
            c.addView(text(PRESSES[i][1], 13, DIM));
            pick[i] = button("", v -> choose(press));
            c.addView(pick[i], lp(0, 6));
            root.addView(c, lp(0, 12));
        }

        LinearLayout pw = card();
        pw.addView(text("Power button replacement", 13, DIM));
        powerStatus = text("", 14, TEXT);
        pw.addView(powerStatus, lp(0, 4));
        pw.addView(button("Use press & hold as power button", v -> {
            Actions.set(this, Actions.LONG, Actions.POWER_MENU);
            if (PowerService.instance == null) {
                startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                android.widget.Toast.makeText(this, "Turn on 'Smart Key power button'", android.widget.Toast.LENGTH_LONG).show();
            }
        }), lp(0, 8));
        pw.addView(button("Allow screen off", v -> {
            Intent i = new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, new android.content.ComponentName(this, LockAdmin.class))
                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets the fingerprint sensor turn the screen off.");
            startActivity(i);
        }), lp(0, 8));
        root.addView(pw, lp(0, 12));

        LinearLayout opt = card();
        Switch vib = new Switch(this);
        vib.setText("Vibrate on press");
        vib.setTextColor(TEXT);
        vib.setChecked(prefs.getBoolean("vibrate", true));
        vib.setOnCheckedChangeListener((s, on) -> prefs.edit().putBoolean("vibrate", on).apply());
        opt.addView(vib);
        root.addView(opt, lp(0, 12));

        LinearLayout lc = card();
        lc.addView(text("Last press detected", 13, DIM));
        last = text("Press the sensor to test…", 15, TEXT);
        lc.addView(last, lp(0, 4));
        root.addView(lc, lp(0, 12));

        root.addView(text("Swipe gestures (notification panel, photos) are in Settings → Fingerprint ID.", 12, DIM), lp(0, 12));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(root);
        setContentView(sv);
    }

    @Override protected void onResume() { super.onResume(); ui.post(refresh); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(refresh); }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            for (int i = 0; i < 3; i++) pick[i].setText(Actions.label(MainActivity.this, Actions.get(MainActivity.this, PRESSES[i][0])));
            last.setText(prefs.getString("last", "Press the sensor to test…"));
            boolean svc = PowerService.instance != null;
            boolean admin = ((android.app.admin.DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE))
                    .isAdminActive(new android.content.ComponentName(MainActivity.this, LockAdmin.class));
            powerStatus.setText((svc ? "✓ Power menu ready" : "✗ Power menu: not enabled yet")
                    + "\n" + (admin ? "✓ Screen off ready" : "✗ Screen off: not allowed yet")
                    + "\nTo wake the phone, touch the fingerprint sensor (it unlocks from screen off).");
            ui.postDelayed(this, 500);
        }
    };

    /** List: built-in actions first, then every launchable app. */
    private void choose(String press) {
        final List<String> labels = new ArrayList<>(), ids = new ArrayList<>();
        for (String[] a : Actions.BUILT_IN) { labels.add(a[0]); ids.add(a[1]); }
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(launcher, 0);
        List<String[]> appRows = new ArrayList<>();
        for (ResolveInfo r : apps) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            appRows.add(new String[]{"Open " + r.loadLabel(getPackageManager()), Actions.APP + pkg});
        }
        Collections.sort(appRows, (a, c) -> a[0].compareToIgnoreCase(c[0]));
        for (String[] a : appRows) { labels.add(a[0]); ids.add(a[1]); }
        new AlertDialog.Builder(this)
                .setTitle("Action")
                .setItems(labels.toArray(new String[0]), (d, which) -> Actions.set(this, press, ids.get(which)))
                .show();
    }

    // ---- small UI helpers ----

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private LinearLayout.LayoutParams lp(int l, int t) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(l), dp(t), 0, 0);
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
        b.setTextSize(16);
        b.setTextColor(0xFF001629);
        b.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setColor(BLUE);
        d.setCornerRadius(dp(14));
        b.setBackground(d);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable d = new GradientDrawable();
        d.setColor(CARD);
        d.setCornerRadius(dp(14));
        d.setStroke(dp(1), LINE);
        c.setBackground(d);
        return c;
    }
}
