package dev.miqat.platform

/**
 * Miqat's palette, taken from the app icon: deep emerald with a gold band. Used everywhere
 * (phone, watch, widget, tiles) instead of wallpaper colors, so the app has its own character.
 * Values are ARGB, usable from Compose (`Color(Brand.X)`) and ProtoLayout (`.toInt()`).
 */
object Brand {
    // Emerald
    const val EMERALD_950 = 0xFF061F18
    const val EMERALD_900 = 0xFF0B3B2E
    const val EMERALD_800 = 0xFF0F4C3A // the icon
    const val EMERALD_700 = 0xFF155E48
    const val EMERALD_600 = 0xFF1B7A62
    const val EMERALD_400 = 0xFF3FA98A
    const val EMERALD_300 = 0xFF7FD3B4
    const val EMERALD_200 = 0xFFA9E5CD
    const val EMERALD_100 = 0xFFCFEDE1
    const val EMERALD_50 = 0xFFEAF7F1

    // Gold (the band on the icon)
    const val GOLD_700 = 0xFF8A6A12
    const val GOLD_500 = 0xFFD4AF37
    const val GOLD_300 = 0xFFE8CF7A
    const val GOLD_100 = 0xFFF8EBC4

    // Neutrals with a slight green cast, so greys don't look generic
    const val INK = 0xFF0C1310
    const val NIGHT_1 = 0xFF121A16
    const val NIGHT_2 = 0xFF18221E
    const val NIGHT_3 = 0xFF1F2B26
    const val NIGHT_4 = 0xFF27352F
    const val MIST = 0xFFE6EEE9
    const val MIST_DIM = 0xFFA8B8AF
    const val PARCHMENT = 0xFFF8F6EF
    const val PARCHMENT_2 = 0xFFF0EDE3
    const val PARCHMENT_3 = 0xFFE8E4D7
    const val PARCHMENT_4 = 0xFFDFDACB
}
