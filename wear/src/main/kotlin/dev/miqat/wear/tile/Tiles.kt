package dev.miqat.wear.tile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.weight
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicString
import androidx.wear.protolayout.material3.ColorScheme
import androidx.wear.protolayout.material3.dynamicColorScheme
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.icon
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.LayoutColor
import androidx.wear.protolayout.types.layoutString
import androidx.wear.protolayout.types.stringLayoutConstraint
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import dev.miqat.core.AppState
import dev.miqat.core.Hijri
import dev.miqat.core.Prayer
import dev.miqat.core.PrayerEvent
import dev.miqat.core.Qibla
import dev.miqat.core.Schedule
import dev.miqat.platform.Brand
import dev.miqat.platform.Miqat
import dev.miqat.platform.TimeFormat
import dev.miqat.platform.prayerIcon
import dev.miqat.wear.MainActivity
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

private fun c(argb: Long) = LayoutColor(argb.toInt())

/**
 * Fallback only: tiles follow the watch's system theme (allowDynamicTheme = true); these brand
 * colors are used when the watch has no dynamic theme.
 */
internal val BrandTileScheme = ColorScheme(
    primary = c(Brand.EMERALD_300),
    primaryDim = c(Brand.EMERALD_400),
    primaryContainer = c(Brand.EMERALD_800),
    onPrimary = c(Brand.EMERALD_950),
    onPrimaryContainer = c(Brand.EMERALD_100),
    secondary = c(Brand.EMERALD_200),
    secondaryContainer = c(Brand.NIGHT_3),
    onSecondaryContainer = c(Brand.EMERALD_100),
    tertiary = c(Brand.GOLD_300),
    tertiaryContainer = c(0xFF574400),
    onTertiaryContainer = c(Brand.GOLD_100),
    surfaceContainerLow = c(Brand.NIGHT_1),
    surfaceContainer = c(Brand.NIGHT_2),
    surfaceContainerHigh = c(Brand.NIGHT_3),
    onSurface = c(Brand.MIST),
    onSurfaceVariant = c(Brand.MIST_DIM),
    outline = c(0xFF55655D),
    outlineVariant = c(0xFF2E3B35),
    background = c(0xFF000000),
    onBackground = c(Brand.MIST),
)

/** Launches the watch app, optionally straight onto a screen (e.g. the compass). */
internal fun openApp(ctx: Context, screen: String? = null): ActionBuilders.LaunchAction =
    ActionBuilders.LaunchAction.Builder()
        .setAndroidActivity(
            ActionBuilders.AndroidActivity.Builder()
                .setPackageName(ctx.packageName)
                .setClassName(MainActivity::class.java.name)
                .apply {
                    if (screen != null) {
                        addKeyToExtraMapping(MainActivity.EXTRA_SCREEN, ActionBuilders.AndroidStringExtra.Builder().setValue(screen).build())
                    }
                }
                .build(),
        )
        .build()

/** Coroutine-friendly base: tiles are built in a suspend function. */
abstract class SuspendingTile : TileService() {
    protected abstract suspend fun tile(request: RequestBuilders.TileRequest): TileBuilders.Tile
    protected abstract suspend fun resources(request: RequestBuilders.ResourcesRequest): ResourceBuilders.Resources

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest) =
        SuspendToFutureAdapter.launchFuture { tile(requestParams) }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) =
        SuspendToFutureAdapter.launchFuture { resources(requestParams) }

    companion object {
        fun requestUpdates(ctx: Context) {
            val updater = getUpdater(ctx)
            updater.requestUpdate(PrayerTimesTileService::class.java)
            updater.requestUpdate(QiblaTileService::class.java)
        }
    }
}

// ---------------------------------------------------------------- Prayer times tile

/**
 * Next prayer with a live countdown (evaluated by the system, no wakeups) and all six of today's
 * times. A timeline entry per prayer window lets the tile flip at each prayer on its own.
 */
class PrayerTimesTileService : SuspendingTile() {

