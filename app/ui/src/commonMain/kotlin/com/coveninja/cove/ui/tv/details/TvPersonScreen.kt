package com.coveninja.cove.ui.tv.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.components.common.CoveAsyncImage
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.Person
import com.coveninja.cove.ui.model.PersonCreditEntry
import com.coveninja.cove.ui.model.filmographyOf
import com.coveninja.cove.ui.model.knownForOf
import com.coveninja.cove.ui.model.toMedia
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import com.coveninja.cove.ui.tv.focus.FocusOnAppear
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.components.TvMediaRow
import com.coveninja.cove.ui.tv.components.TvPosterCard
import com.coveninja.cove.ui.tv.focus.TvSectionScroll

/**
 * A person, and everything of theirs worth reaching from here.
 *
 * Two rows rather than the phone's filterable list: what they are known for, then everything,
 * newest first. `knownForOf` ranks by popularity rather than recency — what someone is known
 * for is rarely whatever they did last — and that is exactly the row a viewer who just saw a
 * face and wondered "what else" is asking for.
 *
 * The credit filters the phone offers (all / films / series) are dropped. They are three focus
 * stops in front of two rows that are already short.
 */
@Composable
internal fun TvPersonScreen(
    person: Person,
    onOpenMedia: (Media) -> Unit,
    modifier: Modifier = Modifier,
    /** True while the player is drawn over this screen. */
    covered: Boolean = false,
) {
    val dimens = TvTheme.dimens
    val listState = rememberLazyListState()
    var focusedSection by remember { mutableStateOf<Int?>(null) }

    val entryFocus = remember { FocusRequester() }
    // Same reasoning as the details screen: coming back out from the player has to put focus
    // somewhere, and this screen is what is underneath.
    FocusOnAppear(entryFocus, enabled = !covered)

    val knownFor = remember(person) { knownForOf(person.credits) }
    val everything = remember(person) { filmographyOf(person.credits) }

    TvSectionScroll(
        state = listState,
        focusedIndex = focusedSection,
        margin = dimens.focusScrollMargin,
    )

    Box(modifier = modifier.fillMaxSize().background(CoveColors.Neutral.Background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().focusRequester(entryFocus).focusRestorer(),
            contentPadding = PaddingValues(
                top = dimens.overscanVertical + 24.dp,
                bottom = dimens.overscanVertical + 32.dp,
            ),
        ) {
            item(key = "person") {
                Row(
                    modifier = Modifier.padding(horizontal = dimens.overscanHorizontal),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(132.dp)
                            .clip(CircleShape)
                            .background(CoveColors.Neutral.SurfaceHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (person.profileUrl != null) {
                            CoveAsyncImage(
                                model = person.profileUrl,
                                contentDescription = person.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            // A headshot is missing often enough that a blank circle would
                            // read as a failed image rather than as somebody without one.
                            Text(
                                text = person.initial,
                                style = MaterialTheme.typography.displaySmall,
                                color = CoveColors.Neutral.MutedDim,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(28.dp))
                    Column(modifier = Modifier.widthIn(max = dimens.width * 0.55f)) {
                        Text(
                            text = person.name,
                            style = MaterialTheme.typography.displaySmall,
                            color = CoveColors.Neutral.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        listOfNotNull(
                            person.knownForDepartment,
                            person.role?.takeIf { it.isNotBlank() },
                        ).joinToString("  ·  ").takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyLarge,
                                color = CoveColors.Neutral.Muted,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        person.biography?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = CoveColors.Neutral.MutedDim,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
            }

            if (knownFor.isNotEmpty()) {
                item(key = "known-for") {
                    TvCreditRow(
                        title = "Known for",
                        icon = "lucide:sparkles",
                        credits = knownFor,
                        onOpenMedia = onOpenMedia,
                        onFocusChanged = { if (it) focusedSection = 1 },
                    )
                }
            }
            if (everything.isNotEmpty()) {
                item(key = "filmography") {
                    TvCreditRow(
                        title = "Filmography",
                        icon = "lucide:clapperboard",
                        credits = everything,
                        onOpenMedia = onOpenMedia,
                        onFocusChanged = { if (it) focusedSection = if (knownFor.isEmpty()) 1 else 2 },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvCreditRow(
    title: String,
    icon: String,
    credits: List<PersonCreditEntry>,
    onOpenMedia: (Media) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    val dimens = TvTheme.dimens
    TvMediaRow(
        title = title,
        subtitle = "${credits.size} titles",
        icon = icon,
        items = credits,
        key = PersonCreditEntry::id,
        onFocusChanged = onFocusChanged,
        modifier = Modifier.padding(top = dimens.sectionSpacing),
    ) { credit ->
        TvPosterCard(
            posterUrl = credit.posterUrl,
            label = credit.title,
            onClick = { onOpenMedia(credit.toMedia()) },
        )
    }
}
