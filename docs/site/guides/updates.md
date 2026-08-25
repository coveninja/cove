# Update Cove

Cove's update path depends on how it was installed.

| Installation | Update method |
|---|---|
| Arch/AUR `cove-bin` | Update through `pacman` and your AUR helper |
| Flatpak bundle | Download and install the new bundle manually |
| Linux tarball | Replace the `/usr` installation manually |
| Windows installer | Signed, verified in-app update |
| Windows portable | Signed, verified in-app update |
| macOS DMG | Replace the application manually |
| Android/Android TV APK | Verified in-app APK followed by Android confirmation |

Continue using the same distribution form you installed. Switching between installed and portable Windows layouts or between official and unofficial Android signatures is not an in-place update.

## In-app checks

Automatic checks are enabled per device by default. Cove checks shortly after launch and at most once every 24 hours while running. It can download a newer stable release, but waits until playback is idle before asking to install or restart.

Use **Profile → Advanced → Check now** for a manual check. Android asks before downloading over a metered connection.

The update overlay reports checking, availability, download, verification, readiness, and failures. Closing a notification does not bypass verification. A staged update is checked again after process restart before Cove applies it.

## Verification

Update-capable packages verify a detached Ed25519-signed manifest, expected asset name, byte count, SHA-256 digest, release version, and platform target. Windows and Android perform additional package checks before replacement.

Manual release assets publish `.sha256` files. If a checksum does not match, do not install the file.

## Platform details

### Windows

Installed and portable copies use different signed replacement helpers. The helper waits for Cove to exit, backs up application-owned files, replaces them, and restarts the application. A failed replacement rolls back before restart.

Pre-`1.0.0` Windows builds must be replaced manually because they used a different application layout and updater contract.

### Android and TV

Cove parses the downloaded APK and requires a newer version code, the Cove package name, and the same signing certificate as the installed release. Android then shows its normal system confirmation. A certificate mismatch cannot be overridden without uninstalling the existing app and its local data.

### Manual distributions

AUR updates belong to the package manager. Standalone Flatpak bundles, Linux tarballs, and macOS DMGs are replaced with the new release package. Their application data lives outside the program image and normally survives replacement.

## If an update fails

1. Record the current version, installation type, target version, and displayed error.
2. Confirm playback has stopped and Cove can write to its installation or staging directory.
3. Retry **Check now** once after restoring network access.
4. For Android, confirm Cove has permission to request package installation and enough storage for the APK.
5. For a manual package, download it again and verify its checksum.
6. Use [Troubleshooting](troubleshooting.md) to collect logs before changing the installation.

Do not copy update helpers between packages, edit the signed manifest, or download an internal replacement payload as though it were the normal installer.

For release-specific changes, open the [release history](https://cove.ninja/releases).
