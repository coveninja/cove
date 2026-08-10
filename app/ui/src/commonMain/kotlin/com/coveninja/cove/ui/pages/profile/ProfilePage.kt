package com.coveninja.cove.ui.pages.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.pages.common.ChoicePill
import com.coveninja.cove.ui.pages.common.ChoicePillRow
import com.coveninja.cove.ui.pages.common.PageHeader
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.rememberSettingsEditor
import kotlinx.coroutines.launch

enum class ProfileTab(val label: String, val icon: String) {
    Insights("Insights", "lucide:chart-line"),
    Settings("Settings", "lucide:settings"),
}

/** Below this the rail would leave too little room for the settings themselves. */
private val RailBreakpoint = 900.dp
private val RailWidth = 216.dp

// One width for every block on the page. The header, the identity card and the
// settings body all share these gutters; letting the settings area run wider than
// the header above it is the kind of misalignment that reads as unfinished.
private val PageMaxWidth = 1000.dp
private val PageBlock = Modifier.fillMaxWidth().widthIn(max = PageMaxWidth)

@Composable
fun ProfilePage(
    profileName: String = "Cove",
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(ProfileTab.Settings) }
    // Hoisted above the tab AnimatedContent: that lambda disposes the state it
    // owns once a transition ends, so keeping this inside would quietly send you
    // back to the category list every time you looked at Insights.
    var openedCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PageHeader(
            title = "Profile",
            subtitle = "Your viewing preferences and account basics.",
            modifier = PageBlock,
        )

        ProfileIdentityCard(
            profileName = profileName,
            modifier = PageBlock.padding(top = 20.dp),
        )

        Box(modifier = PageBlock.padding(top = 16.dp)) {
            ChoicePillRow {
                ProfileTab.entries.forEach { entry ->
                    ChoicePill(
                        label = entry.label,
                        iconName = entry.icon,
                        selected = tab == entry,
                        onClick = { tab = entry },
                    )
                }
            }
        }

        Box(modifier = PageBlock.padding(top = 16.dp, bottom = 36.dp)) {
            // Siblings, not a hierarchy, so they cross-fade rather than slide.
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (fadeIn(tween(200, delayMillis = 40)) +
                        slideInVertically(tween(260, delayMillis = 40)) { it / 24 })
                        .togetherWith(fadeOut(tween(120)))
                        .using(SizeTransform(clip = false))
                },
                label = "ProfileTab",
            ) { target ->
                when (target) {
                    ProfileTab.Insights -> InsightsTab()
                    // Drilling in or back on a narrow window would otherwise land
                    // you wherever the previous screen happened to be scrolled to.
                    ProfileTab.Settings -> SettingsTab(
                        opened = openedCategory,
                        onOpenedChange = { openedCategory = it },
                        onNavigate = { scope.launch { scrollState.animateScrollTo(0) } },
                    )
                }
            }
        }
    }
}

// ── Settings ─────────────────────────────────────────────────────────────────

