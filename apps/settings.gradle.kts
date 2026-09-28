pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "mtg-share"

// The app that ships today. Untouched by any of the multiplatform work —
// it stays buildable and installable while that is proven out.
include(":app")
include(":sender")

// The multiplatform side.
include(":core")
include(":androidApp")
include(":webApp")
