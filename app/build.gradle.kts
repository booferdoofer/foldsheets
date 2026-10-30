plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.foldblur"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.foldblur"
        minSdk = 31          // window blur APIs need Android 12+
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
// No dependencies beyond the Kotlin standard library.
