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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import app.twig.share.LinkInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** The snackbar. Only removals and moves show one, always with Undo. */
class Notice(val message: String, val undo: () -> Unit)

/** A request to put the cursor in a bullet. Plain class on purpose: every request is distinct. */
class FocusReq(val nodeId: Long, val cursor: Int)

/**
 * The whole outline, in memory. Everything here runs on the main thread except the
 * initial load (background thread) and SQLite writes (single writer thread).
 */
object Store {
    const val ROOT_ID = 0L

    lateinit var appContext: Context
        private set
    private lateinit var db: Db
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "twig-db") }

    @Volatile private var loadLatch = CountDownLatch(1)
    @Volatile private var loadError: Throwable? = null

    var main: Handler = Handler(Looper.getMainLooper())
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var root = newRoot()
        private set
    private val byId = HashMap<Long, Node>()
    private var nextId = 1L
    private val meta = HashMap<String, String>()

    /** Bumped on every structural change; pages re-read their children when it changes. */
    var version by mutableIntStateOf(0)
        private set

    private var zoomState by mutableStateOf(root)
    /** The list on screen. */
    var zoom: Node
        get() = zoomState
        set(v) {
            if (zoomState === v) return
            zoomState = v
            selection = null
            revealedId = null
            setMeta("zoom", v.id.toString())
        }

    var focusReq by mutableStateOf<FocusReq?>(null)
    var focusedId by mutableStateOf<Long?>(null)
    var highlightId by mutableStateOf<Long?>(null)
    var notice by mutableStateOf<Notice?>(null)
    /** Batch mode: ids of the selected bullets on this page, or null when not selecting. */
    var selection by mutableStateOf<Set<Long>?>(null)
    /** The bullet whose Delete button is showing. */
    var revealedId by mutableStateOf<Long?>(null)
    val focusRequesters = HashMap<Long, FocusRequester>()
    /** Scroll position per list (index, offset), so going back lands where you were. */
    val scrollMemory = HashMap<Long, Pair<Int, Int>>()

    private val pendingUpserts = LinkedHashMap<Long, Node>()
    private val pendingDeletes = HashSet<Long>()
    private val pendingMeta = HashMap<String, String>()
    private var flushScheduled = false
    private val flushRunnable = Runnable {
        flushScheduled = false
        flush()
    }

    private fun newRoot() = Node(ROOT_ID, null, 0.0, "", null, false)

    // ---------------------------------------------------------------- lifecycle

    /** Starts loading on a background thread. Call [awaitLoaded] before touching the tree. */
    fun init(ctx: Context, dbName: String? = "twig4.db") {
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
        selection = null
        revealedId = null
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
        val r = newRoot()
        val map = HashMap<Long, Node>(rows.size * 2 + 16)
        for (row in rows) map[row.id] = Node(row.id, null, row.rank, row.text, row.url, row.done)
        for (row in rows) {
            val n = map.getValue(row.id)
            val p = map[row.parent]?.takeIf { it !== n } ?: r
            n.parent = p
            p.children.add(n)
        }
        r.children.sortBy { it.rank }
        for (n in map.values) n.children.sortBy { it.rank }
        root = r
        byId.clear()
        byId.putAll(map)
        nextId = (map.keys.maxOrNull() ?: 0L) + 1
        meta.clear()
        meta.putAll(db.loadMeta())
        zoomState = meta["zoom"]?.toLongOrNull()?.let { map[it] } ?: r
    }

    // ---------------------------------------------------------------- persistence

    fun dirty(n: Node) {
        if (n === root) return
        pendingDeletes.remove(n.id)
        pendingUpserts[n.id] = n
        scheduleFlush()
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
        val ups = pendingUpserts.values.map { NodeRow(it.id, it.parent?.id ?: ROOT_ID, it.rank, it.plain, it.url, it.done) }
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
                Log.e("Twig", "write failed", t)
            }
        }
    }

    /** For tests: flush and wait for the writer. */
    fun flushBlocking() {
        flush()
        writer.submit {}.get()
    }

    // ---------------------------------------------------------------- queries

    fun node(id: Long): Node? = if (id == ROOT_ID) root else byId[id]
    fun isAlive(n: Node) = n === root || byId[n.id] === n
    val size: Int get() = byId.size
    fun allNodes(): Collection<Node> = byId.values

    /** Home is 0, its bullets 1, and so on down to [MAX_DEPTH]. */
    fun depth(n: Node): Int {
        var d = 0
        var p = n.parent
        while (p != null) {
            d++
            p = p.parent
        }
        return d
    }

    private fun height(n: Node): Int = if (n.children.isEmpty()) 0 else 1 + n.children.maxOf { height(it) }

    /** Whether [nodes] can all go inside [dest]: not into themselves, and no deeper than [MAX_DEPTH]. */
    fun canHold(dest: Node, nodes: Collection<Node>): Boolean =
        isAlive(dest) && nodes.none { it === dest || it.isAncestorOf(dest) } &&
            depth(dest) + 1 + (nodes.maxOfOrNull { height(it) } ?: 0) <= MAX_DEPTH

    fun label(n: Node): String =
        if (n === root) "Home" else n.plain.lineSequence().first().trim().take(60).ifEmpty { n.url ?: "Untitled" }

    /** "Home › Groceries": where [n] lives. */
    fun pathLabel(n: Node): String {
        val parts = ArrayList<String>()
        var p = n.parent
        while (p != null) {
            parts.add(label(p))
            p = p.parent
        }
        return parts.asReversed().joinToString(" › ")
    }

    /** All terms must match (text or URL). Best matches first, then outline order. */
    fun search(query: String, limit: Int = 300): List<Node> {
        val terms = query.trim().split(' ', '\t', '\n').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val hits = ArrayList<Pair<Node, Int>>()
        fun visit(p: Node) {
            for (n in p.children) {
                score(n, terms)?.let { hits.add(n to it) }
                visit(n)
            }
        }
        visit(root)
        hits.sortBy { it.second }
        return hits.asSequence().take(limit).map { it.first }.toList()
    }

    private fun score(n: Node, terms: List<String>): Int? {
        val t = n.text.text
        var s = 0
        for (term in terms) {
            val i = t.indexOf(term, ignoreCase = true)
            s += when {
                i == 0 -> 0
                i > 0 && !t[i - 1].isLetterOrDigit() -> 1
                i > 0 -> 2
                n.url?.contains(term, ignoreCase = true) == true -> 3
                else -> return null
            }
        }
        return s
    }

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
        if ((before != null && r <= before) || (after != null && r >= after)) {
            parent.children.forEachIndexed { i, c ->
                c.rank = (i + 1).toDouble()
                dirty(c)
            }
            return rankAt(parent, index)
        }
        return r
    }

    private fun place(n: Node, parent: Node, index: Int) {
        val i = index.coerceIn(0, parent.children.size)
        n.rank = rankAt(parent, i)
        parent.children.add(i, n)
        n.parent = parent
        dirty(n)
    }

    private fun detach(n: Node) {
        n.parent?.children?.remove(n)
    }

    private inline fun walk(n: Node, f: (Node) -> Unit) {
        val stack = arrayListOf(n)
        while (stack.isNotEmpty()) {
            val x = stack.removeAt(stack.size - 1)
            f(x)
            stack.addAll(x.children)
        }
    }

    /** Where each of [nodes] is now, in an order that puts them back right on Undo. */
    private fun spots(nodes: List<Node>) =
        nodes.map { Triple(it, it.parent!!, it.parent!!.children.indexOf(it)) }.sortedBy { it.third }

    // ---------------------------------------------------------------- edits

    fun create(parent: Node, index: Int, text: String = "", url: String? = null, done: Boolean = false): Node {
        val n = Node(nextId++, null, 0.0, text, url, done)
        byId[n.id] = n
        place(n, parent, index)
        bump()
        return n
    }

    /** Drag & drop: [n] goes to [index] among its siblings. */
    fun reorder(n: Node, index: Int): Boolean {
        val p = n.parent ?: return false
        if (p.children.indexOf(n) == index) return false
        detach(n)
        place(n, p, index)
        bump()
        return true
    }

    /** "Move to…": [nodes] go to the end of [dest], keeping their order. Undoable. */
    fun move(nodes: List<Node>, dest: Node): Boolean {
        val list = nodes.filter { it !== root && isAlive(it) }
        if (list.isEmpty() || !canHold(dest, list)) return false
        val undo = spots(list)
        for (n in list) {
            detach(n)
            place(n, dest, dest.children.size)
        }
        bump()
        notice = Notice(if (list.size == 1) "Moved to ${label(dest)}" else "Moved ${list.size} to ${label(dest)}") {
            for ((n, p, i) in undo) {
                if (isAlive(n) && isAlive(p)) {
                    detach(n)
                    place(n, p, i)
                }
            }
            bump()
        }
        return true
    }

    fun delete(nodes: List<Node>, undoable: Boolean = true) {
        val list = nodes.filter { it !== root && isAlive(it) }
        if (list.isEmpty()) return
        val undo = spots(list)
        val ids = ArrayList<Long>()
        for (n in list) {
            detach(n)
            walk(n) {
                ids.add(it.id)
                byId.remove(it.id)
                focusRequesters.remove(it.id)
            }
        }
        markDeleted(ids)
        var z = zoomState
        while (!isAlive(z)) z = z.parent ?: root
        zoom = z
        if (focusedId in ids) focusedId = null
        revealedId = null
        bump()
        if (!undoable) return
        notice = Notice(if (list.size == 1) "Deleted “${label(list[0]).take(32)}”" else "Deleted ${list.size} bullets") {
            for ((n, p, i) in undo) restore(n, p, i)
        }
    }

    private fun restore(n: Node, p: Node, index: Int) {
        if (byId.containsKey(n.id)) return
        walk(n) {
            byId[it.id] = it
            dirty(it)
        }
        place(n, if (isAlive(p)) p else root, index)
        bump()
    }

    fun setDone(nodes: Collection<Node>, done: Boolean) {
        for (n in nodes) {
            if (n.done == done) continue
            n.done = done
            dirty(n)
        }
    }

    fun toggleDone(n: Node) = setDone(listOf(n), !n.done)

    /**
     * The user typed or pasted line breaks into [n]. [parts] is the inserted text split on '\n'
     * (so at least two parts); [before]/[after] are the untouched text around it.
     * The new lines become bullets right after [n].
     */
    fun onEnter(n: Node, before: String, parts: List<String>, after: String) {
        val p = n.parent
        if (!isAlive(n) || p == null || parts.size < 2) return
        if (parts.size == 2 && parts.all { it.isEmpty() } && before.isEmpty()) {
            create(p, p.children.indexOf(n)) // Enter at the start: blank bullet above, cursor stays
            return
        }
        n.text.setTextAndPlaceCursorAtEnd(before + parts.first())
        dirty(n)
        addLines(p, p.children.indexOf(n) + 1, parts, after)
    }

    /** Enter in a list's title: the new lines become its first bullets. */
    fun onHeaderEnter(z: Node, before: String, parts: List<String>, after: String) {
        if (!isAlive(z) || parts.size < 2) return
        z.text.setTextAndPlaceCursorAtEnd(before + parts.first())
        dirty(z)
        addLines(z, 0, parts, after)
    }

    private fun addLines(parent: Node, index: Int, parts: List<String>, after: String) {
        val lines = parts.drop(1).toMutableList()
        lines[lines.lastIndex] += after
        var i = index
        var last: Node? = null
        for (line in lines) last = create(parent, i++, line)
        last?.let { focusReq = FocusReq(it.id, parts.last().length) }
    }

    /** Backspace in an empty bullet: delete it and continue at the end of [prev]. */
    fun backspaceEmpty(n: Node, prev: Node?): Boolean {
        if (n.text.text.isNotEmpty() || n.children.isNotEmpty() || !n.url.isNullOrBlank() || prev == null) return false
        val end = prev.text.text.length
        prev.text.edit { selection = TextRange(end) }
        val fr = focusRequesters[prev.id]
        if (fr == null || runCatching { fr.requestFocus() }.isFailure) focusReq = FocusReq(prev.id, end)
        delete(listOf(n), undoable = false)
        return true
    }

    fun addAtEnd(parent: Node) {
        val last = parent.children.lastOrNull()
        val n = if (last != null && isBlank(last)) last else create(parent, parent.children.size)
        focusReq = FocusReq(n.id, 0)
    }

    private fun isBlank(n: Node) = n.plain.isBlank() && n.url.isNullOrBlank() && n.children.isEmpty()

    /** The user left [n]: tidy its URL, and drop the bullet if nothing is left in it. */
    fun finishEditing(n: Node) {
        if (!isAlive(n)) return
        val u = n.url?.let { LinkInfo.normalize(it) }?.ifEmpty { null }
        if (u != n.url) {
            n.url = u
            dirty(n)
        }
        if (isBlank(n) && n !== zoomState && !n.isAncestorOf(zoomState)) delete(listOf(n), undoable = false)
    }

    // ---------------------------------------------------------------- navigation

    fun zoomOut() {
        val z = zoomState
        zoom = z.parent ?: root
        highlightId = z.id
    }

    fun reveal(n: Node) {
        if (!isAlive(n) || n === root) return
        zoom = n.parent ?: root
        highlightId = n.id
    }

    // ---------------------------------------------------------------- sharing, import

    /** Where shared items go without asking, or null for "Always ask". */
    var shareTarget: Node?
        get() = meta["shareTarget"]?.toLongOrNull()?.let { byId[it] }?.takeIf { canHold(it, emptyList()) }
        set(v) = setMeta("shareTarget", v?.id?.toString().orEmpty())

    fun addShared(dest: Node, text: String, url: String?): Node {
        val n = create(if (canHold(dest, emptyList())) dest else root, 0, text, url)
        if (url != null && (text.isBlank() || text == url)) fetchTitleLater(n, url)
        return n
    }

    /** Fill in a page title after the fact (the share sheet may close before the fetch ends). */
    private fun fetchTitleLater(n: Node, url: String) {
        appScope.launch {
            val title = withContext(Dispatchers.IO) { LinkInfo.fetchTitle(url) } ?: return@launch
            if (!isAlive(n) || n.url != url || !(n.plain.isBlank() || n.plain == url)) return@launch
            n.text.setTextAndPlaceCursorAtEnd(title)
            dirty(n)
            flush()
        }
    }

    /** Clipboard or file import: everything goes, [items] come in. */
    fun replaceAll(items: List<TextFormat.Item>) {
        markDeleted(byId.keys.toList())
        byId.clear()
        root.children.clear()
        focusRequesters.clear()
        scrollMemory.clear()
        focusedId = null
        notice = null
        zoom = root
        val last = arrayOfNulls<Node>(MAX_DEPTH + 1)
        last[0] = root
        for (it in items) {
            val p = last[it.depth - 1] ?: root
            last[it.depth] = create(p, p.children.size, it.text, it.url, it.done)
        }
        bump()
    }

    fun ensureSeeded() {
        if (meta["seeded"] != null) return
        setMeta("seeded", "1")
        if (byId.isEmpty()) replaceAll(TextFormat.parse(WELCOME))
    }

    private val WELCOME = """
        - Welcome to Twig
            - Tap a list to open it. Back goes up.
            - Tap the dot of any bullet to open it and put bullets inside. Lists go three levels deep.
            - Tap a bullet to edit it. Its URL field shows up below.
            - Swipe right to complete a bullet. Swipe left to delete one.
            - Long-press a dot to drag the bullet, or let go for more.
            - ⋮ › Select acts on many bullets at once.
            - ⋮ › Copy all as text gives you Markdown. Replace all from clipboard takes it back.
        - Bucket list
            - ~~Find a note app that isn't annoying~~
            - See the northern lights
            - Learn to make fresh pasta
    """.trimIndent()
}
