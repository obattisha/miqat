package dev.miqat.wear.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingTimelineComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import dev.miqat.core.Prayer
import dev.miqat.core.PrayerEvent
import dev.miqat.core.Schedule
import dev.miqat.platform.Miqat
import dev.miqat.platform.TimeFormat
import dev.miqat.platform.prayerIcon
import dev.miqat.wear.MainActivity
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Next prayer + countdown. Tapping opens the app on the compass.
 *
 * Design (per Wear OS complication guidance): the preferred type is RANGED_VALUE, so the watch
 * face draws its own native progress ring in its accent color; the title is the prayer and the
 * text is the countdown. No icon: the guidance is to show an icon or a title, not both.
 *
 * Battery: we hand the system a timeline covering the next ~2 days. The watch face swaps entries
 * at each prayer by itself, and both the countdown text and the ring are driven from target
 * times, so our code isn't woken to keep them current.
 */
open class NextPrayerComplicationService : SuspendingTimelineComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val now = Instant.now()
        val prev = PrayerEvent(Prayer.DHUHR, now.minus(Duration.ofHours(2)))
        val next = PrayerEvent(Prayer.ASR, now.plus(Duration.ofMinutes(83)))
        return build(type, prev, next, countdown = true)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline? {
        val type = request.complicationType
        val state = Miqat.snapshot(this)
        val location = state.location ?: return ComplicationDataTimeline(placeholder(type), emptyList())
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        // Each prayer window is split at one hour out: before that the text is the prayer's clock
        // time ("5:30"), after it a countdown ("42m"). The watch face switches entries itself.
        val entries = Schedule.events(location, state.params, now, days = 2, zone = zone)
            .filter { it.prayer.isPrayer }
            .zipWithNext()
            .filter { (_, next) -> next.time > now }
            .flatMap { (prev, next) ->
                val switchAt = maxOf(prev.time, next.time.minus(COUNTDOWN_WINDOW))
                listOfNotNull(
                    if (switchAt > prev.time) build(type, prev, next, countdown = false)?.let { TimelineEntry(TimeInterval(prev.time, switchAt), it) } else null,
                    build(type, prev, next, countdown = true)?.let { TimelineEntry(TimeInterval(switchAt, next.time), it) },
                )
            }
        // The default must be its own object: the library attaches the timeline to it, so reusing
        // an entry's object here would make the data contain itself (infinite recursion on parcel).
        val (prev, next) = Schedule.window(location, state.params, now, zone)
        val default = build(type, prev, next, countdown = Duration.between(now, next.time) <= COUNTDOWN_WINDOW) ?: placeholder(type)
        return ComplicationDataTimeline(default, entries)
    }

    private fun build(type: ComplicationType, prev: PrayerEvent, next: PrayerEvent, countdown: Boolean): ComplicationData? {
        val name = next.prayer.label
        // One line, "FJR 5:32" (or "FJR 42m" in the last hour), and no separate title. Faces lay
        // out title + text differently and some cram them together; a single line is only ever
        // shrunk to fit, so it reads cleanly on every face.
        val code = next.prayer.code
        val text = if (countdown) {
            TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountDownTimeReference(next.time))
                .setMinimumTimeUnit(TimeUnit.MINUTES)
                .setText("$code ^1")
                .build()
        } else {
            PlainComplicationText.Builder("$code ${TimeFormat.compactTime(this, next.time)}").build()
        }
        val description = PlainComplicationText.Builder("$name at ${TimeFormat.time(this, next.time)}").build()
        val tap = tapIntent()
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, prayerIcon(next.prayer))).build()

        return when (type) {
            ComplicationType.RANGED_VALUE -> {
                // The ring fills as the next prayer approaches.
                val total = Duration.between(prev.time, next.time).seconds.toFloat().coerceAtLeast(1f)
                val elapsedNow = Duration.between(prev.time, Instant.now()).seconds.toFloat().coerceIn(0f, total)
                val builder = if (Build.VERSION.SDK_INT >= 33) {
                    // Wear OS 4+: the watch face animates the value itself from a time expression.
                    val elapsed = DynamicInstant.withSecondsPrecision(prev.time)
                        .durationUntil(DynamicInstant.platformTimeWithSecondsPrecision())
                        .toIntSeconds()
                        .asFloat()
                    RangedValueComplicationData.Builder(elapsed, elapsedNow, 0f, total, description)
                } else {
                    RangedValueComplicationData.Builder(elapsedNow, 0f, total, description)
                }
                builder.setText(text).setTapAction(tap).build()
            }

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text, description)
                .setTapAction(tap)
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_DUAL_UNIT, CountDownTimeReference(next.time))
                    .setMinimumTimeUnit(TimeUnit.MINUTES)
                    .setText("in ^1")
                    .build(),
                description,
            )
                .setTitle(PlainComplicationText.Builder("$name · ${TimeFormat.time(this, next.time)}").build())
                .setMonochromaticImage(icon)
                .setTapAction(tap)
                .build()

            else -> null
        }
    }

    private fun placeholder(type: ComplicationType): ComplicationData {
        val text = PlainComplicationText.Builder("Open").build()
        val desc = PlainComplicationText.Builder("Open Miqat to set location").build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text, desc).setTapAction(tapIntent()).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(desc, desc).setTapAction(tapIntent()).build()
            else -> NoDataComplicationData()
        }
    }

    private fun tapIntent(): PendingIntent = PendingIntent.getActivity(
        this, 1,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_QIBLA),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private val COUNTDOWN_WINDOW: Duration = Duration.ofHours(1)

        fun requestUpdate(ctx: Context) {
            for (cls in listOf(NextPrayerComplicationService::class.java, NextPrayerTextComplicationService::class.java, PrayerBadgeComplicationService::class.java)) {
                ComplicationDataSourceUpdateRequester.create(ctx, ComponentName(ctx, cls)).requestUpdateAll()
            }
        }
    }
}

/** Same data, offered without the progress ring (text only), for faces where the ring looks heavy. */
class NextPrayerTextComplicationService : NextPrayerComplicationService() {
}
