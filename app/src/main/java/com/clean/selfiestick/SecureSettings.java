package com.clean.selfiestick;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * The optional system-setting writes. They need WRITE_SECURE_SETTINGS, which this app gets from a shell: the
 * "write secure settings" toggle runs pm grant over the app's own adb connection. Every write is guarded by its
 * toggle and by the permission, and does nothing otherwise.
 */
final class SecureSettings {
    private SecureSettings() {}

    static final String PERM = "android.permission.WRITE_SECURE_SETTINGS";
    static final String TAG = "SelfieStick";
    private static final String ENABLED_SERVICES = "enabled_accessibility_services";
    private static final String ACCESSIBILITY_ON = "accessibility_enabled";

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED;
    }

    /** Turns on the adb and/or wireless adb settings that the user asked for, if they are off. */
    static boolean ensureAdb(Context c, boolean adb, boolean wifi) {
        if (!hasPermission(c)) return false;
        boolean changed = false;
        if (adb) changed |= putGlobalIfDiffers(c, "adb_enabled", "1");
        if (wifi) changed |= putGlobalIfDiffers(c, "adb_wifi_enabled", "1");
        return changed;
    }

    private static boolean putGlobalIfDiffers(Context c, String key, String value) {
        ContentResolver cr = c.getContentResolver();
        if (value.equals(Settings.Global.getString(cr, key))) return false;
        boolean ok = Settings.Global.putString(cr, key, value);
        if (!ok) Log.w(TAG, "could not set " + key);
        return ok;
    }

    /**
     * Adds (on) or removes (off) only this app's early-start service in the enabled accessibility list.
     * Every other entry is kept, in its order. Nothing is written when the list is already as wanted, so opening the
     * app does not make the system re-read the setting. Turning it off does not touch the accessibility master switch.
     */
    static boolean setEarlyStartAccessibility(Context c, boolean on) {
        if (!hasPermission(c)) return false;
        ContentResolver cr = c.getContentResolver();
        String me = new ComponentName(c, WarmService.class).flattenToString();
        String cur = Settings.Secure.getString(cr, ENABLED_SERVICES);
        List<String> items = new ArrayList<>();
        boolean listed = false;
        if (cur != null) {
            for (String part : cur.split(":")) {
                String t = part.trim();
                if (t.isEmpty()) continue;
                if (t.equals(me)) listed = true; else items.add(t);
            }
        }
        boolean changed = false;
        if (on != listed) {
            if (on) items.add(me);
            changed = Settings.Secure.putString(cr, ENABLED_SERVICES, join(items));
            if (!changed) Log.w(TAG, "could not write " + ENABLED_SERVICES);
        }
        if (on && !"1".equals(Settings.Secure.getString(cr, ACCESSIBILITY_ON))) {
            changed |= Settings.Secure.putString(cr, ACCESSIBILITY_ON, "1");
        }
        return changed;
    }

    /**
     * An older build of this app could leave itself set as the always-on VPN. The system then starts the VPN service on
     * its own. If (and only if) the always-on app is this app and the permission is held, clear it.
     */
    static void clearOwnAlwaysOn(Context c) {
        if (!hasPermission(c)) return;
        ContentResolver cr = c.getContentResolver();
        if (c.getPackageName().equals(Settings.Secure.getString(cr, "always_on_vpn_app"))) {
            Settings.Secure.putString(cr, "always_on_vpn_app", null);
            Log.i(TAG, "cleared a leftover always-on VPN entry that pointed at this app");
        }
    }

    /**
     * The shell command that enables this app's early-start service, for when the app does not hold
     * WRITE_SECURE_SETTINGS but is connected to adbd (the shell user can write secure settings). Keeps every other
     * entry. Null when nothing needs writing, or the current list cannot be quoted safely.
     */
    static String earlyStartShellCommand(Context c) {
        ContentResolver cr = c.getContentResolver();
        String me = new ComponentName(c, WarmService.class).flattenToString();
        boolean listed = earlyStartListed(c);
        boolean masterOn = "1".equals(Settings.Secure.getString(cr, ACCESSIBILITY_ON));
        if (listed && masterOn) return null;
        String cur = Settings.Secure.getString(cr, ENABLED_SERVICES);
        boolean empty = cur == null || cur.trim().isEmpty() || "null".equals(cur.trim());
        String list = empty ? me : (listed ? cur.trim() : cur.trim() + ":" + me);
        if (list.indexOf('\'') >= 0) return null;
        StringBuilder b = new StringBuilder();
        if (!listed) b.append("settings put secure ").append(ENABLED_SERVICES).append(" '").append(list).append("'; ");
        if (!masterOn) b.append("settings put secure ").append(ACCESSIBILITY_ON).append(" 1");
        return b.toString();
    }

    /** Whether this app's early-start entry is in the enabled list right now. */
    static boolean earlyStartListed(Context c) {
        String me = new ComponentName(c, WarmService.class).flattenToString();
        String cur = Settings.Secure.getString(c.getContentResolver(), ENABLED_SERVICES);
        if (cur == null) return false;
        for (String part : cur.split(":")) if (part.trim().equals(me)) return true;
        return false;
    }

    private static String join(List<String> items) {
        StringBuilder b = new StringBuilder();
        for (String s : items) { if (b.length() > 0) b.append(':'); b.append(s); }
        return b.toString();
    }
}
