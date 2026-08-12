package com.coveninja.cove.ui.pages.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.common.HorizontalLazyListScrollbar
import com.coveninja.cove.ui.components.media.card.PersonCard
import com.coveninja.cove.ui.model.Person

/**
 * The people a query matched, above the titles it matched.
 *
 * A rail rather than a second grid, and above rather than below, for the same reason: a
 * person is usually the way through to a title rather than the thing being looked for, so
 * it has to be visible without scrolling and cost almost nothing when it is not what was
 * wanted. Searching an actor's name lands you on their face; opening it lands you on
 * everything they have been in.
 */
@Composable
fun SearchPeopleRail(
    people: List<Person>,
    onOpenPerson: (Person) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (people.isEmpty()) return

    val listState = rememberLazyListState()

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = if (people.size == 1) "1 person" else "${people.size} people",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )

        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(186.dp)
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items = people, key = { person -> person.id }) { person ->
                PersonCard(
                    person = person,
                    onClick = { onOpenPerson(person) },
                )
            }
        }

        HorizontalLazyListScrollbar(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}
