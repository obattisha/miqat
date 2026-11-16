package dev.miqat.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

object Qibla {
    const val KAABA_LAT = 21.422487
    const val KAABA_LNG = 39.826206

    /** Great-circle initial bearing from [c] to the Kaaba, degrees clockwise from true north. */
    fun bearing(c: Coordinates): Double {
        val phi = Math.toRadians(c.latitude)
        val phiK = Math.toRadians(KAABA_LAT)
        val dLambda = Math.toRadians(KAABA_LNG - c.longitude)
        val y = sin(dLambda)
        val x = cos(phi) * tan(phiK) - sin(phi) * cos(dLambda)
        return normalize(Math.toDegrees(atan2(y, x)))
    }

    fun distanceKm(c: Coordinates): Double = haversineKm(c, Coordinates(KAABA_LAT, KAABA_LNG))

    fun compassPoint(deg: Double): String {
        val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return points[((normalize(deg) + 22.5) / 45.0).toInt() % 8]
    }

    /** Signed smallest difference target - current, in (-180, 180]. */
    fun delta(target: Double, current: Double): Double {
        var d = normalize(target - current)
        if (d > 180) d -= 360
        return d
    }

    fun normalize(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }
}

fun haversineKm(a: Coordinates, b: Coordinates): Double {
    val r = 6371.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLng = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * r * asin(sqrt(h))
}
