package app.twig.share

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.twig.data.Node
import app.twig.data.Store
import app.twig.ui.LocationSheet
import app.twig.ui.LinkLabel
import app.twig.ui.TwigTheme

/**
 * Receives shares. With a chosen destination it saves right away, silently; with "Always ask" it
 * shows the location sheet over the app you shared from, and tapping a place saves and closes.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val p = SharePayload.from(intent) ?: return finish()
        Store.awaitLoaded()
        Store.ensureSeeded()
        Store.shareTarget?.let { return save(p.title, p.url, it) }
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

}

@Composable
fun ShareSheet(p: SharePayload, onSave: (String, Node) -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val title = rememberTextFieldState(p.title)
    LocationSheet("Save to Twig", onDismiss = onCancel, onPick = { it?.let { dest -> onSave(title.text.toString(), dest) } }) {
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
                        if (title.text.isEmpty()) Text("Title", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                        inner()
                    }
                },
            )
            p.url?.let { LinkLabel(it, Modifier.padding(top = 4.dp)) }
        }
    }
}
