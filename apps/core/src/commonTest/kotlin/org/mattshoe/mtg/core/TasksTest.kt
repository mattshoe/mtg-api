package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tasks, as Admin Settings lists them.
 *
 * Matt: "I want to be able to see the status of ongoing tasks in the
 * app. Nothing too fancy just the literal status like hold or done or
 * whatever statuses you assign. I want the done ones minimized by
 * default but still browsable, ordered by the time which they
 * completed, most recent first".
 *
 * A task is a row in D1 the dispatcher writes as it changes: queued
 * when it is sent, building when a builder starts, and what its pull
 * request came to when the builder stops. The app reads it from the
 * Worker and decides nothing about it from GitHub.
 */
class TasksTest {

    private fun quoted(s: String?) = if (s == null) "null" else "\"$s\""

    private fun row(key: String, title: String, status: String, started: String? = null, finished: String? = null) =
        """{"key":"$key","name":null,"title":"$title","status":"$status","pr":null,
            "created_at":"2026-10-01T00:00:00.000Z",
            "started_at":${quoted(started)},"finished_at":${quoted(finished)}}"""

    private val body = listOf(
        row("k1", "Merged one", "done", "2026-10-01T09:00:00.000Z", "2026-10-01T10:00:00.000Z"),
        row("k2", "Merged two", "done", "2026-10-03T12:00:00.000Z", "2026-10-03T12:30:00.000Z"),
        row("k3", "Given up", "closed", null, "2026-10-02T08:00:00.000Z"),
        row("k4", "Waiting on CI", "in review", "2026-10-08T09:00:00.000Z"),
        row("k5", "still going", "building", "2026-10-08T10:00:00.000Z"),
        row("k6", "Sent from the phone", "queued"),
        row("k7", "Never mind", "cancelled", null, "2026-10-04T08:00:00.000Z"),
        row("k8", "Builder died", "stopped", "2026-10-07T08:00:00.000Z"),
    ).joinToString(",", """{"tasks":[""", "]}")

    private fun decoded() = Tasks.decode(body)

    private fun statusOf(title: String) = decoded().single { it.title == title }.status.word

    @Test
    fun eachTaskSaysWhereItIsInOneWord() {
        assertEquals("done", statusOf("Merged one"))
        assertEquals("closed", statusOf("Given up"))
        assertEquals("in review", statusOf("Waiting on CI"))
        assertEquals("building", statusOf("still going"))
    }

    /** What GitHub could never show: a task nobody has started, and one called off. */
    @Test
    fun aTaskNotYetStartedIsQueuedAndOneCalledOffIsCancelled() {
        assertEquals("queued", statusOf("Sent from the phone"))
        assertEquals("cancelled", statusOf("Never mind"))
        assertEquals("stopped", statusOf("Builder died"))
    }

    @Test
    fun theStartAndFinishAreWhatTheWorkerWrote() {
        val t = decoded().single { it.title == "Merged one" }
        assertEquals("k1", t.key)
        assertEquals("2026-10-01T09:00:00.000Z", t.startedAt)
        assertEquals("2026-10-01T10:00:00.000Z", t.finishedAt)
        assertEquals("took 1h 00m", t.took)
    }

    @Test
    fun aStatusThisBuildDoesNotKnowIsLeftOutRatherThanGuessed() {
        val t = Tasks.decode("""{"tasks":[""" + row("k9", "From the future", "teleporting") + "]}")
        assertTrue(t.isEmpty())
    }

    @Test
    fun doneTasksAreNewestFirstAndActiveOnesAreApart() {
        val s = Tasks().loaded(decoded())
        assertEquals(listOf("Waiting on CI", "still going", "Builder died", "Sent from the phone"), s.active.map { it.title })
        assertEquals(listOf("Merged two", "Never mind", "Given up", "Merged one"), s.done.map { it.title })
    }

    @Test
    fun theDoneOnesAreMinimisedUntilAskedFor() {
        val s = Tasks().loaded(decoded())
        assertFalse(s.showDone)
        assertTrue(s.toggleDone().showDone)
        assertFalse(s.toggleDone().toggleDone().showDone)
    }

