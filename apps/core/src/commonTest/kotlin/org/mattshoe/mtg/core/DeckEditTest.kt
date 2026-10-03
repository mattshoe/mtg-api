package org.mattshoe.mtg.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun theLineCountIsTheSameRuleEveryPastedListUses() {
        // Not reimplemented here — if `lineCount` ever drifted from
        // `DeckList.countCards`, the number shown above the box and
        // the number the server actually bills for would disagree.
        val s = DeckEditState.of(deck, cards)
        assertEquals(DeckList.countCards(s.list), s.lineCount)
        assertEquals(2, s.lineCount)
    }

    @Test
    fun workingMarksItBusyAndDropsTheLastComplaint() {
        val s = DeckEditState.of(deck, cards)
            .failed("network error", listOf("line 3"))
            .working()
        assertTrue(s.busy)
        assertEquals(null, s.error)
        assertTrue(s.errors.isEmpty())
    }

    @Test
    fun aFailureWithNoLineLevelDetailLeavesTheListEmptyRatherThanNull() {
        // The common case: the server refused the whole thing with one
        // message and nothing line-by-line to show under it.
        val s = DeckEditState.of(deck, cards).failed("deck not found")
        assertEquals("deck not found", s.error)
        assertTrue(s.errors.isEmpty())
    }
}

/** The two generated serializers that make the wire's positional arrays readable. */
class DeckPlanSerializersTest {

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test
    fun everyWireTypeHasADiscoverableSerializer() {
        // Mechanical, but real: if `@Serializable(with = ...)` were
        // ever dropped from one of these, `Json.decodeFromString` on
        // anything containing it would fail to compile or silently
        // use the wrong shape, not fail at runtime where this would
        // catch it instead.
        assertEquals("Tally", Tally.serializer().descriptor.serialName)
        assertEquals("Shift", Shift.serializer().descriptor.serialName)
        assertEquals("org.mattshoe.mtg.core.FreedCard", FreedCard.serializer().descriptor.serialName)
        assertEquals("org.mattshoe.mtg.core.DeckRef", DeckRef.serializer().descriptor.serialName)
    }

    @Test
    fun aTallyThatIsNotAnArrayAtAllFailsLoudlyRatherThanSilently() {
        // A plan is read, never written by hand, so the only way this
        // shape goes wrong is the server changing its wire format —
        // and that should come back as a clear parse failure, not a
        // Tally quietly holding "" and nought.
        val json = Json { ignoreUnknownKeys = true }
        assertFailsWith<SerializationException> {
            json.decodeFromString<DeckPlan>("""{"added":[{"name":"Sol Ring","qty":1}]}""")
        }
    }

    @Test
    fun aShiftThatIsNotAnArrayAtAllFailsLoudlyRatherThanSilently() {
        val json = Json { ignoreUnknownKeys = true }
        assertFailsWith<SerializationException> {
            json.decodeFromString<DeckPlan>("""{"changed":["not an array"]}""")
        }
    }

    @Test
    fun aPlanIsNeverWrittenBackToTheServerAsJson() {
        // "plans are only ever read" is the whole design: the UI shows
        // what the dry run said and sends the edited list back, never
        // the plan itself. Both serializers refuse to encode, on
        // purpose, so a future caller who tries learns immediately.
        assertFailsWith<SerializationException> {
            TallySerializer.serialize(NoOpEncoder, Tally("Sol Ring", 1))
        }
        assertFailsWith<SerializationException> {
            ShiftSerializer.serialize(NoOpEncoder, Shift("Forest", 8, 10))
        }
    }
}

