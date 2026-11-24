@file:OptIn(ExperimentalFoundationApi::class)

package app.twig.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.twig.data.Markup
import app.twig.data.SwipeAction
import app.twig.data.Node
import app.twig.data.Store
import app.twig.share.Glyph
import app.twig.share.Sites
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.sign

internal val BULLET_W = 30.dp
internal val START_PAD = 6.dp
/** How far a swipe goes. A Delete swipe stays open this wide; any other action fires and springs back. */
private val SWIPE = 72.dp

private enum class Swipe { Closed, Right, Left }

/** Soft spring for a swipe settling or springing back. */
private val Springy = spring<Float>(stiffness = Spring.StiffnessMediumLow)

/** How much of the finger's travel a swipe keeps once it is past its stop. */
private const val GIVE = 0.35f

/**
 * Resistance for [anchoredDraggable], which stops dead at its last anchor: past an action's stop
 * the row keeps going at [GIVE] of the finger's speed, and springs back on release.
 */
private class Stretch(private val swipe: AnchoredDraggableState<Swipe>) : OverscrollEffect {
    var x by mutableFloatStateOf(0f)
    private var back: Job? = null
    override val isInProgress get() = x != 0f

    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
        back?.cancel()
        var d = delta.x
        if (x != 0f && sign(d) != sign(x)) { // pulling back undoes the stretch first
            val to = if (abs(d * GIVE) < abs(x)) x + d * GIVE else 0f
            d -= (to - x) / GIVE
            x = to
        }
        val rest = d - performScroll(Offset(d, 0f)).x
        if (swipe.offset != 0f) x += rest * GIVE // not where there is no action to swipe to
        return delta
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) = coroutineScope {
        back = launch { animate(x, 0f, animationSpec = Springy) { v, _ -> x = v } }
        performFling(velocity)
        Unit
    }
}
private const val LONG_PRESS_MS = 280L

/** Height of a row's first line, so the dot sits on it at any text size. */
@Composable
internal fun lineBox(): Dp = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() } + 14.dp

enum class MenuAction { MoveTo, Select, Copy, Share }

/** What a row needs from its page: dragging and the long-press menu. Everything else goes to [Store]. */
interface RowActions {
    fun dragStart(node: Node): Boolean
    fun drag(dy: Float)
    fun dragEnd(node: Node, moved: Boolean, cancelled: Boolean)
    fun menuDismiss()
    fun menu(node: Node, action: MenuAction)
}

private fun swipeIcon(a: SwipeAction, done: Boolean) = when (a) {
    SwipeAction.Done -> if (done) Ic.CheckBoxBlank else Ic.CheckBox
    SwipeAction.Select -> Ic.CheckCircle
    SwipeAction.Move -> Ic.Move
    else -> Ic.Delete
}

