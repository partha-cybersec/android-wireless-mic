package com.partha.wirelessmic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Remote control from the Smart Key app (fingerprint-sensor press). Protected by a signature
 * permission, so only apps signed with the same key can send these commands.
 */
public class ControlReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        TxService t = TxService.instance;
        if ("toggle_mic".equals(cmd)) {
            if (t != null) TxService.stop(c); else TxService.start(c);
            toast(c, t != null ? "Wireless Mic stopped" : "Wireless Mic on air");
            return;
        }
        if (t == null) { toast(c, "Wireless Mic is not running"); return; }
        if ("toggle_mute".equals(cmd)) {
            t.mute = !t.mute;
            toast(c, t.mute ? "Mic MUTED" : "Mic ON AIR");
        } else if ("toggle_rec".equals(cmd)) {
            boolean on = !t.rec.active();
            t.setRecording(on);
            toast(c, on ? "Recording started" : "Recording saved");
        }
    }

    private static void toast(Context c, String s) { Toast.makeText(c, s, Toast.LENGTH_SHORT).show(); }
}
