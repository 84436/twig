package app.twig.ui

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.twig.data.Node
import app.twig.data.Store
import app.twig.data.TextFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sign

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val zoom = Store.zoom
    val query = rememberTextFieldState()
    val q = query.text.toString()
    val selection = Store.selection
    val searching = q.isNotBlank() && selection == null
    val version = Store.version
    val results = remember(q, version) { Store.search(q) }
    val searchFocus = remember { FocusRequester() }
    var moving by remember { mutableStateOf<List<Node>?>(null) }
    var importing by remember { mutableStateOf<List<TextFormat.Item>?>(null) }
    var askTarget by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // One scroll position per list, so going back lands where you were.
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

    BackHandler(enabled = selection != null || searching || zoom !== Store.root) {
        when {
            selection != null -> Store.selection = null
            searching -> closeSearch()
            else -> Store.zoomOut()
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { Store.notice }.filterNotNull().collect { n ->
            snackbar.currentSnackbarData?.dismiss()
            launch {
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
            if (selection != null) {
                SelectionBar(selection.size, all = { Store.selection = zoom.children.mapTo(HashSet()) { it.id } })
            } else {
                SearchBar(
                    state = query,
                    focusRequester = searchFocus,
                    showBack = searching || zoom !== Store.root,
                    backLabel = if (searching) "Close search" else "Back",
                    onBack = { if (searching) closeSearch() else Store.zoomOut() },
                    onSubmit = { results.firstOrNull()?.let(open) },
                ) {
                    MoreMenu(
                        onSelect = {
                            focusManager.clearFocus()
                            Store.selection = emptySet()
                        },
                        onShareTarget = { askTarget = true },
                        onCopyAll = { Actions.copy(ctx, TextFormat.export(Store.root)) },
                        onPasteAll = { importing = TextFormat.parse(Actions.clipboardText(ctx)) },
                        onExport = { exportFile.launch("twig-${LocalDate.now()}.md") },
                        onImport = { importFile.launch(arrayOf("text/*")) },
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                if (searching) SearchResults(q, results, open)
                else Page(zoom, listState, onMove = { moving = it })
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { data -> SwipeableSnackbar(data) }
            }
            if (selection != null) BatchBar(zoom.children.filter { it.id in selection }, onMove = { moving = it })
        }
    }

    moving?.let { nodes ->
        MoveDialog(nodes, onDismiss = { moving = null }) { dest ->
            moving = null
            if (Store.move(nodes, dest)) Store.selection = null
        }
    }
    importing?.let { items ->
        ImportDialog(items.size, onDismiss = { importing = null }) {
            importing = null
            Store.replaceAll(items)
        }
    }
    if (askTarget) ShareTargetDialog { askTarget = false }
}

/** The only bar at the top: search field, with Back on the left and the menu on the right. */
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
private fun MoreMenu(
    onSelect: () -> Unit,
    onShareTarget: () -> Unit,
    onCopyAll: () -> Unit,
    onPasteAll: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    fun item(f: () -> Unit): () -> Unit = {
        open = false
        f()
    }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("more")) { Icon(Ic.More, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem("Select", Ic.CheckCircle, item(onSelect))
            MenuItem("Save shared items to…", Ic.Inbox, item(onShareTarget))
            HorizontalDivider()
            MenuItem("Copy all as text", Ic.Copy, item(onCopyAll))
            MenuItem("Replace all from clipboard", Ic.Paste, item(onPasteAll))
            MenuItem("Export to file", Ic.Down, item(onExport))
            MenuItem("Replace all from file", Ic.Up, item(onImport))
        }
    }
}

/** Batch mode's top bar. */
@Composable
private fun SelectionBar(count: Int, all: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)
            .height(52.dp)
            .testTag("selectionBar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { Store.selection = null }) { Icon(Ic.Close, "Stop selecting") }
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
            BatchAction(Ic.Check, "Done", on) { Store.setDone(nodes, true) }
            BatchAction(Ic.Undo, "Undone", on) { Store.setDone(nodes, false) }
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

@Composable
private fun MoveDialog(nodes: List<Node>, onDismiss: () -> Unit, onPick: (Node) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.8f).imePadding(),
        ) {
            Column(Modifier.padding(top = 18.dp)) {
                Text(
                    if (nodes.size == 1) "Move “${Store.label(nodes[0]).take(40)}”" else "Move ${nodes.size} bullets",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                )
                DestinationPicker(nodes, onPick, Modifier.weight(1f))
            }
        }
    }
}

/** "Always ask" shows the share sheet; a top-level bullet takes shares without asking. */
@Composable
private fun ShareTargetDialog(onDismiss: () -> Unit) {
    val current = Store.shareTarget
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(vertical = 18.dp)) {
                Text(
                    "Save shared items to",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                )
                LazyColumn(Modifier.heightIn(max = 480.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    item {
                        Choice("Always ask", current == null, "target-ask") {
                            Store.shareTarget = null
                            onDismiss()
                        }
                    }
                    items(Store.root.children, key = { it.id }) { n ->
                        Choice(Store.label(n), current === n, "target-${n.id}") {
                            Store.shareTarget = n
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(end = 16.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = on, onClick = null, modifier = Modifier.padding(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
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
