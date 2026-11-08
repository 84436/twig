package app.twig.data

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Bullets nest at most this deep: list › list › bullet. */
const val MAX_DEPTH = 3

/**
 * One bullet. A bullet with children is a list. The whole tree lives in memory; SQLite is only a
 * write-behind store. [parent], [children] and [rank] are plain: structural changes bump [Store.version].
 */
@Stable
class Node(val id: Long, var parent: Node?, var rank: Double, text: String, url: String?, done: Boolean) {
    val children = ArrayList<Node>(0)
    val text = TextFieldState(text)
    var url by mutableStateOf(url)
    var done by mutableStateOf(done)

    val plain: String get() = text.text.toString()

    fun isAncestorOf(other: Node): Boolean {
        var p = other.parent
        while (p != null) {
            if (p === this) return true
            p = p.parent
        }
        return false
    }

    override fun toString() = "Node($id, \"$plain\")"
}
