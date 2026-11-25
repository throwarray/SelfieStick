package com.clean.selfiestick;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.pm.ServiceInfo;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    static final int REQ_VPN = 7;
    static final int REQ_GRANT = 8;

    TextView statusText, grantStateText, earlyStartText;
    Button connectButton, grantButton;
    EditText localPortEdit, portEdit, commandEdit;
    Switch bootSwitch, debugSwitch, wideSwitch, localhostSwitch, secureSwitch, a11ySwitch, adbSwitch, wifiSwitch;
    TextView secureStateText;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int earlyRechecks;
    private final Runnable recheckEarly = new Runnable() { public void run() { refreshEarlyStart(); } };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        BridgeService.uiSeen = true;
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.status_text);
        grantStateText = findViewById(R.id.grant_state_text);
        earlyStartText = findViewById(R.id.early_start_text);
        connectButton = findViewById(R.id.connect_button);
        grantButton = findViewById(R.id.grant_vpn_button);
        localPortEdit = findViewById(R.id.local_port_edit);
        portEdit = findViewById(R.id.port_edit);
        commandEdit = findViewById(R.id.command_edit);
        bootSwitch = findViewById(R.id.boot_switch);
        debugSwitch = findViewById(R.id.debug_switch);
        wideSwitch = findViewById(R.id.wide_switch);
        localhostSwitch = findViewById(R.id.localhost_switch);
        secureSwitch = findViewById(R.id.secure_switch);
        a11ySwitch = findViewById(R.id.a11y_switch);
        adbSwitch = findViewById(R.id.adb_switch);
        wifiSwitch = findViewById(R.id.wifi_switch);
        secureStateText = findViewById(R.id.secure_state_text);

        localPortEdit.setText(String.valueOf(Prefs.localPort(this)));
        portEdit.setText(String.valueOf(Prefs.port(this)));
        commandEdit.setText(Prefs.command(this));
        bootSwitch.setChecked(Prefs.bootEnabled(this));
        debugSwitch.setChecked(Prefs.debugLog(this));
        wideSwitch.setChecked(Prefs.wideFallback(this));
        localhostSwitch.setChecked(Prefs.localhost(this));
        secureSwitch.setChecked(Prefs.secureToggle(this));
        a11ySwitch.setChecked(Prefs.a11yToggle(this));
        adbSwitch.setChecked(Prefs.adbToggle(this));
        wifiSwitch.setChecked(Prefs.wifiToggle(this));

        bootSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                Prefs.setBootEnabled(MainActivity.this, on);
                if (on) BootJob.schedule(MainActivity.this); else BootJob.cancel(MainActivity.this);
            }
        });
        debugSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) { Prefs.setDebugLog(MainActivity.this, on); }
        });
        wideSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) { Prefs.setWideFallback(MainActivity.this, on); }
        });
        localhostSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                Prefs.setLocalhost(MainActivity.this, on);
                refreshGrant();
            }
        });
        secureSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                Prefs.setSecureToggle(MainActivity.this, on);
                refreshSecure();
            }
        });
        a11ySwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                Prefs.setA11yToggle(MainActivity.this, on);
                // only writes when the permission is held; with the toggle off this removes only our own entry
                SecureSettings.setEarlyStartAccessibility(MainActivity.this, on);
                refreshSecure();
                refreshEarlyStart();
            }
        });
        adbSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                Prefs.setAdbToggle(MainActivity.this, on);
                if (on) SecureSettings.ensureAdb(MainActivity.this, true, false);   // only writes with the permission
                refreshSecure();
            }
        });
        wifiSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                Prefs.setWifiToggle(MainActivity.this, on);
                if (on) SecureSettings.ensureAdb(MainActivity.this, false, true);
                refreshSecure();
            }
        });
        connectButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onConnectClicked(); }
        });
        grantButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onGrantClicked(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        BridgeService.setListener(new BridgeService.Listener() {
            public void onStatus(String text, boolean busy) {
                showStatus(text, busy);
                if (!busy) {   // a run just ended: it may have enabled the accessibility entry or granted the permission
                    earlyRechecks = 0;
                    refreshEarlyStart();
                    refreshSecure();
                }
            }
        });
        showStatus(BridgeService.status, BridgeService.running);
        if (Prefs.bootEnabled(this)) BootJob.schedule(this);   // no-op when already pending
        refreshGrant();
        // A force-stop makes the system drop this app's accessibility entry. Put it back on open, if the toggle asks.
        if (Prefs.a11yToggle(this)) SecureSettings.setEarlyStartAccessibility(this, true);
        SecureSettings.clearOwnAlwaysOn(this);
        earlyRechecks = 0;
        refreshEarlyStart();
        refreshSecure();
    }

    @Override
    protected void onPause() {
        BridgeService.setListener(null);
        ui.removeCallbacks(recheckEarly);
        saveFields(false);
        super.onPause();
    }

    /**
     * VPN consent as Android records it (the ACTIVATE_VPN app op). This only reads state. VpnService.prepare() is NOT
     * read-only: it can take the active VPN away from another app, so it is called only on Connect and on Grant VPN.
     */
    private boolean vpnConsentGranted() {
        AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) return false;
        return ops.checkOpNoThrow("android:activate_vpn", android.os.Process.myUid(), getPackageName())
            == AppOpsManager.MODE_ALLOWED;
    }

    /** Shows the VPN permission state. Reads only, so opening the app never changes the active VPN. */
    private void refreshGrant() {
        try {
            if (Prefs.localhost(this)) {
                grantStateText.setText("VPN not used: connecting over localhost");
                return;
            }
            grantStateText.setText(vpnConsentGranted() ? "VPN permission: granted"
                : "VPN permission: needed. Press Grant VPN.");
        } catch (RuntimeException e) {
            grantStateText.setText("VPN permission: unknown (" + e.getMessage() + ")");
        }
    }

    /** Shows the secure-permission state and what the two toggles will do. */
    private void refreshSecure() {
        boolean granted = SecureSettings.hasPermission(this);
        String perm = granted ? "Write secure settings: granted."
            : "Write secure settings: not granted. With the toggle on, it is granted on the first connect that reaches adb.";
        String a11y = Prefs.a11yToggle(this)
            ? " Accessibility is enabled over adb by the first connect that reaches it, if it is not on already."
            : "";
        secureStateText.setText(perm + a11y);
    }

    /** Whether the optional early-start accessibility service is on. Reads only; never changes accessibility settings. */
    private void refreshEarlyStart() {
        ui.removeCallbacks(recheckEarly);
        if (earlyStartEnabled()) {
            earlyStartText.setText("Early start at boot: on (SelfieStick early start is enabled in Accessibility).");
        } else if (SecureSettings.earlyStartListed(this)) {
            // written to the setting, but Android binds the service a moment later: look again shortly
            earlyStartText.setText("Early start at boot: enabled in Accessibility, starting\u2026");
            if (earlyRechecks++ < 6) ui.postDelayed(recheckEarly, 1000);
        } else {
            earlyStartText.setText("Early start at boot: off. Optional: enable \"SelfieStick early start\" in Settings > Accessibility to start sooner.");
        }
    }

    private boolean earlyStartEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        for (AccessibilityServiceInfo info : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            ServiceInfo si = info.getResolveInfo().serviceInfo;
            if (getPackageName().equals(si.packageName) && WarmService.class.getName().equals(si.name)) return true;
        }
        return false;
    }

    private void showStatus(String text, boolean busy) {
        statusText.setText(text);
        connectButton.setText(busy ? "Disconnect" : "Connect");
    }

    private void onConnectClicked() {
        if (BridgeService.running) {
            startService(new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_DISCONNECT));
            return;
        }
        if (!saveFields(true)) return;
        if (Prefs.localhost(this)) {   // no VPN in this mode, so no VPN consent
            startBridge();
            return;
        }
        try {
            Intent consent = VpnService.prepare(this);   // non-null when Android needs the VPN permission from us
            if (consent != null) {
                showStatus("Waiting for VPN permission\u2026", false);
                startActivityForResult(consent, REQ_VPN);
            } else {
                startBridge();
            }
        } catch (Throwable e) {
            showStatus("Could not start: " + e, false);   // never a silent no-op
        }
    }

    /** Shows Android's VPN permission dialog when Android needs one; says so when it is already granted. */
    private void onGrantClicked() {
        try {
            if (vpnConsentGranted()) {
                showStatus("VPN permission is already granted", false);
                refreshGrant();
                Toast.makeText(this, "VPN permission already granted", Toast.LENGTH_SHORT).show();
            } else {
                // only here, when the permission is really needed: prepare() shows Android's dialog
                Intent consent = VpnService.prepare(this);
                if (consent == null) {
                    showStatus("VPN permission granted", false);
                    refreshGrant();
                } else {
                    startActivityForResult(consent, REQ_GRANT);
                }
            }
        } catch (Throwable e) {
            showStatus("Could not show the VPN permission: " + e, false);
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_VPN) {
            if (result == RESULT_OK) startBridge();
            else showStatus("VPN permission denied", false);
        } else if (req == REQ_GRANT) {
            showStatus(result == RESULT_OK ? "VPN permission granted" : "VPN permission not granted", false);
        }
        refreshGrant();
    }

    private void startBridge() {
        Intent i = new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_CONNECT);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            showStatus("Starting VPN\u2026", true);
        } catch (RuntimeException e) {
            showStatus("Could not start the service: " + e, false);
        }
    }

    /** Saves both ports and the command. Returns false (and shows why) if a port is not valid. */
    private boolean saveFields(boolean warn) {
        int local = parsePort(localPortEdit);
        int port = parsePort(portEdit);
        if (local < 1) { if (warn) fail(localPortEdit, "Local port must be 1-65535"); return false; }
        if (port < 1) { if (warn) fail(portEdit, "ADB port must be 1-65535"); return false; }
        Prefs.save(this, local, port, commandEdit.getText().toString());
        return true;
    }

    private static int parsePort(EditText e) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return v >= 1 && v <= 65535 ? v : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private void fail(EditText e, String msg) {
        e.setError(msg);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
