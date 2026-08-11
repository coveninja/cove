package com.coveninja.cove.ui.pages.profile

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.pages.common.ChoicePill
import com.coveninja.cove.ui.pages.common.ChoicePillRow

/**
 * The settings visual language, in one place.
 *
 * Rows are uniform on purpose: one text column on the left, one control on the
 * right, the same padding everywhere, and dividers inset to the text rather than
 * full-bleed. Once every row obeys that, a long list reads as a single object
 * instead of a stack of unrelated widgets.
 */
private val CardShape = RoundedCornerShape(18.dp)
private val RowPadding = 24.dp

@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    iconName: String? = null,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        // A hairline gives the card an edge on dark backgrounds where a fill alone
        // barely separates from the page.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column {
            if (title != null) {
                Column(
                    modifier = Modifier.padding(
                        start = RowPadding,
                        end = RowPadding,
                        top = 15.dp,
                        bottom = if (description == null) 13.dp else 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        iconName?.let {
                            IconifyIcon(
                                icon = it,
                                modifier = Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        Text(
                            text = title,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    description?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                SettingDivider()
            }
            content()
        }
    }
}

/** Inset to line up with row text; a full-bleed rule cuts the card in half. */
@Composable
internal fun SettingDivider(inset: Boolean = true) {
    HorizontalDivider(
        modifier = if (inset) Modifier.padding(horizontal = RowPadding) else Modifier,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

@Composable
internal fun SettingLabels(
    title: String,
    description: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        description?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Text on the left, one control on the right. Every simple setting uses this.
 *
 * [onClick] makes the whole row the hit target rather than just the control,
 * which matters most on a phone where a switch is a small thing to hit.
 */
@Composable
internal fun SettingRow(
    title: String,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    control: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = if (hovered && onClick != null) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(140),
        label = "SettingRowBackground",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier
                        .hoverable(interactionSource)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick,
                        )
                },
            )
            .padding(horizontal = RowPadding, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingLabels(title, description, Modifier.weight(1f))
        control()
    }
}

@Composable
internal fun SettingToggle(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingRow(title, description, onClick = { onCheckedChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Tracks the drag locally and writes once it ends. Every write is a whole-object
 * settings PUT, so committing per pixel would fire dozens of round trips per drag
 * and recreate the editor under this composable each time.
 */
@Composable
internal fun SettingSlider(
    title: String,
    description: String? = null,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var dragged by remember(value) { mutableStateOf<Float?>(null) }
    val shown = dragged ?: value

    Column(
        modifier = Modifier.padding(horizontal = RowPadding, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingLabels(title, description, Modifier.weight(1f))
            ValuePill(text = format(shown), active = dragged != null)
        }
        Slider(
            value = shown.coerceIn(range.start, range.endInclusive),
            valueRange = range,
            onValueChange = { dragged = it },
            onValueChangeFinished = { dragged?.let(onCommit) },
        )
    }
}

/**
 * Reacts to the drag rather than to the value: the number itself changes every
 * frame while dragging, so animating on it would just thrash.
 */
@Composable
private fun ValuePill(text: String, active: Boolean) {
    val scale by animateFloatAsState(
        targetValue = if (active) 1.07f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "ValuePillScale",
    )
    val container by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.tertiary.copy(alpha = if (active) 0.24f else 0.14f),
        animationSpec = tween(140),
        label = "ValuePillContainer",
    )

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(container, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun SettingChoice(
    title: String,
    description: String? = null,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = RowPadding, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        SettingLabels(title, description)
        ChoicePillRow {
            options.forEach { (value, label) ->
                ChoicePill(
                    label = label,
                    selected = selected == value,
                    onClick = { onSelect(value) },
                )
            }
        }
    }
}

/** Places a divider between consecutive rows without one trailing the last. */
@Composable
internal fun ColumnScope.SettingRows(vararg rows: @Composable () -> Unit) {
    rows.forEachIndexed { index, row ->
        if (index > 0) SettingDivider()
        row()
    }
}

/**
 * The one text input in settings.
 *
 * BasicTextField rather than Material's: the settings cards define their own
 * surface, and an OutlinedTextField brings a second border and its own label
 * behaviour that fights the row layout everything else here uses.
 */
@Composable
internal fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    masked: Boolean = false,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(
        targetValue = if (focused) {
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        },
        animationSpec = tween(160),
        label = "SettingsFieldBorder",
    )

    Box(
        modifier = modifier
            .height(42.dp)
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.06f else 0.03f),
                RoundedCornerShape(11.dp),
            )
            .border(1.dp, border, RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
            visualTransformation = if (masked) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onDone = { onSubmit?.invoke() },
                onGo = { onSubmit?.invoke() },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                inner()
            },
        )
    }
}

/**
 * The filled action button used by every settings form.
 *
 * Dims rather than disappears when there is nothing to submit, so the form does
 * not reflow as fields are filled in.
 */
@Composable
internal fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val active = enabled && !busy

    val scale by animateFloatAsState(
        targetValue = when {
            pressed && active -> 0.95f
            hovered && active -> 1.04f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "PrimaryButtonScale",
    )
    val container by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.tertiary.copy(alpha = if (active) 1f else 0.45f),
        animationSpec = tween(160),
        label = "PrimaryButtonContainer",
    )

    Box(
        modifier = modifier
            .height(42.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(container, RoundedCornerShape(11.dp))
            .hoverable(interactionSource)
            .clickable(
                enabled = active,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (busy) "…" else label,
            color = MaterialTheme.colorScheme.onTertiary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** A quieter button for the secondary action beside a [PrimaryButton]. */
@Composable
internal fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val accent = if (danger) colors.error else colors.onSurface
    val container by animateColorAsState(
        targetValue = accent.copy(alpha = if (hovered) 0.14f else 0.06f),
        animationSpec = tween(140),
        label = "SecondaryButtonContainer",
    )

    Box(
        modifier = modifier
            .height(42.dp)
            .background(container, RoundedCornerShape(11.dp))
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (danger) colors.error else colors.onSurface,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * A free-text setting.
 *
 * Edits are held locally and committed explicitly, for the same reason
 * [SettingSlider] commits at the end of a drag: every write is a whole-object
 * settings PUT, and one per keystroke would be dozens of round trips per URL.
 */
@Composable
internal fun SettingTextRow(
    title: String,
    description: String? = null,
    value: String,
    placeholder: String,
    onCommit: (String) -> Unit,
    masked: Boolean = false,
) {
    var draft by remember(value) { mutableStateOf(value) }
    val dirty = draft.trim() != value

    Column(
        modifier = Modifier.padding(horizontal = RowPadding, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        SettingLabels(title, description)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val commit = { if (dirty) onCommit(draft.trim()) }
            SettingsTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = placeholder,
                modifier = Modifier.weight(1f),
                masked = masked,
                keyboardType = KeyboardType.Uri,
                onSubmit = commit,
            )
            PrimaryButton(label = "Save", onClick = commit, enabled = dirty)
        }
    }
}

/**
 * A value that should not simply sit on screen — a pairing token — with reveal
 * and copy.
 *
 * Read-only by design: this token is what already-paired devices authenticate
 * with, so an editable field here would be a way to lock them all out by
 * accident.
 */
@Composable
internal fun SecretRow(
    title: String,
    description: String? = null,
    secret: String,
    emptyLabel: String,
) {
    var revealed by remember(secret) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = RowPadding, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SettingLabels(title, description)
            Text(
                text = when {
                    secret.isBlank() -> emptyLabel
                    revealed -> secret
                    else -> "•".repeat(secret.length.coerceAtMost(24))
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (secret.isNotBlank()) {
            SettingsIconAction(
                icon = if (revealed) "lucide:eye-off" else "lucide:eye",
                onClick = { revealed = !revealed },
            )
            SettingsIconAction(
                icon = "lucide:copy",
                onClick = { clipboard.setText(AnnotatedString(secret)) },
            )
        }
    }
}

/** A small tinted status chip: sync state, an active profile, a linked account. */
@Composable
internal fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color = MaterialTheme.colorScheme.tertiary,
) {
    Box(
        modifier = modifier
            .background(tone.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = tone,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A small square icon button: refresh, rename, delete. */
@Composable
internal fun SettingsIconAction(icon: String, onClick: () -> Unit, danger: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()

    val accent = if (danger) colors.error else colors.onSurface
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else if (hovered) 1.06f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "IconActionScale",
    )
    val container by animateColorAsState(
        targetValue = accent.copy(alpha = if (hovered) 0.14f else 0.06f),
        animationSpec = tween(140),
        label = "IconActionContainer",
    )

    Box(
        modifier = Modifier
            .size(34.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(container, RoundedCornerShape(9.dp))
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        IconifyIcon(
            icon = icon,
            modifier = Modifier.size(15.dp),
            tint = if (hovered) accent else colors.onSurfaceVariant,
        )
    }
}

/** Quiet state used for loading, errors and empty lists inside a card. */
@Composable
internal fun SettingsNotice(
    message: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    Text(
        text = message,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = RowPadding, vertical = 16.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

/** One entry in the settings category rail. */
@Composable
internal fun CategoryRailItem(
    label: String,
    iconName: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val background by animateColorAsState(
        targetValue = when {
            selected -> colors.tertiary.copy(alpha = 0.15f)
            hovered -> colors.onSurface.copy(alpha = 0.06f)
            else -> colors.onSurface.copy(alpha = 0f)
        },
        animationSpec = tween(120),
        label = "CategoryRailBackground",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            selected -> colors.tertiary
            hovered -> colors.onSurface
            else -> colors.onSurfaceVariant
        },
        animationSpec = tween(120),
        label = "CategoryRailContent",
    )

    // Grows out of nothing on selection, so the eye can follow which entry took
    // over rather than just noticing two tints swap.
    val indicatorHeight by animateDpAsState(
        targetValue = if (selected) 16.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "CategoryRailIndicator",
    )
    val iconNudge by animateDpAsState(
        targetValue = if (hovered && !selected) 2.dp else 0.dp,
        animationSpec = tween(140),
        label = "CategoryRailNudge",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(11.dp))
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(end = 12.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = indicatorHeight)
                    .background(colors.tertiary, RoundedCornerShape(2.dp)),
            )
        }
        IconifyIcon(
            icon = iconName,
            modifier = Modifier.offset(x = iconNudge).size(17.dp),
            tint = contentColor,
        )
        Text(
            text = label,
            modifier = Modifier.offset(x = iconNudge),
            color = contentColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
