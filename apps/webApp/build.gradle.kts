plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Compose HTML, not Compose Multiplatform's canvas renderer.
//
// This emits real DOM elements with real CSS, so text stays selectable
// and copyable, find-in-page works and the contrast work still applies.
// The canvas renderer would share UI code with Android and take all of
// that away, which is not a trade worth making for a site that is
// almost entirely card names and tables.
kotlin {
    js(IR) {
        browser {
            commonWebpackConfig { outputFileName = "mtg.js" }
            // Headless Chrome, because Compose HTML drives recomposition
            // off requestAnimationFrame and a browser that is not
            // painting never ticks it. Anything that clicks a button has
            // to run somewhere frames actually happen.
            testTask { useKarma { useChromeHeadless() } }
        }
        binaries.executable()
    }
    sourceSets {
        // The real stylesheet, served to Karma, so a layout test can
        // measure what the site actually looks like rather than what
        // an unstyled DOM happens to lay out as.
        named("jsTest") { resources.srcDir(rootProject.file("../frontend/css")) }

        jsTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
        jsMain.dependencies {
            implementation(project(":core"))
            implementation(project(":core-net"))
            implementation(compose.runtime)
            implementation(compose.html.core)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
    }
}
