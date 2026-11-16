package dev.miqat.core

import java.time.LocalDate
import java.time.chrono.HijrahChronology
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Hijri date using the Umm al-Qura calendar (java.time's HijrahChronology). */
object Hijri {
    private val full = DateTimeFormatter.ofPattern("d MMMM y", Locale.ENGLISH).withChronology(HijrahChronology.INSTANCE)
    private val short = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH).withChronology(HijrahChronology.INSTANCE)

    /** "19 Jumada I 1448", or null if the date is outside the calendar's supported range. */
    fun format(date: LocalDate): String? = runCatching { full.format(HijrahDate.from(date)) }.getOrNull()

    /** "19 Jumada I" */
    fun formatShort(date: LocalDate): String? = runCatching { short.format(HijrahDate.from(date)) }.getOrNull()
}
