@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.coveninja.cove.desktop.backend

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import java.io.InputStream
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// ── Test doubles ─────────────────────────────────────────────────────────────

/** A controllable fake process.  Call [exit] to unblock [awaitExit]. */
private class FakeProcess : ManagedProcess {
    private val exitSource = CompletableDeferred<Int>()

    override val isAlive: Boolean get() = !exitSource.isCompleted
    override fun inputStream(): InputStream = InputStream.nullInputStream()
    override fun destroy() { exitSource.complete(0) }
    override fun destroyForcibly() { exitSource.complete(-9) }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
    override suspend fun awaitExit(): Int = exitSource.await()

    fun exit(code: Int) { exitSource.complete(code) }
}

/** Queue-based factory so tests can hand out specific [FakeProcess] instances. */
private class FakeProcessFactory(vararg procs: FakeProcess) : BackendProcessFactory {
    private val queue = ArrayDeque(procs.toList())
    var spawnCount = 0

    override fun spawn(executable: java.nio.file.Path, port: Int, parentPid: Long): ManagedProcess {
        spawnCount++
        return queue.removeFirst()
    }
}

/** Probe whose outcome is fully controlled by the test. */
private class ControlledProbe : ReadinessProbe {
    private val source = CompletableDeferred<Boolean>()
    fun succeed() = source.complete(true)
    fun timeout() = source.complete(false)
    override suspend fun awaitReady(): Boolean = source.await()
}

/** Queue-based probe factory. */
private class ProbeQueue(vararg probes: ControlledProbe) {
    private val queue = ArrayDeque(probes.toList())
    fun factory(): (Int, Long) -> ReadinessProbe = { _, _ -> queue.removeFirst() }
}

private val testExe = Paths.get("/fake/cove")
private fun testConfig(maxRestarts: Int = 3) = SupervisorConfig(
    executable = testExe,
    port = 6969,
    startupTimeoutMillis = 20_000,
    maxRestarts = maxRestarts,
    restartWindowMillis = 60_000,
)

// ── Tests ─────────────────────────────────────────────────────────────────────

class BackendSupervisorTest {

    /**
     * Exit 42 BEFORE the readiness probe resolves must emit [BackendState.RestartRequested]
     * and must NOT count against the crash budget.
     *
     * The "not counted" property is proved by following the exit-42 restart
     * with the maximum number of allowed real crashes (maxRestarts = 1) and
     * verifying all of them are permitted — if exit-42 had consumed a slot,
     * the first real crash would immediately give up.
     */
    @Test
    fun `exit 42 before ready emits RestartRequested and does not consume crash budget`() =
        runTest {
            // Three rounds:
            // 1. exit-42 before probe → RestartRequested
            // 2. probe succeeds → Ready, then crash → Backoff (budget 1/1)
            // 3. probe succeeds → Ready, then crash → Failed  (budget exhausted)
            val proc1 = FakeProcess()
            val proc2 = FakeProcess()
            val proc3 = FakeProcess()
            val probe1 = ControlledProbe()  // never resolves before proc1 exits
            val probe2 = ControlledProbe()
            val probe3 = ControlledProbe()

            val factory = FakeProcessFactory(proc1, proc2, proc3)
            val probes = ProbeQueue(probe1, probe2, probe3)
            val policy = RestartPolicy(maxRestarts = 1, restartWindowMillis = 60_000)
            val supervisorScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            val supervisor = BackendSupervisor(
                config = testConfig(maxRestarts = 1),
                processFactory = factory,
                probeFactory = probes.factory(),
                portChecker = { false },
                restartPolicy = policy,
                scope = supervisorScope,
            )

            // Collect all states emitted over the test's lifetime.
            val states = mutableListOf<BackendState>()
            val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
                supervisor.state.collect { states.add(it) }
            }

            supervisor.start()
            advanceUntilIdle()

            // Round 1: exit-42 before probe → RestartRequested then Starting
            proc1.exit(42)
            advanceUntilIdle()

            // RestartRequested must appear in the collected sequence; the current
            // value may already be Starting (the loop re-entered immediately).
            assertContains(
                states, BackendState.RestartRequested,
                "exit 42 before ready should emit RestartRequested in the state sequence"
            )

            // Round 2: probe succeeds, then a real crash
            probe2.succeed()
            advanceUntilIdle()
            proc2.exit(1)
            advanceUntilIdle()

            // Round 3: probe succeeds, second real crash → budget exhausted
            probe3.succeed()
            advanceUntilIdle()
            proc3.exit(1)
            advanceUntilIdle()

            val final = supervisor.state.value
            assertIs<BackendState.Failed>(final)
            assertTrue(
                final.message.contains("crashed too many times"),
                "expected budget-exhaustion message, got: ${final.message}"
            )
            // Three spawns total: exit-42 round + 2 crash rounds.
            // If exit-42 had consumed budget, only 2 spawns would occur.
            assertEquals(3, factory.spawnCount, "exit-42 restart must spawn a new process")

