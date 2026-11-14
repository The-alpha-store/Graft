package dev.graft.app.ui.screens.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Pure text-editing operations behind the editor's line tools and auto-indent. Kept free of Compose
 * state and Android so each one is a plain function from [TextFieldValue] to [TextFieldValue] - which
 * is what makes them unit-testable and lets the ViewModel turn any of them into a single undo step.
 */

/** Replace [removeLen] characters at [pos] with [insert]. Edits passed together must not overlap. */
internal data class TextEdit(val pos: Int, val removeLen: Int, val insert: String)

internal fun lineStartOf(text: String, offset: Int): Int {
    val o = offset.coerceIn(0, text.length)
    return if (o == 0) 0 else text.lastIndexOf('\n', o - 1) + 1
}

internal fun lineEndOf(text: String, offset: Int): Int {
    val o = offset.coerceIn(0, text.length)
    val i = text.indexOf('\n', o)
    return if (i < 0) text.length else i
}

/**
 * Start of the first line and end of the last line the selection touches. A selection that ends
 * right after a newline (e.g. triple-tap select of a whole line) doesn't drag in the *next* line.
 */
internal fun selectedBlock(value: TextFieldValue): Pair<Int, Int> {
    val t = value.text
    val s = value.selection.min
    var e = value.selection.max
    if (e > s && e > 0 && t[e - 1] == '\n') e -= 1
    return lineStartOf(t, s) to lineEndOf(t, e)
}

/** Start offset of every line in `[blockStart, blockEnd]`. */
private fun lineStartsIn(text: String, blockStart: Int, blockEnd: Int): List<Int> {
    val starts = ArrayList<Int>()
    var ls = blockStart
    while (ls <= blockEnd) {
        starts.add(ls)
        val le = lineEndOf(text, ls)
        if (le >= text.length) break
        ls = le + 1
    }
    return starts
}

/**
 * Applies [edits] to the text and carries the selection along: offsets after an edit shift by its
 * net length change, and an offset that fell inside a removed span lands at the end of its replacement.
 */
internal fun applyEdits(value: TextFieldValue, edits: List<TextEdit>): TextFieldValue {
    if (edits.isEmpty()) return value
    val sorted = edits.sortedBy { it.pos }
    val sb = StringBuilder()
    var cursor = 0
    for (e in sorted) {
        sb.append(value.text, cursor, e.pos)
        sb.append(e.insert)
        cursor = e.pos + e.removeLen
    }
    sb.append(value.text, cursor, value.text.length)

    fun map(offset: Int): Int {
        var delta = 0
        for (e in sorted) {
            if (offset <= e.pos) break
            if (offset >= e.pos + e.removeLen) delta += e.insert.length - e.removeLen
            else return e.pos + delta + e.insert.length
        }
        return offset + delta
    }
    val text = sb.toString()
    return TextFieldValue(
        text,
        TextRange(map(value.selection.start).coerceIn(0, text.length), map(value.selection.end).coerceIn(0, text.length)),
    )
}

private const val INDENT = "    "

internal fun indentLines(value: TextFieldValue): TextFieldValue {
    val (bs, be) = selectedBlock(value)
    val edits = lineStartsIn(value.text, bs, be)
        .filter { lineEndOf(value.text, it) > it }          // don't pad blank lines with spaces
        .map { TextEdit(it, 0, INDENT) }
    return applyEdits(value, edits)
}

internal fun outdentLines(value: TextFieldValue): TextFieldValue {
    val (bs, be) = selectedBlock(value)
    val t = value.text
    val edits = lineStartsIn(t, bs, be).mapNotNull { ls ->
        val remove = when {
            t.startsWith("\t", ls) -> 1
            else -> {
                var n = 0
                while (n < INDENT.length && t.startsWith(" ", ls + n)) n++
                n
            }
        }
        if (remove > 0) TextEdit(ls, remove, "") else null
    }
    return applyEdits(value, edits)
}

/**
 * Comments the selected lines with [prefix], or - if every non-blank line already has it - removes it.
 * New markers go at the block's *shallowest* indent so the commented block stays aligned.
 */
