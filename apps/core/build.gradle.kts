plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
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
    androidTarget()
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
