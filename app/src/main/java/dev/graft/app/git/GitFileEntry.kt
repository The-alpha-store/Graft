package dev.graft.app.git

/** How a single file differs from the last commit / the index. */
enum class GitFileStatus {
    ADDED,       // new file, not yet tracked
    MODIFIED,    // tracked file with changes
    DELETED,     // tracked file removed from working dir
    RENAMED,
    TYPE_CHANGED,
    CONFLICTED
}

/**
 * One row in the Changes screen: a file path plus whether the change is
 * currently staged (in the index) or only in the working directory.
 */
data class GitFileEntry(
    val path: String,
    val status: GitFileStatus,
    val staged: Boolean
)
