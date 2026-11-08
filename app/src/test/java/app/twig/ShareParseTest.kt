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
@Config(sdk = [35])
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
    private val yt = LinkLook(Glyph.Play, "YouTube")

    @Test
    fun youtubeVideosOnly() {
        assertEquals(yt, look("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(yt, look("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(yt, look("https://m.youtube.com/watch?v=abc&t=10"))
        assertEquals(yt, look("https://youtube.com/shorts/abc"))
        assertEquals(yt, look("https://music.youtube.com/watch?v=abc"))
        assertEquals(plain("youtube.com"), look("https://www.youtube.com/@channel"))
        assertEquals(plain("youtube.com"), look("https://www.youtube.com/watch"))
        assertEquals(plain("youtube.com"), look("https://www.youtube.com/"))
    }

    @Test
    fun githubExactRepoOnly() {
        assertEquals(plain("84436/twig"), look("https://github.com/84436/twig"))
        assertEquals(plain("84436/twig"), look("https://github.com/84436/twig/"))
        assertEquals(plain("84436/twig"), look("https://www.github.com/84436/twig"))
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
    fun everythingElseShowsTheHost() {
        assertEquals(plain("example.com"), look("https://www.example.com/a/b?c=d"))
        assertEquals(plain("en.m.wikipedia.org"), look("https://en.m.wikipedia.org/wiki/X"))
        assertEquals(plain("mailto:a@b.c"), look("mailto:a@b.c"))
        assertEquals(plain("not a url"), look("not a url"))
    }
}
