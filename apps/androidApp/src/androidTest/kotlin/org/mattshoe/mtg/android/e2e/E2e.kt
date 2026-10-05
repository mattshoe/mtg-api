package org.mattshoe.mtg.android.e2e

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.mattshoe.mtg.android.MainActivity
import org.mattshoe.mtg.android.Wiring
import org.mattshoe.mtg.core.AppState

/**
 * The base every end-to-end journey sits on.
 *
 * Two rules in a chain, and the order is the whole thing: the fake
 * worker has to be listening and `Wiring.apiBase` has to be pointing
 * at it **before** `MainActivity` is constructed, because the
 * activity builds its `MtgApi` in a field initialiser and the first
 * fetch goes out of `onCreate`. Compose's activity rule launches on
 * `before()`, so it goes inside.
 *
 * What separates these from the suite in `sharedTest` is what is
 * real. Those mount `AppShell` with a hand-built `AppState` and ask
 * whether the chrome looks right; this presses the app's own
 * buttons and lets the answer come back over a socket from SQL that
 * actually ran. A screen that renders beautifully against invented
 * state and shows nothing against the real query is a bug only this
 * kind of test can see, and this repository has shipped four of
 * them.
 */
internal abstract class E2eTest {

    private val worker = object : ExternalResource() {
        override fun before() {
            // A journey starts the way a fresh install does.
            //
            // The app persists the admin token, so the login
            // journey left the next journey already signed in and
            // its profile menu offering "Log out" where the test
            // was waiting for "Log in". Found by this suite on its
            // second run, which is the sort of thing only a test
            // that drives the real app can find.
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("mtg", Context.MODE_PRIVATE)
                .edit().clear().commit()
            fake = FakeWorker.start()
            Wiring.apiBase = fake.base
        }

        override fun after() {
            Wiring.apiBase = null
            runCatching { fake.close() }
        }
    }

    protected lateinit var fake: FakeWorker

    protected val compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> =
        createAndroidComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(worker).around(compose)

    /** What the app is actually holding, read off the real activity. */
    protected fun state(): AppState = compose.activity.stateForTesting()

    /**
     * Wait for something the network has to deliver.
     *
     * `waitForIdle` is not enough on its own here and it is worth
     * saying why: it waits for Compose to stop recomposing, and a
     * request that is still in flight leaves Compose perfectly idle
     * with an empty screen. Every wait in these tests is therefore
     * for a fact — rows arrived, the route changed — and never for
     * a duration.
     */
    protected fun until(said: String, timeoutMs: Long = 10_000, it: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs, it)
        } catch (e: Throwable) {
            throw AssertionError(
                "$said — gave up after ${timeoutMs}ms. " +
                    "The app was on ${state().view} and the worker had been asked " +
                    "${fake.statements.size} questions.",
                e,
            )
        }
    }

    protected fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    /**
     * Wait until the app has stopped asking the server things.
     *
     * `onCreate` starts two loads, not one: the view's own fetch and
     * the facet load behind the filter panel. A test that snapshots
     * anything about traffic while the second is still in flight
     * measures a race rather than the app.
     */
    protected fun settled() {
        until("the Library never filled") { state().library.rows.isNotEmpty() }
        until("the facet load never finished") {
            compose.activity.facetsJob?.isCompleted ?: false
        }
        compose.waitForIdle()
    }

    /**
     * A card the app has actually got, named the way it is drawn.
     *
     * `fullName` and not `name`, because that is what the tile's
     * text and its picture's description both say.
     */
    protected fun firstCardOnScreen(): String = state().library.rows.first().fullName
}
