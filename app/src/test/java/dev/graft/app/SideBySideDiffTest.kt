package dev.graft.app

import dev.graft.app.ui.screens.diff.DiffLine
import dev.graft.app.ui.screens.diff.DiffLineType
import dev.graft.app.ui.screens.diff.SideBySideRow
import dev.graft.app.ui.screens.diff.alignSideBySide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SideBySideDiffTest {

    private fun ctx(text: String, o: Int, n: Int) = DiffLine(text, DiffLineType.CONTEXT, o, n)
    private fun del(text: String, o: Int) = DiffLine(text, DiffLineType.REMOVED, oldLineNo = o)
    private fun add(text: String, n: Int) = DiffLine(text, DiffLineType.ADDED, newLineNo = n)

    @Test
    fun `a multi-line modified block pairs removals with additions by position`() {
        // Unified form of "A,B -> C,D":  -A -B +C +D
        val rows = alignSideBySide(listOf(del("A", 1), del("B", 2), add("C", 1), add("D", 2)))
            .filterIsInstance<SideBySideRow.Lines>()

        assertEquals(2, rows.size)
        assertEquals("A", rows[0].left?.text); assertEquals("C", rows[0].right?.text)
        // The old implementation put B beside C here.
        assertEquals("B", rows[1].left?.text); assertEquals("D", rows[1].right?.text)
    }

    @Test
    fun `uneven blocks pad the shorter side with blank cells`() {
        val rows = alignSideBySide(listOf(del("old", 1), add("new1", 1), add("new2", 2), add("new3", 3)))
            .filterIsInstance<SideBySideRow.Lines>()

        assertEquals(3, rows.size)
        assertEquals("old", rows[0].left?.text)
        assertNull(rows[1].left); assertNull(rows[2].left)
        assertEquals(listOf("new1", "new2", "new3"), rows.map { it.right?.text })
    }

    @Test
    fun `pure additions leave the left column empty`() {
        val rows = alignSideBySide(listOf(add("x", 5))).filterIsInstance<SideBySideRow.Lines>()
        assertEquals(1, rows.size)
        assertNull(rows[0].left)
        assertEquals("x", rows[0].right?.text)
    }

    @Test
    fun `context lines appear on both sides and flush pending changes first`() {
        val rows = alignSideBySide(
            listOf(ctx("keep", 1, 1), del("gone", 2), add("here", 2), ctx("keep2", 3, 3)),
        ).filterIsInstance<SideBySideRow.Lines>()

        assertEquals(3, rows.size)
        assertEquals("keep", rows[0].left?.text); assertEquals("keep", rows[0].right?.text)
        assertEquals("gone", rows[1].left?.text); assertEquals("here", rows[1].right?.text)
        assertEquals("keep2", rows[2].left?.text)
    }

    @Test
    fun `hunk headers become full-width rows and split change runs`() {
        val rows = alignSideBySide(
            listOf(del("a", 1), DiffLine("@@ -10 +10 @@", DiffLineType.HUNK), add("b", 10)),
        )
        assertEquals(3, rows.size)
        assertTrue(rows[1] is SideBySideRow.Hunk)
        // The removal before the hunk must not pair with the addition after it.
        assertNull((rows[0] as SideBySideRow.Lines).right)
        assertNull((rows[2] as SideBySideRow.Lines).left)
    }

    @Test
    fun `empty input yields no rows`() {
        assertTrue(alignSideBySide(emptyList()).isEmpty())
    }
}
