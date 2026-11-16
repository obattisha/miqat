package dev.miqat.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reference values: api.aladhan.com for 2026-11-10, in UTC. Asr for London and Stockholm uses an
 * independent shadow-length calculation (aladhan runs 2-3 min late at high latitudes).
 */
class PrayerEngineTest {
    private val date = LocalDate.of(2026, 11, 10)

    private fun check(lat: Double, lng: Double, method: CalcMethod, madhab: Madhab, expected: List<String>, tol: Long = 1) {
        val t = PrayerEngine.compute(date, Coordinates(lat, lng), CalcParams(method, madhab))
        Prayer.entries.zip(expected).forEach { (p, hhmm) ->
            val (h, m) = hhmm.split(":").map { it.toInt() }
            val actual = t[p].atZone(ZoneOffset.UTC)
            // Times east of UTC can land on the previous UTC day.
            val actualMin = actual.hour * 60 + actual.minute
            val diff = abs(actualMin - (h * 60 + m)).let { minOf(it, 1440 - it) }
            assertTrue(diff <= tol, "$method $p expected $hhmm got ${actual.toLocalTime()}")
        }
    }

    @Test fun newYorkIsna() = check(40.7128, -74.0060, CalcMethod.ISNA, Madhab.STANDARD,
        listOf("10:19", "11:37", "16:40", "19:21", "21:42", "23:00"))

    @Test fun makkahUmmAlQura() = check(21.4225, 39.8262, CalcMethod.UMM_AL_QURA, Madhab.STANDARD,
        listOf("02:10", "03:29", "09:05", "12:18", "14:40", "16:10"))

    @Test fun londonMwl() = check(51.5074, -0.1278, CalcMethod.MWL, Madhab.STANDARD,
        listOf("05:14", "07:10", "11:44", "13:54", "16:18", "18:08"))

    @Test fun karachiHanafi() = check(24.8607, 67.0011, CalcMethod.KARACHI, Madhab.HANAFI,
        listOf("00:26", "01:45", "07:16", "11:11", "12:47", "14:05"))

    @Test fun cairoEgyptian() = check(30.0444, 31.2357, CalcMethod.EGYPTIAN, Madhab.STANDARD,
        listOf("02:47", "04:16", "09:39", "12:41", "15:02", "16:21"))

    @Test fun stockholmHighLatitude() = check(59.3293, 18.0686, CalcMethod.MWL, Madhab.STANDARD,
        listOf("04:05", "06:29", "10:32", "12:09", "14:33", "16:49"), tol = 2)

    @Test fun midsummerFarNorthStillProducesOrderedTimes() {
        val t = PrayerEngine.compute(LocalDate.of(2026, 6, 21), Coordinates(64.0, -21.9), CalcParams(CalcMethod.MWL, Madhab.STANDARD))
        val order = Prayer.entries.map { t[it] }
        assertEquals(order.sorted(), order)
    }

    @Test fun qiblaBearings() {
        assertEquals(58.5, Qibla.bearing(Coordinates(40.7128, -74.0060)), 0.5) // New York
        assertEquals(119.0, Qibla.bearing(Coordinates(51.5074, -0.1278)), 0.5) // London
        assertEquals("NE", Qibla.compassPoint(58.5))
    }

    @Test fun defaultsByCountry() {
        assertEquals(CalcMethod.ISNA, RegionalDefaults.method("US"))
        assertEquals(CalcMethod.UMM_AL_QURA, RegionalDefaults.method("sa"))
        assertEquals(CalcMethod.MWL, RegionalDefaults.method(null))
        assertEquals(Madhab.HANAFI, RegionalDefaults.madhab("PK"))
    }

    @Test fun windowRollsOverAfterIsha() {
        val loc = SavedLocation(40.7128, -74.0060, "New York", "US", 0)
        val zone = ZoneId.of("America/New_York")
        val late = date.atTime(23, 30).atZone(zone).toInstant()
        val (prev, next) = Schedule.window(loc, CalcParams(CalcMethod.ISNA, Madhab.STANDARD), late, zone)
        assertEquals(Prayer.ISHA, prev.prayer)
        assertEquals(Prayer.FAJR, next.prayer)
        assertTrue(next.time > late)
    }

    @Test fun remindersPlanBeforeAndAtTime() {
        val loc = SavedLocation(40.7128, -74.0060, "New York", "US", 0)
        val zone = ZoneId.of("America/New_York")
        val now = date.atTime(12, 0).atZone(zone).toInstant()
        val events = Schedule.events(loc, CalcParams(CalcMethod.ISNA, Madhab.STANDARD), now, 1, zone)
        val plan = Reminders.plan(events, Settings(reminderMinutes = 15, notifyBefore = setOf(Prayer.ASR)), now, 50)
        val asrBefore = plan.first { it.prayer == Prayer.ASR && it.kind == AlertKind.BEFORE }
        assertEquals(Duration.ofMinutes(15), Duration.between(asrBefore.fireAt, asrBefore.prayerTime))
        assertTrue(plan.none { it.prayer == Prayer.DHUHR && it.kind == AlertKind.BEFORE })
        assertTrue(plan.all { it.fireAt > now })
    }

    @Test fun settingsRoundTrip() {
        val s = Settings(CalcMethod.TURKEY, Madhab.HANAFI, setOf(Prayer.FAJR), emptySet(), 20, 123)
        assertEquals(s, Settings.decode(s.encode()))
        val l = SavedLocation(1.5, -2.25, "Kuala Lumpur", "MY", 99)
        assertEquals(l, SavedLocation.decode(l.encode()))
    }

    @Test fun compassAccuracyEstimate() {
        // Calibrated, clean field: tight.
        assertEquals(5.0, CompassAccuracy.estimateDegrees(3, null, 48.0, 48.0), 0.01)
        // Watch's placeholder 90° estimate is ignored; low status dominates.
        assertEquals(20.0, CompassAccuracy.estimateDegrees(1, 90.0, 48.0, 48.0), 0.01)
        // Plausible device estimate is used.
        assertEquals(6.0, CompassAccuracy.estimateDegrees(1, 6.0, 48.0, 48.0), 0.01)
        // 30% stronger field than Earth's here (metal nearby) adds ~20°.
        assertEquals(25.0, CompassAccuracy.estimateDegrees(3, null, 62.4, 48.0), 0.5)
        assertEquals(CompassAccuracy.Quality.POOR, CompassAccuracy.quality(25.0))
    }

    @Test fun hijriDate() {
        // Nov 2026 falls in AH 1448.
        val h = Hijri.format(LocalDate.of(2026, 11, 10))!!
        assertTrue(h.endsWith("1448"), h)
    }

    @Test fun countdownFormatting() {
        assertEquals("1h 23m", formatCountdown(Duration.ofMinutes(83)))
        assertEquals("1m", formatCountdown(Duration.ofSeconds(5)))
        assertEquals("2h", formatCountdown(Duration.ofHours(2)))
        assertEquals(Instant.EPOCH, Instant.EPOCH)
    }
}
