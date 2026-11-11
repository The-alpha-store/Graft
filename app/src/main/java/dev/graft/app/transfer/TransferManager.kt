package dev.graft.app.transfer

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.graft.app.git.GitEngine
import dev.graft.app.git.GitResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs clone / pull / push / fetch as *transfers*: tracked, cancellable, observable, and independent
 * of whichever screen started them.
 *
 * That independence is the point. Previously a push ran inside the Changes screen's ViewModel, so
 * backing out of the repo mid-push threw away the outcome (and the spinner was all the feedback
 * there was). Here the work lives in an application-scoped scope; screens just observe [tasks].
 * While anything is active a foreground [TransferService] keeps the process alive and shows a
 * progress notification, so a clone keeps going with the app in the background.
 *
 * - **Concurrency:** at most [MAX_CONCURRENT] transfers run at once; the rest show as "Waiting".
 * - **Per-repo ordering:** two operations on the same repo run one after the other, never together -
 *   JGit would otherwise fight itself over the index and ref locks.
 * - **Self-contained git handles:** a transfer that needs a repo opens (and closes) its own
 *   [Git] via [withGit], so it never depends on a ViewModel's handle staying alive.
 */
class TransferManager(
    /** Starts the foreground service that keeps the process alive while transfers run. */
    private val onStartService: () -> Unit,
    /** Posts a "finished / failed" notification (only called while the app isn't on screen). */
    private val onResultNotification: (TransferTask) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** Production wiring. The two side effects are injected only so the scheduling rules - the
     *  concurrency limit, per-repo ordering, cancellation - can be unit-tested without Android. */
    constructor(context: Context) : this(
        onStartService = {
            // Delegating-constructor arguments run before `this` exists, so the call site
            // must not touch `this` - including implicitly. A bare `runCatching { }` here
            // resolves to the `T.runCatching` extension (T = TransferManager), which binds
            // an uninitialised `this` and fails to compile. Explicit try/catch has the same
            // behaviour (swallow the failure) without any receiver binding.
            try {
                ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
            } catch (e: Exception) {
                // Service start not allowed right now (e.g. background start restrictions).
                // Transfers still run; only the "stay alive in background" guarantee is lost.
                // (Matches the old `runCatching { }` behaviour except it catches Exception
                //  rather than Throwable - OutOfMemoryError etc. will now propagate, which
                //  is what you want anyway.)
            }
        },
        onResultNotification = { task -> TransferNotifier.notifyResult(context, task) },
    )
    private val globalLimit = Semaphore(MAX_CONCURRENT)
    private val repoLocks = ConcurrentHashMap<Long, Mutex>()
    private val nextId = AtomicLong(1)

    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    /** Newest first. Finished tasks stay (like Chrome's list) until cleared or trimmed. */
    val tasks: StateFlow<List<TransferTask>> = _tasks.asStateFlow()

    private val _events = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<TransferEvent> = _events.asSharedFlow()

    private class Control(val cancelRequested: AtomicBoolean = AtomicBoolean(false)) {
        @Volatile var job: Deferred<GitResult<*>>? = null
    }

    private class Spec(
        val kind: TransferKind,
        val repoId: Long,
        val title: String,
        val watchDir: File?,
        val announce: Boolean,
        val cleanup: (() -> Unit)?,
        val work: suspend (TransferReporter) -> GitResult<*>,
    )

    private val controls = ConcurrentHashMap<Long, Control>()
    private val specs = ConcurrentHashMap<Long, Spec>()

    /**
     * Queues [work]. It receives a [TransferReporter] whose `monitor` should be handed to the JGit
     * command so progress and cancellation flow through.
     *
     * @param watchDir  a repo/destination folder whose `.git/objects/pack` growth is sampled to show
     *                  downloaded bytes and speed. Null for operations with nothing to measure.
     * @param announce  also emit a [TransferEvent] on completion (the shell turns it into a snackbar).
     *                  Leave false when the caller already reports its own result.
     * @param cleanup   run if the transfer fails or is cancelled (a clone uses it to delete the
     *                  half-written folder).
     * @return a handle to the eventual result; awaiting it with [awaitResult] is optional.
     */
    fun enqueue(
        kind: TransferKind,
        repoId: Long,
        title: String,
        watchDir: File? = null,
        announce: Boolean = false,
        cleanup: (() -> Unit)? = null,
        work: suspend (TransferReporter) -> GitResult<*>,
    ): Deferred<GitResult<*>> = start(Spec(kind, repoId, title, watchDir, announce, cleanup, work))

    private fun start(spec: Spec): Deferred<GitResult<*>> {
        val id = nextId.getAndIncrement()
        _tasks.update { listOf(TransferTask(id = id, kind = spec.kind, repoId = spec.repoId, title = spec.title)) + it }
        val control = Control()
        controls[id] = control
        specs[id] = spec
        ensureService()
        val job = scope.async<GitResult<*>> { runTask(id, spec, control) }
        control.job = job
        return job
    }

    fun cancel(id: Long) {
        val control = controls[id] ?: return
        control.cancelRequested.set(true)
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        // Running: the progress monitor reports "cancelled" and JGit aborts. Queued: nothing is
        // running yet, so just drop the waiting coroutine.
        if (task.state == TransferState.QUEUED) control.job?.cancel()
    }

    fun cancelAll() {
        controls.keys.toList().forEach { cancel(it) }
    }

    /** Re-runs a failed or cancelled transfer as a fresh task. */
    fun retry(id: Long) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (!task.state.isFinished) return
        val spec = specs[id] ?: return
        remove(id)
        start(spec)
    }

    fun remove(id: Long) {
        _tasks.update { list -> list.filterNot { it.id == id } }
        specs.remove(id)
    }

    fun clearFinished() {
        val finished = _tasks.value.filter { it.state.isFinished }.map { it.id }
        _tasks.update { list -> list.filter { it.state.isActive } }
        finished.forEach { specs.remove(it) }
    }

    /**
     * Opens [repoPath], runs [block], and always closes the handle. Use inside a transfer's work
     * lambda so the operation owns its repository for exactly as long as it runs.
     */
    suspend fun withGit(repoPath: String, block: suspend (Git) -> GitResult<*>): GitResult<*> =
        when (val opened = GitEngine.openRepo(repoPath)) {
            is GitResult.Error -> GitResult.Error("Couldn't open repository: ${opened.message}", opened.cause)
            is GitResult.Success -> {
                val git = opened.data
                try {
                    block(git)
                } finally {
                    runCatching { git.close() }
                }
            }
        }

    // ---------------------------------------------------------------------------------- internals

    private suspend fun runTask(id: Long, spec: Spec, control: Control): GitResult<*> {
        val lock = if (spec.repoId > 0) repoLocks.getOrPut(spec.repoId) { Mutex() } else null
        return try {
            if (lock != null) {
                lock.withLock { globalLimit.withPermit { execute(id, spec, control) } }
            } else {
                globalLimit.withPermit { execute(id, spec, control) }
            }
        } catch (e: CancellationException) {
            // Cancelled while still waiting in the queue.
            finish(id, spec, TransferState.CANCELLED, "Cancelled")
            GitResult.Error("Cancelled")
        }
    }

    private suspend fun execute(id: Long, spec: Spec, control: Control): GitResult<*> {
        if (control.cancelRequested.get()) {
            finish(id, spec, TransferState.CANCELLED, "Cancelled")
            return GitResult.Error("Cancelled")
        }
        update(id) { it.copy(state = TransferState.RUNNING, startedAt = System.currentTimeMillis()) }

        val reporter = TransferReporter(this, id, control.cancelRequested)
        val sampler = spec.watchDir?.let { dir -> scope.launch(Dispatchers.IO) { sampleBytes(id, dir) } }

        val result: GitResult<*> = try {
            spec.work(reporter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GitResult.Error(e.message ?: e.javaClass.simpleName, e)
        } finally {
            sampler?.cancel()
        }

        return when {
            control.cancelRequested.get() -> {
                runCatching { spec.cleanup?.invoke() }
                finish(id, spec, TransferState.CANCELLED, "Cancelled")
                GitResult.Error("Cancelled")
            }
            result is GitResult.Success<*> -> {
                finish(id, spec, TransferState.SUCCEEDED, (result.data as? String) ?: "${spec.kind.label} complete")
                result
            }
            else -> {
                val message = (result as? GitResult.Error)?.message ?: "Failed"
                runCatching { spec.cleanup?.invoke() }
                finish(id, spec, TransferState.FAILED, message)
                result
            }
        }
    }

    /** Samples growth of `.git/objects/pack` twice a second -> downloaded bytes and smoothed speed. */
    private suspend fun sampleBytes(id: Long, dir: File) {
        val packDir = File(dir, ".git/objects/pack")
        val baseline = folderBytes(packDir)
        val rate = RateTracker()
        while (true) {
            delay(SAMPLE_MS)
            val bytes = (folderBytes(packDir) - baseline).coerceAtLeast(0L)
            val bps = rate.sample(bytes, System.nanoTime())
            updateRunning(id) { it.copy(bytes = bytes, bytesPerSec = bps) }
        }
    }

    private fun folderBytes(dir: File): Long =
        dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    private fun finish(id: Long, spec: Spec, state: TransferState, message: String) {
        controls.remove(id)
        update(id) {
            it.copy(
                state = state,
                message = message,
                finishedAt = System.currentTimeMillis(),
                progress = if (state == TransferState.SUCCEEDED) 1f else it.progress,
                indeterminate = false,
                bytesPerSec = 0L,
                etaSeconds = -1L,
            )
        }
        trimHistory()
        val finished = _tasks.value.firstOrNull { it.id == id } ?: return
        if (spec.announce) _events.tryEmit(TransferEvent(finished))
        if (!AppVisibility.isVisible && state != TransferState.CANCELLED) {
            onResultNotification(finished)
        }
    }

    private fun trimHistory() {
        _tasks.update { list ->
            var finishedSeen = 0
            list.filter { task ->
                if (task.state.isActive) true else (++finishedSeen <= MAX_HISTORY)
            }
        }
    }

    internal fun update(id: Long, transform: (TransferTask) -> TransferTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    /** Progress callbacks can arrive just after a task finishes; they must not resurrect it. */
    internal fun updateRunning(id: Long, transform: (TransferTask) -> TransferTask) {
        _tasks.update { list ->
            list.map { if (it.id == id && it.state == TransferState.RUNNING) transform(it) else it }
        }
    }

    private fun ensureService() {
        runCatching { onStartService() }
    }

    companion object {
        const val MAX_CONCURRENT = 2
        private const val MAX_HISTORY = 30
        private const val SAMPLE_MS = 500L
    }
}

/**
 * Awaits a transfer's result. If the *transfer* was cancelled this returns a normal "Cancelled"
 * error; if the *caller's own* coroutine was cancelled (screen gone) the cancellation propagates, as
 * coroutines require - the transfer itself carries on regardless.
 */
suspend fun Deferred<GitResult<*>>.awaitResult(): GitResult<*> =
    try {
        await()
    } catch (e: CancellationException) {
        if (currentCoroutineContext().isActive) GitResult.Error("Cancelled") else throw e
    }

/** What a transfer's work lambda gets: the JGit [monitor] to pass along, plus small hooks. */
class TransferReporter internal constructor(
    private val manager: TransferManager,
    private val taskId: Long,
    private val cancelRequested: AtomicBoolean,
) {
    private val progress = PhaseProgress()
    @Volatile private var lastPublishNanos = 0L

    /** Hand this to `setProgressMonitor(...)`. Throttled, and aborts the command when cancelled. */
    val monitor: ProgressMonitor = object : ProgressMonitor {
        override fun start(totalTasks: Int) {}

        override fun beginTask(title: String?, totalWork: Int) {
            synchronized(progress) { progress.begin(title, totalWork, System.nanoTime()) }
            publish(force = true)
        }

        override fun update(completed: Int) {
            synchronized(progress) { progress.advance(completed) }
            publish(force = false)
        }

        override fun endTask() {
            synchronized(progress) { progress.end() }
            publish(force = true)
        }

        override fun isCancelled(): Boolean = cancelRequested.get()

        override fun showDuration(enabled: Boolean) {}
    }

    /** The repo to open when the finished row is tapped (a clone sets this once it's tracked). */
    fun setOpenRepoId(repoId: Long) {
        manager.update(taskId) { it.copy(openRepoId = repoId) }
    }

    private fun publish(force: Boolean) {
        val now = System.nanoTime()
        if (!force && now - lastPublishNanos < PUBLISH_INTERVAL_NANOS) return
        lastPublishNanos = now
        val snap = synchronized(progress) {
            Snapshot(
                phase = progress.phase,
                overall = progress.overall,
                indeterminate = progress.indeterminate,
                done = progress.done,
                total = progress.total,
                eta = progress.phaseEtaSeconds(now),
            )
        }
        manager.updateRunning(taskId) {
            it.copy(
                phase = snap.phase,
                progress = snap.overall,
                indeterminate = snap.indeterminate,
                unitsDone = snap.done,
                unitsTotal = snap.total,
                etaSeconds = snap.eta,
            )
        }
    }

    private class Snapshot(
        val phase: String, val overall: Float, val indeterminate: Boolean,
        val done: Int, val total: Int, val eta: Long,
    )

    private companion object {
        const val PUBLISH_INTERVAL_NANOS = 120_000_000L
    }
}