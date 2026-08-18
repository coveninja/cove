package com.coveninja.cove.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.state.LocalMotionPolicy

/**
 * The chrome every step sits in: backdrop, progress, header, footer.
 *
 * Only the middle changes between steps. Keeping the frame fixed is what makes the flow feel
 * like one screen being rearranged rather than seven screens in a row — the progress bar, the
 * mark and the footer buttons never move, so the eye only has to track the part that actually
 * changed.
 */
@Composable
internal fun OnboardingScaffold(
    controller: OnboardingController,
    modifier: Modifier = Modifier,
    stepContent: @Composable (OnboardingStep) -> Unit,
) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    // Observed here rather than on the tile field itself. The field is the bottom sibling in the
    // Box below and would never be hit-tested while the pointer is over a button, so the only
    // node that reliably sees every position is one that contains both.
    val pointer = rememberBackdropPointer()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compact = maxWidth < CompactWidth
        val gutter = if (compact) 20.dp else 40.dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CoveColors.Neutral.Background)
                .trackBackdropPointer(pointer),
        ) {
            OnboardingAurora()
            // Only behind the welcome screen. The middle five steps are asking for input, and a
            // field that lights up under the cursor competes with the thing being filled in
            // rather than setting a mood for it.
            if (controller.step == OnboardingStep.Welcome) {
                OnboardingTileField(pointer = pointer)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeContentPadding()
                    // The profile, sources and sync steps all have text fields; without this the
                    // software keyboard covers the footer and the flow cannot be advanced.
                    .imePadding()
                    .padding(horizontal = gutter, vertical = if (compact) 16.dp else 26.dp),
            ) {
                OnboardingTopBar(
                    controller = controller,
                    compact = compact,
                )

                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = controller.step,
                        transitionSpec = {
                            stepTransition(
                                advancing = controller.advancing,
                                reducedMotion = reducedMotion,
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "OnboardingStep",
                    ) { step ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = ContentMaxWidth)
                                    .padding(vertical = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(StepGap),
                            ) {
                                if (!step.ownsHeader()) {
                                    OnboardingStepHeader(step = step, compact = compact)
                                }
                                stepContent(step)
                            }
                        }
                    }
                }

                OnboardingFooter(controller = controller, compact = compact)
            }
        }
    }
}

/**
 * Welcome and Finish draw their own titles.
 *
 * Both are single gestures rather than tasks — one says hello, the other says goodbye — and
 * both want the mark and the headline centred on the whole panel, which the standard
 * left-aligned header cannot express.
 */
internal fun OnboardingStep.ownsHeader(): Boolean =
    this == OnboardingStep.Welcome || this == OnboardingStep.Finish

@Composable
private fun OnboardingTopBar(
    controller: OnboardingController,
    compact: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OnboardingProgressRail(
            controller = controller,
            modifier = Modifier.weight(1f),
        )
        // Nothing to skip past on the last screen, and offering it there would look like a
        // second, quieter way of doing what the primary button already does.
        if (controller.step != OnboardingStep.Finish) {
            OnboardingGhostButton(
                label = if (compact) "Skip" else "Skip setup",
                onClick = controller::skipAll,
            )
        }
    }
}

/**
 * The segmented progress bar.
 *
 * One segment per step, filling with a spring as it is reached, and completed segments holding
 * the accent rather than resetting. A single continuous bar was the first attempt and it read
 * as a loading indicator; discrete segments say "this many things, you are here".
 */
@Composable
private fun OnboardingProgressRail(
    controller: OnboardingController,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val currentIndex = controller.steps.indexOf(controller.step)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        controller.steps.forEachIndexed { index, _ ->
            val reached = index <= currentIndex
            val fill by animateFloatAsState(
                targetValue = if (reached) 1f else 0f,
                animationSpec = if (reducedMotion) {
                    snap()
                } else {
                    spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
                },
                label = "OnboardingProgressFill",
            )
            // The current segment is wider than the rest, so the bar says where you are as
            // well as how far you have come — colour alone is hard to count at a glance.
            val weight = if (index == currentIndex) 2.2f else 1f

            Box(
                modifier = Modifier
                    .weight(weight)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fill)
                        .clip(CircleShape)
                        .background(CoveColors.Brand.Accent),
                )
            }
        }
    }
}

@Composable
private fun OnboardingStepHeader(step: OnboardingStep, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CoveColors.Brand.Accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                IconifyIcon(
                    icon = step.icon,
                    tint = CoveColors.Brand.Accent,
                    modifier = Modifier.size(19.dp),
                )
            }
            Text(
                text = step.title,
                color = MaterialTheme.colorScheme.onBackground,
                style = if (compact) {
                    MaterialTheme.typography.headlineSmall
                } else {
                    MaterialTheme.typography.headlineMedium
                },
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = step.blurb,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.widthIn(max = BlurbMaxWidth),
        )
    }
}

@Composable
private fun OnboardingFooter(controller: OnboardingController, compact: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (controller.canGoBack) {
            OnboardingGhostButton(
                label = "Back",
                icon = "lucide:chevron-left",
                onClick = controller::back,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        OnboardingPrimaryButton(
            label = advanceLabel(controller.step, controller.draft),
            icon = if (controller.step == OnboardingStep.Finish) {
                "lucide:play"
            } else {
                "lucide:chevron-right"
            },
            busy = controller.committing,
            onClick = controller::advance,
            modifier = if (compact && !controller.canGoBack) Modifier.weight(1f) else Modifier,
        )
    }
}

/**
 * Slide in the direction of travel, and stay out of each other's way.
 *
 * `SizeTransform(clip = false)` is what stops the outgoing step being cropped to the incoming
 * one's height while both are on screen — without it a tall step leaving for a short one
 * visibly guillotines itself halfway through the slide. Built with the [ContentTransform]
 * constructor rather than `togetherWith(...).using(...)`, because `using` is only in scope
 * inside an `AnimatedContentTransitionScope` and this is resolved outside one.
 */
private fun stepTransition(advancing: Boolean, reducedMotion: Boolean): ContentTransform =
    if (reducedMotion) {
        ContentTransform(
            targetContentEnter = fadeIn(snap()),
            initialContentExit = fadeOut(snap()),
            sizeTransform = SizeTransform(clip = false),
        )
    } else {
        val distance = if (advancing) 1 else -1
        ContentTransform(
            targetContentEnter = slideInHorizontally(
                animationSpec = spring(
                    dampingRatio = 0.9f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) { width -> distance * width / 5 } + fadeIn(tween(220)),
            initialContentExit = slideOutHorizontally(animationSpec = tween(180)) { width ->
                -distance * width / 6
            } + fadeOut(tween(140)),
            sizeTransform = SizeTransform(clip = false),
        )
    }

/** Below this the flow stops being a centred card and becomes a full-bleed phone screen. */
private val CompactWidth: Dp = 720.dp

/** A measure long enough for a poster wall, short enough that prose stays readable. */
private val ContentMaxWidth: Dp = 880.dp
private val BlurbMaxWidth: Dp = 560.dp
