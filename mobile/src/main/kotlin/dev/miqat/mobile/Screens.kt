package dev.miqat.mobile

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import dev.miqat.platform.AlarmScheduler
import dev.miqat.platform.AlignmentHaptics
import dev.miqat.platform.DialColors
import dev.miqat.platform.LocationUpdater
import dev.miqat.platform.Miqat
import dev.miqat.platform.Notifier
import dev.miqat.platform.QiblaDial
import dev.miqat.platform.TimeFormat
import dev.miqat.platform.compassFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.HijrahChronology
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val PanelShape = RoundedCornerShape(28.dp)

// ---------------------------------------------------------------- Times

@Composable
fun TimesScreen(state: AppState, now: Instant, modifier: Modifier) {
    val ctx = LocalContext.current
    val location = state.location ?: return NeedLocation(modifier)
    val zone = ZoneId.systemDefault()
    val today = Schedule.today(location, state.params, now, zone)
    val (current, next) = Schedule.window(location, state.params, now, zone)
    val total = Duration.between(current.time, next.time).seconds.toFloat().coerceAtLeast(1f)
    val progress = (Duration.between(current.time, now).seconds / total).coerceIn(0f, 1f)
    val colors = MaterialTheme.colorScheme

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Text(location.displayName, style = MaterialTheme.typography.headlineSmall)
            Text(dateLine(now.atZone(zone).toLocalDate()), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))

            // Hero: the same progress ring as the watch complication.
            Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 4.dp,
                    trackColor = colors.surfaceContainerHigh,
                    gapSize = 0.dp,
                    strokeCap = StrokeCap.Round,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(next.prayer.label, style = MaterialTheme.typography.titleLarge, color = colors.tertiary)
                    Text(
                        formatCountdown(Duration.between(now, next.time)),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Light,
                    )
                    Text("at ${TimeFormat.time(ctx, next.time)}", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(28.dp))
        }
        item {
            Panel {
                today.events.forEach { e ->
                    val isCurrent = e.prayer == current.prayer && e.time == current.time
                    val muted = !isCurrent && (e.time <= now || !e.prayer.isPrayer)
                    val fg = when {
                        isCurrent -> colors.onPrimaryContainer
                        muted -> colors.onSurfaceVariant
                        else -> colors.onSurface
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .clip(CircleShape)
                            .background(if (isCurrent) colors.primaryContainer else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(e.prayer.label, style = MaterialTheme.typography.titleMedium, color = fg)
                        Text(TimeFormat.time(ctx, e.time), style = MaterialTheme.typography.titleMedium, color = fg)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                state.methodLabel() + " · Asr " + (if (state.madhab == Madhab.HANAFI) "Hanafi" else "Standard"),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** "Tuesday, 10 November · 19 Jumada I 1448" */
private fun dateLine(date: LocalDate): String {
    val greg = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()).format(date)
    val hijri = Hijri.format(date)
    return if (hijri != null) "$greg · $hijri" else greg
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = PanelShape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp), content = content)
    }
}

@Composable
private fun NeedLocation(modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch {
            LocationUpdater.refreshNow(ctx)
            LocationUpdater.register(ctx)
        }
    }
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Prayer times need your location", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Approximate is enough. It's only used on your phone and watch.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = { launcher.launch(foregroundPermissions) }) { Text("Allow location") }
    }
}

// ---------------------------------------------------------------- Qibla

