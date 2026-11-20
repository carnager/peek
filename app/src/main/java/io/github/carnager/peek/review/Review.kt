package io.github.carnager.peek.review

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.carnager.peek.MainActivity
import io.github.carnager.peek.R
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The nightly review: in the evening peek asks to go through what arrived since the last review,
 * first which photos to keep, then which of those go to trunk. Nothing is deleted or uploaded
 * without it; photos that are not reviewed simply wait for the next time. The review can also be
 * started by hand at any time.
 *
 * "New" means: added to one of the chosen folders after the last finished review, which is kept
 * as a mark on MediaStore's DATE_ADDED. Closing a review half way leaves the mark where it was.
 */
object Review {
    private const val PREFS = "review"
    private const val WORK = "review-reminder"
    const val CHANNEL = "review"
    const val EXTRA_START = "io.github.carnager.peek.review"
    private const val NOTIFY_ID = 4301

    data class Settings(
        /** Whether the evening reminder is on. The review itself always works. */
        val remind: Boolean = false,
        val hour: Int = 21,
        val minute: Int = 0,
        /** Folders whose new photos are reviewed (MediaStore bucket ids). */
        val buckets: Set<Long> = emptySet(),
        /** Whether the user has set the review up at least once. */
        val configured: Boolean = false,
    )

    fun settings(ctx: Context): Settings {
        val p = ctx.getSharedPreferences(PREFS, 0)
        return Settings(
            remind = p.getBoolean("remind", false),
            hour = p.getInt("hour", 21),
            minute = p.getInt("minute", 0),
            buckets = p.getStringSet("buckets", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet(),
            configured = p.getBoolean("configured", false),
        )
    }

    fun save(ctx: Context, s: Settings) {
        val p = ctx.getSharedPreferences(PREFS, 0)
        val e = p.edit()
            .putBoolean("remind", s.remind)
            .putInt("hour", s.hour)
            .putInt("minute", s.minute)
            .putStringSet("buckets", s.buckets.map { it.toString() }.toSet())
            .putBoolean("configured", true)
        // The first time: start with today's photos, not the whole library.
        if (!p.contains("mark")) e.putLong("mark", LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond())
        e.apply()
        schedule(ctx, replace = true)
    }

    /** DATE_ADDED (seconds) up to which everything has been reviewed. */
    fun mark(ctx: Context): Long = ctx.getSharedPreferences(PREFS, 0).getLong("mark", Long.MAX_VALUE)

    /** Records a finished review of everything added up to [addedUpTo]. */
    fun finished(ctx: Context, addedUpTo: Long) {
        val p = ctx.getSharedPreferences(PREFS, 0)
        if (addedUpTo > p.getLong("mark", 0)) p.edit().putLong("mark", addedUpTo).apply()
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFY_ID)
    }

    /** How many photos and videos wait for review; asks MediaStore directly, for the reminder. */
    fun pendingCount(ctx: Context): Int {
        val s = settings(ctx)
        if (!s.configured || s.buckets.isEmpty()) return 0
        val where = "${MediaStore.MediaColumns.BUCKET_ID} IN (${s.buckets.joinToString(",")}) AND ${MediaStore.MediaColumns.DATE_ADDED} > ?"
        val args = arrayOf(mark(ctx).toString())
        return listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
        ).sumOf { uri ->
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), where, args, null)?.use { it.count } ?: 0
        }
    }

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.review_channel), NotificationManager.IMPORTANCE_DEFAULT)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /**
     * Sets the alarm for the next reminder, or clears it. An exact alarm, so it comes at the
     * minute chosen even when the phone sleeps; each one sets the next. [replace] is kept for the
     * callers: setting an alarm always replaces the previous one.
     */
    @Suppress("UNUSED_PARAMETER")
    fun schedule(ctx: Context, replace: Boolean = false) {
        // Earlier versions used a daily WorkManager job, which Doze could delay by hours.
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(ctx)
        val s = settings(ctx)
        if (!s.remind) {
            am.cancel(pi)
            return
        }
        val now = LocalDateTime.now()
        var next = LocalDateTime.of(now.toLocalDate(), LocalTime.of(s.hour, s.minute))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) // permission revoked: a little late is still fine
        }
    }

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0,
        Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_REMIND),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal const val ACTION_REMIND = "io.github.carnager.peek.review.REMIND"

    internal fun notify(ctx: Context, count: Int) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_START, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_upload)
            .setContentTitle(ctx.resources.getQuantityString(R.plurals.review_notify_title, count, count))
            .setContentText(ctx.getString(R.string.review_notify_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, n)
    }
}

/**
 * The alarm itself: reminds about new photos if there are any and sets tomorrow's alarm. Also
 * sets the alarm again after a reboot, a change of time or timezone, and an update of peek.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val done = goAsync()
        Thread {
            try {
                if (intent.action == Review.ACTION_REMIND) {
                    val n = Review.pendingCount(app)
                    if (n > 0) Review.notify(app, n)
                }
                Review.schedule(app)
            } finally {
                done.finish()
            }
        }.start()
    }
}

/** What the reminder was before it became an alarm; left so a job queued by an older version ends quietly. */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = Result.success()
}