internal fun toggleLineComment(value: TextFieldValue, prefix: String): TextFieldValue {
    val (bs, be) = selectedBlock(value)
    val t = value.text
    val lines = lineStartsIn(t, bs, be)
        .map { ls -> ls to lineEndOf(t, ls) }
        .filter { (ls, le) -> t.substring(ls, le).isNotBlank() }
    if (lines.isEmpty()) return value

    fun indentOf(ls: Int, le: Int): Int = t.substring(ls, le).takeWhile { it == ' ' || it == '\t' }.length

    val allCommented = lines.all { (ls, le) -> t.substring(ls + indentOf(ls, le), le).startsWith(prefix) }
    val edits = if (allCommented) {
        lines.map { (ls, le) ->
            val pos = ls + indentOf(ls, le)
            val spaceAfter = if (t.startsWith(" ", pos + prefix.length)) 1 else 0
            TextEdit(pos, prefix.length + spaceAfter, "")
        }
    } else {
        val minIndent = lines.minOf { (ls, le) -> indentOf(ls, le) }
        lines.map { (ls, _) -> TextEdit(ls + minIndent, 0, "$prefix ") }
    }
    return applyEdits(value, edits)
}

/** Copies the selected lines directly beneath themselves; the selection moves down onto the copy. */
internal fun duplicateLine(value: TextFieldValue): TextFieldValue {
    val (bs, be) = selectedBlock(value)
    val t = value.text
    val block = t.substring(bs, be)
    val delta = block.length + 1
    return TextFieldValue(
        t.substring(0, be) + "\n" + block + t.substring(be),
        TextRange(value.selection.start + delta, value.selection.end + delta),
    )
}

/** Removes the selected lines, newline included, leaving the cursor where the next line now begins. */
internal fun deleteLine(value: TextFieldValue): TextFieldValue {
    val (bs, be) = selectedBlock(value)
    val t = value.text
    val removeEnd = if (be < t.length) be + 1 else be
    val removeStart = if (be >= t.length && bs > 0) bs - 1 else bs   // last line: take the newline before it
    val newText = t.removeRange(removeStart, removeEnd)
    val caret = removeStart.coerceAtMost(newText.length)
    return TextFieldValue(newText, TextRange(caret))
}

/**
 * After the user presses Enter, carry the previous line's indentation onto the new one; add a level after
 * an opening `{ ( [ :`; and when Enter splits a matching pair like `{|}`, open the block with the closing
 * bracket dropped to its own line. Only fires for a lone newline typed at a collapsed cursor - pastes and
 * replacements are left exactly as the user made them.
 */
internal fun autoIndentOnEnter(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    if (!old.selection.collapsed || !new.selection.collapsed) return new
    if (new.text.length != old.text.length + 1) return new
    val cursor = new.selection.start
    if (cursor < 1 || new.text[cursor - 1] != '\n') return new
    if (old.selection.start != cursor - 1) return new

    val prevLine = new.text.substring(lineStartOf(new.text, cursor - 1), cursor - 1)
    val indent = prevLine.takeWhile { it == ' ' || it == '\t' }
    val last = prevLine.trimEnd().lastOrNull()
    val extra = if (last != null && last in "{([:") INDENT else ""
    if (indent.isEmpty() && extra.isEmpty()) return new

    val next = new.text.getOrNull(cursor)
    val splitsPair = (last == '{' && next == '}') || (last == '(' && next == ')') || (last == '[' && next == ']')
    val insert = if (splitsPair) indent + extra + "\n" + indent else indent + extra
    val caret = cursor + indent.length + extra.length
    return TextFieldValue(new.text.substring(0, cursor) + insert + new.text.substring(cursor), TextRange(caret))
}

internal fun moveCursor(value: TextFieldValue, delta: Int): TextFieldValue {
    val pos = (value.selection.end + delta).coerceIn(0, value.text.length)
    return value.copy(selection = TextRange(pos))
}

/** The single-line comment marker for [language], or null where there isn't a sensible one. */
internal fun lineCommentPrefix(language: CodeLanguage): String? = when (language) {
    CodeLanguage.KOTLIN_JAVA_C_STYLE -> "//"
    CodeLanguage.PYTHON, CodeLanguage.SHELL, CodeLanguage.YAML, CodeLanguage.PROPERTIES -> "#"
    else -> null
}
