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
 * A task is a row in D1 written as each transition happens: pending
 * when it is sent, in progress when a builder starts, and what its pull
 * request came to when the builder stops, with why. The app reads it
 * from the Worker and decides nothing about it from GitHub.
 */
class TasksTest {

    private fun quoted(s: String?) = if (s == null) "null" else "\"$s\""

    private fun row(
        key: String,
        title: String,
        status: String,
        started: String? = null,
        finished: String? = null,
        statusAt: String? = null,
        note: String? = null,
        pr: String? = null,
    ) =
        """{"key":"$key","name":null,"title":"$title","status":"$status","pr":${quoted(pr)},
            "status_at":${quoted(statusAt)},"note":${quoted(note)},
            "created_at":"2026-10-01T00:00:00.000Z",
            "started_at":${quoted(started)},"finished_at":${quoted(finished)}}"""

    private val body = listOf(
        row("k1", "Merged one", "merged", "2026-10-01T09:00:00.000Z", "2026-10-01T10:00:00.000Z"),
        row("k2", "Merged two", "deployed", "2026-10-03T12:00:00.000Z", "2026-10-03T12:30:00.000Z"),
        row("k3", "Given up", "cancelled", null, "2026-10-02T08:00:00.000Z"),
        row(
            "k4", "Waiting on CI", "in review", "2026-10-08T09:00:00.000Z",
            pr = "https://github.com/mattshoe/mtg-api/pull/142",
        ),
        row("k5", "still going", "in progress", "2026-10-08T10:00:00.000Z"),
        row("k6", "Sent from the phone", "pending"),
        row("k7", "Never mind", "cancelled", null, "2026-10-04T08:00:00.000Z"),
        row(
            "k8", "Builder died", "paused", "2026-10-07T08:00:00.000Z",
            statusAt = "2026-10-08T09:55:00.000Z", note = "agent crashed",
        ),
        row("k9", "On hold", "paused", note = "held by Matt"),
        row("k10", "Needs Matt", "blocked", note = "needs the Cloudflare token"),
    ).joinToString(",", """{"tasks":[""", "]}")

    private fun decoded() = Tasks.decode(body)

    private fun statusOf(title: String) = decoded().single { it.title == title }.status.word

    @Test
    fun eachTaskSaysWhereItIsInOneWord() {
        assertEquals("merged", statusOf("Merged one"))
        assertEquals("deployed", statusOf("Merged two"))
        assertEquals("in review", statusOf("Waiting on CI"))
        assertEquals("in progress", statusOf("still going"))
        assertEquals("blocked", statusOf("Needs Matt"))
    }

    /** What GitHub could never show: a task nobody has started, one called off, and one whose agent died. */
    @Test
    fun aTaskNotYetStartedIsPendingAndOneCalledOffIsCancelled() {
        assertEquals("pending", statusOf("Sent from the phone"))
        assertEquals("cancelled", statusOf("Never mind"))
        assertEquals("paused", statusOf("Builder died"))
    }

    /** Matt's eight, and no `stopped`, `failing`, `building`, `queued`, `done` or `closed`. */
    @Test
    fun theStatusesAreMattsEightWords() {
        assertEquals(
            setOf("pending", "in progress", "blocked", "paused", "in review", "merged", "deployed", "cancelled"),
            TaskStatus.entries.map { it.word }.toSet(),
        )
    }

    /** "Done, collapsed by default ... merged, deployed, cancelled." Everything else is live. */
    @Test
    fun mergedDeployedAndCancelledAreDoneAndNothingElseIs() {
        assertEquals(
            setOf("merged", "deployed", "cancelled"),
            TaskStatus.entries.filter { it.finished }.map { it.word }.toSet(),
        )
    }

    /** "A row should answer the question without Matt having to ask me": why, and for how long. */
    @Test
    fun aPausedTaskSaysWhyAndHowLongItHasBeenPaused() {
        val t = decoded().single { it.title == "Builder died" }
        assertEquals("agent crashed", t.note)
        assertEquals("for 2h 05m · agent crashed", t.detail(Tasks.epochMillis("2026-10-08T12:00:00Z")!!))
    }

    @Test
    fun aTaskInReviewNamesItsPullRequest() {
        val t = decoded().single { it.title == "Waiting on CI" }
        assertEquals("https://github.com/mattshoe/mtg-api/pull/142", t.pr)
        assertEquals("PR #142", t.detail(0))
    }

