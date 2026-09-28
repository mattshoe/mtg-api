package org.mattshoe.mtg.core

/**
 * Somewhere small to keep things between launches.
 *
 * `localStorage` on the web, `SharedPreferences` on Android, and
 * `NSUserDefaults` when iOS arrives. The core never learns which — it
 * asks for a string and puts a string back, so the entry history and
 * the admin token are stored by the same code on both platforms rather
 * than by two that drift.
 */
interface Store {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)

    /** For tests, and for a platform that has nowhere to write. */
    companion object {
        fun inMemory(): Store = object : Store {
            private val map = mutableMapOf<String, String>()
            override fun get(key: String) = map[key]
            override fun put(key: String, value: String) { map[key] = value }
            override fun remove(key: String) { map.remove(key) }
        }
    }
}
