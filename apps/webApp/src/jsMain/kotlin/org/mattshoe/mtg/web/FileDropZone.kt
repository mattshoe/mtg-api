package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FileList

/**
 * Drop a file, or tap to pick one.
 *
 * Shared, because the new deck wizard had no way to take a file at
 * all: the only upload on the site lived inside the mass entry
 * screen, so a deck you already had written down had to be pasted.
 */
@Composable
fun FileDrop(onFiles: (List<File>) -> Unit) {
    Div(attrs = {
        classes("dropzone")
        onDragOver { it.preventDefault() }
        onDrop { e ->
            e.preventDefault()
            onFiles(e.dataTransfer?.files.toList())
        }
    }) {
        Input(type = InputType.File) {
            // Hidden by being tiny and transparent, NOT by
            // `display: none`. Android Chrome will not open a picker for
            // an input that is not rendered, so a display:none input is
            // a button that does nothing at all.
            classes("file-in")
            attr("accept", "*/*")
            attr("multiple", "")
            onChange { e ->
                val el = e.target
                onFiles(el.files.toList())
                el.value = ""
            }
        }
        Span(attrs = { classes("dz-icon") }) { Text("⤓") }
        Div {
            Div(attrs = { classes("dz-main") }) { Text("Upload a file") }
            Div(attrs = { classes("dz-sub", "small", "muted") }) { Text("or drop one here") }
        }
    }
}

internal fun FileList?.toList(): List<File> =
    if (this == null) emptyList() else (0 until length).mapNotNull { item(it) }

