package io.github.carnager.peek.trunk

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Originals from trunk as local files, for the editor and for sharing. They live in the cache
 * (Android may clear it) and are handed out through a FileProvider, so other apps can read them.
 */
object TrunkFiles {
    private fun dir(ctx: Context) = File(ctx.cacheDir, "trunk/originals").apply { mkdirs() }

    /** Downloads the original once and returns a content:// URI for it. */
    suspend fun original(ctx: Context, asset: RemoteAsset): Uri = withContext(Dispatchers.IO) {
        // The id keeps names unique; the original name keeps the extension other apps look at.
        val file = File(dir(ctx), "${asset.id}_${asset.name.replace('/', '_')}")
        if (!file.exists() || file.length() != asset.size) {
            val tmp = File(file.path + ".part")
            TrunkClient.http.newCall(Request.Builder().url(TrunkAccount.originalUrl(asset.id)).build()).execute().use { r ->
                if (!r.isSuccessful) throw TrunkException(r.code, "Original konnte nicht geladen werden (HTTP ${r.code})")
                tmp.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
            }
            tmp.renameTo(file)
        }
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    }

    /** Drops downloaded originals, e.g. after logging out. */
    fun clear(ctx: Context) {
        dir(ctx).deleteRecursively()
    }
}
