package app.twig.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.twig.data.Node
import app.twig.data.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull

@Composable
fun Outline(
    zoom: Node,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onMoveTo: (Node) -> Unit,
) {
    val ctx = LocalContext.current
    val version = Store.version
    val hide = Store.hideCompleted
    val rows = remember(version, zoom, hide) { Store.flatten(zoom) }
    val rowsState = rememberUpdatedState(rows)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val indentPx = with(density) { INDENT.toPx() }
    val drag = remember(listState) {
        with(density) { DragState(listState, scope, INDENT.toPx(), 72.dp.toPx(), 20.dp.toPx()) }
    }
    drag.onDepthChanged = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val hasHeader = zoom !== Store.root
    val headerCount = if (hasHeader) 1 else 0
    var menuFor by remember { mutableStateOf<Long?>(null) }
    val highlight = Store.highlightId
    val focusedId = Store.focusedId

    val actions = remember(drag) {
        object : RowActions {
            override fun onTapBullet(node: Node) {
                Store.zoom = node
            }

            override fun onDragStart(node: Node, pickedUp: Boolean): Boolean {
                menuFor = null
                val z = Store.zoom
                return drag.start(node, rowsState.value, if (z !== Store.root) 1 else 0, z)
            }

            override fun onDrag(delta: Offset) = drag.drag(delta)

            override fun onDragEnd(node: Node, moved: Boolean, pickedUp: Boolean, cancelled: Boolean) {
                if (pickedUp && !moved) {
                    drag.end(commit = false)
                    menuFor = node.id
                } else {
                    drag.end(commit = !cancelled)
                }
            }

            override fun onEnter(node: Node, before: String, parts: List<String>, after: String) =
                Store.onEnter(node, before, parts, after)

            override fun onBackspaceEmpty(node: Node): Boolean {
                val list = rowsState.value
                val i = list.indexOfFirst { it.node === node }
                val prev = list.getOrNull(i - 1)?.node ?: Store.zoom.takeIf { it !== Store.root }
                return Store.backspaceEmpty(node, prev)
            }

            override fun onToggleCollapse(node: Node) = Store.toggleCollapsed(node)
            override fun onToggleDone(node: Node) = Store.toggleDone(node)
            override fun onOpenUrl(url: String) = Actions.openUrl(ctx, url)
            override fun onCopyUrl(url: String) = Actions.copy(ctx, url, "Link copied")
            override fun onOpenAttachment(name: String) = Actions.openImage(ctx, name)
            override fun onMenuDismiss() {
                menuFor = null
            }

            override fun onMenuAction(node: Node, action: MenuAction) {
                menuFor = null
                when (action) {
                    MenuAction.OpenLink -> node.url?.let { Actions.openUrl(ctx, it) }
                    MenuAction.CopyLink -> node.url?.let { Actions.copy(ctx, it, "Link copied") }
                    // after the menu's popup window is gone, so the keyboard can come up
                    MenuAction.EditLink -> Store.main.postDelayed({ Store.editLink(node) }, 120)
                    MenuAction.RemoveLink -> Store.removeLink(node)
                    MenuAction.Done -> Store.toggleDone(node)
                    MenuAction.Zoom -> Store.zoom = node
                    MenuAction.MoveTo -> onMoveTo(node)
                    MenuAction.CopyText -> Actions.copy(ctx, node.plain, "Copied")
                    MenuAction.Share -> Actions.share(ctx, node)
                    MenuAction.Delete -> Store.delete(node)
                }
            }
        }
    }

    // Bring a row into view before it takes focus (new items, Enter at the bottom, ...).
    LaunchedEffect(listState) {
        snapshotFlow { Store.focusReq }.filterNotNull().collect { req ->
            val z = Store.zoom
            val idx = Store.flatten(z).indexOfFirst { it.node.id == req.nodeId }
            if (idx < 0) return@collect
            val li = idx + if (z !== Store.root) 1 else 0
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == li }) {
                listState.scrollToItem(maxOf(0, li - 2))
            }
        }
    }

    // Search results and zoom-outs land here: scroll to the row and flash it.
    LaunchedEffect(highlight, zoom) {
        val id = highlight ?: return@LaunchedEffect
        val idx = rows.indexOfFirst { it.node.id == id }
        if (idx >= 0) {
            val li = idx + headerCount
            val vis = listState.layoutInfo.visibleItemsInfo
            if (vis.none { it.index == li } || vis.isEmpty()) listState.scrollToItem(maxOf(0, li - 2))
        }
        delay(1200)
        if (Store.highlightId == id) Store.highlightId = null
    }

    val shown = drag.preview ?: rows
    // Keep every item (and so the start of a swipe) out of the system back-gesture strip.
    // Zero with 3-button navigation and before Android 10.
    val dir = LocalLayoutDirection.current
    val edge = WindowInsets.systemGestures.only(WindowInsetsSides.Start).asPaddingValues().calculateStartPadding(dir)
    val padding = PaddingValues(
        start = contentPadding.calculateStartPadding(dir) + (edge - START_PAD).coerceAtLeast(0.dp),
        top = contentPadding.calculateTopPadding(),
        end = contentPadding.calculateEndPadding(dir),
        bottom = contentPadding.calculateBottomPadding(),
    )
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("outline"),
        contentPadding = padding,
    ) {
        if (hasHeader) {
            // keyed by zoom: a new page gets a fresh header (and its own focus/edit state)
            item(key = "header-${zoom.id}", contentType = "header") { ZoomHeader(zoom, focusedId == zoom.id, actions) }
        }
        items(shown, key = { it.node.id }, contentType = { "row" }) { row ->
            val id = row.node.id
            val isDragged = drag.draggingId == id
            val settling = !isDragged && drag.settlingId == id
            val shift = remember { Animatable(0f) }
            val target = if (isDragged) (drag.targetDepth - drag.originDepth).toFloat() else 0f
            LaunchedEffect(isDragged, target) {
                if (isDragged) shift.animateTo(target, spring(stiffness = 900f)) else shift.snapTo(0f)
            }
            val m = when {
                isDragged -> Modifier
                    .zIndex(2f)
                    .graphicsLayer {
                        translationY = drag.offsetY()
                        translationX = shift.value * indentPx
                    }
                settling -> Modifier
                    .zIndex(1f)
                    .graphicsLayer { translationY = drag.settle.value }
                else -> Modifier.animateItem(
                    fadeInSpec = tween(140),
                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold),
                    fadeOutSpec = tween(90),
                )
            }
            OutlineRow(
                row = row,
                dragged = isDragged,
                highlighted = highlight == id,
                menuOpen = menuFor == id,
                editing = focusedId == id,
                carried = if (isDragged) drag.carried else 0,
                actions = actions,
                modifier = m,
            )
        }
        if (drag.preview == null) {
            item(key = "add", contentType = "add") { AddBulletHint { Store.addAtEnd(Store.zoom) } }
        }
        // room to scroll the last items above the keyboard; deliberately not tappable
        item(key = "footer", contentType = "footer") { Spacer(Modifier.fillMaxWidth().height(160.dp).testTag("footer")) }
    }
}

