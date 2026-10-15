package org.mattshoe.mtg.core

enum class Allowed { NO, SOME, YES }

data class BracketRule(val topic: String, val allowed: Allowed, val short: String, val detail: String = "")

data class Bracket(val number: Int, val name: String, val tagline: String, val about: String, val games: String, val rules: List<BracketRule>)

object Brackets {
    const val ABOUT = ""
    val all: List<Bracket> = emptyList()
}
