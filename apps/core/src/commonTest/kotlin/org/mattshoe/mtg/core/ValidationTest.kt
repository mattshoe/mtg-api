package org.mattshoe.mtg.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wizard's answer from `/cards/validate`.
 *
 * `bad` and `suggestions` are what a screen actually renders — the rows
 * worth a red mark and the ones worth a one-tap fix — so they need
 * checking against a mixed answer, not just a request that came back
 * clean.
 */
class ValidationTest {

    private fun ok(name: String) = NameCheck(name = name, nameNorm = name.lowercase(), ok = true, source = "collection")
    private fun unknown(name: String, suggestion: String? = null) =
        NameCheck(name = name, nameNorm = name.lowercase(), ok = false, suggestion = suggestion)

    @Test
    fun badIsOnlyTheRowsThatDidNotCheckOut() {
        val v = Validation(
            cards = listOf(ok("Sol Ring"), unknown("Sol Rnig"), ok("Arcane Signet")),
        )
        assertEquals(listOf("Sol Rnig"), v.bad.map { it.name })
    }

    @Test
    fun aCleanListHasNothingBad() {
        val v = Validation(cards = listOf(ok("Sol Ring"), ok("Arcane Signet")))
        assertTrue(v.bad.isEmpty())
    }

    @Test
    fun suggestionsPairTheTypedNameWithTheFix() {
        // A bad row with no suggestion is still worth flagging, but
        // there is nothing to offer a one-tap fix for.
        val v = Validation(
            cards = listOf(
                unknown("Sol Rnig", "Sol Ring"),
                unknown("Not A Card"),
            ),
        )
        assertEquals(listOf("Sol Rnig" to "Sol Ring"), v.suggestions)
    }

    /**
     * A `NameCheck` arrives from the Worker as JSON, so a round trip
     * through its generated serializer has to come back the row it
     * started as — the field names coming off the wire are the
     * snake_case `name_norm`, not the Kotlin property.
     */
    @Test
    fun aNameCheckSurvivesTheWireFormatItActuallyArrivesIn() {
        val original = NameCheck(name = "Sol Ring", nameNorm = "sol ring", ok = true, source = "collection")
        val json = Json.encodeToString(NameCheck.serializer(), original)
        assertTrue("\"name_norm\":\"sol ring\"" in json, json)
        assertEquals(original, Json.decodeFromString(NameCheck.serializer(), json))
    }
}
