package app.twig.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import app.twig.data.Attachments
import app.twig.data.Node
import app.twig.data.Store
import app.twig.share.LinkInfo

object Actions {
    fun openUrl(ctx: Context, url: String) {
        // file:// would throw FileUriExposedException, content:// a SecurityException: catch everything
        try {
            val fixed = LinkInfo.normalize(url)
            if (fixed.startsWith("file:", ignoreCase = true)) {
                Store.notify("Can't open file links")
                return
            }
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fixed)))
        } catch (_: ActivityNotFoundException) {
            Store.notify("No app can open this link")
        } catch (_: Exception) {
            Store.notify("Can't open this link")
        }
    }

    fun openImage(ctx: Context, name: String) {
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", Attachments.file(ctx, name))
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: Exception) {
            Store.notify("Can't open that image")
        }
    }

    private fun textOf(n: Node) = listOfNotNull(n.plain.ifBlank { null }, n.url?.takeIf { it.isNotBlank() }).joinToString("\n")

    /** Android 13+ shows its own "copied" confirmation, so we only announce it on older versions. */
    fun copy(ctx: Context, text: String, confirmation: String) {
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Twig", text))
        if (Build.VERSION.SDK_INT < 33) Store.notify(confirmation)
    }

    fun share(ctx: Context, n: Node) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, textOf(n))
        if (!n.url.isNullOrBlank() && n.plain.isNotBlank()) send.putExtra(Intent.EXTRA_SUBJECT, n.plain)
        n.attach?.let {
            try {
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", Attachments.file(ctx, it))
                send.setType("image/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        ctx.startActivity(Intent.createChooser(send, null))
    }
}
