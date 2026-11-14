package dev.graft.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.graft.app.ui.screens.editor.CodeLanguage
import dev.graft.app.ui.screens.editor.TextEdit
import dev.graft.app.ui.screens.editor.applyEdits
import dev.graft.app.ui.screens.editor.autoIndentOnEnter
import dev.graft.app.ui.screens.editor.deleteLine
import dev.graft.app.ui.screens.editor.duplicateLine
import dev.graft.app.ui.screens.editor.indentLines
import dev.graft.app.ui.screens.editor.lineCommentPrefix
import dev.graft.app.ui.screens.editor.moveCursor
import dev.graft.app.ui.screens.editor.outdentLines
import dev.graft.app.ui.screens.editor.toggleLineComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorTextTest {

    private fun tf(text: String, start: Int = 0, end: Int = start) = TextFieldValue(text, TextRange(start, end))

    // ---------------------------------------------------------------- auto-indent

    @Test
    fun `enter keeps the previous line's indentation`() {
        val r = autoIndentOnEnter(tf("    foo", 7), tf("    foo\n", 8))
        assertEquals("    foo\n    ", r.text)
        assertEquals(12, r.selection.start)
    }

    @Test
    fun `enter after an opening brace adds a level`() {
        val r = autoIndentOnEnter(tf("fun a() {", 9), tf("fun a() {\n", 10))
        assertEquals("fun a() {\n    ", r.text)
        assertEquals(14, r.selection.start)
    }

    @Test
    fun `enter between a matching pair opens the block and drops the closer`() {
        val r = autoIndentOnEnter(tf("{}", 1), tf("{\n}", 2))
        assertEquals("{\n    \n}", r.text)
        assertEquals(6, r.selection.start)                     // caret inside the new indented line
    }

    @Test
    fun `auto-indent leaves pastes, replacements and unindented lines alone`() {
        val paste = tf("a\nb\n", 4)
        assertEquals(paste, autoIndentOnEnter(tf("a", 1), paste))                 // +3 chars: a paste
        val replaced = tf("\n", 1)
        assertEquals(replaced, autoIndentOnEnter(tf("abc", 0, 3), replaced))      // selection replaced
        val plain = tf("foo\n", 4)
        assertEquals(plain, autoIndentOnEnter(tf("foo", 3), plain))               // nothing to carry over
    }

    // ---------------------------------------------------------------- line tools

    @Test
    fun `duplicate line copies it below and moves the cursor onto the copy`() {
        val r = duplicateLine(tf("a\nb\nc", 2))
        assertEquals("a\nb\nb\nc", r.text)
        assertEquals(4, r.selection.start)
    }

    @Test
    fun `delete line removes the line and its newline`() {
        assertEquals("a\nc", deleteLine(tf("a\nb\nc", 2)).text)
        assertEquals(2, deleteLine(tf("a\nb\nc", 2)).selection.start)
    }

    @Test
    fun `delete the last line takes the newline before it, and the only line leaves empty text`() {
        val last = deleteLine(tf("a\nb", 3))
        assertEquals("a", last.text)
        assertEquals(1, last.selection.start)
        assertEquals("", deleteLine(tf("abc", 1)).text)
    }

    @Test
    fun `indent shifts every selected line but not blank ones, and keeps the selection covering them`() {
        val r = indentLines(tf("a\nb", 0, 3))
        assertEquals("    a\n    b", r.text)
        assertEquals(TextRange(0, 11), r.selection)

        assertEquals("    a\n\n    b", indentLines(tf("a\n\nb", 0, 4)).text)
    }

    @Test
    fun `outdent removes up to four spaces or one tab per line`() {
        assertEquals("a\nb", outdentLines(tf("    a\n  b", 0, 9)).text)
        assertEquals("a", outdentLines(tf("\ta", 1)).text)
        assertEquals("a", outdentLines(tf("a", 0)).text)                           // nothing to remove
    }

    @Test
    fun `toggle comment adds markers at the shallowest indent, then removes them again`() {
        val commented = toggleLineComment(tf("val a\n  val b", 0, 13), "//")
        assertEquals("// val a\n//   val b", commented.text)

        val restored = toggleLineComment(commented.copy(selection = TextRange(0, commented.text.length)), "//")
        assertEquals("val a\n  val b", restored.text)
    }

    @Test
    fun `toggle comment ignores blank lines and does nothing on an empty block`() {
        assertEquals("# a\n\n# b", toggleLineComment(tf("a\n\nb", 0, 4), "#").text)
        val blank = tf("   ", 1)
        assertEquals(blank, toggleLineComment(blank, "//"))
    }

    @Test
    fun `comment markers depend on the language`() {
        assertEquals("//", lineCommentPrefix(CodeLanguage.KOTLIN_JAVA_C_STYLE))
        assertEquals("#", lineCommentPrefix(CodeLanguage.YAML))
        assertEquals("#", lineCommentPrefix(CodeLanguage.PYTHON))
        assertNull(lineCommentPrefix(CodeLanguage.JSON))
        assertNull(lineCommentPrefix(CodeLanguage.MARKDOWN))
    }

    // ---------------------------------------------------------------- primitives

    @Test
    fun `an offset inside a replaced span lands after the replacement`() {
        val r = applyEdits(tf("abcdef", 3), listOf(TextEdit(pos = 2, removeLen = 3, insert = "X")))
        assertEquals("abXf", r.text)
        assertEquals(3, r.selection.start)
    }

    @Test
    fun `cursor movement clamps to the text`() {
        assertEquals(2, moveCursor(tf("abc", 1), 1).selection.end)
        assertEquals(3, moveCursor(tf("abc", 3), 1).selection.end)
        assertEquals(0, moveCursor(tf("abc", 0), -1).selection.end)
    }
}
