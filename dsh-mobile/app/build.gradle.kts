plugins {
    id("com.android.application")
}

android {
    namespace = "com.dshmobile.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dshmobile.app"
        minSdk = 26
        // targetSdk must be ≤ 28: Android 10+ enables SELinux domain
        // restrictions, prohibiting exec of binaries in private directories (neither proot nor node can run), same applies to Termux.
        // This is a hard conflict, so Android 16 (API 36) adaptation takes a different path:
        // Keep targetSdk at 28 (Android 16 can still be installed and run normally), and request all permissions explicitly
        // (POST_NOTIFICATIONS / MANAGE_EXTERNAL_STORAGE / foreground service declaration),
        // 16KB page devices are detected and warned by BootstrapInstaller during installation.
        targetSdk = 28
        versionCode = 29
        versionName = "1.0.28"
    }

    lint {
        abortOnError = false
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }

    signingConfigs {
        create("release") {
            storeFile = file("../dsh-mobile.keystore")
            storePassword = "dshmobile2026"
            keyAlias = "dshmobile"
            keyPassword = "dshmobile2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
}
