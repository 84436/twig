package app.twig.share

import android.net.Uri

enum class Glyph { Link, Play }

/** How a URL reads under its bullet: an icon and a short text. */
data class LinkLook(val glyph: Glyph, val text: String)

/**
 * Site-specific display tweaks. A rule gets the parsed URL and its host (no "www.") and either
 * returns a look or passes. The first rule that answers wins; with none, it's a link icon + host.
 * To support another site, write a rule and add it to [rules].
 */
object Sites {
    private val rules: List<(Uri, String) -> LinkLook?> = listOf(::youtube, ::github)

    fun look(url: String): LinkLook {
        val uri = Uri.parse(url.trim())
        val host = uri.host?.lowercase()?.removePrefix("www.")
        if (host.isNullOrEmpty() || uri.scheme?.lowercase() !in setOf("http", "https")) return LinkLook(Glyph.Link, url.trim())
        return rules.firstNotNullOfOrNull { it(uri, host) } ?: LinkLook(Glyph.Link, host)
    }

    /** A video: youtu.be/ID, /watch?v=ID, /shorts/ID, /live/ID. Channels and the rest stay plain. */
    private fun youtube(u: Uri, host: String): LinkLook? {
        val seg = u.pathSegments
        val video = when (host) {
            "youtu.be" -> seg.size == 1
            "youtube.com", "m.youtube.com", "music.youtube.com" ->
                (seg == listOf("watch") && !u.getQueryParameter("v").isNullOrEmpty()) ||
                    (seg.size == 2 && seg[0] in setOf("shorts", "live", "embed"))
            else -> false
        }
        return if (video) LinkLook(Glyph.Play, "YouTube") else null
    }

    private val repoPath = Regex("""/([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+)/?""")
    private val notOwners = setOf(
        "about", "apps", "collections", "customer-stories", "enterprise", "events", "explore", "features",
        "issues", "login", "marketplace", "new", "notifications", "orgs", "organizations", "pricing", "pulls",
        "search", "security", "settings", "sponsors", "topics", "trending",
    )

    /** Exactly github.com/owner/repo (a trailing slash is fine). Anything more or less stays plain. */
    private fun github(u: Uri, host: String): LinkLook? {
        if (host != "github.com" || u.query != null || u.fragment != null) return null
        val m = repoPath.matchEntire(u.path.orEmpty()) ?: return null
        val (owner, repo) = m.destructured
        return if (owner.lowercase() in notOwners) null else LinkLook(Glyph.Link, "$owner/$repo")
    }
}
