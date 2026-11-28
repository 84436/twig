package app.twig.data

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em

/**
 * Bold is plain Markdown in the text (`**like this**`), so it survives export to Obsidian.
 * On screen the markers are hidden and the words are bold; while editing, the markers show (dimmed),
 * the way Obsidian shows them on the line you're on.
 */
object Markup {
    private val bold = Regex("""\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL)
    private val boldStyle = SpanStyle(fontWeight = FontWeight.Bold)

    /** The text without markers, for labels and sorting. */
    fun plain(s: CharSequence): String = bold.replace(s, "$1")

    /** The text as shown when not editing: markers gone, bold applied. */
    fun styled(s: String): AnnotatedString = buildAnnotatedString {
        var at = 0
        for (m in bold.findAll(s)) {
            append(s, at, m.range.first)
            val start = length
            append(m.groupValues[1])
            addStyle(boldStyle, start, length)
            at = m.range.last + 1
        }
        append(s, at, s.length)
    }

    /**
     * For the bullet's text field. While [editing] says so, the markers show, dimmed. [editing] is
     * read as state, so the field keeps one transformation: a new one would restart the keyboard.
     */
    fun output(editing: () -> Boolean) = OutputTransformation {
        val ms = bold.findAll(asCharSequence()).toList()
        if (editing()) {
            val dim = SpanStyle(fontSize = 0.8.em, fontWeight = FontWeight.Normal)
            for (m in ms) {
                addStyle(boldStyle, m.range.first, m.range.last + 1)
                addStyle(dim, m.range.first, m.range.first + 2)
                addStyle(dim, m.range.last - 1, m.range.last + 1)
            }
        } else {
            for (m in ms.asReversed()) {
                replace(m.range.last - 1, m.range.last + 1, "")
                replace(m.range.first, m.range.first + 2, "")
            }
            ms.forEachIndexed { i, m ->
                val start = m.range.first - 4 * i
                addStyle(boldStyle, start, start + m.groupValues[1].length)
            }
        }
    }

    /** Bold the selected words, or, if the selection touches bold, un-bold all of that. Nothing selected: nothing to do. */
    fun toggleBold(t: TextFieldState) {
        val s = t.text.toString()
        var a = t.selection.min
        var b = t.selection.max
        while (a < b && s[a].isWhitespace()) a++
        while (b > a && s[b - 1].isWhitespace()) b--
        if (a == b) return
        val hits = bold.findAll(s).filter { it.range.first < b && it.range.last >= a }.toList()
        t.edit {
            if (hits.isEmpty()) {
                replace(b, b, "**")
                replace(a, a, "**")
                selection = TextRange(a + 2, b + 2)
            } else {
                for (m in hits.asReversed()) {
                    replace(m.range.last - 1, m.range.last + 1, "")
                    replace(m.range.first, m.range.first + 2, "")
                }
                selection = TextRange(hits.first().range.first, hits.last().range.last + 1 - 4 * hits.size)
            }
        }
    }
}
