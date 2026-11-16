plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.miqat.mobile"
    compileSdk = 37
    defaultConfig {
        // Must match the watch app so the Wearable Data Layer pairs them.
        applicationId = "dev.miqat"
        minSdk = 26
        targetSdk = 35
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
    implementation("androidx.compose.material3:material3")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.glance:glance-material3:1.2.0")
}
