package dev.graft.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory "latest known uncommitted-change count" per repo.
 *
 * Whichever screen last computed a repo's status (Home's refresh, the Changes tab, the Overview)
 * publishes it here, and anything that wants to *show* it - the Changes tab badge in the repo
 * navigation bar, the Home cards - just observes. That keeps the badge live without the shell
 * opening repositories itself or screens knowing about each other.
 *
 * Deliberately not persisted: a count is only meaningful for the moment it was computed.
 */
object RepoStatusStore {
    private val _changeCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val changeCounts: StateFlow<Map<Long, Int>> = _changeCounts

    fun setChangeCount(repoId: Long, count: Int) {
        _changeCounts.update { it + (repoId to count) }
    }
}
