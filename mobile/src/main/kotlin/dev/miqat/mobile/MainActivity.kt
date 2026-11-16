package dev.miqat.mobile

import android.Manifest
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.miqat.core.AppState
import dev.miqat.platform.Brand
import dev.miqat.platform.Hooks
import dev.miqat.platform.LocationUpdater
import dev.miqat.platform.Miqat
import dev.miqat.mobile.widget.MiqatWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

class MiqatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hooks.isWatch = false
        // Redraw home-screen widgets whenever times, settings or location change.
        Hooks.onChanged = { ctx -> CoroutineScope(Dispatchers.Default).launch { MiqatWidget.refresh(ctx) } }
        Miqat.start(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { MiqatTheme { Root() } }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app always refreshes location (one cheap fix) and re-arms passive updates.
        lifecycleScope.launch {
            LocationUpdater.refreshNow(this@MainActivity)
            LocationUpdater.register(this@MainActivity)
        }
    }
}

private enum class Tab(val label: String) { TIMES("Times"), QIBLA("Qibla"), SETTINGS("Settings") }

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val state by Miqat.state(ctx).collectAsStateWithLifecycle(initialValue = null)
    var tab by rememberSaveable { mutableStateOf(Tab.TIMES) }
    val now = rememberNow()

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(painterResource(t.icon()), contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        val s = state ?: return@Scaffold
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.TIMES -> TimesScreen(s, now, modifier)
            Tab.QIBLA -> QiblaScreen(s, now, modifier)
            Tab.SETTINGS -> SettingsScreen(s, modifier)
        }
    }
}

private fun Tab.icon() = when (this) {
    Tab.TIMES -> R.drawable.ic_tab_times
    Tab.QIBLA -> R.drawable.ic_tab_qibla
    Tab.SETTINGS -> R.drawable.ic_tab_settings
}

/** Current time, ticking at each minute boundary while on screen. */
@Composable
fun rememberNow(): Instant {
    var millis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            // Countdowns show minutes, so redraw once a minute.
            delay(60_000 - System.currentTimeMillis() % 60_000)
            millis = System.currentTimeMillis()
        }
    }
    return Instant.ofEpochMilli(millis)
}

val foregroundPermissions: Array<String>
    get() = buildList {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

/**
 * Brand theme: emerald + gold from the app icon, on warm parchment (light) or green-black (dark).
 * Deliberately not wallpaper-derived, so the app has a consistent identity.
 */
@Composable
fun MiqatTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(Brand.EMERALD_300),
            onPrimary = Color(Brand.EMERALD_950),
            primaryContainer = Color(Brand.EMERALD_800),
            onPrimaryContainer = Color(Brand.EMERALD_100),
            secondary = Color(Brand.EMERALD_200),
            onSecondary = Color(Brand.EMERALD_950),
            secondaryContainer = Color(Brand.NIGHT_4),
            onSecondaryContainer = Color(Brand.EMERALD_100),
            tertiary = Color(Brand.GOLD_300),
            onTertiary = Color(0xFF3A2C00),
            tertiaryContainer = Color(0xFF574400),
            onTertiaryContainer = Color(Brand.GOLD_100),
            background = Color(Brand.INK),
            onBackground = Color(Brand.MIST),
            surface = Color(Brand.INK),
            onSurface = Color(Brand.MIST),
            onSurfaceVariant = Color(Brand.MIST_DIM),
            surfaceContainerLowest = Color(Brand.INK),
            surfaceContainerLow = Color(Brand.NIGHT_1),
            surfaceContainer = Color(Brand.NIGHT_2),
            surfaceContainerHigh = Color(Brand.NIGHT_3),
            surfaceContainerHighest = Color(Brand.NIGHT_4),
            outline = Color(0xFF55655D),
            outlineVariant = Color(0xFF2E3B35),
        )
    } else {
        lightColorScheme(
            primary = Color(Brand.EMERALD_800),
            onPrimary = Color.White,
            primaryContainer = Color(Brand.EMERALD_100),
            onPrimaryContainer = Color(Brand.EMERALD_900),
            secondary = Color(Brand.EMERALD_600),
            onSecondary = Color.White,
            secondaryContainer = Color(Brand.EMERALD_50),
            onSecondaryContainer = Color(Brand.EMERALD_900),
            tertiary = Color(Brand.GOLD_700),
            onTertiary = Color.White,
            tertiaryContainer = Color(Brand.GOLD_100),
            onTertiaryContainer = Color(0xFF3A2C00),
            background = Color(Brand.PARCHMENT),
            onBackground = Color(0xFF16201B),
            surface = Color(Brand.PARCHMENT),
            onSurface = Color(0xFF16201B),
            onSurfaceVariant = Color(0xFF4F5C55),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(Brand.PARCHMENT_2),
            surfaceContainer = Color(Brand.PARCHMENT_2),
            surfaceContainerHigh = Color(Brand.PARCHMENT_3),
            surfaceContainerHighest = Color(Brand.PARCHMENT_4),
            outline = Color(0xFF7D8A83),
            outlineVariant = Color(0xFFD3D7CF),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun AppState.methodLabel(): String =
    if (settings.methodOverride == null) "${method.label} · auto" else method.label
