package app.twig.share

import android.net.Uri

enum class Glyph { Link, YouTube, GitHub, Cookie }

/** How a URL reads under its bullet: an icon and a short text. */
data class LinkLook(val glyph: Glyph, val text: String)

/**
 * Site-specific display tweaks. A rule gets the parsed URL and its host (no "www.") and either
 * returns a look or passes. The first rule that answers wins; with none, it's a link icon + host.
 * To support another site, write a rule and add it to [rules].
 */
object Sites {
    private val rules: List<(Uri, String) -> LinkLook?> = listOf(::youtube, ::github, ::osu)

    fun look(url: String): LinkLook {
        val uri = Uri.parse(url.trim())
        val host = uri.host?.lowercase()?.removePrefix("www.")
        if (host.isNullOrEmpty() || uri.scheme?.lowercase() !in setOf("http", "https")) return LinkLook(Glyph.Link, url.trim())
        return rules.firstNotNullOfOrNull { it(uri, host) } ?: LinkLook(Glyph.Link, host)
    }

    private val youtubeHosts = setOf("youtube.com", "m.youtube.com", "music.youtube.com")

    /** Videos, channels and playlists. YouTube's home page, search and the rest stay plain. */
    private fun youtube(u: Uri, host: String): LinkLook? {
        val seg = u.pathSegments
        val what = when {
            host == "youtu.be" -> "video".takeIf { seg.size == 1 }
            host !in youtubeHosts -> null
            seg == listOf("watch") && !u.getQueryParameter("v").isNullOrEmpty() -> "video"
            seg.size == 2 && seg[0] in setOf("shorts", "live", "embed") -> "video"
            seg == listOf("playlist") && !u.getQueryParameter("list").isNullOrEmpty() -> "playlist"
            seg.isNotEmpty() && seg[0].length > 1 && seg[0].startsWith("@") -> "channel"
            seg.size >= 2 && seg[0] in setOf("channel", "c", "user") -> "channel"
            else -> null
        } ?: return null
        return LinkLook(Glyph.YouTube, "YouTube $what")
    }

    /** osu! beatmaps: /beatmapsets/ID (any difficulty or mode after it), /beatmaps/ID, /b/ID, /s/ID. */
    private fun osu(u: Uri, host: String): LinkLook? {
        val seg = u.pathSegments
        val map = host == "osu.ppy.sh" && seg.size >= 2 && seg[0] in setOf("beatmapsets", "beatmaps", "b", "s") && seg[1].all(Char::isDigit)
        return if (map) LinkLook(Glyph.Cookie, "Beatmap") else null
    }

    private val repoPath = Regex("""/([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+)/?""")
    private val notOwners = setOf(
        "about", "account", "advisories", "apps", "codespaces", "collections", "contact", "customer-stories",
        "dashboard", "enterprise", "events", "explore", "features", "git-guides", "issues", "login", "marketplace",
        "new", "notifications", "orgs", "organizations", "pricing", "pulls", "readme", "resources", "search",
        "security", "settings", "site", "solutions", "sponsors", "stars", "team", "topics", "trending", "users",
    )

    /** Exactly github.com/owner/repo (a trailing slash is fine). Anything more or less stays plain. */
    private fun github(u: Uri, host: String): LinkLook? {
        if (host != "github.com" || u.query != null || u.fragment != null) return null
        val m = repoPath.matchEntire(u.path.orEmpty()) ?: return null
        val (owner, repo) = m.destructured
        return if (owner.lowercase() in notOwners) null else LinkLook(Glyph.GitHub, "$owner/$repo")
    }
}
