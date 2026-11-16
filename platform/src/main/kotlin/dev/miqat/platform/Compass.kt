package dev.miqat.platform

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.miqat.core.CompassAccuracy
import dev.miqat.core.Qibla
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Heading relative to TRUE north (magnetic declination already applied). [tiltDegrees] is how far
 * the screen is from lying flat (0 = flat). [accuracyDeg] is an estimate of how far off the heading
 * could be (see [CompassAccuracy]); [interference] is the fractional deviation of the local magnetic
 * field from Earth's expected field here.
 */
data class CompassReading(
    val heading: Float,
    val needsCalibration: Boolean,
    val tiltDegrees: Float = 0f,
    val accuracyDeg: Float = 0f,
    val interference: Float = 0f,
) {
    val quality: CompassAccuracy.Quality get() = CompassAccuracy.quality(accuracyDeg.toDouble())
}

/**
 * Fused rotation-vector heading, corrected for magnetic declination at the user's location so
 * it lines up with the true-north qibla bearing. Sensors only run while the compass is on screen.
 */
fun compassFlow(ctx: Context, latitude: Double, longitude: Double): Flow<CompassReading> = callbackFlow {
    val sm = ctx.getSystemService(SensorManager::class.java)
    val rotation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    val magnetic = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    val geo = GeomagneticField(latitude.toFloat(), longitude.toFloat(), 0f, System.currentTimeMillis())
    val declination = geo.declination
    val expectedFieldUt = geo.fieldStrength / 1000.0 // nT -> µT

    val matrix = FloatArray(9)
    val orientation = FloatArray(3)
    var magAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    var fieldUt: Double? = null
    var accuracyAvg = Double.NaN
    var sinAvg = 0.0
    var cosAvg = 1.0
    var initialized = false

    val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (e.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                val (x, y, z) = Triple(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
                val m = sqrt(x * x + y * y + z * z)
                fieldUt = fieldUt?.let { it + 0.2 * (m - it) } ?: m
                return
            }
            SensorManager.getRotationMatrixFromVector(matrix, e.values)
            SensorManager.getOrientation(matrix, orientation)
            val raw = Math.toDegrees(orientation[0].toDouble()) + declination
            // Low-pass filter on the unit circle (avoids the 359°→0° jump).
            val a = if (initialized) 0.15 else 1.0
            sinAvg += a * (sin(Math.toRadians(raw)) - sinAvg)
            cosAvg += a * (cos(Math.toRadians(raw)) - cosAvg)
            initialized = true
            val heading = Qibla.normalize(Math.toDegrees(atan2(sinAvg, cosAvg))).toFloat()

            // values[4] = the device's own heading-accuracy estimate (radians), if it has one.
            val deviceErr = if (e.values.size > 4 && e.values[4] > 0) Math.toDegrees(e.values[4].toDouble()) else null
            val estimate = CompassAccuracy.estimateDegrees(magAccuracy, deviceErr, fieldUt, expectedFieldUt)
            // Smooth so the number doesn't flicker.
            accuracyAvg = if (accuracyAvg.isNaN()) estimate else accuracyAvg + 0.1 * (estimate - accuracyAvg)

            // Only call it "needs calibrating" when the magnetometer says it's unusable. Watches in
            // particular sit at "low" much of the time while still pointing correctly.
            val unreliable = magAccuracy <= SensorManager.SENSOR_STATUS_UNRELIABLE
            val tilt = maxOf(abs(Math.toDegrees(orientation[1].toDouble())), abs(Math.toDegrees(orientation[2].toDouble())))
            trySend(
                CompassReading(
                    heading = heading,
                    needsCalibration = unreliable,
                    tiltDegrees = tilt.toFloat(),
                    accuracyDeg = accuracyAvg.toFloat(),
                    interference = CompassAccuracy.interference(fieldUt, expectedFieldUt).toFloat(),
                ),
            )
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) magAccuracy = accuracy
        }
    }
    rotation?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
    magnetic?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
    awaitClose { sm.unregisterListener(listener) }
}

