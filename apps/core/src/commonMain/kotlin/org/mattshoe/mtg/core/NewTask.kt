package org.mattshoe.mtg.core

/**
 * One file sent with a task: what it is called, how big it is, and its
 * bytes as base64, which is how `POST /tasks` carries them.
 *
 * Read off disk by each platform — `FileReader` on the web, the content
 * resolver on Android — and handed in whole, so the limits below are
 * one rule rather than two.
 */
data class TaskFile(val name: String, val type: String, val bytes: Long, val data: String) {

    /** "820 KB", "1.2 MB", for the row that lists it. */
    val shownSize: String get() = when {
        bytes >= 1_000_000 -> {
            val tenths = (bytes + 50_000) / 100_000
            "${tenths / 10}.${tenths % 10} MB"
        }
        bytes >= 1_000 -> "${(bytes + 500) / 1_000} KB"
        else -> "$bytes B"
    }
}

/**
 * The New task page on Admin Settings.
 *
 * Matt: "I want to be able to tap a "new task" button and get a simple
 * but attractive new screen where i can enter the details and upload
 * files". The details and files, and no title: "come up with your own
 * fucking task titles just give me a way to enter the task details fuck
 * the title field". The Worker names it (`titleFrom` in `src/tasks.js`).
 * Sending writes it to the Worker's inbox, and the laptop that
 * dispatches collects it into `requests/` within five minutes (`scripts/intake/inbox.mjs`), where it
 * builds like any request Matt asked for in conversation.
 *
 * The limits are the Worker's (`src/tasks.js`), said here first so a
 * file that would be refused is refused before it is uploaded.
 */
data class NewTask(
    val details: String = "",
    val files: List<TaskFile> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canSend: Boolean get() = !busy && details.isNotBlank()

    fun described(text: String) = copy(details = text)

    /**
     * Files added, never replacing what is there. Any that would be
     * refused are left out and the first reason is said; the rest land.
     */
    fun attach(more: List<TaskFile>): NewTask {
        var kept = files
        var problem: String? = null
        for (f in more) {
            when {
                f.bytes > MAX_BYTES -> problem = problem ?: "${f.name} is over 1.5 MB"
                kept.size >= MAX_FILES -> problem = problem ?: "$MAX_FILES files at most"
                else -> kept = kept.filterNot { it.name == f.name } + f
            }
        }
        return copy(files = kept, error = problem)
    }

    fun remove(name: String) = copy(files = files.filterNot { it.name == name }, error = null)

    fun sending() = copy(busy = true, error = null)

    /** Refused: everything typed stays, and the reason is on the page. */
    fun failed(message: String) = copy(busy = false, error = message)

    companion object {
        /** `#/admin/new-task`. Never an account key, which is eight characters with no dash. */
        const val ROUTE = "new-task"
        const val MAX_FILES = 5
        const val MAX_BYTES = 1_500_000L
    }
}
