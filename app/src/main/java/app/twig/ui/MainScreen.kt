package app.twig.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.twig.data.Gist
import app.twig.data.Markup
import app.twig.data.Node
import app.twig.data.Store
import app.twig.data.SwipeAction
import app.twig.data.TextFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sign

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val zoom = Store.zoom
    val selection = Store.selection
    var searching by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<List<Node>?>(null) }
    var importing by remember { mutableStateOf<List<TextFormat.Item>?>(null) }
    var settings by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val editingNode = Store.focusedId?.let { Store.node(it) }?.takeIf { !Store.readOnly }

    // One scroll position per list, so going back lands where you were.
    val listState = remember(zoom) {
        Store.scrollMemory[zoom.id]?.let { LazyListState(it.first, it.second) } ?: LazyListState()
    }
    DisposableEffect(listState) {
        onDispose { Store.scrollMemory[zoom.id] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
    }

    // Keyboard put away (back, or the keyboard's own button): back to reading (no cursor, no keyboard bar).
    val ime = WindowInsets.isImeVisible
    LaunchedEffect(ime) { if (!ime) focusManager.clearFocus() }

    BackHandler(enabled = searching || selection != null || zoom !== Store.root) {
        when {
            searching -> searching = false
            selection != null -> Store.selection = null
            else -> Store.zoomOut()
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { Store.notice }.collect { n ->
            snackbar.currentSnackbarData?.dismiss() // also when an import clears it: no Undo into the new data
            if (n != null) launch {
                val r = snackbar.showSnackbar(n.message, actionLabel = "Undo", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) n.undo()
                if (Store.notice === n) Store.notice = null
            }
        }
    }

    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = TextFormat.export(Store.root)
        scope.launch(Dispatchers.IO) { runCatching { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } } }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrDefault("")
            }
            importing = TextFormat.parse(text)
        }
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.ime.union(WindowInsets.navigationBars).union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                )
        ) {
            when {
                searching -> SearchView(onClose = { searching = false }) { n ->
                    searching = false
                    Store.reveal(n)
                }
                selection != null -> SelectionBar(selection.size, all = { Store.selection = zoom.children.mapTo(HashSet()) { it.id } })
                else -> TitleBar(zoom, onSearch = {
                    focusManager.clearFocus()
                    snackbar.currentSnackbarData?.dismiss() // its timer would stop while search is up
                    searching = true
                }) {
                    MoreMenu(
                        onSelect = {
                            focusManager.clearFocus()
                            Store.selection = emptySet()
                        },
                        onSort = { Store.sort(zoom, it) },
                        onCopyAll = { Actions.copy(ctx, TextFormat.export(Store.root)) },
                        onPasteAll = { importing = TextFormat.parse(Actions.clipboardText(ctx)) },
                        onExport = { exportFile.launch("twig-${LocalDate.now()}.md") },
                        onImport = { importFile.launch(arrayOf("text/*")) },
                        onSettings = {
                            focusManager.clearFocus()
                            Store.revealedId = null // a Delete button would outlive a changed swipe setting
                            settings = true
                        },
                    )
                }
            }
            if (!searching) {
                Box(Modifier.weight(1f)) {
                    Page(zoom, listState, onMove = { moving = it })
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { data -> SwipeableSnackbar(data) }
                }
                if (selection != null) BatchBar(zoom.children.filter { it.id in selection }, onMove = { moving = it })
                else editingNode?.let { n ->
                    EditToolbar(n, onMove = { moving = it }) { d -> Store.moveBy(n, d) }
                }
            }
        }
    }

    moving?.let { nodes ->
        LocationSheet(
            title = if (nodes.size == 1) "Move “${Store.label(nodes[0]).take(40)}” to…" else "Move ${nodes.size} bullets to…",
            onDismiss = { moving = null },
            moving = nodes,
            onPick = { dest ->
                moving = null
                if (dest != null && Store.move(nodes, dest)) Store.selection = null
            },
        )
    }
    importing?.let { items ->
        ImportDialog(items.size, onDismiss = { importing = null }) {
            importing = null
            Store.replaceAll(items)
        }
    }
    if (settings) SettingsSheet { settings = false }
}

/** Where you are: Home › list › list. Tap a step to go there. */
@Composable
private fun TitleBar(zoom: Node, onSearch: () -> Unit, menu: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val path = remember(zoom, Store.version) { generateSequence(zoom) { it.parent }.toList().asReversed() }
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.scrollTo(scroll.maxValue) }
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .height(52.dp)
            .testTag("titleBar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (zoom !== Store.root) {
            Tip("Back", below = true) { IconButton(onClick = Store::zoomOut, modifier = Modifier.testTag("back")) { Icon(Ic.Back, "Back") } }
        } else {
            Spacer(Modifier.size(12.dp))
        }
        Row(Modifier.weight(1f).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
            path.forEachIndexed { i, n ->
                val last = i == path.lastIndex
                if (i > 0) Icon(Ic.ChevronRight, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                Text(
                    Store.label(n),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (last) cs.onSurface else cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !last) { Store.zoom = n }
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                        .testTag("crumb-${n.id}"),
                )
            }
        }
        Tip("Search", below = true) { IconButton(onClick = onSearch, modifier = Modifier.testTag("openSearch")) { Icon(Ic.Search, "Search") } }
        // the eye switches to reading only, the pencil back to editing
        val readOnly = if (Store.readOnly) "Edit" else "Read-only"
        Tip(readOnly, below = true) {
            IconButton(onClick = { Store.readOnly = !Store.readOnly }, modifier = Modifier.testTag("readOnly")) {
                Icon(if (Store.readOnly) Ic.Edit else Ic.Eye, readOnly)
            }
        }
        menu()
    }
}

