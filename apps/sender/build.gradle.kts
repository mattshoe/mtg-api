plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// A stand-in for ManaBox, for testing only.
//
// It owns a file in its own private storage and shares it with a real
// FileProvider grant — which is the thing the shell cannot fake and the
// thing that broke the web app. Never shipped.
android {
    namespace = "org.mattshoe.mtg.sender"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.mattshoe.mtg.sender"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets { getByName("main").java.srcDirs("src/main/kotlin") }
}
