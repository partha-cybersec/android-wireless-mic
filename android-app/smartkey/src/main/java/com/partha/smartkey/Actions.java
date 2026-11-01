package com.partha.smartkey;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.widget.Toast;

/** What each Smart Key press does. Action ids are stored in SharedPreferences per press type. */
final class Actions {
    static final String PREFS = "smartkey";
    static final String CLICK = "click", DOUBLE = "double", LONG = "long";

    static final String NONE = "none", TORCH = "torch", CAMERA = "camera", MEDIA = "media", WIFI = "wifi",
            MIC_MUTE = "mic_mute", MIC_REC = "mic_rec", MIC_START = "mic_start", APP = "app:";

    /** Built-in choices shown in the picker (label, id). "Open app…" is added separately. */
    static final String[][] BUILT_IN = {
            {"Do nothing", NONE},
            {"Flashlight on/off", TORCH},
            {"Open camera", CAMERA},
            {"Play / pause music", MEDIA},
            {"Wi-Fi on/off", WIFI},
            {"Wireless Mic: start/stop mic", MIC_START},
            {"Wireless Mic: mute/unmute", MIC_MUTE},
            {"Wireless Mic: start/stop recording", MIC_REC},
    };

    private static boolean torchOn;

    static String get(Context c, String press) {
        String def = CLICK.equals(press) ? TORCH : DOUBLE.equals(press) ? CAMERA : MIC_MUTE;
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("act_" + press, def);
    }

    static void set(Context c, String press, String action) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("act_" + press, action).apply();
    }

    static String label(Context c, String action) {
        for (String[] b : BUILT_IN) if (b[1].equals(action)) return b[0];
        if (action.startsWith(APP)) {
            try {
                String pkg = action.substring(APP.length());
                return "Open " + c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0));
            } catch (Exception e) { return "Open app (not installed)"; }
        }
        return action;
    }

    static void run(Context c, String action) {
        try {
            buzz(c);
            switch (action) {
                case NONE: return;
                case TORCH: torch(c); return;
                case CAMERA:
                    c.startActivity(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                case MEDIA: media(c); return;
                case WIFI: {
                    WifiManager w = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    boolean on = !w.isWifiEnabled();
                    w.setWifiEnabled(on);
                    toast(c, "Wi-Fi " + (on ? "on" : "off"));
                    return;
                }
                case MIC_START: case MIC_MUTE: case MIC_REC:
                    // the Wireless Mic app listens for this (explicit package, so only it receives it)
                    c.sendBroadcast(new Intent("com.partha.wirelessmic.CONTROL").setPackage("com.partha.wirelessmic")
                            .putExtra("cmd", MIC_START.equals(action) ? "toggle_mic" : MIC_MUTE.equals(action) ? "toggle_mute" : "toggle_rec"));
                    return;
                default:
                    if (action.startsWith(APP)) {
                        Intent i = c.getPackageManager().getLaunchIntentForPackage(action.substring(APP.length()));
                        if (i != null) c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
            }
        } catch (Exception e) {
            toast(c, "Smart Key: " + e.getMessage());
        }
    }

    private static void torch(Context c) throws Exception {
        CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        for (String id : cm.getCameraIdList()) {
            Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (Boolean.TRUE.equals(flash) && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                torchOn = !torchOn;
                cm.setTorchMode(id, torchOn);
                return;
            }
        }
        toast(c, "No flashlight found");
    }

    private static void media(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
    }

    private static void buzz(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("vibrate", true)) return;
        Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) v.vibrate(25);
    }

    private static void toast(Context c, String s) { Toast.makeText(c, s, Toast.LENGTH_SHORT).show(); }

    private Actions() {}
}