    override suspend fun tile(request: RequestBuilders.TileRequest): TileBuilders.Tile {
        val state = Miqat.snapshot(this)
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val timeline = TimelineBuilders.Timeline.Builder()
        val location = state.location
        if (location == null) {
            timeline.addTimelineEntry(entry(layout(request) { noLocation() }))
        } else {
            val windows = Schedule.events(location, state.params, now, days = 1, zone = zone)
                .filter { it.prayer.isPrayer }
                .zipWithNext()
                .filter { (_, n) -> n.time > now }
                .take(6)
            windows.forEach { (prev, next) ->
                val built = layout(request) { prayerTimes(state, prev, next) }
                timeline.addTimelineEntry(
                    TimelineBuilders.TimelineEntry.Builder()
                        .setValidity(
                            TimelineBuilders.TimeInterval.Builder()
                                .setStartMillis(prev.time.toEpochMilli())
                                .setEndMillis(next.time.toEpochMilli())
                                .build(),
                        )
                        .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(built).build())
                        .build(),
                )
            }
        }
        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RES_VERSION)
            .setTileTimeline(timeline.build())
            .build()
    }

    private fun layout(request: RequestBuilders.TileRequest, body: MaterialScope.() -> LayoutElementBuilders.LayoutElement) =
        materialScope(this, request.deviceConfiguration, allowDynamicTheme = true, defaultColorScheme = BrandTileScheme) { body() }

    private fun entry(root: LayoutElementBuilders.LayoutElement) = TimelineBuilders.TimelineEntry.Builder()
        .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(root).build())
        .build()

    private fun MaterialScope.noLocation() = primaryLayout(
        titleSlot = { text("Prayer times".layoutString) },
        mainSlot = { text("Open Miqat to set your location".layoutString, maxLines = 3) },
    )

    private fun MaterialScope.prayerTimes(state: AppState, prev: PrayerEvent, next: PrayerEvent): LayoutElementBuilders.LayoutElement {
        val ctx = this@PrayerTimesTileService
        val location = state.location!!
        val date = prev.time.atZone(ZoneId.systemDefault()).toLocalDate()
        // The strip shows the day of the current prayer, so it's always the one highlighted.
        val today = Schedule.today(location, state.params, prev.time, ZoneId.systemDefault())
        return primaryLayout(
            titleSlot = {
                text((Hijri.formatShort(date) ?: "Prayer times").layoutString, typography = Typography.LABEL_SMALL, color = colorScheme.tertiary)
            },
            mainSlot = {
                Column.Builder()
                    .setWidth(expand())
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(
                        Row.Builder()
                            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                            .addContent(icon(iconId(next.prayer), width = dp(20f), height = dp(20f), tintColor = colorScheme.tertiary))
                            .addContent(Spacer.Builder().setWidth(dp(6f)).build())
                            .addContent(text(next.prayer.label.layoutString, typography = Typography.TITLE_MEDIUM, color = colorScheme.tertiary))
                            .build(),
                    )
                    .addContent(text(countdown(next), typography = Typography.NUMERAL_SMALL, color = colorScheme.onSurface))
                    .addContent(Spacer.Builder().setHeight(dp(6f)).build())
                    .addContent(strip(ctx, today.events, prev))
                    .build()
            },
            bottomSlot = {
                textEdgeButton(onClick = clickable(openApp(ctx), "open")) { text("Open".layoutString) }
            },
        )
    }

    /** Two rows of three: prayer name over time; the current prayer is highlighted in gold. */
    private fun MaterialScope.strip(ctx: Context, events: List<PrayerEvent>, current: PrayerEvent): LayoutElementBuilders.LayoutElement {
        val grid = Column.Builder().setWidth(expand()).setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        events.chunked(3).forEachIndexed { i, rowEvents ->
            if (i > 0) grid.addContent(Spacer.Builder().setHeight(dp(3f)).build())
            val row = Row.Builder().setWidth(expand())
            rowEvents.forEach { e ->
                val isCurrent = e.prayer == current.prayer && e.time == current.time
                row.addContent(
                    Column.Builder()
                        .setWidth(weight(1f))
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                        .addContent(
                            text(
                                e.prayer.label.layoutString,
                                typography = Typography.LABEL_SMALL,
                                color = if (isCurrent) colorScheme.tertiary else colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            ),
                        )
                        .addContent(
                            text(
                                TimeFormat.compactTime(ctx, e.time).layoutString,
                                typography = Typography.BODY_EXTRA_SMALL,
                                color = if (isCurrent) colorScheme.tertiary else colorScheme.onSurface,
                                maxLines = 1,
                            ),
                        )
                        .build(),
                )
            }
            grid.addContent(row.build())
        }
        return grid.build()
    }

    /** "1h 42m" / "42m", computed by the system from the platform clock. */
    private fun countdown(next: PrayerEvent): androidx.wear.protolayout.types.LayoutString {
        val left = DynamicInstant.platformTimeWithSecondsPrecision().durationUntil(DynamicInstant.withSecondsPrecision(next.time))
        val h = left.hoursPart.plus(left.toIntDays().times(24))
        val m = left.minutesPart
        val withHours = h.format().concat(DynamicString.constant("h ")).concat(m.format()).concat(DynamicString.constant("m"))
        val minutesOnly = m.format().concat(DynamicString.constant("m"))
        val dynamic = DynamicString.onCondition(h.gt(0)).use(withHours).elseUse(minutesOnly)
        val fallback = dev.miqat.core.formatCountdown(java.time.Duration.between(Instant.now(), next.time))
        return androidx.wear.protolayout.types.LayoutString(fallback, dynamic, stringLayoutConstraint("00h 00m"))
    }

    override suspend fun resources(request: RequestBuilders.ResourcesRequest): ResourceBuilders.Resources =
        ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).apply {
            Prayer.entries.forEach { p ->
                addIdToImageMapping(iconId(p), ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(prayerIcon(p)).build())
                    .build())
            }
        }.build()

    private fun iconId(p: Prayer) = "prayer_${p.name.lowercase()}"

    companion object {
        private const val RES_VERSION = "1"
    }
}

