plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// The app. The only one — the share-only build it replaced has been
// deleted, having been unreachable since this took its applicationId:
// two modules cannot both be installed under one id, so it was never
// the rollback it was kept as, just a second copy of the icon and the
// share intents that nothing could run.
//
// The id stays `…mtg.share` and the signing key stays the same, so an
// install from back then still upgrades in place.
android {
    namespace = "org.mattshoe.mtg.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.mattshoe.mtg.share"
        minSdk = 26
        targetSdk = 35
        // CI hands in a code that only ever goes up, because Android
        // refuses to install a build whose code is not higher than
        // the one already on the phone. Locally it stays put.
        versionCode = (System.getenv("MTG_VERSION_CODE") ?: "4").toInt()
        versionName = System.getenv("MTG_VERSION_NAME") ?: "2.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Kept outside the repo, in ~/.mtg-android.env, so the key and its
    // password are never in git. Android refuses to upgrade an app
    // signed with a different key, so this has to be the same one :app
    // was signed with.
    val creds = file(System.getProperty("user.home") + "/.mtg-android.env")
        .takeIf { it.exists() }
        ?.readLines()
        ?.mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 } }
        ?.associate { it[0].trim() to it[1].trim() }
        .orEmpty()
        // On a build machine there is no home file. The same three
        // values arrive as environment variables instead, out of the
        // repository secrets — and it has to be the *same* key, or
        // every phone with the app on it refuses the update.
        .ifEmpty {
            val store = System.getenv("MTG_ANDROID_KEYSTORE")
            if (store.isNullOrBlank()) {
                emptyMap()
            } else {
                mapOf(
                    "MTG_ANDROID_KEYSTORE" to store,
                    "MTG_ANDROID_KEYSTORE_PASSWORD" to System.getenv("MTG_ANDROID_KEYSTORE_PASSWORD").orEmpty(),
                    "MTG_ANDROID_KEY_ALIAS" to System.getenv("MTG_ANDROID_KEY_ALIAS").orEmpty(),
                )
            }
        }

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
            // Deliberately not minified and not obfuscated. This is
            // sideloaded through Obtainium by the one person who uses
            // it; there is nothing to hide and a readable stack trace
            // out of a real crash is worth more than a smaller file.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // FlowRow. Chips that wrap are the whole point of it, and
        // writing the wrapping by hand to avoid an opt-in would be
        // worse code than the opt-in.
        freeCompilerArgs += "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi"
    }
    buildFeatures { compose = true }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("androidTest").java.srcDirs("src/androidTest/kotlin")
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/LICENSE.md",
            "META-INF/LICENSE-notice.md",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":core-net"))
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // Card art. The web gets it from an <img>; Compose has no loader of
    // its own, and a grid of Magic cards without the pictures is not the
    // same screen.
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    // The screens are tested on a device, clicked, because "the port is
    // done" is a claim about what a person can do with the app.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.6")
    androidTestImplementation(kotlin("test"))
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.6")
}
