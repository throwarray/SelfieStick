package com.clean.selfiestick;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the boot run at BOOT_COMPLETED, unless the early start already did (see WarmService). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {   // an update drops scheduled jobs
            if (Prefs.bootEnabled(c)) BootJob.schedule(c);
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)) return;
        if (Prefs.bootEnabled(c)) BootJob.schedule(c);
        if (Prefs.debugLog(c)) android.util.Log.i("SelfieStick", "boot broadcast received at t+" + android.os.SystemClock.elapsedRealtime() + "ms");
        BridgeService.startBootRun(c, "BOOT_COMPLETED");
    }
}
