package app.twig

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.twig.data.Backup
import app.twig.data.FlatRow
import app.twig.data.Node
import app.twig.data.Store
import app.twig.ui.DragState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StoreTest {
    private val root get() = Store.root

    @Before
    fun setup() {
        Store.awaitLoaded()
    }

    private fun outline(top: Node = root) =
        Store.flatten(top).joinToString("\n") { "  ".repeat(it.depth) + it.node.plain }

    private fun mk(vararg texts: String, parent: Node = root): List<Node> =
        texts.map { Store.create(parent, parent.children.size, it) }

    @Test
    fun indentOutdent() {
        val (a, b, c) = mk("a", "b", "c")
        assertTrue(Store.indent(b))
        assertTrue(Store.indent(c))
        assertEquals("a\n  b\n  c", outline())
        assertTrue(Store.indent(c)) // under b now
        assertEquals("a\n  b\n    c", outline())
        assertTrue(Store.outdent(b))
        assertEquals("a\nb\n  c", outline())
        assertFalse(Store.indent(a)) // nothing above
        assertFalse(Store.outdent(a)) // already top level
    }

    @Test
    fun indentIntoCollapsedExpandsIt() {
        val (a, b) = mk("a", "b")
        Store.create(a, 0, "a1")
        Store.toggleCollapsed(a)
        assertEquals("a\nb", outline())
        Store.indent(b)
        assertFalse(a.collapsed)
        assertEquals("a\n  a1\n  b", outline())
    }

    @Test
    fun enterSplitsAndFocusesNewItem() {
        val (a) = mk("hello world")
        Store.onEnter(a, "hello", listOf("", ""), " world")
        assertEquals("hello\n world", outline())
        val req = Store.focusReq!!
        assertEquals(root.children[1].id, req.nodeId)
        assertEquals(0, req.cursor)
    }

    @Test
    fun enterAtStartOpensLineAbove() {
        val (a) = mk("abc")
        Store.onEnter(a, "", listOf("", ""), "abc")
        assertEquals("\nabc", outline())
        assertSame(a, root.children[1])
    }

    @Test
    fun enterOnEmptyNestedItemOutdents() {
        val (a) = mk("a")
        val e = Store.create(a, 0, "")
        Store.onEnter(e, "", listOf("", ""), "")
        assertSame(root, e.parent)
        assertEquals("a\n", outline())
    }

    @Test
    fun enterOnItemWithChildrenMakesFirstChild() {
        val (a) = mk("a")
        Store.create(a, 0, "child")
        Store.onEnter(a, "a", listOf("", ""), "")
        assertEquals("a\n  \n  child", outline())
    }

    @Test
    fun bareUrlBecomesLinkWhenLeavingItem() {
        val (a, b, c) = mk("https://example.com/page?utm_source=x", "see https://example.com", "plain")
        Store.finishEditing(a)
        assertEquals("https://example.com/page", a.url)
        assertEquals("", a.plain)
        Store.finishEditing(b) // text around the URL: leave it alone
        assertNull(b.url)
        Store.finishEditing(c)
        assertNull(c.url)
    }

    @Test
    fun tappingAnOldBareUrlItemDoesNotConvertIt() {
        val (a) = mk("https://example.com")
        Store.finishEditing(a, edited = false)
        assertNull(a.url)
    }

    @Test
    fun typedLinksAreNormalised() {
        val (a, b, c) = mk("a", "b", "c")
        a.url = "Https://Example.com/X"
        Store.finishEditing(a)
        assertEquals("https://Example.com/X", a.url)
        b.url = "example.com/page"
        Store.finishEditing(b)
        assertEquals("https://example.com/page", b.url)
        c.url = "mailto:me@example.com"
        Store.finishEditing(c)
        assertEquals("mailto:me@example.com", c.url)
        val (d) = mk("Https://auto.example/caps")
        Store.finishEditing(d)
        assertEquals("https://auto.example/caps", d.url)
    }

    @Test
    fun moveIntoTopOrBottom() {
        val (a, b) = mk("a", "b")
        val (x, y) = mk("x", "y", parent = b)
        assertTrue(Store.moveInto(a, b, atEnd = true))
        assertEquals(listOf(x, y, a), b.children)
        assertTrue(Store.moveInto(a, b, atEnd = false))
        assertEquals(listOf(a, x, y), b.children)
        assertFalse(Store.moveInto(a, b, atEnd = false))
        Store.notice!!.action!!.invoke() // undo the last real move
        assertEquals(listOf(x, y, a), b.children)
    }

    @Test
    fun completingWithHideOnOffersUndo() {
        val (a) = mk("a")
        Store.hideCompleted = true
        Store.toggleDone(a)
        assertEquals("Undo", Store.notice!!.actionLabel)
        Store.notice!!.action!!.invoke()
        assertFalse(a.done)
        assertEquals("a", outline())
    }

    @Test
    fun oldTipsAreRewritten() {
        val (old) = mk("Quick nest: swipe a bullet right (indent) or left (outdent).")
        Store.setMeta("seeded", "1")
        Store.ensureSeeded()
        assertTrue(old.plain.startsWith("Swipe an item to the right"))
    }

    @Test
    fun markdownBlankItemsStayBlank() {
        val (holder, _) = Backup.import("- a\n- \n- b\n", null)
        assertEquals(listOf("a", "", "b"), holder.children.map { it.plain })
    }

    @Test
    fun zipBackupCarriesImages() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val img = app.twig.data.Attachments.file(ctx, "pic.png")
        img.writeBytes(byteArrayOf(1, 2, 3))
        val (a) = mk("photo")
        a.attach = "pic.png"
        val out = java.io.ByteArrayOutputStream()
        Backup.writeZip(ctx, out, Backup.toJson(root), Backup.attachmentsUnder(root))
        img.delete()
        val bytes = out.toByteArray()
        assertTrue(Backup.isZip(bytes))
        val json = Backup.readZip(ctx, bytes)!!
        assertTrue(img.exists())
        val (holder, count) = Backup.import(json, "b.zip")
        assertEquals(1, count)
        assertEquals("pic.png", holder.children[0].attach)
    }

    @Test
    fun emptyLinkIsDroppedWhenLeavingItem() {
        val (a) = mk("title")
        Store.editLink(a)
        assertEquals("", a.url)
        Store.finishEditing(a)
        assertNull(a.url)
        a.url = "  https://x.y  "
        Store.finishEditing(a)
        assertEquals("https://x.y", a.url)
    }

    @Test
    fun pasteMultipleLines() {
        val (a) = mk("x")
        Store.onEnter(a, "x", listOf("1", "2", "3"), "y")
        assertEquals("x1\n2\n3y", outline())
        assertEquals(1, Store.focusReq!!.cursor)
    }

    @Test
    fun backspaceOnEmptyDeletes() {
        val (a, b) = mk("a", "")
        assertTrue(Store.backspaceEmpty(b, a))
        assertEquals("a", outline())
        assertFalse(Store.backspaceEmpty(a, null))
    }

    @Test
    fun moveBySwapsSiblings() {
        val (a, b, c) = mk("a", "b", "c")
        Store.moveBy(c, -1)
        assertEquals("a\nc\nb", outline())
        Store.moveBy(a, 1)
        assertEquals("c\na\nb", outline())
        assertFalse(Store.moveBy(b, 1))
    }

    @Test
    fun dropTargetMath() {
        val (a, b, c) = mk("a", "b", "c")
        val a1 = Store.create(a, 0, "a1")
        val rows = Store.flatten(root) // a, a1, b, c
        // move c to sit right after a1 at depth 1 -> child of a after a1
        val preview = listOf(rows[0], rows[1], rows[3], rows[2])
        val (p1, after1) = DragState.dropTarget(preview, 2, 1, root)
        assertSame(a, p1)
        assertSame(a1, after1)
        // depth 2 -> first child of a1
        val (p2, after2) = DragState.dropTarget(preview, 2, 2, root)
        assertSame(a1, p2)
        assertNull(after2)
        // depth 0 -> top level after a
        val (p3, after3) = DragState.dropTarget(preview, 2, 0, root)
        assertSame(root, p3)
        assertSame(a, after3)
        // to the very top
        val top = listOf(rows[3], rows[0], rows[1], rows[2])
        val (p4, after4) = DragState.dropTarget(top, 0, 0, root)
        assertSame(root, p4)
        assertNull(after4)
        Store.moveTo(c, p1, after1)
        assertEquals("a\n  a1\n  c\nb", outline())
        assertFalse(Store.moveTo(a, a1, null)) // into own child
        assertFalse(Store.moveTo(c, a, a1)) // already there
    }

    @Test
    fun deleteAndUndo() {
        val (a, b) = mk("a", "b")
        Store.create(a, 0, "a1")
        Store.delete(a)
        assertEquals("b", outline())
        assertEquals("Undo", Store.notice!!.actionLabel)
        Store.notice!!.action!!.invoke()
        assertEquals("a\n  a1\nb", outline())
    }

    @Test
    fun deletingZoomedItemZoomsOut() {
        val (a) = mk("a")
        val a1 = Store.create(a, 0, "a1")
        Store.zoom = a1
        Store.delete(a)
        assertSame(root, Store.zoom)
    }

    @Test
    fun searchFindsAllTermsAndRanks() {
        mk("Fresh pasta recipe", "pasta salad", "Video about rockets")
        Store.create(root, 0, "Rocket", url = "https://youtu.be/xyz")
        assertEquals(listOf("pasta salad", "Fresh pasta recipe"), Store.search("pasta").map { it.plain })
        assertEquals(listOf("Fresh pasta recipe"), Store.search("PASTA fresh").map { it.plain })
        assertEquals(listOf("Rocket"), Store.search("youtu").map { it.plain })
        assertTrue(Store.search("   ").isEmpty())
    }

    @Test
    fun ranksSurviveManyInsertsInTheSameGap() {
        val (a, b) = mk("a", "b")
        repeat(80) { Store.create(root, 1, "x$it") }
        val ranks = root.children.map { it.rank }
        assertEquals(ranks.sorted(), ranks)
        assertEquals(ranks.size, ranks.toSet().size)
        assertSame(a, root.children.first())
        assertSame(b, root.children.last())
    }

    @Test
    fun hideCompleted() {
        val (a, b) = mk("a", "b")
        Store.toggleDone(a)
        Store.hideCompleted = true
        assertEquals("b", outline())
        Store.hideCompleted = false
        assertEquals("a\nb", outline())
    }

    @Test
    fun persistsAcrossRestart() {
        val (a, b) = mk("first", "second")
        val c = Store.create(a, 0, "child", url = "https://example.com", note = "Example")
        Store.toggleDone(c)
        Store.toggleCollapsed(a)
        b.text.edit { replace(0, length, "second edited") }
        Store.touched(b)
        Store.setMeta("x", "y")
        Store.flushBlocking()

        Store.init(ApplicationProvider.getApplicationContext())
        Store.awaitLoaded()
        assertEquals("first\nsecond edited", outline())
        val a2 = Store.root.children[0]
        assertTrue(a2.collapsed)
        val c2 = a2.children.single()
        assertEquals("child", c2.plain)
        assertTrue(c2.done)
        assertEquals("https://example.com", c2.url)
        assertEquals("Example", c2.note)
        assertEquals("y", Store.meta("x"))
        // new ids don't collide with loaded ones
        val n = Store.create(Store.root, 0, "new")
        assertTrue(n.id > c2.id)
    }

    @Test
    fun deletesPersist() {
        val (a, _) = mk("a", "b")
        Store.flushBlocking()
        Store.delete(a)
        Store.flushBlocking()
        Store.init(ApplicationProvider.getApplicationContext())
        Store.awaitLoaded()
        assertEquals("b", outline())
    }

    @Test
    fun seedingHappensOnce() {
        Store.ensureSeeded()
        val n = Store.size
        assertTrue(n > 5)
        Store.ensureSeeded()
        assertEquals(n, Store.size)
        assertEquals("Inbox", Store.inbox().plain)
    }

    @Test
    fun importMarkdown() {
        val (holder, count) = Backup.import(
            "- Groceries\n  - [ ] milk\n  - [x] eggs\n  - ~~bread~~\n- [Twig](https://example.com)\n\tdeep\n", "list.md"
        )
        assertEquals(6, count)
        assertEquals("Groceries\n  milk\n  eggs\n  bread\nTwig\n  deep", outline(holder))
        val kids = holder.children[0].children
        assertFalse(kids[0].done)
        assertTrue(kids[1].done)
        assertTrue(kids[2].done)
        assertEquals("https://example.com", holder.children[1].url)
    }

    @Test
    fun importOpml() {
        val opml = """<?xml version="1.0"?><opml version="2.0"><head><title>x</title></head><body>
            <outline text="Projects"><outline text="Twig" _complete="true"/><outline text="Other" _note="a note"/></outline>
            <outline text="Solo"/></body></opml>"""
        val (holder, count) = Backup.import(opml, null)
        assertEquals(5, count)
        assertEquals("Projects\n  Twig\n  Other\n    a note\nSolo", outline(holder))
        assertTrue(holder.children[0].children[0].done)
    }

    @Test
    fun jsonRoundTrip() {
        val (a) = mk("a")
        Store.create(a, 0, "a1", url = "https://x.y")
        val json = Backup.toJson(root)
        val (holder, count) = Backup.import(json, "backup.json")
        assertEquals(2, count)
        assertEquals("a\n  a1", outline(holder))
        assertEquals("https://x.y", holder.children[0].children[0].url)
    }

    @Test
    fun markdownExport() {
        val (a) = mk("Trip")
        val t = Store.create(a, 0, "Book flight")
        Store.toggleDone(t)
        Store.create(a, 1, "Guide", url = "https://ex.com")
        assertEquals("- Trip\n  - ~~Book flight~~\n  - [Guide](https://ex.com)\n", Backup.toMarkdown(root))
        // and back in
        val (holder, _) = Backup.import(Backup.toMarkdown(root), null)
        assertTrue(holder.children[0].children[0].done)
        assertEquals("https://ex.com", holder.children[0].children[1].url)
    }

    @Test
    fun flushIsDebounced() {
        mk("a")
        shadowOf(Looper.getMainLooper()).idle()
        Store.flushBlocking()
        Store.init(ApplicationProvider.getApplicationContext())
        Store.awaitLoaded()
        assertEquals("a", outline())
    }

    @Suppress("unused")
    private fun rows(vararg r: FlatRow) = r.toList()
}
