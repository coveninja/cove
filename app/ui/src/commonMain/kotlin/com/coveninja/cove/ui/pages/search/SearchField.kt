package com.coveninja.cove.ui.pages.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.platform.hasPointerHover
import kotlinx.coroutines.delay

/**
 * The field the search page never had.
 *
 * Everything about it is meant to answer "is it working?" without a spinner covering the
 * page: the leading icon becomes the progress indicator, the ring lights up while it has
 * focus, and the placeholder offers something to try rather than restating the page title.
 *
 * The [focusRequester] is owned by the caller rather than created here, because the page's
 * `/` shortcut has to be able to hand focus back to a field it does not otherwise touch.
 */
@Composable
fun SearchField(
    query: String,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val hovered = remember { MutableInteractionSource() }
    val isHovered by hovered.collectIsHoveredAsState()

    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> colors.tertiary.copy(alpha = 0.85f)
            isHovered -> colors.outline.copy(alpha = 0.75f)
            else -> colors.outline.copy(alpha = 0.35f)
        },
        animationSpec = tween(180),
        label = "SearchFieldBorderColor",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (focused) 2.dp else 1.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "SearchFieldBorderWidth",
    )
    val elevation by animateDpAsState(
        targetValue = when {
            focused -> 12.dp
            isHovered -> 6.dp
            else -> 0.dp
        },
        animationSpec = tween(180),
        label = "SearchFieldElevation",
    )

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .height(FIELD_HEIGHT)
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                focused = state.isFocused
                onFocusChanged(state.isFocused)
            }
            // Escape empties the field rather than merely blurring it: a stale query left in
            // a field you have visibly finished with is the thing you have to clear by hand
            // the next time you come back to the page.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onClear()
                    true
                } else {
                    false
                }
            }
            .shadow(elevation, CircleShape, clip = false)
            .clip(CircleShape)
            .background(colors.surfaceContainer)
            .border(borderWidth, borderColor, CircleShape)
            .hoverable(hovered),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.tertiary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSubmit() }),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LeadingIndicator(searching = searching)

                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Placeholder(cycling = !focused)
                    }
                    innerTextField()
                }

                AnimatedVisibility(
                    visible = query.isNotEmpty(),
                    enter = fadeIn(tween(120)) + scaleIn(
                        initialScale = 0.6f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                    ),
                    exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.6f),
                ) {
                    ClearButton(onClick = onClear)
                }
            }
        },
    )
}

/**
 * The search icon, which becomes the progress indicator while a query is in flight.
 *
 * Same slot, same size, so the row does not reflow — and it puts the "working" signal on the
 * control the viewer is already looking at instead of over the results they are waiting for.
 */
@Composable
private fun LeadingIndicator(searching: Boolean) {
    val colors = MaterialTheme.colorScheme

    AnimatedContent(
        targetState = searching,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
        label = "SearchFieldLeading",
    ) { busy ->
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(19.dp),
                color = colors.tertiary,
                strokeWidth = 2.dp,
            )
        } else {
            IconifyIcon(
                icon = "lucide:search",
                modifier = Modifier.size(20.dp),
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * "Search “heist”" — a worked example rather than a restatement of the page title.
 *
 * The example rotates only while the field is unfocused. Text that moves under a cursor
 * that is about to type over it is a distraction, not a hint, so focusing freezes it.
 */
@Composable
private fun Placeholder(cycling: Boolean) {
    val colors = MaterialTheme.colorScheme
    var index by remember { mutableStateOf(0) }

    LaunchedEffect(cycling) {
        if (!cycling) return@LaunchedEffect
        while (true) {
            delay(HINT_DWELL_MILLIS)
            index = (index + 1) % SEARCH_HINTS.size
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Search",
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        AnimatedContent(
            targetState = SEARCH_HINTS[index % SEARCH_HINTS.size],
            transitionSpec = {
                (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 2 }) togetherWith
                    (fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 2 })
            },
            label = "SearchFieldHint",
        ) { hint ->
            Text(
                text = "“$hint”",
                color = colors.onSurfaceVariant.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ClearButton(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .size(if (hasPointerHover) 30.dp else 48.dp)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = "Clear search" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    if (hovered) {
                        colors.onSurface.copy(alpha = 0.12f)
                    } else {
                        colors.onSurface.copy(alpha = 0.06f)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            IconifyIcon(
                icon = "lucide:x",
                modifier = Modifier.size(15.dp),
                tint = if (hovered) colors.onSurface else colors.onSurfaceVariant,
            )
        }
    }
}

/** Tall enough to be the page's primary control rather than a toolbar accessory. */
private val FIELD_HEIGHT = 54.dp

/** Long enough to read the example, short enough that the row is never stale. */
private const val HINT_DWELL_MILLIS = 3_600L