@Composable
fun QiblaScreen(state: AppState, now: Instant, modifier: Modifier) {
    val ctx = LocalContext.current
    val location = state.location ?: return NeedLocation(modifier)
    val qibla = Qibla.bearing(location.coords)
    val reading by remember(location.latitude, location.longitude) {
        compassFlow(ctx, location.latitude, location.longitude)
    }.collectAsStateWithLifecycle(initialValue = null)
    val haptics = remember { AlignmentHaptics(ctx) }
    val r = reading
    val offset = r?.let { Qibla.delta(qibla, it.heading.toDouble()) } ?: 180.0
    val aligned = r != null && haptics.update(offset)
    val (_, next) = Schedule.window(location, state.params, now, ZoneId.systemDefault())
    val colors = MaterialTheme.colorScheme

    Column(
        modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Qibla", style = MaterialTheme.typography.titleMedium, color = colors.tertiary)
        Text(
            "${qibla.roundToInt()}° ${Qibla.compassPoint(qibla)} · %,d km to Makkah".format(Qibla.distanceKm(location.coords).roundToInt()),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        // The live dial, same design as the watch tile, with the readout in its centre.
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            if (r == null) {
                Text("This phone has no compass", color = colors.onSurfaceVariant)
            } else {
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (aligned) "${qibla.roundToInt()}°" else "${abs(offset).roundToInt()}°",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Light,
                    )
                    Text(
                        when {
                            aligned -> "Facing the qibla"
                            offset > 0 -> "turn right"
                            else -> "turn left"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.primary,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (r != null) AccuracyPill(r)
        Spacer(Modifier.height(10.dp))
        Text(
            "Hold your phone flat · ${next.prayer.label} in ${formatCountdown(Duration.between(now, next.time))}",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

/**
 * "Accuracy ±6° · Good", plus a note when there's magnetic interference nearby, so you can decide
 * whether it's worth calibrating before you pray.
 */
@Composable
private fun AccuracyPill(r: dev.miqat.platform.CompassReading) {
    val colors = MaterialTheme.colorScheme
    val q = r.quality
    val dot = when (q) {
        CompassAccuracy.Quality.GOOD -> colors.primary
        CompassAccuracy.Quality.FAIR -> colors.secondary
        CompassAccuracy.Quality.POOR -> colors.error
    }
    Surface(shape = CircleShape, color = colors.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(10.dp))
            Text("Accuracy about ±${r.accuracyDeg.roundToInt()}° · ${if (q == CompassAccuracy.Quality.POOR) "calibrate" else q.label.lowercase()}", style = MaterialTheme.typography.labelLarge)
        }
    }
    val hints = buildList {
        if (r.interference > 0.10f) add("Magnetic interference ${(r.interference * 100).roundToInt()}%: move away from metal or electronics")
        if (q == CompassAccuracy.Quality.POOR) add("Move your phone in a figure 8 a few times")
    }
    if (hints.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(hints.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------- Settings

@Composable
fun SettingsScreen(state: AppState, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun update(f: (Settings) -> Settings) = scope.launch { Miqat.updateSettings(ctx, f) }
    var showMethods by remember { mutableStateOf(false) }

    // Re-read permission state whenever we come back from system settings.
    var resumeTick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumeTick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resumeTick++ }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        resumeTick++
        LocationUpdater.register(ctx)
    }
    val s = state.settings
    val itemColors = ListItemDefaults.colors(containerColor = Color.Transparent)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))

        Section("Calculation")
        Panel {
            ListItem(
                headlineContent = { Text("Method") },
                supportingContent = { Text(state.methodLabel()) },
                colors = itemColors,
                modifier = Modifier.clickable { showMethods = true },
            )
            ListItem(
                headlineContent = { Text("Asr") },
                supportingContent = {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = s.madhabOverride == null,
                            onClick = { update { it.copy(madhabOverride = null) } },
                            label = { Text("Auto · ${if (state.autoMadhab == Madhab.HANAFI) "Hanafi" else "Standard"}") },
                        )
                        FilterChip(
                            selected = s.madhabOverride == Madhab.STANDARD,
                            onClick = { update { it.copy(madhabOverride = Madhab.STANDARD) } },
                            label = { Text("Standard") },
                        )
                        FilterChip(
                            selected = s.madhabOverride == Madhab.HANAFI,
                            onClick = { update { it.copy(madhabOverride = Madhab.HANAFI) } },
                            label = { Text("Hanafi") },
                        )
                    }
                },
                colors = itemColors,
            )
        }

        Section("Alerts")
        Panel {
            ListItem(
                headlineContent = { Text("Reminder") },
                supportingContent = {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Settings.REMINDER_CHOICES.forEach { m ->
                            FilterChip(
                                selected = s.reminderMinutes == m,
                                onClick = { update { it.copy(reminderMinutes = m) } },
                                label = { Text("$m min") },
                            )
                        }
                    }
                },
                colors = itemColors,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text("Before", Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("At time", Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Prayer.prayers.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                        Checkbox(checked = p in s.notifyBefore, onCheckedChange = { on -> update { it.copy(notifyBefore = it.notifyBefore.toggle(p, on)) } })
                    }
                    Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                        Checkbox(checked = p in s.notifyAtTime, onCheckedChange = { on -> update { it.copy(notifyAtTime = it.notifyAtTime.toggle(p, on)) } })
                    }
                }
            }
            Text(
                "One notification per prayer, shown on your phone and watch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        Section("Location")
        Panel {
            ListItem(
                headlineContent = { Text(state.location?.displayName ?: "Unknown") },
                supportingContent = { Text("Follows you when you travel") },
                trailingContent = { TextButton(onClick = { scope.launch { LocationUpdater.refreshNow(ctx) } }) { Text("Update") } },
                colors = itemColors,
            )
        }

        Section("Permissions")
        Panel {
            key(resumeTick) {
                PermissionRow("Location", LocationUpdater.hasForeground(ctx), "Needed for prayer times", itemColors) {
                    permLauncher.launch(foregroundPermissions)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    PermissionRow("Location while closed", LocationUpdater.hasBackground(ctx), "Pick \"Allow all the time\" so times follow you", itemColors) {
                        bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }
                PermissionRow("Notifications", Notifier.canPost(ctx), "Needed for prayer alerts", itemColors) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    } else {
                        ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    }
                }
                PermissionRow("Exact alerts", AlarmScheduler.canScheduleExact(ctx), "Alerts arrive on the minute", itemColors) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showMethods) {
        MethodDialog(state, onDismiss = { showMethods = false }) { m ->
            update { it.copy(methodOverride = m) }
            showMethods = false
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun MethodDialog(state: AppState, onDismiss: () -> Unit, onPick: (CalcMethod?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Calculation method") },
        text = {
            LazyColumn {
                item { MethodRow("Automatic: ${state.autoMethod.label}", state.settings.methodOverride == null) { onPick(null) } }
                items(CalcMethod.entries) { m -> MethodRow(m.label, state.settings.methodOverride == m) { onPick(m) } }
            }
        },
    )
}

@Composable
private fun MethodRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    why: String,
    colors: androidx.compose.material3.ListItemColors,
    onFix: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(if (granted) "Allowed" else why) },
        trailingContent = {
            if (!granted) TextButton(onClick = onFix) { Text("Allow") } else Text("✓", color = MaterialTheme.colorScheme.primary)
        },
        colors = colors,
    )
}

private fun Set<Prayer>.toggle(p: Prayer, on: Boolean) = if (on) this + p else this - p