    /**
     * Matt: "Done tasks should show how long they took, not a UTC
     * timestamp". From the start the dispatcher wrote to the finish, in
     * the same words a running one counts in.
     */
    @Test
    fun aFinishedTaskSaysHowLongItTook() {
        val shipped = Task("k1", "Shipped", TaskStatus.DONE, "2026-10-08T13:05:00Z", startedAt = "2026-10-08T10:00:00Z")
        assertEquals("took 3h 05m", shipped.took)
        val dropped = Task("k2", "Dropped", TaskStatus.CLOSED, "2026-10-08T10:12:00Z", startedAt = "2026-10-08T10:00:00Z")
        assertEquals("took 12m", dropped.took)
    }

    @Test
    fun aTaskWithNoKnownStartOrStillGoingSaysNothingAboutHowLongItTook() {
        assertNull(Task("k1", "Shipped", TaskStatus.DONE, "2026-10-08T11:00:00Z").took)
        assertNull(Task("k1", "Going", TaskStatus.BUILDING, null, startedAt = "2026-10-08T10:00:00Z").took)
    }

    /** Only a task being worked on counts up: a stopped one would count forever. */
    @Test
    fun aStoppedOrQueuedTaskSaysNothingAboutTime() {
        val now = Tasks.epochMillis("2026-10-08T12:00:00Z")!!
        assertNull(Task("k", "Died", TaskStatus.STOPPED, null, startedAt = "2026-10-08T10:00:00Z").elapsed(now))
        assertNull(Task("k", "Waiting", TaskStatus.QUEUED, null, startedAt = "2026-10-08T10:00:00Z").elapsed(now))
    }

    @Test
    fun anythingUnreadableIsNoTasksRatherThanACrash() {
        assertTrue(Tasks.decode("""{"error":"that needs the admin role"}""").isEmpty())
        assertTrue(Tasks.decode("<html>").isEmpty())
    }

    @Test
    fun aFailedLoadLeavesNothingStaleOnScreen() {
        val s = Tasks().loaded(decoded()).toggleDone().loading().failed("offline")
        assertTrue(s.rows.isEmpty())
        assertEquals("offline", s.error)
    }

    @Test
    fun anActiveTaskSaysHowLongSinceItStarted() {
        val start = "2026-10-08T09:15:00Z"
        val t = Task("k1", "Going", TaskStatus.BUILDING, null, startedAt = start)
        val at = Tasks.epochMillis(start)!!
        assertEquals("just started", t.elapsed(at + 30_000))
        assertEquals("12m", t.elapsed(at + 12 * 60_000))
        assertEquals("3h 05m", t.elapsed(at + (3 * 60 + 5) * 60_000L))
        assertEquals("2d 4h", t.elapsed(at + ((2 * 24 + 4) * 60 + 59) * 60_000L))
    }

    @Test
    fun aTaskWithNoKnownStartOrThatHasFinishedSaysNothingAboutTime() {
        val now = Tasks.epochMillis("2026-10-08T12:00:00Z")!!
        assertNull(Task("k1", "Going", TaskStatus.BUILDING, null).elapsed(now))
        assertNull(
            Task("k1", "Shipped", TaskStatus.DONE, "2026-10-08T11:00:00Z", startedAt = "2026-10-08T10:00:00Z")
                .elapsed(now),
        )
    }

    /** D1's timestamps carry milliseconds, which GitHub's never did. */
    @Test
    fun anIsoTimeIsReadAsMillisSinceTheEpoch() {
        assertEquals(0L, Tasks.epochMillis("1970-01-01T00:00:00Z"))
        assertEquals(1_791_450_900_000L, Tasks.epochMillis("2026-10-08T09:15:00Z"))
        assertEquals(1_791_450_900_000L, Tasks.epochMillis("2026-10-08T09:15:00.123Z"))
        assertNull(Tasks.epochMillis("yesterday"))
    }

    @Test
    fun theTasksPanelKnowsWhatTimeItIs() {
        assertEquals(42L, Tasks().at(42L).now)
    }
}
