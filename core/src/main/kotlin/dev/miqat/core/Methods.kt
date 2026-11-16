package dev.miqat.core

enum class Prayer(val label: String, val isPrayer: Boolean, val code: String) {
    FAJR("Fajr", true, "FJR"),
    SUNRISE("Sunrise", false, "SUN"),
    DHUHR("Dhuhr", true, "DHR"),
    ASR("Asr", true, "ASR"),
    MAGHRIB("Maghrib", true, "MGB"),
    ISHA("Isha", true, "ISH");

    companion object {
        val prayers: List<Prayer> = entries.filter { it.isPrayer }
    }
}

enum class Madhab(val label: String, val shadowFactor: Int) {
    STANDARD("Standard (Shafi'i, Maliki, Hanbali)", 1),
    HANAFI("Hanafi", 2),
}

/** Minute offsets a calculation authority applies on top of the astronomical times. */
data class Adjustments(
    val fajr: Int = 0,
    val sunrise: Int = 0,
    val dhuhr: Int = 0,
    val asr: Int = 0,
    val maghrib: Int = 0,
    val isha: Int = 0,
) {
    fun of(p: Prayer) = when (p) {
        Prayer.FAJR -> fajr
        Prayer.SUNRISE -> sunrise
        Prayer.DHUHR -> dhuhr
        Prayer.ASR -> asr
        Prayer.MAGHRIB -> maghrib
        Prayer.ISHA -> isha
    }
}

/**
 * Calculation conventions. Isha is either an angle ([ishaAngle]) or a fixed interval after
 * Maghrib ([ishaMinutes]). Values follow the published parameters of each authority, matching
 * the widely used Adhan / PrayTimes libraries.
 */
enum class CalcMethod(
    val label: String,
    val fajrAngle: Double,
    val ishaAngle: Double? = null,
    val ishaMinutes: Int = 0,
    val ramadanIshaMinutes: Int? = null,
    val maghribAngle: Double? = null,
    val adjustments: Adjustments = Adjustments(),
) {
    ISNA("ISNA (North America)", 15.0, ishaAngle = 15.0),
    MWL("Muslim World League", 18.0, ishaAngle = 17.0),
    UMM_AL_QURA("Umm al-Qura, Makkah", 18.5, ishaMinutes = 90, ramadanIshaMinutes = 120),
    EGYPTIAN("Egyptian General Authority", 19.5, ishaAngle = 17.5),
    KARACHI("Univ. of Islamic Sciences, Karachi", 18.0, ishaAngle = 18.0),
    DUBAI(
        "Dubai", 18.2, ishaAngle = 18.2,
        adjustments = Adjustments(sunrise = -3, dhuhr = 3, asr = 3, maghrib = 3),
    ),
    QATAR("Qatar", 18.0, ishaMinutes = 90),
    KUWAIT("Kuwait", 18.0, ishaAngle = 17.5),
    TURKEY(
        "Diyanet, Turkey", 18.0, ishaAngle = 17.0,
        adjustments = Adjustments(sunrise = -7, dhuhr = 5, asr = 4, maghrib = 7),
    ),
    SINGAPORE(
        "MUIS / JAKIM / Kemenag (SE Asia)", 20.0, ishaAngle = 18.0,
        adjustments = Adjustments(dhuhr = 1),
    ),
    TEHRAN("Institute of Geophysics, Tehran", 17.7, ishaAngle = 14.0, maghribAngle = 4.5),
    ALGERIA("Algeria", 18.0, ishaAngle = 17.0, adjustments = Adjustments(maghrib = 3)),
    MOROCCO("Morocco", 19.0, ishaAngle = 17.0, adjustments = Adjustments(maghrib = 5)),
    TUNISIA("Tunisia", 18.0, ishaAngle = 18.0),
    FRANCE("UOIF, France (12°)", 12.0, ishaAngle = 12.0),
    RUSSIA("Spiritual Administration, Russia", 16.0, ishaAngle = 15.0),
}

/** Picks what local mosques in a country typically follow. Users can always override. */
object RegionalDefaults {
    private val methodByCountry: Map<String, CalcMethod> = buildMap {
        listOf("US", "CA", "MX", "PR").forEach { put(it, CalcMethod.ISNA) }
        listOf("SA", "BH", "YE").forEach { put(it, CalcMethod.UMM_AL_QURA) }
        put("AE", CalcMethod.DUBAI)
        put("QA", CalcMethod.QATAR)
        put("KW", CalcMethod.KUWAIT)
        listOf("EG", "SD", "SS", "LY", "SY", "LB", "IQ").forEach { put(it, CalcMethod.EGYPTIAN) }
        listOf("PK", "IN", "BD", "AF", "NP", "LK").forEach { put(it, CalcMethod.KARACHI) }
        put("TR", CalcMethod.TURKEY)
        listOf("SG", "MY", "ID", "BN").forEach { put(it, CalcMethod.SINGAPORE) }
        put("IR", CalcMethod.TEHRAN)
        put("DZ", CalcMethod.ALGERIA)
        put("MA", CalcMethod.MOROCCO)
        put("TN", CalcMethod.TUNISIA)
        put("FR", CalcMethod.FRANCE)
        put("RU", CalcMethod.RUSSIA)
    }

    private val hanafiCountries = setOf(
        "PK", "IN", "BD", "AF", "TR", "RU", "KZ", "UZ", "TJ", "KG", "TM", "BA", "AL", "XK", "MK",
    )

    fun method(countryCode: String?): CalcMethod =
        methodByCountry[countryCode?.uppercase()] ?: CalcMethod.MWL

    fun madhab(countryCode: String?): Madhab =
        if (countryCode?.uppercase() in hanafiCountries) Madhab.HANAFI else Madhab.STANDARD
}
