package org.mattshoe.mtg.core

/**
 * Whose a row is, as SQL.
 *
 * A card or deck belongs to `users.id`, which is private: no screen
 * ever holds one. What the app holds is the owner's public key — the
 * one `#/c/<key>` carries — so every query names an owner by key and
 * the database turns it into the id, and every row comes back with
 * its owner's key and name rather than its id.
 *
 * The key is an address and only an address. Reading by it is open to
 * anybody; writing is decided by the Worker from the session, and
 * nothing here takes part in that.
 */
object Owners {
    /** The owning account's public key, for an `owner_id` column. */
    fun keyOf(col: String) = "(SELECT key FROM users WHERE id = $col)"

    /** What to call the owning account: its display name, or its key if it has none. */
    fun nameOf(col: String) = "(SELECT COALESCE(display_name, key) FROM users WHERE id = $col)"

    /** True for the rows the account whose key is bound to the `?` owns. */
    fun owns(col: String) = "$col = (SELECT id FROM users WHERE key = ?)"
}
