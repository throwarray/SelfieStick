# SelfieStick\*

A small Android app that runs an adb shell command on the device, over a VPN the app creates for itself. 
Set the command once, press **Connect**, and it runs as the `shell` user.

The device asks you to approve its adb key on the first connect.

## How it works

1. Connect brings up a VPN that routes only `10.99.0.2/32` into a tun interface. Other traffic is not touched.
2. The app writes IPv4/TCP packets by hand into the tun, from `10.99.0.2:<local port>` to `10.99.0.1:<adb port>`, which is the device's own adbd. It never calls `bind()`, because `10.99.0.2` is not a local address.
3. It speaks the adb protocol itself. The first time, the device asks you to approve the app's RSA key. After that the key is accepted.
4. It runs your command as `shell` (uid 2000), tears the tun down, and stops.

The RSA key is created on the first connect and kept in the app's private storage.
It is not in the APK. Clearing the app's data or uninstalling it removes the key, 
and the device will ask to approve a new one.

## Setup

- **adb port on the device** (default 5555): the port adbd listens on, at `10.99.0.1` in tun mode and `127.0.0.1` in localhost mode. Enable TCP or wireless debugging on this port. Wireless debugging uses a random port, so set this field to the port it shows.
- **Local port** (default 5556): our end on `10.99.0.2` in tun mode. Unused in localhost mode.
- **Launch command**: any shell command, for example `am start -n com.example/.MainActivity`.
- **Connect via localhost** (off by default): connects to adbd on `127.0.0.1` with a plain socket, with no VPN. Turn it on when the tun route is blocked, for example by an always-on VPN. The VPN is the default and is used when this is off.
- **Write secure settings** (off by default): when on, a connect that reaches adb grants this app `WRITE_SECURE_SETTINGS` with `pm grant`, once. Nothing else is written without that permission.
- **Turn on adb at run start** and **Turn on wireless adb at run start** (both off by default): each sets its own setting, `adb_enabled` or `adb_wifi_enabled`, if it is off. Wireless adb is not needed for this app to work. Whether a plain TCP adbd comes up depends on the device. On Android 11 and later, wireless debugging uses TLS and a random port, so test before relying on it.
- **Auto-enable early-start accessibility** (off by default): adds only this app's early-start service to the enabled accessibility list, keeping every other entry. With the secure permission held, the app writes it itself. Without it, the first connect that reaches adb does it over adb (`settings put secure`, which the shell user may do), so this toggle does not depend on **Write secure settings**. Turning it off removes only that entry, and only with the permission held.
- **Connect on boot**: runs the same sequence once after boot. In tun mode it takes over from any VPN that is active, the same way Connect does.
- **Wide VPN fallback** (on by default): lets a connect retry through the wide VPN when adbd is silent (see Connection modes). Off means the app never uses the wide VPN.
- **Debug log**: traces every adb packet to logcat under the `SelfieStick` tag. Off by default. Errors are always logged.

The first connect asks for the VPN permission. Accept it. Android keeps that grant, so later connects, including the boot run, don't show the dialog.

The status card shows **VPN permission: granted** or **needed**. **Grant VPN** opens Android's VPN permission dialog when Android needs one.


## Connection modes

- **Narrow VPN** (default): only SelfieStick and the shell uid enter the tun.
- **Wide VPN** (fallback): used when adbd is silent through the narrow VPN, which happens when adbd runs as another uid (for example root), and the **Wide VPN fallback** switch is on. Every app sees the VPN for those seconds. Once adb answers through the wide VPN, SelfieStick remembers that and uses it from then on, as long as the switch stays on.

A refused connect (RST) is reported as "nothing accepted on that port" and does not trigger the wide fallback, because a wide VPN can't fix a closed port.

## Other VPNs

Android allows one VPN at a time, so connecting replaces whatever VPN is up, including on boot. Opening the app only reads state: it shows the VPN permission and never changes the active VPN. Only Connect, and Grant VPN when the permission is needed, ask Android for the VPN slot. When the run ends, SelfieStick does not restore the VPN it replaced; that app has to reconnect itself.

## Always-on VPN

Android allows one VPN at a time. While another app is set as always-on, its VPN stays up and the tun route can't start, so the connect fails. Turn on **Connect via localhost** to avoid the VPN entirely. That route doesn't depend on the VPN slot, so always-on doesn't matter.

**Service starts it did not ask for.** SelfieStick only connects on its own Connect, boot and early-start requests. If the system starts the VPN service by itself (for example when this app is set as the always-on VPN), the start is ignored. An older build could leave `always_on_vpn_app` pointing at this app; check with `adb shell settings get secure always_on_vpn_app` and clear it with `adb shell settings put secure always_on_vpn_app null`. With the write-secure permission, the app clears it itself.

**Early start and opening the app.** The early start fires only in a process the UI has not been opened in, so opening the app never counts as a boot.

**Boot notifications.** When a boot run is requested (by BOOT_COMPLETED, the boot job or the early start), fails to start, or finishes, SelfieStick posts a low-importance notification on the "SelfieStick status" channel, with the result text. If none appears after a reboot, either Connect on boot is off, the broadcast did not arrive (for example after a force-stop), or notifications are blocked for the app (on Android 13 and later, check the app's notification permission).

**Boot triggers.** Three, whichever comes first: the early-start accessibility service, BOOT_COMPLETED, and a persisted JobScheduler job. Some ROMs never deliver BOOT_COMPLETED to sideloaded apps, but they do restore persisted jobs after a reboot, which is why the job exists. With Connect on boot on, the app keeps a periodic job and a one-off job that re-arms itself every few minutes; opening the app, switching Connect on boot on, an update, and every boot re-arm them, and switching it off cancels them. A boot is served once: after a run reaches adb (a boot trigger or a manual Connect), the other triggers do nothing until the next reboot. A failing setup is tried at most 4 times per boot, so it cannot take the VPN over in a loop.

**No boot wait.** A boot run connects once, straight away, the same as a manual Connect. It does not poll for adbd or retry a refused connect; if adb isn't answering at that moment, the run ends with the error and the boot notification shows it. Boot runs always log when they were requested (by BOOT_COMPLETED or by the early start) and how they ended, under the `SelfieStick` tag, even with the debug log off. The debug log shows timestamps (`t+...ms`) for each step, so you can see where the time goes.

## Notes

- Whether Android reconnects VPNs after our tun closes depends on the device.
- **Early start (optional).** Enable "SelfieStick early start" in Settings > Accessibility. Android starts enabled accessibility services before boot completes, so the boot run starts sooner. The service reads no screen content and receives no events. With the auto-enable toggle on, the app adds only its own entry, so other enabled services are left alone. Without it, the boot run starts at BOOT_COMPLETED as before.
- **Force-stop.** Force-stopping the app makes the system drop its accessibility entry, and Android sends no boot broadcast to a force-stopped app until it is opened again. Opening the app restores the accessibility entry if the auto-enable toggle is on. Boot runs also need the app to have been opened once since the force-stop.
- **Updating from an earlier zip.** Zips overlay files and don't delete them. Delete `app/src/main/java/com/clean/selfiestick/AlwaysOn.java` if it is still there from an earlier build.

## Credits
- The majority of the codebase is inherited from MohamedNofal/stickman. 
Please show support for their project. https://github.com/MohamedNofal/stickman

## LICENSE
MIT