/**
 * One bullet on a page; a list is a bullet with bullets in it. Tap the text to edit, the dot or a
 * list's › to open. Swipes do what Settings says (Delete uncovers a button instead of deleting).
 * In batch mode ([selected] not null) a tap selects; in read-only mode a tap on a list opens it.
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
    readOnly: Boolean,
    actions: RowActions,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val isList = kids > 0
    val selecting = selected != null
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    // read-only: completing is the one thing a swipe may still do
    val right = Store.swipeRight.takeIf { !readOnly || it == SwipeAction.Done } ?: SwipeAction.None
    val left = Store.swipeLeft.takeIf { !readOnly || it == SwipeAction.Done } ?: SwipeAction.None
    val distance = with(density) { SWIPE.toPx() }
    val anchors = remember(right, left, distance) {
        DraggableAnchors {
            Swipe.Closed at 0f
            if (right != SwipeAction.None) Swipe.Right at distance
            if (left != SwipeAction.None) Swipe.Left at -distance
        }
    }
    val swipe = remember { AnchoredDraggableState(Swipe.Closed, anchors) }
    SideEffect { swipe.updateAnchors(anchors) }
    val stretch = remember { Stretch(swipe) }
    fun action(v: Swipe) = if (v == Swipe.Right) right else left
    // Landed on an action: Delete stays open for a tap, the rest fire and spring back.
    LaunchedEffect(swipe.settledValue) {
        val v = swipe.settledValue
        if (v == Swipe.Closed) {
            if (Store.revealedId == node.id) Store.revealedId = null
            return@LaunchedEffect
        }
        when (action(v)) {
            SwipeAction.Delete -> {
                Store.revealedId = node.id
                return@LaunchedEffect
            }
            SwipeAction.Done -> Store.toggleDone(node)
            SwipeAction.Select -> Store.selection = setOf(node.id)
            SwipeAction.Move -> actions.menu(node, MenuAction.MoveTo)
            SwipeAction.None -> Unit
        }
        swipe.animateTo(Swipe.Closed, Springy)
    }
    // An open Delete goes away when another row opens, the list scrolls, or editing starts.
    val revealed = Store.revealedId == node.id && !selecting
    LaunchedEffect(revealed) { if (!revealed && swipe.settledValue != Swipe.Closed) swipe.animateTo(Swipe.Closed, Springy) }
    val armed = swipe.targetValue != Swipe.Closed
    LaunchedEffect(armed) { if (armed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val side by remember { derivedStateOf { swipe.offset.let { if (it.isNaN() || it == 0f) 0 else sign(it).toInt() } } }
    EditSession(node, editing)
    val bg by animateColorAsState(
        if (highlighted || selected == true) cs.primary.copy(alpha = 0.16f) else cs.primary.copy(alpha = 0f),
        tween(if (highlighted || selected == true) 120 else 900),
        label = "hl",
    )

    Box(
        modifier
            .fillMaxWidth()
            .testTag("row-${node.id}")
            .then(
                if (selecting) {
                    Modifier
                        .clickable { Store.selection?.let { s -> Store.selection = if (node.id in s) s - node.id else s + node.id } }
                        .semantics { stateDescription = if (selected == true) "Selected" else "Not selected" }
                } else {
                    // physical left/right, also in right-to-left languages
                    Modifier
                        .anchoredDraggable(
                            swipe,
                            reverseDirection = false,
                            orientation = Orientation.Horizontal,
                            overscrollEffect = stretch,
                            flingBehavior = AnchoredDraggableDefaults.flingBehavior(swipe, animationSpec = Springy),
                        )
                        .semantics { customActions = a11y(node, canOpen, readOnly, actions) }
                }
            )
    ) {
        // what the swipe uncovers: the action's icon, centred in a strip at the edge it came from
        if (side != 0) {
            val act = action(if (side > 0) Swipe.Right else Swipe.Left)
            val delete = act == SwipeAction.Delete
            val (fill, ink) = when {
                !armed -> cs.surfaceContainerHighest to cs.onSurfaceVariant
                delete -> cs.error to cs.onError
                else -> cs.primary to cs.onPrimary
            }
            Box(Modifier.matchParentSize().background(fill), contentAlignment = if (side > 0) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight) {
                Box(
                    Modifier
                        .width(SWIPE)
                        .fillMaxHeight()
                        .then(if (delete) Modifier.clickable(enabled = revealed) { Store.delete(listOf(node)) }.testTag("delete-${node.id}") else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(swipeIcon(act, node.done), act.label, Modifier.size(22.dp), tint = ink)
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = (swipe.offset.takeUnless { it.isNaN() } ?: 0f) + stretch.x }
                .then(
                    if (dragged) {
                        Modifier
                            .shadow(10.dp, RoundedCornerShape(12.dp))
                            .background(cs.surfaceContainerHigh, RoundedCornerShape(12.dp))
                    } else {
                        Modifier.background(cs.surface).background(bg) // opaque: hides what a swipe uncovers
                    }
                )
                .then(if (readOnly && isList && !selecting) Modifier.clickable { Store.zoom = node } else Modifier)
                .padding(start = START_PAD, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Dot(node, isList, canOpen, selected, menuOpen, actions)
            Column(Modifier.weight(1f).padding(vertical = 7.dp)) {
                if (selecting || readOnly) {
                    val raw = node.plain
                    Text(remember(raw) { Markup.styled(raw) }, style = textStyle(node.done), modifier = Modifier.testTag("label-${node.id}"))
                } else {
                    NodeText(node, editing, canOpen, actions)
                }
                val url = node.url
                if (!selecting && !readOnly && Store.urlFor == node.id) UrlField(node)
                else if (!url.isNullOrBlank()) LinkLine(url, enabled = !selecting)
            }
            if (isList) {
                Row(
                    Modifier
                        .height(lineBox())
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !selecting) { Store.zoom = node }
                        .padding(start = 10.dp, end = 6.dp)
                        .testTag("open-${node.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$kids", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    Icon(Ic.ChevronRight, "Open", Modifier.size(20.dp), tint = cs.onSurfaceVariant)
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
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
    val fetching = node.id in Store.fetching
    Box(
        Modifier
            .size(BULLET_W, lineBox())
            .testTag("bullet-${node.id}")
            // read-only: only real lists open, there's nothing to add to an empty one
            .then(if (selected == null) Modifier.pressGestures(node, if (canOpen && (isList || !Store.readOnly)) ({ Store.zoom = node }) else null, actions) else Modifier)
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
                    null -> if (!fetching) {
                        val alpha = if (node.done) 0.4f else 1f
                        if (isList) drawCircle(ring, radius = 9.dp.toPx(), alpha = alpha)
                        drawCircle(dot, radius = 3.5.dp.toPx(), alpha = alpha)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (fetching) CircularProgressIndicator(Modifier.size(14.dp).testTag("fetching-${node.id}"), strokeWidth = 2.dp)
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
private fun NodeText(node: Node, editing: Boolean, canOpen: Boolean, actions: RowActions) {
    val cs = MaterialTheme.colorScheme
    val fr = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    TrackFocusAndEdits(node, fr)
    val transformation = remember(node.id) {
        SplitOnNewline { before, parts, after ->
            // Enter on an empty bullet: done typing (the empty bullet then goes away)
            if (before.isEmpty() && after.isEmpty() && parts.size == 2 && parts.all { it.isEmpty() }) focus.clearFocus()
            else Store.onEnter(node, before, parts, after)
        }
    }
    BasicTextField(
        state = node.text,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("text-${node.id}")
            .semantics {
                stateDescription = if (node.done) "Completed" else "Not completed"
                customActions = a11y(node, canOpen, false, actions)
            }
            .focusRequester(fr)
            .trackFocus(node)
            .onPreviewKeyEvent { e ->
                e.type == KeyEventType.KeyDown && e.key == Key.Backspace && node.text.text.isEmpty() && Store.backspaceEmpty(node)
            },
        textStyle = textStyle(node.done),
        inputTransformation = transformation,
        outputTransformation = Markup.output(editing),
        lineLimits = TextFieldLineLimits.MultiLine(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        cursorBrush = SolidColor(cs.primary),
    )
}

/** TalkBack: everything the gestures do, as named actions. */
private fun a11y(node: Node, canOpen: Boolean, readOnly: Boolean, actions: RowActions): List<CustomAccessibilityAction> = buildList {
    add(CustomAccessibilityAction(if (node.done) "Mark not completed" else "Mark completed") { Store.toggleDone(node); true })
    if (canOpen) add(CustomAccessibilityAction("Open") { Store.zoom = node; true })
    if (readOnly) return@buildList
    add(CustomAccessibilityAction("Move to…") { actions.menu(node, MenuAction.MoveTo); true })
    add(CustomAccessibilityAction("Delete") { Store.delete(listOf(node)); true })
}

