package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "- 3 +" under a card.
 *
 * Matt: "i need a way to toggle the amount of a given card on the
 * carousel and on the card details [...] make sure the carousel on the
 * deck details toggles the number in the deck, not the number in the
 * collection (tho if increasing being the amount in the collection,
 * then you'd have to add it to the collection)".
 *
 * So there are two numbers and two ways to write them. Over a deck it
 * is the deck's count, sent through `/decks/list`, which already buys
 * whatever the collection is short of. Everywhere else it is the
 * collection's count, sent through `/cards/add` and `/cards/remove`
 * one copy at a time.
 */
class CountStepTest {

    private val me = Admin().signIn(Account(key = "matt0001"), "t")

    private val deck = Deck("alela", "Alela", "matt0001", "Alela, Artful Provocateur", "UW", 3, null)

    private fun deckCard(name: String, qty: Int, owned: Int, role: String? = null) = DeckCard(
        name = name,
        qty = qty,
        role = role,
        owned = owned,
        nameNorm = name.lowercase(),
        typeLine = "Artifact",
        scryfallId = "abcdef12-3456",
    )

    private fun overADeck(vararg cards: DeckCard, admin: Admin = me) = AppState(admin = admin)
        .navigate(Route(View.DECKS, "alela"))
        .let { it.copy(decks = it.decks.loaded(listOf(deck)).opened("alela", cards.toList())) }

    private fun row(name: String, qty: Int, owner: String = "matt0001", finish: String? = "nonfoil") = CardRow(
        id = 1, owner = owner, name = name, nameNorm = name.lowercase(), face2 = null,
        layout = null, scryfallId = null, manaCost = null, cmc = null, typeLine = "Artifact",
        colorIdentity = null, rarity = null, setCode = "m3c", setName = "Modern Horizons 3 Commander",
        collectorNumber = "409", edhrecRank = null, releasedAt = null, finish = finish,
        power = null, toughness = null, artist = null, qty = qty, printings = 1, free = 1,
        price = null, value = null,
    )

    private fun inTheLibrary(r: CardRow, admin: Admin = me) = AppState(admin = admin)
        .navigate(View.LIBRARY)
        .let { it.copy(library = it.library.loaded(listOf(r), 1)) }
        .peekRow(r)

    // ------------------------------------------------- over a deck

    @Test
    fun aDecksCarouselCountsTheDeckNotTheCollection() {
        val ring = deckCard("Sol Ring", qty = 2, owned = 5)
        val s = overADeck(ring).peekCard(ring)
        val stepper = assertNotNull(s.peekStepper, "no stepper on the deck's carousel")
        assertEquals(2, stepper.count, "the stepper shows the collection's 5, not the deck's 2")
    }

    @Test
    fun plusOnADeckSendsTheDeckListWithOneMore() {
        val ring = deckCard("Sol Ring", qty = 1, owned = 1)
        val cult = deckCard("Cultivate", qty = 1, owned = 1)
        val s = overADeck(ring, cult).peekCard(ring)
        val write = assertIs<CountWrite.DeckList>(s.peekStep(+1), "plus over a deck did not write the deck")
        assertEquals("alela", write.deck)
        assertTrue("2 Sol Ring" in write.list.lines(), "the list sent was:\n${write.list}")
        assertTrue("1 Cultivate" in write.list.lines(), "the rest of the deck went missing:\n${write.list}")
        assertEquals("Alela, Artful Provocateur", write.commander, "the commander was dropped")
    }

    @Test
    fun minusToNoughtTakesTheCardOutOfTheDeck() {
        val ring = deckCard("Sol Ring", qty = 1, owned = 1)
        val cult = deckCard("Cultivate", qty = 1, owned = 1)
        val s = overADeck(ring, cult).peekCard(ring)
        val write = assertIs<CountWrite.DeckList>(s.peekStep(-1))
        assertFalse(write.list.lines().any { it.endsWith("Sol Ring") }, "Sol Ring is still in:\n${write.list}")
    }

    @Test
    fun theCommanderHasNoStepper() {
        val alela = deckCard("Alela, Artful Provocateur", qty = 1, owned = 1, role = "commander")
        val s = overADeck(alela).peekCard(alela)
        assertNull(s.peekStepper, "a commander's count is not a number to step")
        assertNull(s.peekStep(+1))
    }

    @Test
    fun somebodyElsesDeckHasNoStepper() {
        val kayla = Admin().signIn(Account(key = "k4yy0003"), "t")
        val ring = deckCard("Sol Ring", qty = 1, owned = 1)
        val s = overADeck(ring, admin = kayla).peekCard(ring)
        assertNull(s.peekStepper, "Kayla can step Matt's deck")
        assertNull(s.peekStep(+1))
    }

    // ------------------------------------------------- the Library

    @Test
    fun theLibrarysCarouselCountsTheCollection() {
        val s = inTheLibrary(row("Sol Ring", qty = 3))
        assertEquals(3, assertNotNull(s.peekStepper, "no stepper on the Library's carousel").count)
    }

    @Test
    fun plusInTheLibraryAddsOneOfThatPrinting() {
        val s = inTheLibrary(row("Sol Ring", qty = 3))
        val write = assertIs<CountWrite.Cards>(s.peekStep(+1))
        assertEquals(Direction.ADD, write.direction)
        assertEquals("matt0001", write.collection)
        assertEquals("1 Sol Ring (M3C) 409", write.line)
    }

    @Test
    fun minusInTheLibraryRemovesOneAndKeepsTheFinish() {
        val s = inTheLibrary(row("Sol Ring", qty = 3, finish = "foil"))
        val write = assertIs<CountWrite.Cards>(s.peekStep(-1))
        assertEquals(Direction.REMOVE, write.direction)
        assertEquals("1 Sol Ring (M3C) 409 foil", write.line)
    }

    @Test
    fun somebodyElsesLibraryHasNoStepper() {
        val s = inTheLibrary(row("Sol Ring", qty = 3, owner = "k4yy0003"))
        assertNull(s.peekStepper, "Matt can step Kayla's collection")
    }

    // ------------------------------------------------- the card page

    private fun printing(set: String, qty: Int, owner: String, finish: String = "nonfoil") =
        Printing(1, set, null, "1", finish, qty, null, owner = owner)

    private fun onTheCard(vararg printings: Printing) = AppState(admin = me)
        .openCard(CardRef("sol ring"), "Sol Ring")
        .copy(card = CardDetail(name = "Sol Ring", nameNorm = "sol ring", printings = printings.toList()))

    @Test
    fun theCardPageCountsOnlyTheCollectionBeingRead() {
        val s = onTheCard(printing("m3c", 2, "matt0001"), printing("c21", 1, "matt0001"), printing("cmr", 4, "k4yy0003"))
        assertEquals(3, assertNotNull(s.cardStepper, "no stepper on the card page").count, "Kayla's copies were counted")
    }

    @Test
    fun plusOnTheCardPageAddsTheirMostHeldPrinting() {
        val s = onTheCard(printing("c21", 1, "matt0001"), printing("m3c", 2, "matt0001"))
        val write = assertIs<CountWrite.Cards>(s.cardStep(+1))
        assertEquals(Direction.ADD, write.direction)
        assertEquals("1 Sol Ring (M3C) 1", write.line)
    }

    @Test
    fun aCardNobodyHereOwnsCanStillBeAddedByName() {
        val s = onTheCard(printing("cmr", 4, "k4yy0003"))
        assertEquals(0, assertNotNull(s.cardStepper).count)
        val write = assertIs<CountWrite.Cards>(s.cardStep(+1))
        assertEquals("1 Sol Ring", write.line)
        assertNull(s.cardStep(-1), "there is nothing here to take away")
    }

    // ------------------------------------------------- in flight

    @Test
    fun bothButtonsAreOffWhileACountIsBeingWritten() {
        val s = inTheLibrary(row("Sol Ring", qty = 3)).countingStarted()
        val stepper = assertNotNull(s.peekStepper)
        assertFalse(stepper.canAddOne, "plus still presses while the last one is in flight")
        assertFalse(stepper.canTakeOne, "minus still presses while the last one is in flight")
        assertNull(s.peekStep(+1))
    }

    // ------------------------------------------------- said once

    // Matt: "why is there still s count button?!?! DON'T YOU THINK
    // THAT'S A LITTLE FUCKING REDUNDANT?! same with remove?! same
    // with the "1x in deck"?!?!?!" Wherever the stepper shows the
    // number, nothing else on the sheet says it or sets it again.

    @Test
    fun aDeckYouCanStepDoesNotAlsoSayHowManyAreInIt() {
        val ring = deckCard("Sol Ring", qty = 2, owned = 5)
        val c = assertNotNull(overADeck(ring).peekCard(ring).peeked)
        assertFalse(c.tags.any { "in deck" in it.text }, "the sheet still says it beside the stepper: ${c.tags}")
        assertTrue(c.tags.any { it.text == "5 owned" }, "the collection's count went too: ${c.tags}")
    }

    @Test
    fun aDeckYouCanStepOffersSwapAndNotCountOrRemove() {
        val ring = deckCard("Sol Ring", qty = 2, owned = 5)
        val c = assertNotNull(overADeck(ring).peekCard(ring).peeked)
        assertEquals(listOf(Tweak.SWAP), c.tweaks(admin = true), "the sheet repeats what the stepper does")
    }

    @Test
    fun aCommanderHasNoStepperSoKeepsItsCountAndItsButtons() {
        val alela = deckCard("Alela, Artful Provocateur", qty = 1, owned = 1, role = "commander")
        val c = assertNotNull(overADeck(alela).peekCard(alela).peeked)
        assertTrue(c.tags.any { it.text == "1× in deck" }, "nothing says how many: ${c.tags}")
        assertEquals(listOf(Tweak.QUANTITY, Tweak.SWAP, Tweak.REMOVE), c.tweaks(admin = true))
    }

    @Test
    fun somebodyElsesDeckKeepsItsCountAndItsButtons() {
        val kayla = Admin().signIn(Account(key = "k4yy0003"), "t")
        val ring = deckCard("Sol Ring", qty = 2, owned = 5)
        val c = assertNotNull(overADeck(ring, admin = kayla).peekCard(ring).peeked)
        assertTrue(c.tags.any { it.text == "2× in deck" }, "no stepper and nothing says how many: ${c.tags}")
        assertEquals(listOf(Tweak.QUANTITY, Tweak.SWAP, Tweak.REMOVE), c.tweaks(admin = true))
        assertEquals(emptyList(), c.tweaks(admin = false), "a locked app is offered changes")
    }

    @Test
    fun aLibraryYouCanStepDoesNotAlsoSayHowManyYouOwn() {
        val c = assertNotNull(inTheLibrary(row("Sol Ring", qty = 3)).peeked)
        assertFalse(c.tags.any { "owned" in it.text }, "the sheet still says it beside the stepper: ${c.tags}")
        assertTrue(c.tags.any { it.text == "1 free" }, "how many are spare went too: ${c.tags}")
        assertEquals(emptyList(), c.tweaks(admin = true), "the Library has no deck to change")
    }
}