@Composable
private fun ZoomHeader(zoom: Node, editing: Boolean, actions: RowActions) {
    val cs = MaterialTheme.colorScheme
    val fr = remember { FocusRequester() }
    TrackFocusAndEdits(zoom, fr)
    EditSession(zoom, editing)
    val transformation = remember(zoom.id) {
        SplitOnNewline { before, parts, after -> Store.onHeaderEnter(zoom, before, parts, after) }
    }
    val done = zoom.done
    val style = MaterialTheme.typography.headlineSmall.copy(
        color = if (done) cs.onSurface.copy(alpha = 0.45f) else cs.onSurface,
        fontWeight = FontWeight.SemiBold,
        textDecoration = if (done) TextDecoration.LineThrough else null,
    )
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 2.dp, bottom = 10.dp)) {
        BasicTextField(
            state = zoom.text,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("header")
                .focusRequester(fr)
                .trackFocus(zoom),
            textStyle = style,
            inputTransformation = transformation,
            lineLimits = TextFieldLineLimits.MultiLine(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            cursorBrush = SolidColor(cs.primary),
        )
        val url = zoom.url
        if (editing && url != null) UrlField(zoom, actions)
        else if (!url.isNullOrBlank()) LinkLine(zoom, actions::onOpenUrl, actions::onCopyUrl)
        zoom.attach?.let { Thumb(it) { actions.onOpenAttachment(it) } }
    }
}

/** Low-key "+ Add bullet" at the end of the list. Only the icon and the words are tappable. */
@Composable
private fun AddBulletHint(onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    Row(Modifier.fillMaxWidth().padding(start = START_PAD + CHEVRON_W, top = 2.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(end = 12.dp)
                .height(LINE_BOX)
                .testTag("addBullet"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(BULLET_W, LINE_BOX), contentAlignment = Alignment.Center) {
                Icon(Ic.Add, null, Modifier.size(18.dp), tint = ink)
            }
            Text("Add bullet", style = MaterialTheme.typography.bodyLarge, color = ink)
        }
    }
}
