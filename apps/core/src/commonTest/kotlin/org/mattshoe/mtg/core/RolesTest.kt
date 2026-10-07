package org.mattshoe.mtg.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two roles, and the screen that hands one out.
 *
 * Matt: "2 roles: user and admin. Every new account gets the user
 * role. Only SPECIFIC accounts that I DECIDE get the admin role.
 * Anyone with the user role can only edit their own cards. Anyone with
 * the admin role will be able to do whatever they want, from modify
 * others cards to giving other users admin etc etc. Then under account
 * avatar, we'll create a button 'Admin Settings' and in there will
 * live all of the admin knobs and levers like assigning roles etc"
 *
 * `admin` edits anybody's cards again here. It did until this morning,
 * then it did not for an hour, because "NOBODY GETS FUCKING ADMIN
 * PERMISSIONS!!!!!!" was about the default rather than about what the
 * role means once granted. Both hold: every new account is a `user`
 * and a `user` owns only its own cards; nobody is an `admin` unless
 * Matt says so, and an `admin` does what it likes.
 */
class RolesTest {

    private val me = Account(slug = "matt", name = "Matt", key = "e7de0cb1")
    private val boss = me.copy(role = Role.ADMIN)

    // ------------------------------------------------------- the two roles

    @Test
    fun thereAreExactlyTwo() {
        assertEquals(listOf("user", "admin"), Role.all)
        assertEquals("user", Role.USER)
        assertEquals("admin", Role.ADMIN)
    }

    @Test
    fun anAccountIsAUserUnlessItSaysOtherwise() {
        assertEquals(Role.USER, Account(slug = "kayla").role)
        assertFalse(Account(slug = "kayla").isOperator)
    }

    @Test
    fun aUserOwnsItsOwnCollectionAndNoOther() {
        assertTrue(me.owns("matt"))
        assertFalse(me.owns("kayla"))
    }

    @Test
    fun anAdminOwnsEverybodys() {
        assertTrue(boss.owns("matt"))
        assertTrue(boss.owns("kayla"), "the admin role does not reach other collections")
        // An admin reaches every collection there is, and "" is not
        // one of them — it is "nobody said yet", which `scopedLibrary`
        // and `DeckQueries` both refuse outright.
        assertTrue(boss.owns(""), "an admin is allowed everywhere, including nowhere in particular")
    }

    @Test
    fun aRoleNobodyRecognisesIsNotAnAdmin() {
        // Whatever lands in the column, only the one word is the role.
        listOf("", "ADMIN", "admin ", "owner", "superuser").forEach {
            assertFalse(me.copy(role = it).isOperator, "[$it] counted as admin")
        }
    }

    // --------------------------------------------------- the settings screen

    @Test
    fun adminSettingsIsItsOwnViewBehindTheProfile() {
        assertEquals("Admin Settings", View.ADMIN.label)
        assertFalse(View.ADMIN.bar, "it is in the bottom bar")
        assertTrue(View.ADMIN.operator, "anybody signed in could reach it")
        assertTrue(View.ADMIN.inNav)
    }

    @Test
    fun andOnlyAnAdminIsOfferedIt() {
        assertEquals(emptyList(), Admin().behindProfile)
        assertEquals(emptyList(), Admin().signIn(me, "s").behindProfile)
        assertEquals(
            listOf(View.ADMIN, View.LOGS),
            Admin().signIn(boss, "s").behindProfile,
            "the profile's admin half is not what it should be",
        )
    }

    @Test
    fun aUserWhoTypesTheAddressBounces() {
        assertEquals(View.LIBRARY, Admin().signIn(me, "s").land(Route(View.ADMIN)).view)
        assertEquals(View.ADMIN, Admin().signIn(boss, "s").land(Route(View.ADMIN)).view)
    }

    @Test
    fun itHasAnAddressOfItsOwnThatRoundTrips() {
        val r = Route(View.ADMIN)
        assertEquals(r, Route.parse(r.toHash()))
        assertEquals("#/admin", r.toHash())
    }

