plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlinx.kover")
}

// Everything that is not a pixel and not a socket.
//
// No Ktor here on purpose. The shipping app wants the decklist rules and
// nothing else, and it has no dependencies today — dragging an HTTP
// stack in behind a line-counting function would be a poor trade. The
// network lives in :core-net.
//
// The models, the API client, the decklist parsing and — the part that
// actually stops the platforms drifting — the wizard's state machine. Two
// hand-written UIs can disagree about padding. They cannot disagree about
// whether Apply is reachable before a dry run, because neither of them
// decides that.
kotlin {
    jvm()                       // where the shared tests run fastest
    androidTarget {
        // Pinned, not inherited. `compileOptions` below fixes Java at
        // 17 while Kotlin takes the target from whatever JDK is running
        // Gradle, so on a JDK 21 machine the two halves of the same
        // compilation disagreed and the Android build of :core would
        // not start. CI runs 17 and never saw it; a laptop on 21 could
        // not build the app at all.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    // browser() is what the web app links against; nodejs() is only so
    // the shared tests can run without standing up a browser.
    js(IR) {
        browser()
        nodejs()
        binaries.library()
    }

    // Declared now, before there is an iOS app, so the compiler refuses
    // anything in commonMain that could not follow us there later.
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "org.mattshoe.mtg.core"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// A measured floor, not a feeling. `koverVerify` runs in CI and fails
// the build under it; raise it as gaps close so it can only ratchet.
//
// The floor is on :core deliberately — it is the half both platforms
// execute, so a line uncovered here is a line uncovered twice.
kover {
    reports {
        filters {
            excludes {
                // The manifest describes the port; it is data, and
                // `InventoryTest` already reads all of it.
                classes("org.mattshoe.mtg.core.Inventory*")
                classes("org.mattshoe.mtg.core.Design*")
            }
        }
        verify {
            // Measured at 87.3% line / 56.9% branch today. Set just
            // under, so it is a ratchet rather than a target: it can
            // only be raised, and nothing may slip below it.
            rule("lines") {
                bound {
                    minValue = 85
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                }
            }
            rule("branches") {
                bound {
                    minValue = 55
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                }
            }
        }
    }
}

// `CoreSqlDump` compares the SQL the core emits against the committed
// `test/fixtures/core-sql.json` that the Worker's suite executes. Nothing
// else tells Gradle that file matters, so an edit to it left `jvmTest`
// UP-TO-DATE and the check silently did not run — which is the same as
// not having it.
tasks.named<Test>("jvmTest") {
    inputs.file(rootProject.file("../test/fixtures/core-sql.json"))
        .withPropertyName("coreSqlFixture")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
