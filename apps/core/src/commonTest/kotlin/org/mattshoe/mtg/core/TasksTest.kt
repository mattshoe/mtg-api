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
 * A task is an intake request: a `request/<slug>-<digest>` branch, and
 * the pull request its builder opens from it. What GitHub can see of
 * that is all the app shows.
 */
class TasksTest {

    private fun pr(ref: String, title: String, state: String, merged: String? = null, closed: String? = null) =
        """{"title":"$title","state":"$state","head":{"ref":"$ref"},
            "merged_at":${merged?.let { "\"$it\"" } ?: "null"},
            "closed_at":${closed?.let { "\"$it\"" } ?: "null"}}"""

    private fun refs(vararg names: String) =
        names.joinToString(",", "[", "]") { """{"ref":"refs/heads/$it"}""" }

    private fun done(vararg names: String) =
        names.joinToString(",", "[", "]") { """{"name":"$it","type":"file"}""" }

    private val pulls = listOf(
        pr("request/merged-one-abc1234", "Merged one", "closed", merged = "2026-10-01T10:00:00Z", closed = "2026-10-01T10:00:00Z"),
        pr("request/merged-two-def5678", "Merged two", "closed", merged = "2026-10-03T12:30:00Z", closed = "2026-10-03T12:30:00Z"),
        pr("request/given-up-0a0b0c0", "Given up", "closed", closed = "2026-10-02T08:00:00Z"),
        pr("request/waiting-on-ci-1112223", "Waiting on CI", "open"),
        pr("fix-something-by-hand", "Not a request", "open"),
    ).joinToString(",", "[", "]")

    private val allRefs = refs(
        "request/merged-one-abc1234",
        "request/merged-two-def5678",
        "request/given-up-0a0b0c0",
        "request/waiting-on-ci-1112223",
        "request/still-going-9998887",
        "main",
    )

    private fun decoded() = Tasks.decode(pulls, allRefs, done("merged-one.md", "merged-two.md"))

    private fun statusOf(title: String) = decoded().single { it.title == title }.status.word

    @Test
    fun eachTaskSaysWhereItIsInOneWord() {
        assertEquals("done", statusOf("Merged one"))
        assertEquals("closed", statusOf("Given up"))
        assertEquals("in review", statusOf("Waiting on CI"))
        assertEquals("building", statusOf("still going"))
    }

    @Test
    fun aPullRequestThatIsNotARequestIsNotATask() {
        assertTrue(decoded().none { it.title == "Not a request" })
    }

    @Test
    fun aRebuiltRequestIsOneTaskAtItsFurthestStatus() {
        val twice = listOf(
            pr("request/again-1234567", "First try", "closed", closed = "2026-10-01T00:00:00Z"),
            pr("request/again-1234567", "Second try", "closed", merged = "2026-10-04T00:00:00Z", closed = "2026-10-04T00:00:00Z"),
        ).joinToString(",", "[", "]")
        val task = Tasks.decode(twice, refs("request/again-1234567"), done()).single()
        assertEquals("done", task.status.word)
        assertEquals("2026-10-04T00:00:00Z", task.finishedAt)
    }

    @Test
    fun aBranchWithNoPullRequestIsNamedAfterTheRequest() {
        val t = Tasks.decode("[]", refs("request/deck-colour-pie-a1b2c3d", "request/orphan-second-matt-account"), done())
        assertEquals(listOf("deck colour pie", "orphan second matt account"), t.map { it.title }.sorted())
    }

    @Test
    fun aBranchFiledUnderDoneWhosePullRequestAgedOutIsNotStillBuilding() {
        val long = "a".repeat(90)
        val t = Tasks.decode(
            "[]",
            refs("request/finished-long-ago-1234567", "request/${"a".repeat(84)}-7654321", "request/still-going-9998887"),
            done("Finished Long Ago.md", "$long.md"),
        )
        assertEquals(listOf("still going"), t.map { it.title })
    }

