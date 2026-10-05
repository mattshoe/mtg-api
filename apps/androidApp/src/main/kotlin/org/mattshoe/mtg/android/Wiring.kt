package org.mattshoe.mtg.android

/**
 * The one thing an end-to-end test is allowed to change about the
 * app: which host it talks to.
 *
 * `MainActivity.useForTesting` already swaps the whole `MtgApi`, but
 * it can only be called on an activity you are holding before it is
 * created, which Robolectric's `buildActivity` gives you and a real
 * device does not — there the system builds the activity. A journey
 * on a phone has to have decided where the app points *before* it
 * launches, so the decision lives here rather than on the instance.
 *
 * Null in every build anybody installs, and nothing reads it but the
 * activity's own field initialiser. It is deliberately not an intent
 * extra: an extra is a surface anything on the phone can reach, and
 * redirecting this app's API is not something another app should be
 * able to ask for.
 */
internal object Wiring {
    /** Set by `E2eTest` before the activity launches; cleared after. */
    var apiBase: String? = null
}
