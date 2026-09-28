plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "by.dzianis.budspopup"
    compileSdk = 35

    defaultConfig {
        applicationId = "by.dzianis.budspopup"
        minSdk = 31          // Android 12+: BLUETOOTH_CONNECT runtime permission model
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug keystore so "Build > Build APK" gives an installable release APK out of the box
            signingConfig = signingConfigs.getByName("debug")
        }
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
    // No external dependencies on purpose: plain Android SDK only.
    testImplementation("junit:junit:4.13.2")
}
