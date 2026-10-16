package org.mattshoe.mtg.core

/**
 * Make every call to [base] carry the session cookie, where a platform
 * keeps the session in one.
 *
 * Only the browser does. The website's session is an HttpOnly cookie
 * on the API's domain, and a browser sends a cookie to another domain
 * only when the fetch says `credentials: 'include'`. Ktor's JS engine
 * has nowhere to say it, so every signed-in call from the site went
 * out as a stranger: Admin Settings read "Could not load tasks: that
 * needs the admin or agent role" for an admin. The phone sends its
 * session as a bearer header and has nothing to do here.
 */
internal expect fun sendSessionTo(base: String)
