package com.clean.selfiestick;

import android.content.Context;
import android.content.SharedPreferences;

/** The user settings, kept in this app's private storage (SharedPreferences). */
final class Prefs {
    private Prefs() {}

    static final int DEFAULT_PORT = 5555;          // adbd on the device
    static final int DEFAULT_LOCAL_PORT = 5556;    // our end, on the fake address 10.99.0.2
    private static final String FILE = "selfiestick";

    private static SharedPreferences p(Context c) { return c.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    static int port(Context c) { return p(c).getInt("port", DEFAULT_PORT); }

    static int localPort(Context c) { return p(c).getInt("localPort", DEFAULT_LOCAL_PORT); }

    static String command(Context c) { return p(c).getString("command", ""); }

    static void save(Context c, int localPort, int port, String command) {
        p(c).edit().putInt("localPort", localPort).putInt("port", port).putString("command", command).apply();
    }

    /** Kernel boot id: new on every boot, so "already done this boot" survives a restarted process but not a reboot. */
    static String bootId() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/sys/kernel/random/boot_id"))) {
            String l = r.readLine();
            return l == null ? "" : l.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** adb has already been reached by a run in this boot (boot or manual): the boot triggers must not run the command again. */
    static boolean bootRunDone(Context c) {
        String id = bootId();
        return !id.isEmpty() && id.equals(p(c).getString("doneBoot", ""));
    }

    static void setBootRunDone(Context c) { p(c).edit().putString("doneBoot", bootId()).apply(); }

    /** Counts a boot-run attempt in this boot; false once max attempts were used (failed ones included), so triggers cannot loop. */
    static boolean takeBootAttempt(Context c, int max) {
        String id = bootId();
        if (id.isEmpty()) return true;
        SharedPreferences sp = p(c);
        int n = id.equals(sp.getString("attemptBoot", "")) ? sp.getInt("attempts", 0) : 0;
        if (n >= max) return false;
        sp.edit().putString("attemptBoot", id).putInt("attempts", n + 1).apply();
        return true;
    }

    static boolean bootEnabled(Context c) { return p(c).getBoolean("boot", false); }

    static void setBootEnabled(Context c, boolean on) { p(c).edit().putBoolean("boot", on).apply(); }

    /** Trace every adb segment and run step to logcat. Off by default. */
    static boolean debugLog(Context c) { return p(c).getBoolean("debugLog", false); }

    static void setDebugLog(Context c, boolean on) { p(c).edit().putBoolean("debugLog", on).apply(); }

    /** Learned: this device's adbd only answers through the wide VPN (every uid enters the tun). */
    static boolean wideVpn(Context c) { return p(c).getBoolean("wide", false); }

    static void setWideVpn(Context c, boolean on) { p(c).edit().putBoolean("wide", on).apply(); }

    /** User switch: may the wide VPN be used at all. On by default. Off means never, even if learned. */
    static boolean wideFallback(Context c) { return p(c).getBoolean("wideFallback", true); }

    static void setWideFallback(Context c, boolean on) { p(c).edit().putBoolean("wideFallback", on).apply(); }

    /** User switch: talk to adbd on 127.0.0.1 with a plain socket instead of through the tun. Off by default. */
    static boolean localhost(Context c) { return p(c).getBoolean("localhost", false); }

    static void setLocalhost(Context c, boolean on) { p(c).edit().putBoolean("localhost", on).apply(); }

    /** User switch: grant WRITE_SECURE_SETTINGS over the app's own adb connection, and turn adb on at run start. Off by default. */
    static boolean secureToggle(Context c) { return p(c).getBoolean("secureToggle", false); }

    static void setSecureToggle(Context c, boolean on) { p(c).edit().putBoolean("secureToggle", on).apply(); }

    /** User switch: add the early-start accessibility service to the enabled list: directly when the secure permission is held, else over adb at the next connect. Off by default. */
    static boolean a11yToggle(Context c) { return p(c).getBoolean("a11yToggle", false); }

    static void setA11yToggle(Context c, boolean on) { p(c).edit().putBoolean("a11yToggle", on).apply(); }

    /** User switch: turn on the adb setting (adb_enabled) at run start, if the permission is held. Off by default. */
    static boolean adbToggle(Context c) { return p(c).getBoolean("adbToggle", false); }

    static void setAdbToggle(Context c, boolean on) { p(c).edit().putBoolean("adbToggle", on).apply(); }

    /** User switch: turn on the wireless adb setting (adb_wifi_enabled) at run start, if the permission is held. Off by default. */
    static boolean wifiToggle(Context c) { return p(c).getBoolean("wifiToggle", false); }

    static void setWifiToggle(Context c, boolean on) { p(c).edit().putBoolean("wifiToggle", on).apply(); }
}
