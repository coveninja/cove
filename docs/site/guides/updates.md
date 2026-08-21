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

## In-app checks

Automatic checks are enabled per device by default. Cove checks shortly after launch and at most once every 24 hours while running. It can download a newer stable release, but waits until playback is idle before asking to install or restart.

Use **Settings → Advanced → Check now** for a manual check. Android asks before downloading over a metered connection.

## Verification

Update-capable packages verify a detached Ed25519-signed manifest, expected asset name, byte count, SHA-256 digest, release version, and platform target. Windows and Android perform additional package checks before replacement.

Manual release assets publish `.sha256` files. If a checksum does not match, do not install the file.

For release-specific changes, open the [release history](https://cove.ninja/releases).

