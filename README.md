# SelfieStick\*

A small Android app that runs a adb shell command on the device, over a VPN the app creates for itself. 
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

- **adb port on the device** (default 5555): the port adbd listens on at `10.99.0.1`. Enable TCP or wireless debugging on this port. Wireless debugging uses a random port, so set this field to the port it shows.
- **Local port** (default 5556): our end on `10.99.0.2`. Any free port works.
- **Launch command**: any shell command, for example `am start -n com.example/.MainActivity`.
- **Connect on boot**: runs the same sequence once after boot.
- **Debug log**: traces every adb packet to logcat under the `SelfieStick` tag. Off by default. Errors are always logged.

The first connect asks for the VPN permission. Accept it. Android keeps that grant, so later connects, including the boot run, don't show the dialog.

The status card shows **VPN permission: granted** or **needed**. **Grant VPN** opens Android's VPN permission dialog when Android needs one, and says so when the permission is already granted. To see the dialog again after it has been accepted, reset the grant from a computer:

```
adb shell appops set com.clean.selfiestick ACTIVATE_VPN default
```

## Connection modes

- **Narrow VPN** (default): only SelfieStick and the shell uid enter the tun.
- **Wide VPN** (fallback): used automatically when adbd is silent through the narrow VPN, which happens when adbd runs as another uid (for example root). Every app sees the VPN for those seconds. Once adb answers through the wide VPN, SelfieStick remembers that and uses it from then on.

A refused connect (RST) is reported as "nothing accepted on that port" and does not trigger the wide fallback, because a wide VPN can't fix a closed port.

## Other VPNs

Android allows one VPN at a time, so connecting replaces whatever VPN is up. When the run ends, SelfieStick does not restore the VPN it replaced; that app has to reconnect itself. A boot run skips and leaves an existing VPN alone, and the status says so.

Always-on VPN is not handled. SelfieStick does not switch it off or hand the network back to it. 
If always-on is set on the device, its VPN and lockdown can keep SelfieStick's VPN from starting.

## Building

1. Install the Android SDK (platform 34 and build-tools) and a JDK 17.
2. Install Gradle 8.2 or newer.
3. In Termux, `pkg install aapt2 gradle openjdk-17`. Check the path in `gradle.properties` (`android.aapt2FromMavenOverride`) against where `aapt2` is installed.
4. `gradle :app:assembleDebug`, then sideload `app/build/outputs/apk/debug/app-debug.apk`.

The debug build uses the standard Android debug signing key, which AGP creates for you. Release builds are unsigned.

## Notes

- Whether Android reconnects VPNs after our tun closes depends on the device. 
The debug log shows the VPN state if needed.

## Credits
- The majority of the codebase is inherited from MohamedNofal/stickman. 
Please show support for their project. https://github.com/MohamedNofal/stickman

## LICENSE
MIT
