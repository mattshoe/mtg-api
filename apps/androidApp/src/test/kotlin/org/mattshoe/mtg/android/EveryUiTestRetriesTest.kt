package org.mattshoe.mtg.android

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every Android UI test retries a failure, on both runners.
 *
 * A UI test here is a class run by `AndroidJUnit4` — Robolectric on
 * the JVM, the instrumentation runner on a phone. Retrying is not
 * something a class declares any more: `AndroidJUnit4` is pointed at a
 * retrying runner on each side, so every class it runs retries. What
 * could still opt a class out is that pointing coming undone, or a
 * class naming some other runner and going round `AndroidJUnit4`, and
 * those are what this holds. See [Retry].
 */
class EveryUiTestRetriesTest {

    private val roots = listOf("src/sharedTest", "src/test", "src/androidTest").map(::File)

    @Test
    fun theJvmHandsEveryAndroidJUnit4ClassToTheRetryingRunner() {
        assertEquals(
            RetryingRobolectricRunner::class.java.name,
            System.getProperty(Retry.RUNNER_PROPERTY),
            "`${Retry.RUNNER_PROPERTY}` is not set to the retrying runner for the JVM tests in build.gradle.kts, " +
                "so AndroidJUnit4 runs plain Robolectric and nothing is retried",
        )
    }

    @Test
    fun thePhoneHandsEveryAndroidJUnit4ClassToTheRetryingRunner() {
        val gradle = File("build.gradle.kts")
        assertTrue(gradle.isFile, "not run from apps/androidApp: ${File(".").absolutePath}")
        assertTrue(
            Regex("""testInstrumentationRunner\s*=\s*"${RetryingInstrumentationRunner}"""").containsMatchIn(gradle.readText()),
            "testInstrumentationRunner in build.gradle.kts is not $RetryingInstrumentationRunner, " +
                "so AndroidJUnit4 on a phone runs its plain class runner and nothing is retried",
        )
    }

    @Test
    fun noUiTestGoesRoundAndroidJUnit4() {
        assertTrue(roots.all { it.isDirectory }, "not run from apps/androidApp: ${File(".").absolutePath}")
        val runWith = Regex("""^@RunWith\((\w+)::class\)""", RegexOption.MULTILINE)
        val files = roots.flatMap { it.walk().filter { f -> f.extension == "kt" }.toList() }
        val ui = files.filter { runWith.containsMatchIn(it.readText()) }
        assertTrue(ui.size > 30, "found only ${ui.size} UI test files, so this is looking in the wrong place")
        val elsewhere = ui.flatMap { f ->
            runWith.findAll(f.readText()).map { it.groupValues[1] }.filter { it != "AndroidJUnit4" }.map { "${f.name}: $it" }
        }.sorted()
        assertTrue(
            elsewhere.isEmpty(),
            "${elsewhere.size} test classes name their own runner, so they are never retried — " +
                "use @RunWith(AndroidJUnit4::class): $elsewhere",
        )
    }

    private companion object {
        const val RetryingInstrumentationRunner = "org.mattshoe.mtg.android.RetryingInstrumentationRunner"
    }
}
