package dev.miqat.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import dev.miqat.core.AppState
import dev.miqat.core.CalcMethod
import dev.miqat.core.Hijri
import dev.miqat.core.CompassAccuracy
import dev.miqat.core.Madhab
import dev.miqat.core.Prayer
import dev.miqat.core.Qibla
import dev.miqat.core.Schedule
import dev.miqat.core.Settings
import dev.miqat.core.formatCountdown
import dev.miqat.platform.AlignmentHaptics
import dev.miqat.platform.DialColors
import dev.miqat.platform.Miqat
import dev.miqat.platform.QiblaDial
import dev.miqat.platform.TimeFormat
import dev.miqat.platform.compassFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Adds side margins (~10% of the screen width) on top of the scaffold's padding, so full-width rows
 * sit inside the round display instead of being cropped by its edge.
 */
@Composable
private fun roundPadding(base: PaddingValues): PaddingValues {
    val side = (LocalConfiguration.current.screenWidthDp * 0.10f).dp
    return PaddingValues(
        start = side,
        end = side,
        top = base.calculateTopPadding(),
        bottom = base.calculateBottomPadding(),
    )
}

/** Current time, redrawn at each minute boundary (countdowns show minutes). */
@Composable
private fun rememberNow(): Instant {
    var millis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            millis = System.currentTimeMillis()
        }
    }
    return Instant.ofEpochMilli(millis)
}

// ---------------------------------------------------------------- Home

/**
 * One glance: the next prayer and how long until it, inside a progress ring that matches the
 * watch-face complication. Today's times follow as a quiet list; Qibla is the edge button.
 */
