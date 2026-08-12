package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.StreamSource

/** How confidently the platform can decode a video codec or profile. */
enum class VideoDecoderSupport {
    Hardware,
    SoftwareOnly,
    Unsupported,
    Unknown,
}

/**
 * Platform decoder capabilities used before a stream is handed to mpv.
 *
 * Every field defaults to [VideoDecoderSupport.Unknown]. Only Android currently
 * probes these values; leaving a capability unknown is intentionally permissive.
 */
data class VideoCodecCapabilities(
    val h264: VideoDecoderSupport = VideoDecoderSupport.Unknown,
    val h264High10: VideoDecoderSupport = VideoDecoderSupport.Unknown,
    val hevc: VideoDecoderSupport = VideoDecoderSupport.Unknown,
    val hevcMain10: VideoDecoderSupport = VideoDecoderSupport.Unknown,
    val av1: VideoDecoderSupport = VideoDecoderSupport.Unknown,
    val vp9: VideoDecoderSupport = VideoDecoderSupport.Unknown,
    val dolbyVision: VideoDecoderSupport = VideoDecoderSupport.Unknown,
)

enum class StreamVideoCodec { H264, Hevc, Av1, Vp9, Unknown }

/** Codec/profile hints parsed from addon-provided release text. */
data class StreamCodecMetadata(
    val codec: StreamVideoCodec,
    val is10Bit: Boolean,
    val isDolbyVision: Boolean,
)

/** The result shown in the source picker and enforced by PlaybackSession. */
data class StreamCompatibility(
    val codecLabel: String?,
    val support: VideoDecoderSupport,
) {
    val automaticallyEligible: Boolean
        get() = support == VideoDecoderSupport.Hardware || support == VideoDecoderSupport.Unknown

    val selectable: Boolean
        get() = support != VideoDecoderSupport.Unsupported
}

data class StreamChoice(
    val source: StreamSource,
    val compatibility: StreamCompatibility,
)

internal fun StreamCompatibility.selectionPriority(): Int = when (support) {
    VideoDecoderSupport.Hardware, VideoDecoderSupport.Unknown -> 0
    VideoDecoderSupport.SoftwareOnly -> 1
    VideoDecoderSupport.Unsupported -> 2
}

private val av1Pattern = Regex("\\bav1\\b", RegexOption.IGNORE_CASE)
private val vp9Pattern = Regex("\\bvp9\\b", RegexOption.IGNORE_CASE)
private val hevcPattern = Regex("\\b(?:x\\.?265|h\\.?265|hevc)\\b", RegexOption.IGNORE_CASE)
private val h264Pattern = Regex("\\b(?:x\\.?264|h\\.?264|avc)\\b", RegexOption.IGNORE_CASE)
private val tenBitPattern = Regex("10.?bits?\\b|\\bhi10p?\\b|\\bmain ?10\\b", RegexOption.IGNORE_CASE)
private val dolbyVisionPattern = Regex("dolby.?vision|\\bdovi\\b", RegexOption.IGNORE_CASE)
private val bareDolbyVisionPattern = Regex("(?:^|[. _-])dv(?:$|[. _-])", RegexOption.IGNORE_CASE)
private val dolbyVisionContextPattern = Regex("\\b(?:2160p?|4k|uhd|hdr)\\b", RegexOption.IGNORE_CASE)

fun StreamSource.codecMetadata(): StreamCodecMetadata {
    val text = "${name.orEmpty()} ${title.orEmpty()}"
    val codec = when {
        av1Pattern.containsMatchIn(text) -> StreamVideoCodec.Av1
        vp9Pattern.containsMatchIn(text) -> StreamVideoCodec.Vp9
        hevcPattern.containsMatchIn(text) -> StreamVideoCodec.Hevc
        h264Pattern.containsMatchIn(text) -> StreamVideoCodec.H264
        else -> StreamVideoCodec.Unknown
    }
    val dolbyVision = dolbyVisionPattern.containsMatchIn(text) ||
        (bareDolbyVisionPattern.containsMatchIn(text) && dolbyVisionContextPattern.containsMatchIn(text))
    return StreamCodecMetadata(
        codec = codec,
        is10Bit = tenBitPattern.containsMatchIn(text),
        isDolbyVision = dolbyVision,
    )
}

fun StreamSource.compatibilityWith(capabilities: VideoCodecCapabilities): StreamCompatibility {
    val metadata = codecMetadata()
    val baseSupport = when (metadata.codec) {
        StreamVideoCodec.H264 -> if (metadata.is10Bit) capabilities.h264High10 else capabilities.h264
        StreamVideoCodec.Hevc -> if (metadata.is10Bit) capabilities.hevcMain10 else capabilities.hevc
        StreamVideoCodec.Av1 -> capabilities.av1
        StreamVideoCodec.Vp9 -> capabilities.vp9
        // A bare 10-bit marker is usually HEVC Main 10. Preserve the historical
        // Android safeguard while leaving completely untagged releases unknown.
        StreamVideoCodec.Unknown -> if (metadata.is10Bit) {
            capabilities.hevcMain10
        } else {
            VideoDecoderSupport.Unknown
        }
    }
    val support = if (metadata.isDolbyVision) {
        combineSupport(baseSupport, capabilities.dolbyVision)
    } else {
        baseSupport
    }
    return StreamCompatibility(metadata.codecLabel(), support)
}

private fun StreamCodecMetadata.codecLabel(): String? {
    val base = when (codec) {
        StreamVideoCodec.H264 -> "H.264"
        StreamVideoCodec.Hevc -> "H.265"
        StreamVideoCodec.Av1 -> "AV1"
        StreamVideoCodec.Vp9 -> "VP9"
        StreamVideoCodec.Unknown -> if (is10Bit) "10-bit" else null
    }?.let { if (is10Bit && codec != StreamVideoCodec.Unknown) "$it 10-bit" else it }

    return when {
        isDolbyVision && base != null -> "$base · Dolby Vision"
        isDolbyVision -> "Dolby Vision"
        else -> base
    }
}

/** The weakest known requirement determines whether the complete stream is safe. */
internal fun combineSupport(
    first: VideoDecoderSupport,
    second: VideoDecoderSupport,
): VideoDecoderSupport = when {
    first == VideoDecoderSupport.Unsupported || second == VideoDecoderSupport.Unsupported ->
        VideoDecoderSupport.Unsupported
    first == VideoDecoderSupport.SoftwareOnly || second == VideoDecoderSupport.SoftwareOnly ->
        VideoDecoderSupport.SoftwareOnly
    first == VideoDecoderSupport.Unknown || second == VideoDecoderSupport.Unknown ->
        VideoDecoderSupport.Unknown
    else -> VideoDecoderSupport.Hardware
}
