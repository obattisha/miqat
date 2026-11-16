package dev.miqat.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import kotlin.math.roundToLong

data class Coordinates(val latitude: Double, val longitude: Double)

data class CalcParams(val method: CalcMethod, val madhab: Madhab)

data class PrayerEvent(val prayer: Prayer, val time: Instant)

/** The six times for one local date at one place. */
data class DayTimes(val date: LocalDate, val times: Map<Prayer, Instant>) {
    operator fun get(p: Prayer): Instant = times.getValue(p)
    val events: List<PrayerEvent> get() = Prayer.entries.map { PrayerEvent(it, get(it)) }
}

object PrayerEngine {

    fun compute(date: LocalDate, coords: Coordinates, params: CalcParams): DayTimes {
        val lat = coords.latitude
        val lng = coords.longitude
        val m = params.method
        // Julian date at local mean midnight.
        val jDate = Astro.julian(date) - lng / (15.0 * 24.0)

        // Approximate times (local mean solar hours) refined over two passes.
        var fajr = 5.0; var sunrise = 6.0; var dhuhr = 12.0; var asr = 13.0
        var sunset = 18.0; var maghrib = 18.0; var isha = 18.0
        repeat(2) {
            fajr = Astro.sunAngleTime(jDate, lat, m.fajrAngle, fajr.orElse(5.0), ccw = true)
            sunrise = Astro.sunAngleTime(jDate, lat, RISE_SET_ANGLE, sunrise.orElse(6.0), ccw = true)
            dhuhr = Astro.midDay(jDate, dhuhr)
            asr = Astro.asrTime(jDate, lat, params.madhab.shadowFactor, asr.orElse(13.0))
            sunset = Astro.sunAngleTime(jDate, lat, RISE_SET_ANGLE, sunset.orElse(18.0), ccw = false)
            maghrib = m.maghribAngle?.let { Astro.sunAngleTime(jDate, lat, it, maghrib.orElse(18.0), ccw = false) }
                ?: sunset
            isha = m.ishaAngle?.let { Astro.sunAngleTime(jDate, lat, it, isha.orElse(18.0), ccw = false) }
                ?: Double.NaN
        }

        // Fixed-interval Isha (Umm al-Qura, Qatar).
        if (m.ishaAngle == null) {
            val minutes = if (m.ramadanIshaMinutes != null && isRamadan(date)) m.ramadanIshaMinutes else m.ishaMinutes
            isha = sunset + minutes / 60.0
        }

        // High-latitude safety: when twilight never ends (or lasts unreasonably long), cap Fajr/Isha
        // at a fraction of the night proportional to their angle ("angle-based" rule).
        val night = 24.0 - (sunset - sunrise)
        if (!night.isNaN()) {
            val fajrPortion = m.fajrAngle / 60.0 * night
            if (fajr.isNaN() || sunrise - fajr > fajrPortion) fajr = sunrise - fajrPortion
            if (m.ishaAngle != null) {
                val ishaPortion = m.ishaAngle / 60.0 * night
                if (isha.isNaN() || isha - sunset > ishaPortion) isha = sunset + ishaPortion
            }
            if (m.maghribAngle != null) {
                val portion = m.maghribAngle / 60.0 * night
                if (maghrib.isNaN() || maghrib - sunset > portion) maghrib = sunset + portion
            }
        }

        val raw = mapOf(
            Prayer.FAJR to fajr,
            Prayer.SUNRISE to sunrise,
            Prayer.DHUHR to dhuhr,
            Prayer.ASR to asr,
            Prayer.MAGHRIB to maghrib,
            Prayer.ISHA to isha,
        )
        val midnightUtc = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val times = raw.mapValues { (prayer, localSolarHours) ->
            val utcHours = localSolarHours - lng / 15.0
            val seconds = midnightUtc + (utcHours * 3600.0).roundToLong() + m.adjustments.of(prayer) * 60L
            Instant.ofEpochSecond(roundToMinute(seconds))
        }
        return DayTimes(date, times)
    }

    private fun Double.orElse(d: Double) = if (isNaN()) d else this

    private fun roundToMinute(epochSeconds: Long): Long = Math.floorDiv(epochSeconds + 30, 60L) * 60L

    private fun isRamadan(date: LocalDate): Boolean = try {
        HijrahDate.from(date).get(ChronoField.MONTH_OF_YEAR) == 9
    } catch (_: Exception) {
        false
    }

    private const val RISE_SET_ANGLE = 0.833
}
