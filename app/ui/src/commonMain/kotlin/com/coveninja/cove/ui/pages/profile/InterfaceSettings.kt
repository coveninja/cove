package com.coveninja.cove.ui.pages.profile

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.coveninja.cove.shared.data.AddonsState
import com.coveninja.cove.shared.model.Addon
import com.coveninja.cove.shared.model.AddonCatalogDescriptor
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.pages.common.ChoicePill
import com.coveninja.cove.ui.pages.common.ChoicePillRow
import com.coveninja.cove.ui.pages.home.HomeSectionKind
import com.coveninja.cove.ui.pages.home.MAX_CATALOG_ROWS
import com.coveninja.cove.ui.pages.home.MAX_CONTINUE_ROWS
import com.coveninja.cove.ui.pages.home.MAX_UPCOMING_DAYS
import com.coveninja.cove.ui.pages.home.MIN_CONTINUE_ROWS
import com.coveninja.cove.ui.pages.home.MIN_UPCOMING_DAYS
import com.coveninja.cove.ui.pages.home.catalogSectionKey
import com.coveninja.cove.ui.pages.home.defaultHomeOrder
import com.coveninja.cove.ui.pages.home.moveSection
import com.coveninja.cove.ui.pages.home.orderHomeSections
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.SettingsEditor
import kotlin.math.roundToInt

/**
 * The subcategories of Interface.
 *
 * Interface is the one settings category with a second level, because what it covers are
 * several unrelated things that happen to share an answer to "how does Cove present itself".
 * Stacking them in one scroll would bury the home layout — which is a list of a dozen
 * draggable rows — under two controls nobody was looking for.
 */
private enum class InterfacePanel(val label: String, val icon: String) {
    Home("Home", "lucide:layout-dashboard"),
    Language("Language", "lucide:languages"),
    Spoilers("Spoilers", "lucide:eye-off"),
}

/**
 * How Cove presents itself: the shape of the first page, the language it speaks, and how much
 * it gives away.
 *
 * The subcategories are pills at the top of the page rather than entries in the settings rail.
 * The rail is one level deep everywhere else, and a second level there would have to be built
 * twice — once for the rail on a wide window and once for the drill-down a phone gets — for a
 * distinction that fits comfortably inside the page it belongs to.
 */
@Composable
fun InterfaceSettings(
    settings: AppSettings,
    editor: SettingsEditor,
    modifier: Modifier = Modifier,
) {
    var panel by remember { mutableStateOf(InterfacePanel.Home) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Bare pills rather than a `SettingChoice`: that primitive draws a label above its
        // row, and the only honest label here is "Interface" — which the page is already
        // headed with.
        ChoicePillRow {
            InterfacePanel.entries.forEach { entry ->
                ChoicePill(
                    label = entry.label,
                    iconName = entry.icon,
                    selected = panel == entry,
                    onClick = { panel = entry },
                )
            }
        }

        when (panel) {
            InterfacePanel.Home -> HomeLayoutSettings(settings, editor)
            InterfacePanel.Language -> LanguageSettings(settings, editor)
            InterfacePanel.Spoilers -> SpoilerSettings(settings, editor)
        }
    }
}

/** The language Cove speaks to you in. Separate from subtitle and audio languages. */
@Composable
private fun LanguageSettings(settings: AppSettings, editor: SettingsEditor) {
    SettingsCard(
        title = "Language",
        iconName = "lucide:languages",
        description = "What Cove itself is written in. Subtitle and audio languages are " +
            "chosen separately, under Subtitles.",
    ) {
        SettingChoice(
            title = "Interface language",
            description = "System follows this device's language.",
            options = listOf("" to "System") + INTERFACE_LANGUAGES,
            selected = settings.uiLanguage,
            onSelect = { editor.edit { copy(uiLanguage = it) } },
        )
    }
}

/** How much a page is allowed to give away about something not yet watched. */
@Composable
private fun SpoilerSettings(settings: AppSettings, editor: SettingsEditor) {
    SettingsCard(title = "Spoilers", iconName = "lucide:eye-off") {
        SettingToggle(
            title = "Hide spoilers",
            description = "Keep episode titles and descriptions discreet until watched.",
            checked = settings.hideSpoilers,
            onCheckedChange = { editor.edit { copy(hideSpoilers = it) } },
        )
    }
}

private val INTERFACE_LANGUAGES = listOf(
    "en" to "English",
    "es" to "Español",
    "it" to "Italiano",
    "de" to "Deutsch",
    "pt" to "Português (Brasil)",
    "tr" to "Türkçe",
    "ja" to "日本語",
)

