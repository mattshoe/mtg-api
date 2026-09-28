plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.mattshoe.mtg.share"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.mattshoe.mtg.share"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "org.mattshoe.mtg.share.ArgRunner"
    }

    // Kept outside the repo, in ~/.mtg-android.env, so the key and its
    // password are never in git. Without it the release build is simply
    // unsigned rather than broken.
    val creds = file(System.getProperty("user.home") + "/.mtg-android.env")
        .takeIf { it.exists() }
        ?.readLines()
        ?.mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 } }
        ?.associate { it[0].trim() to it[1].trim() }
        .orEmpty()

    signingConfigs {
        if (creds.containsKey("MTG_ANDROID_KEYSTORE")) {
            create("release") {
                storeFile = file(creds.getValue("MTG_ANDROID_KEYSTORE"))
                storePassword = creds.getValue("MTG_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = creds.getValue("MTG_ANDROID_KEY_ALIAS")
                keyPassword = creds.getValue("MTG_ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("androidTest").java.srcDirs("src/androidTest/kotlin")
    }

    // The instrumentation tests use the framework's own runner and
    // assertions, so the app carries no third-party dependencies —
    // nothing to resolve, nothing to go stale, nothing to break a build at
    // two in the morning.
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
}

dependencies {
    // The decklist rules, shared with the web and with the multiplatform
    // build, rather than a third private copy of them. :core deliberately
    // has no HTTP stack in it, so this stays a small app.
    implementation(project(":core"))
}
