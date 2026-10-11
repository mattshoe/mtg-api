package org.mattshoe.mtg.core

/** "- 3 +" under a card. */
data class Stepper(val count: Int, val busy: Boolean = false) {
    val canTakeOne: Boolean get() = false
    val canAddOne: Boolean get() = false
}

/** What pressing plus or minus sends. */
sealed interface CountWrite {
    val summary: String

    data class DeckList(
        val deck: String,
        val commander: String,
        val list: String,
        override val summary: String,
    ) : CountWrite

    data class Cards(
        val direction: Direction,
        val collection: String,
        val line: String,
        override val summary: String,
    ) : CountWrite
}

val AppState.peekStepper: Stepper? get() = null
fun AppState.peekStep(by: Int): CountWrite? = null
val AppState.cardStepper: Stepper? get() = null
fun AppState.cardStep(by: Int): CountWrite? = null
fun AppState.countingStarted(): AppState = this