/**
 * Title and URL field both count as "editing this bullet". Only a real focus *loss* clears it:
 * onFocusChanged also fires with "unfocused" when a field first appears.
 */
@Composable
private fun Modifier.trackFocus(node: Node): Modifier {
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
private fun EditSession(node: Node, editing: Boolean) {
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
private fun TrackFocusAndEdits(node: Node, fr: FocusRequester) {
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

/** The bullet's URL, editable. Opened from the keyboard bar; takes the cursor when it opens. */
@Composable
private fun UrlField(node: Node) {
    val cs = MaterialTheme.colorScheme
    val state = remember(node.id) { TextFieldState(node.url.orEmpty()) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(node.id) { runCatching { fr.requestFocus() } }
    LaunchedEffect(node.id) {
        var first = true
        snapshotFlow { state.text.toString() }.collect {
            if (first) {
                first = false
            } else if (Store.isAlive(node)) {
                node.url = it.ifBlank { null }
                Store.dirty(node)
            }
        }
    }
    val style = MaterialTheme.typography.bodyMedium.copy(color = cs.primary)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.Link, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
        BasicTextField(
            state = state,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .focusRequester(fr)
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
            onKeyboardAction = { Store.closeUrl(node) },
            cursorBrush = SolidColor(cs.primary),
            decorator = { inner ->
                Box {
                    if (state.text.isEmpty()) Text("Paste or type a URL", style = style, color = cs.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1)
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

/** Icon and short text for a URL: "YouTube video", "84436/twig", "Beatmap", or the host. */
@Composable
fun LinkLabel(url: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val look = remember(url) { Sites.look(url) }
    val icon = when (look.glyph) {
        Glyph.YouTube -> Ic.YouTube
        Glyph.GitHub -> Ic.GitHub
        Glyph.Cookie -> Ic.Cookie
        Glyph.Link -> Ic.Link
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(15.dp), tint = cs.primary)
        Spacer(Modifier.width(5.dp))
        Text(look.text, style = MaterialTheme.typography.labelMedium, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Tap opens, long-press copies. */
@Composable
private fun LinkLine(url: String, enabled: Boolean) {
    val ctx = LocalContext.current
    LinkLabel(
        url,
        Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(if (enabled) Modifier.combinedClickable(onLongClick = { Actions.copy(ctx, url) }, onClick = { Actions.openUrl(ctx, url) }) else Modifier)
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
            // read-only: no dragging, no menu
            if (!held || Store.readOnly || !actions.dragStart(node)) return@awaitEachGesture
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
