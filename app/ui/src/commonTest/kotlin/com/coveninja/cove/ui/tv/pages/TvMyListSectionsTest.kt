package com.coveninja.cove.ui.tv.pages

import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.MediaType as DomainMediaType
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.pages.mylist.MyListRow
import com.coveninja.cove.ui.pages.mylist.MyListView
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvMyListSectionsTest {

    private val today = LocalDate(2026, 8, 18)

    // Mutation applied to verify: dropped the takeIf { it.isNotEmpty() } → test failed, every
    // category produced a section and the page drew four headings over nothing.
    @Test
    fun `a category with nothing in it gets no row at all`() {
        val sections = tvMyListSections(
            rows = listOf(row("a", MyListCategory.Watching)),
            calendarItems = emptyList(),
            view = MyListView.Library,
            today = today,
        )

        assertEquals(1, sections.size)
        assertEquals(
            MyListCategory.Watching,
            (sections.single() as TvMyListSection.Category).category,
        )
    }

    // The order is the phone's, so the two screens do not disagree about which pile comes first.
    // Mutation applied to verify: reversed the category iteration → test failed.
    @Test
    fun `categories keep the order the rest of the app uses`() {
        val sections = tvMyListSections(
            rows = listOf(
                row("a", MyListCategory.Finished),
                row("b", MyListCategory.Watching),
                row("c", MyListCategory.WatchLater),
            ),
            calendarItems = emptyList(),
            view = MyListView.Library,
            today = today,
        )

        assertEquals(
            listOf(MyListCategory.Watching, MyListCategory.WatchLater, MyListCategory.Finished),
            sections.map { (it as TvMyListSection.Category).category },
        )
    }

    // The one that fails quietly. An available entry's date points backwards — often by months —
    // so grouping it by day as well would list it twice, with the second copy filed under a date
    // far enough in the past that nobody scrolls to it.
    // Mutation applied to verify: passed the unfiltered items to groupByDay → test failed, the
    // available title appeared in a day section too.
    @Test
    fun `a watchable entry appears once and never under its air date`() {
        val available = item(id = "waiting", date = "2026-06-01", available = true)
        val scheduled = item(id = "soon", date = "2026-08-20", available = false)

        val sections = tvMyListSections(
            rows = emptyList(),
            calendarItems = listOf(available, scheduled),
            view = MyListView.Calendar,
            today = today,
        )

        val dayTitles = sections.filterIsInstance<TvMyListSection.Day>()
            .flatMap { day -> day.day.items.map { it.title } }
        assertEquals(listOf(scheduled.title), dayTitles)
        assertTrue(sections.first() is TvMyListSection.Available)
    }

    // Mutation applied to verify: emitted the Available section unconditionally → test failed,
    // an empty "Ready to watch" row was drawn with no cards under it.
    @Test
    fun `with nothing waiting the calendar is only its day sections`() {
        val sections = tvMyListSections(
            rows = emptyList(),
            calendarItems = listOf(item(id = "soon", date = "2026-08-20", available = false)),
            view = MyListView.Calendar,
            today = today,
        )

        assertTrue(sections.none { it is TvMyListSection.Available })
        assertEquals(1, sections.count { it is TvMyListSection.Day })
    }

    private fun row(id: String, category: MyListCategory): MyListRow {
        val entry = LibraryEntry(
            id = id,
            tmdbId = id.hashCode(),
            mediaType = DomainMediaType.Movie,
            title = id,
            posterPath = "",
            voteAverage = 0.0,
            status = LibraryStatus.Watching,
        )
        return MyListRow(
            media = Media(
                id = id,
                tmdbId = entry.tmdbId,
                title = id,
                name = null,
                overview = null,
                released = null,
                firstAirDate = null,
                posterUrl = null,
                logoUrl = null,
                backdropUrl = null,
                rating = null,
                type = null,
                popularity = null,
                adult = null,
                originalLanguage = null,
            ),
            entry = entry,
            category = category,
        )
    }

    private fun item(id: String, date: String, available: Boolean) = CalendarItem(
        date = date,
        kind = if (available) CalendarItem.KIND_AVAILABLE else CalendarItem.KIND_EPISODE,
        tmdbId = id.hashCode(),
        mediaType = "tv",
        title = id,
        posterPath = "",
    )
}
