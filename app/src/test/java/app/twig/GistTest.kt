package app.twig

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.twig.data.Gist
import app.twig.data.Store
import app.twig.data.TextFormat
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [37])
class GistTest {
    /** GitHub's gist API, just the parts Twig uses: every gist is a list of twig.md revisions. */
    private class FakeGitHub {
        val gists = LinkedHashMap<String, MutableList<String>>()
        var refuse = false

        fun latest(id: String) = gists.getValue(id).last()

        fun reply(method: String, url: String, body: String?): Gist.Reply {
            if (refuse) return Gist.Reply(401, """{"message":"Bad credentials"}""")
            val path = url.removePrefix("https://api.github.com/gists")
            val id = path.removePrefix("/").substringBefore('/').substringBefore('?')
            val text = body?.let { JSONObject(it).getJSONObject("files").getJSONObject("twig.md").getString("content") }
            return when {
                method == "GET" && path.startsWith("?") ->
                    ok(JSONArray(gists.keys.map { JSONObject().put("id", it).put("files", JSONObject().put("twig.md", JSONObject())) }))
                method == "POST" -> {
                    val new = "g${gists.size + 1}"
                    gists[new] = mutableListOf(text!!)
                    ok(gist(new))
                }
                id !in gists -> Gist.Reply(404, """{"message":"Not Found"}""")
                path.endsWith("/commits?per_page=1") -> ok(JSONArray().put(JSONObject().put("version", rev(id))))
                method == "GET" -> ok(gist(id))
                else -> {
                    gists.getValue(id).add(text!!)
                    ok(gist(id))
                }
            }
        }

        private fun rev(id: String) = "$id@${gists.getValue(id).size}"

        private fun gist(id: String) = JSONObject()
            .put("id", id)
            .put("files", JSONObject().put("twig.md", JSONObject().put("content", latest(id)).put("truncated", false)))
            .put("history", JSONArray().put(JSONObject().put("version", rev(id))))

        private fun ok(json: Any) = Gist.Reply(200, json.toString())
    }

    private val github = FakeGitHub()

    @Before
    fun setup() {
        Store.awaitLoaded()
        Store.ensureSeeded()
        Gist.http = github::reply
    }

    private fun connect() {
        Gist.token = "token"
        sync()
    }

    private fun sync() = runBlocking { Gist.sync() }

    private fun here() = TextFormat.export(Store.root)

    private fun labels() = Store.root.children.map { Store.label(it) }

    @Test
    fun firstPhoneMakesTheGist() {
        connect()
        assertEquals(listOf("g1"), github.gists.keys.toList())
        assertEquals(here(), github.latest("g1"))
        assertTrue(Gist.status!!, Gist.status!!.startsWith("Synced"))
        sync() // nothing new on either side: nothing sent
        assertEquals(1, github.gists.getValue("g1").size)
    }

    @Test
    fun anotherPhoneTakesTheGist() {
        github.gists["g1"] = mutableListOf("- From the other phone\n\t- an item\n")
        connect()
        assertEquals(listOf("From the other phone"), labels())
        assertEquals(1, github.gists.getValue("g1").size) // taking it changes nothing online
    }

    @Test
    fun editsGoUpAndComeDown() {
        connect()
        Store.create(Store.root, 0, "Written here")
        sync()
        assertTrue(github.latest("g1").startsWith("- Written here\n"))
        github.gists.getValue("g1").add("- Written there\n")
        sync()
        assertEquals(listOf("Written there"), labels())
    }

    @Test
    fun theOpenListStaysOpen() {
        connect()
        val list = Store.root.children.first { Store.label(it) == "Bucket list" }
        Store.zoom = list
        github.gists.getValue("g1").add(github.latest("g1") + "\t- Climb a volcano\n")
        sync()
        assertEquals("Bucket list", Store.label(Store.zoom))
        assertEquals("Climb a volcano", Store.zoom.children.last().plain)
    }

    @Test
    fun changedOnBothSidesThisPhoneWinsWithUndo() {
        connect()
        github.gists.getValue("g1").add("- Written there\n")
        Store.create(Store.root, 0, "Written here")
        sync()
        assertTrue(github.latest("g1").startsWith("- Written here\n"))
        Store.notice!!.undo()
        assertEquals(listOf("Written there"), labels())
        sync()
        assertEquals("- Written there\n", github.latest("g1"))
    }

    @Test
    fun aDeletedGistIsMadeAgain() {
        connect()
        github.gists.clear()
        Store.create(Store.root, 0, "Still here")
        sync()
        assertEquals(listOf("g1"), github.gists.keys.toList())
        assertEquals(here(), github.latest("g1"))
    }

    @Test
    fun editsWhileOffGoUpWithTheNextToken() {
        connect()
        Gist.token = ""
        Store.create(Store.root, 0, "Written offline")
        Gist.token = "renewed"
        sync()
        assertEquals(listOf("g1"), github.gists.keys.toList()) // the same gist, not a fresh start
        assertEquals(here(), github.latest("g1"))
    }

    @Test
    fun aBadTokenSaysSo() {
        github.refuse = true
        connect()
        assertEquals("GitHub says: Bad credentials", Gist.status)
    }
}
