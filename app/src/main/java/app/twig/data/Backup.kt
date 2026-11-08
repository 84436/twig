package app.twig.data

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.OutputStream
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Export (JSON backup, Markdown) and import (JSON, OPML, Markdown / indented text). */
object Backup {

    fun toJson(top: Node): String {
        val o = JSONObject()
        o.put("app", "twig")
        o.put("version", 1)
        o.put("exported", System.currentTimeMillis())
        o.put("items", JSONArray().apply { top.children.forEach { put(nodeJson(it)) } })
        return o.toString(1)
    }

    private fun nodeJson(n: Node): JSONObject = JSONObject().apply {
        put("text", n.plain)
        n.url?.let { put("url", it) }
        n.note?.let { put("note", it) }
        if (n.done) put("done", true)
        if (n.collapsed) put("collapsed", true)
        put("created", n.created)
        put("modified", n.modified)
        n.attach?.let { put("attach", it) }
        if (n.children.isNotEmpty()) put("children", JSONArray().apply { n.children.forEach { put(nodeJson(it)) } })
    }

    fun toMarkdown(top: Node): String {
        val sb = StringBuilder()
        fun visit(p: Node, depth: Int) {
            for (c in p.children) {
                sb.append("  ".repeat(depth)).append("- ")
                val text = c.plain.replace("\n", " ")
                val url = c.url?.takeIf { it.isNotBlank() }
                val body = if (url != null) "[${text.ifBlank { url }}]($url)" else text
                // completed items are struck through, which round-trips through import
                if (c.done && body.isNotEmpty()) sb.append("~~").append(body).append("~~") else sb.append(body)
                sb.append('\n')
                visit(c, depth + 1)
            }
        }
        visit(top, 0)
        return sb.toString()
    }

    /** Every image file the tree under [top] points to. */
    fun attachmentsUnder(top: Node): List<String> {
        val out = ArrayList<String>()
        val stack = ArrayList<Node>(top.children)
        while (stack.isNotEmpty()) {
            val n = stack.removeAt(stack.size - 1)
            n.attach?.let { out.add(it) }
            stack.addAll(n.children)
        }
        return out
    }

    /** Full backup: the JSON plus the image files. Call off the main thread. */
    fun writeZip(ctx: Context, out: OutputStream, json: String, attachments: Collection<String>) {
        ZipOutputStream(out.buffered()).use { z ->
            z.putNextEntry(ZipEntry("twig.json"))
            z.write(json.toByteArray())
            z.closeEntry()
            for (name in attachments.toSet()) {
                val f = Attachments.file(ctx, name)
                if (!f.isFile) continue
                z.putNextEntry(ZipEntry("att/$name"))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    private val safeName = Regex("""^[A-Za-z0-9._-]{1,128}$""")

    /** Unpacks a backup zip: restores images, returns the JSON. Call off the main thread. */
    fun readZip(ctx: Context, bytes: ByteArray): String? {
        var json: String? = null
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name
                when {
                    e.isDirectory -> Unit
                    name == "twig.json" -> json = z.readBytes().toString(Charsets.UTF_8)
                    name.startsWith("att/") && safeName.matches(name.substring(4)) -> {
                        val f = Attachments.file(ctx, name.substring(4))
                        if (!f.exists()) f.outputStream().use { z.copyTo(it) }
                    }
                }
            }
        }
        return json
    }

    fun isZip(bytes: ByteArray) = bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    /** Imports under a new top-level item. Returns that item and how many were added. */
    fun import(content: String, fileName: String?): Pair<Node, Int> {
        val stamp = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())
        val holder = Store.create(Store.root, Store.root.children.size, "Imported $stamp" + (fileName?.let { " · $it" } ?: ""))
        val trimmed = content.trimStart()
        val count = when {
            trimmed.startsWith("{") -> importJson(trimmed, holder)
            trimmed.startsWith("<") -> importOpml(trimmed, holder)
            else -> importText(content, holder)
        }
        return holder to count
    }

    private fun importJson(s: String, into: Node): Int {
        val items = JSONObject(s).optJSONArray("items") ?: return 0
        var count = 0
        fun add(arr: JSONArray, parent: Node) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val n = Store.create(
                    parent, parent.children.size, o.optString("text"),
                    o.optString("url").ifEmpty { null }, o.optString("note").ifEmpty { null },
                    o.optString("attach").ifEmpty { null },
                )
                if (o.optBoolean("done")) n.done = true
                if (o.optBoolean("collapsed")) n.collapsed = true
                Store.dirty(n)
                count++
                o.optJSONArray("children")?.let { add(it, n) }
            }
        }
        add(items, into)
        return count
    }

    private fun importOpml(s: String, into: Node): Int {
        val p = Xml.newPullParser()
        p.setInput(StringReader(s))
        val stack = ArrayList<Node>()
        stack.add(into)
        var count = 0
        var inBody = false
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when (p.name) {
                    "body" -> inBody = true
                    "outline" -> if (inBody) {
                        val parent = stack.last()
                        val text = p.getAttributeValue(null, "text") ?: p.getAttributeValue(null, "title") ?: ""
                        val url = p.getAttributeValue(null, "url") ?: p.getAttributeValue(null, "htmlUrl")
                        val done = p.getAttributeValue(null, "_complete") == "true"
                        val n = Store.create(parent, parent.children.size, text, url = url)
                        if (done) {
                            n.done = true
                            Store.dirty(n)
                        }
                        p.getAttributeValue(null, "_note")?.takeIf { it.isNotBlank() }?.let {
                            Store.create(n, 0, it)
                            count++
                        }
                        count++
                        stack.add(n)
                    }
                }
                XmlPullParser.END_TAG -> if (p.name == "outline" && inBody && stack.size > 1) stack.removeAt(stack.size - 1)
            }
        }
        return count
    }

    private val bulletRe = Regex("""^([-*+•]|\d+[.)])(\s+|$)""")
    private val todoRe = Regex("""^\[([ xX])]\s*""")
    private val strikeRe = Regex("""^~~(.*)~~$""")
    private val mdLinkRe = Regex("""^\[(.*)]\((https?://\S+)\)$""")

    private fun importText(s: String, into: Node): Int {
        // stack of (indent, node)
        val stack = ArrayList<Pair<Int, Node>>()
        var count = 0
        for (raw in s.lines()) {
            if (raw.isBlank()) continue
            var indent = 0
            var i = 0
            while (i < raw.length && (raw[i] == ' ' || raw[i] == '\t')) {
                indent += if (raw[i] == '\t') 4 else 1
                i++
            }
            var text = raw.substring(i).trimEnd()
            text = text.replaceFirst(bulletRe, "")
            var done = false
            todoRe.find(text)?.let {
                done = it.groupValues[1] != " "
                text = text.substring(it.range.last + 1)
            }
            strikeRe.find(text)?.let {
                done = true
                text = it.groupValues[1]
            }
            var url: String? = null
            mdLinkRe.find(text)?.let {
                url = it.groupValues[2]
                text = it.groupValues[1]
            }
            while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeAt(stack.size - 1)
            val parent = stack.lastOrNull()?.second ?: into
            val n = Store.create(parent, parent.children.size, text, url)
            if (done) {
                n.done = true
                Store.dirty(n)
            }
            stack.add(indent to n)
            count++
        }
        return count
    }
}