@Composable
private fun SettingsTab(
    // Null means "no category chosen yet", which only narrow layouts can show —
    // that is the category list. A sidebar always has something selected, so it
    // falls back to the first category rather than rendering an empty pane.
    opened: SettingsCategory?,
    onOpenedChange: (SettingsCategory?) -> Unit,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = LocalAppGraph.current
    val settingsState by graph.settings.settings.collectAsState()

    BoxWithConstraints(modifier = modifier) {
        val wide = maxWidth >= RailBreakpoint
        val category = opened ?: SettingsCategory.entries.first()

        // Addons owns its own repository and stays usable while preferences are
        // still loading, so it is routed directly rather than through the settings
        // content. It must never be handed a SettingsEditor built over a default
        // AppSettings either — writing through one of those would replace the whole
        // stored object with defaults.
        // Takes the category as a parameter rather than closing over the selected
        // one: during a transition the outgoing pane is composed with the previous
        // value, and a captured variable would make it render the incoming content
        // instead — an animation between two identical panes.
        val body: @Composable (SettingsCategory, Modifier) -> Unit = { shown, bodyModifier ->
            if (shown == SettingsCategory.Addons) {
                AddonSettings(modifier = bodyModifier)
            } else {
                when (val state = settingsState) {
                    is SettingsState.Ready -> SettingsCategoryContent(
                        category = shown,
                        settings = state.settings,
                        editor = rememberSettingsEditor(state.settings),
                        modifier = bodyModifier,
                    )

                    is SettingsState.Failed -> SettingsCard(modifier = bodyModifier) {
                        SettingsNotice(message = state.message, isError = true)
                    }

                    SettingsState.Loading -> SettingsCard(modifier = bodyModifier) {
                        SettingsNotice("Loading your preferences…")
                    }
                }
            }
        }

        if (wide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                CategoryRail(
                    selected = category,
                    onSelect = onOpenedChange,
                    modifier = Modifier.width(RailWidth),
                )
                Column(modifier = Modifier.weight(1f)) {
                    // Swapping panes instantly reads as a flicker; a short lift and
                    // fade says the pane was replaced rather than redrawn.
                    AnimatedContent(
                        targetState = category,
                        transitionSpec = {
                            (fadeIn(tween(200, delayMillis = 40)) +
                                slideInVertically(tween(260, delayMillis = 40)) { it / 22 })
                                .togetherWith(fadeOut(tween(120)))
                                .using(SizeTransform(clip = false))
                        },
                        label = "SettingsPane",
                    ) { target ->
                        Column {
                            CategoryHeading(target)
                            body(target, Modifier.fillMaxWidth().padding(top = 14.dp))
                        }
                    }
                }
            }
        } else {
            // Narrow layouts drill in rather than showing a strip of categories:
            // a horizontal rail of eight items is unusable on a phone, and it
            // leaves no room for the section it is meant to introduce.
            AnimatedContent(
                targetState = opened,
                // Direction carries the meaning: going deeper slides forward,
                // coming back slides the other way, so the gesture is legible
                // without reading anything.
                transitionSpec = {
                    val forward = targetState != null
                    val enter = slideInHorizontally(tween(260)) { full ->
                        if (forward) full / 5 else -full / 5
                    } + fadeIn(tween(180))
                    val exit = slideOutHorizontally(tween(200)) { full ->
                        if (forward) -full / 8 else full / 8
                    } + fadeOut(tween(140))
                    enter.togetherWith(exit).using(SizeTransform(clip = false))
                },
                label = "SettingsDrill",
            ) { current ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (current == null) {
                        CategoryList(
                            onSelect = {
                                onOpenedChange(it)
                                onNavigate()
                            },
                        )
                    } else {
                        CategoryDetailHeader(
                            category = current,
                            onBack = {
                                onOpenedChange(null)
                                onNavigate()
                            },
                        )
                        body(current, Modifier.fillMaxWidth().padding(top = 14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRail(
    selected: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SettingsCategory.entries.forEach { entry ->
                CategoryRailItem(
                    label = entry.label,
                    iconName = entry.icon,
                    selected = selected == entry,
                    onClick = { onSelect(entry) },
                )
            }
        }
    }
}

/** Narrow-window level one: every category as a tappable row. */
@Composable
private fun CategoryList(onSelect: (SettingsCategory) -> Unit) {
    SettingsCard {
        SettingsCategory.entries.forEachIndexed { index, entry ->
            if (index > 0) SettingDivider()
            CategoryListRow(category = entry, onClick = { onSelect(entry) })
        }
    }
}

@Composable
private fun CategoryListRow(category: SettingsCategory, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (hovered) colors.onSurface.copy(alpha = 0.04f) else Color.Transparent,
            )
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(colors.tertiary.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            IconifyIcon(
                icon = category.icon,
                modifier = Modifier.size(18.dp),
                tint = colors.tertiary,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = category.headline,
                color = colors.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = category.blurb,
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val chevronNudge by animateDpAsState(
            targetValue = if (hovered) 4.dp else 0.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "CategoryChevronNudge",
        )
        IconifyIcon(
            icon = "lucide:chevron-right",
            modifier = Modifier.offset(x = chevronNudge).size(16.dp),
            tint = if (hovered) colors.tertiary else colors.onSurfaceVariant,
        )
    }
}

/** Narrow-window level two: what you drilled into, and the way back out. */
@Composable
private fun CategoryDetailHeader(category: SettingsCategory, onBack: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    RoundedCornerShape(11.dp),
                )
                .hoverable(interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onBack,
                ),
            contentAlignment = Alignment.Center,
        ) {
            IconifyIcon(
                icon = "iconamoon:arrow-left-1",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            CategoryHeading(category)
        }
    }
}

@Composable
private fun CategoryHeading(category: SettingsCategory) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = category.headline,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = category.blurb,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ── Insights ─────────────────────────────────────────────────────────────────

/**
 * Placeholder. Nothing aggregates activity data yet — the backend keeps activity
 * hours and watch progress per profile, but no endpoint rolls them up — so this
 * says what is coming rather than inventing numbers.
 */
@Composable
private fun InsightsTab(modifier: Modifier = Modifier) {
    SettingsCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 44.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                IconifyIcon(
                    icon = "lucide:chart-line",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(
                text = "Insights are coming",
                modifier = Modifier.padding(top = 16.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Watch time, most-watched genres, and viewing streaks will " +
                    "live here once the activity data is aggregated.",
                modifier = Modifier
                    .padding(top = 8.dp)
                    .widthIn(max = 420.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ── Identity ─────────────────────────────────────────────────────────────────

@Composable
private fun ProfileIdentityCard(profileName: String, modifier: Modifier = Modifier) {
    SettingsCard(modifier = modifier) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = profileName.trim().firstOrNull()?.uppercase() ?: "C",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profileName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Local profile",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                        CircleShape,
                    )
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "ACTIVE",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
