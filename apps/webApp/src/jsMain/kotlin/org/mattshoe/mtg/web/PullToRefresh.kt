package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div
import org.mattshoe.mtg.core.Pull

/**
 * The circle that comes down with a pull to refresh.
 *
 * The phone's `PullToRefreshBox` draws its own. This is the web's,
 * following the finger by [travel] and spinning in place while the
 * pulled page is [refreshing] — which is `AppState.refreshing`, a pull
 * still waiting, so opening a page never brings it down.
 */
@Composable
fun PullIndicator(travel: Double, refreshing: Boolean) {
    val shown = refreshing || travel > 0.0
    val drop = if (refreshing) Pull.THRESHOLD else minOf(travel, Pull.THRESHOLD * 1.5)
    Div({
        classes("pull")
        if (shown) classes("shown")
        attr("data-pull", "")
        attr("data-refreshing", refreshing.toString())
        style { property("transform", "translate(-50%, ${drop}px)") }
    }) {
        Div({
            classes("spinner")
            // Wound round as it is pulled, and only spinning on its own
            // once it has been let go and the page is waiting.
            if (!refreshing) style { property("transform", "rotate(${travel * 3}deg)") }
        })
    }
}
