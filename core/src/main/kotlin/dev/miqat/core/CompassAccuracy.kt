package dev.miqat.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Rough "how far off could the heading be" estimate, in degrees, so the user can decide whether to
 * calibrate. It combines:
 *  - the magnetometer's calibration status (0 unreliable .. 3 high),
 *  - the device's own heading-error estimate, when it's believable (some watches report a flat 90°),
 *  - magnetic interference: how far the measured field strength is from what Earth's field should
 *    be here (from the World Magnetic Model). Steel, magnets and electronics distort both.
 * It's a heuristic, not a measurement; the UI says "about".
 */
object CompassAccuracy {
    enum class Quality(val label: String) { GOOD("Good"), FAIR("Fair"), POOR("Calibrate") }

    fun estimateDegrees(status: Int, deviceErrorDeg: Double?, measuredFieldUt: Double?, expectedFieldUt: Double): Double {
        val fromStatus = when (status) {
            3 -> 5.0
            2 -> 10.0
            1 -> 20.0
            else -> 45.0
        }
        // Trust the device estimate only in a plausible range.
        val base = deviceErrorDeg?.takeIf { it > 0.5 && it < 60 }?.let { max(it, 2.0) } ?: fromStatus
        val interference = interference(measuredFieldUt, expectedFieldUt)
        // Small deviations are normal noise; beyond 10% they skew the heading noticeably.
        val penalty = if (interference > 0.10) (interference - 0.10) * 100 else 0.0
        return min(base + penalty, 90.0)
    }

    /** Fractional deviation of the measured field strength from Earth's expected field here. */
    fun interference(measuredFieldUt: Double?, expectedFieldUt: Double): Double =
        if (measuredFieldUt == null || expectedFieldUt <= 0) 0.0 else abs(measuredFieldUt - expectedFieldUt) / expectedFieldUt

    fun quality(degrees: Double): Quality = when {
        degrees <= 8 -> Quality.GOOD
        degrees <= 15 -> Quality.FAIR
        else -> Quality.POOR
    }
}
