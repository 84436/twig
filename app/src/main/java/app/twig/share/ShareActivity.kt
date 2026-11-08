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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.twig.data.Node
import app.twig.data.Store
import app.twig.ui.DestinationPicker
import app.twig.ui.Ic
import app.twig.ui.LinkLabel
import app.twig.ui.TwigTheme

/**
 * Receives shares. With a chosen destination it saves right away; with "Always ask" it floats a
 * sheet over the app you shared from, and tapping a list saves and closes.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val p = SharePayload.from(intent) ?: return finish()
        Store.awaitLoaded()
        Store.shareTarget?.let { dest ->
            save(p.title, p.url, dest)
            // nothing else on screen says it worked
            Toast.makeText(this, "Saved to ${Store.label(dest)}", Toast.LENGTH_SHORT).show()
            return finish()
        }
        setContent {
            TwigTheme {
                ShareSheet(p, onSave = { title, dest -> save(title, p.url, dest) }, onCancel = ::finish)
            }
        }
    }

    private fun save(title: String, url: String?, dest: Node) {
        if (title.isNotBlank() || url != null) Store.addShared(dest, title.trim(), url)
        Store.flush()
        finish()
    }

    override fun onStop() {
        super.onStop()
        Store.flush()
    }
}

@Composable
fun ShareSheet(p: SharePayload, onSave: (String, Node) -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val title = rememberTextFieldState(p.title)
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
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
                Column(Modifier.navigationBarsPadding().padding(top = 8.dp, bottom = 8.dp)) {
                    Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Save to Twig", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = onCancel) { Icon(Ic.Close, "Cancel") }
                    }
                    Column(
                        Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(cs.surfaceContainerHighest)
                            .padding(14.dp)
                    ) {
                        BasicTextField(
                            state = title,
                            modifier = Modifier.fillMaxWidth().testTag("shareTitle"),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            cursorBrush = SolidColor(cs.primary),
                            decorator = { inner ->
                                Box {
                                    if (title.text.isEmpty()) {
                                        Text("Title", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                                    }
                                    inner()
                                }
                            },
                        )
                        p.url?.let { LinkLabel(it, Modifier.padding(top = 4.dp)) }
                    }
                    Text(
                        "Save to",
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
                    )
                    DestinationPicker(
                        moving = emptyList(),
                        onPick = { onSave(title.text.toString(), it) },
                        modifier = Modifier.heightIn(max = 360.dp),
                        autoFocus = false,
                    )
                }
            }
        }
    }
}
