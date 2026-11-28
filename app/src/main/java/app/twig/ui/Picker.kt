package app.twig.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch
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
 * Where should bullets go? A bottom sheet with Cancel and [title] on top, then [top] (e.g. what's
 * being shared), then Home and the top-level bullets that can take [moving]; search narrows them
 * down. With [ask], "Always ask" comes first and is picked as null. [marked] gets a check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSheet(
    title: String,
    onDismiss: () -> Unit,
    onPick: (Node?) -> Unit,
    moving: List<Node> = emptyList(),
    ask: Boolean = false,
    marked: Node? = null,
    top: @Composable () -> Unit = {},
) {
    val sheet = rememberBottomSheetState(SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    // slide away first, then act
    fun close(then: () -> Unit) = scope.launch { sheet.hide() }.invokeOnCompletion { then() }
    val q = rememberTextFieldState()
    val fr = remember { FocusRequester() }
    val query = q.text.toString()
    val entries = remember(query) {
        val ok = { n: Node -> Store.canHold(n, moving) }
        if (query.isNotBlank()) Store.search(query, 120).filter(ok) else listOf(Store.root) + Store.root.children.filter(ok)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, modifier = Modifier.testTag("locationSheet")) {
        Row(Modifier.padding(start = 4.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Tip("Cancel") { IconButton(onClick = { close(onDismiss) }, modifier = Modifier.testTag("cancel")) { Icon(Ic.Close, "Cancel") } }
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        top()
        SearchField(q, "Search lists…", fr, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("pickerField")) {
            if (query.isNotBlank()) entries.firstOrNull()?.let { n -> close { onPick(n) } }
        }
        LazyColumn(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
            if (ask && query.isBlank()) item(key = "ask") { DestRow(null, marked == null) { close { onPick(null) } } }
            items(entries, key = { it.id }) { n -> DestRow(n, marked === n) { close { onPick(n) } } }
        }
    }
}

/** A place: Home, a top-level bullet, or (null) "Always ask". */
@Composable
private fun DestRow(n: Node?, marked: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("dest-${n?.id ?: "ask"}")
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                n === Store.root -> Icon(Ic.Home, null, Modifier.size(20.dp), tint = cs.primary)
                n != null -> Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(cs.onSurface.copy(alpha = 0.7f)))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(n?.let { Store.label(it) } ?: "Always ask", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        when {
            marked -> Icon(Ic.Check, "Current", Modifier.size(20.dp), tint = cs.primary)
            n != null && n.children.isNotEmpty() -> Text("${n.children.size}", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        }
    }
}
