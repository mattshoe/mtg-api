package org.mattshoe.mtg.core

data class TaskFile(val name: String, val type: String, val bytes: Long, val data: String) {
    val shownSize: String get() = ""
}

data class NewTask(
    val title: String = "",
    val details: String = "",
    val files: List<TaskFile> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canSend: Boolean get() = false
    fun titled(text: String) = this
    fun described(text: String) = this
    fun attach(more: List<TaskFile>) = this
    fun remove(name: String) = this
    fun sending() = this
    fun failed(message: String) = this

    companion object {
        const val ROUTE = "new-task"
    }
}
