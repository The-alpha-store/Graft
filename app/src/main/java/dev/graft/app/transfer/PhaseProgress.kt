package dev.graft.app.transfer

import kotlin.math.max

/**
 * Turns JGit's sequence of separate progress phases into one monotonic overall fraction.
 *
 * JGit reports each phase on its own - "Receiving objects" runs 0..N, then "Resolving deltas" starts
 * again at 0..M - so a naive bar jumps back to 0% several times, which looks broken. Each known phase
 * is given a band of the overall bar (sized by how long it typically takes), progress inside the band
 * is the phase's own fraction, and the overall figure never moves backwards. Unrecognised phases leave
 * the bar where it is rather than guessing.
 *
 * Pure (no Android, no clock beyond the timestamps passed in) so it's unit-tested directly.
 */
class PhaseProgress {
    var overall: Float = 0f
        private set
    var phase: String = ""
        private set
    var done: Int = 0
        private set
    var total: Int = 0
        private set

    private var band: Pair<Float, Float>? = null
    private var phaseStartNanos: Long = 0L

    /** True until there's a measurable fraction - the UI shows an indeterminate bar until then. */
    val indeterminate: Boolean get() = overall <= 0f

    fun begin(title: String?, totalWork: Int, nowNanos: Long) {
        phase = title.orEmpty()
        done = 0
        total = totalWork
        band = bandFor(phase)
        phaseStartNanos = nowNanos
    }

    fun advance(completed: Int) {
        done += completed
        val b = band ?: return
        if (total > 0) {
            val fraction = (done.toFloat() / total).coerceIn(0f, 1f)
            overall = max(overall, b.first + (b.second - b.first) * fraction)
        }
    }

    fun end() {
        val b = band ?: return
        overall = max(overall, b.second)
    }

    /** Seconds left in the *current phase*, from its own pace; -1 until enough has happened. */
    fun phaseEtaSeconds(nowNanos: Long): Long {
        if (total <= 0 || done <= 0) return -1L
        val fraction = done.toFloat() / total
        if (fraction < 0.05f || fraction >= 1f) return -1L
        val elapsedSec = (nowNanos - phaseStartNanos) / 1_000_000_000.0
        if (elapsedSec < 1.0) return -1L
        return (elapsedSec * (1 - fraction) / fraction).toLong()
    }

    companion object {
        /** The overall-bar range each recognised phase occupies. */
        fun bandFor(title: String): Pair<Float, Float>? {
            val t = title.lowercase()
            return when {
                "counting" in t || "enumerating" in t || "finding" in t || "analyzing" in t -> 0f to 0.08f
                "compressing" in t -> 0.08f to 0.20f
                "receiving" in t || "writing" in t || "downloading" in t -> 0.20f to 0.75f
                "resolving" in t -> 0.75f to 0.90f
                "checking out" in t || "updating" in t -> 0.90f to 1.00f
                else -> null
            }
        }
    }
}

/**
 * Smoothed throughput from periodic byte-count samples (exponential moving average), so the speed
 * readout is steady instead of flickering with every network burst.
 */
class RateTracker(private val smoothing: Double = 0.3) {
    private var lastBytes = -1L
    private var lastNanos = 0L
    var bytesPerSec: Long = 0L
        private set

    fun sample(bytes: Long, nowNanos: Long): Long {
        if (lastBytes < 0) {
            lastBytes = bytes
            lastNanos = nowNanos
            return 0L
        }
        val dtSec = (nowNanos - lastNanos) / 1_000_000_000.0
        if (dtSec < 0.2) return bytesPerSec
        val instantaneous = (bytes - lastBytes).coerceAtLeast(0L) / dtSec
        bytesPerSec = (if (bytesPerSec == 0L) instantaneous
        else smoothing * instantaneous + (1 - smoothing) * bytesPerSec).toLong()
        lastBytes = bytes
        lastNanos = nowNanos
        return bytesPerSec
    }
}
