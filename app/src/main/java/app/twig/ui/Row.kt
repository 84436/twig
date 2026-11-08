@file:OptIn(ExperimentalFoundationApi::class)

package app.twig.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.twig.data.Attachments
import app.twig.data.FIELD_TEXT
import app.twig.data.FIELD_URL
import app.twig.data.FlatRow
import app.twig.data.FocusReq
import app.twig.data.Node
import app.twig.data.Store
import app.twig.share.LinkInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal val INDENT = 24.dp
internal val CHEVRON_W = 22.dp
internal val BULLET_W = 26.dp
internal val LINE_BOX = 38.dp
// Minimum gap at the left; Outline adds more when the system back-gesture strip is wider.
internal val START_PAD = 6.dp
private val SWIPE_DONE = 76.dp
private const val LONG_PRESS_MS = 280L

enum class MenuAction { OpenLink, CopyLink, EditLink, RemoveLink, Done, Zoom, MoveTo, CopyText, Share, Delete }

interface RowActions {
    fun onTapBullet(node: Node)
    fun onDragStart(node: Node, pickedUp: Boolean): Boolean
    fun onDrag(delta: Offset)
    fun onDragEnd(node: Node, moved: Boolean, pickedUp: Boolean, cancelled: Boolean)
    fun onEnter(node: Node, before: String, parts: List<String>, after: String)
    fun onBackspaceEmpty(node: Node): Boolean
    fun onToggleCollapse(node: Node)
    fun onToggleDone(node: Node)
    fun onOpenUrl(url: String)
    fun onCopyUrl(url: String)
    fun onOpenAttachment(name: String)
    fun onMenuDismiss()
    fun onMenuAction(node: Node, action: MenuAction)
}

