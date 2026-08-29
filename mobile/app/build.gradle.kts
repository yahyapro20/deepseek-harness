plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yahyapro20.dshmobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yahyapro20.dshmobile"
        minSdk = 29 // Android 10, matches the dev's own test device
        targetSdk = 34

        // Bump versionCode by 1 on every release you want to ship as an update.
        // Keeping the SAME applicationId + SAME signing key + a HIGHER versionCode
        // is exactly what makes a new APK install as an update over the old one.
        versionCode = 1
        versionName = "0.1.0-mvp"
    }

    signingConfigs {
        create("release") {
            // These come from environment variables injected by the GitHub Actions
            // workflow (.github/workflows/build-mobile.yml), which itself reads them
            // from repo Secrets. Never commit real values here.
            val ksPath = System.getenv("CI_KEYSTORE_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("CI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CI_KEY_ALIAS")
                keyPassword = System.getenv("CI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Falls back to the debug key automatically if CI env vars are absent,
            // so a local `gradle assembleRelease` without secrets still succeeds
            // (useful for quickly checking the build compiles).
            signingConfig = if (System.getenv("CI_KEYSTORE_PATH") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        viewBinding = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.apache.commons:commons-compress:1.26.1")
    implementation("org.tukaani:xz:1.9")
}
