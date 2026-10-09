package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.data.SourceMemory
import com.coveninja.cove.shared.model.StreamSource

/**
 * How a remembered source is found again in a fresh listing.
 *
 * Two different questions, which is why there are two functions. Resuming the *same* episode
 * asks whether that exact release is still on offer, and if it is there is nothing to decide:
 * the viewer answered the picker's question the first time round. Starting the *next* episode
 * can only ask something weaker — that file does not exist for this episode — so the memory
 * becomes a preference, and the listing is reordered rather than skipped.
 */

/** The release identity a memory stores, read back off a candidate. */
internal fun StreamSource.toSourceMemory(): SourceMemory = SourceMemory(
    releaseKey = releaseKey(),
    addonName = addonName.orEmpty(),
    quality = qualityLabel().orEmpty(),
    displayName = displayLabel(),
)

/**
 * The candidate that *is* the remembered release, or null.
 *
 * Selectable only: a release whose codec this device cannot decode is still listed — knowing a
 * 4K copy exists is worth more than a shorter list — but starting it without asking would be a
 * worse answer than opening the picker.
 */
internal fun List<StreamChoice>.matchingRemembered(memory: SourceMemory): StreamChoice? {
    if (memory.isEmpty) return null
    return firstOrNull { it.compatibility.selectable && it.source.releaseKey() == memory.releaseKey }
}

/**
 * Reorders a listing so the remembered source leads, and marks it.
 *
 * Within its compatibility tier and never across one: promoting a software-only encode past a
 * source the device can decode in hardware would undo the ordering that keeps a phone from
 * stuttering, and the memory is a preference rather than an instruction. The sort is stable, so
 * everything the ranking decided survives underneath it.
 */
internal fun List<StreamChoice>.promoteRemembered(memory: SourceMemory): List<StreamChoice> {
    if (memory.isEmpty) return this
    val scored = map { choice -> choice.copy(remembered = choice.source.matches(memory)) }
    return scored.sortedWith(
        compareBy({ it.compatibility.selectionPriority() }, { it.source.rememberedRank(memory) }),
    )
}

/**
 * 0 for the same release, 1 for one that merely looks like it, 2 for everything else.
 *
 * The middle rank is what carries a choice to the next episode: that file is gone, but the
 * provider that had it and the quality it was in are the two things worth repeating. Both have
 * to agree — a provider's 4K copy is not what somebody who settled on 1080p asked for.
 */
private fun StreamSource.rememberedRank(memory: SourceMemory): Int = when {
    releaseKey() == memory.releaseKey -> 0
    matches(memory) -> 1
    else -> 2
}

private fun StreamSource.matches(memory: SourceMemory): Boolean {
    if (releaseKey() == memory.releaseKey) return true
    val addon = addonName?.takeIf { it.isNotBlank() } ?: return false
    if (memory.addonName.isBlank()) return false
    if (!addon.equals(memory.addonName, ignoreCase = true)) return false
    // An unknown quality on either side is not a match: it would promote whatever that provider
    // happened to list first, which is the ranking's job rather than the memory's.
    val quality = qualityLabel() ?: return false
    return memory.quality.isNotBlank() && quality.equals(memory.quality, ignoreCase = true)
}
