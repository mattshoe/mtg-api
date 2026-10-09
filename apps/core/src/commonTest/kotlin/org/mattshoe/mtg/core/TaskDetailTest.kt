package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Matt: "I want to be able to tap on a task and be taken to a details
 * page that shows the whole request and all information about it,
 * including any attachments and whatnot".
 */
class TaskDetailTest {

    private val admin = Admin().signIn(Account(key = "m4tt0001", role = Role.ADMIN), "t")

    private val task = Task("q7w8e9r0", "Bigger buttons", TaskStatus.IN_REVIEW, null, pr = "https://github.com/mattshoe/mtg-api/pull/72")

    private fun onAdmin() = AppState(admin = admin, tasks = Tasks().loaded(listOf(task))).navigate(View.ADMIN)

    /** `GET /tasks/<key>`, for a task the dispatcher has started and a builder has opened a pull request for. */
    private val row = """{"key":"q7w8e9r0","name":"bigger-buttons","title":"Bigger buttons",
        "details":"The buttons on the deck page are too small to hit.\n\nMake them bigger.",
        "status":"in review","status_at":"2026-10-08T11:00:00.000Z","note":"CI running",
        "pr":"https://github.com/mattshoe/mtg-api/pull/72","created_at":"2026-10-08T10:00:00.000Z",
        "received_at":"2026-10-08T10:01:00.000Z","started_at":"2026-10-08T10:05:00.000Z","finished_at":null,
        "files":[{"name":"screen.png","type":"image/png","data":"aGVsbG8="},{"name":"notes.txt","type":"text/plain","data":"aGk="}]}"""

    /** A request written straight into requests/, as the dispatcher sends it. */
    private val file = """
        ---
        status: ready
        ---

        # Faster decks

        The decks page is slow.

        ## Files

        - /Users/m/repos/mtg-api/.intake/attachments/faster-decks/screen.png

        <!-- from the app, task q7w8e9r0, 2026-10-08T10:00:00.000Z -->
    """.trimIndent()

    @Test
    fun tappingATaskOpensItsOwnPageUnderAdminSettings() {
        val s = onAdmin().openingTask(task)
        assertEquals("#/admin/task/q7w8e9r0", s.hash())
        assertEquals(task, s.openTask, "the page does not know which task it is for: ${s.route}")
        assertEquals("q7w8e9r0", s.taskDetail?.key, "nothing is loading for the page")
        assertNull(s.person, "the task page was read as somebody's account page")
        assertFalse(s.writingTask, "the task page was read as the New task page")
    }

    @Test
    fun anAddressNamingATaskOpensItsPageBeforeTheListHasLoaded() {
        val s = AppState(admin = admin).navigate(Route.parse("#/admin/task/q7w8e9r0"))
        assertEquals("q7w8e9r0", s.openTaskKey)
        assertNull(s.openTask, "a task was made up before the list said what it is")
    }

    @Test
    fun backFromATaskIsAdminSettings() {
        assertEquals(Route(View.ADMIN), onAdmin().openingTask(task).back()?.route)
    }

    @Test
    fun somebodyWithoutTheRoleCannotReachIt() {
        val user = AppState(admin = Admin().signIn(Account(key = "k4yy0003"), "t"))
        assertNull(user.openingTask(task).openTaskKey, "a plain account reached a task page")
    }

    @Test
    fun everythingTheWorkerKnowsAboutATaskIsRead() {
        val d = TaskDetail.decode("q7w8e9r0", row)
        assertEquals("The buttons on the deck page are too small to hit.\n\nMake them bigger.", d.body)
        assertEquals(TaskStatus.IN_REVIEW, d.task?.status)
        assertEquals("CI running", d.task?.note)
        assertEquals("bigger-buttons", d.name)
        assertEquals(72, d.pull, "the pull request's number was not read out of its address")
        assertEquals(listOf("screen.png" to 5L, "notes.txt" to 2L), d.files.map { it.name to it.bytes })
        assertEquals(listOf(true, false), d.files.map { it.isImage })
    }

    @Test
    fun theFactsAreTheSameOnBothShells() {
        val now = Tasks.epochMillis("2026-10-08T12:05:00Z")!!
        assertEquals(
            listOf(
                "Status" to "in review",
                "Why" to "CI running",
                "Time so far" to "2h 05m",
                "Request" to "requests/bigger-buttons.md",
                "Sent" to "2026-10-08 06:00",
            ),
            TaskDetail.decode("q7w8e9r0", row).facts(now, newYork),
        )
    }

    /** Eastern, as a phone in Ohio has it: four hours behind in summer, five in winter. */
    private val newYork: (Long) -> Int = { ms -> if (ms < Tasks.epochMillis("2026-11-01T06:00:00Z")!!) -240 else -300 }

    /** Matt: "the time values on task details should show local time not utc". */
    @Test
    fun theTimeATaskWasSentIsTheReadersOwnClockNotUtc() {
        fun sent(iso: String, offset: (Long) -> Int) =
            TaskDetail.decode("k", """{"key":"k","title":"t","status":"pending","created_at":"$iso"}""")
                .facts(0, offset).toMap()["Sent"]
        assertEquals("2026-10-08 06:00", sent("2026-10-08T10:00:00.000Z", newYork), "summer in Ohio is UTC-4")
        assertEquals("2026-11-02 05:00", sent("2026-11-02T10:00:00.000Z", newYork), "after the clocks go back it is UTC-5")
        assertEquals("2027-01-01 00:30", sent("2026-12-31T23:30:00.000Z") { 60 }, "an hour ahead crosses into the new year")
        assertEquals("2026-03-01 23:00", sent("2026-03-02T04:00:00.000Z") { -300 }, "five hours behind goes back across the month")
        assertEquals("2026-10-08 10:00", sent("2026-10-08T10:00:00.000Z") { 0 }, "at UTC it reads as it was sent")
    }

    @Test
    fun aRequestWrittenOnTheLaptopIsShownWithoutItsFrontmatterTitleOrPathsOnTheLaptop() {
        val d = TaskDetail.decode("a1b2c3d4", """{"key":"a1b2c3d4","title":"Faster decks","status":"pending","details":${json(file)},"files":[]}""")
        assertEquals("The decks page is slow.", d.body)
    }

    @Test
    fun aTaskWithNoTextSaysSoRatherThanShowingNothing() {
        val d = TaskDetail.decode("a1b2c3d4", """{"key":"a1b2c3d4","title":"Old one","status":"merged","details":"","files":[]}""")
        assertNull(d.body)
        assertTrue(TaskDetail.NO_TEXT.isNotBlank())
    }

    @Test
    fun anAnswerThatIsNotATaskIsAnErrorNotABlankPage() {
        val d = TaskDetail.decode("a1b2c3d4", "<html>")
        assertEquals(TaskDetail.UNREADABLE, d.error)
        assertFalse(d.busy)
    }

    private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
