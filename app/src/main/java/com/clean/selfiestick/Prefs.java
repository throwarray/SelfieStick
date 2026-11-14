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

    static boolean bootEnabled(Context c) { return p(c).getBoolean("boot", false); }

    static void setBootEnabled(Context c, boolean on) { p(c).edit().putBoolean("boot", on).apply(); }

    /** Trace every adb segment and run step to logcat. Off by default. */
    static boolean debugLog(Context c) { return p(c).getBoolean("debugLog", false); }

    static void setDebugLog(Context c, boolean on) { p(c).edit().putBoolean("debugLog", on).apply(); }

    /** Learned: this device's adbd only answers through the wide VPN (every uid enters the tun). */
    static boolean wideVpn(Context c) { return p(c).getBoolean("wide", false); }

    static void setWideVpn(Context c, boolean on) { p(c).edit().putBoolean("wide", on).apply(); }
}
