package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Span

/**
 * Icons, with no network and no icon font.
 *
 * A span whose background is `currentColor`, masked to the shape.
 * Not an inline `<svg>`: Compose HTML's `TagElement` builds elements
 * in the HTML namespace, and an `<svg>` there is inert — it renders
 * as nothing at all, which is exactly what the first attempt did.
 * Not an `<img>` either: that cannot take the colour of the text
 * around it, so it would not dim with the button it sits in.
 *
 * The shape itself is in the stylesheet, so a mask is one CSS rule
 * rather than a data URI repeated through the markup.
 */
@Composable
fun ShareIcon() {
    Span(attrs = {
        classes("icon", "icon-share")
        attr("aria-hidden", "true")
    }) {}
}

/**
 * The X every window has had since windows had corners.
 *
 * Same mask trick as the share mark: an inline `<svg>` built through
 * `TagElement` lands in the HTML namespace and renders as nothing.
 */
@Composable
fun CloseIcon() {
    Span(attrs = {
        classes("icon", "icon-close")
        attr("aria-hidden", "true")
    }) {}
}

/**
 * A head and shoulders, where the account's own picture would be.
 *
 * The same mask trick as the others, and the same shape the phone
 * draws in `NavIcons.ProfileIcon` — a circle over an arc — so the two
 * profile buttons are recognisably one control on two platforms.
 */
@Composable
fun ProfileIcon() {
    Span(attrs = {
        classes("icon", "icon-profile")
        attr("aria-hidden", "true")
    }) {}
}
