package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.data.CacheKind
import com.coveninja.cove.ui.tv.pages.cycleOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The storage controls' pure half.
 *
 * Each assertion was checked against a broken implementation before its comment was written.
 */
class StorageOptionsTest {

    @Test
    fun `zero reads as no limit rather than as nothing allowed`() {
        // The same value means "unbounded" in every one of these fields, and reading it as a
        // literal would put "0 GiB" on screen beside a cache that is not being limited at all.
        assertEquals("No limit", cacheLimitLabel(0))
        assertEquals("Whole file", downloadAheadLabel(0))
        assertEquals("Forever", cacheAgeLabel(0))
    }

    @Test
    fun `sizes are labelled in the unit they are large enough for`() {
        assertEquals("20 GiB", cacheLimitLabel(20L * 1024 * 1024 * 1024))
        assertEquals("512 MiB", downloadAheadLabel(512L * 1024 * 1024))
        // Fails on a formatter that stops at MiB: a one-gigabyte allowance would read as
        // "1024 MiB" beside a limit reading "20 GiB".
        assertEquals("1 GiB", downloadAheadLabel(1024L * 1024 * 1024))
    }

    @Test
    fun `unlimited is offered last in every list`() {
        // It is the most permissive option, not the smallest, so a list sorted purely by the
        // stored number would put it first and read as "off".
        assertEquals(0L, CacheLimitChoices.last())
        assertEquals(0L, DownloadAheadChoices.last())
        assertEquals(0, CacheAgeChoices.last())
    }

    @Test
    fun `a value not on the list is inserted in order rather than dropped`() {
        val choices = withCurrent(CacheLimitChoices, 7L * 1024 * 1024 * 1024, 0)
        // Reachable in ordinary use: Android defaults to 4 GiB, the desktop to 20, and the
        // policy is per device. Fails if the control silently shows a neighbouring value, which
        // misreports the setting, and fails if the value is appended after "No limit".
        assertTrue(7L * 1024 * 1024 * 1024 in choices)
        assertEquals(CacheLimitChoices.size + 1, choices.size)
        assertEquals(0L, choices.last())
        assertEquals(choices.dropLast(1).sorted(), choices.dropLast(1))
    }

    @Test
    fun `a value already on the list is not duplicated`() {
        // Fails if the insert runs unconditionally: the pill row would show "20 GiB" twice and
        // the television would need two presses to get past it.
        assertEquals(CacheLimitChoices, withCurrent(CacheLimitChoices, 20L * 1024 * 1024 * 1024, 0))
    }

    @Test
    fun `cycling an off-list value on a television reaches every option`() {
        val current = 7L * 1024 * 1024 * 1024
        val choices = withCurrent(CacheLimitChoices, current, 0)
        val visited = mutableListOf<Long>()
        var value = current
        repeat(choices.size) {
            value = cycleOption(choices, value)
            visited += value
        }
        // A full cycle has to come back to where it started, or a viewer who overshoots can
        // never return to the value their device was actually on.
        assertEquals(choices.toSet(), visited.toSet())
        assertEquals(current, visited.last())
    }

    @Test
    fun `every cache kind has a label and a count phrased for what it holds`() {
        // The screen renders whatever the host reports, so a kind added later without its words
        // would otherwise reach a viewer as an enum name.
        CacheKind.entries.forEach { kind ->
            assertTrue(cacheKindLabel(kind).isNotBlank())
            assertTrue(cacheKindDescription(kind).isNotBlank())
            assertTrue(cacheKindItems(kind, 2).startsWith("2 "))
        }
        // Singular where it matters: "1 downloads" is the kind of thing that reads as a bug in
        // the app rather than as a plural rule.
        assertEquals("1 download", cacheKindItems(CacheKind.TorrentDownloads, 1))
    }
}
