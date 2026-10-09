package org.mattshoe.mtg.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Job
import org.mattshoe.mtg.core.AppState

/**
 * Where the app's state lives.
 *
 * It used to live in a field on `MainActivity`:
 *
 *     private var held by mutableStateOf(AppState())
 *
 * A field dies with the activity, and Android destroys the activity
 * on every configuration change — a rotation, the keyboard appearing,
 * the font size changing, dark mode switching on at sunset. Each one
 * threw away the route, the filters, the search results, the open
 * deck, the unsaved mass-entry list and the unlock, and the app
 * reopened on an empty Library. Matt: "Configuration changes wipe out
 * all state in the Android app."
 *
 * A `ViewModel` is the one thing that survives that recreation
 * without being written to a `Bundle` and read back, which matters
 * because `AppState` holds whole pages of rows. The activity now owns
 * nothing but the view; this owns everything it was holding.
 *
 * The in-flight work moves here too, on `viewModelScope`. A load
 * launched on `lifecycleScope` is cancelled by the rotation that
 * recreates the activity, so turning the phone over mid-search used
 * to abandon the search as well as forget it.
 */
class MtgViewModel : ViewModel() {

    /**
     * The whole app, as one value.
     *
     * Still Compose state, so the shell recomposes on a write exactly
     * as it did when the activity held it. Only the owner changed.
     */
    var app by mutableStateOf(AppState())

    /**
     * The facet load, held so a test can wait for it rather than
     * guess. It lives here rather than on the activity so a rotation
     * mid-load does not lose the handle to work that is still running.
     */
    var facetsJob: Job? = null

    /** Why the facet load gave up, if it did. See `MainActivity.loadFacets`. */
    var facetsError: Exception? = null

    /** Whether the facet load actually wrote its result. */
    var facetsApplied: Boolean = false

    /**
     * The token load, held so a test can wait for it rather than
     * guess — the same reason `facetsJob` is held.
     */
    var tokensJob: Job? = null

    /** The release notes load, held for the same reason. */
    var releasesJob: Job? = null

    /** The tasks load, held for the same reason. */
    var tasksJob: Job? = null

    /** The minute tick that keeps a running task's elapsed time current. */
    var tasksClock: Job? = null

    var lookupJob: Job? = null
    var findJob: Job? = null
    var tweakJob: Job? = null
    var commanderJob: Job? = null

    /**
     * Whether `onCreate` has already restored and loaded.
     *
     * The activity runs `onCreate` again after every configuration
     * change. Without this, each rotation re-ran the opening fetch
     * and reset `app` to a fresh `AppState`, which is the same bug
     * wearing a different hat.
     */
    var started: Boolean = false
}
