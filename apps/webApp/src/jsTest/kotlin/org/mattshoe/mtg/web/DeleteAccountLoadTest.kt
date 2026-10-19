package org.mattshoe.mtg.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mattshoe.mtg.core.MtgApi
import org.mattshoe.mtg.core.People
import org.mattshoe.mtg.core.Scryfall
import org.w3c.dom.HTMLElement
import org.w3c.fetch.Response
import org.w3c.fetch.ResponseInit
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deleting Kayla's account from Admin Settings, through the real
 * website and the real `MtgApi`, over a stubbed Worker.
 *
 * The page tests prove what is drawn. This proves `Main`'s own handler:
 * the key goes up, the list comes back without her, and when the Worker
 * fails the spinner stops, the page says why and the bin works again
 * without a reload. Sibling of Android's `DeleteAccountJourneyTest`.
 */
class DeleteAccountLoadTest {

    private val base = "https://worker.test"

    private var realFetch: dynamic = null
    private val roots = mutableListOf<HTMLElement>()

    /** The keys the Worker was asked to delete, in order. */
    private val deletes = mutableListOf<String>()

    /** The Worker is down: the delete answers 500. */
    private var broken = false

    private var users = USERS

    @BeforeTest
    fun playTheWorker() {
        deletes.clear()
        broken = false
        users = USERS
        val engine = MockEngine { request ->
            val (status, body) = when (request.url.encodedPath) {
                "/admin/users" -> HttpStatusCode.OK to users
                "/admin/users/delete" -> {
                    val sent = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    val key = Json.parseToJsonElement(sent).jsonObject["key"]!!.jsonPrimitive.content
                    deletes += key
                    if (broken) HttpStatusCode.InternalServerError to """{"error":"D1 is down"}"""
                    else {
                        users = ONLY_MATT
                        HttpStatusCode.OK to """{"ok":true,"key":"$key","deleted":{"cards":3,"decks":1}}"""
                    }
                }
                "/releases" -> HttpStatusCode.OK to "[]"
                "/tasks" -> HttpStatusCode.OK to """{"tasks":[]}"""
                else -> HttpStatusCode.OK to """{"cols":[],"rows":[],"n":0}"""
            }
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        MtgApp.useForTesting(MtgApi.withEngine(base, http), Scryfall.withEngine(http))
        // Who the session is goes by `window.fetch`; see `AppDriverTest`.
        if (realFetch == null) realFetch = window.asDynamic().fetch
        window.asDynamic().fetch = { url: dynamic, init: dynamic ->
            if ("$url".contains("/auth/me")) {
                Promise.resolve(
                    Response(
                        """{"key":"e7de0cb1","name":"Matt","role":"admin"}""",
                        ResponseInit(status = 200, headers = js("({'Content-Type':'application/json'})")),
                    ),
                )
            } else {
                realFetch!!(url, init)
            }
        }
    }

    @AfterTest
    fun cleanUp() {
        val back = realFetch
        if (back != null) window.asDynamic().fetch = back
        MtgApp.unmount()
        roots.forEach { it.remove() }
        roots.clear()
        window.location.replace("#")
        window.scrollTo(0.0, 0.0)
    }

    private fun mount(hash: String): HTMLElement {
        window.location.replace(hash)
        val view = (document.createElement("div") as HTMLElement).also {
            document.body!!.appendChild(it)
            roots += it
        }
        MtgApp.mount(view, null, "")
        return view
    }

    private suspend fun tick() {
        Promise<Unit> { r, _ -> window.setTimeout({ r(Unit) }, 40) }.await()
    }

    private suspend fun waitFor(what: String, root: HTMLElement, cond: () -> Boolean) {
        repeat(125) { if (cond()) return; tick() }
        error("gave up waiting for $what: ${root.textContent}")
    }

    private fun HTMLElement.buttons(): List<HTMLElement> =
        querySelectorAll("button").let { n -> (0 until n.length).map { n.item(it)!!.unsafeCast<HTMLElement>() } }

    private fun HTMLElement.bin(): HTMLElement? = buttons().firstOrNull { it.getAttribute("aria-label") == People.DELETE }

    private suspend fun pressDelete(root: HTMLElement) {
        waitFor("Kayla's page and its bin", root) { root.bin()?.hasAttribute("disabled") == false }
        root.bin()!!.click()
        waitFor("the question", root) { root.buttons().any { it.textContent?.trim() == People.CONFIRM } }
        root.buttons().first { it.textContent?.trim() == People.CONFIRM }.click()
    }

    @Test
    fun deletingKaylaSendsHerKeyAndTheListComesBackWithoutHer() = runTest {
        val root = mount("#/admin/k4yy0003")
        pressDelete(root)
        waitFor("the list without Kayla", root) { window.location.hash.endsWith("/admin") && "/c/e7de0cb1" in root.textContent.orEmpty() }
        assertEquals(listOf("k4yy0003"), deletes, "the Worker was not asked to delete Kayla, once")
        assertTrue("/c/k4yy0003" !in root.textContent.orEmpty(), "Kayla is still on the list: ${root.textContent}")
        assertTrue("Deleted Kayla M's account" in root.textContent.orEmpty(), "nothing said it was done: ${root.textContent}")
    }

    @Test
    fun aDeleteTheWorkerFailsStopsTheSpinnerSaysWhyAndCanBeTriedAgain() = runTest {
        broken = true
        val root = mount("#/admin/k4yy0003")
        pressDelete(root)
        waitFor("the failure on the page", root) { "D1 is down" in root.textContent.orEmpty() }
        assertTrue(window.location.hash.endsWith("/admin/k4yy0003"), "a failure left Kayla's page: ${window.location.hash}")
        waitFor("the bin to come back to life", root) { root.bin()?.hasAttribute("disabled") == false }

        broken = false
        pressDelete(root)
        waitFor("the second try to land", root) { window.location.hash.endsWith("/admin") }
        assertEquals(listOf("k4yy0003", "k4yy0003"), deletes)
    }

    private companion object {
        const val USERS = """{"users":[
            {"key":"e7de0cb1","name":"Matt","role":"admin"},
            {"key":"k4yy0003","name":"Kayla M","role":"user"}]}"""
        const val ONLY_MATT = """{"users":[{"key":"e7de0cb1","name":"Matt","role":"admin"}]}"""
    }
}