    // ------------------------------------------------------- the role list

    @Test
    fun theListStartsEmptyAndSaysSoRatherThanLookingLoaded() {
        val s = People()
        assertTrue(s.rows.isEmpty())
        assertFalse(s.busy)
        assertNull(s.error)
    }

    @Test
    fun whatComesBackIsWhoIsThere() {
        val s = People().loaded(
            listOf(
                Person("matt", "Matt", null, Role.ADMIN, "e7de0cb1"),
                Person("kayla", "Kayla", null, Role.USER, "a1b2c3d4"),
            ),
        )
        assertEquals(listOf("matt", "kayla"), s.rows.map { it.slug })
        assertFalse(s.busy)
        assertTrue(s.rows.first().isAdmin)
        assertFalse(s.rows.last().isAdmin)
    }

    @Test
    fun aFailureSaysWhatWentWrongAndKeepsNothingStale() {
        val s = People().loaded(listOf(Person("matt", "Matt", null, Role.ADMIN, "k"))).failed("500")
        assertEquals("500", s.error)
        assertFalse(s.busy)
        assertTrue(s.rows.isEmpty(), "a stale list under an error reads as current")
    }

    @Test
    fun aPersonIsOfferedTheRoleTheyDoNotHave() {
        val user = Person("kayla", "Kayla", null, Role.USER, "k")
        val admin = Person("matt", "Matt", null, Role.ADMIN, "m")
        assertEquals(Role.ADMIN, user.otherRole)
        assertEquals(Role.USER, admin.otherRole)
    }

    @Test
    fun oneRowAtATimeIsBusyAndNotTheWholeScreen() {
        // Pressing a row's button must not grey out every other row:
        // a list that goes dead on one press reads as broken.
        val s = People()
            .loaded(listOf(Person("kayla", "Kayla", null, Role.USER, "k")))
            .changing("kayla")
        assertEquals("kayla", s.changing)
        assertTrue(s.isChanging("kayla"))
        assertFalse(s.isChanging("matt"))
    }

    @Test
    fun aChangedRoleLandsOnThatRowAlone() {
        val s = People()
            .loaded(
                listOf(
                    Person("kayla", "Kayla", null, Role.USER, "k"),
                    Person("zoe", "Zoe", null, Role.USER, "z"),
                ),
            )
            .changing("kayla")
            .changed("kayla", Role.ADMIN)
        assertEquals(Role.ADMIN, s.rows.first { it.slug == "kayla" }.role)
        assertEquals(Role.USER, s.rows.first { it.slug == "zoe" }.role)
        assertNull(s.changing, "the row is still spinning after it came back")
    }

    @Test
    fun aRefusalLeavesTheRowAsItWasAndSaysWhy() {
        val s = People()
            .loaded(listOf(Person("matt", "Matt", null, Role.ADMIN, "m")))
            .changing("matt")
            .refused("that is the last admin")
        assertEquals(Role.ADMIN, s.rows.single().role, "the row moved on a refusal")
        assertEquals("that is the last admin", s.error)
        assertNull(s.changing)
    }

    @Test
    fun everyRowCanBeChanged() {
        // Matt: "I want to be able to assign and remove roles at
        // will!!!! I don't want to need you for it!!! The admin
        // screen should allow me to add and remove rules from any
        // user!!!!"
        //
        // Including the last admin, including yourself. The screen
        // used to withhold that one button, which is the shape of
        // needing somebody.
        val alone = People().loaded(listOf(Person("matt", "Matt", null, Role.ADMIN, "m")))
        assertTrue(alone.mayChange("matt", me = "matt"))

        val two = People().loaded(
            listOf(
                Person("matt", "Matt", null, Role.ADMIN, "m"),
                Person("kayla", "Kayla", null, Role.USER, "k"),
            ),
        )
        assertTrue(two.mayChange("matt", me = "matt"))
        assertTrue(two.mayChange("kayla", me = "matt"))
    }

