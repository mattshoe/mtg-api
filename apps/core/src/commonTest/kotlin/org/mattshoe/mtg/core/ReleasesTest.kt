package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Release notes, as Admin Settings lists them.
 *
 * Matt: "I want versioned release notes for every build. It should be
 * accessible via admin settings". Every merge to main cuts a GitHub
 * release, and its tag is the version; the note is the hand-written
 * file the pull request added under `release-notes/`, which
 * `release.yml` puts into that release's body.
 */
class ReleasesTest {

    private val github = """
        [
          {"tag_name":"android-v2.1.0-294","published_at":"2026-10-08T14:17:52Z",
           "body":"Built from abc. Install over the top; same signing key."},
          {"tag_name":"android-v2.1.0-296","published_at":"2026-10-09T09:01:00Z",
           "body":"Release notes in Admin Settings.\n\nBuilt from def. Install over the top; same signing key."},
          {"tag_name":"android-v2.1.0-295","published_at":"2026-10-08T14:37:59Z",
           "body":"One line.\r\nAnother line.\n\nBuilt from ghi. Install over the top; same signing key."}
        ]
    """.trimIndent()

    @Test
    fun newestFirstWhateverOrderGitHubSentThem() {
        assertEquals(
            listOf("android-v2.1.0-296", "android-v2.1.0-295", "android-v2.1.0-294"),
            Releases.decode(github).map { it.tag },
        )
    }

    @Test
    fun theVersionIsTheTagAndTheDateIsTheDayItShipped() {
        val r = Releases.decode(github).first()
        assertEquals("2.1.0 (296)", r.version)
        assertEquals("2026-10-09", r.date)
    }

    @Test
    fun theNoteIsWhatAPersonWroteNotTheBuildBoilerplate() {
        val rows = Releases.decode(github)
        assertEquals("Release notes in Admin Settings.", rows[0].note)
        assertEquals("One line.\nAnother line.", rows[1].note)
    }

    @Test
    fun aBuildNobodyWroteANoteForSaysSo() {
        val r = Releases.decode(github).last()
        assertNull(r.note)
        assertEquals("No note was written for this build.", r.shownNote)
    }

    @Test
    fun aTagThatIsNotOursIsShownAsItIs() {
        val r = Releases.decode("""[{"tag_name":"v9","published_at":"2026-01-02T00:00:00Z","body":"x"}]""").single()
        assertEquals("v9", r.version)
    }

    @Test
    fun anythingUnreadableIsNoReleasesRatherThanACrash() {
        assertTrue(Releases.decode("<html>rate limited</html>").isEmpty())
        assertTrue(Releases.decode("""{"message":"API rate limit exceeded"}""").isEmpty())
    }

    @Test
    fun aFailedLoadLeavesNothingStaleOnScreen() {
        val s = Releases().loaded(Releases.decode(github)).loading().failed("offline")
        assertTrue(s.rows.isEmpty())
        assertEquals("offline", s.error)
    }
}
