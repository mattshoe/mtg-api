package org.mattshoe.mtg.core

/**
 * What is on top, and what the back button should do about it.
 *
 * The web version of this pushes a history entry per overlay so Android
 * Chrome's back gesture dismisses the drawer instead of navigating out
 * from under it (`overlay.js`). Android has the same problem and the
 * same answer, and "which overlay does back close" is a rule rather
 * than a rendering detail, so it lives here.
 */
enum class Overlay {
    CARD,
    PALETTE,
    CHEATSHEET,
    DECK_EDIT,
    DISASSEMBLE,
    UNLOCK,
    NEW_DECK,
}

/**
 * A stack, outermost first. Two can be up at once — the cheatsheet over
 * the Library, the disassemble confirmation over a deck — and back has
 * to take them off one at a time.
 */
data class Overlays(val stack: List<Overlay> = emptyList()) {

    val top: Overlay? get() = stack.lastOrNull()
    val any: Boolean get() = stack.isNotEmpty()

    operator fun contains(o: Overlay) = o in stack

    /** Opening the same one twice does not stack it twice. */
    fun open(o: Overlay) = if (o in stack) this else Overlays(stack + o)

    /** Back, or escape: whatever is on top goes. */
    fun pop() = if (stack.isEmpty()) this else Overlays(stack.dropLast(1))

    /** Closed by its own X, which may not be the top one. */
    fun close(o: Overlay) = Overlays(stack.filterNot { it == o })

    /** A route change takes them all with it. */
    fun clear() = Overlays()
}
