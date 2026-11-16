package dev.miqat.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.miqat.core.AlertKind
import dev.miqat.core.Prayer
import java.time.Duration
import java.time.Instant

object Notifier {
    private const val CH_PRAYER = "prayer"
    private const val CH_REMINDER = "reminder"
    private const val CH_STATUS = "status"
    private const val ID_TRAVEL = 900

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_PRAYER, ctx.getString(R.string.channel_prayer), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_REMINDER, ctx.getString(R.string.channel_reminder), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_STATUS, ctx.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    fun canPost(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun show(ctx: Context, prayer: Prayer, kind: AlertKind, prayerTime: Instant) {
        if (!canPost(ctx)) return
        val time = TimeFormat.time(ctx, prayerTime)
        val nm = NotificationManagerCompat.from(ctx)
        val builder = when (kind) {
            AlertKind.BEFORE -> {
                val mins = Duration.between(Instant.now(), prayerTime).plusSeconds(59).toMinutes()
                NotificationCompat.Builder(ctx, CH_REMINDER)
                    .setContentTitle("${prayer.label} in $mins min")
                    .setContentText("${prayer.label} is at $time")
                    // Disappears by itself once the prayer time arrives.
                    .setTimeoutAfter(Duration.between(Instant.now(), prayerTime).toMillis().coerceAtLeast(1000))
            }
            AlertKind.AT_TIME -> {
                nm.cancel(id(prayer, AlertKind.BEFORE))
                NotificationCompat.Builder(ctx, CH_PRAYER)
                    .setContentTitle("${prayer.label} · $time")
                    .setContentText("It's time for ${prayer.label}")
                    .setTimeoutAfter(Duration.ofMinutes(90).toMillis())
            }
        }
        builder.setSmallIcon(R.drawable.ic_kaaba)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setWhen(prayerTime.toEpochMilli())
            .setShowWhen(true)
            .setContentIntent(launchIntent(ctx))
        try {
            nm.notify(id(prayer, kind), builder.build())
        } catch (_: SecurityException) {
        }
    }

    /** Shown after a timezone change when we can't fetch location in the background. */
    fun showTravelHint(ctx: Context) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_kaaba)
            .setContentTitle("New timezone")
            .setContentText("Open Miqat to update prayer times for where you are.")
            .setAutoCancel(true)
            .setContentIntent(launchIntent(ctx))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(ID_TRAVEL, n)
        } catch (_: SecurityException) {
        }
    }

    private fun id(prayer: Prayer, kind: AlertKind) = 100 + prayer.ordinal * 2 + kind.ordinal

    private fun launchIntent(ctx: Context): PendingIntent? {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        return PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

object TimeFormat {
    /** "5:30" (12h, no AM/PM) or "17:30": short enough for a small watch-face slot. */
    fun compactTime(ctx: Context, instant: Instant): String {
        val pattern = if (android.text.format.DateFormat.is24HourFormat(ctx)) "H:mm" else "h:mm"
        return java.time.format.DateTimeFormatter.ofPattern(pattern).format(instant.atZone(java.time.ZoneId.systemDefault()))
    }

    fun time(ctx: Context, instant: Instant): String {
        val pattern = if (android.text.format.DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a"
        return java.time.format.DateTimeFormatter.ofPattern(pattern)
            .format(instant.atZone(java.time.ZoneId.systemDefault()))
    }
}

/** Small monochrome glyph per prayer (dawn, sun, afternoon sun, sunset, moon). */
fun prayerIcon(p: dev.miqat.core.Prayer): Int = when (p) {
    dev.miqat.core.Prayer.FAJR -> R.drawable.ic_prayer_fajr
    dev.miqat.core.Prayer.SUNRISE -> R.drawable.ic_prayer_fajr
    dev.miqat.core.Prayer.DHUHR -> R.drawable.ic_prayer_dhuhr
    dev.miqat.core.Prayer.ASR -> R.drawable.ic_prayer_asr
    dev.miqat.core.Prayer.MAGHRIB -> R.drawable.ic_prayer_maghrib
    dev.miqat.core.Prayer.ISHA -> R.drawable.ic_prayer_isha
}
