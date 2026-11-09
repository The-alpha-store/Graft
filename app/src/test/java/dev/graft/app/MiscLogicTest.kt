package dev.graft.app

import dev.graft.app.data.github.WorkflowTemplateCatalog
import dev.graft.app.ui.components.formatRelativeSeconds
import dev.graft.app.ui.components.relativeTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiscLogicTest {

    @Test
    fun `relative time buckets`() {
        assertEquals("just now", formatRelativeSeconds(5))
        assertEquals("5m ago", formatRelativeSeconds(5 * 60))
        assertEquals("3h ago", formatRelativeSeconds(3 * 3_600))
        assertEquals("yesterday", formatRelativeSeconds(30 * 3_600))
        assertEquals("4d ago", formatRelativeSeconds(4 * 86_400))
        assertEquals("2mo ago", formatRelativeSeconds(65 * 86_400))
        assertEquals("2y ago", formatRelativeSeconds(2 * 31_536_000L + 10))
    }

    @Test
    fun `relative time handles unset and future timestamps`() {
        assertEquals("never", relativeTimeMillis(0))
        // A commit dated slightly in the future (clock skew) must not render negative.
        assertEquals("just now", relativeTimeMillis(epochMillis = 10_000, nowMillis = 5_000))
        assertEquals("2m ago", relativeTimeMillis(epochMillis = 1_000, nowMillis = 1_000 + 130_000))
    }

    @Test
    fun `workflow templates are well formed`() {
        val templates = WorkflowTemplateCatalog.templates
        assertTrue(templates.isNotEmpty())
        assertEquals(templates.size, templates.map { it.id }.toSet().size)
        assertEquals(templates.size, templates.map { it.fileName }.toSet().size)
        for (t in templates) {
            assertTrue(t.fileName, t.fileName.startsWith(".github/workflows/"))
            assertTrue(t.fileName, t.fileName.endsWith(".yml"))
            assertTrue("${t.id} has a name: key", t.yamlContent.contains("name:"))
            assertTrue("${t.id} has jobs", t.yamlContent.contains("jobs:"))
            // The Kotlin raw-string escapes for GitHub's ${{ }} syntax must have survived intact.
            assertTrue("${t.id} expression syntax", !t.yamlContent.contains("'\$'"))
        }
        assertEquals(templates.first(), WorkflowTemplateCatalog.byFileName(templates.first().fileName))
    }
}
