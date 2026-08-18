package com.coveninja.cove.ui.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.onboarding.OnboardingController
import com.coveninja.cove.ui.onboarding.OnboardingToggleCard

/**
 * Three switches, and deliberately only three.
 *
 * `AppSettings` has thirty-six fields and every one of them is reachable from the settings
 * page. These are the three whose default someone is most likely to disagree with on their
 * first evening — and each is phrased as what it does for the viewer rather than as the field
 * it sets, because "autoPlay" is a schema name and "roll straight into the next episode" is
 * the thing being chosen.
 *
 * Nothing is written here. The draft carries the answers to the final commit, so backing out
 * of this step genuinely backs out of it.
 */
@Composable
internal fun PreferencesStep(
    controller: OnboardingController,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OnboardingToggleCard(
            icon = "lucide:captions",
            title = "Subtitles on by default",
            description = "Turn them on for everything, and pick a language in Settings.",
            checked = controller.draft.subtitlesEnabled,
            onCheckedChange = { value ->
                controller.update { copy(subtitlesEnabled = value) }
            },
        )
        OnboardingToggleCard(
            icon = "lucide:list-video",
            title = "Roll into the next episode",
            description = "When one finishes, start the next without asking.",
            checked = controller.draft.autoPlayNext,
            onCheckedChange = { value ->
                controller.update { copy(autoPlayNext = value) }
            },
        )
        OnboardingToggleCard(
            icon = "lucide:skip-forward",
            title = "Skip intros automatically",
            description = "Where Cove knows the timestamps, jump the opening titles.",
            checked = controller.draft.autoSkipIntro,
            onCheckedChange = { value ->
                controller.update { copy(autoSkipIntro = value) }
            },
        )

        Text(
            text = "All three live in Settings → Playback, along with everything else.",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
