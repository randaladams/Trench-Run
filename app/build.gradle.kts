plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.randaladams.trenchrun"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.randaladams.trenchrun"
        minSdk = 26
        targetSdk = 35
        // CI passes its run number so each build shows on the title screen and installs as an update
        val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        versionCode = 100 + buildNumber
        versionName = "0.2.$buildNumber"
    }

    // A fixed debug key is committed so every CI build installs over the previous one
    // without having to uninstall first. Replace with a real key before publishing.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
