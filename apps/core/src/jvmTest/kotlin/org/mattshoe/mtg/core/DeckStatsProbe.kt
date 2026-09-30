package org.mattshoe.mtg.core

import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test

/**
 * The deck analysis, run over a real deck.
 *
 * Not an assertion and not part of the gate — it prints, so the
 * numbers can be read by somebody who knows what a Magic deck should
 * look like. Skips itself when the network is not there.
 */
class DeckStatsProbe {

    private fun query(sql: String, params: List<String>): List<List<String?>> {
        val body = buildString {
            append("{\"sql\":").append(quote(sql)).append(",\"params\":[")
            append(params.joinToString(",") { quote(it) })
            append("]}")
        }
        val c = URL("https://mtg-api.mattshoe81.workers.dev/query").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("user-agent", "mtg-probe/1.0")
        c.connectTimeout = 8000
        c.readTimeout = 30000
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = c.inputStream.bufferedReader().readText()
        val json = kotlinx.serialization.json.Json.parseToJsonElement(text)
        val obj = json as kotlinx.serialization.json.JsonObject
        val cols = (obj["cols"] as kotlinx.serialization.json.JsonArray).map {
            (it as kotlinx.serialization.json.JsonPrimitive).content
        }
        colsOut = cols
        return (obj["rows"] as kotlinx.serialization.json.JsonArray).map { row ->
            (row as kotlinx.serialization.json.JsonArray).map { v ->
                if (v is kotlinx.serialization.json.JsonNull) null
                else (v as kotlinx.serialization.json.JsonPrimitive).content
            }
        }
    }

    private var colsOut: List<String> = emptyList()

    private fun quote(s: String) = buildString {
        append('"')
        s.forEach {
            when (it) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(it)
            }
        }
        append('"')
    }

    @Test
    fun printTheAnalysisOfARealDeck() {
        // Off by default: CI must not depend on the live database for
        // a test that asserts nothing. `MTG_PROBE=1 ./gradlew ...`
        // when a number looks wrong and you want to see the real one.
        if (System.getenv("MTG_PROBE").isNullOrBlank()) return
        val slug = System.getenv("MTG_PROBE_DECK") ?: "feather-storm"
        val rows = try {
            query(DeckQueries.cards(slug).sql, listOf(slug))
        } catch (e: Exception) {
            println("no network, skipping: ${e.message}")
            return
        }
        val arrays = rows.map { r ->
            kotlinx.serialization.json.JsonArray(
                r.map { v ->
                    if (v == null) kotlinx.serialization.json.JsonNull
                    else kotlinx.serialization.json.JsonPrimitive(v)
                },
            )
        }
        val cards = DeckQueries.decodeCards(colsOut, arrays)
        if (cards.isEmpty()) { println("no such deck: $slug"); return }
        val s = DeckAnalysis.of(cards)

        println("=== $slug: ${s.totalCards} cards, ${s.lands} lands (${s.landShare}%), identity ${s.identity}")
        println("avg mv ${s.averageManaValue}  median ${s.medianManaValue}  missing ${s.missing}")
        println("value ${s.value?.let { (it * 100).toLong() / 100.0 }}  unpriced ${s.unpriced}")
        println("curve:    " + s.curve.joinToString(" ") { "${it.label}:${it.value}" })
        println("pips:     " + s.pips.joinToString(" ") { "${it.note}:${it.value}" })
        println("sources:  " + s.sources.joinToString(" ") { "${it.note}:${it.value}" })
        println("types:    " + s.types.joinToString(" ") { "${it.label}:${it.value}" })
        println("rarity:   " + s.rarities.joinToString(" ") { "${it.label}:${it.value}" })
        println("unsupported: " + s.unsupported)
        println("tokens (${s.tokens.size}):")
        s.tokens.take(20).forEach { println("   ${it.cards}x  ${it.what}") }
    }
}