// ---------------------------------------------------------------- Qibla tile

/**
 * The tile is the compass: a full-screen, north-up dial with the qibla marked on it and the bearing
 * in the middle. Tiles can't read sensors, so it's static; tapping anywhere opens the live compass.
 * Drawn in the watch's theme colors.
 */
class QiblaTileService : SuspendingTile() {

    override suspend fun tile(request: RequestBuilders.TileRequest): TileBuilders.Tile {
        val state = Miqat.snapshot(this)
        val bearing = state.qiblaBearing
        val device = request.deviceConfiguration
        val px = roseSizePx(device.screenWidthDp, device.screenDensity)
        val scheme = dynamicColorScheme(this, BrandTileScheme)
        val ctx = this
        val root = materialScope(this, device, allowDynamicTheme = true, defaultColorScheme = BrandTileScheme) {
            val tap = ModifiersBuilders.Modifiers.Builder()
                .setClickable(clickable(openApp(ctx, MainActivity.SCREEN_QIBLA), "compass"))
                .build()
            val box = LayoutElementBuilders.Box.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .setModifiers(tap)
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            if (bearing == null) {
                box.addContent(text("Open Miqat to set your location".layoutString, maxLines = 3))
            } else {
                box.addContent(
                    LayoutElementBuilders.Image.Builder()
                        .setResourceId(roseId(bearing, px, scheme))
                        .setWidth(expand())
                        .setHeight(expand())
                        .build(),
                )
                box.addContent(
                    Column.Builder()
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                        .addContent(text("${bearing.roundToInt()}°".layoutString, typography = Typography.NUMERAL_MEDIUM, color = colorScheme.onSurface))
                        .addContent(text("Qibla".layoutString, typography = Typography.LABEL_SMALL, color = colorScheme.primary))
                        .build(),
                )
            }
            box.build()
        }
        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(TimelineBuilders.TimelineEntry.Builder().setLayout(LayoutElementBuilders.Layout.Builder().setRoot(root).build()).build())
            .build()
        // Place, size and theme are part of the resource version, so any change ships a new dial.
        return TileBuilders.Tile.Builder()
            .setResourcesVersion(bearing?.let { roseId(it, px, scheme) } ?: "none")
            .setTileTimeline(timeline)
            .build()
    }

