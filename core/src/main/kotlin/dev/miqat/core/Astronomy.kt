package dev.miqat.core

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Solar geometry used by the prayer-time engine. Follows the standard algorithm used by
 * PrayTimes.org and most prayer apps (accurate to well under a minute at normal latitudes).
 */
internal object Astro {
    fun dsin(d: Double) = sin(Math.toRadians(d))
    fun dcos(d: Double) = cos(Math.toRadians(d))
    fun dtan(d: Double) = tan(Math.toRadians(d))
    fun darcsin(x: Double) = Math.toDegrees(asin(x))
    fun darccos(x: Double) = Math.toDegrees(acos(x))
    fun darctan2(y: Double, x: Double) = Math.toDegrees(atan2(y, x))
    fun darccot(x: Double) = Math.toDegrees(atan(1 / x))

    fun fixAngle(a: Double) = fix(a, 360.0)
    fun fixHour(a: Double) = fix(a, 24.0)
    private fun fix(a: Double, b: Double): Double {
        val r = a - b * floor(a / b)
        return if (r < 0) r + b else r
    }

    /** Julian date at 0h UT of the given calendar date. */
    fun julian(date: LocalDate): Double {
        var year = date.year
        var month = date.monthValue
        val day = date.dayOfMonth
        if (month <= 2) {
            year -= 1
            month += 12
        }
        val a = floor(year / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (year + 4716)) + floor(30.6001 * (month + 1)) + day + b - 1524.5
    }

    /** Sun declination (degrees) and equation of time (hours) for a Julian date. */
    fun sunPosition(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
        val e = 23.439 - 0.00000036 * d
        val ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0
        val eqt = q / 15.0 - fixHour(ra)
        val decl = darcsin(dsin(e) * dsin(l))
        return decl to eqt
    }

    /**
     * Local-mean-solar hours (0..24 from local mean midnight) when the sun reaches [angle] degrees
     * below the horizon, before (ccw = true) or after solar noon. NaN if it never gets there.
     */
    fun sunAngleTime(jDate: Double, lat: Double, angle: Double, approx: Double, ccw: Boolean): Double {
        val decl = sunPosition(jDate + approx / 24.0).first
        val noon = midDay(jDate, approx)
        val cosT = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))
        if (cosT < -1 || cosT > 1) return Double.NaN
        val t = darccos(cosT) / 15.0
        return noon + if (ccw) -t else t
    }

    fun midDay(jDate: Double, approx: Double): Double {
        val eqt = sunPosition(jDate + approx / 24.0).second
        return fixHour(12 - eqt)
    }

    fun asrTime(jDate: Double, lat: Double, shadowFactor: Int, approx: Double): Double {
        val decl = sunPosition(jDate + approx / 24.0).first
        val angle = -darccot(shadowFactor + dtan(abs(lat - decl)))
        return sunAngleTime(jDate, lat, angle, approx, ccw = false)
    }
}
