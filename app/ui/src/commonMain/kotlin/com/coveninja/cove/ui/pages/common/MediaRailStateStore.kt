package com.coveninja.cove.ui.pages.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

/**
 * Retains horizontal rail positions while a primary page is not composed.
 *
 * Each state also asks Compose to precompose the three cards that fit a typical phone viewport
 * when its containing vertical list is prefetched. This moves nested-list composition into idle
 * time instead of the first frame in which the rail becomes visible.
 */
@Stable
internal class MediaRailStateStore {
    private val states = mutableMapOf<Any, LazyListState>()

    fun stateFor(key: Any): LazyListState = states.getOrPut(key, ::newMediaRailState)
}

@Composable
internal fun rememberMediaRailStateStore(): MediaRailStateStore =
    remember { MediaRailStateStore() }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberMediaRailListState(): LazyListState {
    val strategy = remember { LazyListPrefetchStrategy(NESTED_RAIL_PREFETCH_ITEMS) }
    return rememberLazyListState(prefetchStrategy = strategy)
}

@OptIn(ExperimentalFoundationApi::class)
private fun newMediaRailState(): LazyListState = LazyListState(
    prefetchStrategy = LazyListPrefetchStrategy(NESTED_RAIL_PREFETCH_ITEMS),
)

private const val NESTED_RAIL_PREFETCH_ITEMS = 3
