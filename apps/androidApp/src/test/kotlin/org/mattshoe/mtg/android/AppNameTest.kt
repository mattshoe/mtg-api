package org.mattshoe.mtg.android

import org.junit.Rule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.mattshoe.mtg.core.Brand
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The app is called what it is called.
 *
 * Matt: "Why the fuck does the app name say next in it?!"
 *
 * Because `:androidApp` started life as the Compose rewrite sitting
 * beside the original `:app`, and "MTG Collection (next)" is how you
 * tell two builds apart on one home screen. It then took over — same
 * `applicationId`, same signing key, so it installs over the old one
 * — and it is the only thing `release.yml` ships. The label never
 * caught up, so the app on the phone has been advertising a
 * distinction that stopped existing.
 *
 * Read off the installed application info rather than the string
 * resource, because the label is what the launcher and the task
 * switcher actually show, and a manifest that stopped pointing at
 * `@string/app_name` would pass a test that only read the file.
 */
@RunWith(AndroidJUnit4::class)
class AppNameTest {

    @get:Rule(order = Int.MIN_VALUE)
    val retry = Retry()

    private fun label(): String {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return context.applicationInfo.loadLabel(context.packageManager).toString()
    }

    @Test
    fun theAppIsCalledMtgCollection() {
        assertEquals("MTG Collection", label())
        // And the shared constant says the same, because the Android
        // header prints `Brand.NAME` now. Two spellings of the app's
        // own name would show one in the launcher and the other at
        // the top of every page.
        assertEquals(Brand.NAME, label())
    }

    @Test
    fun theNameCarriesNoBuildMarker() {
        // The specific thing that was wrong, said separately so the
        // failure names it. A marker in a shipped app's name is the
        // kind of thing everybody stops seeing after a fortnight.
        val name = label().lowercase()
        listOf("next", "dev", "debug", "beta", "wip", "(").forEach { marker ->
            assertFalse(
                name.contains(marker),
                "the app's name still carries a build marker: \"${label()}\"",
            )
        }
    }

    @Test
    fun itMatchesWhatTheOtherModuleAlwaysCalledIt() {
        // `:app` is the build this one replaced, and it has always
        // been plain "MTG Collection". The two carry the same
        // `applicationId` precisely so one updates over the other —
        // which makes a different name on the same app a rename the
        // user never asked for.
        assertEquals("MTG Collection", label())
    }
}
