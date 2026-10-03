package org.mattshoe.mtg.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wizard's core data: whose collection, which direction, and the
 * rows it moves. None of this had ever been driven directly — it only
 * ever ran as a side effect of some other screen's test, which is how
 * `Change.isIncrease` or a wire decode could silently flip and nothing
 * would notice until a review screen said "+1" for a card that was
 * just removed.
 */
class ModelTest {

    // ------------------------------------------------------------- Owner

    @Test
    fun ownerLabelsAreCapitalisedSlugs() {
        assertEquals("matt", Owner.MATT.slug)
        assertEquals("Matt", Owner.MATT.label)
        assertEquals("kayla", Owner.KAYLA.slug)
        assertEquals("Kayla", Owner.KAYLA.label)
    }

    @Test
    fun thereAreExactlyTwoOwnersAndNoDefault() {
        // "There is no default, anywhere, on purpose" is the comment
        // on the type — proving the enum still has exactly these two
        // entries is what keeps that true.
        assertEquals(listOf(Owner.MATT, Owner.KAYLA), Owner.entries)
    }

    // --------------------------------------------------------- Direction

    @Test
    fun addAndRemoveEachPointAtTheirOwnEndpoint() {
        assertEquals("/cards/add", Direction.ADD.path)
        assertEquals("/cards/remove", Direction.REMOVE.path)
    }

    @Test
    fun theWizardsFirstQuestionDependsOnDirection() {
        assertEquals("What are you adding?", Direction.ADD.question)
        assertEquals("What are you removing?", Direction.REMOVE.question)
        // Asked the other way round: the two must not share a question,
        // since that question is the only thing on screen telling a
        // person which flow they are in.
        assertFalse(Direction.ADD.question == Direction.REMOVE.question)
    }

    @Test
    fun directionVerbsReadAsImperatives() {
        assertEquals("Add", Direction.ADD.verb)
        assertEquals("Remove", Direction.REMOVE.verb)
    }

    // -------------------------------------------------------------- Change

    private fun change(before: Int, after: Int, scryfallId: String = "") = Change(
        name = "Sol Ring", set = "lea", collectorNumber = "1", finish = "nonfoil",
        before = before, after = after, scryfallId = scryfallId,
    )

    @Test
    fun isIncreaseIsStrictlyMoreNotJustDifferent() {
        assertTrue(change(before = 1, after = 2).isIncrease)
        assertFalse(change(before = 2, after = 1).isIncrease)
        // Equal counts are not a change of either kind — a row that
        // gets rewritten with the same quantity (a re-import, say)
        // must read as neither an increase nor a decrease.
        assertFalse(change(before = 3, after = 3).isIncrease)
    }

    @Test
    fun deltaIsSignedAfterMinusBefore() {
        assertEquals(1, change(before = 0, after = 1).delta)
        assertEquals(-2, change(before = 3, after = 1).delta)
        assertEquals(0, change(before = 4, after = 4).delta)
    }

    @Test
    fun signReadsPlusOrMinusWithNoSignForZero() {
        // What actually prints on the review row.
        assertEquals("+1", change(before = 0, after = 1).sign)
        assertEquals("+4", change(before = 2, after = 6).sign)
        assertEquals("-2", change(before = 3, after = 1).sign)
        // Zero has no useful sign, and `delta` is already 0, so this
        // falls into the `else` branch and reads as a bare "0" rather
        // than a wrong "+0".
        assertEquals("0", change(before = 5, after = 5).sign)
    }

    @Test
    fun isNewRequiresStartingFromExactlyZero() {
        assertTrue(change(before = 0, after = 1).isNew)
        // Owning one already and getting a second is a plain increase,
        // not a new printing — conflating the two would make "fresh"
        // on `Applied` count a restock as a new card.
        assertFalse(change(before = 1, after = 2).isNew)
        assertFalse(change(before = 0, after = 0).isNew)
    }

    @Test
    fun isGoneRequiresEndingAtExactlyZeroFromSomething() {
        assertTrue(change(before = 2, after = 0).isGone)
        assertFalse(change(before = 0, after = 0).isGone, "never owned is not the same as emptied")
        assertFalse(change(before = 2, after = 1).isGone)
    }

    @Test
    fun artIsNullWithoutAScryfallId() {
        assertNull(change(before = 1, after = 2, scryfallId = "").art)
    }

    @Test
    fun artBuildsTheCroppedScryfallUrlFromTheId() {
        val art = change(before = 1, after = 2, scryfallId = "abcd1234").art
        assertEquals("https://cards.scryfall.io/art_crop/front/a/b/abcd1234.jpg", art)
    }

    // -------------------------------------------------------------- Applied

