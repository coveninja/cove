package com.coveninja.cove.ui.pages.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
 * What Explore shows before its first rail resolves.
 *
 * The placeholder mirrors the real layout — hero, then headed rows of posters — so the
 * page does not visibly jump when content replaces it. A centred spinner would be less
 * code and a worse answer: it tells the viewer to wait without telling them for what.
 */
@Composable
fun ExploreSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        ShimmerBlock(
            modifier = Modifier.fillMaxWidth().height(360.dp),
            corner = 28.dp,
        )

        repeat(2) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PageLayoutDefaults.HorizontalPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ShimmerBlock(modifier = Modifier.width(180.dp).height(20.dp), corner = 6.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(SKELETON_CARDS) {
                        ShimmerBlock(modifier = Modifier.width(158.dp).height(237.dp))
                    }
                }
            }
        }
    }
}

/** Enough to fill a wide window; the row clips rather than wrapping on a narrow one. */
private const val SKELETON_CARDS = 7