    @Test
    fun doneTasksAreNewestFirstAndActiveOnesAreApart() {
        val s = Tasks().loaded(decoded())
        assertEquals(listOf("Waiting on CI", "still going"), s.active.map { it.title })
        assertEquals(listOf("Merged two", "Given up", "Merged one"), s.done.map { it.title })
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
     * timestamp". From the branch's first commit to the merge, or the
     * close, in the same words a running one counts in.
     */
    @Test
    fun aFinishedTaskSaysHowLongItTook() {
        val shipped = Task("request/x-1234567", "Shipped", TaskStatus.DONE, "2026-10-08T13:05:00Z", startedAt = "2026-10-08T10:00:00Z")
        assertEquals("took 3h 05m", shipped.took)
        val dropped = Task("request/y-1234567", "Dropped", TaskStatus.CLOSED, "2026-10-08T10:12:00Z", startedAt = "2026-10-08T10:00:00Z")
        assertEquals("took 12m", dropped.took)
    }

    @Test
    fun aTaskWithNoKnownStartOrStillGoingSaysNothingAboutHowLongItTook() {
        assertNull(Task("request/x-1234567", "Shipped", TaskStatus.DONE, "2026-10-08T11:00:00Z").took)
        assertNull(Task("request/x-1234567", "Going", TaskStatus.BUILDING, null, startedAt = "2026-10-08T10:00:00Z").took)
    }

    /**
     * A finished task's start never changes, and GitHub allows sixty
     * unsigned asks an hour. Kept between launches, every done task is
     * asked about once rather than on every visit to Admin Settings.
     */
    @Test
    fun aFinishedTasksStartIsKeptBetweenLaunchesAndARunningOnesIsNot() {
        val store = Store.inMemory()
        Tasks.saveStarts(
            store,
            listOf(
                Task("request/x-1234567", "Shipped", TaskStatus.DONE, "2026-10-08T11:00:00Z", startedAt = "2026-10-08T10:00:00Z"),
                Task("request/y-1234567", "Going", TaskStatus.BUILDING, null, startedAt = "2026-10-08T09:00:00Z"),
                Task("request/z-1234567", "Unknown", TaskStatus.DONE, "2026-10-08T11:00:00Z"),
            ),
        )
        assertEquals(mapOf("request/x-1234567" to "2026-10-08T10:00:00Z"), Tasks.knownStarts(store))
    }

    @Test
    fun nothingKeptOrSomethingUnreadableIsNoKnownStarts() {
        assertEquals(emptyMap(), Tasks.knownStarts(Store.inMemory()))
        val store = Store.inMemory().apply { put("task-starts", "not json") }
        assertEquals(emptyMap(), Tasks.knownStarts(store))
    }

    @Test
    fun anythingUnreadableIsNoTasksRatherThanACrash() {
        assertTrue(Tasks.decode("""{"message":"API rate limit exceeded"}""", "<html>", "nope").isEmpty())
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
        val t = Task("request/x-1234567", "Going", TaskStatus.BUILDING, null, startedAt = start)
        val at = Tasks.epochMillis(start)!!
        assertEquals("just started", t.elapsed(at + 30_000))
        assertEquals("12m", t.elapsed(at + 12 * 60_000))
        assertEquals("3h 05m", t.elapsed(at + (3 * 60 + 5) * 60_000L))
        assertEquals("2d 4h", t.elapsed(at + ((2 * 24 + 4) * 60 + 59) * 60_000L))
    }

    @Test
    fun aTaskWithNoKnownStartOrThatHasFinishedSaysNothingAboutTime() {
        val now = Tasks.epochMillis("2026-10-08T12:00:00Z")!!
        assertNull(Task("request/x-1234567", "Going", TaskStatus.BUILDING, null).elapsed(now))
        assertNull(
            Task("request/x-1234567", "Shipped", TaskStatus.DONE, "2026-10-08T11:00:00Z", startedAt = "2026-10-08T10:00:00Z")
                .elapsed(now),
        )
    }

    @Test
    fun anIsoTimeFromGitHubIsReadAsMillisSinceTheEpoch() {
        assertEquals(0L, Tasks.epochMillis("1970-01-01T00:00:00Z"))
        assertEquals(1_791_450_900_000L, Tasks.epochMillis("2026-10-08T09:15:00Z"))
        assertNull(Tasks.epochMillis("yesterday"))
    }

    @Test
    fun theStartOfABranchIsItsFirstCommit() {
        val compare = """{"status":"ahead","commits":[
            {"commit":{"author":{"date":"2026-10-08T09:15:00Z"},"committer":{"date":"2026-10-08T09:16:00Z"}}},
            {"commit":{"author":{"date":"2026-10-08T10:40:00Z"},"committer":{"date":"2026-10-08T10:40:00Z"}}}]}"""
        assertEquals("2026-10-08T09:15:00Z", Tasks.firstCommitAt(compare))
        assertNull(Tasks.firstCommitAt("""{"status":"identical","commits":[]}"""))
        assertNull(Tasks.firstCommitAt("""{"message":"Not Found"}"""))
    }

    @Test
    fun theTasksPanelKnowsWhatTimeItIs() {
        assertEquals(42L, Tasks().at(42L).now)
    }
}