    @Test
    fun butTheOneThatCannotBeUndoneSaysSo() {
        // Taking your own last admin away leaves a database no
        // browser can promote anybody from — `ADMIN_PASSWORD` is the
        // way back. That is worth a word on the row, and not worth
        // taking the decision away.
        val alone = People().loaded(listOf(Person("matt", "Matt", null, Role.ADMIN, "m")))
        assertTrue(alone.strands("matt", me = "matt"))

        val two = People().loaded(
            listOf(
                Person("matt", "Matt", null, Role.ADMIN, "m"),
                Person("zoe", "Zoe", null, Role.ADMIN, "z"),
            ),
        )
        assertFalse(two.strands("matt", me = "matt"), "with two admins nothing is stranded")
        assertFalse(alone.strands("kayla", me = "matt"), "promoting somebody strands nothing")
    }

    @Test
    fun andSomebodyElsesRowNeverWarns() {
        val s = People().loaded(
            listOf(
                Person("matt", "Matt", null, Role.ADMIN, "m"),
                Person("kayla", "Kayla", null, Role.USER, "k"),
            ),
        )
        assertFalse(s.strands("kayla", me = "matt"))
        assertTrue(s.mayChange("kayla", me = "matt"))
    }

    // ------------------------------------------------------------ the wire

    @Test
    fun theListDecodesWhatTheServerSends() {
        val people = People.decode(
            """{"users":[
              {"slug":"matt","name":"Matt","avatar":null,"role":"admin","key":"e7de0cb1"},
              {"slug":"kayla","name":"Kayla","avatar":"http://x/y.png","role":"user","key":"a1b2c3d4"}
            ]}""",
        )
        assertEquals(2, people.size)
        assertEquals("matt", people.first().slug)
        assertEquals(Role.ADMIN, people.first().role)
        assertEquals("http://x/y.png", people.last().avatar)
    }

    @Test
    fun anUnknownRoleOnTheWireReadsAsAUserRatherThanThrowing() {
        val people = People.decode("""{"users":[{"slug":"x","role":"wizard","key":"k"}]}""")
        assertEquals(Role.USER, people.single().role, "an unknown role was taken at its word")
    }

    @Test
    fun andRubbishOnTheWireIsAnEmptyListRatherThanACrash() {
        listOf("", "not json", "{}", """{"users":null}""").forEach {
            assertEquals(emptyList(), People.decode(it), "[$it]")
        }
    }
}

/**
 * Admin Settings at more than two accounts.
 *
 * Matt, on a screen with two rows crammed side by side: "This looks
 * like FUCKING SHIT dude [...] WHAT HAPPENS WHEN SET HAVE 20 DIFFERENT
 * FUCKING ROLES?!?!?! YOU DON'T WANT ME TO BE ABLE TO FUCKING
 * SEARCH?!?! [...] I need to be able to search users and then tapping
 * one needs to open a user details page where i can assign roles!!!"
 *
 * So the list is a list — a search box and a row per person — and
 * everything you can do to somebody lives on their own page, where
 * there is room for it. A row with the controls jammed in beside the
 * name does not survive a third role, let alone twenty.
 *
 * And the address it shows is the address: `/c/<key>`. It said
 * `/c/<slug>`, which is not a page anybody can open — Matt: "why is
 * the fucking slug still not the GOD DAMN USER KEY LIKE YOU FUCKING
 * SAID".
 */
class PeopleSearchTest {

    private val everybody = listOf(
        Person("matt", "Matt Shoemaker", null, Role.ADMIN, "e7de0cb1"),
        Person("matthew-shoemaker", "Matthew Shoemaker", null, Role.USER, "a1b2c3d4"),
        Person("kayla", "Kayla", null, Role.USER, "99887766"),
    )

