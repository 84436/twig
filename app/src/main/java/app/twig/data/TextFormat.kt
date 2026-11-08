package app.twig.data

import app.twig.share.LinkInfo

/**
 * The outline as plain text: Markdown bullets, one tab per level. Clipboard and file use it both ways.
 *
 *     - Groceries
 *     	- ~~Eggs~~                       completed
 *     	- [Twig](https://github.com/…)   bullet with a URL
 *     	- <https://example.com>          URL only
 *
 * Reading is lenient: any indentation, `*`/`+`/`1.` markers, `- [x]` tasks, lines without markers.
 * Anything nested deeper than [MAX_DEPTH] lands on the last level.
 */
object TextFormat {
    class Item(val text: String, val url: String?, val done: Boolean, val depth: Int)

    fun export(top: Node): String = buildString {
        fun visit(p: Node, depth: Int) {
            for (c in p.children) {
                repeat(depth) { append('\t') }
                append("- ").append(line(c)).append('\n')
                visit(c, depth + 1)
            }
        }
        visit(top, 0)
    }

    private fun line(n: Node): String {
        val text = n.plain.lines().joinToString(" ").trim()
        val url = n.url
        val s = when {
            url == null -> text
            text.isEmpty() -> "<$url>"
            else -> "[$text]($url)"
        }
        return if (n.done && s.isNotEmpty()) "~~$s~~" else s
    }

    private val marker = Regex("""^([-*+•]|\d+[.)])(\s+|$)""")
    private val task = Regex("""^\[([ xX])](\s+|$)""") // not "[x](url)"
    private val link = Regex("""^\[(.*)]\((\S+)\)$""")
    private val autolink = Regex("""^<(\S+)>$""")

    fun parse(text: String): List<Item> {
        val out = ArrayList<Item>()
        val indents = ArrayList<Int>() // indentation of the current item's ancestors and itself
        for (raw in text.lines()) {
            val body = raw.trim()
            if (body.isEmpty()) continue
            val w = raw.takeWhile { it.isWhitespace() }.sumOf { if (it == '\t') 4 else 1 }
            while (indents.isNotEmpty() && indents.last() >= w) indents.removeAt(indents.lastIndex)
            if (indents.size < MAX_DEPTH) indents.add(w)
            out.add(item(body, indents.size))
        }
        return out
    }

    private fun item(body: String, depth: Int): Item {
        var s = marker.replaceFirst(body, "")
        var done = false
        task.find(s)?.let {
            done = it.groupValues[1] != " "
            s = s.substring(it.value.length)
        }
        if (s.length > 4 && s.startsWith("~~") && s.endsWith("~~")) {
            done = true
            s = s.substring(2, s.length - 2)
        }
        link.matchEntire(s)?.let { return Item(it.groupValues[1].trim(), it.groupValues[2], done, depth) }
        autolink.matchEntire(s)?.let { return Item("", it.groupValues[1], done, depth) }
        if (LinkInfo.findUrl(s) == s) return Item("", s, done, depth)
        return Item(s.trim(), null, done, depth)
    }
}
