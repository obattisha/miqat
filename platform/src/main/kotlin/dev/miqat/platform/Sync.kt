package dev.miqat.platform

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.miqat.core.SavedLocation
import dev.miqat.core.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phone <-> watch sync over the Wearable Data Layer. Each device publishes its own copy of
 * settings and location; the receiver keeps whichever is newer. Google Play services delivers
 * changes even when the app isn't running, with no polling on our side.
 */
object Sync {
    private const val TAG = "MiqatSync"
    const val PATH_SETTINGS = "/miqat/settings"
    const val PATH_LOCATION = "/miqat/location"
    const val PHONE_CAPABILITY = "miqat_phone"

    suspend fun pushSettings(ctx: Context, s: Settings) = put(ctx, PATH_SETTINGS, s.encode())

    suspend fun pushLocation(ctx: Context, l: SavedLocation) = put(ctx, PATH_LOCATION, l.encode())

    private suspend fun put(ctx: Context, path: String, value: String) {
        runCatching {
            val req = PutDataMapRequest.create(path).apply {
                dataMap.putString("v", value)
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(ctx).putDataItem(req).await()
        }.onFailure { Log.w(TAG, "put $path failed (no paired device?)", it) }
    }

    /** True when a phone running Miqat is connected (Bluetooth or Wi-Fi/cloud) right now. */
    suspend fun phoneReachable(ctx: Context): Boolean = runCatching {
        withTimeoutOrNull(4_000) {
            Wearable.getCapabilityClient(ctx)
                .getCapability(PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes.isNotEmpty()
        } ?: false
    }.getOrDefault(false)

    /** Reconcile with whatever the other device last published (e.g. after install/reboot). */
    suspend fun pullAll(ctx: Context) {
        runCatching {
            val buffer = Wearable.getDataClient(ctx).dataItems.await()
            val items = buffer.map { it.freeze() }
            buffer.release()
            items.forEach { handle(ctx, it) }
        }.onFailure { Log.w(TAG, "pull failed", it) }
    }

    suspend fun handle(ctx: Context, item: DataItem) {
        val path = item.uri.path ?: return
        val value = DataMapItem.fromDataItem(item).dataMap.getString("v") ?: return
        when (path) {
            PATH_SETTINGS -> Miqat.applyRemoteSettings(ctx, Settings.decode(value))
            PATH_LOCATION -> SavedLocation.decode(value)?.let { Miqat.updateLocation(ctx, it, fromRemote = true) }
        }
    }
}

class SyncListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val items = events.filter { it.type == DataEvent.TYPE_CHANGED }.map { it.dataItem.freeze() }
        events.release()
        // Called on a background thread; block until state is persisted.
        runBlocking { items.forEach { Sync.handle(applicationContext, it) } }
    }
}
