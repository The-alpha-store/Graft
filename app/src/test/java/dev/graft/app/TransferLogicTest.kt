package dev.graft.app

import dev.graft.app.transfer.PhaseProgress
import dev.graft.app.transfer.RateTracker
import dev.graft.app.transfer.TransferKind
import dev.graft.app.transfer.TransferState
import dev.graft.app.transfer.TransferTask
import dev.graft.app.transfer.formatBytes
import dev.graft.app.transfer.formatEta
import dev.graft.app.transfer.formatSpeed
import dev.graft.app.transfer.statusLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferLogicTest {

    // ------------------------------------------------------------------ PhaseProgress

    @Test
    fun `a clone's phases form one monotonic bar`() {
        val p = PhaseProgress()
        var last = 0f
        fun check() { assertTrue("went backwards: $last -> ${p.overall}", p.overall >= last); last = p.overall }

        p.begin("remote: Counting objects", 0, 0); check()
        p.begin("Receiving objects", 100, 0)
        p.advance(50); check()
        assertEquals(0.475f, p.overall, 0.001f)          // 0.20 + 0.55 * 0.5
        p.advance(50); p.end(); check()
        assertEquals(0.75f, p.overall, 0.001f)
        p.begin("Resolving deltas", 10, 0)
        p.advance(10); check()
        assertEquals(0.90f, p.overall, 0.001f)
        p.begin("Checking out files", 4, 0)
        p.advance(2); check()
        assertEquals(0.95f, p.overall, 0.001f)
        p.advance(2); p.end(); check()
        assertEquals(1.0f, p.overall, 0.001f)
    }

    @Test
    fun `a later phase never pulls the bar backwards`() {
        val p = PhaseProgress()
        p.begin("Receiving objects", 10, 0); p.advance(10)
        val high = p.overall
        p.begin("Counting objects", 10, 0); p.advance(1)   // out-of-order phase
        assertEquals(high, p.overall, 0f)
    }

    @Test
    fun `unrecognised phases leave the bar where it is`() {
        val p = PhaseProgress()
        p.begin("Doing something novel", 100, 0)
        p.advance(50)
        assertEquals(0f, p.overall, 0f)
        assertTrue(p.indeterminate)
    }

    @Test
    fun `phase bands cover the documented titles`() {
        assertEquals(0f to 0.08f, PhaseProgress.bandFor("remote: Enumerating objects"))
        assertEquals(0.08f to 0.20f, PhaseProgress.bandFor("Compressing objects"))
        assertEquals(0.20f to 0.75f, PhaseProgress.bandFor("Writing objects"))
        assertEquals(0.90f to 1.00f, PhaseProgress.bandFor("Updating references"))
        assertNull(PhaseProgress.bandFor("???"))
    }

    @Test
    fun `phase eta comes from the phase's own pace`() {
        val p = PhaseProgress()
        p.begin("Receiving objects", 100, nowNanos = 0)
        assertEquals(-1L, p.phaseEtaSeconds(10_000_000_000L))   // nothing done yet
        p.advance(20)
        assertEquals(40L, p.phaseEtaSeconds(10_000_000_000L))   // 10s for 20% -> 40s for the rest
        assertEquals(-1L, p.phaseEtaSeconds(500_000_000L))      // too early to trust
    }

    // ------------------------------------------------------------------ RateTracker

    @Test
    fun `rate is smoothed and ignores too-frequent samples`() {
        val r = RateTracker()
        assertEquals(0L, r.sample(0, 0))
        assertEquals(1_000_000.0, r.sample(1_000_000, 1_000_000_000L).toDouble(), 1.0)
        // 3 MB more in the next second: 0.3 * 3,000,000 + 0.7 * 1,000,000
        assertEquals(1_600_000.0, r.sample(4_000_000, 2_000_000_000L).toDouble(), 1.0)
        val before = r.bytesPerSec
        assertEquals(before, r.sample(5_000_000, 2_050_000_000L))      // only 50 ms later: ignored
    }

    @Test
    fun `a shrinking byte count never produces a negative rate`() {
        val r = RateTracker()
        r.sample(1000, 0)
        assertTrue(r.sample(500, 1_000_000_000L) >= 0)
    }

    // ------------------------------------------------------------------ formatting

    @Test
    fun `byte formatting`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("10 KB", formatBytes(10 * 1024))
        assertEquals("5.0 MB", formatBytes(5L * 1024 * 1024))
        assertEquals("3.00 GB", formatBytes(3L * 1024 * 1024 * 1024))
        // Just under a megabyte must not render as the odd-looking "1024 KB".
        assertNotEquals("1024 KB", formatBytes(1024L * 1024 - 1))
        assertEquals("2.0 MB/s", formatSpeed(2L * 1024 * 1024))
    }

    @Test
    fun `eta formatting`() {
        assertEquals("", formatEta(-1))
        assertEquals("8s left", formatEta(8))
        assertEquals("1m 15s left", formatEta(75))
        assertEquals("1h 3m left", formatEta(3_780))
    }

    @Test
    fun `status line reads naturally for each state`() {
        val base = TransferTask(id = 1, kind = TransferKind.CLONE, repoId = 0, title = "graft")
        assertEquals("Waiting…", base.statusLine())
        assertEquals("Starting…", base.copy(state = TransferState.RUNNING).statusLine())

        val running = base.copy(
            state = TransferState.RUNNING, phase = "remote: Counting objects",
            bytes = 12L * 1024 * 1024, bytesPerSec = 2L * 1024 * 1024, etaSeconds = 8,
        )
        assertEquals("Counting objects · 12 MB · 2.0 MB/s · 8s left", running.statusLine())

        // No byte rate (a push): fall back to object counts.
        val push = base.copy(state = TransferState.RUNNING, phase = "Writing objects", unitsDone = 40, unitsTotal = 100)
        assertEquals("Writing objects · 40 / 100", push.statusLine())

        assertEquals("Cloned graft", base.copy(state = TransferState.SUCCEEDED, message = "Cloned graft").statusLine())
        assertEquals("auth failed", base.copy(state = TransferState.FAILED, message = "auth failed").statusLine())
        assertEquals("Cancelled", base.copy(state = TransferState.CANCELLED).statusLine())
    }
}
