package org.mattshoe.mtg.core

/**
 * What the query box understands, written down.
 *
 * A port of `cheatsheet.js`. Shared rather than duplicated per platform
 * because it documents `parseQueryBox`, which is also shared — two
 * copies of this would start describing two different parsers the first
 * time one of them learned a new key.
 */
data class Key(val keys: String, val what: String, val example: String)

data class KeyGroup(val title: String, val keys: List<Key>)

object Cheatsheet {

    /** The sentence at the top, which is the part people actually miss. */
    const val PREAMBLE: String =
        "Terms are ANDed. Put a minus in front to negate anything. Quote a phrase " +
            "with spaces. Everything here also works alongside the filter panel."

    val groups: List<KeyGroup> = listOf(
        KeyGroup(
            "Words",
            listOf(
                Key("bare word", "name contains it", "bolt"),
                Key("name:", "same, explicitly", "name:\"sol ring\""),
                Key("o: oracle: text:", "rules text contains", "o:\"draw a card\""),
                Key("t: type:", "type line contains", "t:creature  t:equipment"),
                Key("ft: flavor:", "flavour text", "ft:goblin"),
                Key("a: artist:", "artist", "a:\"rebecca guay\""),
                Key("wm: watermark:", "watermark", "wm:izzet"),
                Key("m: mana:", "mana cost contains", "m:{G}{G}"),
            ),
        ),
        KeyGroup(
            "Colour",
            listOf(
                Key("id<=wub", "identity fits in these — what a commander allows", "id<=wub"),
                Key("id=wu", "identity is exactly these", "id=wu"),
                Key("id>=wu", "identity includes all of these", "id>=wu"),
                Key("id:wu", "identity includes all of these (same as >=)", "id:wu"),
                Key("c<=r c=r c>=r", "same four, on the printed colour instead", "c=r"),
                Key("produces:g", "taps for this colour", "produces:g"),
                Key("is:colorless is:mono is:multicolor", "shorthand", "is:multicolor"),
            ),
        ),
        KeyGroup(
            "Numbers",
            listOf(
                Key("mv: cmc:", "mana value", "mv<=3  mv=0  mv>5"),
                Key("pow: tou: loy:", "power, toughness, loyalty", "pow>=5  tou<2"),
                Key("qty:", "copies of this printing owned", "qty>=4"),
                Key("free:", "copies not committed to a deck", "free>=1"),
                Key("edhrec:", "EDHREC rank, lower is more played", "edhrec<=250"),
                Key("year:", "release year", "year>=2023"),
            ),
        ),
        KeyGroup(
            "Printing",
            listOf(
                Key("r: rarity:", "rarity", "r:mythic"),
                Key("s: set: e:", "set code", "s:mh3"),
                Key("st: settype:", "set type", "st:commander  st:masters"),
                Key("layout:", "card layout", "layout:saga"),
                Key("cn:", "collector number", "cn:117"),
                Key("game:", "available in", "game:paper"),
            ),
        ),
        KeyGroup(
            "Oracle-level",
            listOf(
                Key("kw: keyword:", "keyword ability", "kw:flying"),
                Key("tag:", "Scryfall tag", "tag:mana-rock"),
                Key("f: format:", "legal in a format", "f:commander"),
                Key("banned: restricted:", "banned or restricted there", "banned:commander"),
            ),
        ),
        KeyGroup(
            "Collection",
            listOf(
                Key("owner:", "whose, by name or collection key", "owner:kayla"),
                Key("deck:", "in this deck, by its key or its name", "deck:\"milly moth\""),
                Key("is:free", "has an unassigned copy", "is:free"),
                Key("is:indeck", "slotted into some deck", "-is:indeck"),
            ),
        ),
    )

    /** The `is:` list, from the parser rather than from a second copy. */
    val isValues: List<String> get() = IS_VALUES

    /** Every example in the sheet has to parse, which is a testable claim. */
    val examples: List<String> get() = groups.flatMap { g -> g.keys.map { it.example } }
}
