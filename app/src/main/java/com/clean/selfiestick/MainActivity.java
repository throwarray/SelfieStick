package com.clean.selfiestick;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    static final int REQ_VPN = 7;
    static final int REQ_GRANT = 8;

    TextView statusText, grantStateText;
    Button connectButton, grantButton;
    EditText localPortEdit, portEdit, commandEdit;
    Switch bootSwitch, debugSwitch;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.status_text);
        grantStateText = findViewById(R.id.grant_state_text);
        connectButton = findViewById(R.id.connect_button);
        grantButton = findViewById(R.id.grant_vpn_button);
        localPortEdit = findViewById(R.id.local_port_edit);
        portEdit = findViewById(R.id.port_edit);
        commandEdit = findViewById(R.id.command_edit);
        bootSwitch = findViewById(R.id.boot_switch);
        debugSwitch = findViewById(R.id.debug_switch);

        localPortEdit.setText(String.valueOf(Prefs.localPort(this)));
        portEdit.setText(String.valueOf(Prefs.port(this)));
        commandEdit.setText(Prefs.command(this));
        bootSwitch.setChecked(Prefs.bootEnabled(this));
        debugSwitch.setChecked(Prefs.debugLog(this));

        bootSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) { Prefs.setBootEnabled(MainActivity.this, on); }
        });
        debugSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) { Prefs.setDebugLog(MainActivity.this, on); }
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
            public void onStatus(String text, boolean busy) { showStatus(text, busy); }
        });
        showStatus(BridgeService.status, BridgeService.running);
        refreshGrant();
    }

    @Override
    protected void onPause() {
        BridgeService.setListener(null);
        saveFields(false);
        super.onPause();
    }

    /** VPN permission as Android sees it right now. prepare() with a null result has no side effects. */
    private void refreshGrant() {
        try {
            boolean granted = VpnService.prepare(this) == null;
            grantStateText.setText(granted ? "VPN permission: granted"
                : "VPN permission: needed. Press Grant VPN.");
        } catch (RuntimeException e) {
            grantStateText.setText("VPN permission: unknown (" + e.getMessage() + ")");
        }
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
            Intent consent = VpnService.prepare(this);
            if (consent == null) {
                showStatus("VPN permission is already granted", false);
                refreshGrant();
                Toast.makeText(this, "VPN permission already granted", Toast.LENGTH_SHORT).show();
            } else {
                startActivityForResult(consent, REQ_GRANT);
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
