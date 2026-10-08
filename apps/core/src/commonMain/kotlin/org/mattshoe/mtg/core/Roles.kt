package org.mattshoe.mtg.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Two roles, and only two.
 *
 * Matt: "2 roles: user and admin. Every new account gets the user
 * role. Only SPECIFIC accounts that I DECIDE get the admin role."
 *
 * Strings rather than an enum, because the column is a string and the
 * wire is a string: an enum here would mean a third state for
 * "something else arrived", which is exactly the case [of] already
 * handles by answering `user`. The floor is the safe answer to
 * anything unrecognised.
 */
object Role {
    const val USER = "user"
    const val ADMIN = "admin"

    val all = listOf(USER, ADMIN)

    /** Whatever the database or the wire said, as one of the two. */
    fun of(raw: String?): String = if (raw == ADMIN) ADMIN else USER
}

/**
 * One account, as the admin screen lists them.
 *
 * No email. A role list is not a mailing list — the server does not
 * send one and this has nowhere to put it. What identifies somebody
 * here is their name and the key their collection is shared by.
 */
data class Person(
    val key: String,
    val name: String? = null,
    val avatar: String? = null,
    val role: String = Role.USER,
) {
    val isAdmin: Boolean get() = role == Role.ADMIN

    /** What to call them. An account can arrive with no name on it. */
    val shownName: String get() = name?.takeIf { it.isNotBlank() } ?: key

    /** The one the button offers, which is the one they do not have. */
    val otherRole: String get() = if (isAdmin) Role.USER else Role.ADMIN

    /**
     * Where their collection actually lives.
     *
     * `/c/<key>`, because that is what an address carries. The screen
     * showed `/c/<slug>` for a while, which is not a page anybody can
     * open — Matt: "why is the fucking slug still not the GOD DAMN
     * USER KEY LIKE YOU FUCKING SAID". There is no slug any more.
     */
    val address: String? get() = key.takeIf { it.isNotBlank() }?.let { "/c/$it" }

    /** Everything worth matching a search against. */
    internal val haystack: String get() = listOf(name.orEmpty(), key, role)
        .joinToString(" ")
        .lowercase()
}

/**
 * Who is there, on the Admin Settings screen.
 *
 * `changing` is one key rather than a flag, so a press greys out the
 * row it was made on and leaves every other row live. A list that
 * goes dead all over on one press reads as broken.
 */
data class People(
    val rows: List<Person> = emptyList(),
    /**
     * What is typed in the search box.
     *
     * Matt: "YOU DON'T WANT ME TO BE ABLE TO FUCKING SEARCH?!?!" —
     * fair, on a screen that will one day hold every account there
     * is. It narrows what is drawn and nothing else: a role set while
     * a search is on lands on the row it was set on, not on whatever
     * happens to be visible.
     */
    val query: String = "",
    val busy: Boolean = false,
    val changing: String? = null,
    val error: String? = null,
) {

    /** Who the list draws, which is everybody until somebody types. */
    val shown: List<Person>
        get() {
            val term = query.trim().lowercase()
            return if (term.isEmpty()) rows else rows.filter { term in it.haystack }
        }

    /** A search that found nobody, which is not the same as no accounts. */
    val nothingMatched: Boolean get() = rows.isNotEmpty() && shown.isEmpty()

    fun searching(term: String) = copy(query = term)

    fun loading() = copy(busy = true, error = null)

    fun loaded(found: List<Person>) = copy(rows = found, busy = false, changing = null, error = null)

    /**
     * Nothing stale under an error.
     *
     * A list left on screen beneath "could not load" reads as the
     * current state of the database, which is the one thing it is
     * certainly not.
     */
    fun failed(message: String) = copy(rows = emptyList(), busy = false, changing = null, error = message)

    fun changing(key: String) = copy(changing = key, error = null)

    fun isChanging(key: String) = changing == key

    /** It came back: that row, and only that row, has the new role. */
    fun changed(key: String, role: String) = copy(
        rows = rows.map { if (it.key == key) it.copy(role = Role.of(role)) else it },
        changing = null,
        error = null,
    )

    /** It was refused: the row stays as it was and the screen says why. */
    fun refused(message: String) = copy(changing = null, error = message)

    val admins: Int get() = rows.count { it.isAdmin }

    /**
     * Whether the screen offers to change that person's role.
     *
     * Everybody, always. Matt: "I want to be able to assign and
     * remove roles at will!!!! I don't want to need you for it!!!"
     *
     * This used to withhold the button from the last admin taking
     * their own role away, which — on a database with one account —
     * meant a screen with one row and nothing to press at all.
     */
    fun mayChange(key: String, me: String?): Boolean = rows.any { it.key == key }

    /**
     * Whether that change is the one nothing here can undo.
     *
     * The last admin taking their own role away leaves a database no
     * browser can promote anybody from; `ADMIN_PASSWORD` and a script
     * are the way back. The row says so. It does not refuse.
     */
    fun strands(key: String, me: String?): Boolean {
        val row = rows.firstOrNull { it.key == key } ?: return false
        return row.isAdmin && key == me && admins <= 1
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * `{"users":[...]}` in, people out.
         *
         * Anything unreadable is an empty list rather than a crash on
         * a screen that is already about things going wrong.
         */
        fun decode(body: String): List<Person> = try {
            json.parseToJsonElement(body).jsonObject["users"]?.jsonArray.orEmpty().map { row ->
                val o = row.jsonObject
                fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
                Person(
                    key = str("key").orEmpty(),
                    name = str("name"),
                    avatar = str("avatar"),
                    role = Role.of(str("role")),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

private fun kotlinx.serialization.json.JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
    this ?: emptyList()

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (this is kotlinx.serialization.json.JsonNull) null else content
