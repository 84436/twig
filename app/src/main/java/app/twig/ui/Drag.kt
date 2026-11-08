package app.twig.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import app.twig.data.Node
import app.twig.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drag to reorder bullets on one page. While dragging, the list shows [preview] (the page's bullets
 * with the dragged one wherever the finger is); on drop that position goes to the store.
 */
class DragState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val edgePx: Float,
    private val maxSpeedPx: Float,
) {
    var draggingId by mutableStateOf<Long?>(null)
        private set
    var preview by mutableStateOf<List<Node>?>(null)
        private set

    /** Row that was just dropped and is easing into its slot. */
    var settlingId by mutableStateOf<Long?>(null)
        private set
    val settle = Animatable(0f)

    private var dy by mutableFloatStateOf(0f)
    private var startOffset = 0
    private var headerCount = 0
    private var autoScroll: Job? = null

    private fun info() = draggingId?.let { id -> listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } }

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

    fun start(node: Node, rows: List<Node>, headerCount: Int): Boolean {
        if (draggingId != null) return false
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == node.id } ?: return false
        preview = rows
        startOffset = item.offset
        dy = 0f
        this.headerCount = headerCount
        draggingId = node.id
        autoScroll?.cancel()
        autoScroll = scope.launch { autoScrollLoop() }
        return true
    }

    fun drag(deltaY: Float) {
        if (draggingId == null) return
        dy += deltaY
        reorder()
    }

    private fun reorder() {
        val list = preview ?: return
        val id = draggingId ?: return
        val k = list.indexOfFirst { it.id == id }
        val me = info() ?: return
        if (k < 0) return
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
        preview = list.toMutableList().apply { add(newK, removeAt(k)) }
        // LazyColumn anchors on the first visible key; keep the viewport still when that row moves.
        if (firstIdx == k + headerCount || firstIdx == newK + headerCount) {
            scope.launch { listState.scrollToItem(firstIdx, firstOff) }
        }
    }

    private suspend fun autoScrollLoop() {
        while (scope.isActive && draggingId != null) {
            withFrameNanos { }
            val me = info() ?: continue
            val top = visualTop(me.size)
            val bottom = top + me.size
            val vpEnd = listState.layoutInfo.viewportSize.height.toFloat()
            val speed = when {
                top < edgePx -> -maxSpeedPx * ((edgePx - top) / edgePx).coerceIn(0f, 1f)
                bottom > vpEnd - edgePx -> maxSpeedPx * ((bottom - (vpEnd - edgePx)) / edgePx).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f && listState.scrollBy(speed) != 0f) reorder()
        }
    }

    /** Ends the drag; with [commit] the new position is saved. */
    fun end(commit: Boolean) {
        val id = draggingId ?: return
        autoScroll?.cancel()
        autoScroll = null
        val list = preview
        val fromY = offsetY()
        if (commit && list != null) {
            val k = list.indexOfFirst { it.id == id }
            if (k >= 0) Store.reorder(list[k], k)
        }
        draggingId = null
        preview = null
        dy = 0f
        settlingId = id
        scope.launch {
            settle.snapTo(fromY)
            settle.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
            if (settlingId == id) settlingId = null
        }
    }
}
