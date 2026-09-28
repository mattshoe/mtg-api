package org.mattshoe.mtg.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.mattshoe.mtg.core.ApiFailure
import org.mattshoe.mtg.core.MassEntry
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.SharedFile

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

            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    MassEntryScreen(
                        state = state,
                        onState = { state = it },
                        onPreview = {
                            work("Checking against Scryfall…") {
                                state.previewed(
                                    api.cards(token(), state.direction!!, state.owner!!, state.list, dryRun = true),
                                )
                            }
                        },
                        onApply = {
                            work("Writing…") {
                                state.finished(
                                    api.cards(token(), state.direction!!, state.owner!!, state.list, dryRun = false),
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    private fun token() = prefs.getString("token", "").orEmpty()

    private fun readShare(from: Intent?): MassEntry {
        val share = SharedFile.read(this, from)
        return if (share.list.isBlank()) MassEntry() else MassEntry.fromShare(share.list)
    }
}
