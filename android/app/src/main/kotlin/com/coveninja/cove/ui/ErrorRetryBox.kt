package com.coveninja.cove.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Centered error state with an outlined Error icon (error color), a descriptive
 * [message], and a "Retry" button that calls [onRetry].
 *
 * The [modifier] parameter controls the outer Box's size — callers that want
 * the box to fill all available space should pass [Modifier.fillMaxSize()];
 * callers inside a [LazyColumn] or bottom sheet (unbounded height) should use
 * [Modifier.fillMaxWidth()] with an appropriate padding or min-height instead.
 */
@Composable
fun ErrorRetryBox(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
) {
    Box(
        modifier = modifier.padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}
