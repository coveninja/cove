# Install on macOS

Cove currently supports Apple-silicon Macs. Intel Macs are not supported.

The release asset is `cove-macos-arm64.dmg`. It bundles libmpv and its native runtime dependencies, so Homebrew and a separate API key are not required.

Use a current macOS version on Apple silicon with enough space for the application and local media caches. Confirm the Mac architecture under **Apple menu → About This Mac** before downloading.

## Recommended installation

The DMG is ad-hoc signed, not notarized with an Apple Developer ID. Downloading it from Terminal avoids the browser quarantine flag that produces the Gatekeeper warning:

```sh
curl -LO https://github.com/coveninja/cove/releases/latest/download/cove-macos-arm64.dmg
open cove-macos-arm64.dmg
```

Drag Cove into **Applications**, then launch it normally.

## If you downloaded with a browser

macOS may say Apple could not verify Cove. Either approve Cove under **System Settings → Privacy & Security**, or clear the downloaded application's quarantine flag:

```sh
xattr -dr com.apple.quarantine /Applications/Cove.app
```

Only do this for the DMG obtained from Cove's official GitHub release.

The ad-hoc signature is required for the bundled native libraries to execute, but it is not an Apple notarization or an independent trust guarantee. Verify that the download came from `coveninja/cove` and compare its published SHA-256 value before bypassing quarantine.

## Updates

macOS releases are replaced manually. Download the new DMG and replace the application in **Applications**. Your library and settings live outside the application bundle.

To uninstall the application, remove Cove from **Applications**. Local profiles, settings, and logs remain under `~/Library/Application Support/cove/` until removed separately. Back up anything important before deleting that directory.

Logs are stored under `~/Library/Application Support/cove/logs/`. See [Troubleshooting](../troubleshooting.md) for the files to include in a report.
