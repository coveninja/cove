package com.coveninja.cove.ui.pages.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.pages.common.PageLayoutDefaults
import com.coveninja.cove.ui.pages.common.ShimmerBlock

/**
 * What the page shows while a *cold* search is running.
 *
 * Shaped like the answer — a wide top-result block, a row of filter pills, then a poster
 * grid — so nothing jumps when the results replace it. The centred spinner it replaces told
 * the viewer to wait without telling them what for, and took the header down with it.
 *
 * Only ever used when there is nothing already on screen. Refining an existing query keeps
 * the previous results visible instead; see `SearchPage`.
 */
@Composable
fun SearchSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PageLayoutDefaults.HorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ShimmerBlock(
            modifier = Modifier.fillMaxWidth().height(280.dp),
            corner = 24.dp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PILL_WIDTHS.forEach { width ->
                ShimmerBlock(modifier = Modifier.width(width.dp).height(34.dp), corner = 17.dp)
            }
        }

        repeat(SKELETON_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(SKELETON_COLUMNS) {
                    ShimmerBlock(modifier = Modifier.width(150.dp).height(225.dp))
                }
            }
        }
    }
}

/** Uneven on purpose: equal-width placeholders read as a chart rather than as words. */
private val PILL_WIDTHS = listOf(88, 72, 104, 64, 96)

/** Enough to fill a wide window; the rows clip rather than wrapping on a narrow one. */
private const val SKELETON_ROWS = 2
private const val SKELETON_COLUMNS = 6
