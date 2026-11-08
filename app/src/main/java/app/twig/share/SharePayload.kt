package app.twig.share

import android.content.Intent

/** What another app handed us, normalised into "title + optional URL". */
data class SharePayload(val title: String, val url: String?) {
    companion object {
        fun from(intent: Intent): SharePayload? = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> parse(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString(), null)
            Intent.ACTION_SEND -> parse(
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
                intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent.getStringExtra(Intent.EXTRA_TITLE),
            )
            else -> null
        }

        fun parse(text: String?, subject: String?): SharePayload? {
            val body = text?.trim().orEmpty()
            val subj = LinkInfo.cleanTitle(subject)
            val rawUrl = LinkInfo.findUrl(body)
            if (rawUrl != null) {
                val url = LinkInfo.clean(rawUrl)
                // Whatever is left once the URL is removed often is the title ("Title - https://...")
                val rest = LinkInfo.cleanTitle(body.replace(rawUrl, " ").trim().trim('-', '–', '—', '|', ':', '·'))
                val title = when {
                    subj != null && subj != url && subj != rawUrl -> subj
                    else -> rest.orEmpty()
                }
                return SharePayload(title, url)
            }
            val title = when {
                subj != null && body.isNotEmpty() && !body.contains(subj) -> "$subj\n$body"
                else -> body.ifEmpty { subj.orEmpty() }
            }
            return if (title.isEmpty()) null else SharePayload(title, null)
        }
    }
}