    override suspend fun resources(request: RequestBuilders.ResourcesRequest): ResourceBuilders.Resources {
        val bearing = Miqat.snapshot(this).qiblaBearing
        val device = request.deviceConfiguration
        val px = roseSizePx(device.screenWidthDp, device.screenDensity)
        val scheme = dynamicColorScheme(this, BrandTileScheme)
        val builder = ResourceBuilders.Resources.Builder().setVersion(request.version)
        if (bearing != null) {
            builder.addIdToImageMapping(
                roseId(bearing, px, scheme),
                ResourceBuilders.ImageResource.Builder()
                    .setInlineResource(
                        ResourceBuilders.InlineImageResource.Builder()
                            .setData(roseBitmap(bearing, px, scheme))
                            .setWidthPx(px)
                            .setHeightPx(px)
                            .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                            .build(),
                    )
                    .build(),
            )
        }
        return builder.build()
    }

    private fun roseSizePx(widthDp: Int, density: Float) = (widthDp * density).roundToInt().coerceIn(200, 600)

    private fun roseId(bearing: Double, px: Int, scheme: ColorScheme) =
        "rose_${bearing.roundToInt()}_${px}_${Integer.toHexString(scheme.primary.staticArgb)}_${Integer.toHexString(scheme.tertiary.staticArgb)}"

    /** Full-screen dial: hairline ring, 15° ticks, letters, and the qibla line ending at the Kaaba. */
    private fun roseBitmap(bearing: Double, px: Int, scheme: ColorScheme): ByteArray {
        val s = px.toFloat()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = s / 2
        val r = s / 2 * 0.94f
        val unit = s / 200f
        val ring = scheme.outlineVariant.staticArgb
        val tick = scheme.onSurfaceVariant.staticArgb
        val north = scheme.tertiary.staticArgb
        val accent = scheme.primary.staticArgb
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

        p.color = ring; p.strokeWidth = 1.2f * unit
        c.drawCircle(cx, cx, r, p)
        for (deg in 0 until 360 step 15) {
            val major = deg % 90 == 0
            c.save(); c.rotate(deg.toFloat(), cx, cx)
            p.color = if (deg == 0) north else tick
            p.alpha = if (major) 255 else 140
            p.strokeWidth = (if (major) 2f else 1f) * unit
            c.drawLine(cx, cx - r + 2 * unit, cx, cx - r + (if (major) 10 else 6) * unit, p)
            c.restore()
        }
        p.alpha = 255
        val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * unit; textAlign = Paint.Align.CENTER }
        listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f).forEach { (l, deg) ->
            // Slide N aside when the qibla is near north, so the Kaaba doesn't cover it.
            val b = ((bearing % 360) + 360) % 360
            val shift = if (l == "N" && (b < 25 || b > 335)) (if (b < 25) -18f else 18f) else 0f
            val a = Math.toRadians((deg + shift).toDouble())
            val rr = r - 21 * unit
            letter.color = if (l == "N") north else tick
            letter.typeface = if (l == "N") android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            val x = cx + (rr * kotlin.math.sin(a)).toFloat()
            val y = cx - (rr * kotlin.math.cos(a)).toFloat() + letter.textSize * 0.36f
            c.drawText(l, x, y, letter)
        }
        // Qibla: line from just outside the centre text to the Kaaba on the rim.
        c.save(); c.rotate(bearing.toFloat(), cx, cx)
        p.color = accent; p.strokeWidth = 2.5f * unit
        c.drawLine(cx, cx - r * 0.42f, cx, cx - r * 0.78f, p)
        val k = 14 * unit
        val top = cx - r * 0.78f - k
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        c.drawRoundRect(cx - k / 2, top, cx + k / 2, top + k, k * 0.18f, k * 0.18f, fill)
        fill.color = north
        c.drawRect(cx - k / 2, top + k * 0.26f, cx + k / 2, top + k * 0.38f, fill)
        c.restore()
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
