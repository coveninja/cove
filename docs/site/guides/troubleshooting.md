# Troubleshooting

Start by recording the Cove version from **Profile → Advanced → About**, your platform, installation method, and the exact action that failed. Change one variable at a time and keep the first complete error message.

## Cove does not start

- Confirm the package matches the operating system and CPU architecture.
- On Linux, verify `libmpv` is installed for AUR and tarball builds.
- On macOS, follow the [Gatekeeper instructions](install/macos.md) for the official DMG.
- On Windows portable, extract the ZIP before starting `Cove.exe`.
- On Android, confirm Android 9 or newer and that the APK installation completed.

If the process exits immediately, collect the current log before reinstalling. Reinstallation replaces application files but does not necessarily repair a corrupt setting or provider response.

## Installation or update fails

- Download only from the official stable release.
- Compare the asset with its `.sha256` file when installing manually.
- On Windows, use the installer for an installed copy and the portable ZIP for a portable copy.
- On macOS, follow the [Gatekeeper instructions](install/macos.md).
- On Android, an incompatible-signature message usually means the installed APK was signed by someone else.

For in-app updates, note the stage that failed: check, download, verification, staging, restart, or Android confirmation. Do not bypass a signature or checksum failure. See [Update Cove](updates.md).

## Account data looks old

Open Cove on the device that has the newest changes and leave it online long enough for the current sync to finish. The website shows the newest stored cloud timestamp, not the status of every device.

Confirm that the same account and profile are selected. Do not delete a local profile as a first troubleshooting step.

If automatic sync is disabled, choose **Sync now**. If it fails, preserve the message and retry once after confirming network access. Tracker errors are separate from Cove account sync; check [Trakt and Simkl tracking](tracking.md) when only external history or lists are affected.

## A title, catalog, or source is missing

1. Confirm the active profile.
2. Check that the provider addon, catalog, or scraper is enabled.
3. Check whether primary-profile addon sharing makes the current profile inherit a different configuration.
4. Refresh the addon or repository.
5. Try another media type and title.
6. Distinguish an empty provider response from a Cove playback failure.

Catalogs affect Home and Explore rows. Stream capability is separate, so hiding a catalog does not necessarily disable streams from that addon.

## Playback fails

Try another source manually, then a lower quality. If hardware decoding fails and Cove offers a software option, test that option. Keep the original source name and error text for the report.

For a torrent, record seeders, selected file, and whether buffering progresses. For a direct stream, record whether it fails before playback, during probing, or after a repeatable amount of time. Do not publish the complete stream URL when it contains a token.

## Playback stutters or drops frames

- Compare a lower resolution or bitrate from the same provider.
- Leave hardware decoding enabled unless it causes a repeatable decoder or rendering defect.
- On desktop, press `I` during playback and record decoder and dropped-frame telemetry.
- Close other GPU- or CPU-heavy applications.
- Use low-performance mode to reduce interface motion on constrained devices; it does not reduce the cost of software decoding.

Report the GPU or device model, driver or Android version, source quality, decode mode, and whether audio remains smooth.

## Subtitles are missing or out of sync

- Open the subtitle menu and confirm a track is selected.
- Try another provider subtitle before changing the video source.
- Adjust subtitle delay in small steps when a release cut differs.
- On desktop, load or drag in a local subtitle file to separate provider failure from player rendering.
- Confirm subtitle defaults, language, size, position, and background under **Profile → Subtitles**.

## A desktop plugin fails

Confirm it is enabled for the active profile and all requested permissions are approved. Refresh the signed catalog, then use Retry once. For Discord Presence, make sure Discord is running locally. See [Desktop plugins](desktop-plugins.md).

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

To reduce unrelated system output after reproducing the problem, search the captured file for `coveninja`, `Cove`, `mpv`, or the displayed error. Keep the unedited original available in case surrounding lines are needed.

## Before clearing data

Clearing caches is safer than deleting the whole application database. Use **Profile → Storage** for supported cache categories. Clearing app data, uninstalling Android, or removing Cove's data directory deletes local profiles that have not been synchronized.

When investigating sync, addon, or playback state, make a backup before manually moving files. Do not edit the SQLite database while Cove is running.

## Report a bug

Use the [Cove bug report form](https://github.com/coveninja/cove/issues/new?template=bug_report.yml). Include reproduction steps, expected behavior, version, operating system, hardware, install method, and relevant logs. Remove credentials, private addon URLs, and access tokens before attaching files.

A useful report says whether the problem reproduces after restart, on another source or profile, and with the relevant optional integration disabled. Do not include copyrighted media, complete provider responses, account verification codes, or the remote-access pairing token.
