package com.coveninja.cove.backend.quality

import com.coveninja.cove.backend.addons.AddonStream
import com.coveninja.cove.shared.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QualityServiceTest {
    @Test
    fun `typed ids are normalized deduplicated and invalid values skipped`() {
        assertEquals(
            listOf(
                QualityMediaId("movie:603", MediaType.Movie, 603),
                QualityMediaId("tv:1396", MediaType.Tv, 1396),
            ),
            parseQualityIds("603,tv:1396,movie:603,foo:1,bad"),
        )
        assertFailsWith<IllegalArgumentException> { parseQualityIds("") }
        assertFailsWith<IllegalArgumentException> {
            parseQualityIds((1..101).joinToString(","))
        }
    }

    @Test
    fun `quality inference preserves strongest legacy ordering`() {
        assertEquals(
            "4k hdr",
            maxQuality(listOf(
                AddonStream(name = "Provider\n1080p", title = "release"),
                AddonStream(title = "Movie 2160p HDR"),
                AddonStream(title = "Movie CAM"),
            )),
        )
        assertEquals("4k dv", maxQuality(listOf(AddonStream(title = "Dolby Vision release"))))
        assertEquals("", maxQuality(listOf(AddonStream(title = "unknown"))))
    }
}
