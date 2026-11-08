@file:OptIn(ExperimentalFoundationApi::class)

package app.twig.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.twig.data.Node
import app.twig.data.Store
import app.twig.share.Glyph
import app.twig.share.Sites
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

internal val BULLET_W = 30.dp
internal val LINE_BOX = 38.dp
internal val START_PAD = 6.dp
private val SWIPE_DONE = 76.dp
private val REVEAL = 88.dp
private const val LONG_PRESS_MS = 280L

enum class MenuAction { MoveTo, Select, Copy, Share }

interface RowActions {
    fun open(node: Node)
    fun dragStart(node: Node): Boolean
    fun drag(dy: Float)
    fun dragEnd(node: Node, moved: Boolean, cancelled: Boolean)
    fun enter(node: Node, before: String, parts: List<String>, after: String)
    fun backspaceEmpty(node: Node): Boolean
    fun toggleDone(node: Node)
    fun delete(node: Node)
    fun toggleSelected(node: Node)
    fun openUrl(url: String)
    fun copy(text: String)
    fun menuDismiss()
    fun menu(node: Node, action: MenuAction)
}

/**
 * One bullet on a page. A plain bullet is edited in place; a list (a bullet with bullets in it)
 * opens when tapped. Swipe right to complete, swipe left to uncover Delete. In batch mode
 * ([selected] not null) a tap selects.
 */
@Composable
fun BulletRow(
    node: Node,
    kids: Int,
    canOpen: Boolean,
    editing: Boolean,
    highlighted: Boolean,
    dragged: Boolean,
    menuOpen: Boolean,
    selected: Boolean?,
    actions: RowActions,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val isList = kids > 0
    val selecting = selected != null
    val density = LocalDensity.current
    val threshold = with(density) { SWIPE_DONE.toPx() }
    val reveal = with(density) { REVEAL.toPx() }
    val flingPx = with(density) { 900.dp.toPx() } // per second
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val swipe = remember { Animatable(0f) }
    val base = remember { FloatArray(1) } // where the row sat when the current swipe began
    val armed = remember { BooleanArray(1) }
    val revealed = Store.revealedId == node.id
    LaunchedEffect(revealed) {
        if (!revealed && swipe.value != 0f) swipe.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
    }
    if (!isList) EditSession(node, editing)
    val bg by animateColorAsState(
        if (highlighted || selected == true) cs.primary.copy(alpha = 0.16f) else cs.primary.copy(alpha = 0f),
        tween(if (highlighted || selected == true) 120 else 900),
        label = "hl",
    )
    val doneIcon = rememberVectorPainter(if (node.done) Ic.Undo else Ic.Check)
    val idle = cs.surfaceContainerHighest
    val go = cs.primary
    val idleInk = cs.onSurfaceVariant
    val goInk = cs.onPrimary

    Box(
        modifier
            .fillMaxWidth()
            .testTag("row-${node.id}")
            .drawBehind {
                val x = swipe.value
                if (x <= 0f) return@drawBehind
                val on = x >= threshold
                val x0 = START_PAD.toPx()
                drawRect(if (on) go else idle, topLeft = Offset(x0, 0f), size = Size(x, size.height))
                val icon = 22.dp.toPx()
                if (x > 14.dp.toPx() + icon) {
                    translate(x0 + 14.dp.toPx(), (LINE_BOX.toPx() - icon) / 2) {
                        with(doneIcon) { draw(Size(icon, icon), colorFilter = ColorFilter.tint(if (on) goInk else idleInk)) }
                    }
                }
            }
            .then(
                if (selecting) {
                    Modifier
                        .clickable { actions.toggleSelected(node) }
                        .semantics { stateDescription = if (selected == true) "Selected" else "Not selected" }
                } else {
                    Modifier.pointerInput(node.id, actions) {
                        swipeRow(
                            onStart = {
                                base[0] = if (Store.revealedId == node.id) -reveal else 0f
                                if (base[0] == 0f) Store.revealedId = null
                            },
                            onMove = { dx ->
                                val raw = base[0] + dx
                                val v = when {
                                    base[0] < 0f -> raw.coerceAtMost(0f)
                                    raw > threshold -> threshold + (raw - threshold) * 0.35f
                                    raw < -reveal -> -reveal + (raw + reveal) * 0.35f
                                    else -> raw
                                }
                                val on = base[0] == 0f && raw >= threshold
                                if (on != armed[0]) {
                                    armed[0] = on
                                    if (on) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                scope.launch { swipe.snapTo(v) }
                            },
                            onEnd = { dx, vx ->
                                armed[0] = false
                                val raw = base[0] + dx
                                val target = when {
                                    base[0] == 0f && (raw >= threshold || (raw >= threshold * 0.5f && vx > flingPx)) -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        actions.toggleDone(node)
                                        0f
                                    }
                                    raw < -reveal / 2 && vx < flingPx || raw < 0f && vx < -flingPx -> -reveal
                                    else -> 0f
                                }
                                Store.revealedId = if (target < 0f) node.id else Store.revealedId.takeIf { it != node.id }
                                scope.launch { swipe.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow)) }
                            },
                        )
                    }.semantics { customActions = a11y(node, canOpen, actions) }
                }
            )
    ) {
        if (!selecting) DeleteButton(node, { swipe.value }) { actions.delete(node) }
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
                .then(if (isList && !selecting) Modifier.pressGestures(node, { actions.open(node) }, actions) else Modifier)
                .padding(start = START_PAD, end = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Dot(node, isList, canOpen, selected, menuOpen, actions)
            Column(Modifier.weight(1f).padding(vertical = 7.dp)) {
                if (isList || selecting) {
                    Text(node.plain, style = textStyle(node.done), modifier = Modifier.testTag("label-${node.id}"))
                } else {
                    NodeText(node, canOpen, actions)
                }
                val url = node.url
                if (editing && !isList && !selecting) UrlField(node)
                else if (!url.isNullOrBlank()) LinkLine(url, actions::openUrl, actions::copy, enabled = !selecting)
            }
            if (isList) {
                Text(
                    "$kids  ›",
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 10.dp),
                )
            }
        }
    }
}

