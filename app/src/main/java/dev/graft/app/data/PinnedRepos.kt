package dev.graft.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which repos the user pinned to the top of Home. Just a set of ids in SharedPreferences, deliberately
 * not a database column: pinning is a view preference, and keeping it out of Room means no schema
 * migration and nothing to lose if the repo list is ever rebuilt.
 */
object PinnedRepos {
    private const val PREFS_NAME = "graft_pinned_repos"
    private const val KEY = "ids"
    private lateinit var prefs: SharedPreferences

    private val _ids = MutableStateFlow<Set<Long>>(emptySet())
    val ids: StateFlow<Set<Long>> = _ids

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _ids.value = prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun toggle(context: Context, repoId: Long) {
        init(context)
        val next = if (repoId in _ids.value) _ids.value - repoId else _ids.value + repoId
        prefs.edit().putStringSet(KEY, next.map { it.toString() }.toSet()).apply()
        _ids.value = next
    }
}
