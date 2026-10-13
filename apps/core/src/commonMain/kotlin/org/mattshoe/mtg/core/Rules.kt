package org.mattshoe.mtg.core

data class Rule(
    val number: String,
    val text: String,
    val examples: List<String> = emptyList(),
    val section: String = "",
)

data class RuleBook(val effective: String = "", val rules: List<Rule> = emptyList()) {
    fun search(query: String): List<Rule> = emptyList()

    companion object {
        fun parse(text: String): RuleBook = RuleBook()
    }
}

data class RulesPage(
    val book: RuleBook? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val collapsed: Set<String> = emptySet(),
) {
    val total: Int get() = 0
    val hits: List<Rule> get() = emptyList()
    fun isOpen(rule: Rule): Boolean = false
    fun toggled(rule: Rule): RulesPage = this
    fun searched(query: String): RulesPage = this
    fun loading(): RulesPage = this
    fun loaded(text: String): RulesPage = this
    fun failed(message: String): RulesPage = this

    companion object {
        const val SHOWN = 150
    }
}

val AppState.onRules: Boolean get() = false
