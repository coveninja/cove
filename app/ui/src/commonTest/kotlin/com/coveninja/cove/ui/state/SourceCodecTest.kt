package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.StreamSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceCodecTest {
    @Test
    fun `parses advertised video codec and profiles from release text`() {
        assertEquals(
            StreamCodecMetadata(StreamVideoCodec.Hevc, is10Bit = true, isDolbyVision = true),
            StreamSource(title = "Movie.2160p.DV.HEVC.Main 10").codecMetadata(),
        )
        assertEquals(
            StreamCodecMetadata(StreamVideoCodec.H264, is10Bit = true, isDolbyVision = false),
            StreamSource(name = "1080p x264 Hi10P").codecMetadata(),
        )
        assertEquals(StreamVideoCodec.Av1, StreamSource(title = "Movie.AV1").codecMetadata().codec)
        assertEquals(StreamVideoCodec.Vp9, StreamSource(title = "Movie.VP9").codecMetadata().codec)
    }

    @Test
    fun `bare dv needs video context and an untagged source stays unknown`() {
        assertFalse(StreamSource(title = "Movie.DV.Group").codecMetadata().isDolbyVision)
        assertTrue(StreamSource(title = "Movie.4K.DV.Group").codecMetadata().isDolbyVision)

        val compatibility = StreamSource(title = "Movie.1080p.WEB-DL")
            .compatibilityWith(
                VideoCodecCapabilities(
                    h264 = VideoDecoderSupport.Unsupported,
                    hevc = VideoDecoderSupport.Unsupported,
                    av1 = VideoDecoderSupport.Unsupported,
                    vp9 = VideoDecoderSupport.Unsupported,
                ),
            )
        assertEquals(VideoDecoderSupport.Unknown, compatibility.support)
        assertNull(compatibility.codecLabel)
        assertTrue(compatibility.selectable)
        assertTrue(compatibility.automaticallyEligible)
    }

    @Test
    fun `profile-specific support controls compatibility`() {
        val capabilities = VideoCodecCapabilities(
            h264 = VideoDecoderSupport.Hardware,
            h264High10 = VideoDecoderSupport.Unsupported,
            hevc = VideoDecoderSupport.Hardware,
            hevcMain10 = VideoDecoderSupport.SoftwareOnly,
            av1 = VideoDecoderSupport.Unsupported,
            vp9 = VideoDecoderSupport.Hardware,
            dolbyVision = VideoDecoderSupport.Unsupported,
        )

        assertEquals(
            VideoDecoderSupport.Hardware,
            StreamSource(title = "Movie.x265").compatibilityWith(capabilities).support,
        )
        assertEquals(
            VideoDecoderSupport.SoftwareOnly,
            StreamSource(title = "Movie.x265.10bit").compatibilityWith(capabilities).support,
        )
        assertEquals(
            VideoDecoderSupport.Unsupported,
            StreamSource(title = "Movie.x264.Hi10P").compatibilityWith(capabilities).support,
        )
        assertEquals(
            VideoDecoderSupport.Unsupported,
            StreamSource(title = "Movie.4K.DV.x265").compatibilityWith(capabilities).support,
        )
    }

    @Test
    fun `software-only sources are manual and unsupported sources cannot be selected`() {
        val software = StreamSource(title = "Movie.AV1").compatibilityWith(
            VideoCodecCapabilities(av1 = VideoDecoderSupport.SoftwareOnly),
        )
        assertTrue(software.selectable)
        assertFalse(software.automaticallyEligible)

        val unsupported = StreamSource(title = "Movie.AV1").compatibilityWith(
            VideoCodecCapabilities(av1 = VideoDecoderSupport.Unsupported),
        )
        assertFalse(unsupported.selectable)
        assertFalse(unsupported.automaticallyEligible)
    }
}
