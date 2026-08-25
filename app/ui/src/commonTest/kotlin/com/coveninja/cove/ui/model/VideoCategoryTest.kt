package com.coveninja.cove.ui.model

import kotlin.test.Test
import kotlin.test.assertEquals

private fun video(
    id: String,
    type: String?,
    official: Boolean = false,
    publishedAt: String? = null,
) = MediaVideo(
    id = id,
    title = id,
    thumbnailUrl = null,
    type = type,
    url = "https://www.youtube.com/watch?v=$id",
    official = official,
    publishedAt = publishedAt,
)

class VideoCategoryTest {

    // TMDB's type field is contributor-entered, so the same category arrives
    // spelled several ways across titles.
    @Test
    fun `spelling and spacing do not split a category`() {
        assertEquals(VideoCategory.BehindTheScenes, videoCategoryOf("Behind the Scenes"))
        assertEquals(VideoCategory.BehindTheScenes, videoCategoryOf("behind the scenes"))
        assertEquals(VideoCategory.BehindTheScenes, videoCategoryOf("BehindTheScenes"))
        assertEquals(VideoCategory.Trailer, videoCategoryOf("Trailer "))
    }

    // Anything unrecognised has to stay reachable: a video filed under a type
    // nobody anticipated is still a video someone can watch.
    @Test
    fun `an unrecognised type falls into Other`() {
        assertEquals(VideoCategory.Other, videoCategoryOf("Making Of"))
        assertEquals(VideoCategory.Other, videoCategoryOf(""))
        assertEquals(VideoCategory.Other, videoCategoryOf(null))
    }

    @Test
    fun `only categories with videos behind them become chips`() {
        val videos = listOf(
            video("a", "Trailer"),
            video("b", "Featurette"),
            video("c", "Trailer"),
        )

        assertEquals(
            listOf(VideoCategory.Trailer, VideoCategory.Featurette),
            videoCategories(videos),
        )
    }

    @Test
    fun `a category keeps only its own videos and null keeps everything`() {
        val videos = listOf(
            video("a", "Trailer"),
            video("b", "Clip"),
            video("c", "Trailer"),
        )

        assertEquals(listOf("b"), videos.inCategory(VideoCategory.Clip).map { it.id })
        assertEquals(listOf("a", "b", "c"), videos.inCategory(null).map { it.id })
    }

    // The first card is the one most people click, and TMDB's own order routinely
    // puts a decade-old teaser there.
    @Test
    fun `trailers lead, then official uploads, then the newest`() {
        val videos = listOf(
            video("old-trailer", "Trailer", official = true, publishedAt = "2019-01-01T00:00:00Z"),
            video("clip", "Clip", official = true, publishedAt = "2021-01-01T00:00:00Z"),
            video("mirror", "Trailer", official = false, publishedAt = "2020-06-01T00:00:00Z"),
            video("new-trailer", "Trailer", official = true, publishedAt = "2020-01-01T00:00:00Z"),
        )

        assertEquals(
            listOf("new-trailer", "old-trailer", "mirror", "clip"),
            videos.sortedForDisplay().map { it.id },
        )
    }
}
