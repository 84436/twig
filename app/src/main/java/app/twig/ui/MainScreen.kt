package app.twig.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.twig.data.Backup
import app.twig.data.FocusReq
import app.twig.data.Node
import app.twig.data.Notice
import app.twig.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sign

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val zoom = Store.zoom
    val zoomed = zoom !== Store.root
    val query = rememberTextFieldState()
    val q = query.text.toString()
    val searching = q.isNotBlank()
    val version = Store.version
    val results = remember(q, version) { if (q.isBlank()) emptyList() else Store.search(q) }
    val searchFocus = remember { FocusRequester() }
    var moveTarget by remember { mutableStateOf<Node?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val imeVisible = WindowInsets.isImeVisible

    // One scroll position per zoom level, so zooming out lands where you were.
    val listState = remember(zoom) {
        Store.scrollMemory[zoom.id]?.let { LazyListState(it.first, it.second) } ?: LazyListState()
    }
    DisposableEffect(listState) {
        onDispose { Store.scrollMemory[zoom.id] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
    }

    val closeSearch = {
        query.clearText()
        focusManager.clearFocus()
    }
    val open: (Node) -> Unit = { n ->
        closeSearch()
        Store.reveal(n)
    }

    BackHandler(enabled = searching || zoomed) {
        if (searching) closeSearch() else Store.zoomOut()
    }

    // An action notice (Undo) replaces whatever is showing; a plain message waits its turn
    // instead of pushing an Undo away.
    LaunchedEffect(Unit) {
        var showing: Notice? = null
        snapshotFlow { Store.notice }.filterNotNull().collect { n ->
            if (n.action != null || showing?.action == null) snackbar.currentSnackbarData?.dismiss()
            launch {
                val r = snackbar.showSnackbar(n.message, actionLabel = n.actionLabel, duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) n.action?.invoke()
                if (Store.notice === n) Store.notice = null
            }
            showing = n
        }
    }

    val stamp = { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            Store.flush()
            val json = Backup.toJson(Store.root)
            val images = Backup.attachmentsUnder(Store.root)
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { Backup.writeZip(ctx, it, json, images) } }.isSuccess
                }
                Store.notify(if (ok) "Backup saved (notes + ${images.size} images)" else "Couldn't write the backup")
            }
        }
    }
    val exportMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) {
            val data = Backup.toMarkdown(Store.zoom)
            scope.launch { write(ctx, uri, data, "Exported") }
        }
    }
    val importDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val text = if (Backup.isZip(bytes)) Backup.readZip(ctx, bytes) else bytes.toString(Charsets.UTF_8)
                    name to text!!
                }.getOrNull()
            }
            if (read == null) {
                Store.notify("Couldn't read that file")
                return@launch
            }
            val result = runCatching { Backup.import(read.second, read.first) }.getOrNull()
            if (result == null) {
                Store.notify("That file doesn't look like notes")
            } else {
                Store.reveal(result.first)
                Store.notify("Imported ${result.second} items")
            }
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
            SearchBar(
                state = query,
                focusRequester = searchFocus,
                showBack = searching || zoomed,
                backLabel = if (searching) "Close search" else "Zoom out",
                onBack = { if (searching) closeSearch() else Store.zoomOut() },
                onSubmit = { results.firstOrNull()?.let(open) },
            ) {
                MoreMenu(
                    zoom = zoom,
                    onExportJson = { exportZip.launch("twig-backup-${stamp()}.zip") },
                    onExportMd = { exportMd.launch("twig-${stamp()}.md") },
                    onImport = { importDoc.launch(arrayOf("application/zip", "application/json", "text/*", "application/xml", "text/x-opml", "*/*")) },
                )
            }
            Box(Modifier.weight(1f)) {
                if (searching) {
                    SearchResults(q, results, open)
                } else {
                    Outline(zoom, listState, PaddingValues(top = 2.dp, bottom = 8.dp), onMoveTo = { moveTarget = it })
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { data -> SwipeableSnackbar(data) }
            }
            if (imeVisible && !searching && Store.focusedId != null) EditToolbar(onMoveTo = { moveTarget = it })
        }
    }

    moveTarget?.let { n ->
        val wasEditing = Store.focusedId == n.id
        MoveDialog(n, onDismiss = { moveTarget = null }) { dest, atEnd ->
            moveTarget = null
            Store.moveInto(n, dest, atEnd)
            // Once the dialog window is gone: keep editing the item if it's still on screen.
            Store.main.postDelayed({
                val visible = Store.flatten(Store.zoom).any { it.node === n }
                if (wasEditing && visible) Store.focusReq = FocusReq(n.id, n.text.text.length)
                else if (wasEditing) focusManager.clearFocus()
                if (visible) Store.highlightId = n.id
            }, 150)
        }
    }
}

private suspend fun write(ctx: Context, uri: Uri, data: String, done: String) {
    val ok = withContext(Dispatchers.IO) {
        runCatching { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(data.toByteArray()) } }.isSuccess
    }
    Store.notify(if (ok) done else "Couldn't write the file")
}