/**
 * What Home draws, in what order.
 *
 * The list is the setting: there is no separate "arrangement" to apply, and no preview,
 * because the page it describes is one tap away and any mock of it here would be a second
 * thing to keep true. Every row can be moved two ways deliberately — dragging is the natural
 * gesture with a mouse or a thumb, and the arrows are what remain when neither is available
 * or when a drag is simply fiddly. Both commit through the same `moveSection`, so they cannot
 * disagree about what a move means.
 */
@Composable
private fun HomeLayoutSettings(
    settings: AppSettings,
    editor: SettingsEditor,
    modifier: Modifier = Modifier,
) {
    val addonsState by LocalAppGraph.current.addons.state.collectAsState()

    // Only catalogs that could reach Home in the first place. A catalog switched off in
    // Addons is off everywhere, and listing it here would offer a position for a row that is
    // never drawn — two switches for one outcome, disagreeing.
    val catalogs: List<AddonCatalogDescriptor> = remember(addonsState) {
        (addonsState as? AddonsState.Ready)?.addons.orEmpty()
            .filter(Addon::enabled)
            .flatMap(Addon::catalogs)
            .filter(AddonCatalogDescriptor::enabled)
    }

    val labels: Map<String, SectionLabel> = remember(catalogs) {
        buildMap {
            for (kind in HomeSectionKind.entries) {
                put(kind.key, SectionLabel(kind.label, kind.blurb, kind.icon))
            }
            for (catalog in catalogs) {
                put(
                    catalogSectionKey(catalog),
                    SectionLabel(
                        title = catalog.name.ifBlank { catalog.addonName },
                        blurb = "Catalog from ${catalog.addonName}",
                        icon = "lucide:blocks",
                    ),
                )
            }
        }
    }

    val order = remember(catalogs, settings.homeSectionOrder) {
        orderHomeSections(
            available = defaultHomeOrder(catalogs.map(::catalogSectionKey)),
            saved = settings.homeSectionOrder,
        )
    }
    val hidden = remember(settings.homeSectionsHidden) { settings.homeSectionsHidden.toSet() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsCard(
            title = "Layout",
            iconName = "lucide:list",
            description = "Drag a row by its handle, or use the arrows. The eye takes a " +
                "section off Home without changing anything else about it.",
        ) {
            SectionOrderList(
                order = order,
                hidden = hidden,
                labels = labels,
                onMove = { from, to ->
                    editor.edit { copy(homeSectionOrder = moveSection(order, from, to)) }
                },
                onToggleHidden = { key ->
                    editor.edit {
                        copy(
                            homeSectionsHidden = if (key in hidden) {
                                homeSectionsHidden - key
                            } else {
                                homeSectionsHidden + key
                            },
                            // Written alongside, because a hide is the first thing most
                            // people change: without it the order stays empty and a section
                            // added by a later release would land wherever that build put
                            // it, quietly moving a page the viewer had already arranged.
                            homeSectionOrder = homeSectionOrder.ifEmpty { order },
                        )
                    }
                },
            )

            SettingDivider()

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                SecondaryButton(
                    label = "Reset to defaults",
                    onClick = {
                        editor.edit {
                            copy(homeSectionOrder = emptyList(), homeSectionsHidden = emptyList())
                        }
                    },
                )
            }
        }

        SettingsCard(
            title = "How much",
            iconName = "lucide:sliders-horizontal",
            description = "How far each of Home's longer sections runs.",
        ) {
            SettingRows(
                {
                    SettingSlider(
                        title = "Catalog rows",
                        description = "How many of your addons' catalogs Home draws. Each one " +
                            "costs a round of metadata requests, and the order above decides " +
                            "which of them make the cut.",
                        value = settings.homeCatalogRows.toFloat(),
                        range = 0f..MAX_CATALOG_ROWS.toFloat(),
                        format = { value ->
                            when (val rows = value.roundToInt()) {
                                0 -> "None"
                                1 -> "1 row"
                                else -> "$rows rows"
                            }
                        },
                        onCommit = { editor.edit { copy(homeCatalogRows = it.roundToInt()) } },
                    )
                },
                {
                    SettingSlider(
                        title = "Carry on watching",
                        description = "How many titles the resume row holds before the rest " +
                            "is My List's business.",
                        value = settings.homeContinueRows.toFloat(),
                        range = MIN_CONTINUE_ROWS.toFloat()..MAX_CONTINUE_ROWS.toFloat(),
                        format = { "${it.roundToInt()} titles" },
                        onCommit = { editor.edit { copy(homeContinueRows = it.roundToInt()) } },
                    )
                },
                {
                    SettingSlider(
                        title = "Coming up",
                        description = "How far ahead the dated releases from your list are " +
                            "worth showing.",
                        value = settings.homeUpcomingDays.toFloat(),
                        range = MIN_UPCOMING_DAYS.toFloat()..MAX_UPCOMING_DAYS.toFloat(),
                        format = { value ->
                            when (val days = value.roundToInt()) {
                                1 -> "1 day"
                                7 -> "1 week"
                                else -> "$days days"
                            }
                        },
                        onCommit = { editor.edit { copy(homeUpcomingDays = it.roundToInt()) } },
                    )
                },
            )
        }
    }
}

