# Install on Windows

Cove publishes two amd64 Windows packages:

- `cove-windows-amd64-setup.exe` is the normal guided installer.
- `cove-windows-amd64-portable.zip` is a self-contained portable copy.

Download either from the [latest stable release](https://github.com/coveninja/cove/releases/latest). The similarly named `portable-update.exe` is an internal update payload, not the portable package users should download.

Use a supported 64-bit Windows installation with current graphics drivers. Both packages include Cove's application runtime and native playback dependencies.

## Standard installation

Run the setup executable and follow the prompts. Cove installs under Program Files and registers an uninstaller.

Use the registered Windows uninstaller when removing this form. Application data is separate from the installed program files and can remain afterward; back up anything important before removing that data separately.

## Portable installation

Extract the portable ZIP into a folder you can keep. Start `Cove.exe` from the extracted folder. Do not run the application from inside the ZIP.

Keep the extracted directory writable by your user so signed portable updates can stage and replace application-owned files. Do not merge an installed build into the portable directory.

## Updates

Both current Windows distributions support signed, verified in-app updates. Installed and portable builds use different update payloads, so do not copy files between their layouts.

Versions older than `1.0.0` used a different application layout and must be upgraded manually. Installing a current package preserves the existing Cove data directory.

## Verify and troubleshoot

Each downloadable release asset has a matching `.sha256` file. In PowerShell, compare the published value with:

```powershell
Get-FileHash .\cove-windows-amd64-setup.exe -Algorithm SHA256
```

Use the ZIP filename instead when checking the portable package. If the values differ, discard the file and download it again from the official release.

Windows logs are under `%APPDATA%\cove\logs`. If an installer or update fails, record whether the copy is installed or portable and see [Troubleshooting](../troubleshooting.md).
