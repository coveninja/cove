package com.coveninja.cove.backend.platform

import android.content.Context
import com.coveninja.cove.backend.storage.TorrentCachePolicyStore
import com.coveninja.cove.shared.data.TorrentCachePolicy

/**
 * The retention policy for this device, in Android's own preference store.
 *
 * Defaults are far tighter than the desktop's. Torrent downloads live under `filesDir`, which
 * Android counts as app data rather than cache — it is never reclaimed under storage pressure, so
 * a phone that filled up would stay full until someone cleared the app's data by hand.
 */
class AndroidTorrentCachePolicyStore(context: Context) : TorrentCachePolicyStore {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(): TorrentCachePolicy = TorrentCachePolicy(
        limitBytes = preferences.getLong(LIMIT_BYTES, DEFAULT_LIMIT_BYTES),
        downloadAheadBytes = preferences.getLong(AHEAD_BYTES, DEFAULT_AHEAD_BYTES),
        deleteAfterWatching = preferences.getBoolean(DELETE_AFTER_WATCHING, false),
        maxAgeDays = preferences.getInt(MAX_AGE_DAYS, DEFAULT_MAX_AGE_DAYS),
    )

    override fun write(policy: TorrentCachePolicy) {
        preferences.edit()
            .putLong(LIMIT_BYTES, policy.limitBytes)
            .putLong(AHEAD_BYTES, policy.downloadAheadBytes)
            .putBoolean(DELETE_AFTER_WATCHING, policy.deleteAfterWatching)
            .putInt(MAX_AGE_DAYS, policy.maxAgeDays)
            .apply()
    }

    private companion object {
        const val PREFERENCES = "cove-storage"
        const val LIMIT_BYTES = "torrent-cache-limit-bytes"
        const val AHEAD_BYTES = "torrent-download-ahead-bytes"
        const val DELETE_AFTER_WATCHING = "torrent-delete-after-watching"
        const val MAX_AGE_DAYS = "torrent-cache-max-age-days"

        const val DEFAULT_LIMIT_BYTES = 4L * 1024 * 1024 * 1024
        const val DEFAULT_AHEAD_BYTES = 512L * 1024 * 1024
        const val DEFAULT_MAX_AGE_DAYS = 14
    }
}
