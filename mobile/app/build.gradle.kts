plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yahyapro20.dshmobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yahyapro20.dshmobile"
        minSdk = 29
        targetSdk = 34
        versionCode = 5
        versionName = "0.5.0"
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("CI_KEYSTORE_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("CI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CI_KEY_ALIAS")
                keyPassword = System.getenv("CI_KEY_PASSWORD") // حذف .orEmpty() برای جلوگیری از خطای Missing property
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ksPath = System.getenv("CI_KEYSTORE_PATH")
            signingConfig = if (ksPath != null) {
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
