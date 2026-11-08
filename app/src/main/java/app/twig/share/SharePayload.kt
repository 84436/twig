package app.twig.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** What another app handed us, normalised into "title + optional url / images". */
data class SharePayload(
    val title: String,
    val url: String?,
    val images: List<Uri> = emptyList(),
) {
    val needsTitle: Boolean get() = url != null && (title.isBlank() || title == url)

    companion object {
        fun from(intent: Intent): SharePayload? {
            val action = intent.action
            val images = ArrayList<Uri>()
            val text: String?
            val subject: String?
            when (action) {
                Intent.ACTION_PROCESS_TEXT -> {
                    text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                    subject = null
                }
                Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                    text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent.getStringExtra(Intent.EXTRA_TITLE)
                    val type = intent.type.orEmpty()
                    if (type.startsWith("image/")) {
                        if (action == Intent.ACTION_SEND) {
                            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { images += it }
                        } else {
                            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { images += it }
                        }
                    }
                }
                else -> return null
            }
            return parse(text, subject, images)
        }

        fun parse(text: String?, subject: String?, images: List<Uri> = emptyList()): SharePayload? {
            val body = text?.trim().orEmpty()
            val subj = LinkInfo.cleanTitle(subject)
            if (body.isEmpty() && subj == null && images.isEmpty()) return null

            val rawUrl = LinkInfo.findUrl(body)
            if (rawUrl != null) {
                val url = LinkInfo.clean(rawUrl)
                // Whatever is left once the URL is removed often is the title ("Title - https://...")
                val rest = body.replace(rawUrl, " ").trim().trim('-', '–', '—', '|', ':', '·').trim()
                val restTitle = LinkInfo.cleanTitle(rest)
                val title = when {
                    subj != null && subj != url && subj != rawUrl -> subj
                    restTitle != null -> restTitle
                    else -> ""
                }
                return SharePayload(title, url, images)
            }
            val title = when {
                subj != null && body.isNotEmpty() && !body.contains(subj) -> "$subj\n$body"
                body.isNotEmpty() -> body
                else -> subj.orEmpty()
            }
            return SharePayload(title, null, images)
        }
    }
}
