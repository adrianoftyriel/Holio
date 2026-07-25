plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// On CI, GITHUB_RUN_NUMBER is set automatically and matches the release tag
// (v1.0.<run-number>), so the shipped APK knows its own version and the
// in-app updater can tell whether a newer release exists. Locally it's a dev build.
val ciRunNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "").toIntOrNull() ?: 0

android {
    namespace = "org.holio.game"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.holio.game"
        minSdk = 24
        targetSdk = 34
        versionCode = if (ciRunNumber > 0) ciRunNumber else 1
        versionName = if (ciRunNumber > 0) "1.0.$ciRunNumber" else "1.0.0-dev"
    }

    signingConfigs {
        // A fixed, checked-in debug keystore (standard "android" password) so
        // every build — local or CI — is signed with the same key. Without this,
        // each CI runner would generate a random debug key and updates would fail
        // to install with a signature mismatch.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // No ads, no analytics — a clean release build.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
