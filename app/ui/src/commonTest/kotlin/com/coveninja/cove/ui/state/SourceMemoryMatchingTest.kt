package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.data.SourceMemory
import com.coveninja.cove.shared.model.StreamSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Finding a remembered source again in a listing that is built fresh every time.
 *
 * Getting this wrong is invisible until it plays the wrong thing: too loose a match starts a
 * release nobody picked, too strict a one quietly stops remembering. Every case here was
 * confirmed to fail against a broken implementation before its comment was written.
 */
class SourceMemoryMatchingTest {
    private fun choice(
        name: String,
        addon: String = "Provider",
        hash: String? = null,
        support: VideoDecoderSupport = VideoDecoderSupport.Hardware,
    ) = StreamChoice(
        source = StreamSource(name = name, title = name, infoHash = hash, addonName = addon),
        compatibility = StreamCompatibility(codecLabel = null, support = support),
    )

    @Test
    fun `the exact release is found again by identity rather than by position`() {
        val wanted = choice("Show.S02E04.1080p.WEB-DL", hash = "a".repeat(40))
        val listing = listOf(choice("Show.S02E04.2160p.WEB-DL", hash = "b".repeat(40)), wanted)

        // The listing is re-ranked on every play, so position is not identity. Fails if the
        // match is made on order: resuming would start whatever now sorts first.
        assertEquals(wanted, listing.matchingRemembered(wanted.source.toSourceMemory()))
    }

    @Test
    fun `a remembered release that is gone matches nothing`() {
        val listing = listOf(choice("Show.S02E04.2160p", hash = "b".repeat(40)))

        // Fails if absence is answered with a near-match: the picker is the right answer when
        // the file the viewer chose is no longer on offer.
        assertNull(listing.matchingRemembered(SourceMemory(releaseKey = "a".repeat(40))))
        assertNull(listing.matchingRemembered(SourceMemory.None))
    }

    @Test
    fun `a remembered release this device cannot decode is not started unasked`() {
        val unsupported = choice(
            "Show.S02E04.AV1",
            hash = "a".repeat(40),
            support = VideoDecoderSupport.Unsupported,
        )

        // It stays in the list — knowing the copy exists is worth more than a shorter list —
        // but skipping the picker for it would start something that cannot play. Fails without
        // the selectable check.
        assertNull(
            listOf(unsupported).matchingRemembered(unsupported.source.toSourceMemory()),
        )
    }

    @Test
    fun `the next episode leads with the provider and quality that worked`() {
        val other = choice("Show.S02E05.1080p.WEB-DL", addon = "Other")
        val same = choice("Show.S02E05.1080p.WEB-DL", addon = "Provider")
        val memory = SourceMemory(releaseKey = "gone", addonName = "Provider", quality = "1080p")

        val promoted = listOf(other, same).promoteRemembered(memory)

        // The remembered file does not exist for this episode, so the memory can only be a
        // preference. Fails if the promotion is dropped: a viewer who settled on one provider
        // gets a different one every episode, which is what this feature is for.
        assertEquals("Provider", promoted.first().source.addonName)
        assertTrue(promoted.first().remembered)
        assertFalse(promoted.last().remembered)
    }

    @Test
    fun `promotion never crosses a compatibility tier`() {
        val hardware = choice("Show.S02E05.1080p", addon = "Other")
        val softwareOnly = choice(
            "Show.S02E05.1080p",
            addon = "Provider",
            support = VideoDecoderSupport.SoftwareOnly,
        )
        val memory = SourceMemory(releaseKey = "gone", addonName = "Provider", quality = "1080p")

        val promoted = listOf(hardware, softwareOnly).promoteRemembered(memory)

        // Fails if the memory sorts above the codec ordering: a phone would be handed a
        // software-decoded source — and stutter through it — because of a choice made on a
        // desktop. The row is still marked, just not first.
        assertEquals("Other", promoted.first().source.addonName)
        assertTrue(promoted.last().remembered)
    }

    @Test
    fun `half a resemblance is not a match`() {
        val memory = SourceMemory(releaseKey = "gone", addonName = "Provider", quality = "1080p")

        // Same provider, different quality: somebody who settled on 1080p did not ask for the
        // 4K remux. Fails if either half of the comparison is dropped.
        assertFalse(
            listOf(choice("Show.S02E05.2160p", addon = "Provider"))
                .promoteRemembered(memory)
                .single()
                .remembered,
        )
        // Same quality, different provider.
        assertFalse(
            listOf(choice("Show.S02E05.1080p", addon = "Elsewhere"))
                .promoteRemembered(memory)
                .single()
                .remembered,
        )
        // A memory that knows neither cannot promote anything: that is the ranking's job.
        assertFalse(
            listOf(choice("Show.S02E05.1080p", addon = "Provider"))
                .promoteRemembered(SourceMemory(releaseKey = "gone"))
                .single()
                .remembered,
        )
    }

    @Test
    fun `an empty memory leaves the listing exactly as it was`() {
        val listing = listOf(choice("a"), choice("b"))

        // Nothing has been played, so nothing is preferred. Two things guarantee that — the
        // early return, and `matches` refusing a memory that names no provider — so this pins
        // the outcome they both exist for rather than either of them: a profile with no history
        // sees the ranking's own order, with no row claiming to be the last one used.
        assertEquals(listing, listing.promoteRemembered(SourceMemory.None))
    }
}
