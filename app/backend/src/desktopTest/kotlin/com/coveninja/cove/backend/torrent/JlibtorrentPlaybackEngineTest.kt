package com.coveninja.cove.backend.torrent

import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Guards the real session-start calls, not just the platform predicate that selects them. */
class JlibtorrentPlaybackEngineTest {
    @Test
    fun `POSIX disk IO is selected on macOS and Linux`() {
        assertTrue(needsPosixTorrentDiskIo("Mac OS X"))
        assertTrue(needsPosixTorrentDiskIo("macOS"))
        assertTrue(needsPosixTorrentDiskIo("Linux"))
        assertFalse(needsPosixTorrentDiskIo("Windows 11"))
    }

    @Test
    fun `macOS and Linux sessions start with POSIX disk IO parameters`() {
        listOf("Mac OS X", "Linux").forEach { osName ->
            val manager = RecordingSessionManager()
            val params = RecordingSessionParams()

            startTorrentSession(manager, osName) { params }

            assertEquals(0, manager.defaultStartCalls, osName)
            assertEquals(1, manager.paramsStartCalls, osName)
            assertSame(params, manager.startedWith, osName)
            assertEquals(1, params.posixSelections, osName)
        }
    }

    @Test
    fun `Windows sessions retain the default disk IO backend`() {
        val manager = RecordingSessionManager()
        var paramsCreated = false

        startTorrentSession(manager, "Windows 11") {
            paramsCreated = true
            RecordingSessionParams()
        }

        assertEquals(1, manager.defaultStartCalls)
        assertEquals(0, manager.paramsStartCalls)
        assertFalse(paramsCreated)
    }

    private class RecordingSessionManager : SessionManager(false) {
        var defaultStartCalls = 0
        var paramsStartCalls = 0
        var startedWith: SessionParams? = null

        override fun start() {
            defaultStartCalls += 1
        }

        override fun start(params: SessionParams) {
            paramsStartCalls += 1
            startedWith = params
        }
    }

    private class RecordingSessionParams : SessionParams() {
        var posixSelections = 0

        override fun setPosixDiskIO() {
            posixSelections += 1
        }
    }
}
