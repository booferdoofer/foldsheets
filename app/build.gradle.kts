plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.foldsheet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.foldsheet"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Fixed signing key, so a newer build installs over the old one
    // (and keeps your sheet data) instead of needing an uninstall.
    signingConfigs {
        getByName("debug") {
            storeFile = file("foldsheet.keystore")
            storePassword = "foldsheet"
            keyAlias = "foldsheet"
            keyPassword = "foldsheet"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Home-screen widget (Jetpack Glance)
    implementation("androidx.glance:glance-appwidget:1.1.1")
}