@Composable
fun OutlineRow(
    row: FlatRow,
    dragged: Boolean,
    highlighted: Boolean,
    menuOpen: Boolean,
    editing: Boolean,
    carried: Int,
    actions: RowActions,
    modifier: Modifier = Modifier,
) {
    val node = row.node
    val cs = MaterialTheme.colorScheme
    val guide = cs.outlineVariant
    val bg by animateColorAsState(
        if (highlighted) cs.primary.copy(alpha = 0.16f) else cs.primary.copy(alpha = 0f),
        tween(if (highlighted) 120 else 900),
        label = "hl",
    )
    val depth = row.depth
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { SWIPE_DONE.toPx() }
    val flingPx = with(LocalDensity.current) { 900.dp.toPx() } // per second
    val swipe = remember { Animatable(0f) }
    val armed = remember { BooleanArray(1) }
    val swipeIcon = rememberVectorPainter(if (node.done) Ic.Undo else Ic.Check)
    val swipeIdle = cs.surfaceContainerHighest
    val swipeGo = cs.primary
    val swipeIdleInk = cs.onSurfaceVariant
    val swipeGoInk = cs.onPrimary

    EditSession(node, editing)

    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                // guides stay put while the row content slides during a swipe
                if (!dragged) drawGuides(depth, guide)
                val x = swipe.value
                if (x <= 0f) return@drawBehind
                val go = x >= threshold
                val x0 = (START_PAD + INDENT * depth).toPx()
                drawRect(if (go) swipeGo else swipeIdle, topLeft = Offset(x0, 0f), size = Size(x, size.height))
                val icon = 22.dp.toPx()
                val left = 14.dp.toPx()
                if (x > left + icon) {
                    translate(x0 + left, (LINE_BOX.toPx() - icon) / 2) {
                        with(swipeIcon) { draw(Size(icon, icon), colorFilter = ColorFilter.tint(if (go) swipeGoInk else swipeIdleInk)) }
                    }
                }
            }
            .pointerInput(node.id, actions) {
                swipeRight(
                    onMove = { dx ->
                        val v = if (dx <= threshold) dx else threshold + (dx - threshold) * 0.35f
                        val go = dx >= threshold
                        if (go != armed[0]) {
                            armed[0] = go
                            if (go) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        scope.launch { swipe.snapTo(v.coerceAtLeast(0f)) }
                    },
                    onEnd = { dx, vx ->
                        armed[0] = false
                        if (dx >= threshold || (dx >= threshold * 0.5f && vx > flingPx)) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            actions.onToggleDone(node)
                        }
                        scope.launch { swipe.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                    },
                )
            }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = swipe.value }
                .then(
                    if (dragged) {
                        Modifier
                            .shadow(10.dp, RoundedCornerShape(12.dp))
                            .background(cs.surfaceContainerHigh, RoundedCornerShape(12.dp))
                    } else {
                        Modifier.background(bg)
                    }
                )
                .padding(start = START_PAD + INDENT * depth, end = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Chevron(row, actions)
            Bullet(row, menuOpen, actions)
            Column(Modifier.weight(1f).padding(top = 7.dp, bottom = 7.dp)) {
                NodeText(node, actions)
                val url = node.url
                if (editing && url != null) UrlField(node, actions)
                else if (!url.isNullOrBlank()) LinkLine(node, actions::onOpenUrl, actions::onCopyUrl)
                node.attach?.let { Thumb(it) { actions.onOpenAttachment(it) } }
            }
            if (dragged && carried > 0) {
                Text(
                    "+$carried",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onPrimaryContainer,
                    modifier = Modifier
                        .padding(top = 10.dp, start = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(cs.primaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private fun DrawScope.drawGuides(depth: Int, color: Color) {
    if (depth == 0) return
    val w = 1.dp.toPx()
    for (i in 0 until depth) {
        val x = (START_PAD + INDENT * i + CHEVRON_W + BULLET_W / 2).toPx()
        drawLine(color, Offset(x, 0f), Offset(x, size.height), w)
    }
}

@Composable
private fun Chevron(row: FlatRow, actions: RowActions) {
    val node = row.node
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val rot by animateFloatAsState(if (node.collapsed) 0f else 90f, tween(140), label = "chev")
    Box(
        Modifier
            .size(CHEVRON_W, LINE_BOX)
            .testTag("chevron-${node.id}")
            .then(
                if (row.hasChildren) Modifier.clickable(interactionSource = null, indication = null) { actions.onToggleCollapse(node) }
                else Modifier
            )
    ) {
        if (row.hasChildren) {
            Canvas(Modifier.size(CHEVRON_W, LINE_BOX)) {
                rotate(rot) {
                    val s = 4.5.dp.toPx()
                    val c = center
                    val p = Path().apply {
                        moveTo(c.x - s * 0.55f, c.y - s)
                        lineTo(c.x + s * 0.75f, c.y)
                        lineTo(c.x - s * 0.55f, c.y + s)
                        close()
                    }
                    drawPath(p, color.copy(alpha = 0.75f))
                }
            }
        }
    }
}

@Composable
private fun Bullet(row: FlatRow, menuOpen: Boolean, actions: RowActions) {
    val node = row.node
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val dot = if (!node.url.isNullOrBlank()) cs.primary else cs.onSurface.copy(alpha = 0.72f)
    val ring = cs.onSurface.copy(alpha = 0.14f)
    val hasKids = row.hasChildren
    Box(
        Modifier
            .size(BULLET_W, LINE_BOX)
            .testTag("bullet-${node.id}")
            .pointerInput(node.id, actions) {
                bulletGestures(
                    onTap = { actions.onTapBullet(node) },
                    onStart = {
                        val ok = actions.onDragStart(node, true)
                        if (ok) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        ok
                    },
                    onDrag = { actions.onDrag(it) },
                    onEnd = { moved, cancelled -> actions.onDragEnd(node, moved, true, cancelled) },
                )
            }
            .drawBehind {
                val alpha = if (node.done) 0.4f else 1f
                if (hasKids && node.collapsed) drawCircle(ring, radius = 9.dp.toPx(), alpha = alpha)
                drawCircle(dot, radius = 3.5.dp.toPx(), alpha = alpha)
            }
    ) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = actions::onMenuDismiss) {
            val hasLink = !node.url.isNullOrBlank()
            if (hasLink) {
                MenuItem("Open link", Ic.OpenInNew) { actions.onMenuAction(node, MenuAction.OpenLink) }
                MenuItem("Copy link", Ic.Link) { actions.onMenuAction(node, MenuAction.CopyLink) }
            }
            MenuItem(if (hasLink) "Edit link" else "Add link", Ic.Link) { actions.onMenuAction(node, MenuAction.EditLink) }
            if (hasLink) MenuItem("Remove link", Ic.Close) { actions.onMenuAction(node, MenuAction.RemoveLink) }
            MenuItem(if (node.done) "Mark not completed" else "Mark completed", if (node.done) Ic.Undo else Ic.Check) {
                actions.onMenuAction(node, MenuAction.Done)
            }
            MenuItem("Zoom in", Ic.Unfold) { actions.onMenuAction(node, MenuAction.Zoom) }
            MenuItem("Move to…", Ic.Move) { actions.onMenuAction(node, MenuAction.MoveTo) }
            MenuItem("Copy text", Ic.Copy) { actions.onMenuAction(node, MenuAction.CopyText) }
            MenuItem("Share", Ic.Share) { actions.onMenuAction(node, MenuAction.Share) }
            HorizontalDivider()
            MenuItem("Delete", Ic.Delete, MaterialTheme.colorScheme.error) { actions.onMenuAction(node, MenuAction.Delete) }
        }
    }
}

@Composable
internal fun MenuItem(text: String, icon: ImageVector, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint) },
        leadingIcon = {
            Icon(icon, null, Modifier.size(20.dp), tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint)
        },
        onClick = onClick,
    )
}

