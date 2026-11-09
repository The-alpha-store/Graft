package dev.graft.app.ui.screens.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders a practical subset of Markdown — headers, bold/italic, inline and
 * fenced code, blockquotes, bullet/numbered lists, links (shown styled but
 * not clickable), and horizontal rules. This is not a full CommonMark
 * implementation; it's the subset that covers the overwhelming majority of
 * real READMEs and notes, which is what this preview is for.
 */
@Composable
fun MarkdownPreview(source: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        val lines = source.split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.isBlank() -> Spacer(Modifier.height(8.dp))

                line.startsWith("```") -> {
                    val fenceLang = line.removePrefix("```").trim()
                    val codeLines = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].startsWith("```")) {
                        codeLines.add(lines[i]); i++
                    }
                    CodeBlock(codeLines.joinToString("\n"), fenceLang)
                }

                line.startsWith("#### ") -> MdHeading(line.removePrefix("#### "), MaterialTheme.typography.titleSmall)
                line.startsWith("### ") -> MdHeading(line.removePrefix("### "), MaterialTheme.typography.titleMedium)
                line.startsWith("## ") -> MdHeading(line.removePrefix("## "), MaterialTheme.typography.titleLarge)
                line.startsWith("# ") -> MdHeading(line.removePrefix("# "), MaterialTheme.typography.headlineSmall)

                line.trim() == "---" || line.trim() == "***" -> {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                }

                // A <p> ... </p> block used purely to wrap shields.io-style badges
                // (<img alt="..." src="...">). Full HTML rendering is out of scope for
                // this preview, but this specific pattern is common enough in READMEs
                // to be worth recognizing — render each badge's alt text as a small chip
                // instead of dumping the raw tag soup as text.
                line.trimStart().startsWith("<p>") || line.trimStart().startsWith("<p ") -> {
                    val block = StringBuilder(line)
                    while (i < lines.size && !lines[i].contains("</p>")) {
                        i++
                        if (i < lines.size) block.append(' ').append(lines[i])
                    }
                    val alts = Regex("""alt="([^"]*)"""").findAll(block.toString()).map { it.groupValues[1] }.toList()
                    if (alts.isNotEmpty()) {
                        Row(Modifier.padding(vertical = 6.dp)) {
                            alts.forEach { alt ->
                                Box(
                                    Modifier
                                        .padding(end = 6.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(alt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    // A <p> block with no <img> badges inside (plain HTML wrapping real
                    // text) — strip the tags and show the text rather than showing nothing.
                    else {
                        val text = block.toString().replace(Regex("<[^>]*>"), "").trim()
                        if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // A line that's nothing but a stray HTML tag (e.g. a lone </p> that
                // wasn't caught above) — skip it rather than showing raw "<...>" text.
                Regex("""^\s*</?[a-zA-Z][^>]*>\s*$""").matches(line) -> { /* no-op */ }

                // A Markdown table: a "| a | b |" header row followed by a
                // "|---|---|" (dashes/colons only) separator row.
                line.trimStart().startsWith("|") && i + 1 < lines.size &&
                    Regex("""^\s*\|?[\s:|-]+\|?\s*$""").matches(lines[i + 1]) && lines[i + 1].contains('-') -> {
                    val headerCells = parseTableRow(line)
                    i += 2 // skip header + separator
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                        rows.add(parseTableRow(lines[i]))
                        i++
                    }
                    i-- // outer loop will i++ again
                    MdTable(headerCells, rows)
                }


                line.trimStart().startsWith("> ") -> {
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Box4(color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.width(8.dp))
                        Text(inlineMarkdown(line.trimStart().removePrefix("> ")), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    Row(Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                        Text("•  ", fontWeight = FontWeight.Bold)
                        Text(inlineMarkdown(line.trimStart().removePrefix("- ").removePrefix("* ")))
                    }
                }

                Regex("""^\d+\.\s""").containsMatchIn(line.trimStart()) -> {
                    Row(Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                        Text(inlineMarkdown(line.trimStart()))
                    }
                }

                else -> Text(
                    inlineMarkdown(line),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            i++
        }
    }
}

/** Splits a "| a | b | c |" row into cells, tolerating missing leading/trailing pipes. */
private fun parseTableRow(row: String): List<String> =
    row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

@Composable
private fun MdTable(header: List<String>, rows: List<List<String>>) {
    val columnCount = header.size
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
    ) {
        TableRow(header, isHeader = true)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        rows.forEachIndexed { idx, row ->
            // Pad/truncate ragged rows to the header's column count rather than
            // crashing or silently misaligning cells.
            val cells = List(columnCount) { c -> row.getOrElse(c) { "" } }
            TableRow(cells, isHeader = false)
            if (idx != rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, isHeader: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEach { cell ->
            Box(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    inlineMarkdown(cell),
                    style = if (isHeader) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
                    fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun MdHeading(text: String, style: androidx.compose.ui.text.TextStyle) {
    Text(inlineMarkdown(text), style = style, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun Box4(color: Color) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(width = 3.dp, height = 20.dp).background(color, RoundedCornerShape(2.dp))
    )
}

@Composable
private fun CodeBlock(code: String, language: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        if (language.isNotBlank()) {
            Text(language, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(
            code,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
}

/** Inline emphasis: bold with **, italic with * or _, code with backticks. Applied within a single line/paragraph. */
@Composable
private fun inlineMarkdown(raw: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val builder = AnnotatedString.Builder()
    val pattern = Regex("""\*\*(.+?)\*\*|`([^`]+?)`|\*(.+?)\*|_(.+?)_|\[([^]]+)]\(([^)]+)\)""")
    var last = 0
    for (m in pattern.findAll(raw)) {
        if (m.range.first > last) builder.append(raw.substring(last, m.range.first))
        when {
            m.groupValues[1].isNotEmpty() -> builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            m.groupValues[2].isNotEmpty() -> builder.withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22808080))
            ) { append(m.groupValues[2]) }
            m.groupValues[3].isNotEmpty() -> builder.withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(m.groupValues[3]) }
            m.groupValues[4].isNotEmpty() -> builder.withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(m.groupValues[4]) }
            m.groupValues[5].isNotEmpty() -> builder.withStyle(
                SpanStyle(color = linkColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
            ) { append(m.groupValues[5]) }
        }
        last = m.range.last + 1
    }
    // No matches (or trailing text after the last match) — append what's left as plain text.
    if (last < raw.length) builder.append(raw.substring(last))
    return builder.toAnnotatedString()
}
