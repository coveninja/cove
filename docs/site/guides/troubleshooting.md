# Troubleshooting

Start by recording the Cove version from **Settings → Advanced → About**, your platform, installation method, and the exact action that failed.

## Installation or update fails

- Download only from the official stable release.
- Compare the asset with its `.sha256` file when installing manually.
- On Windows, use the installer for an installed copy and the portable ZIP for a portable copy.
- On macOS, follow the [Gatekeeper instructions](install/macos.md).
- On Android, an incompatible-signature message usually means the installed APK was signed by someone else.

## Account data looks old

Open Cove on the device that has the newest changes and leave it online long enough for the current sync to finish. The website shows the newest stored cloud timestamp, not the status of every device.

Confirm that the same account and profile are selected. Do not delete a local profile as a first troubleshooting step.

## Playback fails

Try another source manually, then a lower quality. If hardware decoding fails and Cove offers a software option, test that option. Keep the original source name and error text for the report.

## Find desktop logs

Cove stores rotating logs beside its desktop data:

- Windows: `%APPDATA%\cove\logs`
- Linux: `~/.config/cove/logs/`
- Flatpak: `~/.var/app/io.github.coveninja.Cove/config/cove/logs/`
- macOS: `~/Library/Application Support/cove/logs/`

`cove.log` is the current run and `cove.log.1` is the previous run.

## Capture Android logs

Reproduce the problem with a device connected through ADB, then run:

```sh
adb logcat -d > cove-log.txt
```

## Report a bug

Use the [Cove bug report form](https://github.com/coveninja/cove/issues/new?template=bug_report.yml). Include reproduction steps, expected behavior, version, operating system, hardware, install method, and relevant logs. Remove credentials, private addon URLs, and access tokens before attaching files.

