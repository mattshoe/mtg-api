package org.mattshoe.mtg.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every feature has to name a test, and every test it names has to
 * exist.
 *
 * "There are eight hundred tests" is not the same claim as "this
 * feature has one". This reads every test source in the repository,
 * collects the function names, and holds the inventory to them — so a
 * renamed test tells you which feature just lost its proof, and a
 * feature added without a test cannot claim to be done.
 *
 * JVM only, because it reads the repository off disk. That is also
 * why it runs in CI rather than on a phone.
 */
class CoverageGateTest {

    private val sources: List<File> by lazy {
        val apps = File("..").canonicalFile
        assertTrue(apps.isDirectory, "cannot find the project root at ${apps.absolutePath}")
        apps.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }
            .filter { it.isFile && it.name.endsWith("Test.kt") }
            .toList()
    }

    /** Every `fun someTest()` in every test file, by bare name. */
    private val testNames: Set<String> by lazy {
        val fn = Regex("""\bfun\s+([A-Za-z][A-Za-z0-9_]*)\s*\(""")
        sources.flatMap { file -> fn.findAll(file.readText()).map { it.groupValues[1] } }.toSet()
    }

    private fun where(name: String): String? =
        sources.firstOrNull { Regex("""\bfun\s+$name\s*\(""").containsMatchIn(it.readText()) }
            ?.let { it.name }

    @Test
    fun thereAreTestSourcesToReadAtAll() {
        assertTrue(sources.size > 10, "found only ${sources.size} test files; the scan is wrong")
        assertTrue(testNames.size > 300, "found only ${testNames.size} test functions")
    }

    @Test
    fun everyTestAFeatureNamesActuallyExists() {
        val missing = Inventory.features.flatMap { f ->
            f.tests.filterNot { it in testNames }.map { "${f.what} -> $it" }
        }
        if (missing.isNotEmpty()) {
            fail(
                "these features name a test that does not exist — renamed or deleted:\n  " +
                    missing.joinToString("\n  "),
            )
        }
    }

    @Test
    fun noFeatureNamesTheSameTestTwice() {
        val dupes = Inventory.features.filter { it.tests.size != it.tests.toSet().size }
        assertTrue(dupes.isEmpty(), "duplicated test names in: ${dupes.map { it.what }}")
    }

    /**
     * The gate itself. Ratcheted rather than absolute, so it can only
     * ever get stricter — raise the number as gaps are closed and it
     * will refuse to let them reopen.
     */
    @Test
    fun everyFeatureThatClaimsToBeDoneNamesATest() {
        val naked = Inventory.features.filter { it.done && it.tests.isEmpty() }
        val allowed = UNCOVERED_ALLOWANCE
        if (naked.size > allowed) {
            fail(
                "${naked.size} finished features have no test, and only $allowed are tolerated:\n  " +
                    naked.joinToString("\n  ") { "${it.area.label}: ${it.what}" } +
                    "\n\nEither write the test or lower the claim.",
            )
        }
    }

    @Test
    fun theAllowanceIsNotSetHigherThanItNeedsToBe() {
        val naked = Inventory.features.count { it.done && it.tests.isEmpty() }
        assertTrue(
            UNCOVERED_ALLOWANCE <= naked,
            "the allowance is $UNCOVERED_ALLOWANCE but only $naked features are uncovered — " +
                "lower it to $naked so the gap cannot reopen",
        )
    }

    /** Printed every run, so the number is visible without reading code. */
    @Test
    fun reportCoverage() {
        val done = Inventory.features.filter { it.done }
        val covered = done.count { it.tests.isNotEmpty() }
        val named = done.sumOf { it.tests.size }
        println(
            buildString {
                appendLine()
                appendLine("Feature coverage")
                appendLine("  ${testNames.size} test functions across ${sources.size} files")
                appendLine("  $covered of ${done.size} finished features name at least one")
                appendLine("  $named named in total")
                Area.entries.forEach { area ->
                    val inArea = done.filter { it.area == area }
                    if (inArea.isEmpty()) return@forEach
                    val with = inArea.count { it.tests.isNotEmpty() }
                    appendLine("    ${area.label}: $with/${inArea.size}")
                    inArea.filter { it.tests.isEmpty() }.forEach { appendLine("        no test: ${it.what}") }
                }
            },
        )
    }

    /**
     * Claims that are covered in part.
     *
     * Every feature names a test, but three of the sentences promise
     * more than the tests behind them deliver. Naming the shortfall
     * here keeps it visible and stops it growing — the alternative is
     * a green gate over a sentence nobody has checked.
     */
    @Test
    fun theKnownPartialClaimsHaveNotGrown() {
        val known = mapOf(
            "Card detail drawer with art, prices, legalities, rulings" to
                "art, prices and the owned/free counts are tested; legalities and rulings are not rendered by any test",
            "Export the whole result as a decklist or to the clipboard" to
                "the decklist, the unpaged query and the filename are tested; nothing touches the clipboard",
            "Free SQL against the collection, read-only" to
                "the console state and its rendering are tested; nothing asserts a write statement is refused",
        )
        known.keys.forEach { what ->
            assertTrue(
                Inventory.features.any { it.what == what },
                "\"$what\" is no longer in the inventory — drop it from the partial list too",
            )
        }
        println("\nPartially covered claims (${known.size}):")
        known.forEach { (what, why) -> println("  $what\n      $why") }
    }

    private companion object {
        /**
         * How many finished features are allowed to have no named
         * test. A ratchet: it may only ever go down.
         */
        const val UNCOVERED_ALLOWANCE = 0
    }
}
