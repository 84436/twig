package app.twig.ui

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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.remember
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
    onSubmit: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface)
    BasicTextField(
        state = state,
        modifier = modifier.focusRequester(focusRequester),
        textStyle = style,
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        onKeyboardAction = { onSubmit() },
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
                        Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).clickable { state.clearText() }.padding(4.dp),
                        tint = cs.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/**
 * Where should [moving] go? Home, every top-level bullet and the lists under them, or type to
 * search everything. Only places that keep the tree within three levels are offered.
 */
@Composable
fun DestinationPicker(moving: List<Node>, onPick: (Node) -> Unit, modifier: Modifier = Modifier, autoFocus: Boolean = true) {
    val q = rememberTextFieldState()
    val fr = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val query = q.text.toString()
    val entries = remember(query) {
        val ok = { n: Node -> Store.canHold(n, moving) }
        if (query.isNotBlank()) {
            Store.search(query, 120).filter(ok)
        } else {
            buildList {
                add(Store.root)
                for (a in Store.root.children) {
                    if (ok(a)) add(a)
                    for (b in a.children) if (b.children.isNotEmpty() && ok(b)) add(b)
                }
            }
        }
    }
    Column(modifier) {
        SearchField(q, "Search lists…", fr, Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("pickerField")) {
            if (query.isNotBlank()) entries.firstOrNull()?.let(onPick)
        }
        LazyColumn(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            items(entries, key = { it.id }) { n -> DestRow(n, searching = query.isNotBlank()) { onPick(n) } }
        }
    }
}

@Composable
private fun DestRow(n: Node, searching: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val nested = !searching && Store.depth(n) == 2
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("dest-${n.id}")
            .clickable(onClick = onClick)
            .padding(start = if (nested) 44.dp else 20.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (n === Store.root) Icon(Ic.Home, null, Modifier.size(20.dp), tint = cs.primary)
            else Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(cs.onSurface.copy(alpha = 0.7f)))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(Store.label(n), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (searching && n.parent !== Store.root) {
                Text(Store.pathLabel(n), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (n.children.isNotEmpty()) Text("${n.children.size}", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
    }
}
