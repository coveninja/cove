package com.coveninja.cove.backend.storage

import com.coveninja.cove.shared.data.CacheKind
import com.coveninja.cove.shared.data.ClearResult
import com.coveninja.cove.shared.data.StorageRepository
import com.coveninja.cove.shared.data.StorageUsageState
import com.coveninja.cove.shared.data.TorrentCachePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where the retention policy is kept. Per host, because the two persist device state differently. */
interface TorrentCachePolicyStore {
    fun read(): TorrentCachePolicy
    fun write(policy: TorrentCachePolicy)
}

/**
 * The storage screen's backing repository, in front of [CacheStorageService].
 *
 * [state] is supplied by the caller rather than created here because the torrent engine reads the
 * same flow on its byte-serving path: the engine is constructed before the repository that owns
 * the policy, and sharing one holder is what lets a changed allowance take effect mid-episode
 * without the engine re-reading a properties file per piece. Seed it from the store.
 */
class LocalStorageRepository(
    private val service: CacheStorageService,
    private val store: TorrentCachePolicyStore,
    state: MutableStateFlow<TorrentCachePolicy>,
) : StorageRepository {
    override val available: Boolean = true

    private val _policy = state
    override val policy = _policy.asStateFlow()

    private val _usage = MutableStateFlow<StorageUsageState>(StorageUsageState.Loading)
    override val usage = _usage.asStateFlow()

    private val mutation = Mutex()

    /**
     * Starts the periodic sweep.
     *
     * Launched rather than awaited: the first pass walks every torrent directory, and putting
     * that in front of the window appearing would trade a disk problem for a startup one. It then
     * repeats, because the two time-based rules — age expiry and delete-after-watching — are only
     * useful if something applies them during a session rather than at the next launch.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                runCatching { enforce() }
                delay(SWEEP_INTERVAL_MILLIS)
            }
        }
    }

    override suspend fun refresh() {
        val measured = runCatching { service.usage() }
        _usage.value = measured.fold(
            onSuccess = StorageUsageState::Ready,
            onFailure = { StorageUsageState.Failed(it.message ?: "Could not read the cache directories") },
        )
    }

    override suspend fun setPolicy(policy: TorrentCachePolicy) {
        mutation.withLock {
            withContext(Dispatchers.IO) { store.write(policy) }
            _policy.value = policy
        }
        // Immediately, so lowering the limit is visibly the thing that freed the space rather
        // than something that happens up to five minutes later for no apparent reason.
        enforce()
    }

    override suspend fun clear(kind: CacheKind): ClearResult {
        val result = mutation.withLock { service.clear(kind) }
        refresh()
        return result
    }

    private suspend fun enforce() {
        val freed = mutation.withLock { service.enforce(_policy.value) }
        // Only when something moved, or the periodic sweep would re-walk every directory each
        // time it runs purely to publish a number that has not changed.
        if (freed.freedBytes > 0 || _usage.value is StorageUsageState.Loading) refresh()
    }

    private companion object {
        const val SWEEP_INTERVAL_MILLIS = 5L * 60 * 1000
    }
}
