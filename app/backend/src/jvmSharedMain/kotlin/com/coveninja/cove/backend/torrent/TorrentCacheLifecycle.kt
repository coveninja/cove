package com.coveninja.cove.backend.torrent

import kotlinx.coroutines.CompletableDeferred

/**
 * Coordinates torrent use with irreversible cache deletion.
 *
 * A use lease may span coroutine suspension and may finish on another thread. Deletion therefore
 * cannot use a thread-owned read/write lock. Instead, the small state transition is synchronized
 * and a coroutine arriving during deletion awaits its completion without blocking a thread.
 *
 * Deletion is deliberately try-only: a retention sweep skips a torrent that is already in use.
 * Once deletion starts, however, new users wait until both the peer-session handle and its files
 * have been removed, then reopen the torrent from a clean state.
 */
class TorrentCacheLifecycle {
    private val monitor = Any()
    private val states = mutableMapOf<String, State>()

    suspend fun <T> withUse(hash: String, action: suspend () -> T): T {
        val canonical = hash.lowercase()
        acquireUse(canonical)
        return try {
            action()
        } finally {
            releaseUse(canonical)
        }
    }

    /**
     * Runs [action] exclusively against every [withUse] block for [hash].
     *
     * Returns false without running [action] when the torrent is already in use or another
     * deletion owns it. While [action] runs, new users suspend until its `finally` completes.
     */
    fun tryDelete(hash: String, action: () -> Boolean): Boolean {
        val canonical = hash.lowercase()
        val finished = CompletableDeferred<Unit>()
        synchronized(monitor) {
            val state = states.getOrPut(canonical, ::State)
            if (state.deleting || state.users > 0) return false
            state.deleting = true
            state.deletionFinished = finished
        }
        return try {
            action()
        } finally {
            synchronized(monitor) {
                val state = states[canonical]
                if (state?.deletionFinished === finished) {
                    state.deleting = false
                    state.deletionFinished = null
                    if (state.users == 0) states.remove(canonical, state)
                }
            }
            finished.complete(Unit)
        }
    }

    fun activeHashes(): Set<String> = synchronized(monitor) {
        states.filterValues { it.users > 0 }.keys.toSet()
    }

    private suspend fun acquireUse(hash: String) {
        while (true) {
            var acquired = false
            val waitFor = synchronized(monitor) {
                val state = states.getOrPut(hash, ::State)
                if (!state.deleting) {
                    state.users += 1
                    acquired = true
                    null
                } else {
                    checkNotNull(state.deletionFinished)
                }
            }
            if (acquired) return
            checkNotNull(waitFor).await()
        }
    }

    private fun releaseUse(hash: String) {
        synchronized(monitor) {
            val state = checkNotNull(states[hash]) { "torrent use lease was not registered" }
            check(state.users > 0) { "torrent use lease was released twice" }
            state.users -= 1
            if (state.users == 0 && !state.deleting) states.remove(hash, state)
        }
    }

    private class State {
        var users: Int = 0
        var deleting: Boolean = false
        var deletionFinished: CompletableDeferred<Unit>? = null
    }
}
