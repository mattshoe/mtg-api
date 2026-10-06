package org.mattshoe.mtg.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.mattshoe.mtg.core.AdminToken
import org.mattshoe.mtg.core.ApiFailure
import org.mattshoe.mtg.core.MassEntry
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.SharedFile
import org.mattshoe.mtg.core.Store

/**
 * The share receiver, rebuilt on the shared core.
 *
 * The file reading is `SharedFile` in `core/androidMain` — the same code
 * `:app` proved on a device, moved somewhere both apps can use it. The
 * screens are Compose. The rules are `MassEntry`, which the web build
 * obeys too.
 */
class NextShareActivity : ComponentActivity() {

    private val api = MtgApi()
    private val prefs by lazy { getSharedPreferences("mtg", Context.MODE_PRIVATE) }
    private val store: Store by lazy { PrefsStore(prefs) }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setContent {
            // remember, or every recomposition rereads the intent and
            // throws away whatever has been answered so far.
            var state by remember { mutableStateOf(readShare(intent)) }

            fun work(what: String, block: suspend () -> MassEntry) {
                state = state.working(what)
                lifecycleScope.launch {
                    state = try {
                        block()
                    } catch (e: ApiFailure) {
                        state.failed(e.message ?: "something went wrong")
                    } catch (e: Exception) {
                        state.failed(e.message ?: e.toString())
                    }
                }
            }

            MtgTheme {
                // Otherwise the heading sits under the clock.
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    MassEntryScreen(
                        state = state,
                        onState = { state = it },
                        onPreview = {
                            work("Checking against Scryfall…") {
                                state.previewed(
                                    api.cards(token(), state.direction!!, mine(), state.list, dryRun = true),
                                )
                            }
                        },
                        onApply = {
                            work("Writing…") {
                                state.finished(
                                    api.cards(token(), state.direction!!, mine(), state.list, dryRun = false),
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    private fun token() = AdminToken.restore(store).orEmpty()

    /**
     * Which collection a shared list lands in: the one the session
     * owns.
     *
     * The wizard used to ask, and a share sheet is the worst place to
     * be asked anything. This activity holds no `AppState`, so it
     * asks the server who the session is rather than keeping a slug
     * of its own that could go stale behind a sign-out.
     */
    private suspend fun mine(): String =
        api.me(token())?.slug
            ?: throw ApiFailure("Sign in on the app before sharing a list to it")

    private fun readShare(from: Intent?): MassEntry {
        val share = SharedFile.read(this, from)
        return if (share.list.isBlank()) MassEntry() else MassEntry.fromShare(share.list)
    }
}
