package dev.miqat.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingTimelineComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import dev.miqat.core.Prayer
import dev.miqat.core.PrayerEvent
import dev.miqat.core.Schedule
import androidx.wear.protolayout.material3.dynamicColorScheme
import dev.miqat.wear.tile.BrandTileScheme
import dev.miqat.platform.Miqat
import dev.miqat.platform.TimeFormat
import dev.miqat.wear.MainActivity
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Circle-slot badge: "FJR" over "5:18", drawn by us so it's centered and sized the same on every
 * face (text complications leave layout to the face, and faces stack title/value inconsistently).
 * In the last hour the time becomes minutes left ("42m").
 *
 * The timeline covers the current window minute by minute and the next two windows as plain
 * times, so it stays right even if the refresh at a prayer (the boundary alarm) arrives late.
 */
class PrayerBadgeComplicationService : SuspendingTimelineComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SMALL_IMAGE) badge(Prayer.FAJR, "5:18", "Fajr at 5:18") else null

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline? {
        if (request.complicationType != ComplicationType.SMALL_IMAGE) return null
        val state = Miqat.snapshot(this)
        val location = state.location ?: return ComplicationDataTimeline(NoDataComplicationData(), emptyList())
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val windows = Schedule.events(location, state.params, now, days = 2, zone = zone)
            .filter { it.prayer.isPrayer }
            .zipWithNext()
            .filter { (_, next) -> next.time > now }

        val entries = mutableListOf<TimelineEntry>()
        windows.take(WINDOWS).forEachIndexed { w, (prev, next) ->
            if (w > 0) {
                // Later windows are a fallback in case the refresh at the prayer is late: the
                // prayer's clock time for the whole window (still correct, just no countdown).
                entries += TimelineEntry(TimeInterval(prev.time, next.time), clock(next))
                return@forEachIndexed
            }
            val countdownFrom = maxOf(prev.time, next.time.minus(COUNTDOWN_WINDOW))
            if (countdownFrom > now) entries += TimelineEntry(TimeInterval(prev.time, countdownFrom), clock(next))
            // One image per minute for the last hour. Minute k covers (k-1, k] minutes left and
            // shows "k m", rounding up like the app does, so the two always agree.
            val minutes = Duration.between(countdownFrom, next.time).toMinutes().toInt()
            for (k in minutes downTo 1) {
                val end = next.time.minus(Duration.ofMinutes(k - 1L))
                if (end <= now) continue
                val start = maxOf(next.time.minus(Duration.ofMinutes(k.toLong())), prev.time)
                entries += TimelineEntry(TimeInterval(start, end), countdown(next, k))
            }
        }
        val (_, next) = windows.first()
        val left = Duration.between(now, next.time)
        val default = if (left <= COUNTDOWN_WINDOW) countdown(next, ((left.seconds + 59) / 60).toInt()) else clock(next)
        return ComplicationDataTimeline(default, entries)
    }

    private fun clock(next: PrayerEvent) =
        badge(next.prayer, TimeFormat.compactTime(this, next.time), "${next.prayer.label} at ${TimeFormat.time(this, next.time)}")

    private fun countdown(next: PrayerEvent, minutes: Int) =
        badge(next.prayer, if (minutes >= 60) "1h" else "${minutes}m", "${next.prayer.label} in $minutes minutes")

    private fun badge(prayer: Prayer, value: String, description: String): ComplicationData {
        val image = SmallImage.Builder(icon(prayer.code, value, ambient = false), SmallImageType.PHOTO).build()
        return SmallImageComplicationData.Builder(image, PlainComplicationText.Builder(description).build())
            .setTapAction(tapIntent())
            .build()
    }

    /** The badge: the code on top, the value centered under it, in the watch's theme colors. */
    private fun icon(code: String, value: String, ambient: Boolean): Icon {
        val s = SIZE.toFloat()
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // No background of our own: the face draws its slot, we follow the watch's theme colors.
        val scheme = dynamicColorScheme(this, BrandTileScheme)
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val codePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = bold
            textAlign = Paint.Align.CENTER
            textSize = s * 0.2f
            letterSpacing = 0.06f
            color = scheme.primary.staticArgb
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = bold
            textAlign = Paint.Align.CENTER
            textSize = s * 0.3f
            color = scheme.onSurface.staticArgb
        }
        // Keep the value inside the circle's chord at that height.
        val maxW = s * 0.74f
        val w = valuePaint.measureText(value)
        if (w > maxW) valuePaint.textSize *= maxW / w

        // Center the two lines as one block using cap heights (digits and caps have no descenders).
        val codeH = codePaint.textSize * 0.72f
        val valueH = valuePaint.textSize * 0.72f
        val gap = s * 0.06f
        val top = (s - (codeH + gap + valueH)) / 2
        c.drawText(code, s / 2, top + codeH, codePaint)
        c.drawText(value, s / 2, top + codeH + gap + valueH, valuePaint)

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return Icon.createWithData(out.toByteArray(), 0, out.size())
    }

    private fun tapIntent(): PendingIntent = PendingIntent.getActivity(
        this, 2,
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_QIBLA),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val SIZE = 112
        private const val WINDOWS = 3
        private val COUNTDOWN_WINDOW: Duration = Duration.ofHours(1)
    }
}
