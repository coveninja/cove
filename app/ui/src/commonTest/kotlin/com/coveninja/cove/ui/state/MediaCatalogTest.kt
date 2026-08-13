package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.Media
import com.coveninja.cove.shared.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaCatalogTest {
    @Test
    fun `localized TMDB presentation replaces persisted library title and poster`() {
        val entry = LibraryEntry(
            id = "saved",
            tmdbId = 7,
            mediaType = MediaType.Movie,
            title = "Old English title",
            posterPath = "http://127.0.0.1:6969/api/img/w500/old.jpg",
            status = LibraryStatus.WatchLater,
        )
        val localized = Media(
            id = 7,
            title = "Yerel başlık",
            posterPath = "/localized.jpg",
            mediaType = MediaType.Movie,
        )

        val result = MediaCatalog(listOf(localized)).enrich(entry)

        assertEquals("Yerel başlık", result.title)
        assertEquals("https://image.tmdb.org/t/p/w500/localized.jpg", result.posterUrl)
    }
}
