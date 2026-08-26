package com.coveninja.cove.ui.tv.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.components.TvButton
import com.coveninja.cove.ui.tv.focus.tvFocusGroup

/** Which half of Profile is showing, plus the state each half wants kept. */
@Stable
class TvProfilePageState internal constructor(
    internal val settings: TvSettingsPageState,
    internal val insights: TvInsightsPageState,
) {
    internal var view by mutableStateOf(TvProfileView.Settings)
}

@Composable
fun rememberTvProfilePageState(): TvProfilePageState {
    val settings = rememberTvSettingsPageState()
    val insights = rememberTvInsightsPageState()
    return remember(settings, insights) { TvProfilePageState(settings, insights) }
}

internal enum class TvProfileView(val label: String, val icon: String) {
    Settings("Settings", "lucide:settings"),
    Insights("Insights", "lucide:chart-line"),
}

/**
 * Profile: the settings, and what the viewer's history says about them.
 *
 * A switch rather than a sixth entry in the rail, because [NavDestination] is the *phone's*
 * navigation too — adding Insights to it would put a tab in the bottom bar of every phone, and
 * on the phone insights already live inside Profile. Keeping the destination list identical
 * across both shells is what lets the rail and the bottom bar stay one enum.
 *
 * The switch sits above both scrollers rather than inside either, so it stays reachable with
 * one press of Up from anywhere near the top of a long page.
 */
@Composable
internal fun TvProfilePage(
    pageState: TvProfilePageState,
    modifier: Modifier = Modifier,
) {
    val dimens = TvTheme.dimens

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .padding(
                    start = dimens.overscanHorizontal,
                    top = dimens.overscanVertical + 20.dp,
                )
                .tvFocusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TvProfileView.entries.forEach { entry ->
                TvButton(
                    label = entry.label,
                    onClick = { pageState.view = entry },
                    icon = entry.icon,
                    selected = entry == pageState.view,
                )
            }
        }

        when (pageState.view) {
            TvProfileView.Settings -> TvSettingsPage(
                pageState = pageState.settings,
                modifier = Modifier.padding(top = 14.dp),
            )

            TvProfileView.Insights -> TvInsightsPage(
                pageState = pageState.insights,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}
