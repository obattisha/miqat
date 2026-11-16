package dev.miqat.mobile.widget

import android.content.Context
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.miqat.core.AppState
import dev.miqat.core.Hijri
import dev.miqat.core.PrayerEvent
import dev.miqat.core.Schedule
import dev.miqat.mobile.MainActivity
import dev.miqat.mobile.R
import dev.miqat.platform.Brand
import dev.miqat.platform.Miqat
import dev.miqat.platform.TimeFormat
import dev.miqat.platform.prayerIcon
import java.time.Instant
import java.time.ZoneId

/**
 * Home-screen widget in the brand palette. The layout is picked from the widget's exact size so
 * every size shows all six times (Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha) without clipping;
 * only the 1-row narrow size falls back to just the next prayer.
 *
 * Content only changes at prayer boundaries, so it's redrawn then (and on settings/location
 * changes). The countdown is a system Chronometer the launcher ticks itself.
 */
class MiqatWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = Miqat.snapshot(context)
        provideContent { WidgetContent(context, state) }
    }

    companion object {
        suspend fun refresh(ctx: Context) = MiqatWidget().updateAll(ctx)
    }
}

class MiqatWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MiqatWidget()
}

private val White = ColorProvider(Color.White)
private val WhiteDim = ColorProvider(Color(0xBFFFFFFF))
private val Gold = ColorProvider(Color(Brand.GOLD_300))

@Composable
private fun WidgetContent(ctx: Context, state: AppState) {
    val size = LocalSize.current
    val location = state.location

    Box(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(24.dp)
            .background(ImageProvider(R.drawable.widget_bg))
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        if (location == null) {
            Box(GlanceModifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("Open Miqat to set your location", style = TextStyle(color = White, fontSize = 13.sp))
            }
            return@Box
        }

        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val (current, next) = Schedule.window(location, state.params, now, zone)
        val events = Schedule.today(location, state.params, now, zone).events
        val hijri = Hijri.formatShort(now.atZone(zone).toLocalDate())
        val isCurrent = { e: PrayerEvent -> e.prayer == current.prayer && e.time == current.time }

        when {
            // One row tall: a strip of all six if it's wide enough, otherwise just the next prayer.
            size.height < 100.dp -> if (size.width >= 230.dp) {
                Strip(ctx, events, isCurrent, GlanceModifier.fillMaxSize().padding(horizontal = 10.dp))
            } else {
                Row(GlanceModifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrayerGlyph(next, 18.dp, Gold)
                    Spacer(GlanceModifier.width(8.dp))
                    Text(next.prayer.label, style = TextStyle(color = White, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                    Spacer(GlanceModifier.width(6.dp))
                    Text(TimeFormat.compactTime(ctx, next.time), style = TextStyle(color = WhiteDim, fontSize = 15.sp))
                }
            }

            // Wide and short: header + strip.
            size.width >= 230.dp && size.height < 200.dp -> Column(GlanceModifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
                Header(ctx, next, hijri, nameSize = 22.sp)
                Spacer(GlanceModifier.defaultWeight())
                Strip(ctx, events, isCurrent, GlanceModifier.fillMaxWidth())
            }

            // Tall: header + a list sized so all six rows always fit.
            else -> {
                val compact = size.width < 230.dp
                val header = if (compact) 58.dp else 82.dp
                val rowH = ((size.height - header - 28.dp) / 6).coerceIn(18.dp, 34.dp)
                val font = (rowH.value * 0.5f).coerceIn(11f, 16f).sp
                Column(GlanceModifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 14.dp)) {
                    if (compact) CompactHeader(ctx, next) else Header(ctx, next, hijri, nameSize = 24.sp)
                    Spacer(GlanceModifier.height(6.dp))
                    events.forEach { e ->
                        val cur = isCurrent(e)
                        ListRow(ctx, e, cur, muted = !cur && (e.time <= now || !e.prayer.isPrayer), height = rowH, font = font)
                    }
                }
            }
        }
    }
}

/** "NEXT · 30 JUMADA I", then the glyph, name and time, and a live countdown. */
@Composable
private fun Header(ctx: Context, next: PrayerEvent, hijri: String?, nameSize: TextUnit) {
    Column {
        Text(
            listOfNotNull("NEXT", hijri?.uppercase()).joinToString("  ·  "),
            style = TextStyle(color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(GlanceModifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrayerGlyph(next, 22.dp, White)
            Spacer(GlanceModifier.width(8.dp))
            Text(next.prayer.label, style = TextStyle(color = White, fontSize = nameSize, fontWeight = FontWeight.Medium))
            Spacer(GlanceModifier.width(8.dp))
            Text(TimeFormat.time(ctx, next.time), style = TextStyle(color = WhiteDim, fontSize = 16.sp))
        }
        AndroidRemoteViews(countdown(ctx, next))
    }
}

@Composable
private fun CompactHeader(ctx: Context, next: PrayerEvent) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrayerGlyph(next, 18.dp, Gold)
            Spacer(GlanceModifier.width(6.dp))
            Text(next.prayer.label, style = TextStyle(color = White, fontSize = 17.sp, fontWeight = FontWeight.Medium))
        }
        AndroidRemoteViews(countdown(ctx, next))
    }
}

/** Six columns: glyph over time. The current prayer sits in a gold-tinted pill. */
@Composable
private fun Strip(ctx: Context, events: List<PrayerEvent>, isCurrent: (PrayerEvent) -> Boolean, modifier: GlanceModifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        events.forEach { e ->
            val cur = isCurrent(e)
            Column(
                GlanceModifier.defaultWeight()
                    .then(if (cur) GlanceModifier.background(ImageProvider(R.drawable.widget_pill)) else GlanceModifier)
                    .padding(vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PrayerGlyph(e, 15.dp, if (cur) Gold else WhiteDim)
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    TimeFormat.compactTime(ctx, e.time),
                    style = TextStyle(color = if (cur) Gold else White, fontSize = 13.sp, fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ListRow(ctx: Context, e: PrayerEvent, current: Boolean, muted: Boolean, height: Dp, font: TextUnit) {
    val fg = when {
        current -> Gold
        muted -> WhiteDim
        else -> White
    }
    val weight = if (current) FontWeight.Bold else FontWeight.Normal
    Row(
        GlanceModifier.fillMaxWidth().height(height)
            .then(if (current) GlanceModifier.background(ImageProvider(R.drawable.widget_pill)) else GlanceModifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrayerGlyph(e, (font.value + 1).dp, fg)
        Spacer(GlanceModifier.width(8.dp))
        Text(e.prayer.label, style = TextStyle(color = fg, fontSize = font, fontWeight = weight), maxLines = 1)
        Spacer(GlanceModifier.defaultWeight())
        Text(TimeFormat.time(ctx, e.time), style = TextStyle(color = fg, fontSize = font, fontWeight = weight), maxLines = 1)
    }
}

@Composable
private fun PrayerGlyph(e: PrayerEvent, size: Dp, tint: ColorProvider) {
    Image(
        ImageProvider(prayerIcon(e.prayer)),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = GlanceModifier.size(size),
    )
}

private fun countdown(ctx: Context, next: PrayerEvent): RemoteViews {
    val untilMs = next.time.toEpochMilli() - System.currentTimeMillis()
    return RemoteViews(ctx.packageName, R.layout.widget_countdown).apply {
        setChronometer(R.id.countdown, SystemClock.elapsedRealtime() + untilMs, "in %s", true)
        setChronometerCountDown(R.id.countdown, true)
    }
}
