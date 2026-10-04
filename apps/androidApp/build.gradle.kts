plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// The app. Not "the Compose rewrite" any more — this is what ships.
//
// It carries :app's applicationId and is signed with the same key, so
// it upgrades the share-only build in place rather than sitting next to
// it. :app stays in the repo as the rollback: it still builds, and
// installing its APK puts the old one back.
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

    // One copy of the screen tests, run two ways.
    //
    // They are all `@RunWith(AndroidJUnit4::class)`, which delegates to
    // Robolectric on the JVM and to the device runner on a device, so
    // the same file is both suites. `testDebugUnitTest` is the one that
    // runs on every change, in under a minute; `connectedDebugAndroidTest`
    // is the same assertions against real hardware, with the screenshots.
    sourceSets {
        getByName("test").kotlin.srcDir("src/sharedTest/kotlin")
        getByName("androidTest").kotlin.srcDir("src/sharedTest/kotlin")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Not `graphicsMode = NATIVE`. It was here so the JVM run
            // could capture pixels, and it crashed the test executor
            // with SIGABRT partway through a run — green once, dead
            // the next time. Nothing on the JVM needs real pixels any
            // more: every test that reads them is marked
            // `needsRealRendering` and runs on a device.
            all {
                it.maxHeapSize = "2g"
                it.testLogging { events("failed") }
            }
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

    // The same Compose test API, on the JVM. A test that mounts one
    // Text and asserts it costs about 2.7 seconds on an emulator —
    // almost all of it launching an activity — which is most of what
    // the device suite spends its time on.
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.7.6")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(kotlin("test"))
    // For MainActivityFacetsTest: a fake network, and a Main dispatcher
    // that runs eagerly instead of posting to Robolectric's looper.
    testImplementation("io.ktor:ktor-client-mock:3.0.3")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    testImplementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
