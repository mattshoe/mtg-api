package org.mattshoe.mtg.web

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.renderComposable
import org.mattshoe.mtg.core.Admin
import org.mattshoe.mtg.core.AppState
import org.mattshoe.mtg.core.Overlay
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.get
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The admin unlock dialog, in a real browser.
 *
 * The password is the one thing this app holds that is worth stealing,
 * and the dialog is the only place it is ever typed. So the rules here
 * are about what it refuses to send, what it forgets on the way out,
 * and what it never writes down.
 */
class UnlockDialogTest {

    private val roots = mutableListOf<HTMLElement>()

    /** Every password the shell handed back, in order. */
    private val handed = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { it.remove() }
        roots.clear()
        handed.clear()
    }

    // ------------------------------------------------------- harness

    private class Shell(val root: HTMLElement, private val cell: MutableState<AppState>) {
        var state: AppState
            get() = cell.value
            set(v) { cell.value = v }
    }

    /**
     * The nav and the shell over one state, the arrangement the real
     * app has — the dialog is opened from the nav's own button.
     */
    private fun mount(initial: AppState = AppState()): Shell {
        val root = document.createElement("div") as HTMLElement
        document.body!!.appendChild(root)
        roots += root
        var cell: MutableState<AppState>? = null
        renderComposable(root = root) {
            val s = remember { mutableStateOf(initial) }
            cell = s
            AppNav(s.value, onState = { s.value = it })
            AppShell(
                state = s.value,
                onState = { s.value = it },
                onUnlock = { handed += it },
                onSearch = {}, onOpenDeck = {},
                onPreviewEntry = {}, onApplyEntry = {},
            )
        }
        return Shell(root, cell!!)
    }

    private suspend fun settle() = repeat(3) {
        Promise<Unit> { r, _ -> kotlinx.browser.window.requestAnimationFrame { r(Unit) } }.await()
    }

    private fun HTMLElement.buttons(): List<HTMLButtonElement> {
        val f = querySelectorAll("button")
        return (0 until f.length).mapNotNull { f[it] as? HTMLButtonElement }
    }

    /** The scrim, which is the dialog: absent means it is not up. */
    private val Shell.dialog: HTMLElement? get() = root.querySelector(".palette-scrim") as? HTMLElement

    private val Shell.up: Boolean get() = dialog != null

    private val Shell.field: HTMLInputElement
        get() = root.querySelector(".palette-scrim input") as HTMLInputElement

    /** The dialog's own Unlock, not the nav's — both say the same word. */
    private val Shell.go: HTMLButtonElement
        get() = root.querySelector(".palette-scrim .btn.primary") as HTMLButtonElement

    private val Shell.cancel: HTMLButtonElement
        get() = root.querySelector(".palette-scrim .btn.ghost") as HTMLButtonElement

    private suspend fun Shell.open() {
        root.buttons().first { it.textContent == "Unlock" }.click()
        settle()
    }

    private suspend fun Shell.type(word: String) {
        val el = field
        el.value = word
        el.dispatchEvent(Event("input", js("({bubbles: true})")))
        settle()
    }

    /** A real keystroke in the box, so it bubbles the way the browser's would. */
    private suspend fun Shell.press(key: String) {
        field.dispatchEvent(
            org.w3c.dom.events.KeyboardEvent(
                "keydown",
                org.w3c.dom.events.KeyboardEventInit(key = key, bubbles = true, cancelable = true),
            ),
        )
        settle()
    }

    // --------------------------------------------------------- tests

    /** It is a password box, and it says what the password is for. */
    @Test
    fun theDialogIsAPasswordBoxThatExplainsItself() = runTest {
        val s = mount()
        settle()
        assertFalse(s.up, "the dialog was up before anybody asked for it")
        s.open()
        assertTrue(s.up, "the Unlock tab did not open the dialog")
        assertTrue(s.root.textContent!!.contains("Admin mode"))
        assertTrue(s.root.textContent!!.contains("never stored"))
        assertEquals(1, s.root.querySelectorAll(".palette-scrim input").length, "one box, not two")
        assertEquals("password", s.field.getAttribute("type"), "the password echoed on screen")
    }

    /** An empty box is not a password. */
    @Test
    fun anEmptyBoxCannotBeSubmitted() = runTest {
        val s = mount()
        settle()
        s.open()
        assertTrue(s.go.disabled, "Unlock was live with nothing typed")
        s.go.click()
        settle()
        assertEquals(emptyList(), handed, "it offered an empty password to the server")
        assertTrue(s.up, "it closed on a press that did nothing")
    }

    /** Neither is a handful of spaces. */
    @Test
    fun whitespaceIsNotAPassword() = runTest {
        val s = mount()
        settle()
        s.open()
        s.type("   ")
        assertTrue(s.go.disabled, "spaces armed the button")
        s.press("Enter")
        assertEquals(emptyList(), handed, "Enter sent a blank password")
        assertTrue(s.up)
        // And it comes back the moment there is something real in it.
        s.type(" a ")
        assertFalse(s.go.disabled, "a real character left the button dead")
    }

    /** A typed password arms the button, and the button says Unlock. */
    @Test
    fun aRealPasswordArmsTheButton() = runTest {
        val s = mount()
        settle()
        s.open()
        s.type("hunter2")
        assertFalse(s.go.disabled)
        assertEquals("Unlock", s.go.textContent?.trim())
    }

    /** Pressing it hands the password back exactly as typed, and gets out of the way. */
    @Test
    fun pressingUnlockHandsThePasswordBackVerbatim() = runTest {
        val s = mount()
        settle()
        s.open()
        // Spaces at the ends are part of a password, not noise to be
        // tidied away — trimming one silently sends a different secret.
        s.type(" correct horse battery ")
        s.go.click()
        settle()
        assertEquals(listOf(" correct horse battery "), handed, "it did not hand back what was typed")
        assertFalse(s.up, "the dialog stayed up over the attempt")
    }

    /** A one-field form answers to Enter. */
    @Test
    fun enterInTheFieldSubmits() = runTest {
        val s = mount()
        settle()
        s.open()
        s.type("hunter2")
        s.press("Enter")
        assertEquals(listOf("hunter2"), handed, "Enter did nothing")
        assertFalse(s.up)
    }

    /** One attempt at a time, and the button says which. */
    @Test
    fun anAttemptAlreadyOutHoldsTheButtonShut() = runTest {
        val s = mount(AppState(admin = Admin(trying = true)).opening(Overlay.UNLOCK))
        settle()
        assertTrue(s.up)
        assertEquals("Unlocking…", s.go.textContent?.trim(), "it did not say an attempt was out")
        s.type("hunter2")
        assertTrue(s.go.disabled, "a second attempt went out over the first")
        s.press("Enter")
        assertEquals(emptyList(), handed, "Enter sent a second attempt over the first")
        assertTrue(s.up)
    }

    /** A wrong password gives the dialog back, rather than locking you out of it. */
    @Test
    fun aRefusedPasswordLeavesTheDialogUsable() = runTest {
        val s = mount(AppState(admin = Admin(trying = true)).opening(Overlay.UNLOCK))
        settle()
        // The server says no.
        s.state = s.state.copy(admin = s.state.admin.gaveUp())
        settle()
        assertEquals("Unlock", s.go.textContent?.trim(), "it still claims to be trying")
        s.type("hunter3")
        assertFalse(s.go.disabled, "a wrong password killed the button for good")
        s.go.click()
        settle()
        assertEquals(listOf("hunter3"), handed)
    }

    /** Cancel gets out, sends nothing, and forgets what was typed. */
    @Test
    fun cancelForgetsTheTypedPassword() = runTest {
        val s = mount()
        settle()
        s.open()
        s.type("hunter2")
        s.cancel.click()
        settle()
        assertFalse(s.up)
        assertEquals(emptyList(), handed, "Cancel sent the password anyway")
        s.open()
        assertEquals("", s.field.value, "the last password was still in the box")
    }

    /** So does the scrim — and a press on the panel itself is not a dismissal. */
    @Test
    fun theScrimDismissesAndForgetsToo() = runTest {
        val s = mount()
        settle()
        s.open()
        s.type("hunter2")
        (s.root.querySelector(".palette") as HTMLElement).click()
        settle()
        assertTrue(s.up, "a press inside the dialog closed it")
        assertEquals("hunter2", s.field.value, "a press inside the dialog cleared the box")
        s.dialog!!.click()
        settle()
        assertFalse(s.up, "the scrim did not dismiss")
        assertEquals(emptyList(), handed)
        s.open()
        assertEquals("", s.field.value, "the scrim left the password behind")
    }

    /** Escape closes it, and takes the password with it. */
    @Test
    fun escapeClosesItAndForgetsThePassword() = runTest {
        val s = mount()
        settle()
        s.open()
        s.type("hunter2")
        s.press("Escape")
        assertFalse(s.up, "Escape did not close the dialog")
        assertEquals(emptyList(), handed, "Escape sent the password")
        s.open()
        assertEquals("", s.field.value, "Escape left the password in the box")
    }

    /** A sent password is not still sitting there for the next person. */
    @Test
    fun submittingLeavesNothingBehindForNextTime() = runTest {
        val s = mount()
        settle()
        s.open()
        s.type("hunter2")
        s.press("Enter")
        s.open()
        assertEquals("", s.field.value, "the sent password was still in the box")
        assertTrue(s.go.disabled, "the reopened dialog was armed with a stale password")
    }

    /**
     * The password lives in the input's value property and nowhere
     * else. An attribute, a title or a stray text node is readable by
     * anything on the page and survives into whatever reads the DOM.
     */
    @Test
    fun thePasswordIsNeverWrittenIntoTheDom() = runTest {
        val secret = "zzsecretzz"
        val s = mount()
        settle()
        s.open()
        s.type(secret)
        assertEquals(secret, s.field.value, "the box lost what was typed")
        assertNull(s.field.getAttribute("value"), "the password was written into a value attribute")
        assertNull(s.field.getAttribute("title"), "the password was written into a title")
        val markup = s.dialog!!.innerHTML
        assertFalse(markup.contains(secret), "the password is in the dialog's markup: $markup")
        assertFalse(
            document.body!!.innerHTML.contains(secret),
            "the password escaped into the page",
        )
        // And nowhere in any attribute of anything inside the dialog.
        val all = s.dialog!!.querySelectorAll("*")
        (0 until all.length).mapNotNull { all[it] as? HTMLElement }.forEach { el ->
            val attrs = el.attributes
            (0 until attrs.length).forEach { i ->
                val a = attrs[i]!!
                assertFalse(
                    a.value.contains(secret),
                    "the password is in ${el.tagName}'s ${a.name}",
                )
            }
        }
    }

    /** Nothing is held hostage: the rest of the app is still there underneath. */
    @Test
    fun theDialogTrapsNothingElse() = runTest {
        val s = mount()
        settle()
        s.open()
        assertNotNull(s.root.querySelector(".app-nav"), "the nav went away behind the dialog")
        s.press("Escape")
        assertFalse(s.up)
        // Still navigable afterwards.
        s.root.buttons().first { it.textContent == "Stats" }.click()
        settle()
        assertTrue(s.root.textContent!!.contains("Stats"))
    }
}
