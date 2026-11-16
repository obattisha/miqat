package dev.miqat.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.miqat.core.SavedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * "Location follows me" at near-zero battery cost:
 *  - A PendingIntent-based fused location request at LOW_POWER priority (cell/Wi-Fi, no GPS),
 *    batched up to 2 h and only delivered after moving 3+ km. It survives the app being killed.
 *  - Plus a one-shot fix on timezone change and whenever the app is opened.
 * The phone forwards each new place to the watch, so the watch never needs its own GPS.
 */
object LocationUpdater {
    private const val TAG = "MiqatLocation"

    fun hasForeground(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun register(ctx: Context) {
        if (Hooks.isWatch || !hasForeground(ctx)) return
        val request = LocationRequest.Builder(Priority.PRIORITY_LOW_POWER, TimeUnit.MINUTES.toMillis(30))
            .setMinUpdateDistanceMeters(3_000f)
            .setMaxUpdateDelayMillis(TimeUnit.HOURS.toMillis(2))
            .build()
        runCatching {
            LocationServices.getFusedLocationProviderClient(ctx).requestLocationUpdates(request, pendingIntent(ctx))
        }.onFailure { Log.w(TAG, "register failed", it) }
    }

    /** One-shot fix. Returns true if it produced a new stored location. */
    @SuppressLint("MissingPermission")
    suspend fun refreshNow(ctx: Context): Boolean {
        if (!hasForeground(ctx)) return false
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val loc = runCatching {
            withTimeoutOrNull(20_000) {
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token).await()
            } ?: client.lastLocation.await()
        }.onFailure { Log.w(TAG, "fused location failed", it) }.getOrNull()
            ?: platformLastKnown(ctx)
        if (loc == null) {
            Log.i(TAG, "no location available yet")
            return false
        }
        return handle(ctx, loc)
    }

    /** Fallback when Play Services location has nothing: the OS's own cached fixes. Free. */
    @SuppressLint("MissingPermission")
    private fun platformLastKnown(ctx: Context): Location? = runCatching {
        val lm = ctx.getSystemService(android.location.LocationManager::class.java)
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }.onFailure { Log.w(TAG, "platform location failed", it) }.getOrNull()

    suspend fun handle(ctx: Context, loc: Location): Boolean {
        val (city, country) = describe(ctx, loc)
        return Miqat.updateLocation(
            ctx,
            SavedLocation(loc.latitude, loc.longitude, city, country, System.currentTimeMillis()),
        )
    }

    /** City + ISO country. Geocoder when available; otherwise the timezone's region (offline). */
    @Suppress("DEPRECATION")
    private suspend fun describe(ctx: Context, loc: Location): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val address = if (Geocoder.isPresent()) {
            runCatching { Geocoder(ctx, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() }.getOrNull()
        } else {
            null
        }
        val city = address?.locality ?: address?.subAdminArea ?: address?.adminArea
        val country = address?.countryCode ?: regionFromTimezone()
        city to country
    }

    private fun regionFromTimezone(): String? = runCatching {
        android.icu.util.TimeZone.getRegion(java.util.TimeZone.getDefault().id).takeIf { it != "001" }
    }.getOrNull()

    private fun pendingIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, LocationReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
}

class LocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = async {
        LocationResult.extractResult(intent)?.lastLocation?.let { LocationUpdater.handle(context, it) }
    }
}