/** What one row of the list says about itself. */
private data class SectionLabel(val title: String, val blurb: String, val icon: String)

/**
 * The orderable list.
 *
 * A plain [Column] rather than a `LazyColumn`: this is a dozen or so fixed-height rows inside
 * a settings pane that already scrolls, and nesting a second scrolling list inside the first
 * would fight it for every gesture. Fixed height is also what makes the drag arithmetic
 * honest — the row under the pointer is the offset divided by that height, with nothing to
 * measure.
 */
@Composable
private fun SectionOrderList(
    order: List<String>,
    hidden: Set<String>,
    labels: Map<String, SectionLabel>,
    onMove: (from: Int, to: Int) -> Unit,
    onToggleHidden: (String) -> Unit,
) {
    val rowHeightPx = with(LocalDensity.current) { SectionRowHeight.toPx() }

    // Which row is in hand, and how far it has travelled. Held here rather than per row so
    // the ones it passes over can shift out of its way.
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }

    val targetIndex = draggingIndex?.let { from ->
        (from + (dragOffset / rowHeightPx).roundToInt()).coerceIn(0, order.lastIndex)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        order.forEachIndexed { index, sectionKey ->
            val label = labels[sectionKey] ?: return@forEachIndexed
            key(sectionKey) {
                val dragging = draggingIndex == index

                // Everything between the row's own place and where it is heading slides one
                // slot the other way, so the gap always sits under the row being carried.
                val shift = when {
                    draggingIndex == null || targetIndex == null || dragging -> 0f
                    index in (draggingIndex!! + 1)..targetIndex -> -rowHeightPx
                    index in targetIndex..<draggingIndex!! -> rowHeightPx
                    else -> 0f
                }

                SectionRow(
                    label = label,
                    hidden = sectionKey in hidden,
                    canMoveUp = index > 0,
                    canMoveDown = index < order.lastIndex,
                    onMoveUp = { onMove(index, index - 1) },
                    onMoveDown = { onMove(index, index + 1) },
                    onToggleHidden = { onToggleHidden(sectionKey) },
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) dragOffset else shift
                        },
                    dragHandle = Modifier.pointerInput(index, order) {
                        detectDragGestures(
                            onDragStart = {
                                draggingIndex = index
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                            },
                            onDragEnd = {
                                val from = draggingIndex
                                val to = (from ?: return@detectDragGestures) +
                                    (dragOffset / rowHeightPx).roundToInt()
                                draggingIndex = null
                                dragOffset = 0f
                                onMove(from, to.coerceIn(0, order.lastIndex))
                            },
                            // A cancelled drag puts the row back rather than committing a
                            // half-finished move: the gesture was interrupted, not completed.
                            onDragCancel = {
                                draggingIndex = null
                                dragOffset = 0f
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionRow(
    label: SectionLabel,
    hidden: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleHidden: () -> Unit,
    dragHandle: Modifier,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(SectionRowHeight)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = dragHandle.size(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconifyIcon(
                icon = "lucide:grip-vertical",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
                // Dimmed rather than removed: a hidden section still holds its place in the
                // order, so that turning it back on returns it where the viewer left it.
                .alpha(if (hidden) 0.45f else 1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                IconifyIcon(
                    icon = label.icon,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = label.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = label.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Always drawn, never revealed on hover: hovering does not exist on a phone, and
        // these are also the only way to move a row without a drag.
        MoveButton("lucide:arrow-up", enabled = canMoveUp, onClick = onMoveUp)
        MoveButton("lucide:arrow-down", enabled = canMoveDown, onClick = onMoveDown)
        SettingsIconAction(
            icon = if (hidden) "lucide:eye-off" else "lucide:eye",
            onClick = onToggleHidden,
        )
    }
}

@Composable
private fun MoveButton(icon: String, enabled: Boolean, onClick: () -> Unit) {
    // Kept in the layout when it cannot act, so the controls stay in the same place on every
    // row — the first and last rows would otherwise shuffle their buttons sideways.
    Box(
        modifier = Modifier
            .size(32.dp)
            .alpha(if (enabled) 1f else 0.25f),
        contentAlignment = Alignment.Center,
    ) {
        if (enabled) {
            SettingsIconAction(icon = icon, onClick = onClick)
        } else {
            IconifyIcon(
                icon = icon,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Fixed, and load-bearing: the drag resolves which row it is over by dividing the travelled
 * distance by this. A row that sized itself to its content would make that arithmetic a lie.
 */
private val SectionRowHeight: Dp = 52.dp