/** Uncovered by swiping left; grows with the swipe. Tapping it is the only way to delete a bullet. */
@Composable
private fun DeleteButton(node: Node, offset: () -> Float, onClick: () -> Unit) {
    val x = offset()
    if (x >= 0f) return
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(with(LocalDensity.current) { (-x).toDp() })
                .clipToBounds()
                .background(cs.error)
                .clickable(onClick = onClick)
                .testTag("delete-${node.id}"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Ic.Delete, null, Modifier.size(20.dp), tint = cs.onError)
            Text("Delete", style = MaterialTheme.typography.labelMedium, color = cs.onError, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun textStyle(done: Boolean): TextStyle {
    val cs = MaterialTheme.colorScheme
    val base = MaterialTheme.typography.bodyLarge
    return remember(done, cs, base) {
        base.copy(
            color = if (done) cs.onSurface.copy(alpha = 0.45f) else cs.onSurface,
            textDecoration = if (done) TextDecoration.LineThrough else null,
        )
    }
}

/** The dot: tap opens the bullet (if it may hold bullets), long-press drags it or opens the menu. */
@Composable
private fun Dot(node: Node, isList: Boolean, canOpen: Boolean, selected: Boolean?, menuOpen: Boolean, actions: RowActions) {
    val cs = MaterialTheme.colorScheme
    val dot = if (!node.url.isNullOrBlank()) cs.primary else cs.onSurface.copy(alpha = 0.72f)
    val ring = cs.onSurface.copy(alpha = 0.18f)
    val check = rememberVectorPainter(Ic.Check)
    val onPrimary = cs.onPrimary
    val primary = cs.primary
    val outline = cs.outline
    Box(
        Modifier
            .size(BULLET_W, LINE_BOX)
            .testTag("bullet-${node.id}")
            .then(
                if (selected == null && !isList) Modifier.pressGestures(node, if (canOpen) ({ actions.open(node) }) else null, actions)
                else Modifier
            )
            .drawBehind {
                when (selected) {
                    true -> {
                        drawCircle(primary, radius = 10.dp.toPx())
                        val s = 14.dp.toPx()
                        translate((size.width - s) / 2, (size.height - s) / 2) {
                            with(check) { draw(Size(s, s), colorFilter = ColorFilter.tint(onPrimary)) }
                        }
                    }
                    false -> drawCircle(outline, radius = 9.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                    null -> {
                        val alpha = if (node.done) 0.4f else 1f
                        if (isList) drawCircle(ring, radius = 9.dp.toPx(), alpha = alpha)
                        drawCircle(dot, radius = 3.5.dp.toPx(), alpha = alpha)
                    }
                }
            }
    ) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = actions::menuDismiss) {
            MenuItem("Move to…", Ic.Move) { actions.menu(node, MenuAction.MoveTo) }
            MenuItem("Select", Ic.CheckCircle) { actions.menu(node, MenuAction.Select) }
            MenuItem("Copy", Ic.Copy) { actions.menu(node, MenuAction.Copy) }
            MenuItem("Share", Ic.Share) { actions.menu(node, MenuAction.Share) }
        }
    }
}

@Composable
internal fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        onClick = onClick,
    )
}

