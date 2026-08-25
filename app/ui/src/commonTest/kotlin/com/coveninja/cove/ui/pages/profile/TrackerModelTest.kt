package com.coveninja.cove.ui.pages.profile

import com.coveninja.cove.shared.data.TrackerState
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.TrackerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TrackerModelTest {
    private val now = Instant.parse("2026-08-25T12:00:00Z")

    @Test
    fun `a linked account that failed its last sync asks for attention`() {
        // Mutation check: fold syncError into the Linked branch and this reads Linked —
        // a failed reconciliation would show the same settled mark as a successful one.
        assertEquals(
            TrackerTone.Attention,
            trackerTone(TrackerState.Linked("viewer", syncError = "Simkl sync returned HTTP 502")),
        )
        assertEquals(TrackerTone.Linked, trackerTone(TrackerState.Linked("viewer")))
    }

    @Test
    fun `a pending link is its own tone rather than a shade of not-connected`() {
        // Mutation check: map Pending to Off and the one state that needs the viewer to go
        // and do something looks exactly like the one that does not.
        assertEquals(TrackerTone.Waiting, trackerTone(TrackerState.Pending("ABCD", "url")))
        assertEquals(TrackerTone.Off, trackerTone(TrackerState.Unlinked()))
        assertEquals(TrackerTone.Attention, trackerTone(TrackerState.Unlinked("Trakt declined.")))
    }

    @Test
    fun `a syncing account says so in its status`() {
        // Mutation check: drop the syncing branch and a running sync still reads
        // "Connected", leaving the press with no acknowledgement anywhere on the card.
        assertEquals(
            "Syncing",
            trackerStatusLabel(TrackerState.Linked("viewer", syncing = true)),
        )
        assertEquals("Connected", trackerStatusLabel(TrackerState.Linked("viewer")))
    }

    @Test
    fun `no status label ever carries a username`() {
        // The provider's name stays on the card and the pill carries only the state. A label
        // that became the username would leave the card unidentifiable at the moment it
        // finally had something to report. Mutation check: return the username for Linked
        // and this fails on the account it is most likely to have been written for.
        val states = listOf(
            TrackerState.Loading,
            TrackerState.Unconfigured("no credentials"),
            TrackerState.Unlinked(),
            TrackerState.Unlinked("declined"),
            TrackerState.Pending("ABCD", "url"),
            TrackerState.Linked("viewer"),
            TrackerState.Linked("viewer", syncing = true),
            TrackerState.Linked("viewer", syncError = "boom"),
        )
        states.forEach { state ->
            assertFalse(trackerStatusLabel(state).contains("viewer"), "$state")
        }
    }

    @Test
    fun `an error outranks every other detail line`() {
        // Mutation check: order the Linked branches the other way and a sync failure is
        // replaced by "Last synced just now" — the reason vanishes with nothing said.
        val failed = TrackerState.Linked(
            username = "viewer",
            lastSyncAt = now,
            syncError = "Simkl sync/all-items returned HTTP 502",
        )
        assertEquals(
            "Simkl sync/all-items returned HTTP 502",
            trackerDetail(failed, TrackerProvider.Simkl, now),
        )
    }

    @Test
    fun `a never-synced account says so rather than implying it is in step`() {
        assertEquals(
            "Connected as viewer. Nothing reconciled from this device yet.",
            trackerDetail(TrackerState.Linked("viewer"), TrackerProvider.Trakt, now),
        )
        // Mutation check: drop the null branch and an account that has never reconciled
        // reads "Last synced just now", which is the opposite of what happened.
        assertEquals(
            "Connected as viewer. Last synced 5 minutes ago.",
            trackerDetail(
                TrackerState.Linked("viewer", lastSyncAt = now - 5.minutes),
                TrackerProvider.Trakt,
                now,
            ),
        )
    }

    @Test
    fun `codes are grouped for transcription but a formatted code is left alone`() {
        // Mutation check: drop the chunking and the code renders as one run of characters,
        // which is where re-reading the same character happens.
        assertEquals(listOf("AB", "CD", "EF"), groupUserCode("ABCDEF"))
        assertEquals(listOf("AB", "C"), groupUserCode("ABC"))
        // Mutation check: remove the punctuation guard and "COVE-1234" is re-chunked into
        // "CO VE -1 23 4", cutting across the separator the tracker chose.
        assertEquals(listOf("COVE-1234"), groupUserCode("COVE-1234"))
        assertEquals(emptyList(), groupUserCode("   "))
    }

    @Test
    fun `the countdown rounds up so a live code never reads as zero`() {
        // Mutation check: round down instead and a code with 30 seconds on it shows "0m
        // left" while it is still perfectly usable.
        assertEquals("2m left", codeCountdown(now + 61.seconds, now))
        assertEquals("1m left", codeCountdown(now + 60.seconds, now))
        assertEquals("59s left", codeCountdown(now + 59.seconds, now))
        assertEquals("Expired", codeCountdown(now - 1.seconds, now))
        // A tracker that did not say has nothing to count down.
        assertNull(codeCountdown(null, now))
    }

    @Test
    fun `the last minute is flagged`() {
        // Mutation check: compare against a wider window and the warning fires while there
        // are still minutes left, which trains people to ignore it.
        assertTrue(codeExpiringSoon(now + 30.seconds, now))
        assertFalse(codeExpiringSoon(now + 90.seconds, now))
        assertFalse(codeExpiringSoon(null, now))
    }

    @Test
    fun `each tracker's switches read and write only its own settings fields`() {
        // Four when-branches that look alike and would pass review swapped, leaving one
        // tracker's switch driving the other's account. Walking every provider is what makes
        // that impossible to add a third one and forget. Mutation check: swap either pair of
        // branches and the round trip below lands on the wrong field.
        TrackerProvider.entries.forEach { provider ->
            val others = TrackerProvider.entries - provider

            val scrobbled = AppSettings().withScrobbleEnabled(provider, false)
            assertFalse(scrobbled.scrobbleEnabled(provider), "$provider scrobble")
            others.forEach { other ->
                assertTrue(scrobbled.scrobbleEnabled(other), "$provider leaked into $other")
            }

            val synced = AppSettings().withSyncEnabled(provider, true)
            assertTrue(synced.syncEnabled(provider), "$provider sync")
            others.forEach { other ->
                assertFalse(synced.syncEnabled(other), "$provider leaked into $other")
            }
        }
    }

    @Test
    fun `the two switches stay independent of each other`() {
        // Mutation check: have withSyncEnabled copy the scrobble field and turning library
        // sync on silently turns scrobbling off with it.
        val settings = AppSettings()
            .withScrobbleEnabled(TrackerProvider.Simkl, false)
            .withSyncEnabled(TrackerProvider.Simkl, true)

        assertFalse(settings.scrobbleEnabled(TrackerProvider.Simkl))
        assertTrue(settings.syncEnabled(TrackerProvider.Simkl))
    }
}
