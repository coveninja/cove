package com.coveninja.cove.backend.storage

import com.coveninja.cove.backend.platform.DeviceSettingsService
import com.coveninja.cove.shared.data.CacheKind
import com.coveninja.cove.shared.data.DevicePerformanceState
import com.coveninja.cove.shared.data.StorageUsageState
import com.coveninja.cove.shared.data.TorrentCachePolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/**
 * The pieces exactly as `LocalBackendRuntime` assembles them: the desktop device-settings file as
 * the policy store, the cache service over the real data layout, and the repository in front.
 *
 * Worth testing together rather than apart, because the seams are where this can go wrong — the
 * policy has to survive a restart, it has to share a file with the performance state without
 * either overwriting the other, and changing it has to act rather than wait for the next sweep.
 */
class LocalStorageRepositoryTest {
    private val hash = "c".repeat(40)

    private fun dataDirectory(): Path {
        val root = Files.createTempDirectory("cove-storage-runtime")
        root.resolve("torrents/$hash").createDirectories()
        root.resolve("torrents/$hash/episode.mkv").writeBytes(ByteArray(6_000))
        return root
    }

    private fun repository(
        root: Path,
        settings: DeviceSettingsService = DeviceSettingsService(root),
    ): Pair<LocalStorageRepository, MutableStateFlow<TorrentCachePolicy>> {
        val policy = MutableStateFlow(settings.read())
        val service = CacheStorageService(
            directories = CacheDirectories(torrents = root.resolve("torrents")),
            journal = TorrentCacheJournal(root.resolve("torrents")),
        )
        return LocalStorageRepository(service, settings, policy) to policy
    }

    @Test
    fun `the policy survives a restart`() = runTest {
        val root = dataDirectory()
        val (repository, _) = repository(root)
        val chosen = TorrentCachePolicy(
            limitBytes = 50L * 1024 * 1024 * 1024,
            downloadAheadBytes = 256L * 1024 * 1024,
            deleteAfterWatching = true,
            maxAgeDays = 7,
        )

        repository.setPolicy(chosen)

        // Read through a fresh service, the way the next launch does. Fails on any field that is
        // written but not read back, which would silently revert to the default on restart — the
        // kind of thing that looks like the setting never saved.
        assertEquals(chosen, DeviceSettingsService(root).read())
    }

    @Test
    fun `writing the policy leaves the performance state alone and the reverse`() = runTest {
        val root = dataDirectory()
        val settings = DeviceSettingsService(root)
        settings.writePerformanceState(DevicePerformanceState(lowPerformanceMode = true))
        val (repository, _) = repository(root, settings)

        repository.setPolicy(TorrentCachePolicy(limitBytes = 5L * 1024 * 1024 * 1024))
        // Read-then-copy, as LocalDeviceRepository does: writePerformanceState takes a whole
        // state, so a caller that built a fresh one would be clearing the mode itself.
        settings.writePerformanceState(settings.readPerformanceState().copy(recommendationDismissed = true))

        // Both live in device-settings.properties, and each writer used to rebuild the whole file
        // from its own keys. Fails if either write drops the other's, which reads as a setting
        // resetting itself whenever an unrelated switch is touched.
        val reloaded = DeviceSettingsService(root)
        assertEquals(5L * 1024 * 1024 * 1024, reloaded.read().limitBytes)
        // Both directions, and both keys. Asserting only the second write's own field would miss
        // the first write being wiped by the one that followed it.
        assertTrue(reloaded.readPerformanceState().recommendationDismissed)
        assertTrue(reloaded.readPerformanceState().lowPerformanceMode)
    }

    @Test
    fun `lowering the limit acts immediately rather than at the next sweep`() = runTest {
        val root = dataDirectory()
        val (repository, _) = repository(root)

        repository.setPolicy(TorrentCachePolicy(limitBytes = 1_000))

        // Fails if setPolicy only stores the value: the viewer would drag the limit down, watch
        // the number on screen not move, and reasonably conclude the control does nothing.
        assertFalse(Files.exists(root.resolve("torrents/$hash")))
        val usage = repository.usage.value
        assertTrue(usage is StorageUsageState.Ready)
        assertEquals(0, (usage as StorageUsageState.Ready).usage.totalBytes)
    }

    @Test
    fun `the shared policy flow is what the engine will read`() = runTest {
        val root = dataDirectory()
        val (repository, flow) = repository(root)

        repository.setPolicy(TorrentCachePolicy(downloadAheadBytes = 42))

        // The engine holds this flow and reads it per piece. Fails if the repository publishes to
        // a flow of its own, in which case a changed download-ahead allowance would never reach
        // the piece picker and the setting would do nothing at all until the app restarted.
        assertEquals(42, flow.value.downloadAheadBytes)
        assertEquals(42, repository.policy.value.downloadAheadBytes)
    }

    @Test
    fun `clearing reports what it freed and republishes the usage`() = runTest {
        val root = dataDirectory()
        val (repository, _) = repository(root)
        repository.refresh()

        val result = repository.clear(CacheKind.TorrentDownloads)

        assertEquals(6_000, result.freedBytes)
        assertEquals(0, result.keptInUse)
        // Fails without the refresh after a clear: the screen would go on showing the space as
        // occupied until something else happened to reload it.
        val usage = repository.usage.value as StorageUsageState.Ready
        assertEquals(0, usage.usage.totalBytes)
    }
}
