package com.coveninja.cove.ui.pages.home

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
import com.coveninja.cove.ui.pages.common.RailDefaults
import com.coveninja.cove.ui.pages.common.ShimmerBlock

/**
 * What Home shows before the library and the feed have resolved.
 *
 * Shaped like the real page — hero, greeting band, then a wide rail and a poster rail — so
 * content replacing it does not make the layout jump. A centred spinner would be less code
 * and a worse answer: it tells the viewer to wait without telling them what for.
 */
@Composable
fun HomeSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        ShimmerBlock(
            modifier = Modifier.fillMaxWidth().height(420.dp),
            corner = 28.dp,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RailDefaults.HorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShimmerBlock(modifier = Modifier.width(220.dp).height(30.dp), corner = 8.dp)
        }

        SkeletonRail(
            cardWidth = RailDefaults.WideCardWidth,
            cardHeight = RailDefaults.WideCardHeight,
            cards = 4,
        )
        SkeletonRail(
            cardWidth = RailDefaults.CardWidth,
            cardHeight = RailDefaults.CardHeight - 12.dp,
            cards = 7,
        )
    }
}

@Composable
private fun SkeletonRail(
    cardWidth: androidx.compose.ui.unit.Dp,
    cardHeight: androidx.compose.ui.unit.Dp,
    cards: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RailDefaults.HorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShimmerBlock(modifier = Modifier.width(180.dp).height(20.dp), corner = 6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(cards) {
                ShimmerBlock(modifier = Modifier.width(cardWidth).height(cardHeight))
            }
        }
    }
}