/** An encoder that does nothing: only here to prove the two serializers refuse to run at all. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private object NoOpEncoder : kotlinx.serialization.encoding.Encoder {
    override val serializersModule = kotlinx.serialization.modules.EmptySerializersModule()
    override fun encodeBoolean(value: Boolean) {}
    override fun encodeByte(value: Byte) {}
    override fun encodeChar(value: Char) {}
    override fun encodeDouble(value: Double) {}
    override fun encodeFloat(value: Float) {}
    override fun encodeInt(value: Int) {}
    override fun encodeLong(value: Long) {}
    override fun encodeShort(value: Short) {}
    override fun encodeString(value: String) {}
    override fun encodeEnum(enumDescriptor: kotlinx.serialization.descriptors.SerialDescriptor, index: Int) {}
    override fun encodeInline(descriptor: kotlinx.serialization.descriptors.SerialDescriptor) = this
    override fun encodeNull() {}
    override fun beginStructure(descriptor: kotlinx.serialization.descriptors.SerialDescriptor) =
        throw UnsupportedOperationException("never reached: serialize throws first")
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

    @Test
    fun itCannotFireAgainWhileTheFirstPressIsStillInFlight() {
        val t = s.planned(Disassembly(freed = 1)).working()
        assertTrue(t.busy)
        assertFalse(t.canGo, "a second disassemble could be sent while the first was in flight")
    }

    @Test
    fun aFailureLeavesTheWarningReadableSoItCanBeTriedAgain() {
        val t = s.planned(Disassembly(freed = 42)).working().failed("the deck was already gone")
        assertFalse(t.busy)
        assertEquals("the deck was already gone", t.error)
        // The dry run's own numbers survive the failure, since this
        // is the same confirmation box, not a reset one.
        assertTrue(t.warning.contains("42 cards"))
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
    fun everyFieldAPlanCanCarryDecodesIncludingTheOnesTheFirstTestLeftOut() {
        // `applied`, `created`, `slug` and `errors` only ever showed up
        // on the apply response, never the dry run, so a decode test
        // built only from a dry-run body never exercised them.
        val body = """
            {"deck":{"slug":"alela","name":"Alela","owner":"matt"},
             "commander":"Alela, Artful Provocateur","commander_changed":false,
             "rows":2,"card_count":2,"owned_count":1,
             "added":[],"removed":[],"changed":[],"newly_missing":[],
             "acquired":[],"returned":[],
             "applied":true,"dry_run":false,"created":true,
             "slug":"alela-2","errors":["one line could not be read"]}
        """.trimIndent()
        val plan = json.decodeFromString<DeckPlan>(body)
        assertTrue(plan.applied)
        assertTrue(plan.created)
        assertEquals("alela-2", plan.slug)
        assertEquals(listOf("one line could not be read"), plan.errors)
        assertTrue(plan.nothingChanges, "an empty diff was read as a change")
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

/**
 * The deck rows were written by hand, so every column carries prose the
 * tile has no room for. These pull the one fact out of each.
 */
class DeckProseTest {

    private fun deck(name: String = "Alela", colors: String? = null, commander: String? = null) =
        Deck("alela", name, "matt", commander, colors, null, null)

    @Test
    fun bareLettersAreTakenAsTheyAre() {
        // What the database stores when the commander was looked up
        // properly, rather than typed.
        assertEquals("UW", deck(colors = "UW").identity)
        assertEquals("BUW", deck(colors = "wub").identity)
    }

    @Test
    fun manaSymbolsWin() {
        assertEquals("BRUW", deck(colors = "{W}{U}{B}{R} (Breya's identity)").identity)
    }

    @Test
    fun colourWordsAreReadWhenThereAreNoSymbols() {
        assertEquals("GU", deck(colors = "Simic (Green/Blue)").identity)
        assertEquals("BG", deck(colors = "Golgari (Black/Green)").identity)
    }

    @Test
    fun fiveColourIsSpeltEveryWhichWay() {
        assertEquals("BGRUW", deck(colors = "Five-color (WUBRG)").identity)
        assertEquals("BGRUW", deck(colors = "five colour").identity)
    }

    @Test
    fun nothingUsableIsColourless() {
        assertEquals("", deck(colors = null).identity)
        assertEquals("", deck(colors = "???").identity)
        assertTrue(deck(colors = null).colorPips.isEmpty())
    }

    @Test
    fun readingItACharacterAtATimeWouldBeNonsense() {
        // The bug this replaced: "Five-color (WUBRG)" rendered as
        // eighteen pips, one per letter of the sentence.
        assertEquals(5, deck(colors = "Five-color (WUBRG)").colorPips.size)
    }

    @Test
    fun theTileNameStopsAtTheEmDash() {
        assertEquals(
            "Dance of the Elements",
            deck(name = "Dance of the Elements — Lorwyn Eclipsed Commander Precon").title,
        )
        assertEquals("Alela", deck(name = "Alela").title)
    }

    @Test
    fun theBannerPrefersACardWeOwn() {
        val url = CardQueries.banner("abcdef12-3456", "Alela, Artful Provocateur")
        assertEquals("https://cards.scryfall.io/art_crop/front/a/b/abcdef12-3456.jpg", url)
    }

    @Test
    fun andFallsBackToAskingScryfallByName() {
        val url = CardQueries.banner(null, "Alela, Artful Provocateur")
        assertTrue(url!!.startsWith("https://api.scryfall.com/cards/named?exact="), url)
        assertTrue(url.contains("Alela%2C%20Artful%20Provocateur"), url)
        assertTrue(url.endsWith("&format=image&version=art_crop"), url)
    }

    @Test
    fun andHasNothingToShowForADeckWithNoCommander() {
        assertEquals(null, CardQueries.banner(null, null))
        assertEquals(null, CardQueries.banner(null, "  "))
    }
}
