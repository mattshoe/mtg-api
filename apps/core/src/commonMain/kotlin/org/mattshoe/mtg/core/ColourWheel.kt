package org.mattshoe.mtg.core

data class WheelColour(val pip: String, val name: String, val wants: String, val goodAt: String, val badAt: String)

data class WheelPair(val pips: String, val name: String, val why: String)

object ColourWheel {
    val intro: List<String> = emptyList()
    val colours: List<WheelColour> = emptyList()
    val allied: List<WheelPair> = emptyList()
    val enemy: List<WheelPair> = emptyList()
    fun allies(pip: String): List<WheelColour> = emptyList()
    fun enemies(pip: String): List<WheelColour> = emptyList()
}
