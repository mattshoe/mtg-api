package org.mattshoe.mtg.web

import org.mattshoe.mtg.core.AppState
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/**
 * A browser keystroke, through the shared shortcut table.
 *
 * Its own function rather than a lambda inside the listener so it can
 * be tested with a real `KeyboardEvent` — the interesting part is the
 * "not while typing" rule, which is about the event's target and not
 * about anything `Shortcuts` can see.
 *
 * Returns null when the key means nothing here, which is the signal to
 * leave the event alone.
 */
fun AppState.onBrowserKey(event: KeyboardEvent): AppState? {
    val tag = (event.target as? HTMLElement)?.tagName.orEmpty()
    val typing = tag == "INPUT" || tag == "TEXTAREA" || tag == "SELECT"
    return onKey(event.key, typing, event.metaKey, event.ctrlKey)
}
