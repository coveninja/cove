# Playback and subtitles

Cove uses native mpv playback on desktop and Android. Source discovery and playback are separate: Cove gathers compatible candidates, applies the configured policy, and then gives one explicit source to the native player.

## Select a source

Turn **Pick a source automatically** on to let **Watch** play the best eligible source. It remains off by default, so multiple candidates open the picker unless the source was already remembered for this film or episode. Automatic selection aims for your **Preferred resolution** (1080p by default) and then applies one of three modes:

- **Balanced** weighs seeders against file size. Past a healthy swarm, more seeders matter little, so a 2 GB release with 85 seeders beats a 9 GB release with 90.
- **Quality first** takes the largest healthy file at the preferred resolution.
- **Most seeded** takes the largest swarm at the preferred resolution.

A release at the preferred resolution wins unless it is barely seeded; otherwise Cove falls back to the closest resolution, lower before higher. Releases whose names point to another language, such as dubbed foreign releases, rank lower.

Cove also reads release and file names. A name that carries the title ranks higher, whether it uses the English, original or a translated title, a small misspelling, or a common shortening such as initials ("HIMYM"). A translated title counts as the same title, and its language decides where it ranks, so a likely dub ranks below a release in your languages. A name Cove cannot place only ranks lower, because providers already look results up by the title itself. Only clear evidence keeps a source from playing automatically: a file named as a different episode, which could spoil the series, a torrent reporting zero seeders, or a cinema recording. A file naming a different year, often a remake, is ranked well down. These sources stay in the picker with an explanation, so you can still choose them.

The picker lists sources by resolution, highest first, and opens on the source automatic selection would play, marked **Best**: scroll up for higher resolutions such as 4K, down for 720p and below.

For the next episode of a series, the season pack that played the previous episode is preferred, which starts faster and keeps the same release.

The picker can show quality, size, provider, seeders, and other available attributes; turn off **Show source details** to see release names only.

Direct HTTP and torrent candidates can appear together. Torrent playback selects a playable video file and shows buffering or download progress while the native media boundary serves it to mpv.

Cove can probe candidates before showing them and prefetch likely sources or the next episode. These options improve startup reliability at the cost of extra network work; see the [settings reference](settings-reference.md).

## Recover from a failed source

When Cove picked the source automatically and it fails before anything plays, such as a torrent with no reachable peers or an expired link, Cove moves to the next eligible source in the ranked list. Each source is tried once, and the failure is shown when no eligible source is left. A source you chose yourself is never replaced.

A source failure during playback can trigger one automatic reconnect at that playback position. If playback advances far enough after recovery, a later interruption receives a fresh recovery attempt. Repeated failure at the same offset stops and presents explicit actions such as Retry, Sources, Pick another, or Try next. Cove does not change provider, quality, audio, or subtitle tracks after playback starts.

Unexpected end-of-file and network loss are failures, not proof that the title completed. When a direct stream repeatedly fails, choose another candidate manually. When a torrent stalls, compare seed availability and try a smaller or better-seeded release.

## Video decoding and performance

Hardware decoding is preferred when the device reports a compatible decoder. Disable **Hardware decoding** when a driver produces corruption, wrong colors, or repeatable failure. Software decoding uses more CPU, power, and battery.

On televisions and lower-end phones, start with a lower resolution or bitrate when playback cannot remain smooth. Low-performance mode reduces interface motion but does not make software video decoding cheaper.

The playback statistics overlay exposes useful native telemetry on desktop. Press `I` and record the decoder and dropped-frame information when reporting a performance problem.

## Player controls

The visible controls provide play/pause, seeking, volume, source or episode selection, audio and subtitle tracks, playback speed, fullscreen, and other platform-supported actions.

Press `?` during desktop playback for the complete shortcut sheet. Common controls include:

| Keys or action | Result |
|---|---|
| `Space` or `K` | Play or pause |
| Left/Right or `J`/`L` | Seek by the configured skip step |
| Shift+Left/Right | Seek by one second |
| `0`–`9` | Jump to that tenth of the file |
| `[` / `]` / Backspace | Slower, faster, or normal speed |
| Up/Down and `M` | Volume and mute |
| `C` / `A` | Cycle subtitle or audio tracks |
| `F` / `Esc` | Enter or leave fullscreen; Esc then closes playback |
| `I` / `S` | Statistics overlay or screenshot |
| Click / wheel | Play-pause or volume |
| Double-click an edge | Seek backward or forward |
| Double-click the center | Toggle fullscreen |

Android touch controls use the on-screen chrome and surface gestures. Android TV uses focusable controls and Back closes the innermost picker before leaving playback; see [TV and remote controls](tv-and-remote-controls.md).

## Audio and subtitles

Use the track menus to choose embedded or addon audio and subtitle tracks. Tracks are grouped by language where metadata allows it; opaque provider identifiers receive readable generated labels.

Desktop can load a local subtitle file from the subtitle menu. You can also drag a supported subtitle file over the playing video and drop it when the **Drop a subtitle file to use it** target appears. Local file loading is not offered on platforms without a compatible file chooser or desktop drop target.

The subtitle menu can adjust delay in small steps for a subtitle made for a different release cut. Try another subtitle source before applying a large offset. Default language, size, position, and background live under **Profile → Subtitles**.

## Chapters and segment skipping

The seek bar shows chapters when the media exposes them. Cove recognizes conservative aliases for intros, recaps, credits, and next-episode previews. A compatible external timestamp can supply the same segment types; embedded media chapters take precedence for matching types.

When automatic skipping is enabled for a known type, Cove jumps to the end of that segment. Otherwise a manual skip action appears while the playhead is inside it. Ordinary chapters remain available for previous/next navigation.

## Progress, completion, and up next

Cove stores position per profile when Remember position is enabled. The next Watch action becomes a Continue target when the saved state identifies a resumable title or episode and position.

Series can show an up-next countdown near completion. Autoplay begins the following episode unless you stop the countdown; otherwise the overlay can be closed without starting another title.

For persistent failures, include the exact title, season and episode, selected source and provider, device model or GPU, decode mode, and logs in a bug report.