@Composable
private fun NodeText(node: Node, actions: RowActions) {
    val cs = MaterialTheme.colorScheme
    val done = node.done
    val base = MaterialTheme.typography.bodyLarge
    val style = remember(done, cs, base) {
        base.copy(
            color = if (done) cs.onSurface.copy(alpha = 0.45f) else cs.onSurface,
            textDecoration = if (done) TextDecoration.LineThrough else null,
        )
    }
    val fr = remember { FocusRequester() }
    TrackFocusAndEdits(node, fr)
    val transformation = remember(node.id) {
        SplitOnNewline { before, parts, after -> actions.onEnter(node, before, parts, after) }
    }
    BasicTextField(
        state = node.text,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("text-${node.id}")
            .semantics {
                stateDescription = if (node.done) "Completed" else "Not completed"
                customActions = rowA11yActions(node, actions)
            }
            .focusRequester(fr)
            .trackFocus(node)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.key == Key.Backspace && node.text.text.isEmpty() -> actions.onBackspaceEmpty(node)
                    e.key == Key.Tab -> {
                        if (e.isShiftPressed) Store.outdent(node) else Store.indent(node)
                        true
                    }
                    else -> false
                }
            },
        textStyle = style,
        inputTransformation = transformation,
        lineLimits = TextFieldLineLimits.MultiLine(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        cursorBrush = SolidColor(cs.primary),
    )
}

/**
 * Title and link field both count as "editing this item". Only a real focus *loss* clears it:
 * onFocusChanged also fires with "unfocused" when a field first appears, which must not end
 * the editing that made the link field appear in the first place.
 */
@Composable
internal fun Modifier.trackFocus(node: Node): Modifier {
    val had = remember { booleanArrayOf(false) }
    return onFocusChanged {
        if (it.isFocused) {
            had[0] = true
            Store.focusedId = node.id
        } else if (had[0]) {
            had[0] = false
            if (Store.focusedId == node.id) Store.focusedId = null
        }
    }
}

private class EditTracker(var was: Boolean, var startText: String)

/**
 * Notices when the user stops editing [node] (focus leaves both its fields, or the row goes away
 * while focused) and tidies the link then. Only text typed in this session gets auto-linked.
 */
@Composable
internal fun EditSession(node: Node, editing: Boolean) {
    val t = remember(node.id) { EditTracker(editing, node.plain) }
    LaunchedEffect(editing) {
        if (editing && !t.was) t.startText = node.plain
        if (t.was && !editing) Store.finishEditing(node, edited = node.plain != t.startText)
        t.was = editing
    }
    DisposableEffect(node.id) {
        onDispose {
            if (t.was) {
                val edited = node.plain != t.startText
                Store.main.post { Store.finishEditing(node, edited) }
            }
        }
    }
}

/** TalkBack: everything the gestures do, as named actions. */
private fun rowA11yActions(node: Node, actions: RowActions): List<CustomAccessibilityAction> = buildList {
    add(CustomAccessibilityAction(if (node.done) "Mark not completed" else "Mark completed") { actions.onToggleDone(node); true })
    add(CustomAccessibilityAction("Zoom in") { actions.onTapBullet(node); true })
    add(CustomAccessibilityAction("Move to…") { actions.onMenuAction(node, MenuAction.MoveTo); true })
    if (!node.url.isNullOrBlank()) add(CustomAccessibilityAction("Open link") { actions.onMenuAction(node, MenuAction.OpenLink); true })
    add(CustomAccessibilityAction("Delete") { actions.onMenuAction(node, MenuAction.Delete); true })
}