    @Test
    fun aBlockedTaskSaysWhatItIsWaitingOn() {
        assertEquals("needs the Cloudflare token", decoded().single { it.title == "Needs Matt" }.detail(0))
    }

    /** A finished task's clock has stopped; how long it took is `took`, not this. */
    @Test
    fun aFinishedTaskDoesNotCountHowLongItHasBeenFinished() {
        val t = Task("k", "Shipped", TaskStatus.MERGED, "2026-10-08T11:00:00Z", statusAt = "2026-10-08T11:00:00Z")
        assertNull(t.detail(Tasks.epochMillis("2026-10-09T11:00:00Z")!!))
    }

    /** Matt: four stopped tasks all read `building`. A held one says paused, and its clock does not run. */
    @Test
    fun aTaskHeldBackIsPausedAndNotCountingUp() {
        val held = decoded().single { it.title == "On hold" }
        assertEquals("paused", held.status.word)
        assertNull(held.copy(startedAt = "2026-10-08T10:00:00Z").elapsed(Tasks.epochMillis("2026-10-08T11:00:00Z")!!))
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

    /** The ones that sit forever if nobody looks come first: blocked, then paused. */
    @Test
    fun doneTasksAreNewestFirstAndActiveOnesAreApart() {
        val s = Tasks().loaded(decoded())
        assertEquals(
            listOf("Needs Matt", "Builder died", "On hold", "still going", "Waiting on CI", "Sent from the phone"),
            s.active.map { it.title },
        )
        assertEquals(listOf("Never mind", "Merged two", "Given up", "Merged one"), s.done.map { it.title })
    }

    /** Done is a consequence of the status: a paused task that resumes is live again. */
    @Test
    fun aTaskThatComesBackFromPausedIsLiveAgain() {
        val paused = decoded().single { it.title == "Builder died" }
        val s = Tasks().loaded(listOf(paused.copy(status = TaskStatus.IN_PROGRESS)))
        assertEquals(listOf("Builder died"), s.active.map { it.title })
        assertTrue(s.done.isEmpty())
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
        val shipped = Task("k1", "Shipped", TaskStatus.MERGED, "2026-10-08T13:05:00Z", startedAt = "2026-10-08T10:00:00Z")
        assertEquals("took 3h 05m", shipped.took)
        val dropped = Task("k2", "Dropped", TaskStatus.CANCELLED, "2026-10-08T10:12:00Z", startedAt = "2026-10-08T10:00:00Z")
        assertEquals("took 12m", dropped.took)
    }

    @Test
    fun aTaskWithNoKnownStartOrStillGoingSaysNothingAboutHowLongItTook() {
        assertNull(Task("k1", "Shipped", TaskStatus.MERGED, "2026-10-08T11:00:00Z").took)
        assertNull(Task("k1", "Going", TaskStatus.IN_PROGRESS, null, startedAt = "2026-10-08T10:00:00Z").took)
    }

    /** Only a task being worked on counts up: a paused one would count forever. */
    @Test
    fun aPausedOrPendingTaskSaysNothingAboutTime() {
        val now = Tasks.epochMillis("2026-10-08T12:00:00Z")!!
        assertNull(Task("k", "Died", TaskStatus.PAUSED, null, startedAt = "2026-10-08T10:00:00Z").elapsed(now))
        assertNull(Task("k", "Waiting", TaskStatus.PENDING, null, startedAt = "2026-10-08T10:00:00Z").elapsed(now))
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
        val t = Task("k1", "Going", TaskStatus.IN_PROGRESS, null, startedAt = start)
        val at = Tasks.epochMillis(start)!!
        assertEquals("just started", t.elapsed(at + 30_000))
        assertEquals("12m", t.elapsed(at + 12 * 60_000))
        assertEquals("3h 05m", t.elapsed(at + (3 * 60 + 5) * 60_000L))
        assertEquals("2d 4h", t.elapsed(at + ((2 * 24 + 4) * 60 + 59) * 60_000L))
    }

    @Test
    fun aTaskWithNoKnownStartOrThatHasFinishedSaysNothingAboutTime() {
        val now = Tasks.epochMillis("2026-10-08T12:00:00Z")!!
        assertNull(Task("k1", "Going", TaskStatus.IN_PROGRESS, null).elapsed(now))
        assertNull(
            Task("k1", "Shipped", TaskStatus.MERGED, "2026-10-08T11:00:00Z", startedAt = "2026-10-08T10:00:00Z")
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
