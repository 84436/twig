package app.twig.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.twig.data.Node
import app.twig.data.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull

/** One list, like a folder in a file manager: its bullets, then "Add bullet". */
@Composable
fun Page(page: Node, listState: LazyListState, onMove: (List<Node>) -> Unit) {
    val ctx = LocalContext.current
    val version = Store.version
    val rows = remember(version, page) { page.children.toList() }
    val rowsState = rememberUpdatedState(rows)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val drag = remember(listState) { with(density) { DragState(listState, scope, 72.dp.toPx(), 20.dp.toPx()) } }
    val canOpen = page === Store.root
    var menuFor by remember { mutableStateOf<Long?>(null) }
    val highlight = Store.highlightId
    val focusedId = Store.focusedId
    val selection = Store.selection
    val readOnly = Store.readOnly

    val actions = remember(drag, page) {
        object : RowActions {
            override fun dragStart(node: Node): Boolean {
                val selection = Store.selection
                if (selection != null && node.id !in selection) return false
                menuFor = null
                Store.revealedId = null
                return drag.start(node, rowsState.value, selection)
            }

            override fun drag(dy: Float) = drag.drag(dy)

            override fun dragEnd(node: Node, moved: Boolean, cancelled: Boolean) {
                drag.end(commit = moved && !cancelled)
                if (!moved && Store.selection == null) menuFor = node.id
            }

            override fun menuDismiss() {
                menuFor = null
            }

            override fun menu(node: Node, action: MenuAction) {
                menuFor = null
                when (action) {
                    MenuAction.MoveTo -> onMove(listOf(node))
                    MenuAction.Select -> Store.selection = setOf(node.id)
                    MenuAction.Copy -> Actions.copy(ctx, Actions.textOf(node))
                    MenuAction.Share -> Actions.share(ctx, node)
                }
            }
        }
    }

    // Bring a bullet into view before it takes focus (new bullets, Enter at the bottom, ...).
    LaunchedEffect(listState) {
        snapshotFlow { Store.focusReq }.filterNotNull().collect { req ->
            val idx = Store.zoom.children.indexOfFirst { it.id == req.nodeId }
            if (idx >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == idx }) listState.scrollToItem(maxOf(0, idx - 2))
        }
    }
    // A Delete button doesn't stay out once you scroll or start typing somewhere.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { if (it) Store.revealedId = null }
    }
    LaunchedEffect(focusedId) { Store.revealedId = null }

    // Search results land here: scroll to the bullet and flash it.
    LaunchedEffect(highlight, page) {
        val id = highlight ?: return@LaunchedEffect
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == idx }) listState.scrollToItem(maxOf(0, idx - 2))
        delay(1200)
        if (Store.highlightId == id) Store.highlightId = null
    }

    // A list holds on to its first visible row when rows move, so a bullet moved past it would
    // leave the screen. Ask for the scroll after recomposing with the new order and before laying it out.
    SideEffect {
        val id = Store.keepInView ?: return@SideEffect
        Store.keepInView = null
        val i = rows.indexOfFirst { it.id == id }
        val vis = listState.layoutInfo.visibleItemsInfo
        if (i >= 0 && vis.isNotEmpty() && (i <= vis.first().index || i >= vis.last().index)) listState.requestScrollToItem(maxOf(0, i - 1))
    }

    val shown = drag.preview ?: rows
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("page"),
        contentPadding = PaddingValues(top = 2.dp, bottom = 8.dp),
    ) {
        items(shown, key = { it.id }, contentType = { "row" }) { n ->
            val isDragged = drag.draggingId == n.id
            val m = when {
                isDragged -> Modifier.zIndex(2f).graphicsLayer { translationY = drag.offsetY() }
                drag.settlingId == n.id -> Modifier.zIndex(1f).graphicsLayer { translationY = drag.settle.value }
                else -> Modifier.animateItem(
                    fadeInSpec = tween(140),
                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold),
                    fadeOutSpec = tween(90),
                )
            }
            BulletRow(
                node = n,
                kids = n.children.size,
                canOpen = canOpen,
                editing = focusedId == n.id,
                highlighted = highlight == n.id,
                dragged = isDragged,
                carrying = if (isDragged) drag.group.size - 1 else 0,
                menuOpen = menuFor == n.id,
                selected = selection?.let { n.id in it },
                readOnly = readOnly,
                actions = actions,
                modifier = m,
            )
        }
        if (drag.preview == null && selection == null && !readOnly) {
            item(key = "add", contentType = "add") { AddBulletHint { Store.addAtEnd(page) } }
        }
        // room to scroll the last bullets above the keyboard; deliberately not tappable
        item(key = "footer", contentType = "footer") { Spacer(Modifier.fillMaxWidth().height(160.dp).testTag("footer")) }
    }
}

/** Low-key "+ Add bullet" at the end of the list. Only the icon and the words are tappable. */
@Composable
private fun AddBulletHint(onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    Row(Modifier.fillMaxWidth().padding(start = START_PAD, top = 2.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(end = 12.dp)
                .height(lineBox())
                .testTag("addBullet"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(BULLET_W, lineBox()), contentAlignment = Alignment.Center) {
                Icon(Ic.Add, null, Modifier.size(18.dp), tint = ink)
            }
            Text("Add bullet", style = MaterialTheme.typography.bodyLarge, color = ink)
        }
    }
}
