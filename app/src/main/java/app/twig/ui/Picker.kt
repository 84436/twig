package app.twig.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.twig.data.Node
import app.twig.data.Store

@Composable
fun SearchField(
    state: TextFieldState,
    hint: String,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface)
    BasicTextField(
        state = state,
        modifier = modifier.focusRequester(focusRequester),
        textStyle = style,
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        onKeyboardAction = { onSubmit?.invoke() },
        cursorBrush = SolidColor(cs.primary),
        decorator = { inner ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(28.dp))
                    .background(cs.surfaceContainerHigh)
                    .padding(start = 16.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Ic.Search, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (state.text.isEmpty()) Text(hint, style = style, color = cs.onSurfaceVariant, maxLines = 1)
                    inner()
                }
                if (state.text.isNotEmpty()) {
                    Icon(
                        Ic.Close, "Clear",
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { state.clearText() }
                            .padding(4.dp),
                        tint = cs.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

private class Entry(val node: Node?, val header: String? = null, val note: String? = null, val primary: Boolean = false)

/**
 * Pick a destination. Built for big outlines: type to search everything (lists with children
 * rank first), or tap a quick target (top level, the item's own ancestors, this page, Inbox,
 * recents), or browse down the tree with the › buttons.
 */
@Composable
fun DestinationPicker(
    exclude: Node?,
    onPick: (Node) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
    moving: Node? = null,
) {
    val q = rememberTextFieldState()
    val fr = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    var browse by remember { mutableStateOf<Node?>(null) }
    val query = q.text.toString()
    val allowed = { n: Node -> exclude == null || (n !== exclude && !exclude.isAncestorOf(n)) }
    val entries = remember(query, browse) {
        val out = ArrayList<Entry>()
        val seen = HashSet<Long>()
        fun add(n: Node?, note: String? = null, primary: Boolean = false) {
            if (n == null || !allowed(n) || !seen.add(n.id)) return
            out.add(Entry(n, note = note, primary = primary))
        }
        val b = browse
        when {
            query.isNotBlank() -> Store.search(query, 120, preferParents = true).forEach { add(it) }
            b != null -> {
                add(b, note = "move here", primary = true)
                b.children.forEach { add(it) }
            }
            else -> {
                out.add(Entry(null, header = "Quick"))
                add(Store.root, note = "top level")
                moving?.let { m -> Store.path(m).asReversed().forEachIndexed { i, a -> add(a, if (i == 0) "current parent" else "up ${i + 1} levels") } }
                if (Store.zoom !== Store.root) add(Store.zoom, "this page")
                add(Store.inboxOrNull())
                val recents = Store.recentDestinations().filter { allowed(it) && it.id !in seen }
                if (recents.isNotEmpty()) {
                    out.add(Entry(null, header = "Recent"))
                    recents.forEach { add(it) }
                }
                val top = Store.root.children.filter { allowed(it) && it.id !in seen }
                if (top.isNotEmpty()) {
                    out.add(Entry(null, header = "Top level"))
                    top.forEach { add(it) }
                }
            }
        }
        out
    }
    BackHandler(enabled = browse != null && query.isBlank()) {
        browse = browse?.parent?.takeIf { it !== Store.root }
    }
    Column(modifier) {
        SearchField(
            q, "Search all bullets…", fr,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("pickerField"),
            onSubmit = { entries.firstOrNull { it.node != null }?.node?.let(onPick) },
        )
        val b = browse
        if (b != null && query.isBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { browse = b.parent?.takeIf { it !== Store.root } }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("browseUp"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Ic.Back, "Up", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    Store.pathLabel(b) + " › " + Store.label(b),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        LazyColumn(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            items(entries.size, key = { i -> entries[i].node?.id?.let { "n$it" } ?: "h${entries[i].header}$i" }) { i ->
                val e = entries[i]
                val n = e.node
                if (n == null) {
                    Text(
                        e.header.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp),
                    )
                } else {
                    val canBrowse = n !== browse && n.children.any(allowed)
                    val showPath = !(b != null && query.isBlank()) // the browse header already says where we are
                    DestRow(n, e.note, e.primary, showPath, if (canBrowse) ({ browse = n }) else null) { onPick(n) }
                }
            }
        }
    }
}

@Composable
private fun DestRow(n: Node, note: String?, primary: Boolean, showPath: Boolean, onBrowse: (() -> Unit)?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val icon = when {
        n === Store.root -> Ic.Home
        n === Store.inboxOrNull() -> Ic.Inbox
        else -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("dest-${n.id}")
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = cs.primary)
            else Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(if (primary) cs.primary else cs.onSurface.copy(alpha = 0.7f)))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (n === Store.root) "Home" else Store.label(n),
                style = MaterialTheme.typography.bodyLarge,
                color = if (primary) cs.primary else cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                note,
                if (showPath && n !== Store.root && n.parent !== Store.root) Store.pathLabel(n) else null,
            ).joinToString("  ·  ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onBrowse != null) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(onClick = onBrowse)
                    .testTag("browse-${n.id}"),
                contentAlignment = Alignment.Center,
            ) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = cs.onSurfaceVariant)
            }
        } else {
            Spacer(Modifier.width(44.dp))
        }
    }
}
