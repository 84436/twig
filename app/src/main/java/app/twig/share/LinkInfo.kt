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

    data class Info(val title: String, val author: String?)

    /** First URL in [text], with trailing punctuation trimmed. */
    fun findUrl(text: String): String? {
        val m = urlRe.find(text) ?: return null
        var u = m.value
        while (u.isNotEmpty() && u.last() in ".,;:!?)]}>»”’") {
            // keep a closing paren that has a matching opener (wikipedia-style links)
            if (u.last() == ')' && u.count { it == '(' } >= u.count { it == ')' }) break
            u = u.dropLast(1)
        }
        return u
    }

    fun host(url: String): String {
        val h = try {
            Uri.parse(url).host
        } catch (_: Throwable) {
            null
        } ?: return url
        return h.removePrefix("www.").removePrefix("m.")
    }

    fun isYouTube(url: String): Boolean {
        val h = host(url)
        return h == "youtube.com" || h == "youtu.be" || h.endsWith(".youtube.com") || h == "youtube-nocookie.com"
    }

    fun isVideo(url: String): Boolean {
        val h = host(url)
        return isYouTube(url) || h == "vimeo.com" || h.endsWith("tiktok.com") || h == "twitch.tv" || h == "dailymotion.com"
    }

    private val schemeRe = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val opaqueSchemes = setOf("mailto", "tel", "sms", "smsto", "geo", "magnet", "market", "intent", "spotify")

    /**
     * Makes a typed link openable: lower-cases the scheme ("Https://" from auto-capitalisation)
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

    /** The URL without scheme or "www.", which is what people want to read. */
    fun display(url: String): String {
        var s = url.trim()
        for (p in listOf("https://", "http://")) if (s.startsWith(p, ignoreCase = true)) s = s.substring(p.length)
        for (p in listOf("www.", "m.")) if (s.startsWith(p, ignoreCase = true)) s = s.substring(p.length)
        return s.trimEnd('/')
    }

    /** "youtu.be/dQw4w9WgXcQ · Channel" style line under a link item. */
    fun label(url: String, note: String?): String {
        val d = display(url)
        return if (note.isNullOrBlank()) d else "$d · $note"
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

    /** Network: page title (YouTube via oEmbed). Null on any failure. Call off the main thread. */
    fun fetch(url: String): Info? = try {
        if (isYouTube(url)) {
            val body = get("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8"), 64 * 1024)
            val o = JSONObject(body)
            Info(o.getString("title"), o.optString("author_name").ifBlank { null })
        } else {
            val html = get(url, 384 * 1024)
            val og = Regex("""<meta[^>]+property=["']og:title["'][^>]*content=["']([^"']+)""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]*property=["']og:title""", RegexOption.IGNORE_CASE).find(html)
            val raw = og?.groupValues?.get(1)
                ?: Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            val site = Regex("""<meta[^>]+property=["']og:site_name["'][^>]*content=["']([^"']+)""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
            raw?.let { decode(it) }?.let { cleanTitle(it) }?.let { Info(it, site?.let(::decode)) }
        }
    } catch (_: Throwable) {
        null
    }

    private fun decode(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().trim()

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
            val cs = try {
                Charset.forName(charset ?: "UTF-8")
            } catch (_: Throwable) {
                Charsets.UTF_8
            }
            return String(out.toByteArray(), cs)
        } finally {
            c.disconnect()
        }
    }
}
