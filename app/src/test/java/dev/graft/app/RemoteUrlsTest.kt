package dev.graft.app

import dev.graft.app.data.github.PullRequest
import dev.graft.app.git.browserUrlFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteUrlsTest {

    @Test
    fun `https clone urls map to the repo page`() {
        assertEquals("https://github.com/o/r", browserUrlFor("https://github.com/o/r.git"))
        assertEquals("https://github.com/o/r", browserUrlFor("https://github.com/o/r"))
        assertEquals("https://github.com/o/r", browserUrlFor("https://github.com/o/r/"))
        assertEquals("https://gitlab.example.com:8443/group/sub/r", browserUrlFor("https://gitlab.example.com:8443/group/sub/r.git"))
    }

    @Test
    fun `credentials embedded in a url are stripped, never carried into the link`() {
        val url = browserUrlFor("https://user:ghp_secretToken@github.com/o/r.git")
        assertEquals("https://github.com/o/r", url)
        assertFalse(url!!.contains("ghp_secretToken"))
    }

    @Test
    fun `scp-style urls become https links`() {
        assertEquals("https://github.com/o/r", browserUrlFor("git@github.com:o/r.git"))
    }

    @Test
    fun `things a browser can't show return null`() {
        assertNull(browserUrlFor(""))
        assertNull(browserUrlFor("   "))
        assertNull(browserUrlFor("/storage/emulated/0/Repos/thing"))
        assertNull(browserUrlFor("ssh://git@github.com/o/r.git"))
    }

    // ---------------------------------------------------------------- PullRequest.label

    private fun pr(state: String, draft: Boolean = false, merged: Boolean = false) = PullRequest(
        number = 1, title = "t", author = "a", state = state, draft = draft, merged = merged,
        headBranch = "feature", baseBranch = "main", htmlUrl = "u", updatedAt = "",
    )

    @Test
    fun `pull request labels distinguish merged from rejected and draft from open`() {
        assertEquals("Open", pr("open").label)
        assertEquals("Draft", pr("open", draft = true).label)
        // GitHub reports a merged PR as state "closed" - merged must win.
        assertEquals("Merged", pr("closed", merged = true).label)
        assertEquals("Closed", pr("closed").label)
    }
}
