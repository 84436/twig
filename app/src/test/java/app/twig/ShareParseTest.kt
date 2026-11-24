package app.twig

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.twig.share.Glyph
import app.twig.share.LinkInfo
import app.twig.share.LinkLook
import app.twig.share.SharePayload
import app.twig.share.Sites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [37])
class ShareParseTest {

    @Test
    fun youtubeApp() {
        val p = SharePayload.parse("https://youtu.be/dQw4w9WgXcQ?si=Abc123xyz", "Never Gonna Give You Up")!!
        assertEquals("Never Gonna Give You Up", p.title)
        assertEquals("https://youtu.be/dQw4w9WgXcQ", p.url)
    }

    @Test
    fun youtubeOldSubjectFormat() {
        val p = SharePayload.parse("https://www.youtube.com/watch?v=abc&feature=shared", "Watch \"Cool Video\" on YouTube")!!
        assertEquals("Cool Video", p.title)
        assertEquals("https://www.youtube.com/watch?v=abc", p.url)
    }

    @Test
    fun browserTabStripsTracking() {
        val p = SharePayload.parse("https://example.com/post?id=3&utm_source=tw&utm_medium=x&fbclid=zz", "A good post")!!
        assertEquals("A good post", p.title)
        assertEquals("https://example.com/post?id=3", p.url)
    }

    @Test
    fun textAroundUrlBecomesTitle() {
        val p = SharePayload.parse("Great thread on outliners https://x.com/foo/status/1", null)!!
        assertEquals("Great thread on outliners", p.title)
        assertEquals("https://x.com/foo/status/1", p.url)
    }

    @Test
    fun bareUrlAndUrlSubject() {
        assertEquals(SharePayload("", "https://example.com"), SharePayload.parse("https://example.com", null))
        assertEquals(SharePayload("", "https://example.com/x"), SharePayload.parse("https://example.com/x", "https://example.com/x"))
    }

    @Test
    fun plainText() {
        assertEquals(SharePayload("buy milk\nand eggs", null), SharePayload.parse("  buy milk\nand eggs ", null))
        assertEquals("Subject\nthe body", SharePayload.parse("the body", "Subject")!!.title)
        assertNull(SharePayload.parse("  ", null))
    }

    @Test
    fun cleaningLeavesTheRestAlone() {
        assertEquals("https://g.co/search?q=a+b&b=%2F", LinkInfo.clean("https://g.co/search?utm_source=x&q=a+b&b=%2F"))
        assertEquals("https://a.com/p#frag", LinkInfo.clean("https://a.com/p?fbclid=1#frag"))
        assertEquals("https://a.com/p?x&y=", LinkInfo.clean("https://a.com/p?x&y="))
    }

    @Test
    fun urlPunctuation() {
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", LinkInfo.findUrl("see https://en.wikipedia.org/wiki/Foo_(bar)."))
        assertEquals("https://a.com/b", LinkInfo.findUrl("(https://a.com/b)"))
        assertEquals("https://a.com/b", LinkInfo.findUrl("link: https://a.com/b, ok"))
    }

    @Test
    fun normalize() {
        assertEquals("https://example.com/x", LinkInfo.normalize(" example.com/x "))
        assertEquals("https://Example.com", LinkInfo.normalize("Https://Example.com"))
        assertEquals("mailto:a@b.c", LinkInfo.normalize("MAILTO:a@b.c"))
    }

    private fun look(url: String) = Sites.look(url)
    private fun plain(text: String) = LinkLook(Glyph.Link, text)
    private fun yt(what: String) = LinkLook(Glyph.YouTube, "YouTube $what")

    @Test
    fun youtube() {
        assertEquals(yt("video"), look("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(yt("video"), look("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(yt("video"), look("https://m.youtube.com/watch?v=abc&t=10"))
        assertEquals(yt("video"), look("https://youtube.com/shorts/abc"))
        assertEquals(yt("video"), look("https://music.youtube.com/watch?v=abc"))
        assertEquals(yt("video"), look("https://www.youtube.com/watch?v=abc&list=PL1")) // a video in a playlist is a video
        assertEquals(yt("channel"), look("https://www.youtube.com/@channel"))
        assertEquals(yt("channel"), look("https://www.youtube.com/@channel/videos"))
        assertEquals(yt("channel"), look("https://www.youtube.com/channel/UC123"))
        assertEquals(yt("channel"), look("https://www.youtube.com/c/Name"))
        assertEquals(yt("channel"), look("https://www.youtube.com/user/name"))
        assertEquals(yt("playlist"), look("https://www.youtube.com/playlist?list=PL123"))
        assertEquals(plain("youtube.com"), look("https://www.youtube.com/playlist"))
        assertEquals(plain("youtube.com"), look("https://www.youtube.com/watch"))
        assertEquals(plain("youtube.com"), look("https://www.youtube.com/"))
        assertEquals(plain("youtube.com"), look("https://www.youtube.com/results?search_query=x"))
    }

    @Test
    fun githubExactRepoOnly() {
        val repo = LinkLook(Glyph.GitHub, "84436/twig")
        assertEquals(repo, look("https://github.com/84436/twig"))
        assertEquals(repo, look("https://github.com/84436/twig/"))
        assertEquals(repo, look("https://www.github.com/84436/twig"))
        assertEquals(plain("github.com"), look("https://github.com/84436/twig/releases"))
        assertEquals(plain("github.com"), look("https://github.com/"))
        assertEquals(plain("github.com"), look("https://github.com/84436"))
        assertEquals(plain("github.com"), look("https://github.com/84436/twig?tab=readme"))
        assertEquals(plain("github.com"), look("https://github.com/84436/twig#readme"))
        assertEquals(plain("github.com"), look("https://github.com/features/copilot"))
        assertEquals(plain("github.com"), look("https://github.com/advisories/GHSA-jfh8-c2jp-5v3q"))
        assertEquals(plain("gist.github.com"), look("https://gist.github.com/84436/abc"))
    }

    @Test
    fun osuBeatmaps() {
        val map = LinkLook(Glyph.Cookie, "Beatmap")
        assertEquals(map, look("https://osu.ppy.sh/beatmapsets/1234#osu/5678"))
        assertEquals(map, look("https://osu.ppy.sh/beatmapsets/1234"))
        assertEquals(map, look("https://osu.ppy.sh/beatmaps/5678"))
        assertEquals(map, look("https://osu.ppy.sh/b/5678"))
        assertEquals(map, look("https://osu.ppy.sh/s/1234"))
        assertEquals(plain("osu.ppy.sh"), look("https://osu.ppy.sh/beatmapsets"))
        assertEquals(plain("osu.ppy.sh"), look("https://osu.ppy.sh/users/2"))
        assertEquals(plain("osu.ppy.sh"), look("https://osu.ppy.sh/beatmapsets/discussions"))
    }

    @Test
    fun everythingElseShowsTheHost() {
        assertEquals(plain("example.com"), look("https://www.example.com/a/b?c=d"))
        assertEquals(plain("en.m.wikipedia.org"), look("https://en.m.wikipedia.org/wiki/X"))
        assertEquals(plain("mailto:a@b.c"), look("mailto:a@b.c"))
        assertEquals(plain("not a url"), look("not a url"))
        assertEquals(yt("video"), look("youtube.com/watch?v=abc")) // typed without https://
        assertEquals(plain("example.com"), look("Example.com/aurora"))
    }
}
