package dev.miqat.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.miqat.core.AlertKind
import dev.miqat.core.Prayer
import dev.miqat.core.Reminders
import dev.miqat.core.Schedule
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Notification scheduling without a foreground service or persistent notification:
 *  - The next [MAX_ALARMS] alerts are registered with AlarmManager as exact, allow-while-idle
 *    alarms, so they fire on the minute even in Doze and even if the app process is dead.
 *  - Every time one fires (and on boot, time/timezone change, app update, settings or location
 *    change) the whole plan is recomputed, so the chain can't drift or run dry.
 */
object AlarmScheduler {
    private const val MAX_ALARMS = 16

    suspend fun reschedule(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        for (i in 0 until MAX_ALARMS) {
            pendingIntent(app, i, null, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
        }
        pendingIntent(app, BOUNDARY, null, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }

        val state = Miqat.snapshot(app)
        val location = state.location ?: return
        val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

        // One timer at the next prayer boundary (incl. sunrise), so widgets and the "current
        // prayer" highlight flip on time even when no notification is due. 6 wakeups a day.
        Schedule.events(location, state.params, Instant.now(), days = 1, zone = ZoneId.systemDefault())
            .firstOrNull { it.time > Instant.now() }
            ?.let { boundary ->
                pendingIntent(app, BOUNDARY, Intent(app, AlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT)?.let { pi ->
                    val at = boundary.time.toEpochMilli() + 1_000
                    if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                }
            }

        val now = Instant.now()
        val events = Schedule.events(location, state.params, now, days = 2, zone = ZoneId.systemDefault())
        val alerts = Reminders.plan(events, state.settings, now, MAX_ALARMS)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

        alerts.forEachIndexed { i, alert ->
            val intent = Intent(app, AlarmReceiver::class.java)
                .putExtra(EXTRA_PRAYER, alert.prayer.name)
                .putExtra(EXTRA_KIND, alert.kind.name)
                .putExtra(EXTRA_PRAYER_TIME, alert.prayerTime.toEpochMilli())
                .putExtra(EXTRA_FIRE_AT, alert.fireAt.toEpochMilli())
            val pi = pendingIntent(app, i, intent, PendingIntent.FLAG_UPDATE_CURRENT) ?: return@forEachIndexed
            val at = alert.fireAt.toEpochMilli()
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                // Exact alarms revoked by the user: still fire, within a few minutes.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }

    fun canScheduleExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun pendingIntent(ctx: Context, index: Int, intent: Intent?, flag: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            ctx, REQUEST_BASE + index,
            intent ?: Intent(ctx, AlarmReceiver::class.java),
            flag or PendingIntent.FLAG_IMMUTABLE,
        )

    private const val REQUEST_BASE = 4200
    private const val BOUNDARY = 99
    internal const val EXTRA_PRAYER = "prayer"
    internal const val EXTRA_KIND = "kind"
    internal const val EXTRA_PRAYER_TIME = "prayer_time"
    internal const val EXTRA_FIRE_AT = "fire_at"
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = async {
        val prayer = intent.getStringExtra(AlarmScheduler.EXTRA_PRAYER)?.let { runCatching { Prayer.valueOf(it) }.getOrNull() }
        val kind = intent.getStringExtra(AlarmScheduler.EXTRA_KIND)?.let { runCatching { AlertKind.valueOf(it) }.getOrNull() }
        val prayerTime = Instant.ofEpochMilli(intent.getLongExtra(AlarmScheduler.EXTRA_PRAYER_TIME, 0))
        val fireAt = Instant.ofEpochMilli(intent.getLongExtra(AlarmScheduler.EXTRA_FIRE_AT, 0))
        if (prayer != null && kind != null) {
            // Skip alerts delivered very late (e.g. phone was off) so we never notify for a past prayer.
            val lateness = Duration.between(fireAt, Instant.now())
            val tooLate = when (kind) {
                AlertKind.BEFORE -> Instant.now() >= prayerTime
                AlertKind.AT_TIME -> lateness > Duration.ofMinutes(20)
            }
            // One notification per prayer, owned by the phone. Wear OS mirrors it to the watch
            // (same notification: dismiss on one, it's gone on both). The watch keeps its own
            // alarms only as a backup and posts locally just when the phone is out of reach.
            val phoneWillHandleIt = Hooks.isWatch && Sync.phoneReachable(context)
            if (!tooLate && !phoneWillHandleIt) Notifier.show(context, prayer, kind, prayerTime)
        }
        Miqat.onChanged(context)
    }
}

/** Boot, clock/timezone changes, app updates: re-plan everything. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = async {
        when (intent.action) {
            Intent.ACTION_TIMEZONE_CHANGED -> {
                // New timezone usually means a new city: grab a fresh fix (cheap, one-shot).
                val updated = LocationUpdater.refreshNow(context)
                if (!updated && !Hooks.isWatch && !LocationUpdater.hasBackground(context)) {
                    Notifier.showTravelHint(context)
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                LocationUpdater.register(context)
                Sync.pullAll(context)
            }
        }
        Miqat.onChanged(context)
    }
}
