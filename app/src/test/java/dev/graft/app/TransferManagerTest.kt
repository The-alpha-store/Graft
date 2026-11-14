package dev.graft.app

import dev.graft.app.git.GitResult
import dev.graft.app.transfer.TransferKind
import dev.graft.app.transfer.TransferManager
import dev.graft.app.transfer.TransferState
import dev.graft.app.transfer.awaitResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The scheduling rules that make transfers safe: how many run at once, that one repo is never touched
 * by two at the same time, and that cancel/failure behave. Runs on the JVM with the Android side
 * effects (service, notifications) stubbed out.
 */
class TransferManagerTest {

    private fun newManager() = TransferManager(onStartService = {}, onResultNotification = {})

    private suspend fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for condition")
            delay(5)
        }
    }

    private fun TransferManager.count(state: TransferState) = tasks.value.count { it.state == state }

    @Test
    fun `a successful transfer ends succeeded with its message`() = runBlocking {
        val m = newManager()
        val result = m.enqueue(TransferKind.PUSH, repoId = 1, title = "repo") { GitResult.Success("Pushed to origin") }.awaitResult()

        assertTrue(result is GitResult.Success<*>)
        val task = m.tasks.value.single()
        assertEquals(TransferState.SUCCEEDED, task.state)
        assertEquals("Pushed to origin", task.message)
        assertEquals(1f, task.progress, 0f)
    }

    @Test
    fun `at most two transfers run at once and the rest wait`() = runBlocking {
        val m = newManager()
        val gate = CompletableDeferred<Unit>()
        repeat(3) { i ->
            m.enqueue(TransferKind.FETCH, repoId = (i + 1).toLong(), title = "r$i") {
                gate.await()
                GitResult.Success("ok")
            }
        }
        waitUntil { m.count(TransferState.RUNNING) == 2 }
        delay(100) // give a wrongly-eager third one the chance to start
        assertEquals(2, m.count(TransferState.RUNNING))
        assertEquals(1, m.count(TransferState.QUEUED))

        gate.complete(Unit)
        waitUntil { m.count(TransferState.SUCCEEDED) == 3 }
    }

    @Test
    fun `two transfers on the same repo never overlap`() = runBlocking {
        val m = newManager()
        val gate = CompletableDeferred<Unit>()
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        repeat(2) {
            m.enqueue(TransferKind.PULL, repoId = 7, title = "same") {
                val now = concurrent.incrementAndGet()
                maxConcurrent.updateAndGet { old -> maxOf(old, now) }
                gate.await()
                concurrent.decrementAndGet()
                GitResult.Success("ok")
            }
        }
        waitUntil { m.count(TransferState.RUNNING) == 1 }
        delay(100)
        // A free global slot exists, but the repo is busy - so the second must still be waiting.
        assertEquals(1, m.count(TransferState.RUNNING))
        assertEquals(1, m.count(TransferState.QUEUED))

        gate.complete(Unit)
        waitUntil { m.count(TransferState.SUCCEEDED) == 2 }
        assertEquals(1, maxConcurrent.get())
    }

    @Test
    fun `failure is reported and runs cleanup`() = runBlocking {
        val m = newManager()
        val cleaned = AtomicBoolean(false)
        val result = m.enqueue(TransferKind.CLONE, repoId = 0, title = "x", cleanup = { cleaned.set(true) }) {
            GitResult.Error("authentication failed")
        }.awaitResult()

        assertTrue(result is GitResult.Error)
        val task = m.tasks.value.single()
        assertEquals(TransferState.FAILED, task.state)
        assertEquals("authentication failed", task.message)
        assertTrue(cleaned.get())
    }

    @Test
    fun `an exception inside the work becomes a failure, not a crash`() = runBlocking {
        val m = newManager()
        m.enqueue(TransferKind.PUSH, repoId = 1, title = "x") { throw IllegalStateException("kaboom") }.awaitResult()
        val task = m.tasks.value.single()
        assertEquals(TransferState.FAILED, task.state)
        assertEquals("kaboom", task.message)
    }

    @Test
    fun `cancelling a running transfer flips the monitor, cleans up and ends cancelled`() = runBlocking {
        val m = newManager()
        val started = CompletableDeferred<Unit>()
        val cleaned = AtomicBoolean(false)
        val handle = m.enqueue(TransferKind.CLONE, repoId = 0, title = "big", cleanup = { cleaned.set(true) }) { reporter ->
            started.complete(Unit)
            while (!reporter.monitor.isCancelled) delay(5)       // what JGit does between objects
            GitResult.Error("Cancelled by JGit")
        }
        started.await()
        m.cancel(m.tasks.value.single().id)
        handle.awaitResult()

        val task = m.tasks.value.single()
        assertEquals(TransferState.CANCELLED, task.state)
        assertEquals("Cancelled", task.message)
        assertTrue("partial clone should be cleaned up", cleaned.get())
    }

    @Test
    fun `cancelling a queued transfer means its work never runs`() = runBlocking {
        val m = newManager()
        val gate = CompletableDeferred<Unit>()
        repeat(2) { i ->
            m.enqueue(TransferKind.FETCH, repoId = (i + 1).toLong(), title = "busy$i") { gate.await(); GitResult.Success("ok") }
        }
        waitUntil { m.count(TransferState.RUNNING) == 2 }

        val ran = AtomicBoolean(false)
        val queued = m.enqueue(TransferKind.FETCH, repoId = 99, title = "waiting") { ran.set(true); GitResult.Success("ok") }
        waitUntil { m.count(TransferState.QUEUED) == 1 }
        m.cancel(m.tasks.value.first { it.state == TransferState.QUEUED }.id)

        val result = queued.awaitResult()
        assertTrue(result is GitResult.Error)
        waitUntil { m.count(TransferState.CANCELLED) == 1 }
        gate.complete(Unit)
        waitUntil { m.count(TransferState.SUCCEEDED) == 2 }
        assertFalse(ran.get())
    }

    @Test
    fun `retry re-runs a failed transfer as a fresh task`() = runBlocking {
        val m = newManager()
        val attempts = AtomicInteger(0)
        m.enqueue(TransferKind.PUSH, repoId = 3, title = "flaky") {
            if (attempts.incrementAndGet() == 1) GitResult.Error("network down") else GitResult.Success("Pushed")
        }.awaitResult()
        val failed = m.tasks.value.single()
        assertEquals(TransferState.FAILED, failed.state)

        m.retry(failed.id)
        waitUntil { m.tasks.value.singleOrNull()?.state == TransferState.SUCCEEDED }
        assertEquals(2, attempts.get())
        assertEquals(1, m.tasks.value.size)          // the failed row was replaced, not duplicated
    }

    @Test
    fun `progress reported through the monitor reaches the task`() = runBlocking {
        val m = newManager()
        val gate = CompletableDeferred<Unit>()
        m.enqueue(TransferKind.CLONE, repoId = 0, title = "p") { reporter ->
            reporter.monitor.beginTask("Receiving objects", 100)
            delay(150)                                   // past the publish throttle
            reporter.monitor.update(50)
            gate.await()
            GitResult.Success("done")
        }
        waitUntil { (m.tasks.value.firstOrNull()?.progress ?: 0f) > 0.4f }
        val task = m.tasks.value.single()
        assertEquals("Receiving objects", task.phase)
        assertEquals(0.475f, task.progress, 0.01f)
        assertEquals(50, task.unitsDone)
        assertFalse(task.indeterminate)
        gate.complete(Unit)
        waitUntil { m.count(TransferState.SUCCEEDED) == 1 }
    }

    @Test
    fun `with no network a transfer fails immediately with a clear message and never starts`() = runBlocking {
        val m = TransferManager(onStartService = {}, onResultNotification = {}, isOnline = { false })
        val ran = AtomicBoolean(false)
        val result = m.enqueue(TransferKind.PUSH, repoId = 1, title = "x") { ran.set(true); GitResult.Success("ok") }.awaitResult()

        assertTrue(result is GitResult.Error)
        val task = m.tasks.value.single()
        assertEquals(TransferState.FAILED, task.state)
        assertEquals(dev.graft.app.git.GitErrors.OFFLINE_MESSAGE, task.message)
        assertFalse(ran.get())
    }

    @Test
    fun `an exception from the work is humanised`() = runBlocking {
        val m = newManager()
        m.enqueue(TransferKind.FETCH, repoId = 1, title = "x") { throw java.net.SocketTimeoutException("read timed out") }.awaitResult()
        assertEquals(dev.graft.app.git.GitErrors.TIMEOUT_MESSAGE, m.tasks.value.single().message)
    }

    @Test
    fun `clearFinished keeps active transfers`() = runBlocking {
        val m = newManager()
        m.enqueue(TransferKind.PUSH, repoId = 1, title = "done") { GitResult.Success("ok") }.awaitResult()
        val gate = CompletableDeferred<Unit>()
        m.enqueue(TransferKind.PUSH, repoId = 2, title = "live") { gate.await(); GitResult.Success("ok") }
        waitUntil { m.count(TransferState.RUNNING) == 1 }

        m.clearFinished()
        assertEquals(listOf("live"), m.tasks.value.map { it.title })
        gate.complete(Unit)
        waitUntil { m.count(TransferState.SUCCEEDED) == 1 }
    }
}
