package app.twig

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.twig.data.Node
import app.twig.data.Store
import app.twig.data.TextFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StoreTest {
    private val root get() = Store.root

    @Before
    fun setup() {
        Store.awaitLoaded()
    }

    private fun mk(vararg texts: String, parent: Node = root): List<Node> =
        texts.map { Store.create(parent, parent.children.size, it) }

    private fun names(p: Node) = p.children.joinToString(",") { it.plain }

    @Test
    fun threeLevelsDeep() {
        val (a) = mk("a")
        val (b) = mk("b", parent = a)
        val (c) = mk("c", parent = b)
        val (loose) = mk("loose")
        assertEquals(3, Store.depth(c))
        assertTrue(Store.canHold(b, listOf(loose)))
        assertFalse(Store.canHold(c, listOf(loose))) // a fourth level
        assertFalse(Store.canHold(b, listOf(a))) // into itself
        val (list) = mk("list")
        mk("item", parent = list)
        assertTrue(Store.canHold(a, listOf(list)))
        assertFalse(Store.canHold(b, listOf(list))) // its item would be at level 4
        assertFalse(Store.move(listOf(list), b))
        assertSame(root, list.parent)
        assertNull(Store.notice)
    }

    @Test
    fun batchMoveKeepsOrderAndUndoes() {
        val (p, dest) = mk("p", "dest")
        val (x1, x2, x3, x4) = mk("x1", "x2", "x3", "x4", parent = p)
        mk("d1", parent = dest)
        assertTrue(Store.move(listOf(x2, x4), dest))
        assertEquals("x1,x3", names(p))
        assertEquals("d1,x2,x4", names(dest))
        assertEquals("Moved 2 to dest", Store.notice!!.message)
        Store.notice!!.undo()
        assertEquals("x1,x2,x3,x4", names(p))
        assertEquals("d1", names(dest))
        assertTrue(Store.move(listOf(x1), dest))
        assertEquals("Moved to dest", Store.notice!!.message)
        assertSame(x3, p.children[1])
    }

    @Test
    fun batchDeleteUndoes() {
        val (p) = mk("p")
        val (x1, _, x3) = mk("x1", "x2", "x3", parent = p)
        mk("kid", parent = x3)
        Store.delete(listOf(x1, x3))
        assertEquals("x2", names(p))
        assertFalse(Store.isAlive(x3.children[0]))
        assertEquals("Deleted 2 bullets", Store.notice!!.message)
        Store.notice!!.undo()
        assertEquals("x1,x2,x3", names(p))
        assertTrue(Store.isAlive(x3.children[0]))
        Store.delete(listOf(x1))
        assertEquals("Deleted “x1”", Store.notice!!.message)
    }

    @Test
    fun deletingTheOpenListGoesUp() {
        val (a) = mk("a")
        val (b) = mk("b", parent = a)
        Store.zoom = b
        Store.delete(listOf(a))
        assertSame(root, Store.zoom)
    }

    @Test
    fun reorderAndReload() {
        val (a, b, c) = mk("a", "b", "c")
        assertTrue(Store.reorder(a, 2))
        assertFalse(Store.reorder(a, 2))
        assertEquals("b,c,a", names(root))
        a.url = "https://example.com"
        Store.toggleDone(c)
        Store.dirty(a)
        mk("b1", parent = b)
        Store.flushBlocking()
        Store.init(ApplicationProvider.getApplicationContext())
        Store.awaitLoaded()
        assertEquals("b,c,a", names(Store.root))
        val r = Store.root.children
        assertEquals("b1", names(r[0]))
        assertTrue(r[1].done)
        assertEquals("https://example.com", r[2].url)
    }

    @Test
    fun enterSplitsIntoSiblings() {
        val (a, z) = mk("hello world", "z")
        Store.onEnter(a, "hello", listOf("", ""), " world")
        assertEquals("hello, world,z", names(root))
        assertEquals(root.children[1].id, Store.focusReq!!.nodeId)
        // Enter at the very start leaves the text alone and adds a blank bullet above
        Store.onEnter(z, "", listOf("", ""), "z")
        assertEquals("hello, world,,z", names(root))
        // a pasted list becomes several bullets
        Store.onEnter(z, "z", listOf("", "one", "two"), "")
        assertEquals("hello, world,,z,one,two", names(root))
    }

    @Test
    fun leavingABulletTidiesIt() {
        val (a, empty) = mk("a", "")
        a.url = "  Example.com/x "
        Store.finishEditing(a)
        assertEquals("https://Example.com/x", a.url)
        a.url = " "
        Store.finishEditing(a)
        assertNull(a.url)
        Store.finishEditing(empty)
        assertFalse(Store.isAlive(empty))
        assertNull(Store.notice) // nothing to undo for an empty bullet
        // an empty list title stays: it's the page you're on
        val (list) = mk("")
        Store.zoom = list
        Store.finishEditing(list)
        assertTrue(Store.isAlive(list))
    }

    @Test
    fun backspaceInEmptyBullet() {
        val (a, b) = mk("a", "")
        assertTrue(Store.backspaceEmpty(b, a))
        assertFalse(Store.isAlive(b))
        assertFalse(Store.backspaceEmpty(a, null))
    }

    @Test
    fun textRoundTrip() {
        val (g, links) = mk("Groceries", "Links")
        val (eggs, _) = mk("Eggs", "Milk\nwhole", parent = g)
        Store.toggleDone(eggs)
        mk("Cheese", parent = eggs)
        Store.create(links, 0, "Twig", "https://github.com/84436/twig")
        Store.create(links, 1, "", "https://example.com")
        val text = TextFormat.export(root)
        assertEquals(
            "- Groceries\n\t- ~~Eggs~~\n\t\t- Cheese\n\t- Milk whole\n- Links\n\t- [Twig](https://github.com/84436/twig)\n\t- https://example.com\n",
            text,
        )
        Store.replaceAll(TextFormat.parse(text))
        assertEquals(text, TextFormat.export(Store.root))
    }

    @Test
    fun lenientParsing() {
        val items = TextFormat.parse(
            listOf(
                "* a", "  + b", "", "    1. c", "      - too deep", "    - [x] task done", "- [ ] task open",
                "no marker", "\t- tabbed", "- [Title](https://x.com/a)", "- ~~https://y.com~~",
            ).joinToString("\n")
        )
        assertEquals(
            listOf(
                "1 a", "2 b", "3 c", "3 too deep", "3 task done ✓", "1 task open", "1 no marker", "2 tabbed",
                "1 Title https://x.com/a", "1  https://y.com ✓",
            ),
            items.map { listOfNotNull("${it.depth} ${it.text}", it.url).joinToString(" ") + if (it.done) " ✓" else "" },
        )
    }

    @Test
    fun replaceAllSwapsEverything() {
        val (old) = mk("old")
        Store.zoom = old
        Store.replaceAll(TextFormat.parse("- new\n  - kid"))
        assertSame(root, Store.zoom)
        assertFalse(Store.isAlive(old))
        assertEquals("new", names(root))
        Store.flushBlocking()
        Store.init(ApplicationProvider.getApplicationContext())
        Store.awaitLoaded()
        assertEquals("new", names(Store.root))
        assertEquals("kid", names(Store.root.children[0]))
    }

    @Test
    fun shareTarget() {
        val (inbox) = mk("Inbox")
        assertNull(Store.shareTarget)
        Store.shareTarget = inbox
        assertSame(inbox, Store.shareTarget)
        val n = Store.addShared(inbox, "A page", "https://example.com/a")
        assertSame(inbox, n.parent)
        Store.delete(listOf(inbox))
        assertNull(Store.shareTarget) // gone: back to asking
        Store.shareTarget = null
        assertNull(Store.shareTarget)
        // a bullet on the third level can't take more
        val (a) = mk("a")
        val (b) = mk("b", parent = a)
        val (c) = mk("c", parent = b)
        assertSame(root, Store.addShared(c, "x", null).parent)
    }

    @Test
    fun searchRanksAndKeepsOrder() {
        val (a) = mk("Pasta night")
        mk("fresh pasta", "nothing", parent = a)
        Store.create(root, 1, "Recipe", "https://example.com/pasta")
        assertEquals(listOf("Pasta night", "fresh pasta", "Recipe"), Store.search("pasta").map { it.plain })
        assertEquals(listOf("fresh pasta"), Store.search("pasta fre").map { it.plain })
    }

    @Test
    fun seedsOnce() {
        Store.ensureSeeded()
        val welcome = root.children.first()
        assertEquals("Welcome to Twig", welcome.plain)
        assertTrue(welcome.children.size > 3)
        assertTrue(root.children[1].children[0].done)
        val n = Store.size
        Store.ensureSeeded()
        assertEquals(n, Store.size)
    }
}
