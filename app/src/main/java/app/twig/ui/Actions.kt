package app.twig.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.twig.data.Node
import app.twig.share.LinkInfo

object Actions {
    fun openUrl(ctx: Context, url: String) {
        val fixed = LinkInfo.normalize(url)
        // file:// would throw FileUriExposedException; no app for the link throws too
        if (!fixed.startsWith("file:", ignoreCase = true)) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fixed))) }
    }

    fun textOf(n: Node) = listOfNotNull(n.plain.ifBlank { null }, n.url).joinToString("\n")

    /** Android 13+ confirms copies itself. */
    fun copy(ctx: Context, text: String) {
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Twig", text))
    }

    fun clipboardText(ctx: Context): String =
        ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()

    fun share(ctx: Context, n: Node) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, textOf(n))
        if (n.url != null && n.plain.isNotBlank()) send.putExtra(Intent.EXTRA_SUBJECT, n.plain)
        ctx.startActivity(Intent.createChooser(send, null))
    }
}
