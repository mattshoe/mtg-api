package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Account
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Release
import org.mattshoe.mtg.core.Releases
import org.mattshoe.mtg.core.View
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Release notes on Admin Settings, through the real shell.
 *
 * Matt: "I JUST WANT TO FUCKING SEE THEM IN THE ADMIN SETTINGS!!!!!"
 * Sibling of `ReleaseNotesParityTest` on the phone.
 */
class ReleaseNotesTest {

    private val roots = mutableListOf<HTMLElement>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private fun mount(initial: AppState): HTMLElement {
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        renderComposable(root = root) {
            val s = remember { mutableStateOf(initial) }
            AppNav(s.value, onState = { s.value = it })
            AppShell(
                state = s.value,
                onState = { s.value = it },
                onSearch = {}, onOpenDeck = {},
                onPreviewEntry = {}, onApplyEntry = {},
            )
        }
        return root
    }

    private suspend fun settle() = repeat(3) {
        Promise<Unit> { r, _ -> kotlinx.browser.window.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun adminSettings(releases: Releases) = AppState(
        admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"),
        releases = releases,
    ).navigate(View.ADMIN)

    private val shipped = Releases().loaded(
        listOf(
            Release("android-v2.1.0-297", "2026-10-09T10:00:00Z", "Release notes in Admin Settings."),
            Release("android-v2.1.0-296", "2026-10-08T15:00:00Z", null),
        ),
    )

    private fun HTMLElement.entries(): List<HTMLElement> {
        val found = querySelectorAll("[data-release]")
        return (0 until found.length).map { found[it] as HTMLElement }
    }

    @Test
    fun adminSettingsListsEveryBuildNewestFirstWithItsDateAndWhatChanged() = runTest {
        val root = mount(adminSettings(shipped))
        settle()
        val rows = root.entries()
        assertEquals(2, rows.size, "Admin Settings shows no release notes: ${root.textContent}")

        val first = rows[0].textContent.orEmpty()
        assertTrue("2.1.0 (297)" in first, "the newest build is not first: $first")
        assertTrue("2026-10-09" in first, "no date on the entry: $first")
        assertTrue("Release notes in Admin Settings." in first, "the note is not there: $first")
        assertTrue("No note was written for this build." in rows[1].textContent.orEmpty())

        // On screen, one below the other, not collapsed to nothing.
        val a = rows[0].getBoundingClientRect()
        val b = rows[1].getBoundingClientRect()
        assertTrue(a.height > 0 && b.height > 0, "an entry has no height")
        assertTrue(b.top >= a.bottom, "the second entry is not below the first")
    }

    @Test
    fun aLoadThatFailedSaysSo() = runTest {
        val root = mount(adminSettings(Releases().failed("API rate limit exceeded")))
        settle()
        val text = root.textContent.orEmpty()
        assertTrue("Could not load release notes: API rate limit exceeded" in text, text)
    }
}
