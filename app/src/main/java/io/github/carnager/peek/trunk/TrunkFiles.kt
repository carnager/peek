package io.github.carnager.peek.trunk

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Photos and videos from trunk as local files, for the editor and for sharing: the current version,
 * i.e. the edit if there is one. They live in the cache
 * (Android may clear it) and are handed out through a FileProvider, so other apps can read them.
 */
object TrunkFiles {
    private fun dir(ctx: Context) = File(ctx.cacheDir, "trunk/originals").apply { mkdirs() }

    /** Downloads the file once per version and returns a content:// URI for it. */
    suspend fun file(ctx: Context, asset: RemoteAsset): Uri = withContext(Dispatchers.IO) {
        // Id and version keep names unique; the name keeps the extension other apps look at,
        // which an edit may have changed (HEIC in, JPEG out).
        val base = asset.name.replace('/', '_')
        val ext = extensions[asset.mime] ?: base.substringAfterLast('.', "")
        val name = if (ext.isEmpty()) base else base.substringBeforeLast('.') + "." + ext
        val file = File(dir(ctx), "${asset.id}_${asset.version}_$name")
        if (!file.exists() || file.length() != asset.size) {
            val tmp = File(file.path + ".part")
            TrunkClient.http.newCall(Request.Builder().url(TrunkAccount.fileUrl(asset)).build()).execute().use { r ->
                if (!r.isSuccessful) throw TrunkException(r.code, "Original konnte nicht geladen werden (HTTP ${r.code})")
                tmp.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
            }
            tmp.renameTo(file)
        }
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    }

    private val extensions = mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/heic" to "heic")

    /** Drops downloaded files, e.g. after logging out. */
    fun clear(ctx: Context) {
        dir(ctx).deleteRecursively()
    }
}
