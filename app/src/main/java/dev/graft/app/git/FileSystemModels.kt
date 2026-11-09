package dev.graft.app.git

/** One row in the file explorer — either a folder or a file, relative to the repo root. */
data class FileNode(
    val name: String,
    val relativePath: String, // relative to repo root, '/'-separated, no leading slash
    val isDirectory: Boolean,
    val sizeBytes: Long
)
