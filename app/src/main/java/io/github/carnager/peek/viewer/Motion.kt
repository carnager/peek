package io.github.carnager.peek.viewer

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import io.github.carnager.peek.trunk.TrunkAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Motion photos: JPEGs with a short MP4 appended, as Pixels take them. For photos on the phone
 * peek finds the video itself, the way trunk does (see trunk's internal/media/motion.go): the XMP
 * says how long it is, and it must be an MP4 running to the end of the file.
 */
object MotionPhotos {
    private val item = Regex("<\\w*:Item\\b[^>]*>")
    private val semantic = Regex(":Semantic=\"MotionPhoto\"")
    private val itemLength = Regex(":Length=\"(\\d+)\"")
    private val microOffset = Regex("MicroVideoOffset(?:=\"|>)(\\d+)")
    private val flag = Regex("MotionPhoto(?:=\"1\"|>1<)|MicroVideo(?:=\"1\"|>1<)|:Semantic=\"MotionPhoto\"")

    /** Where the video sits: offset and length; null for a still. Remembered per file version. */
    private val found = ConcurrentHashMap<String, LongArray>()

    private fun key(uri: Uri, version: Long) = "$uri#$version"

    /** Finds the video in a photo on the phone; null if it is a still. Reads the header only. */
    suspend fun find(ctx: Context, uri: Uri, version: Long): LongArray? = withContext(Dispatchers.IO) {
        found[key(uri, version)]?.let { return@withContext it.takeIf { it[1] > 0 } }
        val r = runCatching { detect(ctx, uri) }.getOrNull()
        found[key(uri, version)] = r ?: longArrayOf(0, 0)
        r
    }

    private fun detect(ctx: Context, uri: Uri): LongArray? {
        val res = ctx.contentResolver
        val size = res.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: return null
        if (size <= 0) return null
        val head = res.openInputStream(uri)?.use { it.readNBytes(256 * 1024) } ?: return null
        if (head.size < 2 || head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) return null
        // XMP is text; Latin-1 keeps one char per byte, so positions stay byte offsets.
        val text = String(head, Charsets.ISO_8859_1)
        if (!flag.containsMatchIn(text)) return null
        var length = item.findAll(text).filter { semantic.containsMatchIn(it.value) }
            .mapNotNull { itemLength.find(it.value)?.groupValues?.get(1)?.toLongOrNull() }.firstOrNull() ?: 0L
        if (length == 0L) length = microOffset.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        if (length in 9 until size && mp4ToEnd(ctx, uri, size - length, size)) return longArrayOf(size - length, length)
        // A stale or missing length: look for the MP4 itself.
        val all = res.openInputStream(uri)?.use { it.readBytes() } ?: return null
        var i = 4
        while (true) {
            val j = indexOf(all, "ftyp".toByteArray(), i)
            if (j < 0) return null
            val off = (j - 4).toLong()
            if (boxesToEnd(all, off)) return longArrayOf(off, size - off)
            i = j + 4
        }
    }

    private fun mp4ToEnd(ctx: Context, uri: Uri, off: Long, size: Long): Boolean {
        val tail = ctx.contentResolver.openInputStream(uri)?.use { s ->
            s.skipNBytes(off)
            s.readBytes()
        } ?: return false
        return tail.size.toLong() == size - off && boxesToEnd(tail, 0)
    }

    /** An MP4 starts at [off] and its top-level boxes run exactly to the end, a movie header among them. */
    private fun boxesToEnd(b: ByteArray, off: Long): Boolean {
        var pos = off
        var first = true
        var moov = false
        val end = b.size.toLong()
        while (pos < end) {
            if (pos + 8 > end) return false
            val p = pos.toInt()
            var size = ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
                ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) {
                if (pos + 16 > end) return false
                size = 0
                for (k in 8 until 16) size = (size shl 8) or (b[p + k].toLong() and 0xFF)
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < 8 || pos + size > end || (first && type != "ftyp")) return false
            if (type.any { it.code < 0x20 || it.code > 0x7e }) return false
            if (type == "moov") moov = true
            first = false
            pos += size
        }
        return pos == end && moov
    }

    private fun indexOf(b: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..b.size - needle.size) {
            for (k in needle.indices) if (b[i + k] != needle[k]) continue@outer
            return i
        }
        return -1
    }

    /** Copies a phone photo's video to the cache, to play it. */
    suspend fun extract(ctx: Context, uri: Uri, version: Long, where: LongArray): Uri? = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "motion").apply { mkdirs() }
        val out = File(dir, "${key(uri, version).hashCode().toUInt()}.mp4")
        if (out.length() == where[1]) return@withContext Uri.fromFile(out)
        // Keep the cache small: only the last few videos played.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(8)?.forEach { it.delete() }
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { s ->
                s.skipNBytes(where[0])
                out.outputStream().use { o -> s.copyTo(o) }
            }
            Uri.fromFile(out)
        }.getOrNull()
    }
}

/**
 * Plays a motion photo's video once over the still, then [onDone]; a tap stops it. The still stays
 * visible underneath until the first frame is there.
 */
@Composable
fun MotionOverlay(source: Uri, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember(source) {
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
        TrunkAccount.token?.takeIf { TrunkAccount.owns(source.toString()) }
            ?.let { http.setDefaultRequestProperties(mapOf("Authorization" to "Bearer $it")) }
        val sources = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(ctx, http))
        ExoPlayer.Builder(ctx).setMediaSourceFactory(sources).build().apply {
            setMediaItem(MediaItem.fromUri(source))
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) onDone() }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { onDone() }
            })
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    ContentFrame(
        player = player,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDone() } },
        surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
        contentScale = ContentScale.Fit,
        shutter = {},
    )
}
