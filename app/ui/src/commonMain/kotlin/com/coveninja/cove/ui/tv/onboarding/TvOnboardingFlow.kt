package com.coveninja.cove.ui.tv.onboarding

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
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.onboarding.OnboardingAurora
import com.coveninja.cove.ui.onboarding.OnboardingConfetti
import com.coveninja.cove.ui.onboarding.OnboardingController
import com.coveninja.cove.ui.onboarding.OnboardingStep
import com.coveninja.cove.ui.onboarding.advanceLabel
import com.coveninja.cove.ui.onboarding.rememberOnboardingController
import com.coveninja.cove.ui.platform.PlatformBackHandler
import com.coveninja.cove.ui.state.LocalMotionPolicy
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.components.TvButton
import com.coveninja.cove.ui.tv.focus.FocusOnAppear
import com.coveninja.cove.ui.tv.focus.tvFocusAnchor

/**
 * First run, for a remote.
 *
 * A separate root from [com.coveninja.cove.ui.onboarding.OnboardingFlow] and a shared
 * controller — the same division `CoveTvApp` makes against `CoveApp`, for the same reason. The
 * pointer flow is built out of hover states, precise poster tiles and text fields you type into
 * without thinking about it; a viewer holding a remote has none of that, and a breakpoint
 * cannot invent it.
 *
 * The layout is two columns rather than the phone's stacked one: at three metres a screen that
 * scrolls is a screen whose bottom half nobody knows exists, so the step's title and progress
 * live permanently on the left and only the right column changes.
 *
 * **Focus is the whole design problem here.** Every step hands focus to its own first control
 * through [FocusOnAppear], because a step change removes whatever held focus from the
 * composition and Compose does not place it anywhere else — with no pointer to click something
 * with, that leaves the entire interface dead. This is the same trap `CoveTvApp` documents
 * around its overlays.
 */
@Composable
fun TvOnboardingFlow(
    preview: Boolean = false,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = rememberOnboardingController(preview = preview, onFinished = onFinished)
    val dimens = TvTheme.dimens
    val reducedMotion = LocalMotionPolicy.current.reducedMotion

    var celebrate by remember { mutableStateOf(false) }
    LaunchedEffect(controller.step) {
        celebrate = controller.step == OnboardingStep.Finish
    }

    // Back walks the flow. Unlike the main TV shell there is no rail to fall back to, so the
    // first step lets the press through and the system closes the app — which is the only
    // correct answer when Back on the very first screen of a first run means "not now".
    PlatformBackHandler(enabled = controller.canGoBack) { controller.back() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(CoveColors.Neutral.Background),
    ) {
        // Captured before the Row's scope shadows the BoxWithConstraints receiver.
        val sidebarWidth = tvSidebarWidthFor(maxWidth)

        OnboardingAurora()

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = dimens.overscanHorizontal + 24.dp,
                    vertical = dimens.overscanVertical + 16.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            TvOnboardingSidebar(
                controller = controller,
                modifier = Modifier.width(sidebarWidth).fillMaxHeight(),
            )

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                AnimatedContent(
                    targetState = controller.step,
                    transitionSpec = {
                        val distance = if (controller.advancing) 1 else -1
                        if (reducedMotion) {
                            ContentTransform(
                                targetContentEnter = fadeIn(snap()),
                                initialContentExit = fadeOut(snap()),
                                sizeTransform = SizeTransform(clip = false),
                            )
                        } else {
                            ContentTransform(
                                targetContentEnter = slideInHorizontally(
                                    animationSpec = spring(
                                        dampingRatio = 0.9f,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                ) { width -> distance * width / 6 } + fadeIn(tween(220)),
                                initialContentExit = fadeOut(tween(140)),
                                sizeTransform = SizeTransform(clip = false),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    label = "TvOnboardingStep",
                ) { step ->
                    Box(
                        modifier = Modifier.fillMaxSize().focusGroup(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        TvOnboardingStepContent(step = step, controller = controller)
                    }
                }

                TvOnboardingFooter(controller = controller)
            }
        }

        OnboardingConfetti(
            active = celebrate,
            modifier = Modifier.fillMaxSize().zIndex(10f),
        )
    }
}

/**
 * The permanent left column: where you are, and what this step is for.
 *
 * Fixed rather than scrolling with the content, so the viewer always has the question in front
 * of them while they answer it. On a phone the header scrolls away and that is fine; across a
 * room, losing the question is losing the step.
 */
@Composable
private fun TvOnboardingSidebar(
    controller: OnboardingController,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val currentIndex = controller.steps.indexOf(controller.step)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CoveColors.Brand.Accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                IconifyIcon(
                    icon = controller.step.icon,
                    tint = CoveColors.Brand.Accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = "Step ${currentIndex + 1} of ${controller.steps.size}",
                color = CoveColors.Neutral.Muted,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Text(
            text = controller.step.title,
            color = CoveColors.Neutral.Text,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = controller.step.blurb,
            color = CoveColors.Neutral.Muted,
            style = MaterialTheme.typography.bodyLarge,
        )

        Spacer(modifier = Modifier.weight(1f))

        // Vertical dots rather than the phone's horizontal bar: the sidebar is a column, and a
        // bar laid across it would be short enough that the segments stopped being countable.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            controller.steps.forEachIndexed { index, _ ->
                val reached = index <= currentIndex
                val fill by animateFloatAsState(
                    targetValue = if (reached) 1f else 0f,
                    animationSpec = if (reducedMotion) {
                        snap()
                    } else {
                        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
                    },
                    label = "TvOnboardingProgress",
                )
                Box(
                    modifier = Modifier
                        .width(if (index == currentIndex) 64.dp else 34.dp)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(CoveColors.Neutral.Text.copy(alpha = 0.16f)),
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
}

/**
 * The footer, and the flow's focus anchor.
 *
 * The forward button takes focus on every step whose body has nothing focusable of its own —
 * the welcome and finish screens — so there is never a frame where the remote does nothing. On
 * the rest, the step's own first control claims it and pressing Down reaches this row.
 */
@Composable
private fun TvOnboardingFooter(
    controller: OnboardingController,
    modifier: Modifier = Modifier,
) {
    val advanceFocus = remember { FocusRequester() }
    FocusOnAppear(
        requester = advanceFocus,
        enabled = controller.step.hasNoFocusableBody(),
    )

    Row(
        modifier = modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (controller.canGoBack) {
            TvButton(
                label = "Back",
                icon = "lucide:chevron-left",
                onClick = controller::back,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (controller.step != OnboardingStep.Finish) {
            TvButton(label = "Skip setup", onClick = controller::skipAll)
        }
        TvButton(
            label = advanceLabel(controller.step, controller.draft),
            icon = if (controller.step == OnboardingStep.Finish) {
                "lucide:play"
            } else {
                "lucide:chevron-right"
            },
            primary = true,
            onClick = controller::advance,
            modifier = Modifier.tvFocusAnchor(advanceFocus),
        )
    }
}

/** Steps whose body is text and artwork only, so the footer has to be what focus lands on. */
private fun OnboardingStep.hasNoFocusableBody(): Boolean =
    this == OnboardingStep.Welcome || this == OnboardingStep.Finish

/** Shared max width for the step bodies, so the seven do not each pick their own measure. */
internal val TvStepMaxWidth = 760.dp
