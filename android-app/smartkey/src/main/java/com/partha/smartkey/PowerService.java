package com.partha.smartkey;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Accessibility service used only to open the system power menu, a replacement for a broken power
 * button. It ignores all accessibility events and never reads window content.
 */
public class PowerService extends AccessibilityService {
    static volatile PowerService instance;

    @Override protected void onServiceConnected() { instance = this; }
    @Override public boolean onUnbind(android.content.Intent i) { instance = null; return super.onUnbind(i); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    static boolean powerMenu() {
        PowerService s = instance;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
    }
}