            collectJob.cancel()
            supervisorScope.cancel()
        }

    /**
     * Exit 42 AFTER the backend was already ready must emit
     * [BackendState.RestartRequested] and must not consume crash budget.
     *
     * Proof: with maxRestarts=1, exit-42 + two real crashes produces three
     * spawns and then Failed.  If exit-42 had consumed a slot, the second
     * real crash would never happen — only two spawns would occur before Failed.
     */
    @Test
    fun `exit 42 after ready emits RestartRequested and does not consume crash budget`() =
        runTest {
            // Three rounds:
            // 1. probe → Ready, exit-42 → RestartRequested (no budget)
            // 2. probe → Ready, crash   → Backoff (budget 1/1)
            // 3. probe → Ready, crash   → Failed  (budget exhausted, 2/1)
            val proc1 = FakeProcess()
            val proc2 = FakeProcess()
            val proc3 = FakeProcess()
            val probe1 = ControlledProbe()
            val probe2 = ControlledProbe()
            val probe3 = ControlledProbe()

            val factory = FakeProcessFactory(proc1, proc2, proc3)
            val probes = ProbeQueue(probe1, probe2, probe3)
            val policy = RestartPolicy(maxRestarts = 1, restartWindowMillis = 60_000)
            val supervisorScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            val supervisor = BackendSupervisor(
                config = testConfig(maxRestarts = 1),
                processFactory = factory,
                probeFactory = probes.factory(),
                portChecker = { false },
                restartPolicy = policy,
                scope = supervisorScope,
            )

            val states = mutableListOf<BackendState>()
            val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
                supervisor.state.collect { states.add(it) }
            }

            supervisor.start()

            // Round 1: probe succeeds → Ready, then exit-42
            advanceUntilIdle()
            probe1.succeed()
            advanceUntilIdle()
            assertContains(states, BackendState.Ready, "should have reached Ready before exit-42")

            proc1.exit(42)
            advanceUntilIdle()

            assertContains(
                states, BackendState.RestartRequested,
                "exit 42 after ready should emit RestartRequested"
            )

            // Round 2: probe succeeds, real crash → budget 1/1 consumed
            probe2.succeed()
            advanceUntilIdle()
            proc2.exit(1)
            advanceUntilIdle()

            // Round 3: probe succeeds, second real crash → budget exhausted
            probe3.succeed()
            advanceUntilIdle()
            proc3.exit(1)
            advanceUntilIdle()

            val final = supervisor.state.value
            assertIs<BackendState.Failed>(final)
            assertTrue(
                final.message.contains("crashed too many times"),
                "budget exhaustion expected after two real crashes, got: ${final.message}"
            )
            // Three spawns total proves exit-42 did NOT consume a crash slot:
            // if it had, only two spawns would occur before budget exhaustion.
            assertEquals(
                3, factory.spawnCount,
                "exit-42 + 2 real crashes = 3 spawns; exit-42 consumed no crash slot"
            )

            collectJob.cancel()
            supervisorScope.cancel()
        }

    /**
     * Three crashes inside the window must all produce restarts; the fourth
     * must produce [BackendState.Failed].
     */
    @Test
    fun `three crashes restart then fourth exhausts budget and emits Failed`() = runTest {
        val procs = (1..4).map { FakeProcess() }
        val probes = (1..4).map { ControlledProbe() }

        val factory = FakeProcessFactory(*procs.toTypedArray())
        val probeQueue = ProbeQueue(*probes.toTypedArray())
        // Injectable clock so the window never expires during the test.
        var now = 0L
        val policy = RestartPolicy(maxRestarts = 3, restartWindowMillis = 60_000, clock = { now })
        val supervisorScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val supervisor = BackendSupervisor(
            config = testConfig(maxRestarts = 3),
            processFactory = factory,
            probeFactory = probeQueue.factory(),
            portChecker = { false },
            restartPolicy = policy,
            scope = supervisorScope,
        )

        val states = mutableListOf<BackendState>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            supervisor.state.collect { states.add(it) }
        }

        supervisor.start()

        // Crash 1–3: each allowed, supervisor restarts
        for (i in 0..2) {
            advanceUntilIdle()
            probes[i].succeed()
            advanceUntilIdle()
            procs[i].exit(1)
            now += 1_000L
            advanceUntilIdle()
            // After each of the first 3 crashes, state must NOT be Failed
            assertFalse(
                supervisor.state.value is BackendState.Failed,
                "crash ${i + 1} of 3 should allow a restart, not terminate"
            )
        }

        // 4th spawn — probe succeeds, then crash exhausts budget
        advanceUntilIdle()
        probes[3].succeed()
        advanceUntilIdle()
        assertContains(states, BackendState.Ready, "4th spawn should also reach Ready before crashing")
        procs[3].exit(1)
        now += 1_000L
        advanceUntilIdle()

        val final = supervisor.state.value
        assertIs<BackendState.Failed>(final, "4th crash should exhaust budget")
        assertTrue(
            final.message.contains("crashed too many times"),
            "wrong failure message: ${final.message}"
        )
        assertEquals(4, factory.spawnCount, "exactly 4 processes should have been spawned")

        collectJob.cancel()
        supervisorScope.cancel()
    }

    /**
     * After the rolling window elapses, old crash timestamps age out and the
     * budget is freed, so the supervisor can restart again.
     */
    @Test
    fun `crash after window ages out frees budget and supervisor restarts again`() = runTest {
        // 3 crashes fill the budget.  Advance the clock past the first crash's
        // window boundary.  A 4th crash should be allowed (5th process spawned).
        val procs = (1..5).map { FakeProcess() }
        val probes = (1..5).map { ControlledProbe() }

        val factory = FakeProcessFactory(*procs.toTypedArray())
        val probeQueue = ProbeQueue(*probes.toTypedArray())
        var now = 0L
        val policy = RestartPolicy(maxRestarts = 3, restartWindowMillis = 60_000, clock = { now })
        val supervisorScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val supervisor = BackendSupervisor(
            config = testConfig(maxRestarts = 3),
            processFactory = factory,
            probeFactory = probeQueue.factory(),
            portChecker = { false },
            restartPolicy = policy,
            scope = supervisorScope,
        )
        supervisor.start()

        // Fill the window with 3 crashes (t=0, 1s, 2s)
        for (i in 0..2) {
            advanceUntilIdle()
            probes[i].succeed()
            advanceUntilIdle()
            procs[i].exit(1)
            now += 1_000L
            advanceUntilIdle()
        }

        // Advance so the first crash (t=0) ages out: now - 0 > 60_000
        now = 62_000L

        // 4th crash: the first timestamp has aged out, freeing a slot
        advanceUntilIdle()
        probes[3].succeed()
        advanceUntilIdle()
        procs[3].exit(1)
        now += 1_000L
        advanceUntilIdle()

        // After the 4th crash the supervisor should restart (5th spawn), not fail.
        assertFalse(
            supervisor.state.value == BackendState.Failed(
                "backend crashed too many times (last exit code 1)"
            ),
            "4th crash after window roll should be allowed; state is: ${supervisor.state.value}"
        )
        // 4 crashes happened → 4 restarts → 5 processes spawned in total
        assertEquals(5, factory.spawnCount, "4 crashes with window roll should produce 5 spawns")

        supervisorScope.cancel()
    }

    /**
     * If the port is already accepting connections the supervisor must emit
     * [BackendState.Failed] immediately and must never call the process factory.
     */
    @Test
    fun `occupied port emits Failed without spawning a process`() = runTest {
        val factory = FakeProcessFactory()  // empty — any call throws NoSuchElement
        val supervisorScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val supervisor = BackendSupervisor(
            config = testConfig(),
            processFactory = factory,
            probeFactory = { _, _ -> ReadinessProbe { true } },
            portChecker = { true },  // always occupied
            scope = supervisorScope,
        )
        supervisor.start()
        advanceUntilIdle()

        val state = supervisor.state.value
        assertIs<BackendState.Failed>(state, "occupied port should produce Failed")
        assertTrue(
            state.message.contains("already in use"),
            "failure message should explain port conflict, got: ${state.message}"
        )
        assertEquals(0, factory.spawnCount, "no process should be spawned when port is occupied")

        supervisorScope.cancel()
    }

    /**
     * A probe timeout (backend never answers) must emit [BackendState.Failed]
     * with a message that identifies the timeout as the cause.
     */
    @Test
    fun `probe timeout emits Failed with timeout message`() = runTest {
        val proc = FakeProcess()
        val probe = ControlledProbe()

        val supervisorScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val supervisor = BackendSupervisor(
            config = testConfig(),
            processFactory = FakeProcessFactory(proc),
            probeFactory = { _, _ -> probe },
            portChecker = { false },
            scope = supervisorScope,
        )
        supervisor.start()
        advanceUntilIdle()

        probe.timeout()  // signal that the deadline expired without a response
        advanceUntilIdle()

        val state = supervisor.state.value
        assertIs<BackendState.Failed>(state)
        assertTrue(
            state.message.contains("did not respond"),
            "timeout message expected, got: ${state.message}"
        )

        supervisorScope.cancel()
    }

    /**
     * A non-42 exit before the probe resolves must emit [BackendState.Failed]
     * with the exit code in the message, not a [BackendState.RestartRequested].
     */
    @Test
    fun `non-42 exit before ready emits Failed not RestartRequested`() = runTest {
        val proc = FakeProcess()
        val probe = ControlledProbe()   // never resolves

        val supervisorScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val supervisor = BackendSupervisor(
            config = testConfig(),
            processFactory = FakeProcessFactory(proc),
            probeFactory = { _, _ -> probe },
            portChecker = { false },
            scope = supervisorScope,
        )
        supervisor.start()
        advanceUntilIdle()

        proc.exit(1)
        advanceUntilIdle()

        val state = supervisor.state.value
        assertIs<BackendState.Failed>(state, "non-42 exit before ready should produce Failed")
        assertTrue(
            state.message.contains("exit code 1"),
            "exit code should appear in message, got: ${state.message}"
        )

        supervisorScope.cancel()
    }
}
