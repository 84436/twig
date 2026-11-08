package app.twig.data

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Every item is a bullet. The column stays in the DB so old databases (v1 had to-dos) still load. */
const val KIND_BULLET = 0

/**
 * One outline item. The whole tree lives in memory; SQLite is only a write-behind store.
 * Fields that the UI reads are snapshot state, so rows recompose on their own.
 * [parent], [children] and [rank] are plain: structural changes bump [Store.version].
 */
@Stable
class Node(
    val id: Long,
    var parent: Node?,
    var rank: Double,
    text: String,
    url: String?,
    note: String?,
    kind: Int,
    done: Boolean,
    collapsed: Boolean,
    val created: Long,
    var modified: Long,
    attach: String?,
) {
    val children = ArrayList<Node>(0)
    val text = TextFieldState(text)
    var url by mutableStateOf(url)
    var note by mutableStateOf(note)
    var kind by mutableIntStateOf(kind)
    var done by mutableStateOf(done)
    var collapsed by mutableStateOf(collapsed)
    var attach by mutableStateOf(attach)

    val plain: String get() = text.text.toString()

    fun isAncestorOf(other: Node): Boolean {
        var p = other.parent
        while (p != null) {
            if (p === this) return true
            p = p.parent
        }
        return false
    }

    fun toRow() = NodeRow(
        id = id,
        parent = parent?.id ?: 0L,
        rank = rank,
        text = plain,
        url = url,
        note = note,
        kind = kind,
        done = done,
        collapsed = collapsed,
        created = created,
        modified = modified,
        attach = attach,
    )

    override fun toString() = "Node($id, \"$plain\")"
}

/** A visible line in the flattened outline. */
@Immutable
data class FlatRow(val node: Node, val depth: Int, val hasChildren: Boolean)

/** Plain row as stored in SQLite. */
class NodeRow(
    val id: Long,
    val parent: Long,
    val rank: Double,
    val text: String,
    val url: String?,
    val note: String?,
    val kind: Int,
    val done: Boolean,
    val collapsed: Boolean,
    val created: Long,
    val modified: Long,
    val attach: String?,
)
