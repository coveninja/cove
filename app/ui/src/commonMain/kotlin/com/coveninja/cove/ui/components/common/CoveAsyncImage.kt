package com.coveninja.cove.ui.components.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import coil3.network.HttpException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
    var traceCookie by remember(model, attempt) { mutableIntStateOf(0) }

    LaunchedEffect(model, failedAttempt) {
        val failed = failedAttempt ?: return@LaunchedEffect
        val retryDelay = imageRetryDelayMillis(failed) ?: return@LaunchedEffect
        delay(retryDelay)
        ImageRetryGate.awaitTurn()
        if (attempt == failed) {
            failedAttempt = null
            attempt = failed + 1
        }
    }

    key(model, attempt) {
        DisposableEffect(model, attempt) {
            onDispose {
                if (traceCookie != 0) ImageLoadTrace.end(traceCookie, "cancelled")
            }
        }
        CoilAsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = modifier,
            alignment = alignment,
            contentScale = contentScale,
            filterQuality = filterQuality,
            onLoading = {
                if (traceCookie == 0) traceCookie = ImageLoadTrace.begin(model)
            },
            onSuccess = {
                if (traceCookie != 0) ImageLoadTrace.end(traceCookie, "success")
                traceCookie = 0
                failedAttempt = null
            },
            onError = { state ->
                if (traceCookie != 0) ImageLoadTrace.end(traceCookie, "error")
                traceCookie = 0
                if (
                    model != null &&
                    failedAttempt != attempt &&
                    isTransientImageFailure(state.result.throwable)
                ) {
                    failedAttempt = attempt
                }
            },
        )
    }
}

/** Permanent HTTP and decoding failures stay failed; reconnectable transport errors retry. */
internal fun isTransientImageFailure(error: Throwable): Boolean {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    if (chain.any { throwable -> imageFailureLooksLikeDecode(throwable::class.qualifiedName.orEmpty()) }) {
        return false
    }
    chain.filterIsInstance<HttpException>().firstOrNull()?.let { exception ->
        return imageHttpStatusIsTransient(exception.response.code)
    }
    return chain.any { throwable ->
        imageFailureClassIsTransient(throwable::class.qualifiedName.orEmpty())
    }
}

internal fun imageHttpStatusIsTransient(status: Int): Boolean =
    status == 408 || status == 425 || status == 429 || status in 500..599

internal fun imageFailureClassIsTransient(className: String): Boolean {
    val simple = className.substringAfterLast('.')
    return simple.endsWith("IOException") ||
        simple.contains("Timeout", ignoreCase = true) ||
        simple.contains("UnknownHost", ignoreCase = true) ||
        simple.contains("ConnectException", ignoreCase = true) ||
        simple.contains("SocketException", ignoreCase = true) ||
        simple.contains("Dns", ignoreCase = true)
}

private fun imageFailureLooksLikeDecode(className: String): Boolean {
    val simple = className.substringAfterLast('.')
    return simple.contains("Decode", ignoreCase = true) ||
        simple.contains("BitmapFactory", ignoreCase = true)
}

/** Spreads reconnect retries so a full poster rail does not wake up in one network burst. */
private object ImageRetryGate {
    private val mutex = Mutex()
    private var lastRetry: TimeMark? = null

    suspend fun awaitTurn() = mutex.withLock {
        val remaining = MIN_RETRY_SPACING_MILLIS -
            (lastRetry?.elapsedNow()?.inWholeMilliseconds ?: MIN_RETRY_SPACING_MILLIS)
        if (remaining > 0) delay(remaining)
        lastRetry = TimeSource.Monotonic.markNow()
    }

    private const val MIN_RETRY_SPACING_MILLIS = 125L
}

internal fun imageRetryDelayMillis(failedAttempt: Int): Long? = when (failedAttempt) {
    0 -> 300L
    1 -> 1_000L
    2 -> 3_000L
    else -> null
}