    private val loaded = People().loaded(everybody)

    // ------------------------------------------------------- the address

    @Test
    fun aPersonIsShownAtTheAddressTheirCollectionActuallyHas() {
        // `/c/e7de0cb1`, not `/c/matt`. The key is what an address
        // carries; the slug is what `cards.owner` holds.
        assertEquals("/c/e7de0cb1", everybody.first().address)
        assertEquals("/c/a1b2c3d4", everybody[1].address)
    }

    @Test
    fun andAnAccountWithNoKeyYetHasNoAddressToShow() {
        assertNull(Person("ghost", "Ghost", null, Role.USER, "").address)
    }

    // -------------------------------------------------------- the search

    @Test
    fun nothingTypedIsEverybody() {
        assertEquals(3, loaded.shown.size)
        assertEquals("", loaded.query)
    }

    @Test
    fun aNameNarrowsIt() {
        assertEquals(listOf("kayla"), loaded.searching("kay").shown.map { it.slug })
    }

    @Test
    fun andSoDoesASlugOrAKey() {
        assertEquals(listOf("matthew-shoemaker"), loaded.searching("matthew-").shown.map { it.slug })
        assertEquals(listOf("matt"), loaded.searching("e7de").shown.map { it.slug })
    }

    @Test
    fun caseAndSpacingDoNotMatter() {
        assertEquals(listOf("kayla"), loaded.searching("  KAYLA ").shown.map { it.slug })
    }

    @Test
    fun aRoleIsSomethingYouCanSearchFor() {
        // "who are the admins" is the question this screen exists for.
        assertEquals(listOf("matt"), loaded.searching("admin").shown.map { it.slug })
    }

    @Test
    fun andSomethingNobodyMatchesIsAnEmptyListRatherThanEverybody() {
        assertEquals(emptyList(), loaded.searching("zzzz").shown)
        assertTrue(loaded.searching("zzzz").nothingMatched)
        assertFalse(loaded.nothingMatched, "an unsearched list is not an empty search")
    }

    @Test
    fun theWholeListIsStillThereUnderneath() {
        // Searching narrows what is shown and changes nothing else:
        // a role set while a search is on must land on the row it was
        // set on, not on whatever is visible.
        val narrowed = loaded.searching("kay").changed("matt", Role.USER)
        assertEquals(3, narrowed.rows.size)
        assertEquals(Role.USER, narrowed.rows.first { it.slug == "matt" }.role)
    }

    // -------------------------------------------------------- one person

    @Test
    fun aPersonHasAPageOfTheirOwn() {
        val r = Route(View.ADMIN, "e7de0cb1")
        assertEquals(r, Route.parse(r.toHash()))
        assertEquals("#/admin/e7de0cb1", r.toHash())
    }

    @Test
    fun andTheAppFindsThemByTheKeyInTheAddress() {
        val s = AppState(people = loaded).navigate(Route(View.ADMIN, "e7de0cb1"))
        assertEquals("matt", s.person?.slug)
    }

    @Test
    fun aKeyNobodyHasIsNobodyRatherThanTheFirstPerson() {
        val s = AppState(people = loaded).navigate(Route(View.ADMIN, "nope"))
        assertNull(s.person)
    }

    @Test
    fun andTheListItselfIsTheAddressWithNobodyNamed() {
        val s = AppState(people = loaded).navigate(Route(View.ADMIN))
        assertNull(s.person, "the list page picked somebody")
    }

    @Test
    fun everyRoleIsOfferedOnThePageRatherThanOneToggle() {
        // Matt: "WHAT HAPPENS WHEN SET HAVE 20 DIFFERENT FUCKING
        // ROLES?!?!" — the page lists the roles there are, so a third
        // one needs no new control.
        assertEquals(Role.all, Role.all)
        val person = everybody.first()
        assertEquals(listOf("user", "admin"), Role.all)
        assertTrue(person.isAdmin)
    }
}
