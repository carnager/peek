package io.github.carnager.peek.trunk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import io.github.carnager.peek.editor.ImageIO
import java.io.File
import java.io.FileInputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sends one photo or video from the phone to trunk: the manual upload and the automatic backup
 * both go through here. Originals are read unredacted, so the location stays in the file and the
 * hash matches what trunk has for the same file from elsewhere (a Google Takeout, another phone).
 */
object Uploader {
    class Item(val uri: Uri, val name: String, val mime: String, val size: Long, val taken: Long, val video: Boolean)

    /** Hash of the phone's file, as trunk identifies it. */
    fun hash(ctx: Context, item: Item): String =
        ctx.contentResolver.openInputStream(ImageIO.originalUri(ctx, item.uri))!!.use { sha256(it) }

    /**
     * Uploads [item] unless trunk already has it. [sha] is the phone file's hash if already known.
     * With an upload limit set, photos above it go up as smaller JPEGs that keep their EXIF data;
     * trunk still knows them by the original's hash.
     */
    fun send(ctx: Context, item: Item, sha: String = hash(ctx, item)): Sent {
        TrunkClient.check(listOf(sha))[sha]?.let { return Sent(false, it) }
        val src = ImageIO.originalUri(ctx, item.uri)

        val limit = TrunkAccount.uploadLimit
        val compressible = !item.video && item.mime in setOf("image/jpeg", "image/heic", "image/heif", "image/webp")
        if (limit > 0 && compressible && item.size > limit) {
            val small = shrink(ctx, item, limit)
            try {
                val name = item.name.substringBeforeLast('.') + ".jpg"
                return TrunkClient.upload(name, item.taken, sha256(small), "image/jpeg", small.length(), sourceSha256 = sha) { sink ->
                    FileInputStream(small).use { input -> input.copyTo(sink.outputStream()) }
                }
            } finally {
                small.delete()
            }
        }

        val length = ctx.contentResolver.openFileDescriptor(src, "r")?.use { it.statSize }?.takeIf { it > 0 } ?: item.size
        return if (item.video || length > 32L shl 20) {
            ctx.contentResolver.openFileDescriptor(src, "r")!!.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val channel = input.channel
                    TrunkClient.uploadResumable(
                        ctx, item.name, item.taken, sha, length,
                        read = { offset, buf ->
                            channel.position(offset)
                            var n = 0
                            while (n < buf.size) {
                                val r = channel.read(java.nio.ByteBuffer.wrap(buf, n, buf.size - n))
                                if (r <= 0) break
                                n += r
                            }
                            n
                        },
                        onProgress = {},
                    )
                }
            }
        } else {
            TrunkClient.upload(item.name, item.taken, sha, item.mime, length) { sink ->
                ctx.contentResolver.openInputStream(src)!!.use { it.copyTo(sink.outputStream()) }
            }
        }
    }

    /** Sends the phone's changed photo as the new version of trunk's asset [assetId]; trunk keeps the first upload. */
    fun sendEdit(ctx: Context, item: Item, sha: String, assetId: Long) {
        val src = ImageIO.originalUri(ctx, item.uri)
        val length = ctx.contentResolver.openFileDescriptor(src, "r")?.use { it.statSize }?.takeIf { it > 0 } ?: item.size
        TrunkClient.saveEdit(assetId, sha, item.mime, length) { sink ->
            ctx.contentResolver.openInputStream(src)!!.use { it.copyTo(sink.outputStream()) }
        }
    }

    /** A JPEG of the photo no bigger than [limit]: lower quality first, then fewer pixels. */
    private fun shrink(ctx: Context, item: Item, limit: Long): File {
        val exif = ImageIO.readExif(ctx, item.uri)
        var side = 4096
        var bitmap = decode(ctx, item.uri, side)
        val out = File.createTempFile("trunk_", ".jpg", ctx.cacheDir)
        var quality = 88
        repeat(14) {
            out.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            if (out.length() <= limit * 97 / 100) {
                ImageIO.writeExif(out, exif)
                return out
            }
            if (quality > 70) {
                quality -= 8
            } else {
                side = (max(bitmap.width, bitmap.height) * 0.8f).roundToInt()
                bitmap = decode(ctx, item.uri, side)
                quality = 84
            }
        }
        ImageIO.writeExif(out, exif)
        return out
    }

    private fun decode(ctx: Context, uri: Uri, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val long = max(info.size.width, info.size.height)
            if (long > maxSide) {
                val f = maxSide.toFloat() / long
                d.setTargetSize((info.size.width * f).roundToInt().coerceAtLeast(1), (info.size.height * f).roundToInt().coerceAtLeast(1))
            }
        }
}
