package com.clean.selfiestick;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Runs one shell command on this device's own adbd, then stops. Two ways to reach adbd:
 * - tun (default): a VPN that routes only 10.99.0.2 into a tun, and talks to 10.99.0.1 through it (MiniTcp).
 * - localhost (switch): a plain TCP socket to 127.0.0.1, with no VPN at all (SocketTransport).
 * Android allows one VPN at a time, so the tun mode takes over from whatever VPN is active and does not restore it.
 */
public class BridgeService extends VpnService {
    static final String ACTION_CONNECT = "com.clean.selfiestick.CONNECT";
    static final String ACTION_DISCONNECT = "com.clean.selfiestick.DISCONNECT";
    static final String EXTRA_BOOT = "boot";
    static final String CHANNEL = "selfiestick";
    static final int NOTE_ID = 1;
    /** Visible (not minimised) notifications that tell the user a boot run was requested, failed to start, or finished. */
    static final String EVENT_CHANNEL = "selfiestick_events";
    static final int EVENT_ID = 2;

    public interface Listener { void onStatus(String text, boolean busy); }

    static volatile boolean running;
    /** Android took our VPN away (another VPN app was prepared/connected). Reset per run. */
    static volatile boolean revoked;
    /** The user pressed Disconnect. */
    static volatile boolean cancelled;
    static volatile String status = "Disconnected";
    static volatile Listener listener;
    static volatile MiniTcp activeTcp;
    /** The UI was opened in this process. The early start must not fire after that: opening the app is not a boot. */
    static volatile boolean uiSeen;
    /** Set once a boot run has got adb working in this process. A failed attempt does not set it, so a later trigger retries. */
    private static volatile boolean bootDone;

    private static final Handler main = new Handler(Looper.getMainLooper());

    public static void setListener(Listener l) { listener = l; }

    static void report(final String text, final boolean busy) {
        status = text;
        final Listener l = listener;
        if (l != null) main.post(new Runnable() { public void run() { l.onStatus(text, busy); } });
    }

