package dev.graft.app.data.repository

import dev.graft.app.data.RepoStorage
import dev.graft.app.data.db.dao.RepoDao
import dev.graft.app.git.GitErrors
import kotlinx.coroutines.flow.first
import dev.graft.app.data.db.entity.RepoEntity
import dev.graft.app.git.GitEngine
import dev.graft.app.git.GitResult
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for repo metadata. ViewModels talk to this,
 * never to the DAO directly.
 */
class RepoRepository(private val dao: RepoDao) {

    val allRepos: Flow<List<RepoEntity>> = dao.getAllFlow()

    suspend fun getById(id: Long): RepoEntity? = dao.getById(id)

    suspend fun getByPath(path: String): RepoEntity? = dao.getByPath(path)

    /** Returns the new repo's row ID. */
    suspend fun addRepo(repo: RepoEntity): Long = dao.insert(repo)

    suspend fun updateRepo(repo: RepoEntity) = dao.update(repo)

    suspend fun markSyncSuccess(id: Long) = dao.markSyncSuccess(id)

    /**
     * Records a repo's lasting error - but only an *actionable* one (bad credentials, a rejected
     * push...). A dropped connection or timeout is not a property of the repo, it's a property of the
     * moment, so it's deliberately not stored: otherwise one sync pass in a tunnel leaves every
     * repo showing a red "Error" until something happens to succeed. Those failures still surface
     * where they happen - as a message, and as a retryable row in Transfers.
     */
    suspend fun markError(id: Long, error: String) {
        if (GitErrors.isTransientMessage(error)) return
        dao.markError(id, GitErrors.friendlyText(error))
    }

    suspend fun clearError(id: Long) = dao.clearError(id)

    /** One-off tidy-up: drops stored errors that were really just network blips (from older builds). */
    suspend fun clearTransientErrors() {
        allRepos.first()
            .filter { it.lastError.isNotBlank() && GitErrors.isTransientMessage(it.lastError) }
            .forEach { dao.clearError(it.id) }
    }

    suspend fun updateBranch(id: Long, branch: String) = dao.updateBranch(id, branch)

    suspend fun deleteRepo(repo: RepoEntity) = dao.delete(repo)

    /**
     * Looks for git repos sitting directly inside the public repos folder that aren't
     * tracked yet — e.g. copied in with a file manager, pulled over USB/MTP, or checked
     * out with `git` from Termux — and registers each one, the same way a Clone would.
     *
     * A folder counts as a repo if it contains a `.git` directory (a normal, non-bare
     * working copy — the only kind this app creates or opens elsewhere). Anything JGit
     * can't open is silently skipped rather than surfaced as an error, since a folder
     * that merely *looks* like a repo (an empty `.git`, a corrupt one, one still being
     * copied) isn't something the person asked about — it just isn't a repo yet.
     *
     * Returns how many new repos were found and added.
     */
    suspend fun scanForLocalRepos(context: android.content.Context): Int {
        val root = RepoStorage.reposRootDir(context)
        val candidates = root.listFiles { f -> f.isDirectory && java.io.File(f, ".git").isDirectory }
            ?: return 0

        var added = 0
        for (dir in candidates) {
            if (dao.getByPath(dir.absolutePath) != null) continue // already tracked

            when (val opened = GitEngine.openRepo(dir.absolutePath)) {
                is GitResult.Error -> Unit // doesn't look like a real repo yet — skip quietly
                is GitResult.Success -> {
                    val git = opened.data
                    val branch = (GitEngine.getCurrentBranch(git) as? GitResult.Success)?.data.orEmpty()
                    val remotes = (GitEngine.listRemotes(git) as? GitResult.Success)?.data.orEmpty()
                    val originUrl = remotes.firstOrNull { it.name == "origin" }?.fetchUrl
                        ?: remotes.firstOrNull()?.fetchUrl.orEmpty()
                    git.close()

                    dao.insert(
                        RepoEntity(
                            name = dir.name,
                            fullSavePath = dir.absolutePath,
                            cloneUrl = originUrl,
                            branch = branch,
                        ),
                    )
                    added++
                }
            }
        }
        return added
    }
}
