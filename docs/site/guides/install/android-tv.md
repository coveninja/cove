# Install on Android and TV

One `cove-android.apk` serves Android phones, tablets, and televisions. Cove selects its touch or ten-foot, D-pad interface from the device at runtime.

## Requirements

- Android 9 (API 28) or newer
- Permission for your browser or file manager to install unknown apps
- Enough free space for the APK and app data

The APK includes native playback and torrent components for supported Android ABIs. It does not require a separate mpv installation.

## Install

Download `cove-android.apk` from the [latest stable release](https://github.com/coveninja/cove/releases/latest), open the file, and follow Android's package-installer prompt.

On a television you can download through a browser, transfer the APK over the network, or install it from a connected computer:

```sh
adb install cove-android.apk
```

For an upgrade through ADB, use:

```sh
adb install -r cove-android.apk
```

The `-r` form retains application data only when Android accepts the package identity and signing certificate.

## Updates

Current builds can download a signed, verified APK in-app. Android still shows its normal confirmation screen before replacement. Cove checks the package name, version, signed release manifest, checksum, and signing certificate before handing the APK to Android.

The first in-app update may ask you to allow Cove to install unknown apps. Upgrades keep app data when the installed release uses the official signing certificate.

If Android reports an incompatible signature, remove any unofficial build before installing the official release. Uninstalling also removes that installation's local data, so export or sync anything important first.

## TV behavior

The APK declares both ordinary and Leanback launchers while keeping touchscreen and Leanback hardware optional. A television launcher opens the D-pad-oriented shell; phones and tablets use the adaptive touch shell. Read [TV and remote controls](../tv-and-remote-controls.md) for navigation details.

## Verify and troubleshoot

The release publishes `cove-android.apk.sha256`. Compare it before sideloading:

```sh
sha256sum -c cove-android.apk.sha256
```

If installation fails, record the Android version, device model, whether another Cove build is installed, and the package-installer message. Use ADB logs as described in [Troubleshooting](../troubleshooting.md).
