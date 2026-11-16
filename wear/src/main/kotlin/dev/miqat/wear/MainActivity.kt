package dev.miqat.wear

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import dev.miqat.platform.Brand
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.phone.interactions.notifications.BridgingConfig
import androidx.wear.phone.interactions.notifications.BridgingManager
import dev.miqat.platform.Hooks
import dev.miqat.platform.LocationUpdater
import dev.miqat.platform.Miqat
import dev.miqat.wear.complication.NextPrayerComplicationService
import kotlinx.coroutines.launch

class WearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hooks.isWatch = true
        Hooks.onChanged = {
            NextPrayerComplicationService.requestUpdate(it)
            dev.miqat.wear.tile.SuspendingTile.requestUpdates(it)
        }
        // Let the phone's prayer notifications mirror to the watch (one shared notification).
        // An earlier build turned bridging off, and the setting persists, so set it explicitly.
        BridgingManager.fromContext(this).setConfig(BridgingConfig.Builder(this, true).build())
        Miqat.start(this)
    }
}

class MainActivity : ComponentActivity() {
    /** Screen requested by the launching intent (e.g. the complication tap opens the compass). */
    private val requested = mutableStateOf(ScreenRequest(SCREEN_HOME))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requested.value = ScreenRequest(intent?.getStringExtra(EXTRA_SCREEN) ?: SCREEN_HOME)
        setContent { MiqatWearTheme { WearRoot(requested.value) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requested.value = ScreenRequest(intent.getStringExtra(EXTRA_SCREEN) ?: SCREEN_HOME)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { LocationUpdater.refreshNow(this@MainActivity) }
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_HOME = "home"
        const val SCREEN_QIBLA = "qibla"
    }
}

/** Wrapper so tapping the complication twice still counts as a new request. */
class ScreenRequest(val screen: String)

/** Brand colors (emerald + gold from the app icon) on a green-black canvas. */
@Composable
private fun MiqatWearTheme(content: @Composable () -> Unit) {
    val scheme = remember {
        ColorScheme(
            primary = Color(Brand.EMERALD_300),
            primaryDim = Color(Brand.EMERALD_400),
            primaryContainer = Color(Brand.EMERALD_800),
            onPrimary = Color(Brand.EMERALD_950),
            onPrimaryContainer = Color(Brand.EMERALD_100),
            secondary = Color(Brand.EMERALD_200),
            secondaryDim = Color(Brand.EMERALD_300),
            secondaryContainer = Color(Brand.NIGHT_3),
            onSecondary = Color(Brand.EMERALD_950),
            onSecondaryContainer = Color(Brand.EMERALD_100),
            tertiary = Color(Brand.GOLD_300),
            tertiaryDim = Color(Brand.GOLD_500),
            tertiaryContainer = Color(0xFF574400),
            onTertiary = Color(0xFF3A2C00),
            onTertiaryContainer = Color(Brand.GOLD_100),
            surfaceContainerLow = Color(Brand.NIGHT_1),
            surfaceContainer = Color(Brand.NIGHT_2),
            surfaceContainerHigh = Color(Brand.NIGHT_3),
            onSurface = Color(Brand.MIST),
            onSurfaceVariant = Color(Brand.MIST_DIM),
            outline = Color(0xFF55655D),
            outlineVariant = Color(0xFF2E3B35),
            background = Color.Black,
            onBackground = Color(Brand.MIST),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun WearRoot(request: ScreenRequest) {
    val ctx = LocalContext.current
    val state by Miqat.state(ctx).collectAsStateWithLifecycle(initialValue = null)
    val nav = rememberSwipeDismissableNavController()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        val missing = buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    AppScaffold {
        val s = state ?: return@AppScaffold
        LaunchedEffect(request) {
            if (request.screen == MainActivity.SCREEN_QIBLA && nav.currentDestination?.route != MainActivity.SCREEN_QIBLA) {
                nav.navigate(MainActivity.SCREEN_QIBLA) { launchSingleTop = true }
            }
        }
        SwipeDismissableNavHost(navController = nav, startDestination = MainActivity.SCREEN_HOME) {
            composable(MainActivity.SCREEN_HOME) {
                HomeScreen(s, onQibla = { nav.navigate(MainActivity.SCREEN_QIBLA) }, onSettings = { nav.navigate(ROUTE_SETTINGS) })
            }
            composable(MainActivity.SCREEN_QIBLA) { QiblaScreen(s) }
            composable(ROUTE_SETTINGS) { SettingsScreen(s, onMethod = { nav.navigate(ROUTE_METHOD) }) }
            composable(ROUTE_METHOD) { MethodScreen(s, onDone = { nav.popBackStack() }) }
        }
    }
}

private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_METHOD = "method"
