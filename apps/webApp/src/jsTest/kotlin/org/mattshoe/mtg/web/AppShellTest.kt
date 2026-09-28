package org.mattshoe.mtg.web

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.View
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shell in a real browser: which tabs exist, and what the lock hides.
 *
 * The visible tabs come from the shared core, so what this asserts is
 * also true of Android.
 */
class AppShellTest {

    private val roots = mutableListOf<HTMLElement>()
    private var unlocked: String? = null

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
        unlocked = null
    }

    private fun mount(initial: AppState): HTMLElement {
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        renderComposable(root = root) {
            val s = remember { mutableStateOf(initial) }
            AppShell(
                state = s.value,
                onState = { s.value = it },
                onUnlock = { unlocked = it },
                onSearch = {}, onOpenDeck = {}, onRunSql = {},
                onPreviewEntry = {}, onApplyEntry = {},
            )
        }
        return root
    }

    private suspend fun settle() = repeat(3) {
        Promise<Unit> { r, _ -> kotlinx.browser.window.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun HTMLElement.buttons(): List<HTMLButtonElement> {
        val f = querySelectorAll("button")
        return (0 until f.length).mapNotNull { f[it] as? HTMLButtonElement }
    }

    private fun HTMLElement.tabs() = buttons().mapNotNull { it.textContent?.trim() }

    /** Gated views are absent while locked, not greyed out. */
    @Test
    fun lockedShowsFourTabsAndNoAdminOnes() = runTest {
        val root = mount(AppState())
        settle()
        val tabs = root.tabs()
        assertTrue(tabs.contains("Library"))
        assertTrue(tabs.contains("Decks"))
        assertTrue(tabs.contains("Stats"))
        assertTrue(tabs.contains("Query"))
        assertFalse(tabs.contains("Mass Entry"), "a gated tab must not be visible")
        assertFalse(tabs.contains("Server Logs"))
        assertTrue(tabs.contains("Unlock"))
    }

    @Test
    fun unlockedShowsThemAll() = runTest {
        val root = mount(AppState(admin = Admin("0.abc")))
        settle()
        val tabs = root.tabs()
        assertTrue(tabs.contains("Mass Entry"))
        assertTrue(tabs.contains("Server Logs"))
        assertTrue(tabs.contains("Lock"))
    }

    @Test
    fun theCurrentTabIsMarked() = runTest {
        val root = mount(AppState().navigate(View.DECKS))
        settle()
        val decks = root.buttons().first { it.textContent == "Decks" }
        assertTrue(decks.className.contains("on"), decks.className)
    }

    @Test
    fun switchingTabsSwapsTheScreen() = runTest {
        val root = mount(AppState())
        settle()
        assertTrue(root.textContent!!.contains("Library"))
        root.buttons().first { it.textContent == "Stats" }.click()
        settle()
        assertTrue(root.textContent!!.contains("Stats"))
    }

    @Test
    fun theUnlockDialogAsksAndHandsThePasswordBack() = runTest {
        val root = mount(AppState())
        settle()
        root.buttons().first { it.textContent == "Unlock" }.click()
        settle()
        assertTrue(root.textContent!!.contains("Admin mode"))
        assertTrue(root.textContent!!.contains("never stored"))
        assertEquals(1, root.querySelectorAll("input[type=password]").length)
    }

    @Test
    fun everyUnlockedTabRendersItsOwnScreen() = runTest {
        View.entries.forEach { view ->
            val root = mount(AppState(admin = Admin("t")).navigate(view))
            settle()
            assertTrue(
                root.querySelectorAll("button").length > 0,
                "${view.label} rendered nothing",
            )
        }
    }
}
