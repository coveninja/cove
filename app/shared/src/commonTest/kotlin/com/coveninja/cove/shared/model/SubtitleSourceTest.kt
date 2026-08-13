package com.coveninja.cove.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleSourceTest {
    @Test
    fun `descriptive ids and filenames remain readable`() {
        assertEquals("Signs & Songs", SubtitleSource(id = "Signs & Songs").displayName)
        assertEquals("English: Full", SubtitleSource(id = "English: Full").displayName)
        assertEquals("Full SDH.srt", SubtitleSource(id = "provider/en/Full SDH.srt").displayName)
        assertEquals(
            "Movie.Name.2026.English.srt",
            SubtitleSource(id = "files/Movie.Name.2026.English.srt?token=private").displayName,
        )
    }

    @Test
    fun `encoded URLs and provider keys are not exposed as titles`() {
        assertNull(
            SubtitleSource(
                id = "v3_aHR0cHM6Ly9zdWJzNS5zdHJlbWlvL2VuL2Rvd25sb2FkLzE5NTc3NDUxMTc",
            ).displayName,
        )
        assertNull(SubtitleSource(id = "https://subs.example/movie.srt?id=42").displayName)
        assertNull(SubtitleSource(id = "aHR0cHM6Ly9zdWJzLmV4YW1wbGUvMTIzNDU2Nzg5").displayName)
        assertNull(SubtitleSource(id = "opensubtitles:19573745117").displayName)
        assertNull(SubtitleSource(id = "19573745117").displayName)
        assertNull(SubtitleSource(id = "").displayName)
    }
}
