package com.coveninja.cove.ui.model

/**
 * The kinds of extra a title carries, in the order they are worth offering.
 *
 * TMDB documents eight video types, but the field is contributor-entered and its
 * spelling drifts ("Behind the Scenes" vs "behind the scenes"), so matching is
 * loose and anything unrecognised lands in [Other] rather than disappearing.
 */
enum class VideoCategory(val label: String) {
    Trailer("Trailers"),
    Teaser("Teasers"),
    Clip("Clips"),
    Featurette("Featurettes"),
    BehindTheScenes("Behind the Scenes"),
    Bloopers("Bloopers"),
    OpeningCredits("Opening Credits"),
    Recap("Recaps"),
    Other("Other"),
}

/** Case and spacing vary between entries, so both are stripped before matching. */
fun videoCategoryOf(type: String?): VideoCategory {
    val normalized = type?.lowercase()?.filter { it.isLetter() }.orEmpty()
    return when (normalized) {
        "trailer", "trailers" -> VideoCategory.Trailer
        "teaser", "teasers" -> VideoCategory.Teaser
        "clip", "clips" -> VideoCategory.Clip
        "featurette", "featurettes" -> VideoCategory.Featurette
        "behindthescenes" -> VideoCategory.BehindTheScenes
        "blooper", "bloopers" -> VideoCategory.Bloopers
        "openingcredits" -> VideoCategory.OpeningCredits
        "recap", "recaps" -> VideoCategory.Recap
        else -> VideoCategory.Other
    }
}

/**
 * The categories [videos] actually contains, in enum order.
 *
 * Only these become filter chips: a chip for a category with nothing behind it
 * is a button that empties the row, which is worse than not offering it.
 */
fun videoCategories(videos: List<MediaVideo>): List<VideoCategory> =
    VideoCategory.entries.filter { category -> videos.any { it.category == category } }

/** Null is "All" — the unfiltered row. */
fun List<MediaVideo>.inCategory(category: VideoCategory?): List<MediaVideo> =
    if (category == null) this else filter { it.category == category }

/**
 * Trailers first, then the rest in category order; official uploads before fan
 * mirrors, and newest first within that.
 *
 * TMDB returns videos in no useful order at all — a 2011 teaser routinely sits
 * above the release trailer — and the first card is the one most people click.
 * [MediaVideo.publishedAt] is an ISO-8601 UTC timestamp, so comparing the strings
 * orders them correctly without parsing a date.
 */
fun List<MediaVideo>.sortedForDisplay(): List<MediaVideo> = sortedWith(
    compareBy<MediaVideo> { it.category.ordinal }
        .thenByDescending { it.official }
        .thenByDescending { it.publishedAt.orEmpty() },
)
