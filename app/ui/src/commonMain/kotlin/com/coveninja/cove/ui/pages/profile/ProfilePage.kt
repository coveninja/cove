package com.coveninja.cove.ui.pages.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.AccountState
import com.coveninja.cove.shared.data.ProfilesState
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.pages.common.ChoicePill
import com.coveninja.cove.ui.pages.common.ChoicePillRow
import com.coveninja.cove.ui.pages.common.PageHeader
import com.coveninja.cove.ui.pages.common.PageLayoutDefaults
import com.coveninja.cove.ui.platform.PlatformBackHandler
import com.coveninja.cove.ui.platform.hasPointerHover
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.LocalMotionPolicy
import com.coveninja.cove.ui.state.rememberSettingsEditor
import kotlinx.coroutines.launch

enum class ProfileTab(val label: String, val icon: String) {
    Insights("Insights", "lucide:chart-line"),
    Settings("Settings", "lucide:settings"),
}

/** Categories that draw themselves from their own repositories, without AppSettings. */
private val SelfContainedCategories = setOf(
    SettingsCategory.Account,
    SettingsCategory.Addons,
    SettingsCategory.Advanced,
)

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
    onNavigateBack: () -> Unit = {},
    // Insights shows posters of the titles behind the numbers, and a poster the viewer
    // cannot open is a dead end — this is the same details route every other page uses.
    onOpenMedia: (Media) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    var tab by remember { mutableStateOf(ProfileTab.Settings) }
    // Hoisted above the tab AnimatedContent: that lambda disposes the state it
    // owns once a transition ends, so keeping this inside would quietly send you
    // back to the category list every time you looked at Insights.
    var openedCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    // Same reasoning as openedCategory: the tab AnimatedContent disposes whichever tab is
    // not showing, and the insights fetches are far too expensive to repeat on every switch.
    val insightsState = rememberInsightsState()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val narrowSettingsLayout = PageLayoutDefaults.Viewport.width < RailBreakpoint

    PlatformBackHandler(enabled = true) {
        if (openedCategory != null && narrowSettingsLayout) {
            openedCategory = null
            scope.launch { scrollState.animateScrollTo(0) }
        } else {
            onNavigateBack()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(
                start = PageLayoutDefaults.HorizontalPadding,
                end = PageLayoutDefaults.HorizontalPadding,
                top = 12.dp,
                // Clears the floating bar and the system navigation area the page now
                // paints into; the last settings card would otherwise sit under both.
                bottom = 12.dp + PageLayoutDefaults.BottomClearance,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PageHeader(
            title = "Profile",
            subtitle = "Your viewing preferences and account basics.",
            modifier = PageBlock,
        )

        ProfileIdentityCard(
            fallbackName = profileName,
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
                    val enterDuration = if (reducedMotion) 0 else 200
                    val movementDuration = if (reducedMotion) 0 else 260
                    val exitDuration = if (reducedMotion) 0 else 120
                    val delay = if (reducedMotion) 0 else 40
                    (fadeIn(tween(enterDuration, delayMillis = delay)) +
                        slideInVertically(tween(movementDuration, delayMillis = delay)) { it / 24 })
                        .togetherWith(fadeOut(tween(exitDuration)))
                        .using(SizeTransform(clip = false))
                },
                label = "ProfileTab",
            ) { target ->
                when (target) {
                    ProfileTab.Insights -> InsightsTab(state = insightsState, onOpenMedia = onOpenMedia)
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
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val settingsState by graph.settings.settings.collectAsState()

    BoxWithConstraints(modifier = modifier) {
        val wide = maxWidth >= RailBreakpoint
        val category = opened ?: SettingsCategory.entries.first()

        // These own their own repositories and stay usable while preferences are
        // still loading, so they are routed directly rather than through the settings
        // content. None must ever be handed a SettingsEditor built over a default
        // AppSettings either — writing through one of those would replace the whole
        // stored object with defaults.
        // Takes the category as a parameter rather than closing over the selected
        // one: during a transition the outgoing pane is composed with the previous
        // value, and a captured variable would make it render the incoming content
        // instead — an animation between two identical panes.
        val body: @Composable (SettingsCategory, Modifier) -> Unit = { shown, bodyModifier ->
            if (shown in SelfContainedCategories) {
                when (shown) {
                    SettingsCategory.Addons -> AddonSettings(modifier = bodyModifier)
                    SettingsCategory.Advanced -> AdvancedSettings(modifier = bodyModifier)
                    else -> Column(
                        modifier = bodyModifier,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        AccountSettings()
                        ProfilesSettings()
                    }
                }
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
                            val enterDuration = if (reducedMotion) 0 else 200
                            val movementDuration = if (reducedMotion) 0 else 260
                            val exitDuration = if (reducedMotion) 0 else 120
                            val delay = if (reducedMotion) 0 else 40
                            (fadeIn(tween(enterDuration, delayMillis = delay)) +
                                slideInVertically(
                                    tween(movementDuration, delayMillis = delay),
                                ) { it / 22 })
                                .togetherWith(fadeOut(tween(exitDuration)))
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
                    val enterDuration = if (reducedMotion) 0 else 260
                    val exitDuration = if (reducedMotion) 0 else 200
                    val enterFadeDuration = if (reducedMotion) 0 else 180
                    val exitFadeDuration = if (reducedMotion) 0 else 140
                    val enter = slideInHorizontally(tween(enterDuration)) { full ->
                        if (forward) full / 5 else -full / 5
                    } + fadeIn(tween(enterFadeDuration))
                    val exit = slideOutHorizontally(tween(exitDuration)) { full ->
                        if (forward) -full / 8 else full / 8
                    } + fadeOut(tween(exitFadeDuration))
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
            .padding(
                horizontal = 18.dp,
                vertical = if (hasPointerHover) 32.dp else 20.dp,
            ),
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
                .size(if (hasPointerHover) 38.dp else 48.dp)
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

// ── Identity ─────────────────────────────────────────────────────────────────

/**
 * Who is signed in and which profile is active — the two facts the page used to
 * assert without checking either of them.
 *
 * [fallbackName] is only reached before the profile store answers; once it does,
 * the active profile's real name is shown.
 */
@Composable
private fun ProfileIdentityCard(fallbackName: String, modifier: Modifier = Modifier) {
    val graph = LocalAppGraph.current
    val profilesState by graph.profiles.profiles.collectAsState()
    val account by graph.account.account.collectAsState()

    val profileName = (profilesState as? ProfilesState.Ready)
        ?.let { state -> state.profiles.firstOrNull { it.id == state.activeProfileId }?.name }
        ?: fallbackName
    val signedIn = account as? AccountState.SignedIn

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
                    text = signedIn?.email?.takeIf(String::isNotBlank)
                        ?: if (signedIn != null) "Signed in" else "On this device only",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Signing in changes what this profile *is*, so the badge swaps
            // rather than recolouring in place — and it carries the icon that
            // says which, for the same reason the sync card does.
            val badgeTint by animateColorAsState(
                targetValue = if (signedIn != null) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(320),
                label = "IdentityBadgeTint",
            )
            AnimatedContent(
                targetState = signedIn != null,
                transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(initialScale = 0.8f, animationSpec = tween(220)))
                        .togetherWith(fadeOut(tween(140)) + scaleOut(targetScale = 0.8f))
                },
                label = "IdentityBadge",
            ) { synced ->
                Row(
                    modifier = Modifier
                        .background(badgeTint.copy(alpha = 0.14f), CircleShape)
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconifyIcon(
                        icon = if (synced) "lucide:cloud-check" else "lucide:cloud-off",
                        modifier = Modifier.size(12.dp),
                        tint = badgeTint,
                    )
                    Text(
                        text = if (synced) "SYNCED" else "LOCAL",
                        color = badgeTint,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
