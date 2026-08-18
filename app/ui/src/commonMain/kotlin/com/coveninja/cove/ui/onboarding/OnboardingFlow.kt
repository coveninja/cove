package com.coveninja.cove.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import com.coveninja.cove.ui.onboarding.steps.FinishStep
import com.coveninja.cove.ui.onboarding.steps.PreferencesStep
import com.coveninja.cove.ui.onboarding.steps.ProfileStep
import com.coveninja.cove.ui.onboarding.steps.SourcesStep
import com.coveninja.cove.ui.onboarding.steps.SyncStep
import com.coveninja.cove.ui.onboarding.steps.TasteStep
import com.coveninja.cove.ui.onboarding.steps.WelcomeStep
import com.coveninja.cove.ui.platform.PlatformBackHandler

/**
 * First run, for a mouse or a finger.
 *
 * The television has its own root ([com.coveninja.cove.ui.tv.onboarding.TvOnboardingFlow]) for
 * the same reason `CoveTvApp` is separate from `CoveApp`: hover, precise targets and a text
 * field you can type into are assumptions this screen makes freely and a remote cannot meet.
 * Both roots drive the same [OnboardingController], so the two flows cannot drift apart in what
 * they ask or what they write — only in how they ask it.
 */
@Composable
fun OnboardingFlow(
    /** True when opened by `--onboarding` rather than by a genuinely fresh install. */
    preview: Boolean = false,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = rememberOnboardingController(preview = preview, onFinished = onFinished)

    // Fires once, on arrival, and stays fired. Keyed on the step rather than on a callback so
    // that stepping back and forward again re-runs it — which is what someone reviewing the
    // finish screen during design work expects to happen.
    var celebrate by remember { mutableStateOf(false) }
    LaunchedEffect(controller.step) {
        celebrate = controller.step == OnboardingStep.Finish
    }

    // Back walks the flow rather than leaving the app. On the first step there is nothing
    // behind it, and the handler stands down so the system can do its usual thing.
    PlatformBackHandler(enabled = controller.canGoBack) { controller.back() }

    Box(modifier = modifier.fillMaxSize()) {
        OnboardingScaffold(controller = controller) { step ->
            when (step) {
                OnboardingStep.Welcome -> WelcomeStep()
                OnboardingStep.Profile -> ProfileStep(controller = controller)
                OnboardingStep.Taste -> TasteStep(controller = controller)
                OnboardingStep.Sources -> SourcesStep(controller = controller)
                OnboardingStep.Preferences -> PreferencesStep(controller = controller)
                OnboardingStep.Sync -> SyncStep(controller = controller)
                OnboardingStep.Finish -> FinishStep(controller = controller)
            }
        }

        // Above the scaffold and unable to receive input: confetti that swallowed the press on
        // "Start watching" would be a genuinely infuriating way to end a first run.
        OnboardingConfetti(
            active = celebrate,
            modifier = Modifier.fillMaxSize().zIndex(10f),
        )
    }
}
