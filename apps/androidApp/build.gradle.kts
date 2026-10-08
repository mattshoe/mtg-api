import java.time.Duration
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
        // The end-to-end harness runs the repository's own schema
        // and fixture against a real SQLite on the phone, so both
        // files are copied into the test APK rather than a second
        // copy of them living here and drifting. See `e2eAssets`.
        getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("e2e-assets"))
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
                // Fork a fresh JVM every few classes.
                //
                // `ComposeRootRegistry` holds every Compose root ever
                // created in a weakly-referenced set, and every single
                // `isIdleNow` call copies that whole set. Each dialog
                // and each dropdown is its own root, so the set grows
                // by thousands over a full run — and the entries are
                // only cleared by a *full* GC, which never happens when
                // the heap is 2g and the suite only ever uses 300MB. The
                // result was a suite that got slower and slower and
                // then stopped: around test 200 a single `waitForIdle()`
                // would spin for twenty minutes inside
                // `getCreatedComposeRoots().toSet()`. Proven by
                // attaching to the stuck run and issuing `jcmd GC.run`,
                // which let it advance immediately.
                //
                // Forking resets the registry, which is a guarantee
                // rather than a hope about collector behaviour. All the
                // classes share one Robolectric sandbox (sdk=34), so a
                // fork costs one sandbox init; five of them is about
                // forty seconds against a suite that otherwise cannot
                // finish at all.
                // One JVM per class.
                //
                // This was four, which was a number that worked on one
                // laptop. CI is slower, and on CI the same accumulation
                // crossed Espresso's 60-second idle ceiling: seven
                // tests failed with "Compose did not get idle after
                // 9,000,000 attempts", in classes that had nothing to
                // do with the change. The registry grows with every
                // root any test in the JVM has ever created, and
                // `ConfigChangeKeepsStateTest` creates a fresh activity
                // — and so a fresh root — on every rotation it
                // simulates.
                //
                // So the isolation is per class and not per four. It is
                // a guarantee rather than a number tuned against one
                // machine's speed, which is the only kind of answer
                // worth having here: the failure mode is a suite that
                // goes green locally and red on hardware nobody has.
                it.setForkEvery(1)
                // How many of those JVMs run at once, which is a
                // different question. Unset, the 42 classes ran one
                // after another on a runner with cores to spare. Half
                // the cores, at most four, so a laptop running this
                // still has room for everything else on it.
                it.maxParallelForks =
                    (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
                it.testLogging { events("failed") }
                // A hung test used to be indistinguishable from a slow
                // suite: the task sat there until the outer guard killed
                // the process group, and the XML left on disk was the
                // *previous* run's, so the run looked green. Two things
                // stop that now.
                //
                // One, the task kills itself. Twelve minutes is about
                // eight times the honest runtime, so this only ever
                // fires on a hang.
                it.timeout.set(Duration.ofMinutes(12))
                // Two, every test writes its name as it starts and
                // again as it ends, flushed. The last line with no
                // matching end is the test that hung — which is the one
                // thing the XML can never tell you, because a hung test
                // never gets an XML entry at all.
                val order = layout.buildDirectory.file("test-order.log").get().asFile
                it.doFirst { order.parentFile.mkdirs(); order.writeText("") }
                it.addTestListener(object : TestListener {
                    override fun beforeSuite(d: TestDescriptor) {}
                    override fun afterSuite(d: TestDescriptor, r: TestResult) {}
                    override fun beforeTest(d: TestDescriptor) {
                        order.appendText("START ${d.className}.${d.name}\n")
                    }
                    override fun afterTest(d: TestDescriptor, r: TestResult) {
                        order.appendText("  END ${d.className}.${d.name} ${r.resultType}\n")
                    }
                })
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

/**
 * `schema.sql` and `test/fixtures/seed.sql`, into the test APK.
 *
 * The same bytes the worker's own vitest suite runs against. A
 * hand-written Android copy of either would be a second source of
 * truth that goes stale silently, and the failure it causes — a
 * journey seeing no rows — looks exactly like a bug in the app.
 */
val e2eAssets by tasks.registering(Copy::class) {
    val repo = rootProject.layout.projectDirectory.dir("..")
    from(repo.file("schema.sql"))
    from(repo.file("test/fixtures/seed.sql"))
    into(layout.buildDirectory.dir("e2e-assets"))
}

tasks.matching { it.name.startsWith("generate") && it.name.contains("AndroidTestAssets") }
    .configureEach { dependsOn(e2eAssets) }
tasks.matching { it.name.contains("AndroidTestAssets") || it.name.contains("MergeAssets") }
    .configureEach { dependsOn(e2eAssets) }

dependencies {
    implementation(project(":core"))
    implementation(project(":core-net"))
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation("androidx.activity:activity-compose:1.9.3")
    // Sign in with Google, natively. Credential Manager is the
    // supported way now — the old Google Sign-In SDK is deprecated —
    // and it hands back an ID token the Worker verifies exactly as it
    // verifies the browser's. See `GoogleSignIn.kt`.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // `viewModelScope` and `by viewModels()`. The whole `AppState`
    // lives in a `ViewModel` now, because it used to live in an
    // activity field and a rotation emptied the app.
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // Card art. The web gets it from an <img>; Compose has no loader of
    // its own, and a grid of Magic cards without the pictures is not the
    // same screen.
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    // Scryfall's mana symbols are SVGs. Without this Coil fetches them
    // and has nothing that can decode one, so every pip comes back
    // empty and the fallback letter is all you ever see.
    implementation("io.coil-kt.coil3:coil-svg:3.0.4")

    // The screens are tested on a device, clicked, because "the port is
    // done" is a claim about what a person can do with the app.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.6")
    androidTestImplementation(kotlin("test"))
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.6")
    // The end-to-end harness: a real HTTP server on a loopback port,
    // so the app's own Ktor/OkHttp stack does a real round trip
    // rather than a mock engine standing in for one.
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // SQLite with FTS5 in it. Android's own build has no fts5
    // module — `CREATE VIRTUAL TABLE card_search USING fts5(...)`
    // fails outright with "no such module" — and the text filter in
    // `:core` searches through `card_search MATCH ?`. A harness on
    // the system SQLite would have to rewrite the app's query to
    // run it, which is the one thing it must not do.
    androidTestImplementation("androidx.sqlite:sqlite-bundled:2.5.2")
    androidTestImplementation("androidx.test:rules:1.6.1")

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
