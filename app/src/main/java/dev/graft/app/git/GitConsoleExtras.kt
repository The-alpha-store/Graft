package dev.graft.app.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevWalk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The second tier of console git commands: the ones you reach for when something looks wrong or you're
 * hunting for history (`show`, `reflog`, `blame`, `shortlog`, `describe`...), plus inspection helpers
 * (`ls-files`, `rev-parse`, `config`) and maintenance (`gc`, `cherry-pick`).
 *
 * Kept apart from [GitConsoleInterpreter] on purpose: that file's command handlers are the reviewed core,
 * and everything here is additive. [run] returns null for a command it doesn't know, so the interpreter
 * can fall through to its own "not recognised" message.
 */
internal object GitConsoleExtras {

    private const val MAX_LINES = 400

    suspend fun run(git: Git, cmd: String, args: List<String>): String? = withContext(Dispatchers.IO) {
        when (cmd) {
            "show" -> show(git, args)
            "reflog" -> reflog(git, args)
            "ls-files" -> lsFiles(git, args)
            "config" -> config(git, args)
            "cherry-pick" -> cherryPick(git, args)
            "describe" -> describe(git, args)
            "rev-parse" -> revParse(git, args)
            "shortlog" -> shortlog(git, args)
            "gc" -> gc(git)
            "blame" -> blame(git, args)
            "version", "--version" -> "git version (Graft's console runs on JGit - a pure-Java git, not the git CLI)"
            else -> null
        }
    }

    private fun nonFlags(args: List<String>) = args.filter { !it.startsWith("-") }

    private fun clip(lines: List<String>): String {
        if (lines.size <= MAX_LINES) return lines.joinToString("\n")
        return lines.take(MAX_LINES).joinToString("\n") + "\n… ${lines.size - MAX_LINES} more lines not shown"
    }

    // ------------------------------------------------------------------------------ show

    private suspend fun show(git: Git, args: List<String>): String {
        val rev = nonFlags(args).firstOrNull() ?: "HEAD"
        val sha = when (val r = GitEngine.resolveRef(git, rev)) {
            is GitResult.Success -> r.data
            is GitResult.Error -> return "fatal: ambiguous argument '$rev': unknown revision or path"
        }
        val walk = RevWalk(git.repository)
        val header = try {
            val c = walk.parseCommit(git.repository.resolve(sha))
            val date = SimpleDateFormat("EEE MMM d HH:mm:ss yyyy Z", Locale.US).format(c.authorIdent.`when`)
            buildString {
                appendLine("commit ${c.name}")
                appendLine("Author: ${c.authorIdent.name} <${c.authorIdent.emailAddress}>")
                appendLine("Date:   $date")
                appendLine()
                c.fullMessage.trimEnd().lines().forEach { appendLine("    $it") }
            }
        } finally {
            walk.close()
        }
        if ("--stat" in args || "--no-patch" in args || "-s" in args) return header.trimEnd()
        return when (val d = GitEngine.getCommitDiff(git, sha)) {
            is GitResult.Success -> clip((header + "\n" + d.data).lines())
            is GitResult.Error -> header + "\n(couldn't build the diff: ${d.message})"
        }
    }

    // ---------------------------------------------------------------------------- reflog

