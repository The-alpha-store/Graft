package dev.graft.app

import dev.graft.app.git.GitErrors
import dev.graft.app.git.GitErrors.Kind
import dev.graft.app.git.GitResult
import dev.graft.app.git.gitTry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class GitErrorsTest {

    @Test
    fun `the real cause deep in the chain beats JGit's generic message`() {
        // JGit reports "cannot open git-upload-pack"; the actual reason is a DNS failure underneath.
        val error = RuntimeException(
            "https://github.com/you/repo.git: cannot open git-upload-pack",
            UnknownHostException("Unable to resolve host \"github.com\": No address associated with hostname"),
        )
        assertEquals(Kind.OFFLINE, GitErrors.classify(error))
        assertEquals(GitErrors.OFFLINE_MESSAGE, GitErrors.friendly(error))
    }

    @Test
    fun `the messages from the bug report classify as transient connection problems`() {
        assertEquals(Kind.CONNECTION, GitErrors.classifyText("https://github.com/The-alpha-store/Graft.git: cannot open git-upload-pack"))
        assertEquals(Kind.CONNECTION, GitErrors.classifyText("Software caused connection abort"))
        assertTrue(GitErrors.isTransientMessage("Software caused connection abort"))
        assertTrue(GitErrors.isTransientMessage("https://github.com/Willykez/ChuoPulse.git: cannot open git-upload-pack"))
    }

    @Test
    fun `timeouts and dropped streams are recognised from exception types`() {
        assertEquals(Kind.TIMEOUT, GitErrors.classify(SocketTimeoutException("connect timed out")))
        assertEquals(Kind.CONNECTION, GitErrors.classify(EOFException("end of stream")))
        assertEquals(GitErrors.TIMEOUT_MESSAGE, GitErrors.friendly(SocketTimeoutException("read timed out")))
    }

    @Test
    fun `auth and rejected pushes are actionable, not transient`() {
        assertEquals(Kind.AUTH, GitErrors.classifyText("https://github.com/x/y.git: not authorized"))
        assertEquals(Kind.AUTH, GitErrors.classifyText("Authentication is required but no CredentialsProvider has been registered"))
        assertEquals(Kind.REJECTED, GitErrors.classifyText("origin/main: REJECTED_NONFASTFORWARD"))
        assertFalse(GitErrors.isTransientMessage("https://github.com/x/y.git: not authorized"))
        assertFalse(GitErrors.isTransientMessage("origin/main: REJECTED_NONFASTFORWARD"))
    }

    @Test
    fun `unrelated messages are left exactly as they were`() {
        // These contain words ("timeout", "403") that must NOT trigger a network classification.
        for (msg in listOf(
            "Branch 'timeout-fix' not found",
            "403.html: No such file or directory",
            "Branch 'feature' is not fully merged",
            "Pathspec 'src/Main.kt' did not match any files",
        )) {
            assertEquals(msg, Kind.OTHER, GitErrors.classifyText(msg))
            assertEquals(msg, msg, GitErrors.friendlyText(msg))
            assertFalse(GitErrors.isTransientMessage(msg))
        }
    }

    @Test
    fun `friendly messages classify back to their own kind`() {
        // markError() and the startup cleanup both see already-friendly text, so this must round-trip.
        assertEquals(Kind.OFFLINE, GitErrors.classifyText(GitErrors.OFFLINE_MESSAGE))
        assertEquals(Kind.CONNECTION, GitErrors.classifyText(GitErrors.CONNECTION_MESSAGE))
        assertEquals(Kind.TIMEOUT, GitErrors.classifyText(GitErrors.TIMEOUT_MESSAGE))
        assertEquals(Kind.SERVER, GitErrors.classifyText(GitErrors.SERVER_MESSAGE))
        assertEquals(Kind.AUTH, GitErrors.classifyText(GitErrors.AUTH_MESSAGE))
        assertEquals(Kind.REJECTED, GitErrors.classifyText(GitErrors.REJECTED_MESSAGE))
        assertTrue(GitErrors.isTransientMessage(GitErrors.OFFLINE_MESSAGE))
        assertFalse(GitErrors.isTransientMessage(GitErrors.AUTH_MESSAGE))
    }

    @Test
    fun `gitTry humanises known failures and keeps unknown ones verbatim`() {
        val timeout = gitTry<Unit> { throw SocketTimeoutException("connect timed out") }
        assertEquals(GitErrors.TIMEOUT_MESSAGE, (timeout as GitResult.Error).message)

        val other = gitTry<Unit> { throw IllegalStateException("something specific broke") }
        assertEquals("something specific broke", (other as GitResult.Error).message)
    }
}
