package com.clean.selfiestick;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * Optional, enabled by the user in Settings > Accessibility. Android starts enabled accessibility services before
 * BOOT_COMPLETED, so this process is already running when the boot run is due, and that run starts sooner.
 * It reads no screen content and receives no events. Without it, the BOOT_COMPLETED receiver starts the run as before.
 * When the auto-enable toggle is on, the app adds only its own entry to the enabled list; other services are left alone.
 */
public class WarmService extends AccessibilityService {
    /** Only near boot. A later reconnect of this service (the user toggling it) must not start a run. */
    static final long EARLY_WINDOW_MS = 5 * 60 * 1000;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (SystemClock.elapsedRealtime() > EARLY_WINDOW_MS) return;
        if (BridgeService.uiSeen) return;   // connected because the app was opened (it restores its entry), not by a boot
        if (MiniTcp.trace) android.util.Log.i("SelfieStick", "early start from accessibility at t+" + SystemClock.elapsedRealtime() + "ms");
        BridgeService.startBootRun(this, "early start (accessibility)");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // no events are requested (see res/xml/warm_accessibility.xml)
    }

    @Override
    public void onInterrupt() {
        // nothing to interrupt
    }
}
