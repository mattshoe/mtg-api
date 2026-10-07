package org.mattshoe.mtg.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.mattshoe.mtg.core.People
import org.mattshoe.mtg.core.Person
import org.mattshoe.mtg.core.Role

/**
 * Admin Settings, on the web. Sibling of `AdminScreen`.
 *
 * Matt: "under account avatar, we'll create a button 'Admin Settings'
 * and in there will live all of the admin knobs and levers like
 * assigning roles etc"
 *
 * Today the only lever is who has which role, so the page is that
 * list and says as much — a page with one section and no heading
 * reads as a page that is missing something.
 */
@Composable
fun AdminPage(state: People, me: String?, onChange: (Person) -> Unit) {
    Div(attrs = { classes("wrap") }) {
        Div(attrs = { classes("panel") }) {
            Div(attrs = { classes("panel-head") }) {
                H2 { Text("Who is here") }
                Span(attrs = { classes("spacer") }) {}
                Span(attrs = { classes("muted", "small") }) {
                    Text("${state.rows.size} " + if (state.rows.size == 1) "account" else "accounts")
                }
            }
            Div(attrs = { classes("panel-body") }) {
                when {
                    state.busy -> Div(attrs = { classes("empty") }) { Text("Loading…") }
                    state.error != null ->
                        Div(attrs = { classes("err") }) { Text("Could not load accounts: ${state.error}") }
                    state.rows.isEmpty() -> Div(attrs = { classes("empty") }) { Text("No accounts yet.") }
                    else -> state.rows.forEach { person -> PersonRow(state, person, me, onChange) }
                }
            }
        }
    }
}

@Composable
private fun PersonRow(state: People, person: Person, me: String?, onChange: (Person) -> Unit) {
    val changing = state.isChanging(person.slug)
    Div(attrs = { classes("person-row") }) {
        person.avatar?.takeIf { it.isNotBlank() }
            ?.let { Img(src = it, alt = "", attrs = { classes("app-avatar") }) }
        Div(attrs = { classes("person-who") }) {
            Span { Text(person.shownName) }
            Span(attrs = { classes("app-who-slug") }) { Text("/c/${person.slug}") }
        }
        Span(attrs = { classes("spacer") }) {}
        // What they are, said in a word rather than by the state of
        // the button beside it — a button's label is what it will do,
        // not what is already true.
        Span(attrs = { classes("tag") }) { Text(person.role) }
        // The one change nothing here can undo gets a word, not a
        // locked button. Matt: "I want to be able to assign and
        // remove roles at will!!!! I don't want to need you for
        // it!!!"
        if (state.strands(person.slug, me)) {
            Span(attrs = { classes("muted", "small") }) { Text("the only admin") }
        }
        Button(attrs = {
            classes("btn", "sm")
            if (person.isAdmin) classes("ghost")
            if (changing) attr("disabled", "")
            onClick { onChange(person) }
        }) {
            Text(
                when {
                    changing -> "Working…"
                    person.isAdmin -> "Make user"
                    else -> "Make admin"
                },
            )
        }
    }
}
