package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.Tools
import org.mattshoe.mtg.core.View

/**
 * The Tools tab: Fetch and Entry as tiles. Sibling of `ToolsScreen`
 * on the phone.
 *
 * Matt: "the tools page should look very similar in presentation to
 * the users tab, but instead of profile avatars, use the current icons
 * on the bottom nav for them". So it is `UsersPage`'s tile, with the
 * phone's bar icon for the tool masked into the ring a face sits in.
 */
@Composable
fun ToolsPage(tools: List<View>, onOpen: (View) -> Unit) {
    Div(attrs = { classes("wrap", "tools") }) {
        Div(attrs = { classes("user-tiles", "tool-tiles") }) {
            tools.forEach { tool ->
                Button(attrs = {
                    classes("guide-tile", "user-tile", "tool-tile")
                    attr("data-tool", tool.slug)
                    onClick { onOpen(tool) }
                }) {
                    Div(attrs = {
                        classes("guide-band")
                        style { property("background-image", band("WUBRG")) }
                    }) {}
                    Span(attrs = { classes("user-face", "user-initial", "tool-face") }) {
                        Span(attrs = {
                            classes("icon", "icon-${tool.slug}")
                            attr("aria-hidden", "true")
                        }) {}
                    }
                    Div(attrs = { classes("user-words") }) {
                        Div(attrs = { classes("guide-tile-title", "user-name") }) { Text(tool.label) }
                        Div(attrs = { classes("guide-tile-blurb") }) { Text(Tools.blurb(tool)) }
                    }
                }
            }
        }
    }
}
