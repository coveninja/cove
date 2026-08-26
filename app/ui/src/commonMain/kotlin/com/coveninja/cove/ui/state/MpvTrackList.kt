package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.network.CoveJson
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * mpv publishes its tracks as a JSON string on the `track-list` property, so this is where
 * that string becomes typed data.
 *
 * One parser for both hosts. There used to be two, identical line for line, in
 * `MpvVideoPlayerHost` and `AndroidMpvVideoPlayerHost` — and identical is not a property that
 * survives being edited twice. The divergence would show up as a phone whose track menu says
 * less than a desktop's, with nothing failing to announce it, which is exactly how the
 * `sub-add select` difference between the two hosts went unnoticed.
 *
 * Parsed defensively throughout: an unreadable track list must cost the track menus, not
 * playback. Every field past the id is optional, because mpv omits what it does not know —
 * a subtitle track has no `audio-channels`, and a track still being probed may have no codec.
 */
fun parseMpvTrackList(json: String): List<MediaTrack> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        CoveJson.parseToJsonElement(json).jsonArray.mapNotNull { element ->
            val track = element.jsonObject
            val kind = when (track["type"]?.jsonPrimitive?.contentOrNull) {
                "audio" -> TrackKind.Audio
                "sub" -> TrackKind.Subtitle
                else -> return@mapNotNull null
            }
            val id = track["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null

            fun text(name: String): String =
                track[name]?.jsonPrimitive?.contentOrNull.orEmpty().trim()

            fun flag(name: String): Boolean =
                track[name]?.jsonPrimitive?.booleanOrNull == true

            MediaTrack(
                id = id,
                kind = kind,
                title = text("title"),
                language = track["lang"]?.jsonPrimitive?.contentOrNull,
                selected = flag("selected"),
                // codec-desc is the readable one ("E-AC-3"); codec is the ffmpeg short name
                // ("eac3") and stands in when the demuxer offered no description.
                codec = text("codec-desc").ifBlank { text("codec") },
                channels = text("demux-channels"),
                sampleRateHz = track["demux-samplerate"]?.jsonPrimitive?.intOrNull ?: 0,
                isDefault = flag("default"),
                forced = flag("forced"),
                hearingImpaired = flag("hearing-impaired"),
                visualImpaired = flag("visual-impaired"),
                external = flag("external"),
                bitmap = flag("image"),
            )
        }
    }.getOrDefault(emptyList())
}

/** Splits a parsed track list into the two menus and works out what is selected in each. */
fun PlaybackStatus.withTracks(tracks: List<MediaTrack>): PlaybackStatus {
    val audio = tracks.filter { it.kind == TrackKind.Audio }
    val subtitles = tracks.filter { it.kind == TrackKind.Subtitle }
    return copy(
        audioTracks = audio,
        subtitleTracks = subtitles,
        selectedAudioId = audio.firstOrNull { it.selected }?.id,
        selectedSubtitleId = subtitles.firstOrNull { it.selected }?.id,
    )
}
