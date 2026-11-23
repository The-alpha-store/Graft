package dev.graft.app

import dev.graft.app.data.github.WorkflowTemplateCatalog
import dev.graft.app.git.CloudRunner
import dev.graft.app.git.ConsoleRouter
import dev.graft.app.git.ConsoleRouter.Route
import dev.graft.app.git.ShellRunner
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ConsoleRouterTest {

    @Test
    fun `git commands go to the git engine with or without a leading git`() {
        assertEquals(Route.Git("git status"), ConsoleRouter.classify("git status"))
        assertEquals(Route.Git("status"), ConsoleRouter.classify("status"))
        assertEquals(Route.Git("show HEAD~1"), ConsoleRouter.classify("  show HEAD~1  "))
        assertEquals(Route.Git("st"), ConsoleRouter.classify("st"))                 // alias
        assertEquals(Route.Git("blame src/Main.kt"), ConsoleRouter.classify("blame src/Main.kt"))
    }

    @Test
    fun `anything else goes to the device shell first`() {
        assertEquals(Route.Shell("ls -la"), ConsoleRouter.classify("ls -la"))
        assertEquals(Route.Shell("cat README.md"), ConsoleRouter.classify("cat README.md"))
        // Not special-cased: a toolchain command is simply "not git"; the shell reporting it missing is
        // what triggers the cloud offer. Nothing for the user to choose.
        assertEquals(Route.Shell("cargo fmt --all"), ConsoleRouter.classify("cargo fmt --all"))
        assertEquals(Route.Shell("npm test"), ConsoleRouter.classify("npm test"))
    }

    @Test
    fun `bang forces the shell, so a shell rm is possible despite git rm`() {
        assertEquals(Route.Git("rm file.txt"), ConsoleRouter.classify("rm file.txt"))   // git rm, as before
        assertEquals(Route.Shell("rm file.txt"), ConsoleRouter.classify("!rm file.txt"))
    }

    @Test
    fun `cloud prefix is explicit and understands no-commit and setup`() {
        assertEquals(Route.Cloud("cargo fmt --all", true), ConsoleRouter.classify("cloud cargo fmt --all"))
        assertEquals(Route.Cloud("cargo test", false), ConsoleRouter.classify("cloud --no-commit cargo test"))
        assertEquals(Route.CloudSetup, ConsoleRouter.classify("cloud setup"))
        assertEquals(Route.Cloud("", true), ConsoleRouter.classify("cloud"))           // the VM prints usage for this
    }

    @Test
    fun `clear is handled locally`() {
        assertEquals(Route.Clear, ConsoleRouter.classify("clear"))
        assertEquals(Route.Clear, ConsoleRouter.classify("cls"))
    }

    @Test
    fun `destructive shell commands are flagged, harmless ones are not`() {
        assertNotNull(ConsoleRouter.shellDanger("rm -rf build"))
        assertNotNull(ConsoleRouter.shellDanger("rm -r src"))
        assertNotNull(ConsoleRouter.shellDanger("ls && rm -f x"))
        assertNotNull(ConsoleRouter.shellDanger("dd if=/dev/zero of=x"))
        assertNull(ConsoleRouter.shellDanger("rm file.txt"))
        assertNull(ConsoleRouter.shellDanger("rm -i file.txt"))
        assertNull(ConsoleRouter.shellDanger("echo rm -rf"))
        assertNull(ConsoleRouter.shellDanger("ls -la"))
    }

    @Test
    fun `the program name is the first real word`() {
        assertEquals("cargo", ConsoleRouter.programOf("cargo fmt --all"))
        assertEquals("cargo", ConsoleRouter.programOf("RUSTFLAGS=-Dwarnings cargo build"))
        assertEquals("ls", ConsoleRouter.programOf("  ls "))
    }
}

class CloudRunnerTest {

    private fun log(vararg lines: String) = lines.joinToString("\n") { "2026-11-20T10:00:00.0000000Z $it" }

    @Test
    fun `log cleaning keeps only what the command printed`() {
        val raw = log(
            "##[group]Set up job", "Current runner version: '2.300'", "##[endgroup]",
            "##[group]Run actions/checkout@v4", "with:", "##[endgroup]",
            "Syncing repository: o/r",
            "##[group]Run bash -c \"\$GRAFT_COMMAND\"", "bash -c \"\$GRAFT_COMMAND\"", "shell: /usr/bin/bash -e {0}",
            "env:", "  GRAFT_COMMAND: cargo fmt --all", "##[endgroup]",
            "Diff in src/main.rs",
            "##[error]Process completed with exit code 1.",
            "##[group]Post Run actions/checkout@v4", "Cleaning up", "##[endgroup]",
        )
        assertEquals("Diff in src/main.rs\nerror: Process completed with exit code 1.", CloudRunner.cleanActionsLog(raw))
    }

