package io.github.carnager.peek.trunk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
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
import io.github.carnager.peek.gallery.MediaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Sends picked photos and videos to trunk in the background, one after another, with a progress
 * notification. What trunk already has is skipped; see [Uploader] for how each file goes up.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(0, 0, null)

    private fun foreground(done: Int, total: Int, name: String?): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_upload)
            .setContentTitle(if (total > 0) applicationContext.getString(R.string.trunk_uploading_progress, done + 1, total) else applicationContext.getString(R.string.trunk_uploading))
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
                Uploader.Item(Uri.parse(o.getString("uri")), o.getString("name"), o.getString("mime"), o.getLong("size"), o.getLong("taken"), o.getBoolean("video"))
            }
        }.getOrElse { return@withContext Result.failure() }

        var uploaded = 0
        var skipped = 0
        val failed = ArrayList<String>()
        items.forEachIndexed { i, item ->
            setForeground(foreground(i, items.size, item.name))
            setProgress(workDataOf(KEY_DONE to i, KEY_TOTAL to items.size))
            try {
                if (Uploader.send(applicationContext, item).created) uploaded++ else skipped++
            } catch (e: Exception) {
                failed += "${item.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        listFile.delete()
        notifyDone(uploaded, skipped, failed)
        Result.success(workDataOf(KEY_UPLOADED to uploaded, KEY_SKIPPED to skipped, KEY_FAILED to failed.size))
    }

    private fun notifyDone(uploaded: Int, skipped: Int, failed: List<String>) {
        val text = buildList {
            if (uploaded > 0) add(applicationContext.getString(R.string.trunk_uploaded_count, uploaded))
            if (skipped > 0) add(applicationContext.getString(R.string.trunk_skipped_count, skipped))
            if (failed.isNotEmpty()) add(applicationContext.getString(R.string.trunk_failed_count, failed.size))
        }.joinToString(", ").ifEmpty { applicationContext.getString(R.string.trunk_nothing_to_do) }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_upload)
            .setContentTitle(applicationContext.getString(if (failed.isEmpty()) R.string.trunk_upload_finished else R.string.trunk_upload_failed))
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
            val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.trunk_upload_channel), NotificationManager.IMPORTANCE_LOW)
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
