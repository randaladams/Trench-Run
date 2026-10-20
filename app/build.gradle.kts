plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- AdMob ids
// Your real ids from admob.google.com (Apps > Trench Run > App settings / Ad units).
// Leave empty until created; they are only used by the Play Store (release) build.
val REAL_APP_ID = ""            // looks like ca-app-pub-1234567890123456~1234567890
val REAL_BANNER_ID = ""         // looks like ca-app-pub-1234567890123456/1234567890
val REAL_INTERSTITIAL_ID = ""   // looks like ca-app-pub-1234567890123456/1234567890
val REAL_REWARDED_ID = ""       // looks like ca-app-pub-1234567890123456/1234567890

// Google's public test ids (always safe to show and tap).
val TEST_APP_ID = "ca-app-pub-3940256099942544~3347511713"
val TEST_BANNER_ID = "ca-app-pub-3940256099942544/6300978111"
val TEST_INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712"
val TEST_REWARDED_ID = "ca-app-pub-3940256099942544/5224354917"

android {
    namespace = "com.randaladams.trenchrun"
    compileSdk = 35

    defaultConfig {
        // Play Store package name (permanent once published)
        applicationId = "com.adamselite.trenchrun"
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
        // Test builds (the download link) ALWAYS use Google's test ads, so tapping them on your own
        // phone can never get your AdMob account flagged for invalid clicks.
        debug {
            signingConfig = signingConfigs.getByName("debug")
            manifestPlaceholders["admobAppId"] = TEST_APP_ID
            buildConfigField("String", "AD_BANNER_ID", "\"$TEST_BANNER_ID\"")
            buildConfigField("String", "AD_INTERSTITIAL_ID", "\"$TEST_INTERSTITIAL_ID\"")
            buildConfigField("String", "AD_REWARDED_ID", "\"$TEST_REWARDED_ID\"")
        }
        // The Play Store build uses your real AdMob ids (falls back to test ids until they're filled in).
        release {
            isMinifyEnabled = false
            manifestPlaceholders["admobAppId"] = REAL_APP_ID.ifEmpty { TEST_APP_ID }
            buildConfigField("String", "AD_BANNER_ID", "\"${REAL_BANNER_ID.ifEmpty { TEST_BANNER_ID }}\"")
            buildConfigField("String", "AD_INTERSTITIAL_ID", "\"${REAL_INTERSTITIAL_ID.ifEmpty { TEST_INTERSTITIAL_ID }}\"")
            buildConfigField("String", "AD_REWARDED_ID", "\"${REAL_REWARDED_ID.ifEmpty { TEST_REWARDED_ID }}\"")
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
