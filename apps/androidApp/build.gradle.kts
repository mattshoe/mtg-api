plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// The Compose rewrite of the share app, on the shared core.
//
// A different applicationId from :app on purpose — both can sit on the
// phone at once so this can be tried against the one that already works,
// and removed without touching it.
android {
    namespace = "org.mattshoe.mtg.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.mattshoe.mtg.share.next"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets { getByName("main").java.srcDirs("src/main/kotlin") }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
