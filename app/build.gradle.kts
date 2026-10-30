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
        versionCode = 14
        versionName = "1.4.0"
    }


    // Fixed signing key so updates install over the old version and keep the pairing.
    signingConfigs {
        create("fixed") {
            storeFile = file("foldblur.keystore")
            storePassword = "foldblur"
            keyAlias = "foldblur"
            keyPassword = "foldblur"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
dependencies {
    // Talks to the phone's own Wireless debugging (like Shizuku does), to read exact hinge angle.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
}
