plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The socket half.
//
// Kept apart from :core so a consumer that only wants the decklist rules
// does not also get an HTTP stack. The shipping app is exactly that
// consumer: it has no dependencies today and there is no reason for
// counting lines in a CSV to change that.
kotlin {
    jvm()
    androidTarget {
        // See :core. `compileOptions` fixes Java at 17 and Kotlin would
        // otherwise take its target from the JDK running Gradle, so the
        // two disagree on anything newer and the module will not build.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    js(IR) { browser(); nodejs(); binaries.library() }
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation("io.ktor:ktor-client-core:3.0.3")
            implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.ktor:ktor-client-mock:3.0.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
        androidMain.dependencies { implementation("io.ktor:ktor-client-okhttp:3.0.3") }
        jvmMain.dependencies { implementation("io.ktor:ktor-client-okhttp:3.0.3") }
        jsMain.dependencies { implementation("io.ktor:ktor-client-js:3.0.3") }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.0.3") }
    }
}

android {
    namespace = "org.mattshoe.mtg.core.net"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
