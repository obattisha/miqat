package dev.miqat.platform

import android.content.BroadcastReceiver
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.miqat.core.AppState
import dev.miqat.core.SavedLocation
import dev.miqat.core.Settings
import dev.miqat.core.haversineKm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private val Context.store by preferencesDataStore("miqat")

/** Per-app hooks so shared code can poke device-specific pieces (e.g. the watch complication). */
object Hooks {
    @Volatile var isWatch: Boolean = false
    @Volatile var onChanged: ((Context) -> Unit)? = null
}

/**
 * Single source of truth on each device. Every change funnels through here so alarms, the
 * complication and the other device always get updated together.
 */
object Miqat {
    private val K_SETTINGS = stringPreferencesKey("settings")
    private val K_LOCATION = stringPreferencesKey("location")

    fun state(ctx: Context): Flow<AppState> = ctx.applicationContext.store.data.map { it.toState() }

    suspend fun snapshot(ctx: Context): AppState = state(ctx).first()

    private fun Preferences.toState() = AppState(
        settings = Settings.decode(this[K_SETTINGS]),
        location = SavedLocation.decode(this[K_LOCATION]),
    )

    suspend fun updateSettings(ctx: Context, transform: (Settings) -> Settings) {
        val app = ctx.applicationContext
        var saved: Settings? = null
        app.store.edit { prefs ->
            val next = transform(Settings.decode(prefs[K_SETTINGS])).copy(updatedAt = System.currentTimeMillis())
            prefs[K_SETTINGS] = next.encode()
            saved = next
        }
        // Return to the UI right away; sync + alarm replanning happen off the main thread,
        // coalesced so a burst of taps only does the work once.
        afterLocalEdit(app, pushSettings = saved != null)
    }

    /** Applies settings from the other device if they are newer than ours. */
    suspend fun applyRemoteSettings(ctx: Context, remote: Settings) {
        val app = ctx.applicationContext
        var applied = false
        app.store.edit { prefs ->
            val local = Settings.decode(prefs[K_SETTINGS])
            if (remote.updatedAt > local.updatedAt) {
                prefs[K_SETTINGS] = remote.encode()
                applied = true
            }
        }
        if (applied) onChanged(app)
    }

    /**
     * Saves a new fix if it's newer and meaningfully different (moved > 2 km, or city/country
     * info improved). Returns true when stored.
     */
    suspend fun updateLocation(ctx: Context, loc: SavedLocation, fromRemote: Boolean = false): Boolean {
        val app = ctx.applicationContext
        var stored = false
        var saved = loc
        app.store.edit { prefs ->
            val current = SavedLocation.decode(prefs[K_LOCATION])
            val accept = current == null || (
                loc.fixTime > current.fixTime && (
                    haversineKm(current.coords, loc.coords) > 2.0 ||
                        (loc.countryCode != null && loc.countryCode != current.countryCode) ||
                        (loc.city != null && current.city == null)
                    )
                )
            if (accept) {
                // A fix without a place name (e.g. from the watch, which can't geocode) keeps the
                // previous city/country when it's still nearby.
                val named = if (current != null && loc.city == null && haversineKm(current.coords, loc.coords) < 25.0) {
                    loc.copy(city = current.city, countryCode = loc.countryCode ?: current.countryCode)
                } else {
                    loc
                }
                prefs[K_LOCATION] = named.encode()
                saved = named
                stored = true
            }
        }
        if (stored) {
            if (!fromRemote) Sync.pushLocation(app, saved)
            onChanged(app)
        }
        return stored
    }

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pendingWork: Job? = null
    private var pendingPush = false
    private val replanLock = Mutex()

    @Synchronized
    private fun afterLocalEdit(app: Context, pushSettings: Boolean) {
        pendingPush = pendingPush || pushSettings
        pendingWork?.cancel()
        pendingWork = background.launch {
            delay(400)
            val push = synchronized(this@Miqat) { pendingPush.also { pendingPush = false } }
            if (push) Sync.pushSettings(app, snapshot(app).settings)
            onChanged(app)
        }
    }

    /** Recompute everything downstream of state: alarms and (on watch) complications. */
    suspend fun onChanged(ctx: Context) = withContext(Dispatchers.Default) {
        replanLock.withLock {
            AlarmScheduler.reschedule(ctx)
            Hooks.onChanged?.invoke(ctx)
        }
    }

    /** Called from Application.onCreate on both devices. Cheap and idempotent. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        Notifier.ensureChannels(app)
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            "miqat-safety-net",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SafetyNetWorker>(12, TimeUnit.HOURS).build(),
        )
        CoroutineScope(Dispatchers.Default).launch {
            Sync.pullAll(app)
            LocationUpdater.register(app)
            onChanged(app)
        }
    }
}

/**
 * Twice-daily backstop: re-plans alarms, re-arms passive location, reconciles sync. Alarms are
 * already re-planned every time one fires and on boot/time changes; this only covers odd cases.
 */
class SafetyNetWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Sync.pullAll(applicationContext)
        LocationUpdater.register(applicationContext)
        Miqat.onChanged(applicationContext)
        return Result.success()
    }
}

/** Runs [block] off the main thread while keeping the broadcast alive. */
internal fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}
