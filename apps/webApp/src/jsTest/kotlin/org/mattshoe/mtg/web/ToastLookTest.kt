package org.mattshoe.mtg.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.AppState
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The toast, against the real stylesheet.
 *
 * `.toast.ok` and `.toast.bad` sat in `app.css` for a success and a
 * failure, but `AppState.toast` was a bare `String?` with no notion
 * of which one it was, so `AppShell` never set either class — a
 * dropped request and a finished save rendered as the exact same
 * toast. `AppState.toastFailed` and the `.toast.bad` class fix that;
 * there is no `.toast.ok` because the un-failed look is already the
 * plain, successful one.
 */
class ToastLookTest {

    private val roots = mutableListOf<HTMLElement>()

    @BeforeTest
    fun loadTheStylesheet() = Stylesheet.load()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
    }

    private suspend fun settle() = repeat(4) {
        Promise<Unit> { r, _ -> window.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun mount(state: AppState): HTMLElement {
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        renderComposable(root = root) {
            AppShell(
                state = state,
                onState = {},
                onUnlock = {},
                onSearch = {}, onOpenDeck = {},
                onPreviewEntry = {}, onApplyEntry = {},
            )
        }
        return root
    }

    private fun HTMLElement.toast(): HTMLElement? = querySelector("button.toast") as? HTMLElement

    @Test
    fun aFailedToastCarriesTheBadClassAndASuccessfulOneDoesNot() = runTest {
        val ok = mount(AppState(toast = "Renamed to Chulane"))
        val bad = mount(AppState(toast = "something went wrong", toastFailed = true))
        settle()
        assertTrue(ok.toast()!!.className.split(" ").none { it == "bad" }, "a success toast is marked bad")
        assertTrue(bad.toast()!!.className.split(" ").any { it == "bad" }, "a failure toast carries no `bad` class")
    }

    /**
     * The actual bug: nothing ever set the class, so a success toast
     * and a failure toast painted identically. This compares resolved
     * style, not the class name — reverting `AppShell`'s
     * `if (state.toastFailed) classes("bad")` turns this red.
     */
    @Test
    fun aFailedToastIsPaintedDifferentlyFromASuccessfulOne() = runTest {
        val ok = mount(AppState(toast = "Renamed to Chulane"))
        val bad = mount(AppState(toast = "something went wrong", toastFailed = true))
        settle()
        assertTrue(Stylesheet.applied(), "the real stylesheet never applied")
        val okStyle = window.getComputedStyle(ok.toast()!!)
        val badStyle = window.getComputedStyle(bad.toast()!!)
        assertNotEquals(
            okStyle.borderLeftColor,
            badStyle.borderLeftColor,
            "a failed toast resolves to the same border colour as a successful one",
        )
        // Non-hue signals too: a colourblind read must not depend on
        // the border colour alone.
        assertNotEquals(
            okStyle.borderLeftWidth,
            badStyle.borderLeftWidth,
            "a failed toast is not any wider down its edge than a successful one",
        )
        assertNotEquals(
            okStyle.fontWeight,
            badStyle.fontWeight,
            "a failed toast is not any bolder than a successful one",
        )
    }

    @Test
    fun dismissingAToastClearsWhetherItWasAFailureToo() = runTest {
        val s = AppState(toast = "something went wrong", toastFailed = true)
        assertEquals(null, s.say(null).toast)
        assertEquals(false, s.say(null).toastFailed)
    }
}
