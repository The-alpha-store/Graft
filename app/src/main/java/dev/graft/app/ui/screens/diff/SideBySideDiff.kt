package dev.graft.app.ui.screens.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.graft.app.ui.screens.editor.CodeLanguage
import dev.graft.app.ui.screens.editor.highlightText
import dev.graft.app.ui.theme.SyntaxColorSet
import dev.graft.app.ui.theme.code
import dev.graft.app.ui.theme.gitColors

/** One visual row of a two-column diff. */
sealed interface SideBySideRow {
    data class Hunk(val text: String) : SideBySideRow

    /** [left] is the old file's line, [right] the new file's; either may be absent (blank cell). */
    data class Lines(val left: DiffLine?, val right: DiffLine?) : SideBySideRow
}

/**
 * Lays a unified diff out in two columns.
 *
 * Context lines appear on both sides. A *run* of removals followed by a run of additions (what a
 * modified block looks like in unified form: `-A -B +C +D`) is paired by position - A/C, B/D - with
 * the shorter side padded by blank cells. The earlier implementation only paired a removal with an
 * addition when they were adjacent lines, so any block of two or more changed lines mis-paired
 * (B beside C) and left stragglers.
 */
fun alignSideBySide(lines: List<DiffLine>): List<SideBySideRow> {
    val out = ArrayList<SideBySideRow>(lines.size)
    val removed = ArrayList<DiffLine>()
    val added = ArrayList<DiffLine>()

    fun flush() {
        val rows = maxOf(removed.size, added.size)
        for (i in 0 until rows) {
            out.add(SideBySideRow.Lines(removed.getOrNull(i), added.getOrNull(i)))
        }
        removed.clear()
        added.clear()
    }

    for (line in lines) {
        when (line.type) {
            DiffLineType.REMOVED -> removed.add(line)
            DiffLineType.ADDED -> added.add(line)
            DiffLineType.CONTEXT -> {
                flush()
                out.add(SideBySideRow.Lines(line, line))
            }
            DiffLineType.HUNK -> {
                flush()
                out.add(SideBySideRow.Hunk(line.text))
            }
        }
    }
    flush()
    return out
}

@Composable
internal fun SideBySideRowView(
    row: SideBySideRow,
    lang: CodeLanguage,
    syntax: SyntaxColorSet,
    modifier: Modifier = Modifier,
) {
    when (row) {
        is SideBySideRow.Hunk -> Text(
            row.text,
            style = MaterialTheme.code.tiny,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.gitColors.diffHunkBackground)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
        is SideBySideRow.Lines -> Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            DiffHalf(row.left, useOldNumber = true, lang = lang, syntax = syntax, modifier = Modifier.weight(1f).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            DiffHalf(row.right, useOldNumber = false, lang = lang, syntax = syntax, modifier = Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun DiffHalf(
    line: DiffLine?,
    useOldNumber: Boolean,
    lang: CodeLanguage,
    syntax: SyntaxColorSet,
    modifier: Modifier,
) {
    val git = MaterialTheme.gitColors
    val background = when {
        line == null -> git.diffGutterBackground
        line.type == DiffLineType.REMOVED -> git.diffRemovedBackground
        line.type == DiffLineType.ADDED -> git.diffAddedBackground
        else -> Color.Transparent
    }
    val number = if (useOldNumber) line?.oldLineNo else line?.newLineNo

    Row(modifier.background(background)) {
        Text(
            number?.toString().orEmpty(),
            style = MaterialTheme.code.tiny,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(30.dp).padding(end = 4.dp, top = 1.dp),
        )
        if (line != null) {
            Text(
                text = highlightText(line.text, lang, syntax),
                color = syntax.plain,
                style = MaterialTheme.code.small,
                modifier = Modifier.weight(1f).padding(end = 6.dp),
            )
        } else {
            Box(Modifier.weight(1f))
        }
    }
}