/** Search on its own screen: a field on top, results below. Tapping a result goes to it. */
@Composable
private fun SearchView(onClose: () -> Unit, onOpen: (Node) -> Unit) {
    val state = rememberTextFieldState()
    val q = state.text.toString()
    val results = remember(q, Store.version) { Store.search(q) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Tip("Close search", below = true) { IconButton(onClick = onClose, modifier = Modifier.testTag("closeSearch")) { Icon(Ic.Back, "Close search") } }
            SearchField(state, "Search", fr, Modifier.weight(1f).testTag("searchField")) { results.firstOrNull()?.let(onOpen) }
        }
        if (q.isNotBlank()) SearchResults(q, results, onOpen)
    }
}

@Composable
private fun MoreMenu(
    onSelect: () -> Unit,
    onSort: (descending: Boolean) -> Unit,
    onCopyAll: () -> Unit,
    onPasteAll: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    fun item(f: () -> Unit): () -> Unit = {
        open = false
        f()
    }
    Box {
        Tip("More", below = true) { IconButton(onClick = { open = true }, modifier = Modifier.testTag("more")) { Icon(Ic.More, "More") } }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // read-only: nothing here that changes bullets
            val edit = !Store.readOnly
            if (edit) {
                MenuItem("Select", Ic.CheckCircle, item(onSelect))
                MenuItem("Sort A–Z", Ic.Sort, item { onSort(false) })
                MenuItem("Sort Z–A", Ic.Sort, item { onSort(true) })
                HorizontalDivider()
            }
            MenuItem("Copy all as text", Ic.Copy, item(onCopyAll))
            if (edit) MenuItem("Replace all from clipboard", Ic.Paste, item(onPasteAll))
            MenuItem("Export to file", Ic.Down, item(onExport))
            if (edit) MenuItem("Replace all from file", Ic.Up, item(onImport))
            HorizontalDivider()
            MenuItem("Settings", Ic.Settings, item(onSettings))
        }
    }
}

/** Above the keyboard while a bullet is being edited. */
@Composable
private fun EditToolbar(node: Node, onMove: (List<Node>) -> Unit, onStep: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val siblings = node.parent?.children.orEmpty()
    val index = remember(Store.version, node) { siblings.indexOf(node) }
    var armed by remember(node.id) { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(3000)
            armed = false
        }
    }
    Surface(color = cs.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp).testTag("toolbar"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val urlOpen = Store.urlFor == node.id
            Tool(Ic.Link, if (node.url == null) "Add URL" else "Edit URL", on = urlOpen) {
                if (urlOpen) Store.closeUrl(node) else Store.urlFor = node.id
            }
            // two taps, so a stray one can't wipe the text
            Tool(Ic.Title, "Use page title", enabled = node.url != null && node.id !in Store.fetching, on = armed) {
                if (armed) Store.useTitle(node)
                armed = !armed
            }
            if (armed) Text("Replace text?", style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.padding(end = 6.dp))
            Tool(Ic.Bold, "Bold", enabled = !node.text.selection.collapsed) { Markup.toggleBold(node.text) }
            Tool(Ic.Up, "Move up", enabled = index > 0) { onStep(-1) }
            Tool(Ic.Down, "Move down", enabled = index in 0 until siblings.lastIndex) { onStep(1) }
            Tool(Ic.Move, "Move to…") { onMove(listOf(node)) }
            Tool(if (node.done) Ic.CheckBoxBlank else Ic.CheckBox, if (node.done) "Mark not done" else "Mark done") { Store.toggleDone(node) }
        }
    }
}

@Composable
private fun Tool(icon: ImageVector, desc: String, enabled: Boolean = true, on: Boolean = false, onClick: () -> Unit) {
    // the button's own container circle, so it fits its slot
    Tip(desc) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
            modifier = Modifier.testTag("tool-$desc"),
        ) {
            Icon(icon, desc, Modifier.size(22.dp))
        }
    }
}

/** An icon button's name in a small tooltip, on long-press or mouse hover. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Tip(text: String, below: Boolean = false, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(if (below) TooltipAnchorPosition.Below else TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        content = content,
    )
}

/** Batch mode's top bar. */
@Composable
private fun SelectionBar(count: Int, all: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
            .height(52.dp)
            .testTag("selectionBar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tip("Stop selecting", below = true) { IconButton(onClick = { Store.selection = null }) { Icon(Ic.Close, "Stop selecting") } }
        Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
        TextButton(onClick = all) { Text("All") }
        TextButton(onClick = { Store.selection = emptySet() }) { Text("None") }
    }
}