    /**
     * Starts the boot run if Connect on boot is on, no run is in progress, and no boot run has succeeded yet.
     * Called by the early start (accessibility) and by BOOT_COMPLETED; whichever comes first runs, the other retries
     * only if the first did not get adb working.
     */
    static void startBootRun(android.content.Context c, String who) {
        if (!Prefs.bootEnabled(c) || running || bootDone || Prefs.bootRunDone(c)) return;
        if (!Prefs.takeBootAttempt(c, 4)) return;   // at most 4 tries per boot over all triggers, so a failing setup cannot loop
        android.util.Log.i("SelfieStick", "boot run requested by " + who + " at t+" + SystemClock.elapsedRealtime() + "ms");
        notifyEvent(c, "Boot run requested by " + who);
        Intent s = new Intent(c, BridgeService.class).setAction(ACTION_CONNECT).putExtra(EXTRA_BOOT, true);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s); else c.startService(s);
        } catch (RuntimeException e) {
            android.util.Log.w("SelfieStick", "boot run not started: " + e.getMessage());
            notifyEvent(c, "Boot run not started: " + e.getMessage());
        }
    }

    /** Posts (or replaces) the one visible event notification. Never throws: a blocked notification must not stop a run. */
    static void notifyEvent(android.content.Context c, String text) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(EVENT_CHANNEL, "SelfieStick status", NotificationManager.IMPORTANCE_LOW));
                b = new Notification.Builder(c, EVENT_CHANNEL);
            } else {
                b = new Notification.Builder(c);
            }
            b.setContentTitle("SelfieStick").setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(open).setAutoCancel(true);
            nm.notify(EVENT_ID, b.build());
        } catch (RuntimeException e) {
            android.util.Log.w("SelfieStick", "status notification not shown: " + e.getMessage());
        }
    }

    /** Timestamped trace (ms since boot, the same clock as logcat's elapsed time) when the debug log is on. */
    static void mark(String what) {
        if (MiniTcp.trace) android.util.Log.i("SelfieStick", "t+" + SystemClock.elapsedRealtime() + "ms " + what);
    }

    @Override
    public void onRevoke() {
        revoked = true;
        android.util.Log.w("SelfieStick", "VPN revoked: another VPN app took over");
        MiniTcp t = activeTcp;
        if (t != null) t.abort();
        super.onRevoke();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_DISCONNECT.equals(action)) {
            cancelled = true;
            MiniTcp t = activeTcp;
            if (t != null) t.abort();   // fails any wait at once; the run's finally block does the teardown
            if (!running) finishSelf();
            return START_NOT_STICKY;
        }

        // Only our own CONNECT starts a run. The system also starts a VpnService by itself (when it is the always-on
        // VPN, with action android.net.VpnService, or with a null intent after a restart): that must not open a connection.
        if (!ACTION_CONNECT.equals(action)) {
            android.util.Log.i("SelfieStick", "ignored service start, action=" + action);
            startForegroundNote();   // a started VPN service has to go foreground promptly, even if it stops right away
            if (!running) finishSelf();
            return START_NOT_STICKY;
        }

        // Must call startForeground promptly after a startForegroundService() call, even if we stop right after.
        startForegroundNote();

        // A run is already in progress: it owns the connection, so do not start a second one.
        if (running) return START_NOT_STICKY;

        running = true;
        revoked = false;
        cancelled = false;
        final boolean boot = intent != null && intent.getBooleanExtra(EXTRA_BOOT, false);
        final String cmd = Prefs.command(this);
        final int port = Prefs.port(this);
        final int localPort = Prefs.localPort(this);
        MiniTcp.trace = Prefs.debugLog(this);
        report("Starting\u2026", true);
        new Thread(new Runnable() { public void run() { runOnce(cmd, localPort, port, boot); } }, "selfiestick").start();
        return START_NOT_STICKY;
    }

    private void runOnce(String cmd, int localPort, int port, boolean boot) {
        boolean localhost = Prefs.localhost(this);
        mark("run start (boot=" + boot + ", localhost=" + localhost + ") adbd port " + port);
        String where = localhost ? "127.0.0.1:" + port : "10.99.0.1:" + port;
        String result;
        ParcelFileDescriptor tun = null;
        TunReader reader = null;
        Thread rt = null;
        MiniTcp tcp = null;
        AdbTransport transport = null;
        boolean wideUsed = false;
        try {
            prepareRun();
            if (localhost) {
                report("Connecting to adb on localhost port " + port + "\u2026", true);
                transport = new SocketTransport(port);
                mark("localhost connected");
            } else {
                // Narrow VPN (only this app and the shell uid enter the tun) first; wide only as a fallback, if the user allows it.
                boolean wideAllowed = Prefs.wideFallback(this);
                boolean wide = wideAllowed && Prefs.wideVpn(this), first = true;
                for (;;) {
                    // the wide retry never prepares again: another VPN that took over meanwhile keeps the VPN
                    if (!first && revoked) throw new IOException("vpn taken before the retry");
                    tun = establishTun(wide, first);
                    first = false;
                    if (tun == null) throw new Exception(revoked ? "VPN taken by another app"
                        : "VPN not allowed: open SelfieStick and accept its VPN request");
                    mark("tun established");
                    if (cancelled) throw new Exception("Disconnected");

                    tcp = new MiniTcp(new FileInputStream(tun.getFileDescriptor()), new FileOutputStream(tun.getFileDescriptor()), localPort, port);
                    activeTcp = tcp;
                    if (revoked || cancelled) tcp.abort();   // revoked between establish and here
                    reader = new TunReader(tcp, tun.getFileDescriptor());
                    rt = new Thread(reader, "tunreader");
                    rt.setDaemon(true);
                    rt.start();
                    try {
                        report("Connecting to adb on port " + port + "\u2026", true);
                        tcp.connect();
                        mark("adb TCP connected");
                        wideUsed = wide;
                        transport = tcp;
                        break;
                    } catch (IOException e) {
                        String m = String.valueOf(e.getMessage());
                        if (revoked || cancelled) throw e;
                        // Silence (not a refusal) means something listens on the port but its answer never came back
                        // through our VPN: adbd runs as another uid than shell. Retry with the wide VPN if allowed.
                        boolean silentWide = wideAllowed && !wide && m.startsWith("no SYN-ACK");
                        if (!silentWide) throw e;
                        mark("no answer from adbd, retrying with the wide VPN");
                        reader.stop = true;
                        try { rt.join(1000); } catch (InterruptedException ignored) {}
                        try { tun.close(); } catch (Exception ignored) {}
                        tun = null; tcp = null; reader = null; rt = null;
                        activeTcp = null;
                        wide = true;
                        report("No answer from adb, retrying with the wide VPN\u2026", true);
                    }
                }
            }

            report("Waiting for adb authorization (accept the prompt on the device if shown)\u2026", true);
            AdbClient adb = new AdbClient(transport);
            adb.connect(AdbKey.loadOrCreate(getFilesDir()), 90000);
            mark("adb authorized");
            bootDone = true;
            Prefs.setBootRunDone(this);   // adb answered: this boot is served, whichever trigger or manual run it was
            afterAuthorized(adb);
            // remembered only once adb really answered through it (a firewall could also explain the silence)
            if (wideUsed && !Prefs.wideVpn(this)) {
                Prefs.setWideVpn(this, true);
                if (MiniTcp.trace) android.util.Log.i("SelfieStick", "this device needs the wide VPN; using it from now on");
            }

            if (cmd == null || cmd.trim().isEmpty()) {
                result = "Connected, no command set";
            } else {
                report("Running command\u2026", true);
                result = "Done: " + adb.exec(cmd, 60000).trim();
                mark("command done");
            }
        } catch (Throwable e) {
            String msg = String.valueOf(e.getMessage());
            if (cancelled) result = "Disconnected";
            else if (revoked) result = "VPN taken by another app";
            else if (msg.startsWith("RST during connect") || msg.startsWith("refused"))
                result = "Refused by " + where + ". Nothing accepted on that port: enable adb "
                    + "TCP/wireless debugging there, or set the device's adb port to match.";
            else if (msg.startsWith("no SYN-ACK") && !localhost && !Prefs.wideFallback(this))
                result = "No answer from adb on " + where + " through the narrow VPN. Turn on Wide VPN fallback to retry.";
            else result = "Failed: " + msg;
            android.util.Log.e("SelfieStick", "run failed", e);
        } finally {
            activeTcp = null;
            if (transport != null) transport.close();
            if (reader != null) reader.stop = true;
            // the reader polls with a timeout: let it leave before the tun is closed, or the interface lingers
            if (rt != null) try { rt.join(1000); } catch (InterruptedException ignored) {}
            try { if (tun != null) tun.close(); } catch (Exception ignored) {}
            mark("torn down");
        }
        if (boot) {
            android.util.Log.i("SelfieStick", "boot run finished at t+" + SystemClock.elapsedRealtime() + "ms: " + result);
            notifyEvent(this, "Boot run finished: " + result);
        }
        report(result, false);
        finishSelf();
    }

    /** Before the connect: adb on, and the early-start accessibility, when the toggles and the permission allow. */
    private void prepareRun() {
        try {
            SecureSettings.clearOwnAlwaysOn(this);
            SecureSettings.ensureAdb(this, Prefs.adbToggle(this), Prefs.wifiToggle(this));
            if (Prefs.a11yToggle(this)) SecureSettings.setEarlyStartAccessibility(this, true);
        } catch (RuntimeException e) {
            android.util.Log.w("SelfieStick", "settings not written: " + e.getMessage());
        }
    }

    /** After adb works: grant the secure permission through the shell if the toggle asks for it, then the accessibility. */
    private void afterAuthorized(AdbClient adb) {
        try {
            if (Prefs.secureToggle(this) && !SecureSettings.hasPermission(this)) {
                String out = adb.exec("pm grant " + getPackageName() + " " + SecureSettings.PERM, 15000);
                mark("pm grant: " + out.trim());
            }
            if (Prefs.a11yToggle(this)) {
                if (SecureSettings.hasPermission(this)) {
                    SecureSettings.setEarlyStartAccessibility(this, true);
                } else {
                    // no permission needed here: the shell user may write secure settings itself
                    String shell = SecureSettings.earlyStartShellCommand(this);
                    if (shell != null) mark("accessibility via shell: " + adb.exec(shell, 15000).trim());
                }
            }
        } catch (Exception e) {
            // the run itself still goes on
            android.util.Log.w("SelfieStick", "settings not written: " + e.getMessage());
        }
    }

    /**
     * wide = no allowed-apps list, so the VPN's one route (the fake peer) applies to every uid. Needed where adbd does
     * not run as the shell user (rooted sticks, where it stays root): its replies are routed by the uid of its socket.
     * Normal traffic still does not enter the tun (only 10.99.0.2 is routed), but every app sees a VPN for those
     * seconds, so it is the fallback.
     *
     * prepare = false for the wide retry inside one run: prepare() would silently take the VPN back from an app that
     * took over in between. Without it, establish() simply returns null when we are no longer the prepared VPN app.
     * Returns null if we are not (or no longer) the prepared VPN app.
     */
    private ParcelFileDescriptor establishTun(boolean wide, boolean prepare) throws Exception {
        if (prepare) {
            // after a reboot no VPN is "prepared"; once the owner accepted the VPN request this makes us the prepared
            // VPN app silently. Needed when started from boot, not just the UI.
            revoked = false;
            if (VpnService.prepare(this) != null)
                throw new Exception("VPN permission missing: open SelfieStick and accept its VPN request");
        }
        Builder b = new Builder();
        b.setSession("SelfieStick");
        b.addAddress("10.99.0.1", 32);
        b.addRoute("10.99.0.2", 32);
        b.setMtu(1500);
        b.setBlocking(true);
        if (!wide) {
            b.addAllowedApplication(getPackageName());
            b.addAllowedApplication("com.android.shell");
        } else {
            // every app is "in" this VPN for a few seconds: keep it harmless for them. Without these, IPv6 would be
            // blocked for all apps (no v6 address here) and Android 11 closes their open connections (secure VPN).
            b.allowFamily(android.system.OsConstants.AF_INET6);
            b.allowBypass();
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);   // or other apps' downloads pause as "metered"
        }
        return b.establish();
    }

    private void startForegroundNote() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "SelfieStick", NotificationManager.IMPORTANCE_MIN));
            startForeground(NOTE_ID, new Notification.Builder(this, CHANNEL)
                .setContentTitle("SelfieStick").setContentText("Running adb command")
                .setSmallIcon(android.R.drawable.ic_menu_manage).build());
        } else {
            startForeground(NOTE_ID, new Notification.Builder(this)
                .setContentTitle("SelfieStick").setContentText("Running adb command")
                .setSmallIcon(android.R.drawable.ic_menu_manage).getNotification());
        }
    }

    private void finishSelf() {
        running = false;
        stopForeground(true);
        stopSelf();
    }
}