/** One firm tap when you turn to face the qibla; re-arms once you turn away. */
class AlignmentHaptics(ctx: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
    }
    private var aligned = false

    /** Returns whether currently aligned. */
    fun update(offsetDegrees: Double): Boolean {
        val d = abs(offsetDegrees)
        if (!aligned && d <= ALIGN_DEG) {
            aligned = true
            buzz()
        } else if (aligned && d > RELEASE_DEG) {
            aligned = false
        }
        return aligned
    }

    private fun buzz() {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    companion object {
        const val ALIGN_DEG = 3.0
        const val RELEASE_DEG = 8.0
    }
}

data class DialColors(
    val ring: Color,
    val ticks: Color,
    val north: Color,
    val pointer: Color,
    val kaaba: Color,
    val aligned: Color,
)

/**
 * Live compass dial shared by phone and watch, in the same design as the Qibla tile: hairline ring
 * near the edge, 15° ticks, N/E/S/W inside the ring, and the qibla as a line ending at the Kaaba.
 * The centre is left clear for a readout. The dial turns with the device so "up" is where it
 * points; a small index at the top marks that direction.
 */
@Composable
fun QiblaDial(heading: Float, qibla: Double, aligned: Boolean, colors: DialColors, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val s = min(size.width, size.height)
        val r = s / 2f * 0.94f
        val u = s / 200f
        val c = center
        val accent = if (aligned) colors.aligned else colors.pointer
        val b = Qibla.normalize(qibla)

        // Index at the very top: the direction you're facing.
        val idx = Path().apply {
            moveTo(c.x, c.y - r + 13 * u)
            lineTo(c.x - 4.5f * u, c.y - r + 21 * u)
            lineTo(c.x + 4.5f * u, c.y - r + 21 * u)
            close()
        }

        rotate(-heading, c) {
            drawCircle(if (aligned) colors.aligned else colors.ring, radius = r, center = c, style = Stroke(width = (if (aligned) 2.4f else 1.2f) * u))
            for (deg in 0 until 360 step 15) {
                val major = deg % 90 == 0
                rotate(deg.toFloat(), c) {
                    drawLine(
                        color = if (deg == 0) colors.north else colors.ticks.copy(alpha = if (major) 1f else 0.55f),
                        start = Offset(c.x, c.y - r + 2 * u),
                        end = Offset(c.x, c.y - r + (if (major) 10 else 6) * u),
                        strokeWidth = (if (major) 2f else 1f) * u,
                        cap = StrokeCap.Round,
                    )
                }
            }
            // Letters inside the ring, upright. N slides aside if the qibla sits on top of it.
            listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f).forEach { (label, deg) ->
                val shift = if (label == "N" && (b < 25 || b > 335)) (if (b < 25) -18f else 18f) else 0f
                val layout = measurer.measure(
                    label,
                    TextStyle(
                        color = if (label == "N") colors.north else colors.ticks,
                        fontSize = (11f * u / density).sp,
                        fontWeight = if (label == "N") FontWeight.Bold else FontWeight.Normal,
                    ),
                )
                val a = Math.toRadians((deg + shift).toDouble())
                val rr = r - 21 * u
                val px = c.x + (rr * sin(a)).toFloat()
                val py = c.y - (rr * cos(a)).toFloat()
                rotate(heading, Offset(px, py)) {
                    drawText(layout, topLeft = Offset(px - layout.size.width / 2f, py - layout.size.height / 2f))
                }
            }
            // Qibla: from just outside the centre readout to the Kaaba.
            rotate(qibla.toFloat(), c) {
                drawLine(accent, Offset(c.x, c.y - r * 0.42f), Offset(c.x, c.y - r * 0.78f), strokeWidth = 2.5f * u, cap = StrokeCap.Round)
                val k = 14 * u
                val top = c.y - r * 0.78f - k
                drawRoundRect(accent, Offset(c.x - k / 2, top), Size(k, k), CornerRadius(k * 0.18f))
                drawRect(colors.north, Offset(c.x - k / 2, top + k * 0.26f), Size(k, k * 0.12f))
            }
        }
        drawPath(idx, accent)
    }
}
