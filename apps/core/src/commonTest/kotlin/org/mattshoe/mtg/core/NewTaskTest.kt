package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Matt: "I want the option to add a new task from the admin settings. I
 * want to be able to tap a "new task" button and get a simple but
 * attractive new screen where i can enter the details and upload files".
 */
class NewTaskTest {

    private val admin = Admin().signIn(Account(key = "m4tt0001", role = Role.ADMIN), "t")

    private fun onAdmin() = AppState(admin = admin).navigate(View.ADMIN)

    private fun file(name: String, bytes: Long = 10) = TaskFile(name, "image/png", bytes, "aGVsbG8=")

    @Test
    fun newTaskOpensItsOwnPageUnderAdminSettings() {
        val s = onAdmin().startingATask()
        assertTrue(s.writingTask, "New task did not open the task page: ${s.route}")
        assertEquals(View.ADMIN, s.view)
        assertNull(s.person, "the task page was read as somebody's account page")
    }

    @Test
    fun backFromTheTaskPageIsAdminSettingsNotTheLibrary() {
        val back = onAdmin().startingATask().back()
        assertEquals(Route(View.ADMIN), back?.route)
    }

    @Test
    fun somebodyWithoutTheRoleCannotReachIt() {
        val user = AppState(admin = Admin().signIn(Account(key = "k4yy0003"), "t"))
        assertFalse(user.startingATask().writingTask, "a plain account reached the task page")
    }

    @Test
    fun itCannotBeSentWithoutATitleAndDetails() {
        val t = NewTask()
        assertFalse(t.canSend)
        assertFalse(t.titled("Bigger buttons").canSend, "sent with no details")
        assertFalse(t.described("They are too small").canSend, "sent with no title")
        assertFalse(t.titled("  ").described("x").canSend, "a blank title counted")
        assertTrue(t.titled("Bigger buttons").described("They are too small").canSend)
    }

    @Test
    fun filesAreAddedAndTakenOffOneAtATime() {
        val t = NewTask().attach(listOf(file("a.png"), file("b.png"))).attach(listOf(file("c.png")))
        assertEquals(listOf("a.png", "b.png", "c.png"), t.files.map { it.name })
        assertEquals(listOf("a.png", "c.png"), t.remove("b.png").files.map { it.name })
    }

    @Test
    fun aFileTooBigIsRefusedByNameAndTheRestStillLand() {
        val t = NewTask().attach(listOf(file("ok.png"), file("huge.mov", 1_500_001)))
        assertEquals(listOf("ok.png"), t.files.map { it.name })
        assertEquals("huge.mov is over 1.5 MB", t.error)
    }

    @Test
    fun fiveFilesAtMost() {
        val t = NewTask().attach((1..7).map { file("$it.png") })
        assertEquals(5, t.files.size)
        assertEquals("5 files at most", t.error)
    }

    @Test
    fun sendingTwiceIsNotPossible() {
        val t = NewTask().titled("x").described("y").sending()
        assertFalse(t.canSend, "a second press could send it again")
    }

    @Test
    fun sentGoesBackToAdminSettingsWithAnEmptyFormAndSaysSo() {
        val s = onAdmin().startingATask()
            .let { it.copy(newTask = it.newTask.titled("Bigger buttons").described("too small").sending()) }
            .taskSent()
        assertEquals(Route(View.ADMIN), s.route)
        assertEquals(NewTask(), s.newTask)
        assertEquals("Task sent: Bigger buttons. It is a request within five minutes.", s.toast)
    }

    @Test
    fun aRefusalStaysOnThePageWithEverythingTypedStillThere() {
        val t = NewTask().titled("x").described("y").attach(listOf(file("a.png"))).sending()
            .failed("that needs the admin role")
        assertEquals("x", t.title)
        assertEquals(listOf("a.png"), t.files.map { it.name })
        assertEquals("that needs the admin role", t.error)
        assertTrue(t.canSend)
    }

    @Test
    fun aFileSizeReadsLikeAPerson() {
        assertEquals("820 KB", file("a", 820_000).shownSize)
        assertEquals("1.2 MB", file("a", 1_200_000).shownSize)
        assertEquals("300 B", file("a", 300).shownSize)
    }
}
