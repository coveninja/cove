package com.coveninja.cove.ui.components.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage as CoilAsyncImage
import kotlinx.coroutines.delay

/**
 * Cove's network-image boundary.
 *
 * Coil intentionally treats a failed request as complete. That is sensible for a bad URL but
 * unfriendly on phones moving between Wi-Fi and mobile data, where a brief DNS/TLS failure used
 * to leave a blank poster until the card was recreated. Re-keying the painter retries the same
 * cacheable URL with a small backoff; leaving the URL itself unchanged preserves Coil's memory
 * and disk-cache identity.
 */
@Composable
fun CoveAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    filterQuality: FilterQuality = FilterQuality.Low,
) {
    var attempt by remember(model) { mutableIntStateOf(0) }
    var failedAttempt by remember(model) { mutableStateOf<Int?>(null) }

    LaunchedEffect(model, failedAttempt) {
        val failed = failedAttempt ?: return@LaunchedEffect
        val retryDelay = imageRetryDelayMillis(failed) ?: return@LaunchedEffect
        delay(retryDelay)
        if (attempt == failed) {
            failedAttempt = null
            attempt = failed + 1
        }
    }

    key(model, attempt) {
        CoilAsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = modifier,
            alignment = alignment,
            contentScale = contentScale,
            filterQuality = filterQuality,
            onError = {
                if (model != null && failedAttempt != attempt) failedAttempt = attempt
            },
        )
    }
}

internal fun imageRetryDelayMillis(failedAttempt: Int): Long? = when (failedAttempt) {
    0 -> 300L
    1 -> 1_000L
    2 -> 3_000L
    else -> null
}