@Composable
private fun NodeText(node: Node, canOpen: Boolean, actions: RowActions) {
    val cs = MaterialTheme.colorScheme
    val fr = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    TrackFocusAndEdits(node, fr)
    val transformation = remember(node.id) {
        SplitOnNewline { before, parts, after ->
            // Enter on an empty bullet: done typing (the empty bullet then goes away)
            if (before.isEmpty() && after.isEmpty() && parts.size == 2 && parts.all { it.isEmpty() }) focus.clearFocus()
            else actions.enter(node, before, parts, after)
        }
    }
    BasicTextField(
        state = node.text,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("text-${node.id}")
            .semantics {
                stateDescription = if (node.done) "Completed" else "Not completed"
                customActions = a11y(node, canOpen, actions)
            }
            .focusRequester(fr)
            .trackFocus(node)
            .onPreviewKeyEvent { e ->
                e.type == KeyEventType.KeyDown && e.key == Key.Backspace && node.text.text.isEmpty() && actions.backspaceEmpty(node)
            },
        textStyle = textStyle(node.done),
        inputTransformation = transformation,
        lineLimits = TextFieldLineLimits.MultiLine(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        cursorBrush = SolidColor(cs.primary),
    )
}

/** TalkBack: everything the gestures do, as named actions. */
private fun a11y(node: Node, canOpen: Boolean, actions: RowActions): List<CustomAccessibilityAction> = buildList {
    add(CustomAccessibilityAction(if (node.done) "Mark not completed" else "Mark completed") { actions.toggleDone(node); true })
    if (canOpen) add(CustomAccessibilityAction("Open") { actions.open(node); true })
    add(CustomAccessibilityAction("Move to…") { actions.menu(node, MenuAction.MoveTo); true })
    add(CustomAccessibilityAction("Delete") { actions.delete(node); true })
}

/**
 * Title and URL field both count as "editing this bullet". Only a real focus *loss* clears it:
 * onFocusChanged also fires with "unfocused" when a field first appears.
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

/** Notices when the user stops editing [node] (focus leaves it, or the row goes away while focused). */
@Composable
internal fun EditSession(node: Node, editing: Boolean) {
    val was = remember(node.id) { booleanArrayOf(editing) }
    LaunchedEffect(editing) {
        if (was[0] && !editing) Store.finishEditing(node)
        was[0] = editing
    }
    DisposableEffect(node.id) {
        onDispose { if (was[0]) Store.main.post { Store.finishEditing(node) } }
    }
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
            if (req != null && req.nodeId == node.id) {
                node.text.edit { selection = TextRange(req.cursor.coerceIn(0, length)) }
                runCatching { fr.requestFocus() }
                if (Store.focusReq === req) Store.focusReq = null
            }
        }
    }
    LaunchedEffect(node.id) {
        var first = true
        snapshotFlow { node.text.text.toString() }.collect {
            if (first) first = false else Store.dirty(node)
        }
    }
}

