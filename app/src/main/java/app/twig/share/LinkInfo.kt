package app.twig.share

import android.net.Uri
import android.text.Html
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset

object LinkInfo {
    private val urlRe = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
    private val trackers = setOf(
        "fbclid", "gclid", "dclid", "msclkid", "igshid", "igsh", "mc_cid", "mc_eid",
        "_hsenc", "_hsmi", "ref_src", "ref_url", "yclid", "twclid", "mkt_tok",
    )

    /** First URL in [text], with trailing punctuation trimmed. */
    fun findUrl(text: String): String? {
        var u = urlRe.find(text)?.value ?: return null
        while (u.isNotEmpty() && u.last() in ".,;:!?)]}>»”’") {
            // keep a closing paren that has a matching opener (wikipedia-style links)
            if (u.last() == ')' && u.count { it == '(' } >= u.count { it == ')' }) break
            u = u.dropLast(1)
        }
        return u
    }

    private fun host(url: String) = Uri.parse(url).host.orEmpty().lowercase().removePrefix("www.").removePrefix("m.")

    private fun isYouTube(url: String) = host(url).let { it == "youtube.com" || it == "youtu.be" || it.endsWith(".youtube.com") }

    private val schemeRe = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val opaqueSchemes = setOf("mailto", "tel", "sms", "smsto", "geo", "magnet", "market", "intent", "spotify")

    /**
     * Makes a typed URL openable: lower-cases the scheme ("Https://" from auto-capitalisation)
     * and adds https:// to bare domains ("example.com/x"). Leaves mailto:, tel: and friends alone.
     */
    fun normalize(raw: String): String {
        val t = raw.trim()
        schemeRe.find(t)?.let { m ->
            val scheme = m.groupValues[1]
            val lower = scheme.lowercase()
            if (t.regionMatches(scheme.length, "://", 0, 3) || lower in opaqueSchemes) return lower + t.substring(scheme.length)
        }
        if (t.isNotEmpty() && ' ' !in t && '.' in t) return "https://$t"
        return t
    }

    /** Drops tracking parameters (utm_*, fbclid, YouTube's si, ...). */
    fun clean(url: String): String = try {
        val uri = Uri.parse(url)
        val names = uri.queryParameterNames
        val ytOrSpotify = isYouTube(url) || host(url).endsWith("spotify.com")
        fun tracking(k: String) = k.startsWith("utm_") || k in trackers || (ytOrSpotify && (k == "si" || k == "feature" || k == "pp"))
        if (names.none(::tracking)) {
            url
        } else {
            val b = uri.buildUpon().clearQuery()
            for (k in names) if (!tracking(k)) for (v in uri.getQueryParameters(k)) b.appendQueryParameter(k, v)
            b.build().toString()
        }
    } catch (_: Throwable) {
        url
    }

    /** Normalises titles that share sheets wrap in boilerplate. */
    fun cleanTitle(s: String?): String? {
        var t = s?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        Regex("""^Watch "(.+)" on YouTube$""").find(t)?.let { t = it.groupValues[1] }
        t = t.removeSuffix(" - YouTube").removeSuffix(" – YouTube")
        if (t.length >= 2 && t.first() == '"' && t.last() == '"') t = t.substring(1, t.length - 1)
        return t.trim().ifEmpty { null }
    }

    /** Network: the page title (YouTube via oEmbed). Null on any failure. Call off the main thread. */
    fun fetchTitle(url: String): String? = try {
        if (isYouTube(url)) {
            JSONObject(get("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8"), 64 * 1024)).getString("title")
        } else {
            val html = get(url, 384 * 1024)
            val raw = Regex("""<meta[^>]+property=["']og:title["'][^>]*content=["']([^"']+)""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]*property=["']og:title""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html)
            raw?.groupValues?.get(1)?.let { cleanTitle(Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString()) }
        }
    } catch (_: Throwable) {
        null
    }

    private fun get(url: String, max: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 5000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36")
        c.setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.5")
        try {
            val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(c.contentType ?: "")?.groupValues?.get(1)
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (out.size() < max) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            val cs = runCatching { Charset.forName(charset ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
            return String(out.toByteArray(), cs)
        } finally {
            c.disconnect()
        }
    }
}
