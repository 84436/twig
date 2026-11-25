package app.twig.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/**
 * Sync through a secret gist holding twig.md, the same Markdown as Copy all. Twig never waits for it:
 * it runs when Twig opens or closes, a few seconds after an edit, and every 15 minutes in the background.
 * When both sides changed, this phone wins and Undo brings the gist's copy back; the gist's
 * revisions keep everything else.
 */
object Gist {
    private const val API = "https://api.github.com/gists"
    private const val FILE = "twig.md"
    private const val JOB = 1

    class Reply(val code: Int, val body: String)
    private class Refused(message: String, val code: Int) : IOException(message)

    /** Network. Swappable for tests. */
    var http: (method: String, url: String, body: String?) -> Reply = ::call

    /** How the last sync went, for Settings. */
    var status by mutableStateOf<String?>(null)
        private set

    private lateinit var ctx: Context
    private val lock = Mutex()
    private val client by lazy { OkHttpClient() }
    /** Edits so far: a push only settles the ones it carried. */
    private var edits = 0
    private val run = Runnable { Store.appScope.launch { sync() } }

    fun reset(context: Context) {
        ctx = context.applicationContext
        http = ::call
        status = null
        edits = 0
    }

    /** A fine-grained GitHub token with Gists: read and write. Empty: no sync. */
    var token: String
        get() = Store.getMeta("gistToken")
        set(v) {
            if (v.trim() == token) return
            Store.setMeta("gistToken", v.trim()) // a renewed token carries on with the same gist
            status = null
            start(1500)
        }

    /** Sync soon, and every 15 minutes while Twig isn't open. */
    fun start(delay: Long = 0) {
        val jobs = ctx.getSystemService(JobScheduler::class.java)
        if (token.isEmpty()) {
            jobs.cancel(JOB)
        } else if (jobs.getPendingJob(JOB) == null) {
            jobs.schedule(
                JobInfo.Builder(JOB, ComponentName(ctx, SyncJob::class.java))
                    .setPeriodic(15 * 60_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
            )
        }
        soon(delay)
    }

    private fun soon(delay: Long) {
        Store.main.removeCallbacks(run)
        if (token.isNotEmpty()) Store.main.postDelayed(run, delay)
    }

    /** Every edit in [Store] lands here. */
    fun changed() {
        edits++
        Store.setMeta("gistDirty", "1") // also while sync is off, so it goes up once it's back on
        soon(5000)
    }

    suspend fun sync() = lock.withLock {
        if (token.isEmpty()) return@withLock
        status = try {
            exchange()
            "Synced at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            (e as? Refused)?.message ?: "Can't reach GitHub. Trying again later."
        }
    }

    private suspend fun exchange() {
        val id = Store.getMeta("gistId").ifEmpty { find() }
            ?: return save("POST", API) // nothing online yet: this phone's copy goes up
        val latest = try {
            JSONArray(request("GET", "$API/$id/commits?per_page=1")).getJSONObject(0).getString("version")
        } catch (e: Refused) {
            if (e.code == 404) return save("POST", API) // deleted on GitHub: start a new one
            throw e
        }
        val mine = Store.getMeta("gistRev")
        val dirty = Store.getMeta("gistDirty").isNotEmpty()
        if (latest == mine) {
            if (dirty) save("PATCH", "$API/$id")
            return
        }
        val gist = JSONObject(request("GET", "$API/$id"))
        val file = gist.getJSONObject("files").optJSONObject(FILE) ?: return save("PATCH", "$API/$id")
        if (file.optBoolean("truncated")) throw Refused("twig.md is over 1 MB, too big to sync", 0)
        val theirs = file.getString("content")
        when {
            // changed here and there: this phone wins, Undo takes the gist's copy instead
            mine.isNotEmpty() && dirty -> {
                save("PATCH", "$API/$id")
                Store.notice = Notice("Replaced newer changes from the gist") { Store.replaceAll(TextFormat.parse(theirs)) }
            }
            // new to this gist, or nothing to lose here; never under the cursor
            Store.focusedId == null -> {
                Store.replaceAll(TextFormat.parse(theirs))
                settle(id, revision(gist), edits)
            }
        }
    }

    /** Puts this phone's copy in the gist (a new one for POST). */
    private suspend fun save(method: String, url: String) {
        val sent = edits
        val text = TextFormat.export(Store.root)
        if (text.isBlank()) return // GitHub keeps no empty files
        val body = JSONObject().put("files", JSONObject().put(FILE, JSONObject().put("content", text)))
        if (method == "POST") body.put("description", "Twig").put("public", false)
        val gist = JSONObject(request(method, url, body.toString()))
        settle(gist.getString("id"), revision(gist), sent)
    }

    private fun settle(id: String, rev: String, sent: Int) {
        Store.setMeta("gistId", id)
        Store.setMeta("gistRev", rev)
        if (edits != sent) return // edited while syncing: those go up next time
        Store.setMeta("gistDirty", "")
        Store.main.removeCallbacks(run)
    }

    private fun revision(gist: JSONObject) = gist.getJSONArray("history").getJSONObject(0).getString("version")

    /** A gist of ours from another phone, or an earlier connection. */
    private suspend fun find(): String? {
        val list = JSONArray(request("GET", "$API?per_page=100"))
        return (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.getJSONObject("files").has(FILE) }?.getString("id")
    }

    private suspend fun request(method: String, url: String, body: String? = null): String {
        val r = withContext(Dispatchers.IO) { http(method, url, body) }
        if (r.code in 200..299) return r.body
        val why = runCatching { JSONObject(r.body).getString("message") }.getOrDefault("error ${r.code}")
        throw Refused("GitHub says: $why", r.code)
    }

    private fun call(method: String, url: String, body: String?): Reply {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .method(method, body?.toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute().use { Reply(it.code, it.body.string()) }
    }
}

/** The background sync [Gist.start] schedules. */
class SyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Store.appScope.launch {
            withContext(Dispatchers.IO) { Store.awaitLoaded() }
            Gist.sync()
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = false
}
