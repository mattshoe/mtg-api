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

// The ManaBox stand-in, for sharing a real file into the app by hand.
include(":sender")

// Shared across platforms: models, parsing, the wizard's rules.
include(":core")
include(":core-net")

// Built on the core.
include(":androidApp")
include(":webApp")
