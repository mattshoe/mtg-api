package org.mattshoe.mtg.core

data class Rule(
    val number: String,
    val text: String,
    val examples: List<String> = emptyList(),
    val section: String = "",
) {
    val shownNumber: String get() = number
}

data class RuleBook(val effective: String = "", val rules: List<Rule> = emptyList()) {
    fun search(query: String): List<Rule> = emptyList()

    companion object {
        fun parse(text: String): RuleBook = RuleBook()
    }
}

data class Rules(
    val book: RuleBook? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val collapsed: Set<String> = emptySet(),
) {
    val total: Int get() = 0
    val hits: List<Rule> get() = emptyList()
    val count: String get() = ""
    fun isOpen(rule: Rule): Boolean = false
    fun toggled(rule: Rule): Rules = this
    fun searched(query: String): Rules = this
    fun loading(): Rules = this
    fun loaded(text: String): Rules = this
    fun failed(message: String): Rules = this

    companion object {
        const val SHOWN = 150
    }
}

val AppState.onRules: Boolean get() = false
