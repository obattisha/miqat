package dev.miqat.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Settings that sync between phone and watch. Last writer (by [updatedAt]) wins. */
data class Settings(
    val methodOverride: CalcMethod? = null,
    val madhabOverride: Madhab? = null,
    val notifyAtTime: Set<Prayer> = Prayer.prayers.toSet(),
    val notifyBefore: Set<Prayer> = Prayer.prayers.toSet(),
    val reminderMinutes: Int = 10,
    val updatedAt: Long = 0,
) {
    fun encode(): String = listOf(
        "v" to "1",
        "m" to (methodOverride?.name ?: ""),
        "h" to (madhabOverride?.name ?: ""),
        "a" to notifyAtTime.joinToString(",") { it.name },
        "b" to notifyBefore.joinToString(",") { it.name },
        "r" to reminderMinutes.toString(),
        "u" to updatedAt.toString(),
    ).joinToString(";") { "${it.first}=${it.second}" }

    companion object {
        val REMINDER_CHOICES = listOf(5, 10, 15, 20, 30, 45, 60)

        fun decode(s: String?): Settings {
            if (s.isNullOrBlank()) return Settings()
            val map = s.split(";").mapNotNull {
                val i = it.indexOf('=')
                if (i < 0) null else it.substring(0, i) to it.substring(i + 1)
            }.toMap()
            fun prayers(key: String, default: Set<Prayer>) = map[key]?.let { v ->
                v.split(",").filter { it.isNotBlank() }
                    .mapNotNull { runCatching { Prayer.valueOf(it) }.getOrNull() }.toSet()
            } ?: default
            return Settings(
                methodOverride = map["m"]?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { CalcMethod.valueOf(it) }.getOrNull() },
                madhabOverride = map["h"]?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { Madhab.valueOf(it) }.getOrNull() },
                notifyAtTime = prayers("a", Prayer.prayers.toSet()),
                notifyBefore = prayers("b", Prayer.prayers.toSet()),
                reminderMinutes = map["r"]?.toIntOrNull() ?: 10,
                updatedAt = map["u"]?.toLongOrNull() ?: 0,
            )
        }
    }
}

/** Last known place. Newest [fixTime] wins when syncing. */
data class SavedLocation(
    val latitude: Double,
    val longitude: Double,
    val city: String?,
    val countryCode: String?,
    val fixTime: Long,
) {
    val coords get() = Coordinates(latitude, longitude)

    val displayName: String
        get() = city ?: "%.2f, %.2f".format(latitude, longitude)

    fun encode(): String = listOf(latitude, longitude, city ?: "", countryCode ?: "", fixTime)
        .joinToString("|") { it.toString().replace("|", "/") }

    companion object {
        fun decode(s: String?): SavedLocation? {
            if (s.isNullOrBlank()) return null
            val p = s.split("|")
            if (p.size < 5) return null
            return SavedLocation(
                latitude = p[0].toDoubleOrNull() ?: return null,
                longitude = p[1].toDoubleOrNull() ?: return null,
                city = p[2].ifBlank { null },
                countryCode = p[3].ifBlank { null },
                fixTime = p[4].toLongOrNull() ?: 0,
            )
        }
    }
}

/** Everything the UI, complication and scheduler need, resolved from settings + location. */
data class AppState(
    val settings: Settings = Settings(),
    val location: SavedLocation? = null,
) {
    val autoMethod: CalcMethod get() = RegionalDefaults.method(location?.countryCode)
    val autoMadhab: Madhab get() = RegionalDefaults.madhab(location?.countryCode)
    val method: CalcMethod get() = settings.methodOverride ?: autoMethod
    val madhab: Madhab get() = settings.madhabOverride ?: autoMadhab
    val params: CalcParams get() = CalcParams(method, madhab)
    val qiblaBearing: Double? get() = location?.let { Qibla.bearing(it.coords) }
}

/** Times rolled out over consecutive days in the device's current timezone. */
object Schedule {
    fun events(location: SavedLocation, params: CalcParams, from: Instant, days: Int, zone: ZoneId): List<PrayerEvent> {
        val start = from.atZone(zone).toLocalDate().minusDays(1)
        return (0..days + 1).flatMap { i ->
            PrayerEngine.compute(start.plusDays(i.toLong()), location.coords, params).events
        }.sortedBy { it.time }
    }

    fun today(location: SavedLocation, params: CalcParams, now: Instant, zone: ZoneId): DayTimes =
        PrayerEngine.compute(now.atZone(zone).toLocalDate(), location.coords, params)

    /** The prayer currently in effect and the next one (Sunrise excluded). */
    fun window(location: SavedLocation, params: CalcParams, now: Instant, zone: ZoneId): Pair<PrayerEvent, PrayerEvent> {
        val prayers = events(location, params, now, 1, zone).filter { it.prayer.isPrayer }
        val nextIndex = prayers.indexOfFirst { it.time > now }
        return prayers[nextIndex - 1] to prayers[nextIndex]
    }
}

enum class AlertKind { BEFORE, AT_TIME }

data class Alert(val prayer: Prayer, val kind: AlertKind, val fireAt: Instant, val prayerTime: Instant)

object Reminders {
    fun plan(events: List<PrayerEvent>, settings: Settings, now: Instant, limit: Int): List<Alert> =
        events.filter { it.prayer.isPrayer }.flatMap { e ->
            buildList {
                if (e.prayer in settings.notifyBefore) {
                    add(Alert(e.prayer, AlertKind.BEFORE, e.time.minus(Duration.ofMinutes(settings.reminderMinutes.toLong())), e.time))
                }
                if (e.prayer in settings.notifyAtTime) add(Alert(e.prayer, AlertKind.AT_TIME, e.time, e.time))
            }
        }.filter { it.fireAt > now }.sortedBy { it.fireAt }.take(limit)
}

fun formatCountdown(d: Duration): String {
    val totalMin = (d.seconds + 59) / 60 // round up, so "0m" never shows before the time
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}
