plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- AdMob ids
// Your real ids from admob.google.com (Apps > Trench Run > App settings / Ad units).
// Only used by the Play Store (release) build. Test builds always use Google's test ids below.
val REAL_APP_ID = "ca-app-pub-7909301840077799~2840317063"
val REAL_BANNER_ID = "ca-app-pub-7909301840077799/4396941315"         // TrenchRun_Banner_Menus
val REAL_INTERSTITIAL_ID = "ca-app-pub-7909301840077799/8974033469"   // TrenchRun_Interstitial_BetweenRuns
val REAL_REWARDED_ID = "ca-app-pub-7909301840077799/1104563568"       // TrenchRun_Rewarded_Continue

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
        versionName = "1.0.0"
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
        // Google Play upload key. Only the Release workflow sets these (from GitHub Secrets);
        // the key file and passwords are never stored in the repository.
        create("release") {
            val ks = System.getenv("TR_KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("TR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TR_KEY_ALIAS")
                keyPassword = System.getenv("TR_KEY_PASSWORD")
            }
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
            // Pro is offered in test builds (Unlock Pro switches it on free) so it can be tried
            buildConfigField("boolean", "PRO_FOR_SALE", "true")
        }
        // The Play Store build uses your real AdMob ids (falls back to test ids until they're filled in).
        release {
            isMinifyEnabled = false
            if (System.getenv("TR_KEYSTORE_FILE") != null) signingConfig = signingConfigs.getByName("release")
            manifestPlaceholders["admobAppId"] = REAL_APP_ID.ifEmpty { TEST_APP_ID }
            buildConfigField("String", "AD_BANNER_ID", "\"${REAL_BANNER_ID.ifEmpty { TEST_BANNER_ID }}\"")
            buildConfigField("String", "AD_INTERSTITIAL_ID", "\"${REAL_INTERSTITIAL_ID.ifEmpty { TEST_INTERSTITIAL_ID }}\"")
            // 1.0.0 launches free only: no Go Pro, Zen or Pro badges until Google Play Billing is added
            buildConfigField("boolean", "PRO_FOR_SALE", "false")
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