/** The only bar at the top: search field, with zoom-out on the left and the menu on the right. */
@Composable
private fun SearchBar(
    state: TextFieldState,
    focusRequester: FocusRequester,
    showBack: Boolean,
    backLabel: String,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface)
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(cs.surfaceContainerHigh)
            .clickable(interactionSource = null, indication = null) {
                runCatching { focusRequester.requestFocus() }
                keyboard?.show() // already focused but keyboard dismissed: bring it back
            }
            .testTag("searchBar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("searchBack")) { Icon(Ic.Back, backLabel) }
        } else {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Ic.Search, null, Modifier.size(22.dp), tint = cs.onSurfaceVariant)
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (state.text.isEmpty()) Text("Search", style = style, color = cs.onSurfaceVariant, maxLines = 1)
            BasicTextField(
                state = state,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("searchField"),
                textStyle = style,
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                onKeyboardAction = { onSubmit() },
                cursorBrush = SolidColor(cs.primary),
            )
        }
        if (state.text.isNotEmpty()) {
            IconButton(onClick = { state.clearText() }) { Icon(Ic.Close, "Clear search", Modifier.size(20.dp)) }
        }
        menu()
    }
}

@Composable
private fun MoreMenu(zoom: Node, onExportJson: () -> Unit, onExportMd: () -> Unit, onImport: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("more")) { Icon(Ic.More, "More") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Hide completed") },
                leadingIcon = { Icon(Ic.Fold, null, Modifier.size(20.dp)) },
                trailingIcon = { if (Store.hideCompleted) Icon(Ic.Check, "On", Modifier.size(20.dp)) },
                onClick = {
                    Store.hideCompleted = !Store.hideCompleted
                    menu = false
                },
            )
            MenuItem("Expand all", Ic.Unfold) {
                Store.setCollapsedBelow(zoom, false)
                menu = false
            }
            MenuItem("Collapse all", Ic.Fold) {
                Store.setCollapsedBelow(zoom, true)
                menu = false
            }
            HorizontalDivider()
            MenuItem("Back up everything (.zip)", Ic.Down) {
                menu = false
                onExportJson()
            }
            MenuItem(if (zoom === Store.root) "Export as Markdown" else "Export this as Markdown", Ic.Share) {
                menu = false
                onExportMd()
            }
            MenuItem("Import (backup, OPML, Markdown)", Ic.Up) {
                menu = false
                onImport()
            }
        }
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

@Composable
private fun EditToolbar(onMoveTo: (Node) -> Unit) {
    val id = Store.focusedId ?: return
    val node = Store.node(id) ?: return
    val structural = node !== Store.zoom
    val focus = LocalFocusManager.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().height(48.dp).testTag("toolbar"), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tool(Ic.Outdent, "Outdent", structural) { Store.outdent(node) }
            Tool(Ic.Indent, "Indent", structural) { Store.indent(node) }
            Tool(Ic.Move, "Move to…", structural) { onMoveTo(node) }
            Tool(if (node.done) Ic.Undo else Ic.Check, if (node.done) "Mark not completed" else "Mark completed") { Store.toggleDone(node) }
            Tool(Ic.Up, "Move up", structural) { Store.moveBy(node, -1) }
            Tool(Ic.Down, "Move down", structural) { Store.moveBy(node, 1) }
            Tool(Ic.Delete, "Delete", structural) {
                val rows = Store.flatten(Store.zoom)
                val i = rows.indexOfFirst { it.node === node }
                var j = i + 1
                while (i >= 0 && j < rows.size && rows[j].depth > rows[i].depth) j++
                val neighbor = rows.getOrNull(i - 1)?.node ?: rows.getOrNull(j)?.node
                if (neighbor != null) {
                    val end = neighbor.text.text.length
                    neighbor.text.edit { selection = TextRange(end) }
                    Store.focusRequesters[neighbor.id]?.let { runCatching { it.requestFocus() } }
                } else {
                    focus.clearFocus()
                }
                Store.delete(node)
            }
        }
            Tool(Ic.KeyboardHide, "Hide keyboard") { focus.clearFocus() }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun Tool(icon: ImageVector, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp).testTag("tool-$desc")) {
        Icon(icon, desc, Modifier.size(22.dp))
    }
}

@Composable
private fun MoveDialog(node: Node, onDismiss: () -> Unit, onPick: (dest: Node, atEnd: Boolean) -> Unit) {
    var atEnd by remember { mutableStateOf(Store.meta("moveAtEnd") == "1") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.8f).imePadding(),
        ) {
            Column(Modifier.padding(top = 18.dp)) {
                Row(Modifier.padding(start = 20.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Move “${Store.label(node).take(40)}”",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // where in the destination it lands; remembered
                    PlaceToggle(atEnd) {
                        atEnd = it
                        Store.setMeta("moveAtEnd", if (it) "1" else "0")
                    }
                }
                DestinationPicker(
                    exclude = node,
                    onPick = { onPick(it, atEnd) },
                    modifier = Modifier.weight(1f),
                    autoFocus = true,
                    moving = node,
                )
            }
        }
    }
}

@Composable
private fun PlaceToggle(atEnd: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainerHighest).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((label, end) in listOf("Top" to false, "Bottom" to true)) {
            val on = atEnd == end
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (on) cs.onPrimary else cs.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) cs.primary else cs.surfaceContainerHighest)
                    .clickable { onChange(end) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("place-$label"),
            )
        }
    }
}
