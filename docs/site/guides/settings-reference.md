# Settings reference

Open **Profile** and select a category to change Cove's behavior. Most changes are saved immediately. Settings are stored as a complete profile object, so use Cove's controls rather than editing database rows or partial API payloads.

## Scope at a glance

| Category | Typical scope | Notes |
|---|---|---|
| Account | Profile and account | Sync controls apply to the signed-in account |
| Addons | Profile | Can be inherited from the primary profile |
| Plugins | Profile, desktop only | Permissions and settings are approved separately per profile |
| Playback | Profile | Applied when new playback starts |
| Sources | Profile | LAN-source permission is security-sensitive |
| Subtitles | Profile | A track can still be changed during playback |
| Skipping | Profile | Works only when Cove has a matching segment timestamp |
| Content | Profile | Changes discovery and presentation |
| Network | Mixed | Upload preference follows the profile; remote access and its token stay on the device |
| Storage | Device | Downloads and caches never sync |
| Tracking | Profile integration | Trakt and Simkl; separate from Cove account sync |
| Advanced | Device | Includes mpv, performance, updates, and version information |

## Playback

- **Autoplay next episode** starts the following episode after the up-next countdown.
- **Remember position** resumes unfinished media instead of starting at zero.
- **Start muted**, **Default volume**, and **Remember the volume** control the initial audio state.
- **Skip step** sets the jump used by player buttons and ordinary arrow-key seeking, from 5 to 60 seconds.
- **Hardware decoding** uses the device decoder when compatible. Disable it when a driver produces corruption, wrong colors, or repeatable decoder failure.

## Sources

**Pick a source automatically** lets Cove choose instead of opening the picker whenever multiple candidates exist. The selection preference can be **Balanced**, **Quality first**, or **Most seeded**. **Show source details** controls whether quality, size, and provider are shown in the picker.

Ahead-of-time controls trade bandwidth for startup speed:

- **Keep yt-dlp up to date** lets Cove provision the helper used for supported video extras when no system copy exists.
- **Check sources are alive** probes candidates before presenting them.
- **Prefetch sources** warms source results in the background.
- **Prefetch the next episode** begins resolving the likely next episode while you watch.

**Allow sources on your local network** permits private and LAN addresses that the normal URL policy blocks. Enable it only for a provider you operate or trust on the current network.

## Subtitles and audio

Choose whether subtitles start enabled and select preferred subtitle and audio languages. **Original** audio follows the title's original language rather than one fixed language code.

Subtitle size ranges from 50% to 200%. Position controls distance from the bottom of the picture, and background adds a shaded readability box. In-player track choices and subtitle delay adjustments apply to the current playback session.

## Skipping

Automatic switches exist for intros, recaps, credits, and next-episode previews. Cove skips only when the media exposes a recognized embedded chapter or a compatible timestamp provider supplies that segment type. If automatic skipping is off, a manual skip action can appear while the playhead is inside the segment.

## Content

- **Hide spoilers** conceals unwatched episode titles and descriptions.
- **Interface language** follows the device by default or pins the locale used for catalog metadata. The current Compose controls themselves remain English-only.
- **Recommendations** selects Smart, Trending, similarity-based, or Custom discovery.
- **Scoring endpoint** appears only for Custom mode and must be an HTTPS service compatible with Cove's scoring request.

## Network

**Share back while streaming** controls torrent upload of pieces already downloaded. Turning it off prevents that upload but does not disable torrent playback.

**Reachable from other devices** starts Cove's authenticated LAN compatibility listener. A pairing token is generated when needed and is shown read-only. Treat the token like a password. Remote access is intended only for a trusted local network and does not provide TLS or internet-grade authentication.

## Storage

The Storage page measures current caches and lets you clear compatible categories. Clearing a cache can make the next lookup or playback slower but does not remove library records.

Torrent retention controls include a maximum size, download-ahead window, maximum idle age, and optional deletion shortly after watching. These apply only to the current device. Cove removes eligible data according to the configured policy; it does not delete synced library history with the media files.

## Advanced

**Low-performance mode** reduces nonessential page, card, and hero motion on the current device. Cove may suggest it when Android reports limited working memory.

The mpv configuration editor passes supported options to the native player. Invalid options are ignored or can prevent the desired behavior; use **Revert** to restore Cove's default text. This configuration is device-local.

Update-capable packages also show **Automatic updates** and **Check now**. The About card reports the running build version. See [Update Cove](updates.md) for platform support.

## Machine-managed values

Some stored fields deliberately have no direct control: the last measured bandwidth, current source preference, onboarding completion, remote token generation, and server update timestamps. Cove maintains these values to preserve compatibility and security.
