package org.mattshoe.mtg.core

enum class TaskStatus(val word: String) { DONE("done"), IN_REVIEW("in review"), CLOSED("closed"), BUILDING("building") }

data class Task(val ref: String, val title: String, val status: TaskStatus, val finishedAt: String?) {
    val finished: String? get() = null
}

data class Tasks(
    val rows: List<Task> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val showDone: Boolean = false,
) {
    val active: List<Task> get() = emptyList()
    val done: List<Task> get() = emptyList()
    fun toggleDone() = this
    fun loading() = this
    fun loaded(found: List<Task>) = this
    fun failed(message: String) = copy(error = message)

    companion object {
        fun decode(pulls: String, refs: String, done: String): List<Task> = emptyList()
    }
}
