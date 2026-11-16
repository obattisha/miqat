plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.miqat.wear"
    compileSdk = 37
    defaultConfig {
        // Same applicationId as the phone app: required for Data Layer sync.
        applicationId = "dev.miqat"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    buildTypes {
        release {
            // Optimized build: much smoother than debug, especially on the watch.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Personal sideload: reuse the debug key so phone and watch stay signed alike
            // (the Data Layer only syncs apps with matching signatures).
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(project(":platform"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    // Material 3 Expressive for Wear OS.
    implementation("androidx.wear.compose:compose-material3:1.7.1")
    implementation("androidx.wear.compose:compose-foundation:1.7.1")
    implementation("androidx.wear.compose:compose-navigation:1.7.1")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.3.0")
    implementation("androidx.wear.protolayout:protolayout-expression:1.4.2")
    implementation("androidx.wear:wear-phone-interactions:1.0.1")
    // Tiles (swipe from the watch face)
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material3:1.4.2")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.3.0")
}
