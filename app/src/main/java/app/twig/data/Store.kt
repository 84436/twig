package app.twig.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import app.twig.share.LinkInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** A snackbar message, optionally with an action (e.g. Undo). */
class Notice(val message: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

const val FIELD_TEXT = 0
const val FIELD_URL = 1

/** A request to put the cursor in a row. Plain class on purpose: every request is distinct. */
class FocusReq(val nodeId: Long, val cursor: Int, val field: Int = FIELD_TEXT)

/**
 * The whole outline, in memory. Everything here runs on the main thread except the
 * initial load (background thread) and SQLite writes (single writer thread).
 */
object Store {
    const val ROOT_ID = 0L
    private const val TAG = "Twig"

    lateinit var appContext: Context
        private set
    private lateinit var db: Db
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "twig-db") }

    @Volatile private var loadLatch = CountDownLatch(1)
    @Volatile private var loadError: Throwable? = null

    var main: Handler = Handler(Looper.getMainLooper())
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var root: Node = newRoot()
        private set
    private val byId = HashMap<Long, Node>()
    private var nextId = 1L
    private val meta = HashMap<String, String>()

    /** Bumped on every structural change; the outline re-flattens when it changes. */
    var version by mutableIntStateOf(0)
        private set

    private var zoomState by mutableStateOf(root)
    var zoom: Node
        get() = zoomState
        set(v) {
            if (zoomState === v) return
            zoomState = v
            setMeta("zoom", v.id.toString())
        }

    private var hideState by mutableStateOf(false)
    var hideCompleted: Boolean
        get() = hideState
        set(v) {
            hideState = v
            setMeta("hideCompleted", if (v) "1" else "0")
            bump()
        }

    var focusReq by mutableStateOf<FocusReq?>(null)
    var focusedId by mutableStateOf<Long?>(null)
    var highlightId by mutableStateOf<Long?>(null)
    var notice by mutableStateOf<Notice?>(null)
    val focusRequesters = HashMap<Long, FocusRequester>()
    /** Scroll position per zoom level (index, offset), so zooming out lands where you were. */
    val scrollMemory = HashMap<Long, Pair<Int, Int>>()

    private val pendingUpserts = LinkedHashMap<Long, Node>()
    private val pendingDeletes = HashSet<Long>()
    private val pendingMeta = HashMap<String, String>()
    private var flushScheduled = false
    private val flushRunnable = Runnable {
        flushScheduled = false
        flush()
    }

    private fun newRoot() = Node(ROOT_ID, null, 0.0, "", null, null, KIND_BULLET, false, false, 0, 0, null)

    // ---------------------------------------------------------------- lifecycle

    /** Starts loading on a background thread. Call [awaitLoaded] before touching the tree. */
    fun init(ctx: Context, dbName: String? = "twig.db") {
        appContext = ctx.applicationContext
        main = Handler(Looper.getMainLooper())
        db = Db(appContext, dbName)
        val latch = CountDownLatch(1)
        loadLatch = latch
        loadError = null
        pendingUpserts.clear()
        pendingDeletes.clear()
        pendingMeta.clear()
        focusRequesters.clear()
        scrollMemory.clear()
        focusReq = null
        focusedId = null
        highlightId = null
        notice = null
        flushScheduled = false
        thread(name = "twig-load", priority = Thread.MAX_PRIORITY) {
            try {
                load()
            } catch (t: Throwable) {
                loadError = t
            } finally {
                latch.countDown()
            }
        }
    }

    fun awaitLoaded() {
        loadLatch.await()
        loadError?.let { throw IllegalStateException("Could not open the notes database", it) }
    }

    private fun load() {
        val rows = db.loadAll()
        val m = db.loadMeta()
        val r = newRoot()
        val map = HashMap<Long, Node>(rows.size * 2 + 16)
        var maxId = 0L
        for (row in rows) {
            map[row.id] = Node(
                row.id, null, row.rank, row.text, row.url?.takeIf { it.isNotBlank() }, row.note, row.kind,
                row.done, row.collapsed, row.created, row.modified, row.attach,
            )
            if (row.id > maxId) maxId = row.id
        }
        for (row in rows) {
            val n = map.getValue(row.id)
            val p = map[row.parent]?.takeIf { it !== n } ?: r
            n.parent = p
            p.children.add(n)
        }
        // Anything not reachable from the root (a parent cycle) goes back to the top level.
        val reachable = HashSet<Long>(map.size * 2 + 16)
        fun mark(from: Node) {
            val stack = ArrayList<Node>()
            stack.add(from)
            while (stack.isNotEmpty()) {
                val x = stack.removeAt(stack.size - 1)
                for (c in x.children) if (reachable.add(c.id)) stack.add(c)
            }
        }
        mark(r)
        if (reachable.size < map.size) {
            for (n in map.values) {
                if (n.id in reachable) continue
                n.parent?.children?.remove(n)
                n.parent = r
                r.children.add(n)
                reachable.add(n.id)
                mark(n)
            }
        }
        r.children.sortBy { it.rank }
        for (n in map.values) if (n.children.size > 1) n.children.sortBy { it.rank }

        root = r
        byId.clear()
        byId.putAll(map)
        nextId = maxId + 1
        meta.clear()
        meta.putAll(m)
        hideState = m["hideCompleted"] == "1"
        zoomState = m["zoom"]?.toLongOrNull()?.let { map[it] } ?: r

        try {
            Attachments.cleanup(appContext, map.values.mapNotNullTo(HashSet()) { it.attach })
        } catch (t: Throwable) {
            Log.w(TAG, "attachment cleanup failed", t)
        }
    }

    // ---------------------------------------------------------------- persistence

    fun dirty(n: Node) {
        if (n === root) return
        pendingDeletes.remove(n.id)
        pendingUpserts[n.id] = n
        scheduleFlush()
    }

    /** User edited the text of [n]. */
    fun touched(n: Node) {
        n.modified = System.currentTimeMillis()
        dirty(n)
    }

    private fun markDeleted(ids: Collection<Long>) {
        for (id in ids) {
            pendingUpserts.remove(id)
            pendingDeletes.add(id)
        }
        scheduleFlush()
    }

    fun meta(k: String): String? = meta[k]

    fun setMeta(k: String, v: String) {
        if (meta[k] == v) return
        meta[k] = v
        pendingMeta[k] = v
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        main.postDelayed(flushRunnable, 350)
    }

    fun flush() {
        if (flushScheduled) {
            main.removeCallbacks(flushRunnable)
            flushScheduled = false
        }
        if (pendingUpserts.isEmpty() && pendingDeletes.isEmpty() && pendingMeta.isEmpty()) return
        val ups = pendingUpserts.values.map { it.toRow() }
        val dels = pendingDeletes.toList()
        val m = HashMap(pendingMeta)
        pendingUpserts.clear()
        pendingDeletes.clear()
        pendingMeta.clear()
        val target = db
        writer.execute {
            try {
                target.write(ups, dels, m)
            } catch (t: Throwable) {
                Log.e(TAG, "write failed", t)
            }
        }
    }

    /** For tests and backups: flush and wait for the writer. */
    fun flushBlocking() {
        flush()
        writer.submit {}.get()
    }

    // ---------------------------------------------------------------- queries

    fun node(id: Long): Node? = if (id == ROOT_ID) root else byId[id]
    fun isAlive(n: Node) = n === root || byId[n.id] === n
    val size: Int get() = byId.size
    fun allNodes(): Collection<Node> = byId.values

    private fun visible(n: Node) = !(hideState && n.done)

    fun label(n: Node): String {
        if (n === root) return "Home"
        val first = n.plain.lineSequence().firstOrNull()?.trim().orEmpty()
        return first.take(60).ifEmpty { n.url ?: if (n.attach != null) "Image" else "Untitled" }
    }

    fun path(n: Node): List<Node> {
        val out = ArrayList<Node>()
        var p = n.parent
        while (p != null && p !== root) {
            out.add(p)
            p = p.parent
        }
        out.reverse()
        return out
    }

    fun pathLabel(n: Node): String = (listOf("Home") + path(n).map { label(it) }).joinToString(" › ")

    /** The visible outline under [zoom], depth-first. */
    fun flatten(zoom: Node): List<FlatRow> = Snapshot.withoutReadObservation {
        val out = ArrayList<FlatRow>(64)
        val hide = hideState
        fun visit(p: Node, d: Int) {
            for (c in p.children) {
                if (hide && c.done) continue
                val kids = if (hide) c.children.any { !it.done } else c.children.isNotEmpty()
                out.add(FlatRow(c, d, kids))
                if (kids && !c.collapsed) visit(c, d + 1)
            }
        }
        visit(zoom, 0)
        out
    }

    /** All terms must match (text, URL or note). Best matches first, then most recent. */
    fun search(query: String, limit: Int = 300, preferParents: Boolean = false): List<Node> {
        val terms = query.trim().split(' ', '\t', '\n').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val hits = ArrayList<Pair<Node, Int>>()
        for (n in byId.values) {
            val t = n.text.text
            var score = 0
            var ok = true
            for (term in terms) {
                val i = t.indexOf(term, ignoreCase = true)
                if (i >= 0) {
                    score += when {
                        i == 0 -> 0
                        !t[i - 1].isLetterOrDigit() -> 1
                        else -> 2
                    }
                } else if (n.url?.contains(term, true) == true || n.note?.contains(term, true) == true) {
                    score += 3
                } else {
                    ok = false
                    break
                }
            }
            if (ok) hits.add(n to score)
        }
        val byScore = compareBy<Pair<Node, Int>> { it.second }
        val order = if (preferParents) byScore.thenByDescending { it.first.children.isNotEmpty() } else byScore
        hits.sortWith(order.thenByDescending { it.first.modified })
        return hits.asSequence().take(limit).map { it.first }.toList()
    }

    fun findByUrl(url: String): Node? = byId.values.firstOrNull { it.url == url }

    // ---------------------------------------------------------------- structure helpers

    private fun bump() {
        version++
    }

    private fun rankAt(parent: Node, index: Int): Double {
        val before = parent.children.getOrNull(index - 1)?.rank
        val after = parent.children.getOrNull(index)?.rank
        val r = when {
            before == null && after == null -> 1.0
            before == null -> after!! - 1.0
            after == null -> before + 1.0
            else -> before + (after - before) / 2
        }
        val ok = (before == null || r > before) && (after == null || r < after)
        if (!ok) {
            renumber(parent)
            return rankAt(parent, index)
        }
        return r
    }

    private fun renumber(parent: Node) {
        parent.children.forEachIndexed { i, c ->
            c.rank = (i + 1).toDouble()
            dirty(c)
        }
    }

    private fun place(n: Node, parent: Node, index: Int) {
        val i = index.coerceIn(0, parent.children.size)
        n.rank = rankAt(parent, i)
        parent.children.add(i, n)
        n.parent = parent
        dirty(n)
    }

    private fun detach(n: Node): Int {
        val p = n.parent ?: return -1
        val i = p.children.indexOf(n)
        if (i >= 0) p.children.removeAt(i)
        return i
    }

    private inline fun walk(n: Node, f: (Node) -> Unit) {
        val stack = ArrayList<Node>()
        stack.add(n)
        while (stack.isNotEmpty()) {
            val x = stack.removeAt(stack.size - 1)
            f(x)
            stack.addAll(x.children)
        }
    }

    // ---------------------------------------------------------------- edits

    fun create(
        parent: Node,
        index: Int,
        text: String = "",
        url: String? = null,
        note: String? = null,
        attach: String? = null,
    ): Node {
        val t = System.currentTimeMillis()
        val n = Node(nextId++, null, 0.0, text, url, note, KIND_BULLET, false, false, t, t, attach)
        byId[n.id] = n
        place(n, parent, index)
        bump()
        return n
    }

    fun indent(n: Node): Boolean {
        val p = n.parent ?: return false
        val i = p.children.indexOf(n)
        var newParent: Node? = null
        for (j in i - 1 downTo 0) {
            if (visible(p.children[j])) {
                newParent = p.children[j]
                break
            }
        }
        if (newParent == null) return false
        detach(n)
        place(n, newParent, newParent.children.size)
        if (newParent.collapsed) {
            newParent.collapsed = false
            dirty(newParent)
        }
        bump()
        return true
    }

    fun outdent(n: Node): Boolean {
        val p = n.parent ?: return false
        if (p === zoomState || p === root) return false
        val gp = p.parent ?: return false
        detach(n)
        place(n, gp, gp.children.indexOf(p) + 1)
        bump()
        return true
    }

    /** Swap with the previous (-1) or next (+1) visible sibling. */
    fun moveBy(n: Node, delta: Int): Boolean {
        val p = n.parent ?: return false
        val sibs = p.children
        var j = sibs.indexOf(n) + delta
        while (j in sibs.indices && !visible(sibs[j])) j += delta
        if (j !in sibs.indices) return false
        detach(n)
        place(n, p, j)
        bump()
        return true
    }

    /** Drop target from drag & drop: [n] goes under [parent], right after [after] (or first). */
    fun moveTo(n: Node, parent: Node, after: Node?): Boolean {
        if (n === parent || n.isAncestorOf(parent)) return false
        if (after != null && (after.parent !== parent || after === n)) return false
        if (n.parent === parent) {
            val i = parent.children.indexOf(n)
            if (after == null && i == 0) return false
            if (after != null && i > 0 && parent.children[i - 1] === after) return false
        }
        detach(n)
        val idx = if (after == null) 0 else parent.children.indexOf(after) + 1
        place(n, parent, idx)
        bump()
        return true
    }

    /** "Move to…": becomes the first child of [dest]. Undoable. */
    fun moveInto(n: Node, dest: Node, atEnd: Boolean = false): Boolean {
        if (n === dest || n.isAncestorOf(dest)) return false
        if (n.parent === dest && (if (atEnd) dest.children.lastOrNull() else dest.children.firstOrNull()) === n) return false
        val oldParent = n.parent ?: return false
        val oldIndex = detach(n)
        place(n, dest, if (atEnd) dest.children.size else 0)
        pushRecent(dest)
        bump()
        notice = Notice("Moved to ${label(dest)}", "Undo") {
            if (isAlive(n) && isAlive(oldParent)) {
                detach(n)
                place(n, oldParent, oldIndex)
                bump()
            }
        }
        return true
    }

    fun delete(n: Node, undoable: Boolean = true) {
        if (n === root) return
        val p = n.parent ?: return
        if (!isAlive(n)) return
        val zoomed = zoomState === n || n.isAncestorOf(zoomState)
        val idx = detach(n)
        val ids = ArrayList<Long>()
        walk(n) {
            ids.add(it.id)
            byId.remove(it.id)
            focusRequesters.remove(it.id)
        }
        markDeleted(ids)
        if (zoomed) zoom = p
        if (focusedId in ids) focusedId = null
        bump()
        if (undoable) {
            val what = label(n)
            notice = Notice("Deleted “${what.take(32)}”" + if (ids.size > 1) " (+${ids.size - 1})" else "", "Undo") {
                restore(n, p, idx)
            }
        }
    }

    private fun restore(n: Node, p: Node, idx: Int) {
        if (byId.containsKey(n.id)) return
        val parent = if (isAlive(p)) p else root
        walk(n) {
            byId[it.id] = it
            dirty(it)
        }
        place(n, parent, idx)
        bump()
    }

    fun toggleDone(n: Node) {
        n.done = !n.done
        n.modified = System.currentTimeMillis()
        dirty(n)
        if (!hideState) return
        if (n.done) {
            main.postDelayed({ bump() }, 700)
            notice = Notice("Completed “${label(n).take(32)}”", "Undo") {
                if (isAlive(n) && n.done) toggleDone(n)
            }
        } else {
            bump()
        }
    }

    fun toggleCollapsed(n: Node) {
        n.collapsed = !n.collapsed
        dirty(n)
        bump()
    }

    fun setCollapsedBelow(top: Node, collapsed: Boolean) {
        for (c in top.children) {
            walk(c) {
                if (it.children.isNotEmpty() && it.collapsed != collapsed) {
                    it.collapsed = collapsed
                    dirty(it)
                }
            }
        }
        bump()
    }

    /**
     * The user typed or pasted line breaks into [n]. [parts] is the inserted text split on '\n'
     * (so at least two parts); [before]/[after] are the untouched text around it.
     */
    fun onEnter(n: Node, before: String, parts: List<String>, after: String) {
        if (!isAlive(n) || parts.size < 2) return
        val plainEnter = parts.size == 2 && parts[0].isEmpty() && parts[1].isEmpty()
        if (plainEnter) {
            if (before.isEmpty() && after.isEmpty()) {
                if (outdent(n)) return // Enter on an empty nested item steps out a level
            } else if (before.isEmpty()) {
                val p = n.parent ?: return
                create(p, p.children.indexOf(n)) // blank line above, cursor stays
                return
            }
        }
        val lines = parts.drop(1).toMutableList()
        lines[lines.size - 1] = lines.last() + after
        n.text.setTextAndPlaceCursorAtEnd(before + parts.first())
        touched(n)

        val asChild = !n.collapsed && n.children.any { visible(it) }
        val parent: Node
        var index: Int
        if (asChild) {
            parent = n
            index = 0
        } else {
            parent = n.parent ?: root
            index = parent.children.indexOf(n) + 1
        }
        var last = n
        for (line in lines) last = create(parent, index++, line)
        focusReq = FocusReq(last.id, parts.last().length)
    }

    /** Enter in the title of the zoomed item: new first children. */
    fun onHeaderEnter(z: Node, before: String, parts: List<String>, after: String) {
        if (!isAlive(z) || parts.size < 2) return
        z.text.setTextAndPlaceCursorAtEnd(before + parts.first())
        touched(z)
        val lines = parts.drop(1).toMutableList()
        lines[lines.size - 1] = lines.last() + after
        var last: Node? = null
        lines.forEachIndexed { i, l -> last = create(z, i, l) }
        last?.let { focusReq = FocusReq(it.id, parts.last().length) }
    }

    /** Backspace in an empty item: delete it and continue at the end of [prev]. */
    fun backspaceEmpty(n: Node, prev: Node?): Boolean {
        if (n.text.text.isNotEmpty() || n.children.isNotEmpty() || n.attach != null || !n.url.isNullOrBlank()) return false
        val target = prev ?: return false
        val end = target.text.text.length
        target.text.edit { selection = TextRange(end) }
        val fr = focusRequesters[target.id]
        if (fr == null || runCatching { fr.requestFocus() }.isFailure) focusReq = FocusReq(target.id, end)
        delete(n, undoable = false)
        return true
    }

    fun addAtEnd(parent: Node): Node {
        val last = parent.children.lastOrNull { visible(it) }
        if (last != null && last.text.text.isEmpty() && last.children.isEmpty() && last.url.isNullOrBlank() && last.attach == null) {
            focusReq = FocusReq(last.id, 0)
            return last
        }
        val n = create(parent, parent.children.size)
        focusReq = FocusReq(n.id, 0)
        return n
    }

    fun notify(message: String) {
        notice = Notice(message)
    }

    fun removeLink(n: Node) {
        if (n.url == null) return
        val old = n.url
        val oldNote = n.note
        n.url = null
        n.note = null
        touched(n)
        notice = Notice("Link removed", "Undo") {
            if (isAlive(n) && n.url == null) {
                n.url = old
                if (n.note == null) n.note = oldNote
                touched(n)
            }
        }
    }

    /** Show the link field of [n] (adding an empty link if it has none) and put the cursor there. */
    fun editLink(n: Node) {
        if (!isAlive(n)) return
        if (n.url == null) {
            n.url = ""
            dirty(n)
        }
        focusRequesters[n.id]?.let { runCatching { it.requestFocus() } }
        focusedId = n.id
        focusReq = FocusReq(n.id, n.url?.length ?: 0, FIELD_URL)
    }

    /**
     * The user left [n] (title and link field). Tidy the link: drop an empty one, and turn an
     * item whose whole text is a URL into a proper link item with a fetched title.
     */
    fun finishEditing(n: Node, edited: Boolean = true) {
        if (!isAlive(n)) return
        val u = n.url
        if (u != null) {
            val t = if (u.isBlank()) "" else LinkInfo.normalize(u)
            if (t.isEmpty()) {
                n.url = null
                dirty(n)
            } else if (t != u) {
                n.url = t
                dirty(n)
            }
            return
        }
        if (!edited) return
        val text = n.plain.trim()
        if (text.isNotEmpty() && !text.contains(' ') && LinkInfo.findUrl(text)?.equals(text, ignoreCase = true) == true) {
            val clean = LinkInfo.clean(LinkInfo.normalize(text))
            n.url = clean
            n.text.setTextAndPlaceCursorAtEnd("")
            touched(n)
            fetchTitleLater(n, clean)
        }
    }

    // ---------------------------------------------------------------- navigation

    fun zoomOut() {
        val z = zoomState
        zoom = z.parent ?: root
        highlightId = z.id
    }

    fun reveal(n: Node) {
        if (!isAlive(n) || n === root) return
        if (hideState && n.done) hideCompleted = false
        zoom = n.parent ?: root
        highlightId = n.id
    }

    // ---------------------------------------------------------------- inbox, sharing

    fun inbox(): Node {
        meta["inbox"]?.toLongOrNull()?.let { byId[it] }?.let { return it }
        val n = create(root, 0, "Inbox")
        setMeta("inbox", n.id.toString())
        return n
    }

    fun inboxOrNull(): Node? = meta["inbox"]?.toLongOrNull()?.let { byId[it] }

    fun recentDestinations(): List<Node> =
        meta["recent"].orEmpty().split(',').mapNotNull { s -> s.toLongOrNull()?.let { node(it) } }

    fun pushRecent(n: Node) {
        val ids = (listOf(n.id) + recentDestinations().map { it.id }.filter { it != n.id }).take(6)
        setMeta("recent", ids.joinToString(","))
    }

    fun addShared(dest0: Node, text: String, url: String?, note: String?, attach: String?): Node {
        val dest = if (isAlive(dest0)) dest0 else inbox()
        val n = create(dest, 0, text, url, note, attach)
        pushRecent(dest)
        return n
    }

    /** Fill in a page title after the fact (the share sheet may close before the fetch ends). */
    fun fetchTitleLater(n: Node, url: String) {
        appScope.launch {
            val info = withContext(Dispatchers.IO) { LinkInfo.fetch(url) } ?: return@launch
            if (!isAlive(n) || n.url != url) return@launch
            val cur = n.plain
            if (cur.isBlank() || cur == url) n.text.setTextAndPlaceCursorAtEnd(info.title)
            if (n.note == null && info.author != null) n.note = info.author
            touched(n)
            flush()
        }
    }

    // ---------------------------------------------------------------- first run

    private const val TIPS_VERSION = "3"
    private val retiredTips = mapOf(
        "Quick nest: swipe a bullet right (indent) or left (outdent)." to
            "Swipe an item to the right to mark it completed. Swipe again to bring it back.",
        "Long-press a bullet and let go for more: to-do, move to, copy, share, delete." to
            "Long-press a bullet and let go for more: move to, links, copy, share, delete.",
    )

    /** Rewrites tips from older versions that describe gestures that changed meaning. */
    private fun migrateTips() {
        if (meta["tips"] == TIPS_VERSION) return
        setMeta("tips", TIPS_VERSION)
        for (n in byId.values) {
            val replacement = retiredTips[n.plain] ?: continue
            n.text.setTextAndPlaceCursorAtEnd(replacement)
            touched(n)
        }
        val welcome = root.children.firstOrNull { it.plain == "Welcome to Twig" } ?: return
        val have = welcome.children.mapTo(HashSet()) { it.plain }
        for (tip in newTips) if (tip !in have) create(welcome, welcome.children.size, tip)
    }

    private val newTips = listOf(
        "Editing an item? The bar above the keyboard can nest, complete, move or delete it.",
        "Paste a bare link as an item and it turns into a titled link.",
    )

    fun ensureSeeded() {
        if (meta["seeded"] != null) {
            migrateTips()
            return
        }
        setMeta("seeded", "1")
        setMeta("tips", TIPS_VERSION)
        if (byId.isNotEmpty()) return
        inbox()
        val w = create(root, root.children.size, "Welcome to Twig")
        listOf(
            "Tap a bullet to zoom in. Back zooms out.",
            "Long-press a bullet, then drag to move it. Drag sideways while moving to nest it.",
            "Swipe an item to the right to mark it completed. Swipe again to bring it back.",
            "Enter makes a new item. Backspace on an empty item deletes it.",
            "Long-press a bullet and let go for more: move to, links, copy, share, delete.",
            "Editing an item? The bar above the keyboard can nest, complete, move or delete it.",
            "Tap the little arrow to fold an item.",
            "Share links, YouTube videos, text or images from any app. They land in Inbox.",
            "Paste a bare link as an item and it turns into a titled link.",
        ).forEachIndexed { i, t -> create(w, i, t) }
        val b = create(root, root.children.size, "Bucket list")
        val done = create(b, 0, "Find a note app that isn't annoying")
        done.done = true
        dirty(done)
        create(b, 1, "See the northern lights")
        create(b, 2, "Learn to make fresh pasta")
    }
}
