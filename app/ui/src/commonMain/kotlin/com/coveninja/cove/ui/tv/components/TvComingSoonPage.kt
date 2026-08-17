package com.coveninja.cove.ui.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.icons.IconifyIcon

/**
 * A destination that exists in navigation but not yet on screen.
 *
 * Deliberately reachable rather than hidden. A rail that grows entries as they are built would
 * change shape under the viewer between releases, and — while the shell is being developed — an
 * absent destination is indistinguishable from a broken one.
 */
@Composable
internal fun TvComingSoonPage(
    title: String,
    detail: String,
    icon: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .background(CoveColors.Neutral.Surface, RoundedCornerShape(24.dp))
                .padding(horizontal = 40.dp, vertical = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconifyIcon(
                icon = icon,
                tint = CoveColors.Brand.Accent,
                modifier = Modifier.size(44.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = CoveColors.Neutral.Text,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyLarge,
                color = CoveColors.Neutral.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