/** Batch mode's actions, for [nodes] (the selected bullets, in page order). */
@Composable
private fun BatchBar(nodes: List<Node>, onMove: (List<Node>) -> Unit) {
    val on = nodes.isNotEmpty()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().height(64.dp).testTag("batchBar"), horizontalArrangement = Arrangement.SpaceEvenly) {
            BatchAction(Ic.CheckBox, "Done", on) { Store.setDone(nodes, true) }
            BatchAction(Ic.CheckBoxBlank, "Undone", on) { Store.setDone(nodes, false) }
            BatchAction(Ic.Move, "Move", on) { onMove(nodes) }
            BatchAction(Ic.Delete, "Delete", on) {
                Store.selection = null
                Store.delete(nodes)
            }
        }
    }
}

@Composable
private fun BatchAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
    Column(
        Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp)
            .testTag("batch-$label"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = ink)
        Text(label, style = MaterialTheme.typography.labelMedium, color = ink)
    }
}

/** A snackbar you can fling away sideways. */
@Composable
private fun SwipeableSnackbar(data: SnackbarData) {
    var dx by remember(data) { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(1) }
    val drag = rememberDraggableState { d -> dx += d }
    Snackbar(
        snackbarData = data,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .graphicsLayer {
                translationX = dx
                alpha = 1f - (abs(dx) / width).coerceIn(0f, 1f) * 0.85f
            }
            .draggable(
                state = drag,
                orientation = Orientation.Horizontal,
                onDragStopped = { velocity ->
                    val away = abs(dx) > width * 0.3f || abs(velocity) > 1200f
                    val dir = if (dx != 0f) sign(dx) else sign(velocity)
                    val target = if (away && dir != 0f) dir * width * 1.15f else 0f
                    animate(dx, target, animationSpec = tween(170)) { v, _ -> dx = v }
                    if (target != 0f) data.dismiss()
                },
            )
            .testTag("snackbar"),
    )
}

/** Everything that's a preference rather than an action. Changes apply as you tap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(onDismiss: () -> Unit) {
    var askTarget by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("settings")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp).navigationBarsPadding()) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            Setting("Save shared items to") {
                Text(
                    Store.shareTarget?.let { Store.label(it) } ?: "Always ask",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { askTarget = true }.padding(vertical = 6.dp).testTag("shareTarget"),
                )
            }
            Setting("Swipe right") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwipeAction.entries.forEach { a -> Chip(a.label, Store.swipeRight == a, "right-${a.name}") { Store.swipeRight = a } }
                }
            }
            Setting("Swipe left") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwipeAction.entries.forEach { a -> Chip(a.label, Store.swipeLeft == a, "left-${a.name}") { Store.swipeLeft = a } }
                }
            }
            Setting("Text size") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(14, 15, 16, 17, 18, 20, 22).forEach { s -> Chip(if (s == 16) "16 (normal)" else "$s", Store.textSize == s, "size-$s") { Store.textSize = s } }
                }
            }
            Setting("Font") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Sans-serif", !Store.monospace, "font-sans") { Store.monospace = false }
                    Chip("Monospace", Store.monospace, "font-mono") { Store.monospace = true }
                }
            }
            Setting("Sync") {
                val token = rememberTextFieldState(Gist.token)
                LaunchedEffect(token) { snapshotFlow { token.text.toString() }.collect { Gist.token = it } }
                OutlinedSecureTextField(
                    state = token,
                    placeholder = { Text("GitHub token") },
                    modifier = Modifier.fillMaxWidth().testTag("gistToken"),
                )
                Text(
                    if (Gist.token.isEmpty()) {
                        "Paste a fine-grained GitHub token with Gists: read and write. Twig keeps everything in a " +
                            "secret gist, twig.md. If there is one already, it replaces what's on this phone."
                    } else {
                        Gist.status ?: "Syncing soon"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp).testTag("gistStatus"),
                )
            }
        }
    }
    if (askTarget) {
        LocationSheet("Save shared items to", onDismiss = { askTarget = false }, ask = true, marked = Store.shareTarget, onPick = {
            askTarget = false
            Store.shareTarget = it
        })
    }
}

@Composable
private fun Setting(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
    content()
}

@Composable
private fun Chip(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(selected = on, onClick = onClick, label = { Text(label) }, modifier = Modifier.testTag(tag))
}

/** Import replaces everything, so it asks first. */
@Composable
private fun ImportDialog(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 0) "Nothing to import" else "Replace everything?") },
        text = {
            Text(
                if (count == 0) "There's no text to turn into bullets."
                else "This deletes all ${Store.size} bullets in Twig and puts in the $count from the text. It can't be undone."
            )
        },
        confirmButton = {
            if (count == 0) {
                TextButton(onClick = onDismiss) { Text("OK") }
            } else {
                TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirmImport")) { Text("Replace") }
            }
        },
        dismissButton = { if (count > 0) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
