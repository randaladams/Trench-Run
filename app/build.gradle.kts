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

        // ---- AdMob. These are Google's public TEST ids, safe to click during development.
        // Before publishing, replace them with the ids from your own AdMob account.
        manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
        buildConfigField("String", "AD_BANNER_ID", "\"ca-app-pub-3940256099942544/6300978111\"")
        buildConfigField("String", "AD_INTERSTITIAL_ID", "\"ca-app-pub-3940256099942544/1033173712\"")
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

dependencies {
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")
}
