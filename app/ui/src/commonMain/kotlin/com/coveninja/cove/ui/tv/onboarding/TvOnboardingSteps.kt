package com.coveninja.cove.ui.tv.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.AccountState
import com.coveninja.cove.shared.data.AddonsState
import com.coveninja.cove.shared.data.AuthOutcome
import com.coveninja.cove.shared.data.HomeState
import com.coveninja.cove.shared.data.ProfilesState
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.icons.CoveLogo
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.toUiMedia
import com.coveninja.cove.ui.onboarding.OnboardingController
import com.coveninja.cove.ui.onboarding.OnboardingGenres
import com.coveninja.cove.ui.onboarding.OnboardingPick
import com.coveninja.cove.ui.onboarding.OnboardingStep
import com.coveninja.cove.ui.onboarding.emblemFor
import com.coveninja.cove.ui.onboarding.manifestUrlSubmittable
import com.coveninja.cove.ui.onboarding.rankByGenre
import com.coveninja.cove.ui.onboarding.summaryFor
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.LocalMotionPolicy
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.components.TvButton
import com.coveninja.cove.ui.tv.components.TvPosterCard
import com.coveninja.cove.ui.tv.components.TvSettingRow
import com.coveninja.cove.ui.tv.components.TvTextField
import com.coveninja.cove.ui.tv.focus.FocusOnAppear
import com.coveninja.cove.ui.tv.focus.tvFocusAnchor
import com.coveninja.cove.ui.tv.focus.tvFocusGroup
import kotlinx.coroutines.launch

/**
 * The seven steps as a remote sees them.
 *
 * Everything here reads and writes the same [OnboardingController] the pointer flow uses, so
 * the two are the same flow asking the same questions. What changes is the vocabulary: buttons
 * instead of switches, rows instead of cards, and — the important one — a visible acknowledgement
 * that typing a URL with a D-pad is miserable, so the sources step leads with the way out.
 */
@Composable
internal fun TvOnboardingStepContent(
    step: OnboardingStep,
    controller: OnboardingController,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.widthIn(max = TvStepMaxWidth)) {
        when (step) {
            OnboardingStep.Welcome -> TvWelcomeStep()
            OnboardingStep.Profile -> TvProfileStep(controller)
            OnboardingStep.Taste -> TvTasteStep(controller)
            OnboardingStep.Sources -> TvSourcesStep(controller)
            OnboardingStep.Preferences -> TvPreferencesStep(controller)
            OnboardingStep.Sync -> TvSyncStep(controller)
            OnboardingStep.Finish -> TvFinishStep(controller)
        }
    }
}

