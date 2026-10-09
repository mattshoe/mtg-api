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

    /**
     * Matt: "Why does it say \"3m for 3m\" and \"17m for 7m\"?!?!?!" and
     * "I don't give a fuck good long is been in the fucking status just
     * show the total fucking time!!!!" One duration, since it was
     * created, whatever it started at or has been in since.
     */
    @Test
    fun aLiveTaskShowsOneDurationTheTotalTimeSinceItWasCreated() {
        val t = Task(
            "k", "reconcile tells the truth", TaskStatus.IN_PROGRESS, null,
            startedAt = "2026-10-08T09:05:00Z", statusAt = "2026-10-08T09:10:00Z",
            createdAt = "2026-10-08T09:00:00Z",
        )
        assertEquals("17m", t.elapsed(Tasks.epochMillis("2026-10-08T09:17:00Z")!!))
    }

    @Test
    fun theTimeSinceItWasCreatedIsWhatTheWorkerWrote() {
        val t = decoded().single { it.title == "Builder died" }
        assertEquals("2026-10-01T00:00:00.000Z", t.createdAt)
        assertEquals("7d 12h", t.elapsed(Tasks.epochMillis("2026-10-08T12:00:00Z")!!))
    }

    /** The pull request stays on the task for its details page, not in the list. */
    @Test
    fun aTaskInReviewStillKnowsItsPullRequest() {
        val t = decoded().single { it.title == "Waiting on CI" }
        assertEquals("https://github.com/mattshoe/mtg-api/pull/142", t.pr)
    }

    @Test
    fun aBlockedTaskSaysWhatItIsWaitingOn() {
        assertEquals("needs the Cloudflare token", decoded().single { it.title == "Needs Matt" }.note)
    }

    /** Matt: four stopped tasks all read `building`. A held one says paused, and how long it has existed. */
    @Test
    fun aTaskHeldBackIsPausedAndStillSaysHowOldItIs() {
        val held = decoded().single { it.title == "On hold" }
        assertEquals("paused", held.status.word)
        assertEquals("7d 11h", held.elapsed(Tasks.epochMillis("2026-10-08T11:00:00Z")!!))
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
     * timestamp", and "are the completed ones going to show it too???"
     * Created to finished, frozen, in the same words a live one counts in.
     */
    @Test
    fun aFinishedTaskSaysHowLongItTookFromCreatedToFinished() {
        val shipped = Task(
            "k1", "Shipped", TaskStatus.MERGED, "2026-10-08T13:05:00Z",
            startedAt = "2026-10-08T11:00:00Z", createdAt = "2026-10-08T10:00:00Z",
        )
        assertEquals("took 3h 05m", shipped.took)
        val dropped = Task("k2", "Dropped", TaskStatus.CANCELLED, "2026-10-08T10:12:00Z", createdAt = "2026-10-08T10:00:00Z")
        assertEquals("took 12m", dropped.took)
    }

    @Test
    fun aTaskWithNoKnownCreationOrStillGoingSaysNothingAboutHowLongItTook() {
        assertNull(Task("k1", "Shipped", TaskStatus.MERGED, "2026-10-08T11:00:00Z").took)
        assertNull(Task("k1", "Going", TaskStatus.IN_PROGRESS, null, createdAt = "2026-10-08T10:00:00Z").took)
    }

    /** Every live task has an age, paused and pending as much as running. */
    @Test
    fun aPausedOrPendingTaskSaysHowLongSinceItWasCreated() {
        val now = Tasks.epochMillis("2026-10-08T12:00:00Z")!!
        assertEquals("2h 00m", Task("k", "Died", TaskStatus.PAUSED, null, createdAt = "2026-10-08T10:00:00Z").elapsed(now))
        assertEquals("2h 00m", Task("k", "Waiting", TaskStatus.PENDING, null, createdAt = "2026-10-08T10:00:00Z").elapsed(now))
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
    fun anActiveTaskSaysHowLongSinceItWasCreated() {
        val start = "2026-10-08T09:15:00Z"
        val t = Task("k1", "Going", TaskStatus.IN_PROGRESS, null, createdAt = start)
        val at = Tasks.epochMillis(start)!!
        assertEquals("just started", t.elapsed(at + 30_000))
        assertEquals("12m", t.elapsed(at + 12 * 60_000))
        assertEquals("3h 05m", t.elapsed(at + (3 * 60 + 5) * 60_000L))
        assertEquals("2d 4h", t.elapsed(at + ((2 * 24 + 4) * 60 + 59) * 60_000L))
    }

    @Test
    fun aTaskWithNoKnownCreationOrThatHasFinishedSaysNothingAboutTime() {
        val now = Tasks.epochMillis("2026-10-08T12:00:00Z")!!
        assertNull(Task("k1", "Going", TaskStatus.IN_PROGRESS, null).elapsed(now))
        assertNull(
            Task("k1", "Shipped", TaskStatus.MERGED, "2026-10-08T11:00:00Z", createdAt = "2026-10-08T10:00:00Z")
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

    /**
     * One ask per visit to the list. The Worker answers in one request,
     * fast enough to finish before `/auth/me` does, and the reload that
     * follows sign-in asked a second time: CI's `TasksLoadTest` "the
     * first visit should ask the Worker once expected:<1> but was:<2>".
     */
    @Test
    fun loadedTasksAreFreshUntilTheVisitEnds() {
        assertFalse(Tasks().fresh, "nothing loaded is not fresh")
        assertTrue(Tasks().loaded(decoded()).fresh)
        assertFalse(Tasks().loaded(decoded()).loading().fresh)
        assertFalse(Tasks().failed("offline").fresh, "a failed load must be asked again")
        assertFalse(Tasks().loaded(decoded()).stale().fresh)
    }

    private fun onTheList() = AppState(
        admin = Admin().signIn(Account(key = "e7de0cb1", role = "admin"), "t"),
        tasks = Tasks().loaded(decoded()),
    ).navigate(View.ADMIN).let { it.copy(tasks = Tasks().loaded(decoded())) }

    @Test
    fun landingOnTheSameRouteAgainKeepsTheTasksFresh() {
        val s = onTheList()
        assertTrue(s.navigate(s.route).tasks.fresh, "the reload after sign-in would ask for the tasks again")
    }

    @Test
    fun leavingTheListOrPullingToRefreshAsksAgain() {
        val s = onTheList()
        assertFalse(s.navigate(Route(View.ADMIN, "t4pee71g")).tasks.fresh, "opening a person did not end the visit")
        assertFalse(s.navigate(View.LIBRARY).tasks.fresh, "leaving Admin Settings did not end the visit")
        assertFalse(s.refreshed().tasks.fresh, "a pull did not ask for the tasks again")
    }

    @Test
    fun theTasksPanelKnowsWhatTimeItIs() {
        assertEquals(42L, Tasks().at(42L).now)
    }
}