    private fun reflog(git: Git, args: List<String>): String {
        val ref = nonFlags(args).firstOrNull { !it.all(Char::isDigit) } ?: "HEAD"
        val limit = (args.indexOf("-n").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() }) ?: 30
        val entries = git.reflog().setRef(ref).call().toList()
        if (entries.isEmpty()) return "(no reflog entries for $ref)"
        return entries.take(limit).mapIndexed { i, e ->
            "${e.newId.abbreviate(7).name()} $ref@{$i}: ${e.comment}"
        }.joinToString("\n")
    }

    // -------------------------------------------------------------------------- ls-files

    private fun lsFiles(git: Git, args: List<String>): String {
        val filter = nonFlags(args).firstOrNull()
        val cache = git.repository.readDirCache()
        val paths = (0 until cache.entryCount).map { cache.getEntry(it).pathString }
            .filter { filter == null || it.contains(filter) }
        if (paths.isEmpty()) return if (filter == null) "(the index is empty)" else "(no tracked files match \"$filter\")"
        return clip(paths)
    }

    // ----------------------------------------------------------------------------- config

    /** `config`, `config -l`, `config user.name`, `config user.name "Ada"` - against this repo's `.git/config`. */
    private fun config(git: Git, args: List<String>): String {
        val config = git.repository.config
        val rest = args.filter { it != "--local" && it != "--get" && it != "--global" }
        if ("--global" in args) return "--global isn't available: Graft has one identity for every repo (Settings → Git identity)."

        if (rest.isEmpty() || "-l" in rest || "--list" in rest) {
            val lines = ArrayList<String>()
            for (section in config.sections.sorted()) {
                for (name in config.getNames(section).sorted()) lines.add("$section.$name=${config.getString(section, null, name)}")
                for (sub in config.getSubsections(section).sorted()) {
                    for (name in config.getNames(section, sub).sorted()) lines.add("$section.$sub.$name=${config.getString(section, sub, name)}")
                }
            }
            return if (lines.isEmpty()) "(empty config)" else clip(lines)
        }

        val key = rest.first()
        val parts = key.split('.')
        if (parts.size < 2) return "error: key does not contain a section: $key"
        val section = parts.first()
        val name = parts.last()
        val sub = if (parts.size > 2) parts.subList(1, parts.size - 1).joinToString(".") else null

        return if (rest.size == 1) {
            config.getString(section, sub, name) ?: "(not set)"
        } else {
            config.setString(section, sub, name, rest[1])
            config.save()
            "Set $key in this repo's .git/config. (Commits made from the console use Settings → Git identity.)"
        }
    }

    // ---------------------------------------------------------------------- cherry-pick

    private suspend fun cherryPick(git: Git, args: List<String>): String {
        val target = nonFlags(args).firstOrNull() ?: return "usage: git cherry-pick <commit>"
        return when (val r = GitEngine.cherryPick(git, target)) {
            is GitResult.Success -> r.data
            is GitResult.Error -> "error: ${r.message}"
        }
    }

    // ------------------------------------------------------------------------- describe

    private fun describe(git: Git, args: List<String>): String {
        val cmd = git.describe()
        if ("--tags" in args) cmd.setTags(true)
        return cmd.call() ?: "fatal: No names found, cannot describe anything."
    }

    // ------------------------------------------------------------------------ rev-parse

    private fun revParse(git: Git, args: List<String>): String {
        val repo = git.repository
        if ("--show-toplevel" in args) return repo.workTree.absolutePath
        if ("--git-dir" in args) return repo.directory.absolutePath
        if ("--is-inside-work-tree" in args) return "true"
        val rev = nonFlags(args).firstOrNull() ?: "HEAD"
        if ("--abbrev-ref" in args) return if (rev == "HEAD") repo.branch else rev
        val id = repo.resolve(rev) ?: return "fatal: ambiguous argument '$rev': unknown revision or path"
        return if ("--short" in args) id.abbreviate(7).name() else id.name
    }

    // ------------------------------------------------------------------------- shortlog

    private suspend fun shortlog(git: Git, args: List<String>): String {
        val commits = when (val r = GitEngine.getLog(git, maxCount = 5000)) {
            is GitResult.Success -> r.data
            is GitResult.Error -> return "error: ${r.message}"
        }
        if (commits.isEmpty()) return "(no commits)"
        val byAuthor = commits.groupBy { it.authorName }
        val summaryOnly = "-s" in args || "-sn" in args || "-ns" in args
        val sortByCount = "-n" in args || "-sn" in args || "-ns" in args
        val authors = if (sortByCount) byAuthor.entries.sortedByDescending { it.value.size } else byAuthor.entries.sortedBy { it.key.lowercase() }
        return clip(authors.flatMap { (author, list) ->
            if (summaryOnly) listOf("%6d\t%s".format(Locale.US, list.size, author))
            else listOf("$author (${list.size}):") + list.map { "      ${it.message}" } + ""
        })
    }

    // ------------------------------------------------------------------------------- gc

    private fun gc(git: Git): String {
        git.gc().call()
        return "Garbage collection finished. (Repacking can make later status/log/diff faster on large repos.)"
    }

    // ----------------------------------------------------------------------------- blame

    /** `blame <file>` or `blame -L 10,40 <file>`. */
    private suspend fun blame(git: Git, args: List<String>): String {
        val file = nonFlags(args).lastOrNull { !it.contains(',') || it.contains('.') || it.contains('/') }
            ?: return "usage: git blame [-L start,end] <file>"
        val range = args.indexOf("-L").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val (from, to) = range?.split(',')?.let { (it.getOrNull(0)?.toIntOrNull() ?: 1) to (it.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE) }
            ?: (1 to Int.MAX_VALUE)
        val lines = when (val r = GitEngine.getBlame(git, file)) {
            is GitResult.Success -> r.data
            is GitResult.Error -> return "fatal: ${r.message}"
        }
        if (lines.isEmpty()) return "fatal: no such path '$file' in HEAD"
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return clip(lines.filter { it.lineNumber in from..to }.map {
            "${it.shortSha} (${it.authorName.take(12).padEnd(12)} ${date.format(Date(it.authorTime))} ${it.lineNumber.toString().padStart(4)}) ${it.content}"
        })
    }
}
