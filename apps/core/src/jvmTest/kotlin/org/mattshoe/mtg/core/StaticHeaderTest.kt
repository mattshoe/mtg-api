package org.mattshoe.mtg.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The page the shared build mounts into, held to carrying no controls
 * of its own.
 *
 * `frontend/index.html` is static only because the header is: the nav,
 * the screens and every dialog are drawn by the Kotlin build, so
 * anything a person can press that lives in that file is a control
 * neither app can keep in step with. One did — an "Android app" link
 * in the top bar's right-hand slot, which no Kotlin test mounts and
 * which three parity passes walked straight past. Matt: "WHY THE FUCK
 * IS THE ANDROID APP LINK IN THE FUCKING HEADER STILL!!!! I FUCKING
 * SAID PARITY!!! NOT 'ALMOST PARITY'!!!!!"
 *
 * JVM, because it reads the repository off disk — the same reason
 * `CoverageGateTest` lives here. `frontend/classic.html` is the
 * retired hand-written site and is deliberately not held to this: its
 * header is its whole navigation.
 */
class StaticHeaderTest {

    private val page: String by lazy {
        val f = File("../../frontend/index.html").canonicalFile
        assertTrue(f.isFile, "cannot find index.html at ${f.absolutePath}")
        f.readText()
    }

    /**
     * The header's markup with its comments taken out.
     *
     * The comments are where the reasoning lives — including the one
     * saying where the Android app link went — so matching on text
     * has to look at what the browser renders, not at what the file
     * says about itself.
     */
    private val header: String by lazy {
        val m = Regex("""<header[\s\S]*?</header>""").find(page)
        assertTrue(m != null, "index.html has no header at all")
        m!!.value.replace(Regex("""<!--[\s\S]*?-->"""), "")
    }

    @Test
    fun theBarIsASlotForTheSharedBuild() {
        assertTrue("""id="nav"""" in header, "no slot for the shared build: $header")
        assertTrue("kmp/mtg.js" in page, "the page does not load the shared build")
    }

    @Test
    fun andHoldsNoLinkOfItsOwn() {
        val links = Regex("""<a\b[^>]*>""").findAll(header).map { it.value }.toList()
        assertEquals(emptyList(), links, "a link in the static header")
    }

    @Test
    fun norAnyButton() {
        val buttons = Regex("""<button\b[^>]*>""").findAll(header).map { it.value }.toList()
        assertEquals(emptyList(), buttons, "a button in the static header")
    }

    @Test
    fun andDoesNotOfferTheAndroidAppFromOutsideTheApp() {
        // It belongs in the hamburger, with the other places you can
        // go — `ProfileMenuTest.theAndroidAppIsInTheHamburgerWithTheOtherPlacesToGo`
        // is the other half of this.
        assertTrue("Android app" !in header, "the Android app link is still in the header")
    }
}
