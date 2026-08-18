package com.coveninja.cove.ui.onboarding.steps

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.onboarding.OnboardingController
import com.coveninja.cove.ui.onboarding.OnboardingCountBadge
import com.coveninja.cove.ui.onboarding.summaryFor
import com.coveninja.cove.ui.state.LocalMotionPolicy

/**
 * The payoff.
 *
 * A check mark that lands with a bounce, a burst of confetti fired by the flow root above this,
 * and a count of what the viewer actually accomplished. The summary is built from the draft
 * rather than from what the commit reported — see `summaryFor` — and shows nothing at all when
 * everything was skipped, because congratulating someone with "0 sources" is worse than saying
 * nothing.
 */
@Composable
internal fun FinishStep(
    controller: OnboardingController,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val summary = remember(controller.draft) { summaryFor(controller.draft) }

    val land = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            land.snapTo(1f)
        } else {
            land.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessLow),
            )
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .graphicsLayer { alpha = land.value }
                    .background(
                        Brush.radialGradient(
                            listOf(CoveColors.Brand.Accent.copy(alpha = 0.24f), Color.Transparent),
                        ),
                        CircleShape,
                    ),
            )
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .graphicsLayer {
                        scaleX = land.value
                        scaleY = land.value
                        alpha = land.value.coerceIn(0f, 1f)
                    }
                    .background(CoveColors.Brand.Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                IconifyIcon(
                    icon = "lucide:check",
                    tint = CoveColors.Brand.OnAccent,
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        Text(
            text = "You're all set",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = finishBlurb(summary.isEmpty()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp),
        )

        if (summary.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                summary.forEach { item ->
                    OnboardingCountBadge(
                        icon = item.icon,
                        count = item.count,
                        label = item.label,
                    )
                }
            }
        }
    }
}

/**
 * Two endings, because a flow that was entirely skipped has not accomplished anything and
 * should not claim to. It gets a pointer to where the skipped steps live instead.
 */
private fun finishBlurb(skippedEverything: Boolean): String = if (skippedEverything) {
    "Everything you passed on is in Settings whenever you want it — sources, sync and the rest."
} else {
    "Home is already building itself around what you picked. Enjoy."
}