@Composable
fun HomeScreen(state: AppState, onQibla: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val now = rememberNow()
    val listState = rememberScalingLazyListState()
    val location = state.location

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = onQibla, buttonSize = EdgeButtonSize.Medium) { Text("Qibla") }
        },
    ) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = roundPadding(padding), modifier = Modifier.fillMaxSize()) {
            if (location == null) {
                item {
                    Text(
                        "Waiting for your location from the phone",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                return@ScalingLazyColumn
            }
            val zone = ZoneId.systemDefault()
            val (current, next) = Schedule.window(location, state.params, now, zone)
            val today = Schedule.today(location, state.params, now, zone)
            val total = Duration.between(current.time, next.time).seconds.toFloat().coerceAtLeast(1f)
            val progress = (Duration.between(current.time, now).seconds / total).coerceIn(0f, 1f)

            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 3.dp,
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                next.prayer.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            val left = Duration.between(now, next.time)
                            Text(
                                formatCountdown(left),
                                style = if (left.toHours() >= 1) MaterialTheme.typography.numeralExtraSmall else MaterialTheme.typography.numeralMedium,
                                maxLines = 1,
                            )
                            Text(
                                TimeFormat.time(ctx, next.time),
                                style = MaterialTheme.typography.bodyExtraSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        location.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Hijri.format(now.atZone(zone).toLocalDate())?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiaryDim)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            today.events.forEach { e ->
                item {
                    val isCurrent = e.prayer == current.prayer && e.time == current.time
                    TimeRow(
                        label = e.prayer.label,
                        time = TimeFormat.time(ctx, e.time),
                        highlighted = isCurrent,
                        muted = !isCurrent && (e.time <= now || !e.prayer.isPrayer),
                        modifier = Modifier,
                    )
                }
            }
            item {
                Button(
                    onClick = onSettings,
                    label = { Text("Settings") },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

/** A prayer row: name left, time right. The current prayer sits in a tinted pill. */
@Composable
private fun TimeRow(label: String, time: String, highlighted: Boolean, muted: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val fg = when {
        highlighted -> colors.onPrimaryContainer
        muted -> colors.onSurfaceVariant
        else -> colors.onSurface
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(if (highlighted) colors.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = fg)
        Text(time, style = MaterialTheme.typography.titleSmall, color = fg)
    }
}

// ---------------------------------------------------------------- Qibla

/**
 * Full-screen live compass in the same design as the Qibla tile: the dial fills the round display
 * (no clock on this screen) and the readout sits in the middle.
 */
@Composable
fun QiblaScreen(state: AppState) {
    val ctx = LocalContext.current
    val location = state.location ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No location yet") }
        return
    }
    val qibla = Qibla.bearing(location.coords)
    val reading by remember(location.latitude, location.longitude) {
        compassFlow(ctx, location.latitude, location.longitude)
    }.collectAsStateWithLifecycle(initialValue = null)
    val haptics = remember { AlignmentHaptics(ctx) }
    val r = reading
    val offset = r?.let { Qibla.delta(qibla, it.heading.toDouble()) } ?: 180.0
    val aligned = r != null && haptics.update(offset)
    val colors = MaterialTheme.colorScheme

    ScreenScaffold(timeText = {}) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (r != null) {
                QiblaDial(
                    heading = r.heading,
                    qibla = qibla,
                    aligned = aligned,
                    colors = DialColors(
                        ring = colors.outlineVariant,
                        ticks = colors.onSurfaceVariant,
                        north = colors.tertiary,
                        pointer = colors.primary,
                        kaaba = colors.primary,
                        aligned = colors.primary,
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    r == null -> Text("No compass", style = MaterialTheme.typography.bodyMedium)
                    aligned -> {
                        Text("${qibla.roundToInt()}°", style = MaterialTheme.typography.numeralMedium, color = colors.onSurface)
                        Text("Facing the qibla", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    }
                    else -> {
                        Text("${abs(offset).roundToInt()}°", style = MaterialTheme.typography.numeralMedium, color = colors.onSurface)
                        Text(if (offset > 0) "turn right" else "turn left", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    }
                }
                if (r != null) {
                    Spacer(Modifier.height(2.dp))
                    AccuracyLabel(r)
                }
            }
        }
    }
}

/** "±6° · Good". Poor accuracy suggests a figure 8 to recalibrate. */
@Composable
private fun AccuracyLabel(r: dev.miqat.platform.CompassReading) {
    val colors = MaterialTheme.colorScheme
    val q = r.quality
    Text(
        if (q == CompassAccuracy.Quality.POOR) "±${r.accuracyDeg.roundToInt()}° · figure 8 to fix" else "±${r.accuracyDeg.roundToInt()}° · ${q.label}",
        style = MaterialTheme.typography.labelSmall,
        color = when (q) {
            CompassAccuracy.Quality.GOOD -> colors.primary
            CompassAccuracy.Quality.FAIR -> colors.onSurfaceVariant
            CompassAccuracy.Quality.POOR -> colors.error
        },
    )
}

// ---------------------------------------------------------------- Settings

@Composable
fun SettingsScreen(state: AppState, onMethod: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun update(f: (Settings) -> Settings) = scope.launch { Miqat.updateSettings(ctx, f) }
    val s = state.settings
    val listState = rememberScalingLazyListState()

    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = roundPadding(padding), modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Settings") } }
            item {
                SettingButton(
                    "Method",
                    if (s.methodOverride == null) "${state.method.label} · auto" else state.method.label,
                    onClick = onMethod,
                    modifier = Modifier,
                )
            }
            item {
                val next = when (s.madhabOverride) {
                    null -> Madhab.STANDARD
                    Madhab.STANDARD -> Madhab.HANAFI
                    Madhab.HANAFI -> null
                }
                SettingButton(
                    "Asr",
                    s.madhabOverride?.let { madhabName(it) } ?: "${madhabName(state.autoMadhab)} · auto",
                    onClick = { update { it.copy(madhabOverride = next) } },
                    modifier = Modifier,
                )
            }
            item {
                val choices = Settings.REMINDER_CHOICES
                val nextMinutes = choices[(choices.indexOf(s.reminderMinutes) + 1) % choices.size]
                SettingButton(
                    "Reminder",
                    "${s.reminderMinutes} min before",
                    onClick = { update { it.copy(reminderMinutes = nextMinutes) } },
                    modifier = Modifier,
                )
            }
            item { ListHeader { Text("At prayer time") } }
            Prayer.prayers.forEach { p ->
                item {
                    SwitchButton(
                        checked = p in s.notifyAtTime,
                        onCheckedChange = { on -> update { it.copy(notifyAtTime = if (on) it.notifyAtTime + p else it.notifyAtTime - p) } },
                        label = { Text(p.label) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item { ListHeader { Text("${s.reminderMinutes} min before") } }
            Prayer.prayers.forEach { p ->
                item {
                    SwitchButton(
                        checked = p in s.notifyBefore,
                        onCheckedChange = { on -> update { it.copy(notifyBefore = if (on) it.notifyBefore + p else it.notifyBefore - p) } },
                        label = { Text(p.label) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingButton(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Button(
        onClick = onClick,
        label = { Text(label) },
        secondaryLabel = { Text(value, maxLines = 2) },
        colors = ButtonDefaults.filledTonalButtonColors(),
        modifier = modifier.fillMaxWidth(),
    )
}

private fun madhabName(m: Madhab) = if (m == Madhab.HANAFI) "Hanafi" else "Standard"

@Composable
fun MethodScreen(state: AppState, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()
    fun pick(m: CalcMethod?) = scope.launch {
        Miqat.updateSettings(ctx) { it.copy(methodOverride = m) }
        onDone()
    }
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = roundPadding(padding), modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Method") } }
            item {
                RadioButton(
                    selected = state.settings.methodOverride == null,
                    onSelect = { pick(null) },
                    label = { Text("Automatic") },
                    secondaryLabel = { Text(state.autoMethod.label, maxLines = 2) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            CalcMethod.entries.forEach { m ->
                item {
                    RadioButton(
                        selected = state.settings.methodOverride == m,
                        onSelect = { pick(m) },
                        label = { Text(m.label, maxLines = 2) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
