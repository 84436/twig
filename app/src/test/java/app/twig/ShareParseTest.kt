package app.twig

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.twig.share.LinkInfo
import app.twig.share.SharePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertFalse(p.needsTitle)
        assertTrue(LinkInfo.isYouTube(p.url!!))
        assertTrue(LinkInfo.isVideo(p.url!!))
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
    fun bareUrlNeedsTitle() {
        val p = SharePayload.parse("https://example.com", null)!!
        assertEquals("", p.title)
        assertTrue(p.needsTitle)
    }

    @Test
    fun subjectEqualToUrlIsIgnored() {
        val p = SharePayload.parse("https://example.com/x", "https://example.com/x")!!
        assertTrue(p.needsTitle)
    }

    @Test
    fun plainText() {
        val p = SharePayload.parse("  buy milk\nand eggs ", null)!!
        assertEquals("buy milk\nand eggs", p.title)
        assertNull(p.url)
    }

    @Test
    fun plainTextWithSubject() {
        val p = SharePayload.parse("the body", "Subject")!!
        assertEquals("Subject\nthe body", p.title)
    }

    @Test
    fun nothing() {
        assertNull(SharePayload.parse("  ", null))
    }

    @Test
    fun urlPunctuation() {
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", LinkInfo.findUrl("see https://en.wikipedia.org/wiki/Foo_(bar)."))
        assertEquals("https://a.com/b", LinkInfo.findUrl("(https://a.com/b)"))
        assertEquals("https://a.com/b", LinkInfo.findUrl("link: https://a.com/b, ok"))
    }

    @Test
    fun labels() {
        assertEquals("youtube.com/watch?v=1 · Rick", LinkInfo.label("https://m.youtube.com/watch?v=1", "Rick"))
        assertEquals("nytimes.com/a", LinkInfo.label("https://www.nytimes.com/a/", null))
        assertEquals("youtu.be/dQw4w9WgXcQ", LinkInfo.display("https://youtu.be/dQw4w9WgXcQ"))
    }
}