@Composable
private fun TvWelcomeStep(modifier: Modifier = Modifier) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val entrance = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            entrance.snapTo(1f)
        } else {
            entrance.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow),
            )
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(230.dp)
                    .graphicsLayer { alpha = entrance.value }
                    .background(
                        Brush.radialGradient(
                            listOf(CoveColors.Brand.Accent.copy(alpha = 0.22f), Color.Transparent),
                        ),
                        CircleShape,
                    ),
            )
            CoveLogo(
                modifier = Modifier.size(132.dp).graphicsLayer {
                    scaleX = entrance.value
                    scaleY = entrance.value
                    alpha = entrance.value.coerceIn(0f, 1f)
                },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TV_CLAIMS.forEach { (icon, label) ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconifyIcon(
                        icon = icon,
                        tint = CoveColors.Brand.Accent,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = label,
                        color = CoveColors.Neutral.Text,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Text(
                text = "Press OK to begin. Everything here can be skipped.",
                color = CoveColors.Neutral.Muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun TvProfileStep(controller: OnboardingController, modifier: Modifier = Modifier) {
    val profiles by LocalAppGraph.current.profiles.profiles.collectAsState()
    val field = remember { FocusRequester() }
    FocusOnAppear(field)

    val storedName = remember(profiles) {
        (profiles as? ProfilesState.Ready)?.let { ready ->
            ready.profiles.firstOrNull { it.id == ready.activeProfileId }?.name
        }.orEmpty()
    }
    LaunchedEffect(storedName) {
        if (controller.draft.profileName.isEmpty() && storedName.isNotEmpty()) {
            controller.update { copy(profileName = storedName) }
        }
    }

    val emblem = emblemFor(controller.draft.profileName)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(104.dp).clip(CircleShape).background(emblem.color),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = emblem.initial,
                color = CoveColors.Brand.OnAccent,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvTextField(
                value = controller.draft.profileName,
                onValueChange = { name -> controller.update { copy(profileName = name) } },
                label = "Profile name",
                placeholder = "Your name",
                modifier = Modifier.fillMaxWidth().tvFocusAnchor(field),
            )
            Text(
                text = "Each profile keeps its own library, progress and recommendations.",
                color = CoveColors.Neutral.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TvTasteStep(controller: OnboardingController, modifier: Modifier = Modifier) {
    val homeState by LocalAppGraph.current.content.home.collectAsState()
    val dimens = TvTheme.dimens
    val first = remember { FocusRequester() }
    FocusOnAppear(first)

    val catalog: List<Media> = remember(homeState) {
        (homeState as? HomeState.Ready)?.items
            ?.map { it.toUiMedia() }
            ?.filter { !it.posterUrl.isNullOrBlank() }
            ?.distinctBy { it.id }
            .orEmpty()
    }
    val ordered = remember(catalog, controller.draft.likedGenreIds) {
        rankByGenre(catalog, controller.draft.likedGenreIds, Media::genreIds).take(TV_WALL_SIZE)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().tvFocusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OnboardingGenres.take(TV_GENRE_COUNT).forEachIndexed { index, genre ->
                val selected = genre.id in controller.draft.likedGenreIds
                TvButton(
                    label = genre.label,
                    icon = if (selected) "lucide:check" else genre.icon,
                    selected = selected,
                    onClick = { controller.toggleGenre(genre.id) },
                    modifier = if (index == 0) Modifier.tvFocusAnchor(first) else Modifier,
                )
            }
        }

        if (ordered.isNotEmpty()) {
            Text(
                text = if (controller.draft.likedTitles.isEmpty()) {
                    "Pick a few you like"
                } else {
                    "${controller.draft.likedTitles.size} picked"
                },
                color = CoveColors.Neutral.Muted,
                style = MaterialTheme.typography.labelLarge,
            )
            // Sized from the room this step was actually given, not from TvDimens.posterWidth.
            // The rail-sized poster is tuned for a full-width row on Home; here it shares the
            // panel with a fixed sidebar, and five of them at that size run off a 960 dp screen.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val posterWidth = tvWallPosterWidth(
                    available = maxWidth,
                    gap = dimens.cardSpacing,
                    count = TV_WALL_SIZE,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().tvFocusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
                ) {
                    ordered.forEach { media ->
                    Box {
                        TvPosterCard(
                            posterUrl = media.posterUrl,
                            label = media.title ?: media.name.orEmpty(),
                            width = posterWidth,
                            onClick = { controller.togglePick(media.toPick()) },
                        )
                        if (controller.isPicked(media.id)) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(CoveColors.Brand.Accent),
                                contentAlignment = Alignment.Center,
                            ) {
                                IconifyIcon(
                                    icon = "lucide:heart",
                                    tint = CoveColors.Brand.OnAccent,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvSourcesStep(controller: OnboardingController, modifier: Modifier = Modifier) {
    val repository = LocalAppGraph.current.addons
    val addonsState by repository.state.collectAsState()
    val repositoryError by repository.lastError.collectAsState()
    val scope = rememberCoroutineScope()
    val field = remember { FocusRequester() }
    FocusOnAppear(field)

    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val installed = remember(addonsState) {
        (addonsState as? AddonsState.Ready)?.addons?.map { it.manifest.name.ifBlank { it.url } }
            .orEmpty()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "Cove doesn't host any media. Providers are addons you install yourself.",
            color = CoveColors.Neutral.Text,
            style = MaterialTheme.typography.bodyLarge,
        )
        // Said first and said plainly. Typing a manifest URL on an on-screen keyboard with four
        // arrows is genuinely unpleasant, and the account this device may already be signed
        // into will carry the addons across on its own.
        Text(
            text = "Easiest on a phone or desktop: add sources there, sign in on both, and they " +
                "arrive here by themselves. Or type one now.",
            color = CoveColors.Neutral.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            modifier = Modifier.fillMaxWidth().tvFocusGroup(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TvTextField(
                value = url,
                onValueChange = { url = it },
                label = "Manifest URL",
                placeholder = "https://…/manifest.json",
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.weight(1f).tvFocusAnchor(field),
            )
            TvButton(
                label = if (busy) "Adding…" else "Add",
                icon = "lucide:plus",
                enabled = manifestUrlSubmittable(url) && !busy,
                onClick = {
                    val candidate = url.trim()
                    busy = true
                    scope.launch {
                        repository.addAddon(candidate)
                        busy = false
                        if (repository.lastError.value == null) {
                            controller.rememberAddon(candidate)
                            url = ""
                        }
                    }
                },
            )
        }

        repositoryError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (installed.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                installed.forEach { name ->
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(CoveColors.Brand.AccentContainer)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconifyIcon(
                            icon = "lucide:check",
                            tint = CoveColors.Brand.Accent,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = name,
                            color = CoveColors.Brand.Accent,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvPreferencesStep(controller: OnboardingController, modifier: Modifier = Modifier) {
    val first = remember { FocusRequester() }
    FocusOnAppear(first)

    Column(
        modifier = modifier.fillMaxWidth().tvFocusGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TvSettingRow(
            label = "Subtitles on by default",
            detail = "Turn them on for everything.",
            value = onOff(controller.draft.subtitlesEnabled),
            highlighted = controller.draft.subtitlesEnabled,
            onActivate = { controller.update { copy(subtitlesEnabled = !subtitlesEnabled) } },
            modifier = Modifier.tvFocusAnchor(first),
        )
        TvSettingRow(
            label = "Roll into the next episode",
            detail = "When one finishes, start the next without asking.",
            value = onOff(controller.draft.autoPlayNext),
            highlighted = controller.draft.autoPlayNext,
            onActivate = { controller.update { copy(autoPlayNext = !autoPlayNext) } },
        )
        TvSettingRow(
            label = "Skip intros automatically",
            detail = "Where Cove knows the timestamps, jump the opening titles.",
            value = onOff(controller.draft.autoSkipIntro),
            highlighted = controller.draft.autoSkipIntro,
            onActivate = { controller.update { copy(autoSkipIntro = !autoSkipIntro) } },
        )
    }
}

@Composable
private fun TvSyncStep(controller: OnboardingController, modifier: Modifier = Modifier) {
    val repository = LocalAppGraph.current.account
    val account by repository.account.collectAsState()
    val scope = rememberCoroutineScope()
    val field = remember { FocusRequester() }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val signedIn = account as? AccountState.SignedIn
    FocusOnAppear(field, enabled = signedIn == null)

    if (signedIn != null) {
        Text(
            text = "Signed in as ${signedIn.email}. Your library will follow you to your " +
                "other devices.",
            color = CoveColors.Neutral.Text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier.fillMaxWidth().tvFocusGroup(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Sign-in only, with no register or emailed-code alternative: creating an account and
        // typing a password twice belongs on a device with a keyboard, and the television's job
        // is to join a household that already has one.
        TvTextField(
            value = email,
            onValueChange = { email = it },
            label = "Email",
            placeholder = "you@example.com",
            keyboardType = KeyboardType.Email,
            modifier = Modifier.fillMaxWidth().tvFocusAnchor(field),
        )
        TvTextField(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            secret = true,
            modifier = Modifier.fillMaxWidth(),
        )
        message?.let { text ->
            Text(
                text = text,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TvButton(
            label = if (busy) "Signing in…" else "Sign in",
            icon = "lucide:cloud",
            enabled = email.isNotBlank() && password.isNotBlank() && !busy,
            onClick = {
                busy = true
                message = null
                scope.launch {
                    when (val outcome = repository.signIn(email, password)) {
                        AuthOutcome.Success -> controller.update { copy(signedIn = true) }
                        is AuthOutcome.Failure -> message = outcome.message
                        AuthOutcome.ConfirmationRequired ->
                            message = "This account still needs confirming — finish that on " +
                                "a phone or desktop, then sign in here."
                    }
                    busy = false
                }
            },
        )
        Text(
            text = "No account? Skip this. Nothing on this device is lost, and you can sign " +
                "in later from Settings.",
            color = CoveColors.Neutral.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TvFinishStep(controller: OnboardingController, modifier: Modifier = Modifier) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val summary = remember(controller.draft) { summaryFor(controller.draft) }
    val land = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            land.snapTo(1f)
        } else {
            land.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessLow))
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .graphicsLayer {
                    scaleX = land.value
                    scaleY = land.value
                    alpha = land.value.coerceIn(0f, 1f)
                }
                .clip(CircleShape)
                .background(CoveColors.Brand.Accent),
            contentAlignment = Alignment.Center,
        ) {
            IconifyIcon(
                icon = "lucide:check",
                tint = CoveColors.Brand.OnAccent,
                modifier = Modifier.size(54.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = if (summary.isEmpty()) {
                    "Everything you passed on is in Settings whenever you want it."
                } else {
                    "Home is already building itself around what you picked."
                },
                color = CoveColors.Neutral.Text,
                style = MaterialTheme.typography.titleLarge,
            )
            if (summary.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    summary.forEach { item ->
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(CoveColors.Neutral.SurfaceRaised)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconifyIcon(
                                icon = item.icon,
                                tint = CoveColors.Brand.Accent,
                                modifier = Modifier.size(17.dp),
                            )
                            Text(
                                text = "${item.count} ${item.label}",
                                color = CoveColors.Neutral.Text,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun onOff(value: Boolean): String = if (value) "On" else "Off"

private fun Media.toPick(): OnboardingPick = OnboardingPick(
    id = id,
    tmdbId = tmdbId,
    type = type,
    title = title ?: name ?: "Untitled",
    posterUrl = posterUrl.orEmpty(),
    voteAverage = rating ?: 0.0,
)

/** A single row that fits inside the step column without scrolling. */
private const val TV_WALL_SIZE = 5

/**
 * Six bubbles, not sixteen and not eight.
 *
 * The step does not scroll — a television screen whose bottom half nobody knows exists is the
 * failure this shell is built to avoid — so everything has to fit at 960×540 dp, which is what a
 * 1080p panel reports at density 2. Eight wrapped to three rows there and pushed the last few
 * pixels of the poster labels off the bottom; six wraps to two and leaves real headroom.
 */
private const val TV_GENRE_COUNT = 6

private val TV_CLAIMS = listOf(
    "lucide:library" to "One library, every device",
    "lucide:blocks" to "Sources you choose yourself",
    "lucide:cloud" to "Progress that follows you",
)