    @Test
    fun `a successful silent command says so`() {
        val raw = log("##[group]Run bash -c \"\$GRAFT_COMMAND\"", "env:", "##[endgroup]")
        assertEquals("(no output)", CloudRunner.cleanActionsLog(raw))
    }

    @Test
    fun `output from several user steps is kept, in order`() {
        val raw = log(
            "##[group]Run bash -c \"\$GRAFT_COMMAND\"", "x", "##[endgroup]", "formatted 3 files",
            "##[group]Run if [ -n \"\$(git status --porcelain)\" ]; then", "x", "##[endgroup]", "[main abc1234] chore: run",
        )
        assertEquals("formatted 3 files\n[main abc1234] chore: run", CloudRunner.cleanActionsLog(raw))
    }

    @Test
    fun `long output is trimmed from the top`() {
        val body = (1..200).map { "line $it" }
        val raw = log("##[group]Run bash -c x", "##[endgroup]", *body.toTypedArray())
        val cleaned = CloudRunner.cleanActionsLog(raw, maxLines = 10).lines()
        assertEquals("… 190 earlier lines hidden", cleaned.first())
        assertEquals("line 200", cleaned.last())
        assertEquals(11, cleaned.size)
    }

    @Test
    fun `dispatch failures are explained with the next step`() {
        assertTrue("cloud setup" in CloudRunner.explainDispatchError("GitHub 404: Not Found"))
        assertTrue("workflow" in CloudRunner.explainDispatchError("403 Resource not accessible by integration"))
        assertTrue("out of date" in CloudRunner.explainDispatchError("422 Workflow does not have 'workflow_dispatch' trigger"))
        assertEquals("something unexpected", CloudRunner.explainDispatchError("something unexpected"))
    }

    @Test
    fun `the runner template matches the constants the console relies on`() {
        val template = WorkflowTemplateCatalog.byFileName(CloudRunner.WORKFLOW_PATH)
        assertNotNull("graft-run.yml must be in the template catalog", template)
        val yaml = template!!.yamlContent
        assertTrue("workflow_dispatch" in yaml)
        assertTrue("inputs:" in yaml)
        assertTrue("name: ${CloudRunner.WORKFLOW_NAME}" in yaml)
        // Security: the user's command must reach the shell through an env var, never be pasted into a
        // `run:` script where a quote could break out of it.
        assertTrue("GRAFT_COMMAND" in yaml)
        assertTrue(yaml.lines().filter { it.trim().startsWith("run:") }.none { "inputs.command" in it })
    }
}

class ShellRunnerTest {

    private fun tempDir(): File = Files.createTempDirectory("graft-shell").toFile()

    private fun requireSh() = assumeTrue("needs /bin/sh", File("/bin/sh").exists())

    @Test
    fun `runs a command and captures its output`() = runBlocking {
        requireSh()
        val r = ShellRunner.run("echo hello", tempDir())
        assertEquals(0, r.exitCode)
        assertEquals("hello", r.output)
        assertTrue(r.succeeded)
    }

    @Test
    fun `commands run inside the given folder`() = runBlocking {
        requireSh()
        val dir = tempDir()
        assertEquals(dir.canonicalPath, ShellRunner.run("pwd", dir).output)
    }

    @Test
    fun `a missing program is reported as command not found`() = runBlocking {
        requireSh()
        val r = ShellRunner.run("definitely-not-a-real-program-xyz --flag", tempDir())
        assertEquals(127, r.exitCode)
        assertTrue(r.commandNotFound)
    }

    @Test
    fun `non-zero exit codes and stderr are both surfaced`() = runBlocking {
        requireSh()
        val r = ShellRunner.run("echo oops 1>&2; exit 3", tempDir())
        assertEquals(3, r.exitCode)
        assertTrue("oops" in r.output)
        assertFalse(r.succeeded)
    }

    @Test
    fun `a hung command is killed at the timeout`() = runBlocking {
        requireSh()
        val r = ShellRunner.run("sleep 30", tempDir(), timeoutSec = 1)
        assertTrue(r.timedOut)
        assertEquals(124, r.exitCode)
    }

    @Test
    fun `cancelling stops the process promptly instead of waiting it out`() = runBlocking {
        requireSh()
        val job = launch { ShellRunner.run("sleep 30", tempDir()) }
        delay(300)
        // If cancellation didn't kill the process this would block for ~30 s and time out.
        withTimeout(5_000) { job.cancelAndJoin() }
        Unit
    }
}
