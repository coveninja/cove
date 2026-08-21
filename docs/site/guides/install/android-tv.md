# Install on Android and TV

One `cove-android.apk` serves Android phones, tablets, and televisions. Cove selects its touch or ten-foot, D-pad interface from the device at runtime.

## Requirements

- Android 9 (API 28) or newer
- Permission for your browser or file manager to install unknown apps
- Enough free space for the APK and app data

## Install

Download `cove-android.apk` from the [latest stable release](https://github.com/coveninja/cove/releases/latest), open the file, and follow Android's package-installer prompt.

On a television you can download through a browser, transfer the APK over the network, or install it from a connected computer:

```sh
adb install cove-android.apk
```

## Updates

Current builds can download a signed, verified APK in-app. Android still shows its normal confirmation screen before replacement. Cove checks the package name, version, signed release manifest, checksum, and signing certificate before handing the APK to Android.

The first in-app update may ask you to allow Cove to install unknown apps. Upgrades keep app data when the installed release uses the official signing certificate.

If Android reports an incompatible signature, remove any unofficial build before installing the official release. Uninstalling also removes that installation's local data, so export or sync anything important first.

