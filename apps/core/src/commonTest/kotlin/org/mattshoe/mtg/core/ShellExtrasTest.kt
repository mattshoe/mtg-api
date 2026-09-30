package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parts of the shell that are not a screen: the overlay stack, the
 * keyboard, and the toast that explains it.
 *
 * These run on the JVM and in the browser from the same source, which
 * is the only way "back closes the drawer" can be one behaviour rather
 * than two.
 */
class OverlayTest {

    @Test
    fun backTakesTheTopOneOff() {
        val s = AppState()
            .opening(Overlay.PALETTE)
            .opening(Overlay.CHEATSHEET)
        assertEquals(Overlay.CHEATSHEET, s.overlays.top)

        val once = s.dismissTop()
        assertNotNull(once)
        assertEquals(Overlay.PALETTE, once.overlays.top)

        val twice = once.dismissTop()
        assertNotNull(twice)
        assertFalse(twice.overlays.any)
    }

    @Test
    fun backWithNothingOpenIsNotHandled() {
        // Null is how a platform knows to let the gesture through to its
        // own navigation instead of swallowing it.
        assertNull(AppState().dismissTop())
    }

    @Test
    fun openingTheSameOverlayTwiceDoesNotStackIt() {
        val s = AppState().opening(Overlay.PALETTE).opening(Overlay.PALETTE)
        assertEquals(1, s.overlays.stack.size)
    }

    @Test
    fun closingAnOverlayThrowsAwayWhatItWasHolding() {
        val s = AppState(newDeck = NewDeck(name = "Half typed"))
            .opening(Overlay.NEW_DECK)
            .closing(Overlay.NEW_DECK)
        assertEquals(NewDeck(), s.newDeck)
        assertFalse(Overlay.NEW_DECK in s.overlays)
    }

    @Test
    fun closingOneUnderneathLeavesTheTopAlone() {
        val s = AppState().opening(Overlay.PALETTE).opening(Overlay.CHEATSHEET).closing(Overlay.PALETTE)
        assertEquals(listOf(Overlay.CHEATSHEET), s.overlays.stack)
    }

    @Test
    fun navigatingTakesEveryOverlayWithIt() {
        val s = AppState(card = CardDetail(name = "Bolt"))
            .opening(Overlay.PALETTE)
            .navigate(View.DECKS)
        assertFalse(s.overlays.any)
        assertNull(s.card)
    }
}

class ShortcutsTest {

    private val locked = Admin()
    private val open = Admin(token = "t")

    @Test
    fun lettersGoToTheirView() {
        assertEquals(Action.Go(View.LIBRARY), Shortcuts.of("s", false, locked, false))
        assertEquals(Action.Go(View.DECKS), Shortcuts.of("d", false, locked, false))
        assertEquals(Action.Go(View.STATS), Shortcuts.of("g", false, locked, false))
        assertEquals(Action.Go(View.CONSOLE), Shortcuts.of("c", false, locked, false))
    }

    @Test
    fun gatedShortcutsAreAsHiddenAsTheirTabs() {
        assertNull(Shortcuts.of("e", false, locked, false))
        assertNull(Shortcuts.of("v", false, locked, false))
        assertEquals(Action.Go(View.ENTRY), Shortcuts.of("e", false, open, false))
        assertEquals(Action.Go(View.LOGS), Shortcuts.of("v", false, open, false))
    }

    @Test
    fun nothingFiresWhileTyping() {
        // Every shortcut is a bare letter, so without this the page
        // jumps away in the middle of a card name.
        listOf("s", "d", "e", "g", "c", "v", "l", "/", "?").forEach {
            assertNull(Shortcuts.of(it, typing = true, admin = open, anythingOpen = false), it)
        }
    }

    @Test
    fun escapeOnlyMeansSomethingWhenSomethingIsOpen() {
        assertNull(Shortcuts.of("Escape", false, open, anythingOpen = false))
        assertEquals(Action.Close, Shortcuts.of("Escape", false, open, anythingOpen = true))
        // And it works while typing, which is the whole point of it.
        assertEquals(Action.Close, Shortcuts.of("Escape", true, open, anythingOpen = true))
    }

    @Test
    fun theChordWorksWhileTyping() {
        assertEquals(Action.OpenPalette, Shortcuts.ofChord("k", meta = true, ctrl = false))
        assertEquals(Action.OpenPalette, Shortcuts.ofChord("K", meta = false, ctrl = true))
        assertNull(Shortcuts.ofChord("k", meta = false, ctrl = false))
    }

    @Test
    fun theHelpToastListsOnlyWhatIsReachable() {
        val shut = Shortcuts.help(locked)
        assertFalse(shut.contains("entry"), shut)
        assertFalse(shut.contains("logs"), shut)
        assertTrue(shut.contains("unlock"), shut)

        val on = Shortcuts.help(open)
        assertTrue(on.contains("e entry"), on)
        assertTrue(on.contains("v logs"), on)
        assertTrue(on.contains("l lock"), on)
    }
}

class OnKeyTest {

    @Test
    fun aLetterNavigates() {
        val s = AppState().onKey("d")
        assertNotNull(s)
        assertEquals(View.DECKS, s.view)
    }

    @Test
    fun questionMarkToasts() {
        val s = AppState().onKey("?")
        assertNotNull(s)
        assertTrue(s.toast!!.contains("s search"))
    }

    @Test
    fun slashOpensTheFinder() {
        val s = AppState().onKey("/")
        assertNotNull(s)
        assertTrue(s.palette.open)
        assertEquals(Overlay.PALETTE, s.overlays.top)
    }

    @Test
    fun lLocksAndUnlocks() {
        val on = AppState(admin = Admin(token = "t")).onKey("l")
        assertNotNull(on)
        assertFalse(on.admin.unlocked)

        val off = AppState().onKey("l")
        assertNotNull(off)
        assertEquals(Overlay.UNLOCK, off.overlays.top)
    }

    @Test
    fun anUnknownKeyChangesNothing() {
        assertNull(AppState().onKey("q"))
    }

    @Test
    fun lockingWhileOnAGatedViewMovesYouOff() {
        val s = AppState(admin = Admin(token = "t")).navigate(View.LOGS)
        assertEquals(View.LOGS, s.view)
        val locked = s.onKey("l")
        assertNotNull(locked)
        assertEquals(View.LIBRARY, locked.view)
    }
}

class CheatsheetTest {

    @Test
    fun everyExampleInTheSheetActuallyParses() {
        // The sheet documents the shared parser. An example it cannot
        // read is a lie in the documentation, and this is the only thing
        // that would notice.
        Cheatsheet.examples.forEach { example ->
            example.split("  ").filter { it.isNotBlank() }.forEach { one ->
                parseQueryBox(one)
            }
        }
    }

    @Test
    fun theIsListComesFromTheParser() {
        assertTrue(Cheatsheet.isValues.size > 30, "${Cheatsheet.isValues.size}")
        Cheatsheet.isValues.forEach { parseQueryBox("is:$it") }
    }

    @Test
    fun itCoversEveryGroupTheOldPageHad() {
        assertEquals(
            listOf("Words", "Colour", "Numbers", "Printing", "Oracle-level", "Collection"),
            Cheatsheet.groups.map { it.title },
        )
        Cheatsheet.groups.forEach { g ->
            assertTrue(g.keys.isNotEmpty(), "${g.title} is empty")
            g.keys.forEach {
                assertTrue(it.what.isNotBlank(), "${it.keys} says nothing")
                assertTrue(it.example.isNotBlank(), "${it.keys} has no example")
            }
        }
    }
}
