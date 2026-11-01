package com.partha.smartkey;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.util.Locale;

/**
 * Receives Huawei's "com.huawei.smartkey" broadcast (sent by HwPhoneWindowManager when the
 * fingerprint sensor is pressed) and runs the action the user assigned to that press type.
 */
public class SmartKeyReceiver extends BroadcastReceiver {
    static final String TAG = "SmartKey";

    @Override
    public void onReceive(Context c, Intent intent) {
        String extras = describe(intent.getExtras());
        // HwPhoneWindowManager sends command = Click | DoubleClick | LongPress (seen on Honor 8, EMUI 5)
        String cmd = intent.getStringExtra("command");
        String press = "DoubleClick".equals(cmd) ? Actions.DOUBLE : "LongPress".equals(cmd) ? Actions.LONG
                : "Click".equals(cmd) ? Actions.CLICK : classify(extras);
        Log.i(TAG, "smartkey " + press + " extras=" + extras);
        c.getSharedPreferences(Actions.PREFS, Context.MODE_PRIVATE).edit()
                .putString("last", press + "  ·  " + new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date()))
                .putString("last_extras", extras)
                .apply();
        Actions.run(c, Actions.get(c, press));
    }

    /** Huawei puts the press type in an extra; match it by value so the exact key name does not matter. */
    static String classify(String extras) {
        String e = extras.toLowerCase(Locale.US);
        if (e.contains("double")) return Actions.DOUBLE;
        if (e.contains("long") || e.contains("hold")) return Actions.LONG;
        return Actions.CLICK;
    }

    static String describe(Bundle b) {
        if (b == null) return "{}";
        StringBuilder sb = new StringBuilder("{");
        for (String k : b.keySet()) sb.append(k).append('=').append(b.get(k)).append(", ");
        return sb.append('}').toString();
    }
}