/** Registers the focus requester, honours focus requests and persists text edits. */
@Composable
internal fun TrackFocusAndEdits(node: Node, fr: FocusRequester) {
    DisposableEffect(node.id) {
        Store.focusRequesters[node.id] = fr
        onDispose { if (Store.focusRequesters[node.id] === fr) Store.focusRequesters.remove(node.id) }
    }
    LaunchedEffect(node.id) {
        snapshotFlow { Store.focusReq }.collect { req ->
            if (req != null && req.nodeId == node.id && req.field == FIELD_TEXT) {
                node.text.edit { selection = TextRange(req.cursor.coerceIn(0, length)) }
                runCatching { fr.requestFocus() }
                if (Store.focusReq === req) Store.focusReq = null
            }
        }
    }
    LaunchedEffect(node.id) {
        var first = true
        snapshotFlow { node.text.text.toString() }.collect {
            if (first) first = false else Store.touched(node)
        }
    }
}

/** The link, editable, shown while the item is being edited. */
@Composable
internal fun UrlField(node: Node, actions: RowActions) {
    val cs = MaterialTheme.colorScheme
    val state = remember(node.id) { TextFieldState(node.url.orEmpty()) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(node.id) {
        var first = true
        snapshotFlow { state.text.toString() }.collect {
            if (first) {
                first = false
            } else if (Store.isAlive(node)) {
                node.url = it
                Store.touched(node)
            }
        }
    }
    LaunchedEffect(node.id) {
        snapshotFlow { Store.focusReq }.collect { req ->
            if (req != null && req.nodeId == node.id && req.field == FIELD_URL) {
                state.edit { selection = TextRange(req.cursor.coerceIn(0, length)) }
                runCatching { fr.requestFocus() }
                if (Store.focusReq === req) Store.focusReq = null
            }
        }
    }
    val style = MaterialTheme.typography.bodyMedium.copy(color = cs.primary)
    Row(
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceContainerHigh)
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.Link, null, Modifier.padding(top = 1.dp).size(16.dp), tint = cs.primary)
        BasicTextField(
            state = state,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 9.dp)
                .focusRequester(fr)
                .trackFocus(node)
                .testTag("url-${node.id}"),
            textStyle = style,
            inputTransformation = NoNewlines,
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
            ),
            onKeyboardAction = { Store.focusReq = FocusReq(node.id, node.text.text.length) },
            cursorBrush = SolidColor(cs.primary),
            decorator = { inner ->
                Box {
                    if (state.text.isEmpty()) {
                        Text("Paste or type a link", style = style, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                    inner()
                }
            },
        )
        SmallIcon(Ic.Copy, "Copy link", "copyurl-${node.id}") {
            val u = state.text.toString().trim()
            if (u.isNotEmpty()) actions.onCopyUrl(u)
        }
        SmallIcon(Ic.OpenInNew, "Open link", "openurl-${node.id}") {
            val u = state.text.toString().trim()
            if (u.isNotEmpty()) actions.onOpenUrl(u)
        }
        SmallIcon(Ic.Close, "Remove link", "removeurl-${node.id}") {
            Store.focusReq = FocusReq(node.id, node.text.text.length)
            Store.removeLink(node)
        }
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, desc: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Tap opens, long-press copies. Shows the actual URL (minus https://www.). */
@Composable
internal fun LinkLine(node: Node, onOpen: (String) -> Unit, onCopy: (String) -> Unit) {
    val url = node.url?.takeIf { it.isNotBlank() } ?: return
    val cs = MaterialTheme.colorScheme
    val video = remember(url) { LinkInfo.isVideo(url) }
    Row(
        Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .combinedClickable(onLongClick = { onCopy(url) }, onClick = { onOpen(url) })
            .padding(vertical = 3.dp, horizontal = 2.dp)
            .testTag("link-${node.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (video) Ic.Play else Ic.Link, null, Modifier.size(15.dp), tint = cs.primary)
        Spacer(Modifier.width(5.dp))
        Text(
            LinkInfo.label(url, node.note),
            style = MaterialTheme.typography.labelMedium,
            color = cs.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun Thumb(name: String, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { 220.dp.roundToPx() }
    val bmp by produceState(Attachments.cached(name), name) {
        if (value == null) value = withContext(Dispatchers.IO) { Attachments.thumb(ctx, name, px) }
    }
    val shape = RoundedCornerShape(10.dp)
    val b = bmp
    if (b == null) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(96.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onClick)
        )
    } else {
        Image(
            b,
            contentDescription = "Image",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .padding(top = 6.dp)
                .height(110.dp)
                .aspectRatio((b.width.toFloat() / b.height).coerceIn(0.6f, 2.2f))
                .clip(shape)
                .clickable(onClick = onClick),
        )
    }
}

/** Links are one logical line even when they wrap; drop pasted line breaks. */
private object NoNewlines : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val s = asCharSequence()
        if (s.indexOf('\n') < 0) return
        val cleaned = s.toString().replace("\n", "")
        replace(0, length, cleaned)
    }
}

/**
 * Turns an inserted line break (Enter key, or a multi-line paste) into a split instead of a
 * newline. Text that already had line breaks (e.g. shared quotes) stays as it is.
 */
class SplitOnNewline(private val onSplit: (String, List<String>, String) -> Unit) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val new = asCharSequence()
        if (new.indexOf('\n') < 0) return
        val old = originalText
        if (new.count { it == '\n' } <= old.count { it == '\n' }) return
        val max = minOf(old.length, new.length)
        var p = 0
        while (p < max && old[p] == new[p]) p++
        var s = 0
        while (s < max - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        val inserted = new.subSequence(p, new.length - s).toString()
        if ('\n' !in inserted) return
        val before = new.subSequence(0, p).toString()
        val after = new.subSequence(new.length - s, new.length).toString()
        revertAllChanges()
        val parts = inserted.split('\n')
        Store.main.post { onSplit(before, parts, after) }
    }
}

/**
 * Swipe right anywhere on a row (bullet, text, empty space): reports the horizontal distance
 * while dragging and the final distance + velocity on release. Vertical swipes and leftward
 * swipes are left alone so the list scrolls and text selection keeps working.
 */
suspend fun PointerInputScope.swipeRight(
    onMove: (dx: Float) -> Unit,
    onEnd: (dx: Float, velocityX: Float) -> Unit,
) = awaitEachGesture {
    // Look at the down on the Initial pass: there, "consumed" can only mean the list used it to
    // stop a fling. (On the Main pass the text field below would have consumed it already.)
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    if (down.isConsumed) return@awaitEachGesture
    val slop = viewConfiguration.touchSlop
    var total = Offset.Zero
    while (true) {
        val ev = awaitPointerEvent()
        val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
        if (ch.changedToDownIgnoreConsumed()) continue // the same down, now on the Main pass
        if (!ch.pressed || ch.isConsumed) return@awaitEachGesture
        total += ch.positionChange()
        if (total.getDistance() > slop) {
            if (total.x > 0f && total.x > abs(total.y) * 1.5f) {
                ch.consume()
                break
            }
            return@awaitEachGesture
        }
    }
    val tracker = VelocityTracker()
    tracker.addPosition(down.uptimeMillis, down.position)
    var dx = total.x
    onMove(dx)
    var ok = false
    try {
        ok = horizontalDrag(down.id) { ch ->
            tracker.addPosition(ch.uptimeMillis, ch.position)
            dx += ch.positionChange().x
            onMove(dx)
            ch.consume()
        }
    } finally {
        if (ok) onEnd(dx, tracker.calculateVelocity().x) else onEnd(0f, 0f)
    }
}

/**
 * Bullet gestures:
 *  - tap: zoom in
 *  - swipes: ignored here (vertical scrolls the list, rightward completes via the row)
 *  - long press: pick the row up; drag to move (sideways to nest), or let go to open the menu
 */
suspend fun PointerInputScope.bulletGestures(
    onTap: () -> Unit,
    onStart: () -> Boolean,
    onDrag: (Offset) -> Unit,
    onEnd: (moved: Boolean, cancelled: Boolean) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    // The list consumes the down when it is flinging: that touch only stops the scroll.
    if (down.isConsumed) return@awaitEachGesture
    val slop = viewConfiguration.touchSlop
    var total = Offset.Zero
    var decision = 0 // 1 tap, 3 long press, 4 not ours
    val finished = withTimeoutOrNull(LONG_PRESS_MS) {
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id }
            if (ch == null) {
                decision = 4
                break
            }
            if (!ch.pressed) {
                decision = if (ch.isConsumed) 4 else 1
                break
            }
            if (ch.isConsumed) {
                decision = 4
                break
            }
            total += ch.positionChange()
            if (total.getDistance() > slop) {
                decision = 4
                break
            }
        }
    }
    if (finished == null) decision = 3
    when (decision) {
        1 -> {
            onTap()
            return@awaitEachGesture
        }
        3 -> Unit
        else -> return@awaitEachGesture
    }
    if (!onStart()) return@awaitEachGesture
    var moved = false
    var afterPick = Offset.Zero
    var cancelled = true
    try {
        cancelled = !drag(down.id) { ch ->
            val d = ch.positionChange()
            if (!moved) {
                afterPick += d
                if (afterPick.getDistance() > slop) moved = true
            }
            onDrag(d)
            ch.consume()
        }
    } finally {
        onEnd(moved, cancelled)
    }
}
