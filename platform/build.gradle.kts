plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.miqat.platform"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    // Apps that consume this library target 34+; lets lint judge USE_EXACT_ALARM correctly.
    lint { targetSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

dependencies {
    api(project(":core"))
    api("androidx.core:core-ktx:1.19.1")
    api("androidx.datastore:datastore-preferences:1.3.0")
    api("androidx.work:work-runtime-ktx:2.12.0")
    api("com.google.android.gms:play-services-wearable:19.0.0")
    api("com.google.android.gms:play-services-location:21.3.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    api(platform("androidx.compose:compose-bom:2026.09.00"))
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.ui:ui")
}
