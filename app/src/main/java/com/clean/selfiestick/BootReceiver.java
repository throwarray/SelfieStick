package com.clean.selfiestick;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Starts one connect-and-run at boot, if the user switched "Connect on boot" on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i == null || !Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        if (!Prefs.bootEnabled(c)) return;
        Intent s = new Intent(c, BridgeService.class)
            .setAction(BridgeService.ACTION_CONNECT)
            .putExtra(BridgeService.EXTRA_BOOT, true);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s); else c.startService(s);
    }
}
