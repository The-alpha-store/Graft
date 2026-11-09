package dev.graft.app.git

/**
 * These five data classes were referenced across GitEngine.kt and the
 * branches/log/remote/stash/tags screens but were never actually defined
 * anywhere in the project — that's the root cause of most of the
 * "Unresolved reference" build errors. Field names/order here match
 * exactly how each screen already consumes them.
 */

data class BranchInfo(
    val name: String,
    val fullRef: String,
    val isRemote: Boolean,
    val isCurrent: Boolean = false,
    val ahead: Int = 0,
    val behind: Int = 0
)

data class CommitInfo(
    val sha: String,
    val shortSha: String,
    val message: String,
    val fullMessage: String,
    val authorName: String,
    val authorEmail: String,
    val authorTime: Long,
    val committerName: String,
    val parentCount: Int,
    val parentShas: List<String> = emptyList(),
)

data class RemoteInfo(
    val name: String,
    val fetchUrl: String,
    val pushUrl: String
)

data class StashInfo(
    val index: Int,
    val message: String,
    val sha: String,
    val author: String,
    val time: Long
)

data class TagInfo(
    val name: String,
    val sha: String
)

/** One line in a file's blame — which commit last touched it, and by whom. */
data class BlameLine(
    val lineNumber: Int, // 1-indexed, matches how the file is normally displayed
    val content: String,
    val commitSha: String,
    val shortSha: String,
    val authorName: String,
    val authorTime: Long,
)
