package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app holds keys, never ids, and asks the database to turn one into
 * the other.
 *
 * Matt: "THE FUCKING USER ID NEEDS TO BE PRIVATE AND DIFFERENT FROM USER
 * KEY!!!!!" A card belongs to `users.id`; the app only ever has the
 * owner's public key, so every scoped query binds the key and resolves
 * it inside the statement. Whether the statement actually runs against
 * the schema is `test/core-sql.test.js`, which executes every one of
 * these through the Worker's own D1.
 */
class OwnerIsAnIdTest {

    private val matt = Account(key = "e7de0cb1", name = "Matt Shoemaker")

    @Test
    fun theLibraryBindsTheOwnersKeyAndResolvesItToAnId() {
        val sql = conditions(Filters(owner = "bprh3d2s"))
        assertTrue(
            "c.owner_id = (SELECT id FROM users WHERE key = ?)" in sql.sql,
            "the Library filters on something other than the id its key names: ${sql.sql}",
        )
        assertEquals(listOf<Any?>("bprh3d2s"), sql.params)
        assertFalse("c.owner =" in sql.sql, "the retired owner column is still read: ${sql.sql}")
    }

    @Test
    fun anUnknownScopeIsStillNoScopeAtAll() {
        // An empty owner is "nobody said", not "everybody".
        val sql = conditions(Filters(owner = ""))
        assertTrue("1=0" in sql.sql, "an empty owner read every collection: ${sql.sql}")
        assertTrue(sql.params.isEmpty())
    }

    @Test
    fun aDeckIsOpenedByItsKey() {
        val sql = DeckQueries.cards("q8ytka9m")
        assertTrue("WHERE d.key = ?" in sql.sql, "a deck was looked up by something other than its key")
        assertEquals(listOf<Any?>("q8ytka9m"), sql.params)
    }

    @Test
    fun oneCollectionsDecksAreAskedForByKey() {
        val sql = DeckQueries.all("e7de0cb1")
        assertTrue("d.owner_id = (SELECT id FROM users WHERE key = ?)" in sql.sql, sql.sql)
        assertEquals(listOf<Any?>("e7de0cb1"), sql.params)
    }

    @Test
    fun anAccountOwnsTheCollectionAtItsKeyAndNoOther() {
        assertTrue(matt.owns("e7de0cb1"))
        assertFalse(matt.owns("bprh3d2s"), "an account could edit a collection that is not at its key")
        assertFalse(matt.owns(""))
    }

    @Test
    fun yourOwnCollectionIsTheOneAtYourKey() {
        val s = AppState(admin = Admin().signIn(matt, "t"))
        assertEquals("e7de0cb1", s.viewing)
        assertEquals("e7de0cb1", s.scopedLibrary().filters.owner)
    }

    @Test
    fun renamingADeckDoesNotMoveIt() {
        val r = RenameState(key = "q8ytka9m", was = "Milly Moth").typed("Milly Moth, Again")
        assertTrue(r.canSave)
        assertEquals("q8ytka9m", r.key)
    }

    @Test
    fun aRowsOwnerComesBackAsAKeyAndAName() {
        val sql = CardQueries.printings("sol ring").sql
        assertTrue("(SELECT key FROM users WHERE id = c.owner_id) AS owner" in sql, sql)
        assertTrue("AS owner_name" in sql, sql)
    }
}