/** The bullet's URL, editable. Shown under the text while the bullet is being edited. */
@Composable
internal fun UrlField(node: Node) {
    val cs = MaterialTheme.colorScheme
    val state = remember(node.id) { TextFieldState(node.url.orEmpty()) }
    val focus = LocalFocusManager.current
    LaunchedEffect(node.id) {
        var first = true
        snapshotFlow { state.text.toString() }.collect {
            if (first) {
                first = false
            } else if (Store.isAlive(node)) {
                node.url = it
                Store.dirty(node)
            }
        }
    }
    val style = MaterialTheme.typography.bodyMedium.copy(color = cs.primary)
    Row(Modifier.padding(top = 2.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.Link, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
        BasicTextField(
            state = state,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .trackFocus(node)
                .testTag("url-${node.id}"),
            textStyle = style,
            inputTransformation = NoNewlines,
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
            ),
            onKeyboardAction = { focus.clearFocus() },
            cursorBrush = SolidColor(cs.primary),
            decorator = { inner ->
                Box {
                    if (state.text.isEmpty()) Text("Add URL", style = style, color = cs.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1)
                    inner()
                }
            },
        )
        if (state.text.isNotEmpty()) {
            Icon(
                Ic.Close, "Remove URL",
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { state.clearText() }
                    .padding(9.dp)
                    .testTag("clearUrl-${node.id}"),
                tint = cs.onSurfaceVariant,
            )
        }
    }
}

/** Icon and short text for a URL: "YouTube", "84436/twig", or the host. */
@Composable
fun LinkLabel(url: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val look = remember(url) { Sites.look(url) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(if (look.glyph == Glyph.Play) Ic.Play else Ic.Link, null, Modifier.size(15.dp), tint = cs.primary)
        Spacer(Modifier.width(5.dp))
        Text(look.text, style = MaterialTheme.typography.labelMedium, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Tap opens, long-press copies. */
@Composable
internal fun LinkLine(url: String, onOpen: (String) -> Unit, onCopy: (String) -> Unit, enabled: Boolean = true) {
    LinkLabel(
        url,
        Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(if (enabled) Modifier.combinedClickable(onLongClick = { onCopy(url) }, onClick = { onOpen(url) }) else Modifier)
            .padding(vertical = 3.dp, horizontal = 2.dp)
            .testTag("link-$url"),
    )
}

/** URLs are one logical line even when they wrap; drop pasted line breaks. */
private object NoNewlines : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val s = asCharSequence()
        if (s.indexOf('\n') >= 0) replace(0, length, s.toString().replace("\n", ""))
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
        Store.main.post { onSplit(before, inserted.split('\n'), after) }
    }
}

/**
 * A horizontal swipe anywhere on a row: reports the distance while dragging and the final
 * distance + velocity on release. Vertical swipes are left alone so the list scrolls.
 */
suspend fun PointerInputScope.swipeRow(
    onStart: () -> Unit,
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
        val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
        if (ch.changedToDownIgnoreConsumed()) continue // the same down, now on the Main pass
        if (!ch.pressed || ch.isConsumed) return@awaitEachGesture
        total += ch.positionChange()
        if (total.getDistance() > slop) {
            if (abs(total.x) <= abs(total.y) * 1.5f) return@awaitEachGesture
            ch.consume()
            break
        }
    }
    val tracker = VelocityTracker()
    tracker.addPosition(down.uptimeMillis, down.position)
    var dx = total.x
    onStart()
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
 * Tap: [onTap]. Long press: pick the row up; drag to reorder, or let go to open the menu.
 * Swipes are left to the row and the list.
 */
@Composable
private fun Modifier.pressGestures(node: Node, onTap: (() -> Unit)?, actions: RowActions): Modifier {
    val haptics = LocalHapticFeedback.current
    return pointerInput(node.id, actions, onTap != null) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // The list consumes the down when it is flinging: that touch only stops the scroll.
            if (down.isConsumed) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            var total = Offset.Zero
            var tapped = false
            var held = true // stays true only if the timeout hits first
            withTimeoutOrNull(LONG_PRESS_MS) {
                while (true) {
                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    if (ch == null || !ch.pressed || ch.isConsumed) {
                        tapped = ch != null && !ch.pressed && !ch.isConsumed
                        held = false
                        return@withTimeoutOrNull
                    }
                    total += ch.positionChange()
                    if (total.getDistance() > slop) {
                        held = false
                        return@withTimeoutOrNull
                    }
                }
            }
            if (tapped) onTap?.invoke()
            if (!held || !actions.dragStart(node)) return@awaitEachGesture
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            var moved = false
            var afterPick = Offset.Zero
            var cancelled = true
            try {
                cancelled = !drag(down.id) { ch ->
                    val d = ch.positionChange()
                    if (!moved) {
                        afterPick += d
                        moved = afterPick.getDistance() > slop
                    }
                    actions.drag(d.y)
                    ch.consume()
                }
            } finally {
                actions.dragEnd(node, moved, cancelled)
            }
        }
    }
}
