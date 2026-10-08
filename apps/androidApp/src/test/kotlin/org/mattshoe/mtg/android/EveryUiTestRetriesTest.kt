package org.mattshoe.mtg.android

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Every Android UI test class carries [Retry], outermost.
 *
 * The rule only helps a class that declares it, so a new screen test
 * written without it would go back to failing a twenty-minute run on
 * one flake. A UI test here is a class run by `AndroidJUnit4` —
 * Robolectric on the JVM, the instrumentation runner on a phone —
 * which is every class in `sharedTest`, `test` and `androidTest`
 * that drives Compose or the activity.
 *
 * Outermost, so each attempt gets a fresh compose rule, activity and
 * `FakeWorker` rather than re-running the body in whatever the failed
 * attempt left behind. `@Rule`'s default order is -1 and a lower
 * order wraps a higher one.
 */
class EveryUiTestRetriesTest {

    private val declared = Regex("""@get:Rule\(order\s*=\s*Int\.MIN_VALUE\)\s*val\s+\w+\s*=\s*Retry\(\)""")

    @Test
    fun everyAndroidJUnit4ClassRetriesItsFailures() {
        val roots = listOf("src/sharedTest", "src/test", "src/androidTest").map(::File)
        assertTrue(roots.all { it.isDirectory }, "not run from apps/androidApp: ${File(".").absolutePath}")
        val ui = roots.flatMap { it.walk().filter { f -> f.extension == "kt" }.toList() }
            .filter { it.readText().contains("@RunWith(AndroidJUnit4::class)") }
        assertTrue(ui.size > 30, "found only ${ui.size} UI test files, so this is looking in the wrong place")
        val missing = ui.filterNot { declared.containsMatchIn(it.readText()) }.map { it.name }.sorted()
        assertTrue(
            missing.isEmpty(),
            "${missing.size} UI test classes do not retry a failure, add " +
                "`@get:Rule(order = Int.MIN_VALUE) val retry = Retry()`: $missing",
        )
    }
}
