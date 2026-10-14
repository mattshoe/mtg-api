package org.mattshoe.mtg.core

/** One article a Guide search found. */
data class GuideHit(val title: String, val part: String, val snippet: String, val address: String)

val AppState.guideHits: List<GuideHit> get() = emptyList()

val AppState.guideSearching: Boolean get() = false

fun AppState.searchGuide(query: String): AppState = this

fun AppState.openGuideHit(hit: GuideHit): AppState = this

val AppState.guideSearch: String get() = ""
