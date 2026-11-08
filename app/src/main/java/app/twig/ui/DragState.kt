package app.twig.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import app.twig.data.FlatRow
import app.twig.data.Node
import app.twig.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Drag & drop for the flattened outline.
 *
 * While dragging, the list shows [preview]: the real rows minus the dragged item's descendants
 * (they travel with it), with the dragged row moved to wherever the finger is. Horizontal
 * movement picks the nesting depth. On drop the preview position + depth is turned into a
 * (parent, after-sibling) pair and committed to the store.
 */
class DragState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val indentPx: Float,
    private val edgePx: Float,
    private val maxSpeedPx: Float,
) {
    var draggingId by mutableStateOf<Long?>(null)
        private set
    var preview by mutableStateOf<List<FlatRow>?>(null)
        private set
    var targetDepth by mutableIntStateOf(0)
        private set
    var originDepth = 0
        private set
    var carried by mutableIntStateOf(0)
        private set

    /** Row that was just dropped and is easing into its slot. */
    var settlingId by mutableStateOf<Long?>(null)
        private set
    val settle = Animatable(0f)

    private var dy by mutableFloatStateOf(0f)
    private var dx = 0f
    private var startOffset = 0
    private var headerCount = 0
    private var zoom: Node? = null
    private var autoScroll: Job? = null
    var onDepthChanged: (() -> Unit)? = null

    val isDragging: Boolean get() = draggingId != null

    private fun info(): LazyListItemInfo? {
        val id = draggingId ?: return null
        return listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
    }

    /** Where the dragged row's top edge is drawn: under the finger, but never off the list. */
    private fun visualTop(size: Int): Float {
        val vp = listState.layoutInfo.viewportSize.height.toFloat()
        return (startOffset + dy).coerceIn(0f, maxOf(vp - size, 0f))
    }

    /** Translation that keeps the dragged row under the finger. Read during layout/draw only. */
    fun offsetY(): Float {
        val i = info() ?: return 0f
        return visualTop(i.size) - i.offset
    }

    fun start(node: Node, rows: List<FlatRow>, headerCount: Int, zoom: Node): Boolean {
        if (draggingId != null) return false
        val idx = rows.indexOfFirst { it.node === node }
        if (idx < 0) return false
        val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == node.id } ?: return false
        val row = rows[idx]
        var end = idx + 1
        while (end < rows.size && rows[end].depth > row.depth) end++
        carried = end - idx - 1
        preview = ArrayList<FlatRow>(rows.size).apply {
            addAll(rows.subList(0, idx + 1))
            addAll(rows.subList(end, rows.size))
        }
        startOffset = itemInfo.offset
        dy = 0f
        dx = 0f
        originDepth = row.depth
        targetDepth = row.depth
        this.headerCount = headerCount
        this.zoom = zoom
        draggingId = node.id
        autoScroll?.cancel()
        autoScroll = scope.launch { autoScrollLoop() }
        return true
    }

    fun drag(delta: Offset) {
        if (draggingId == null) return
        dy += delta.y
        dx += delta.x
        reorder()
        updateDepth()
    }

    private fun reorder() {
        val list = preview ?: return
        val id = draggingId ?: return
        val k = list.indexOfFirst { it.node.id == id }
        if (k < 0) return
        val me = info() ?: return
        val size = me.size
        val center = visualTop(size) + size / 2f
        var newK = -1
        var firstW = -1
        // Positions are measured as if the dragged row were not in the list, so a swap never
        // changes the decision that caused it (no flicker with rows of different heights).
        for (it in listState.layoutInfo.visibleItemsInfo) {
            val key = it.key as? Long ?: continue
            if (key == id) continue
            val li = it.index - headerCount
            if (li !in list.indices) continue
            val above = li < k
            val pos = if (above) it.offset else it.offset - size
            val w = if (above) li else li - 1
            if (firstW < 0) firstW = w
            if (pos + it.size / 2f < center) newK = w + 1
        }
        if (newK < 0) {
            if (firstW < 0) return
            newK = firstW
        }
        newK = newK.coerceIn(0, list.size - 1)
        if (newK == k) return
        val firstIdx = listState.firstVisibleItemIndex
        val firstOff = listState.firstVisibleItemScrollOffset
        val m = ArrayList(list)
        val item = m.removeAt(k)
        m.add(newK, item)
        preview = m
        // LazyColumn anchors on the first visible key; keep the viewport still when that row moves.
        if (firstIdx == k + headerCount || firstIdx == newK + headerCount) {
            scope.launch { listState.scrollToItem(firstIdx, firstOff) }
        }
    }

    private fun updateDepth() {
        val list = preview ?: return
        val k = list.indexOfFirst { it.node.id == draggingId }
        if (k < 0) return
        val prev = list.getOrNull(k - 1)
        val next = list.getOrNull(k + 1)
        val maxD = prev?.let { it.depth + 1 } ?: 0
        val minD = next?.depth ?: 0
        val want = originDepth + (dx / indentPx).roundToInt()
        val d = want.coerceIn(minD, maxOf(minD, maxD))
        if (d != targetDepth) {
            targetDepth = d
            onDepthChanged?.invoke()
        }
    }

    private suspend fun autoScrollLoop() {
        while (scope.isActive && draggingId != null) {
            androidx.compose.runtime.withFrameNanos { }
            val me = info() ?: continue
            val top = visualTop(me.size)
            val bottom = top + me.size
            val vpEnd = listState.layoutInfo.viewportSize.height.toFloat()
            val speed = when {
                top < edgePx -> -maxSpeedPx * ((edgePx - top) / edgePx).coerceIn(0f, 1f)
                bottom > vpEnd - edgePx -> maxSpeedPx * ((bottom - (vpEnd - edgePx)) / edgePx).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f) {
                val used = listState.scrollBy(speed)
                if (used != 0f) {
                    reorder()
                    updateDepth()
                }
            }
        }
    }

    /**
     * Ends the drag. With [commit] the move is written to the store.
     * Returns true when the tree changed.
     */
    fun end(commit: Boolean): Boolean {
        val id = draggingId ?: return false
        autoScroll?.cancel()
        autoScroll = null
        val list = preview
        val z = zoom
        val fromY = offsetY()
        var changed = false
        if (commit && list != null && z != null) {
            val k = list.indexOfFirst { it.node.id == id }
            if (k >= 0) {
                val (parent, after) = dropTarget(list, k, targetDepth, z)
                changed = Store.moveTo(list[k].node, parent, after)
            }
        }
        draggingId = null
        preview = null
        carried = 0
        dy = 0f
        dx = 0f
        settlingId = id
        scope.launch {
            settle.snapTo(fromY)
            settle.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
            if (settlingId == id) settlingId = null
        }
        return changed
    }

    companion object {
        /** Where a row at index [k] of [list] with depth [d] belongs. */
        fun dropTarget(list: List<FlatRow>, k: Int, d: Int, zoom: Node): Pair<Node, Node?> {
            var parent = zoom
            if (d > 0) {
                for (i in k - 1 downTo 0) {
                    if (list[i].depth == d - 1) {
                        parent = list[i].node
                        break
                    }
                }
            }
            var after: Node? = null
            for (i in k - 1 downTo 0) {
                val r = list[i]
                if (r.depth < d) break
                if (r.depth == d) {
                    after = r.node
                    break
                }
            }
            return parent to after
        }
    }
}
