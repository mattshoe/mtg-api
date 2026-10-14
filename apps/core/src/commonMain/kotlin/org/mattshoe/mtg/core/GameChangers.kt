package org.mattshoe.mtg.core

object GameChangers {
    val cards: List<DeckCard> = emptyList()
    val byType: List<Pair<DeckGroup, List<DeckCard>>> = emptyList()
    val pageOrder: List<DeckCard> = emptyList()
}

fun AppState.peekGameChanger(card: DeckCard): AppState = this
