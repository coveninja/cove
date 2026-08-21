# Playback and subtitles

Cove uses native mpv playback on desktop and Android. Stream discovery and playback are separate steps: Cove first gathers compatible sources, then applies the configured sort and selection policy.

## Stream selection

You can choose a source manually or let Cove rank sources by quality, size, reliability, or connection-speed match. A source that fails during playback may be retried once; Cove does not silently move to another source or quality.

## Video decoding

Hardware decoding is preferred when the device reports a compatible decoder. If a stream is not supported in hardware, choose an available software-decoding option. Options with no matching decoder are disabled rather than pretending they can play.

Software decoding uses more CPU and battery. On televisions and lower-end phones, start with a lower resolution or bitrate when playback cannot remain smooth.

## Audio and subtitles

Use the in-player track menu to select audio and subtitle tracks. Addon subtitles can be proxied and converted from SRT to WebVTT when required by the playback boundary.

Opaque provider identifiers are presented as readable generated labels when no language or title is available. If subtitles are out of sync, try another subtitle source before changing playback sources.

## Progress and completion

Cove records position and completion per profile. A network interruption or a source reaching an unexpected EOF is not automatically treated as a completed title.

For persistent failures, include the exact title, season and episode, selected source, device model or GPU, and logs in a bug report.

