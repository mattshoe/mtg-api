package org.mattshoe.mtg.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeckEditStateTest {

    private val deck = Deck(
        slug = "alela", name = "Alela", owner = "matt",
        commander = "Alela, Artful Provocateur (ELD) 324",
        colors = "UWB", bracket = 3, artId = null,
    )

    private val cards = listOf(
        DeckCard("Alela, Artful Provocateur", 1, "commander", 1),
        DeckCard("Sol Ring", 1, null, 1),
        DeckCard("Arcane Signet", 1, null, 0),
    )

    @Test
    fun theCommanderComesOutOfTheList() {
        val s = DeckEditState.of(deck, cards)
        assertEquals("Alela, Artful Provocateur", s.commander)
        assertEquals("1 Sol Ring\n1 Arcane Signet", s.list)
    }

    @Test
    fun aDeckWithNoCommanderRowFallsBackToTheStoredName() {
        // Stored as "Alela, Artful Provocateur (ELD) 324" often enough
        // that matching the whole string finds nothing.
        val s = DeckEditState.of(deck, cards.drop(1))
        assertEquals("Alela, Artful Provocateur", s.commander)
    }

    @Test
    fun savingIsNotOfferedUntilTheServerHasSaidWhatWouldHappen() {
        val s = DeckEditState.of(deck, cards)
        assertTrue(s.canReview)
        assertFalse(s.canSave)
        assertTrue(s.planned(DeckPlan(cardCount = 2)).canSave)
    }

    @Test
    fun editingAfterAReviewTakesSaveAwayAgain() {
        // What is approved has to be what is written.
        val reviewed = DeckEditState.of(deck, cards).planned(DeckPlan(cardCount = 2))
        val edited = reviewed.typeList("1 Sol Ring\n1 Mana Crypt")
        assertTrue(edited.stale)
        assertFalse(edited.canSave)
    }

    @Test
    fun changingOnlyTheCommanderAlsoCountsAsAnEdit() {
        val reviewed = DeckEditState.of(deck, cards).planned(DeckPlan(cardCount = 2))
        assertTrue(reviewed.typeCommander("Kenrith").stale)
    }

    @Test
    fun aFailureDropsThePlanSoSaveCannotFireOnIt() {
        val s = DeckEditState.of(deck, cards)
            .planned(DeckPlan(cardCount = 2))
            .failed("2 line(s) could not be read", listOf("xx", "yy"))
        assertFalse(s.canSave)
        assertEquals(listOf("xx", "yy"), s.errors)
    }

    @Test
    fun savingTwiceIsNotOffered() {
        val s = DeckEditState.of(deck, cards).planned(DeckPlan()).finished(DeckPlan(applied = true))
        assertTrue(s.saved)
        assertFalse(s.canSave)
    }

    @Test
    fun anEmptyListIsNotWorthReviewing() {
        assertFalse(DeckEditState(slug = "x").canReview)
    }
}

class DisassembleStateTest {

    private val s = DisassembleState(slug = "alela", deckName = "Alela", owner = "matt")

    @Test
    fun itWillNotFireBeforeTheDryRunComesBack() {
        assertFalse(s.canGo)
        assertTrue(s.planned(Disassembly(freed = 42)).canGo)
    }

    @Test
    fun theWarningSaysAllOfIt() {
        val w = s.planned(Disassembly(freed = 42)).warning
        assertTrue(w.contains("Alela is deleted"), w)
        assertTrue(w.contains("42 cards"), w)
        assertTrue(w.contains("matt's bulk"), w)
        assertTrue(w.contains("cannot be undone"), w)
    }

    @Test
    fun oneCardIsNotOneCards() {
        assertTrue(s.planned(Disassembly(freed = 1)).warning.contains("1 card it"))
    }

    @Test
    fun doneMeansDone() {
        assertFalse(s.planned(Disassembly(freed = 1)).finished().canGo)
    }
}

class DeckPlanWireTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun thePositionalArraysBecomeSomethingReadable() {
        val body = """
            {"deck":{"slug":"alela","name":"Alela","owner":"matt"},
             "commander":"Alela, Artful Provocateur","commander_changed":true,
             "rows":99,"card_count":99,"owned_count":96,
             "added":[["Sol Ring",1],["Mana Crypt",1]],
             "removed":[["Fellwar Stone",1]],
             "changed":[["Forest",8,10]],
             "newly_missing":["Mox Diamond"],
             "acquired":[["Mana Crypt",1]],
             "returned":[["Fellwar Stone",1]],
             "applied":false,"dry_run":true}
        """.trimIndent()
        val plan = json.decodeFromString<DeckPlan>(body)

        assertEquals("alela", plan.deck.slug)
        assertEquals(listOf(Tally("Sol Ring", 1), Tally("Mana Crypt", 1)), plan.added)
        assertEquals(listOf(Tally("Fellwar Stone", 1)), plan.removed)
        assertEquals(listOf(Shift("Forest", 8, 10)), plan.changed)
        assertEquals(listOf("Mox Diamond"), plan.newlyMissing)
        assertEquals(1, plan.buying)
        assertTrue(plan.commanderChanged)
        assertFalse(plan.nothingChanges)
    }

    @Test
    fun anEmptyPlanSaysSo() {
        val plan = json.decodeFromString<DeckPlan>("""{"card_count":99,"dry_run":true}""")
        assertTrue(plan.nothingChanges)
        assertEquals(0, plan.buying)
    }

    @Test
    fun theDisassembleDryRunReads() {
        val body = """
            {"deck":{"slug":"alela","name":"Alela","owner":"matt"},
             "freed":96,"cards":[{"name":"Sol Ring","qty":1}],
             "rows":{"deck_cards":99,"deck_notes":2},
             "applied":false,"dry_run":true}
        """.trimIndent()
        val d = json.decodeFromString<Disassembly>(body)
        assertEquals(96, d.freed)
        assertEquals("Sol Ring", d.cards.single().name)
        assertFalse(d.applied)
    }
}
