package com.clean.selfiestick;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Brings up a VPN that routes ONLY 10.99.0.2 (a fake peer) into the tun, runs one shell command through the
 * self-ADB link to adbd on the tun's own address, then tears the VPN down and stops. A VPN that was running
 * before is not restarted by us: Android only lets one VPN be active at a time.
 */
public class BridgeService extends VpnService {
    static final String ACTION_CONNECT = "com.clean.selfiestick.CONNECT";
    static final String ACTION_DISCONNECT = "com.clean.selfiestick.DISCONNECT";
    static final String EXTRA_BOOT = "boot";
    static final String CHANNEL = "selfiestick";
    static final int NOTE_ID = 1;

    public interface Listener { void onStatus(String text, boolean busy); }

    static volatile boolean running;
    /** Android took our VPN away (another VPN app was prepared/connected). Reset per run. */
    static volatile boolean revoked;
    /** The user pressed Disconnect. */
    static volatile boolean cancelled;
    static volatile String status = "Disconnected";
    static volatile Listener listener;
    static volatile MiniTcp activeTcp;

    private static final Handler main = new Handler(Looper.getMainLooper());

    public static void setListener(Listener l) { listener = l; }

    static void report(final String text, final boolean busy) {
        status = text;
        final Listener l = listener;
        if (l != null) main.post(new Runnable() { public void run() { l.onStatus(text, busy); } });
    }

    /** True if some VPN other than ours is up. A boot run uses this to avoid displacing it. */
    static boolean otherVpnActive(Context c) {
        if (running && !revoked) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network[] ns = cm.getAllNetworks();
            if (ns == null) return false;
            for (Network n : ns) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (RuntimeException e) {   // courtesy check only: fail open
            android.util.Log.w("SelfieStick", "otherVpnActive failed: " + e);
        }
        return false;
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

        // Must call startForeground promptly after a startForegroundService() call, even if we stop right after.
        startForegroundNote();

        // A run is already in progress: it owns the connection, so do not start a second one.
        if (running) return START_NOT_STICKY;

        boolean boot = intent != null && intent.getBooleanExtra(EXTRA_BOOT, false);
        if (boot && otherVpnActive(this)) {
            report("Skipped at boot: another VPN is active", false);
            finishSelf();
            return START_NOT_STICKY;
        }

        running = true;
        revoked = false;
        cancelled = false;
        final String cmd = Prefs.command(this);
        final int port = Prefs.port(this);
        final int localPort = Prefs.localPort(this);
        MiniTcp.trace = Prefs.debugLog(this);
        report("Starting VPN\u2026", true);
        new Thread(new Runnable() { public void run() { runOnce(cmd, localPort, port); } }, "selfiestick").start();
        return START_NOT_STICKY;
    }

    private void runOnce(String cmd, int localPort, int port) {
        if (MiniTcp.trace) android.util.Log.i("SelfieStick", "run start: local=10.99.0.2:" + localPort + " adbd=10.99.0.1:" + port + " cmd=" + cmd);
        String result;
        ParcelFileDescriptor tun = null;
        TunReader reader = null;
        Thread rt = null;
        MiniTcp tcp = null;
        try {
            // Narrow VPN (only this app and adbd's shell uid enter the tun) first; wide only as a fallback.
            boolean wide = Prefs.wideVpn(this), wideUsed = false, first = true;
            for (;;) {
                // the wide retry never prepares again: another VPN that took over meanwhile keeps the VPN
                if (!first && revoked) throw new IOException("vpn taken before the retry");
                tun = establishTun(wide, first);
                first = false;
                if (tun == null) throw new Exception(revoked ? "VPN taken by another app"
                    : "VPN not allowed: open SelfieStick and accept its VPN request");
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
                    wideUsed = wide;
                    break;
                } catch (IOException e) {
                    // Silence (not a refusal) means something listens on the port but its answer never came back
                    // through our VPN: adbd runs as another uid than shell. Try once more with the wide VPN.
                    if (wide || revoked || cancelled || !String.valueOf(e.getMessage()).startsWith("no SYN-ACK")) throw e;
                    if (MiniTcp.trace) android.util.Log.i("SelfieStick", "no answer from adbd, retrying with the wide VPN");
                    reader.stop = true;
                    try { rt.join(1000); } catch (InterruptedException ignored) {}
                    try { tun.close(); } catch (Exception ignored) {}
                    tun = null; tcp = null; reader = null; rt = null;
                    activeTcp = null;
                    wide = true;
                    report("No answer from adb, retrying with the wide VPN\u2026", true);
                }
            }

            report("Waiting for adb authorization (accept the prompt on the device if shown)\u2026", true);
            AdbClient adb = new AdbClient(tcp);
            adb.connect(AdbKey.loadOrCreate(getFilesDir()), 90000);
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
            }
        } catch (Throwable e) {
            if (cancelled) result = "Disconnected";
            else if (revoked) result = "VPN taken by another app";
            else if (String.valueOf(e.getMessage()).startsWith("RST during connect"))
                result = "Refused by 10.99.0.1:" + port + ". Nothing accepted on that port: enable adb "
                    + "TCP/wireless debugging there, or set the device's adb port to match.";
            else result = "Failed: " + e.getMessage();
            android.util.Log.e("SelfieStick", "run failed", e);
        } finally {
            activeTcp = null;
            if (tcp != null) tcp.close();
            if (reader != null) reader.stop = true;
            // the reader polls with a timeout: let it leave before the tun is closed, or the interface lingers
            if (rt != null) try { rt.join(1000); } catch (InterruptedException ignored) {}
            try { if (tun != null) tun.close(); } catch (Exception ignored) {}
        }
        report(result, false);
        finishSelf();
    }

    /** Package names only: letters, digits, underscores, dot-separated. */
    static boolean validPackage(String pkg) {
        return pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
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
                .setContentTitle("SelfieStick").setContentText("Running adb command over VPN")
                .setSmallIcon(android.R.drawable.ic_menu_manage).build());
        } else {
            startForeground(NOTE_ID, new Notification.Builder(this)
                .setContentTitle("SelfieStick").setContentText("Running adb command over VPN")
                .setSmallIcon(android.R.drawable.ic_menu_manage).getNotification());
        }
    }

    private void finishSelf() {
        running = false;
        stopForeground(true);
        stopSelf();
    }
}
