package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Span
import org.w3c.dom.HTMLButtonElement
import androidx.compose.web.events.SyntheticMouseEvent

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

/**
 * The drawings that stand in for an action's word.
 *
 * Matt: "Instead of an X icon or trash icon, just using the text
 * 'remove', same with share and edit etc etc. This looks terrible and
 * dated." The phone's `Glyph` is the same four, the same shapes; the
 * cross is the close mark the website already had.
 */
enum class Glyph(val css: String) {
    BIN("icon-bin"),
    PENCIL("icon-pencil"),
    CROSS("icon-close"),
    RENAME("icon-rename"),
    FLIP("icon-flip"),
}

/**
 * An action drawn rather than written.
 *
 * The word stays as `aria-label`, and as `title` so a mouse can ask
 * what it is. [look] is the button it replaces, `btn sm ghost` by
 * default; `.action` squares it off at 40px at least. The phone's
 * `IconAction`.
 */
@Composable
fun IconAction(
    label: String,
    glyph: Glyph,
    look: List<String> = listOf("btn", "sm", "ghost"),
    enabled: Boolean = true,
    attrs: AttrsScope<HTMLButtonElement>.() -> Unit = {},
    onClick: (SyntheticMouseEvent) -> Unit,
) {
    Button(attrs = {
        classes(look + "action")
        attr("type", "button")
        attr("aria-label", label)
        attr("title", label)
        if (!enabled) disabled()
        attrs()
        onClick { onClick(it) }
    }) {
        Span(attrs = {
            classes("icon", glyph.css)
            attr("aria-hidden", "true")
        }) {}
    }
}
