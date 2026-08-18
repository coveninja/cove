package com.coveninja.cove.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors

/**
 * Says out loud that the catalog on screen is canned.
 *
 * Every host has a fixtures path — `--backend-mode fixtures` (which is also what the desktop
 * falls back to when no mode is given), the onboarding previews, and the Android benchmark
 * and `ONBOARDING_FIXTURES` launches — and none of them looked any different from a live run.
 * The fixtures carry real TMDB artwork and a plausible library, so the first thing that gives
 * them away is a page that asks the catalog a question: search a title outside the canned
 * dozen and nothing comes back, which reads as a broken search rather than as a harness.
 *
 * Hence a marker rather than, say, ending fixture mode when a preview finishes: the trap is
 * every fixtures run, not just the previews, and the fix has to be visible on all of them.
 *
 * Deliberately not interactive and not dismissible — a marker you can put away is one that is
 * missing at the moment it would have explained something. The type scale comes from the
 * theme, so the television shell renders it at television size without a second definition.
 */
@Composable
fun FixtureDataBadge(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    Text(
        text = "Fixture data",
        style = MaterialTheme.typography.labelSmall,
        color = CoveColors.Status.Warning,
        modifier = modifier
            .background(CoveColors.Neutral.SurfaceRaised.copy(alpha = 0.92f), shape)
            .border(1.dp, CoveColors.Status.Warning.copy(alpha = 0.45f), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
