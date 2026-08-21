# Install on Windows

Cove publishes two amd64 Windows packages:

- `cove-windows-amd64-setup.exe` is the normal guided installer.
- `cove-windows-amd64-portable.zip` is a self-contained portable copy.

Download either from the [latest stable release](https://github.com/coveninja/cove/releases/latest). The similarly named `portable-update.exe` is an internal update payload, not the portable package users should download.

## Standard installation

Run the setup executable and follow the prompts. Cove installs under Program Files and registers an uninstaller.

## Portable installation

Extract the portable ZIP into a folder you can keep. Start `Cove.exe` from the extracted folder. Do not run the application from inside the ZIP.

## Updates

Both current Windows distributions support signed, verified in-app updates. Installed and portable builds use different update payloads, so do not copy files between their layouts.

Versions older than `1.0.0` used a different application layout and must be upgraded manually. Installing a current package preserves the existing Cove data directory.

If an installer or update fails, see [Troubleshooting](../troubleshooting.md).

