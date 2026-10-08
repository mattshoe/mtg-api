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

    @Test
    fun aFinishedTaskSaysWhenToTheMinute() {
        assertEquals("2026-10-03 12:30", decoded().single { it.title == "Merged two" }.finished)
        assertNull(decoded().single { it.title == "Waiting on CI" }.finished)
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
}