    @Test
    fun copiesSumsTheAbsoluteDeltaNotTheSignedOne() {
        // Mixed adds and removes in one apply have to add up to how
        // many cards physically moved — a signed sum would let a
        // +3/-3 batch report "0 copies moved".
        val applied = Applied(
            changes = listOf(
                change(before = 0, after = 3),
                change(before = 5, after = 2),
            ),
        )
        assertEquals(6, applied.copies)
    }

    @Test
    fun copiesOfNoChangesIsZero() {
        assertEquals(0, Applied().copies)
    }

    @Test
    fun freshCountsOnlyThePrintingsThatStartedAtZero() {
        val applied = Applied(
            changes = listOf(
                change(before = 0, after = 1),
                change(before = 0, after = 4),
                change(before = 1, after = 2),
            ),
        )
        assertEquals(2, applied.fresh)
    }

    @Test
    fun emptiedCountsOnlyThePrintingsThatEndedAtZero() {
        val applied = Applied(
            changes = listOf(
                change(before = 2, after = 0),
                change(before = 1, after = 0),
                change(before = 3, after = 1),
            ),
        )
        assertEquals(2, applied.emptied)
    }

    /**
     * `Applied` is what the server's own response body decodes into —
     * this is the wire contract, not an internal detail. A renamed
     * field here (`dry_run` back to `dryRun`, say) would pass every
     * other test in this file while the real app quietly stopped
     * reading whether a run was a dry run at all.
     */
    @Test
    fun appliedDecodesTheServersWireNamesIncludingTheSnakeCaseOne() {
        val applied = Json.decodeFromString<Applied>(
            """{"applied":true,"dry_run":false,"resolved":2,"failed":0,"changes":[],"errors":[],"notes":["ok"]}""",
        )
        assertTrue(applied.applied)
        assertFalse(applied.dryRun)
        assertEquals(2, applied.resolved)
        assertEquals(listOf("ok"), applied.notes)
    }

    @Test
    fun appliedDefaultsToNothingHappenedYet() {
        val applied = Applied()
        assertFalse(applied.applied)
        assertFalse(applied.dryRun)
        assertEquals(0, applied.resolved)
        assertEquals(0, applied.failed)
        assertTrue(applied.changes.isEmpty())
        assertTrue(applied.errors.isEmpty())
        assertTrue(applied.notes.isEmpty())
    }

    // ------------------------------------------------------------- Unlocked

    @Test
    fun unlockedDefaultsToNoTokenAndNoExpiry() {
        val u = Unlocked()
        assertEquals("", u.token)
        assertNull(u.expiresAt)
    }

    @Test
    fun unlockedHoldsWhateverTheServerIssued() {
        val u = Unlocked(token = "abc123", expiresAt = 1_700_000_000L)
        assertEquals("abc123", u.token)
        assertEquals(1_700_000_000L, u.expiresAt)
    }

    @Test
    fun twoUnlockedWithTheSameFieldsAreEqual() {
        // The admin screen compares an `Unlocked` against the one it
        // already holds to decide whether a refresh actually changed
        // anything; a token class without real equality would always
        // say yes.
        assertEquals(Unlocked(token = "t", expiresAt = 5), Unlocked(token = "t", expiresAt = 5))
        assertFalse(Unlocked(token = "t", expiresAt = 5) == Unlocked(token = "t", expiresAt = 6))
        assertEquals(
            Unlocked(token = "t", expiresAt = 5).hashCode(),
            Unlocked(token = "t", expiresAt = 5).hashCode(),
        )
    }

    @Test
    fun unlockedRoundTripsThroughJsonLikeTheAdminEndpointSendsIt() {
        // `Unlocked` is what the admin-unlock endpoint's response body
        // decodes into. Encoding it back out is not something the app
        // does in production, but proving the auto-generated
        // serializer agrees field-for-field with the hand-written
        // properties is what stops a renamed field going out as the
        // old JSON key while the Kotlin side reads the new one.
        val encoded = Json.encodeToString(Unlocked(token = "abc123", expiresAt = 1_700_000_000L))
        assertTrue(encoded.contains("\"token\":\"abc123\""), encoded)
        assertTrue(encoded.contains("\"expires_at\":1700000000"), encoded)
        assertEquals(Unlocked(token = "abc123", expiresAt = 1_700_000_000L), Json.decodeFromString(encoded))
    }

    @Test
    fun unlockedCopyReplacesOnlyTheFieldYouName() {
        // Renewing a token keeps the same expiry calculation path
        // rather than re-deriving it, which is exactly what `copy`
        // buys the caller over rebuilding the whole thing by hand.
        val original = Unlocked(token = "old", expiresAt = 1)
        val renewed = original.copy(token = "new")
        assertEquals("new", renewed.token)
        assertEquals(1, renewed.expiresAt)
        val (token, expiresAt) = renewed
        assertEquals("new", token)
        assertEquals(1, expiresAt)
    }

    // ------------------------------------------------------------ ApiFailure

