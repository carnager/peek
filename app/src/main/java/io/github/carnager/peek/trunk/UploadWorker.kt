package io.github.carnager.peek.trunk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.carnager.peek.R
import io.github.carnager.peek.editor.ImageIO
import io.github.carnager.peek.gallery.MediaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sends picked photos and videos to trunk in the background, one after another, with a progress
 * notification. Asks the server first and skips what it already has. With an upload limit set,
 * photos above it go up as smaller JPEGs that keep their EXIF data (date, camera, location).
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private data class Item(val uri: Uri, val name: String, val mime: String, val size: Long, val taken: Long, val video: Boolean)

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(0, 0, null)

    private fun foreground(done: Int, total: Int, name: String?): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_upload)
            .setContentTitle(if (total > 0) "Lade zu trunk hoch: ${done + 1} von $total" else "Lade zu trunk hoch")
            .setContentText(name)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTIFY_ID, n)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        TrunkAccount.load(applicationContext)
        if (!TrunkAccount.connected) return@withContext Result.failure()
        val listFile = File(inputData.getString(KEY_LIST) ?: return@withContext Result.failure())
        val items = runCatching {
            val arr = JSONArray(listFile.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Item(Uri.parse(o.getString("uri")), o.getString("name"), o.getString("mime"), o.getLong("size"), o.getLong("taken"), o.getBoolean("video"))
            }
        }.getOrElse { return@withContext Result.failure() }

        var uploaded = 0
        var skipped = 0
        val failed = ArrayList<String>()
        items.forEachIndexed { i, item ->
            setForeground(foreground(i, items.size, item.name))
            setProgress(workDataOf(KEY_DONE to i, KEY_TOTAL to items.size))
            try {
                if (send(item)) uploaded++ else skipped++
            } catch (e: Exception) {
                failed += "${item.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        listFile.delete()
        notifyDone(uploaded, skipped, failed)
        Result.success(workDataOf(KEY_UPLOADED to uploaded, KEY_SKIPPED to skipped, KEY_FAILED to failed.size))
    }

    /** Uploads one item; false when the server already had it. */
    private fun send(item: Item): Boolean {
        val ctx = applicationContext
        // Originals are read unredacted so the location stays in the file. Their hash is what the
        // server knows the photo by, even when a shrunk copy is what goes up.
        val src = ImageIO.originalUri(ctx, item.uri)
        val sha = ctx.contentResolver.openInputStream(src)!!.use { sha256(it) }
        if (TrunkClient.check(listOf(sha)).isNotEmpty()) return false

        val limit = TrunkAccount.uploadLimit
        val compressible = !item.video && item.mime in setOf("image/jpeg", "image/heic", "image/heif", "image/webp")
        if (limit > 0 && compressible && item.size > limit) {
            val small = shrink(item, limit)
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
                        item.name, item.taken, sha, length,
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

    /** A JPEG of the photo no bigger than [limit]: lower quality first, then fewer pixels. */
    private fun shrink(item: Item, limit: Long): File {
        val ctx = applicationContext
        val exif = ImageIO.readExif(ctx, item.uri)
        var side = 4096
        var bitmap = decode(item.uri, side)
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
                bitmap = decode(item.uri, side)
                quality = 84
            }
        }
        ImageIO.writeExif(out, exif)
        return out
    }

    private fun decode(uri: Uri, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(applicationContext.contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val long = max(info.size.width, info.size.height)
            if (long > maxSide) {
                val f = maxSide.toFloat() / long
                d.setTargetSize((info.size.width * f).roundToInt().coerceAtLeast(1), (info.size.height * f).roundToInt().coerceAtLeast(1))
            }
        }

    private fun notifyDone(uploaded: Int, skipped: Int, failed: List<String>) {
        val text = buildList {
            if (uploaded > 0) add("$uploaded hochgeladen")
            if (skipped > 0) add("$skipped waren schon da")
            if (failed.isNotEmpty()) add("${failed.size} fehlgeschlagen")
        }.joinToString(", ").ifEmpty { "Nichts zu tun" }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_upload)
            .setContentTitle(if (failed.isEmpty()) "Upload zu trunk fertig" else "Upload zu trunk mit Fehlern")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText((listOf(text) + failed.take(5)).joinToString("\n")))
            .setAutoCancel(true)
            .build()
        runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIFY_DONE_ID, n) }
    }

    companion object {
        const val WORK = "trunk-upload"
        const val CHANNEL = "trunk-upload"
        const val KEY_LIST = "list"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_UPLOADED = "uploaded"
        const val KEY_SKIPPED = "skipped"
        const val KEY_FAILED = "failed"
        private const val NOTIFY_ID = 4201
        private const val NOTIFY_DONE_ID = 4202

        fun createChannel(ctx: Context) {
            val ch = NotificationChannel(CHANNEL, "Upload zu trunk", NotificationManager.IMPORTANCE_LOW)
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        /** Queues [images] for upload. A new batch waits for one that is still running. */
        fun enqueue(ctx: Context, images: List<MediaImage>) {
            val arr = JSONArray()
            images.filter { it.remote == null }.forEach {
                arr.put(
                    JSONObject().put("uri", it.uri.toString()).put("name", it.name).put("mime", it.mimeType)
                        .put("size", it.size).put("taken", it.dateTaken).put("video", it.isVideo)
                )
            }
            if (arr.length() == 0) return
            val file = File(ctx.filesDir, "trunk/upload-${System.currentTimeMillis()}.json").apply { parentFile?.mkdirs(); writeText(arr.toString()) }
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_LIST to file.absolutePath))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
