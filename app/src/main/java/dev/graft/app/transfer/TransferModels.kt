package dev.graft.app.transfer

enum class TransferKind(val label: String, val doing: String) {
    CLONE("Clone", "Cloning"),
    PULL("Pull", "Pulling"),
    PUSH("Push", "Pushing"),
    FETCH("Fetch", "Fetching"),
    SYNC("Sync", "Syncing"),
}

enum class TransferState {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    val isActive: Boolean get() = this == QUEUED || this == RUNNING
    val isFinished: Boolean get() = !isActive
}

/**
 * One network operation - what a row in Chrome's downloads list is.
 *
 * [progress] is an *overall* 0..1 figure built from JGit's separate phases (see [PhaseProgress]) so a
 * single bar moves forward monotonically through counting -> receiving -> resolving -> checkout,
 * rather than restarting at 0% each phase. [bytes]/[bytesPerSec] are only known when something
 * measurable lands on disk (clone, fetch, pull); pushes report object counts instead.
 */
data class TransferTask(
    val id: Long,
    val kind: TransferKind,
    /** 0 when there's no tracked repo yet (a clone in progress). */
    val repoId: Long,
    /** Repo name, shown as the row's headline. */
    val title: String,
    val state: TransferState = TransferState.QUEUED,
    /** Current JGit phase, e.g. "Receiving objects". */
    val phase: String = "",
    val progress: Float = 0f,
    val indeterminate: Boolean = true,
    val unitsDone: Int = 0,
    val unitsTotal: Int = 0,
    val bytes: Long = 0L,
    val bytesPerSec: Long = 0L,
    /** Seconds left in the current phase, or -1 when it can't be estimated yet. */
    val etaSeconds: Long = -1L,
    /** Result summary on success, error text on failure. */
    val message: String? = null,
    /** Repo to open when the person taps the finished row (set by a clone once it's tracked). */
    val openRepoId: Long = 0L,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0L,
)

/** Emitted when a task finishes, for the app shell's snackbar. Only tasks that opt in produce one. */
data class TransferEvent(val task: TransferTask)
