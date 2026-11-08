package app.twig.share

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.twig.data.Attachments
import app.twig.data.Node
import app.twig.data.Store
import app.twig.ui.DestinationPicker
import app.twig.ui.Ic
import app.twig.ui.TwigTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Floating sheet over the app you shared from. One tap saves and closes. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val payload = SharePayload.from(intent)
        if (payload == null) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Store.awaitLoaded()
        setContent {
            TwigTheme {
                ShareSheet(
                    payload,
                    onDone = { msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Store.flush()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareSheet(p: SharePayload, onDone: (String) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val title = rememberTextFieldState(p.title)
    var note by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(p.needsTitle) }
    var saving by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val dup = remember { p.url?.let { Store.findByUrl(it) } }
    val inbox = remember { Store.inbox() }
    val recents = remember { Store.recentDestinations().filter { it !== inbox }.take(4) }
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }

    LaunchedEffect(Unit) {
        val url = p.url
        if (!fetching || url == null) return@LaunchedEffect
        val info = withContext(Dispatchers.IO) { LinkInfo.fetch(url) }
        if (info != null) {
            if (title.text.isBlank() || title.text.toString() == url) title.setTextAndPlaceCursorAtEnd(info.title)
            note = info.author
        }
        fetching = false
    }

    fun save(dest: Node) {
        if (saving) return
        saving = true
        scope.launch {
            val names = if (p.images.isEmpty()) emptyList() else withContext(Dispatchers.IO) { p.images.mapNotNull { Attachments.copy(ctx, it) } }
            val typed = title.text.toString().trim()
            if (typed.isEmpty() && p.url == null && names.isEmpty()) {
                onDone("Couldn't read that")
                return@launch
            }
            // Newest goes on top, so add extra images first and the main item last.
            for (extra in names.drop(1).reversed()) Store.addShared(dest, "", null, null, extra)
            val n = Store.addShared(dest, typed.ifEmpty { p.url ?: "" }, p.url, note, names.firstOrNull())
            if (p.url != null && (typed.isEmpty() || typed == p.url)) Store.fetchTitleLater(n, p.url)
            Store.flush()
            onDone("Saved to ${Store.label(dest)}")
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = null, indication = null) { onCancel() }
            .imePadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(visible, enter = slideInVertically(tween(160)) { it / 2 }) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = cs.surfaceContainerLow,
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .clickable(interactionSource = null, indication = null) { },
            ) {
                Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Save to Twig", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = onCancel) { Icon(Ic.Close, "Cancel") }
                    }
                    Column(Modifier.padding(end = 8.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(cs.surfaceContainerHighest)
                                .padding(14.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            val icon = when {
                                p.url != null && LinkInfo.isVideo(p.url) -> Ic.Play
                                p.url != null -> Ic.Link
                                else -> null
                            }
                            if (icon != null) {
                                Icon(icon, null, Modifier.padding(top = 2.dp).size(20.dp), tint = cs.primary)
                                Spacer(Modifier.width(10.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                BasicTextField(
                                    state = title,
                                    modifier = Modifier.fillMaxWidth().testTag("shareTitle"),
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                    cursorBrush = SolidColor(cs.primary),
                                    decorator = { inner ->
                                        Box {
                                            if (title.text.isEmpty()) {
                                                Text(
                                                    if (fetching) "Fetching title…" else "Add a note",
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = cs.onSurfaceVariant,
                                                )
                                            }
                                            inner()
                                        }
                                    },
                                )
                                if (p.url != null) {
                                    Text(
                                        LinkInfo.label(p.url, note),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = cs.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                                if (p.images.isNotEmpty()) {
                                    Text(
                                        if (p.images.size == 1) "1 image" else "${p.images.size} images",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = cs.primary,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                        if (dup != null) {
                            Text(
                                "Already saved in ${Store.pathLabel(dup)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = cs.tertiary,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("Save to", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DestChip(Store.label(inbox), Ic.Inbox, primary = true, tag = "dest-inbox") { save(inbox) }
                            recents.forEach { d -> DestChip(Store.label(d), null) { save(d) } }
                            DestChip(if (choosing) "Close" else "Other…", Ic.Search, tag = "dest-other") { choosing = !choosing }
                        }
                    }
                    if (choosing) {
                        DestinationPicker(
                            exclude = null,
                            onPick = { save(it) },
                            modifier = Modifier.heightIn(max = 320.dp).padding(top = 12.dp),
                            autoFocus = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DestChip(label: String, icon: ImageVector?, primary: Boolean = false, tag: String? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (primary) cs.primary else cs.surfaceContainerHighest,
        contentColor = if (primary) cs.onPrimary else cs.onSurface,
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label.take(28), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
