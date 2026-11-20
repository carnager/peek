package io.github.carnager.peek.trunk

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.carnager.peek.MainActivity
import io.github.carnager.peek.R
import java.util.concurrent.TimeUnit

/**
 * Photos other people in the household shared with you that you have not looked at yet: sent to
 * you directly, or added by someone else to a shared album. Your own never count. "Seen" means the
 * album was opened. The first time, everything there is counts as seen, so nobody starts with a
 * flood. Kept per album and photo, since one photo can be new in one album and known in another.
 */
object SharedNews {
    private const val PREFS = "shared_news"
    private const val CHANNEL = "shared"
    const val EXTRA_ALBUM = "io.github.carnager.peek.OPEN_TRUNK_ALBUM"
    private const val WORK = "shared-news"

    private fun key(album: Long, asset: Long) = "$album:$asset"

    /** Others' photos per album, by id. */
    private fun others(albums: List<RemoteAlbum>, shared: Map<Long, List<RemoteAsset>>): Map<Long, Set<Long>> =
        albums.filter { it.shared }.associate { a ->
            a.id to (shared[a.id] ?: emptyList()).filter { it.owner != null && !it.trashed }.mapTo(HashSet()) { it.id }
        }

    /** Album id → photos in it not seen yet. */
    fun unseen(ctx: Context, albums: List<RemoteAlbum>, shared: Map<Long, List<RemoteAsset>>): Map<Long, Set<Long>> {
        val p = ctx.getSharedPreferences(PREFS, 0)
        val all = others(albums, shared)
        if (!p.contains("seen")) {
            // First run: what is there is known.
            p.edit().putStringSet("seen", all.flatMap { (a, ids) -> ids.map { key(a, it) } }.toSet()).apply()
            return emptyMap()
        }
        val seen = p.getStringSet("seen", emptySet())!!
        return all.mapValues { (a, ids) -> ids.filterTo(HashSet()) { key(a, it) !in seen } }.filterValues { it.isNotEmpty() }
    }

    /** The album was opened: its photos are seen. */
    fun markSeen(ctx: Context, album: Long, ids: Collection<Long>) {
        val p = ctx.getSharedPreferences(PREFS, 0)
        val seen = HashSet(p.getStringSet("seen", emptySet())!!)
        if (seen.addAll(ids.map { key(album, it) })) p.edit().putStringSet("seen", seen).apply()
        ctx.getSystemService(NotificationManager::class.java).cancel(album.toInt())
    }

    // --- notifications ---

    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREFS, 0).getBoolean("notify", true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("notify", on).apply()
        schedule(ctx)
    }

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.trunk_shared_channel), NotificationManager.IMPORTANCE_DEFAULT)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /** Looks for shared photos about once an hour while connected to trunk (no push without Google). */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!TrunkAccount.connected || !enabled(ctx)) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val req = PeriodicWorkRequestBuilder<SharedNewsWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Tells about photos not seen and not told about yet, one notification per album. */
    fun notifyNew(ctx: Context, albums: List<RemoteAlbum>, shared: Map<Long, List<RemoteAsset>>) {
        if (!enabled(ctx)) return
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val p = ctx.getSharedPreferences(PREFS, 0)
        val told = HashSet(p.getStringSet("told", emptySet())!!)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val byId = albums.associateBy { it.id }
        val owners = shared.values.flatten().associate { it.id to it.owner }
        for ((albumId, ids) in unseen(ctx, albums, shared)) {
            val fresh = ids.filter { key(albumId, it) !in told }
            if (fresh.isEmpty()) continue
            val album = byId[albumId] ?: continue
            val n = ids.size // all unseen, so the count matches the badge
            val who = fresh.mapNotNull { owners[it] }.distinct()
            val text = if (album.direct) {
                ctx.resources.getQuantityString(R.plurals.trunk_shared_sent, n, who.firstOrNull() ?: album.owner ?: "", n)
            } else {
                ctx.resources.getQuantityString(R.plurals.trunk_shared_added, n, n, album.title(ctx))
            }
            val open = PendingIntent.getActivity(
                ctx, albumId.toInt(),
                Intent(ctx, MainActivity::class.java).putExtra(EXTRA_ALBUM, albumId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            nm.notify(
                albumId.toInt(),
                NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(album.title(ctx))
                    .setContentText(text)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
            told.addAll(fresh.map { key(albumId, it) })
        }
        p.edit().putStringSet("told", told).apply()
    }
}

class SharedNewsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        TrunkAccount.load(applicationContext)
        if (!TrunkAccount.connected) return Result.success()
        return try {
            TrunkLibrary.sync(applicationContext)
            SharedNews.notifyNew(
                applicationContext,
                TrunkLibrary.cachedAlbums(applicationContext),
                TrunkLibrary.sharedAssets(applicationContext),
            )
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
