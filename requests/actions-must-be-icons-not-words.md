---
status: ready
merge: auto
---

# The seventeen actions that are still words

Matt: "The agents keep doing stupid shit like using text instead of
icons. Instead of an X icon or trash icon, just using the text
'remove', same with share and edit etc etc. This looks terrible and
dated."

The rule is written and enforced — see "An action is an icon, not the
word for it" in CLAUDE.md, and `test/icon-actions.test.js`. A NEW
word-labelled action fails the gate as of now.

This request is the backlog the ratchet started with:
`test/fixtures/text-label-actions.json`, seventeen labels across both
platforms. Replace them with drawn icons and take each one out of that
file as you go.

They pair up across platforms, so do them in pairs:

| Android | Web | label |
|---|---|---|
| `CardCarousel.kt` | `CardCarousel.kt` | Remove |
| `DecksScreen.kt` | `DecksPage.kt` | Edit list, Rename |
| — | `DeckDialogs.kt` | Edit list, Rename deck |
| `FetchListScreen.kt` | `FetchListPage.kt` | Delete, Edit |
| `FilterSheet.kt` | `FilterPanel.kt` | clear |
| `MassEntryScreen.kt` | `MassEntryPage.kt` | Clear |
| `NewTaskScreen.kt` | `NewTaskPage.kt` | Remove |

Icons go where the existing ones live: `NavIcons.kt` on Android,
`Icons.kt` plus a CSS mask on the web. Four new shapes cover the lot —
a bin, a pencil, a cross and a copy mark — and the share arrow already
exists.

Keep the word as `contentDescription` / `aria-label`. An icon with no
label reads as nothing at all to a screen reader.

Check the `DeckDialogs.kt` ones before changing them: if the dialog
itself asks "Rename this deck?", the button under it should stay a
word, and the right move is to take it off the list with a line in the
commit saying why.

**This is a tablet-and-phone change on both platforms**, so it wants
the geometry tests at both sizes like everything else here: the icon
has to be at least 40dp of hittable area and sit where the word used
to, not shrink the row.
