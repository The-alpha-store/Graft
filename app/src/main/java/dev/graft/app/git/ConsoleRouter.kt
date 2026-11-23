package dev.graft.app.git

/**
 * Decides where a typed line runs, so there's one prompt and nothing to choose:
 *
 * - a known git command (with or without a leading `git`)  -> the git engine
 * - `!cmd`                                                   -> the device shell, explicitly
 * - `cloud <cmd>` / `cloud --no-commit <cmd>` / `cloud setup`-> GitHub Actions, explicitly
 * - `clear`                                                  -> clears the screen
 * - anything else                                            -> tried on the device shell first; if the
 *   program doesn't exist there, the console offers to run it in the cloud (see ConsoleViewModel)
 */
object ConsoleRouter {
    sealed interface Route {
        data class Git(val raw: String) : Route
        data class Shell(val raw: String) : Route
        data class Cloud(val command: String, val commit: Boolean) : Route
        data object CloudSetup : Route
        data object Clear : Route
    }

    /** Everything the git interpreter handles (including its aliases), so these never reach the shell. */
    val GIT_COMMANDS = setOf(
        "status", "diff", "log", "branch", "add", "restore", "rm", "mv", "commit", "push", "pull", "fetch",
        "switch", "checkout", "merge", "rebase", "stash", "reset", "tag", "remote", "clean",
        "show", "reflog", "ls-files", "config", "cherry-pick", "describe", "rev-parse", "shortlog", "gc", "blame",
        "clone", "init", "bisect", "submodule", "version", "--version",
        "st", "co", "br", "ci", "lg", "last", "unstage",
    )

    fun classify(input: String): Route {
        val raw = input.trim()
        if (raw == "clear" || raw == "cls") return Route.Clear
        if (raw.startsWith("!")) return Route.Shell(raw.removePrefix("!").trim())

        val words = raw.split(Regex("\\s+"))
        if (words.first() == "cloud") {
            val rest = words.drop(1)
            return when {
                rest.firstOrNull() == "setup" && rest.size == 1 -> Route.CloudSetup
                rest.firstOrNull() == "--no-commit" -> Route.Cloud(rest.drop(1).joinToString(" "), commit = false)
                else -> Route.Cloud(rest.joinToString(" "), commit = true)
            }
        }
        if (words.first() == "git" || words.first() in GIT_COMMANDS) return Route.Git(raw)
        return Route.Shell(raw)
    }

    /** A warning for shell commands that destroy files outright; null when nothing to confirm. */
    fun shellDanger(command: String): String? {
        val c = command.trim()
        return when {
            Regex("""(^|[;&|]\s*)rm\s+(-\w*[rRf]\w*|--recursive|--force)""").containsMatchIn(c) ->
                "This deletes files permanently - there's no recycle bin, and Git can't bring back anything uncommitted."
            Regex("""(^|[;&|]\s*)(dd|mkfs|shred)\b""").containsMatchIn(c) ->
                "This can irreversibly overwrite or destroy data."
            else -> null
        }
    }

    /** The first word, used in "`cargo` isn't available on this device". */
    fun programOf(command: String): String =
        command.trim().split(Regex("\\s+")).firstOrNull { !it.contains('=') } ?: command.trim()
}
