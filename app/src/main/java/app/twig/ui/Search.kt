package app.twig.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.twig.data.Node
import app.twig.data.Store
import app.twig.share.LinkInfo

/** Live results under the search bar; replaces the outline while there is a query. */
@Composable
fun SearchResults(query: String, results: List<Node>, onOpen: (Node) -> Unit) {
    val terms = remember(query) { query.trim().split(' ').filter { it.isNotEmpty() } }
    val cs = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize().testTag("results"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "caption", contentType = "caption") {
            Text(
                if (results.isEmpty()) "Nothing found" else "${results.size}${if (results.size >= 300) "+" else ""} found",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 4.dp),
            )
        }
        items(results, key = { it.id }, contentType = { "result" }) { n -> SearchResult(n, terms) { onOpen(n) } }
    }
}

@Composable
private fun SearchResult(n: Node, terms: List<String>, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val text = n.plain.ifEmpty { n.url?.let { LinkInfo.display(it) } ?: "" }
    val accent = cs.primary
    val annotated = remember(text, terms, accent) { highlight(text, terms, accent) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(
            annotated,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            color = if (n.done) cs.onSurface.copy(alpha = 0.5f) else cs.onSurface,
            textDecoration = if (n.done) TextDecoration.LineThrough else null,
        )
        val sub = buildString {
            append(Store.pathLabel(n))
            n.url?.takeIf { it.isNotBlank() }?.let { append("  ·  ").append(LinkInfo.display(it)) }
        }
        Text(sub, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun highlight(text: String, terms: List<String>, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    for (t in terms) {
        var i = text.indexOf(t, ignoreCase = true)
        while (i >= 0) {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color), i, i + t.length)
            i = text.indexOf(t, i + t.length, ignoreCase = true)
        }
    }
}