    @Test
    fun apiFailureCarriesTheServersMessage() {
        val failure = ApiFailure("admin token required")
        assertEquals("admin token required", failure.message)
    }

    @Test
    fun apiFailureIsCatchableAsAnException() {
        // Everything that reads this treats it as an ordinary
        // exception, so it has to actually be throwable as one.
        val thrown = assertFailsWith<ApiFailure> { throw ApiFailure("locked") }
        assertEquals("locked", thrown.message)
    }

    // -------------------------------------------------------- ChangeSerializer

    private fun decode(json: String): Change = Json.decodeFromString(ChangeSerializer, json)

    @Test
    fun decodesTheFullSevenElementWireRow() {
        val change = decode("""["Sol Ring","lea","1","nonfoil",0,3,"abcd1234"]""")
        assertEquals("Sol Ring", change.name)
        assertEquals("lea", change.set)
        assertEquals("1", change.collectorNumber)
        assertEquals("nonfoil", change.finish)
        assertEquals(0, change.before)
        assertEquals(3, change.after)
        assertEquals("abcd1234", change.scryfallId)
    }

    /**
     * The row was six fields before the Scryfall id was added, and a
     * server that has not redeployed yet still sends six. Without the
     * default, a row like this would throw and lose the whole apply
     * review over a field nobody can see anyway.
     */
    @Test
    fun aSixElementRowFromAnOldServerDefaultsScryfallIdToBlank() {
        val change = decode("""["Sol Ring","lea","1","nonfoil",0,3]""")
        assertEquals("", change.scryfallId)
        assertEquals(3, change.after)
    }

    @Test
    fun beforeAndAfterFallBackToZeroWhenNotANumber() {
        // Belt and braces: a malformed count must not take down the
        // whole decode, since one bad row in a batch of fifty should
        // not cost the other forty-nine.
        val change = decode("""["Sol Ring","lea","1","nonfoil","who knows","also not a number"]""")
        assertEquals(0, change.before)
        assertEquals(0, change.after)
    }

    @Test
    fun missingTrailingFieldsAllDefaultRatherThanThrow() {
        val change = decode("""["Sol Ring"]""")
        assertEquals("Sol Ring", change.name)
        assertEquals("", change.set)
        assertEquals("", change.collectorNumber)
        assertEquals("", change.finish)
        assertEquals(0, change.before)
        assertEquals(0, change.after)
        assertEquals("", change.scryfallId)
    }

    @Test
    fun anEmptyArrayDecodesToAllDefaults() {
        assertEquals(Change(name = "", set = "", collectorNumber = "", finish = "", before = 0, after = 0), decode("[]"))
    }

    @Test
    fun aJsonObjectInsteadOfAnArrayFailsRatherThanMisreadingFields() {
        // The wire form is positional, never a map — if the server
        // ever sent an object here, reading it as one would silently
        // take whichever fields happened to share a name rather than
        // failing loudly.
        assertFailsWith<SerializationException> { decode("""{"name":"Sol Ring"}""") }
    }

    /** A decoder with no idea what JSON is — anything that is not `JsonDecoder`. */
    private class NonJsonDecoder : AbstractDecoder() {
        override val serializersModule: SerializersModule = EmptySerializersModule
        override fun decodeElementIndex(descriptor: SerialDescriptor): Int = CompositeDecoder.DECODE_DONE
    }

    @Test
    fun aNonJsonFormatIsRefusedRatherThanMisread() {
        // The wire shape `[name, set, ...]` only exists because JSON
        // has arrays; were this serializer ever reached through
        // protobuf or cbor it must say so rather than quietly
        // returning a blank Change.
        val thrown = assertFailsWith<SerializationException> { ChangeSerializer.deserialize(NonJsonDecoder()) }
        assertEquals("Change is JSON only", thrown.message)
    }

    @Test
    fun theAnnotationWiresChangeToItsSerializerForOrdinaryDecodeCalls() {
        // Everywhere else in the app just writes `Json.decodeFromString<Change>(...)`
        // rather than naming `ChangeSerializer` explicitly — that only
        // works because `@Serializable(with = ChangeSerializer::class)`
        // tells the compiler which serializer answers for the type.
        val change = Json.decodeFromString<Change>("""["Sol Ring","lea","1","nonfoil",0,1]""")
        assertEquals("Sol Ring", change.name)
        assertEquals(1, change.after)
    }

    @Test
    fun serializingAChangeBackOutIsRefused() {
        // Changes are a read-only view of what the server already
        // decided; writing one back out would imply the client could
        // mint its own, which it never does.
        assertFailsWith<SerializationException> {
            Json.encodeToString(
                ChangeSerializer,
                Change(name = "Sol Ring", set = "lea", collectorNumber = "1", finish = "nonfoil", before = 0, after = 1),
            )
        }
    }
}
