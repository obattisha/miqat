plugins {
    id("com.android.application") version "9.5.1" apply false
    id("com.android.library") version "9.5.1" apply false
    // AGP 9 compiles Kotlin itself; these pin the Kotlin version it uses.
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
