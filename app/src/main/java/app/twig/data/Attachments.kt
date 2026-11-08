package app.twig.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.webkit.MimeTypeMap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.UUID

/** Shared images are copied into app storage so they survive the source app deleting them. */
object Attachments {
    private val cache = LruCache<String, ImageBitmap>(48)

    fun dir(ctx: Context): File = File(ctx.filesDir, "att").apply { mkdirs() }

    fun file(ctx: Context, name: String) = File(dir(ctx), name)

    fun copy(ctx: Context, uri: Uri): String? {
        return try {
            val type = ctx.contentResolver.getType(uri)
            val ext = type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "jpg"
            val name = UUID.randomUUID().toString() + "." + ext
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            input.use { inp -> file(ctx, name).outputStream().use { inp.copyTo(it, 64 * 1024) } }
            name
        } catch (t: Throwable) {
            null
        }
    }

    fun cached(name: String): ImageBitmap? = cache.get(name)

    /** Decodes a downsampled thumbnail no larger than [maxPx] on its long side. */
    fun thumb(ctx: Context, name: String, maxPx: Int): ImageBitmap? {
        cache.get(name)?.let { return it }
        val f = file(ctx, name)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return bmp.asImageBitmap().also { cache.put(name, it) }
    }

    /** Deletes files no node points to (older than a few minutes, so in-flight shares are safe). */
    fun cleanup(ctx: Context, referenced: Set<String>) {
        val cutoff = System.currentTimeMillis() - 10 * 60_000
        dir(ctx).listFiles()?.forEach { f ->
            if (f.name !in referenced && f.lastModified() < cutoff) f.delete()
        }
    }
}
