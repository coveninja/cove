# TV and remote controls

The same `cove-android.apk` serves Android phones, tablets, and televisions. On a Leanback television Cove starts a ten-foot interface designed for D-pad focus; it does not stretch the touch layout onto the TV.

## Navigate with a remote

- Use the directional pad to move focus between navigation destinations, rows, cards, and controls.
- Press the center or Enter button to activate the focused item.
- Press Back to close the current menu or overlay before leaving the page.
- When an overlay closes, Cove restores focus to the control that opened it where possible.

The main destinations are Home, Explore, Search, My List, and Profile. My List includes library rows, Ready to watch, and calendar groups. The Profile destination contains account controls and a settings surface reduced to choices that are useful from a remote.

## Control playback

Press the center button during playback to reveal controls. The TV player supports play and pause, seeking, source selection, episodes, audio and subtitle tracks, playback speed where available, segment skipping, and up-next behavior.

Back closes an open picker first, then hides playback controls, then exits playback. This order prevents one Back press from accidentally abandoning the title while a menu is open.

Automatic intro, recap, credit, and preview skipping follows the active profile's settings. A manual skip action appears when a known segment is active and automatic skipping for that type is disabled.

## TV settings

The TV settings page includes remote-friendly controls for:

- Profiles and optional primary-profile addon sharing
- Playback, source selection, and skip distance
- Automatic segment skipping
- Subtitle defaults, size, and background
- Provider addons and Nuvio scrapers
- Device storage and torrent-retention policy
- Account sign-in, manual sync, and sign-out

Settings that require long text, filesystem paths, or detailed desktop diagnostics may be easier to configure on another Cove device and sync when the value is profile-scoped. Device-local settings must still be changed on the television.

## Installation and updates

Android TV uses the same package name and production signing certificate as the touch application. Install and update it using the [Android and TV guide](install/android-tv.md). The first in-app update can require permission for Cove to install unknown apps, followed by Android's normal confirmation screen.

## Troubleshoot focus or playback

1. Confirm Cove was launched from its television/Leanback entry rather than through a forced phone compatibility mode.
2. Close overlays with Back and retry the control from the restored focus position.
3. Test a different source and lower quality for playback-specific failures.
4. Restart Cove if focus becomes unreachable after an Android system dialog.
5. Include the TV model, Android version, remote type, focused control, and exact button sequence in a bug report.

ADB logs can provide the needed runtime detail; see [Troubleshooting](troubleshooting.md).
