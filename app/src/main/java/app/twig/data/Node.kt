package app.twig.data

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf

/** Bullets nest at most this deep: list › bullet. */
const val MAX_DEPTH = 2

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

    override fun toString() = "Node($id, \"$plain\")"
}
