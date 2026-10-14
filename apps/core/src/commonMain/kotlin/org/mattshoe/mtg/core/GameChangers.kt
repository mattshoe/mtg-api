package org.mattshoe.mtg.core

/**
 * Commander's Game Changers: the cards that lift a deck out of the
 * lower brackets on their own.
 *
 * Matt: "add a guide section for game changers. it should be a list
 * of cards just like a deck page. tapping any of them should pop a
 * carousel like any other cards".
 *
 * Written down here rather than read from the collection, which only
 * holds the ones somebody owns. The list is Wizards', as Scryfall's
 * `is:gamechanger` gave it on 2026-10-14, one printing each so the row
 * and the carousel have art. When Wizards change it, this changes.
 *
 * As [DeckCard]s, so the page is grouped and ordered by exactly the
 * rules a deck list is, and both shells draw it from here.
 */
object GameChangers {

    /** The line over the list, the same on both shells. */
    const val ABOUT = "Wizards' list of the cards that warp a Commander game on their own. " +
        "Brackets 1 and 2 allow none, bracket 3 allows up to three, and 4 and 5 any number. Tap a card to see it."

    val cards: List<DeckCard> = listOf(
        gc("Ad Nauseam", "a9f2c53e-ff58-4aa8-89a6-4f45628cc571", "Instant", "B", "{3}{B}{B}", "rare", 1362L, "2xm", "76"),
        gc("Ancient Tomb", "bd3d4b4b-cf31-4f89-8140-9650edb03c7b", "Land", "", "", "rare", 69L, "uma", "236"),
        gc("Aura Shards", "a6222cc6-996e-4b73-af87-e837bf1eb921", "Enchantment", "GW", "{1}{G}{W}", "uncommon", 1060L, "cmd", "182"),
        gc("Biorhythm", "17d1a10f-ce21-4914-9984-c7c559161230", "Sorcery", "G", "{6}{G}{G}", "rare", 9451L, "9ed", "231"),
        gc("Bolas's Citadel", "d2124603-d20e-40eb-97f0-a66323397ac2", "Legendary Artifact", "B", "{3}{B}{B}{B}", "rare", 276L, "war", "79"),
        gc("Braids, Cabal Minion", "27691efa-052d-4afe-b9ef-159858ca660f", "Legendary Creature — Human Minion", "B", "{2}{B}{B}", "rare", 4734L, "mh2", "273"),
        gc("Chrome Mox", "f340cbf7-5bbe-45b9-a4bf-d1caa500ff93", "Artifact", "", "{0}", "mythic", 156L, "2xm", "240"),
        gc("Coalition Victory", "446824f7-dc42-42da-8bd5-6c37f3358d65", "Sorcery", "BGRUW", "{3}{W}{U}{B}{R}{G}", "special", 9597L, "tsb", "91"),
        gc("Consecrated Sphinx", "c9bd60fc-2777-44bc-b314-a792e8e48360", "Creature — Sphinx", "U", "{4}{U}{U}", "mythic", 655L, "2x2", "43"),
        gc("Crop Rotation", "523414cb-f8db-407a-808a-01454e03d8b9", "Instant", "G", "{G}", "uncommon", 217L, "dmr", "154"),
        gc("Cyclonic Rift", "dfb7c4b9-f2f4-4d4e-baf2-86551c8150fe", "Instant", "U", "{1}{U}", "mythic", 58L, "rvr", "40"),
        gc("Demonic Tutor", "a24b4cb6-cebb-428b-8654-74347a6a8d63", "Sorcery", "B", "{1}{B}", "mythic", 64L, "cmm", "150"),
        gc("Drannith Magistrate", "98b0a4a8-9319-451b-9b79-b0bca7a41e91", "Creature — Human Wizard", "W", "{1}{W}", "rare", 759L, "iko", "11"),
        gc("Enlightened Tutor", "1c9675fb-1a89-420f-aea8-50e0642f549c", "Instant", "W", "{W}", "rare", 123L, "dmr", "6"),
        gc("Farewell", "114d2180-093b-4838-97ad-badbc8ee50b0", "Sorcery", "W", "{4}{W}{W}", "rare", 177L, "mkc", "64"),
        gc("Field of the Dead", "470ca3f4-29aa-4c4c-8ff2-8cdd70c69943", "Land", "", "", "rare", 490L, "m20", "247"),
        gc("Fierce Guardianship", "f7f3dd95-bd14-4e0f-a388-444f9cf1b0dc", "Instant", "U", "{2}{U}", "rare", 82L, "cmm", "94"),
        gc("Force of Will", "89f612d6-7c59-4a7b-a87d-45f789e88ba5", "Instant", "U", "{3}{U}{U}", "mythic", 201L, "dmr", "50"),
        gc("Gaea's Cradle", "25b0b816-0583-44aa-9dc5-f3ff48993a51", "Legendary Land", "G", "", "rare", 447L, "usg", "321"),
        gc("Gamble", "8e37fae5-ddd0-4e16-8581-71579f89d9c5", "Sorcery", "R", "{R}", "rare", 241L, "dmr", "121"),
        gc("Gifts Ungiven", "cc950b6c-0346-4939-b36b-9f10a75f7a32", "Instant", "U", "{3}{U}", "rare", 1542L, "2x2", "51"),
        gc("Glacial Chasm", "0c008129-daba-46bc-829c-d2c0c13ecdd3", "Land", "", "", "rare", 2241L, "me2", "229"),
        gc("Grand Arbiter Augustin IV", "f43748ea-1d65-4ee8-9c66-221412a284c0", "Legendary Creature — Human Advisor", "UW", "{2}{W}{U}", "rare", 1802L, "2x2", "221"),
        gc("Grim Monolith", "9ddc9fe1-17c8-4e1d-aeb8-c4214e881280", "Artifact", "", "{2}", "rare", 676L, "ulg", "126"),
        gc("Humility", "55ad6a45-a840-45ba-89ad-066e20e983f3", "Enchantment", "W", "{2}{W}{W}", "mythic", 11708L, "tpr", "16"),
        gc("Imperial Seal", "e71a6bd3-7478-4c3a-8ae0-98352ce28492", "Sorcery", "B", "{B}", "mythic", 540L, "2x2", "79"),
        gc("Intuition", "b13e73a5-067d-4dbd-9c98-34a0db6140de", "Instant", "U", "{2}{U}", "rare", 2029L, "tpr", "54"),
        gc("Jeska's Will", "99e0c371-1024-4432-9fd9-3bc29c8d38e4", "Sorcery", "R", "{2}{R}", "rare", 107L, "mkc", "156"),
        gc("Lion's Eye Diamond", "758f95f8-bcb0-43ae-b474-56ebd855951e", "Artifact", "", "{0}", "mythic", 741L, "vma", "271"),
        gc("Mana Vault", "c1a31d52-a407-4ded-bfca-cc812f11afa0", "Artifact", "", "{1}", "mythic", 152L, "2x2", "308"),
        gc("Mishra's Workshop", "aac0c8df-f01d-4178-8d66-ee603f814d24", "Land", "", "", "mythic", 3694L, "vma", "305"),
        gc("Mox Diamond", "bf9fecfd-d122-422f-bd0a-5bf69b434dfe", "Artifact", "", "{0}", "mythic", 248L, "tpr", "228"),
        gc("Mystical Tutor", "36fa9a0b-b0c9-43ea-ba11-99d7982f974e", "Instant", "U", "{U}", "rare", 162L, "dmr", "60"),
        gc("Narset, Parter of Veils", "4e6e5c63-b6e5-4756-bf23-6c6f8669442d", "Legendary Planeswalker — Narset", "U", "{1}{U}{U}", "uncommon", 977L, "cmm", "853"),
        gc("Natural Order", "bfe3329c-7faa-4925-b9d2-075a1ab27e80", "Sorcery", "G", "{2}{G}{G}", "mythic", 1534L, "ema", "177"),
        gc("Necropotence", "c89c6895-b0f8-444a-9c89-c6b4fd027b3e", "Enchantment", "B", "{B}{B}{B}", "mythic", 522L, "ima", "98"),
        gc("Notion Thief", "f675f509-4343-4568-96dd-265626cb6c2b", "Creature — Human Rogue", "BU", "{2}{U}{B}", "rare", 1210L, "znc", "96"),
        gc("Opposition Agent", "086f97e9-8b62-44f3-b467-149c2ac5ca78", "Creature — Human Rogue", "B", "{2}{B}", "rare", 592L, "cmr", "141"),
        gc("Orcish Bowmasters", "7c024bae-5631-4e20-ac69-df392ac9e109", "Creature — Orc Archer", "B", "{1}{B}", "rare", 260L, "ltr", "103"),
        gc("Panoptic Mirror", "50e945b0-e919-41bb-9bc5-f71ad531e8f1", "Artifact", "", "{5}", "rare", 9916L, "dst", "136"),
        gc("Rhystic Study", "9f37c5b6-a59c-45cd-9a99-e9357fe9ea1b", "Enchantment", "U", "{2}{U}", "rare", 46L, "j22", "114"),
        gc("Seedborn Muse", "76f58441-5674-4f1e-af9b-94e14ab7e619", "Creature — Spirit", "G", "{3}{G}{G}", "rare", 286L, "tdc", "268"),
        gc("Serra's Sanctum", "f7a18130-dbaa-4657-a885-3a96a985935a", "Legendary Land", "W", "", "rare", 3098L, "usg", "325"),
        gc("Smothering Tithe", "861b5889-0183-4bee-afeb-a4b2aa700a8e", "Enchantment", "W", "{3}{W}", "mythic", 66L, "cmm", "57"),
        gc("Survival of the Fittest", "4ef0d7f9-ddb9-4e83-a9bf-09bec22fc80d", "Enchantment", "G", "{1}{G}", "mythic", 2365L, "tpr", "199"),
        gc("Teferi's Protection", "483fa1cb-1e35-44f2-a143-98c0f107f5ca", "Instant", "W", "{2}{W}", "rare", 109L, "2x2", "32"),
        gc("Tergrid, God of Fright // Tergrid's Lantern", "14dc88ee-bba9-4625-af0d-89f3762a0ead", "Legendary Creature — God // Legendary Artifact", "B", "{3}{B}{B}", "rare", 1587L, "khm", "112"),
        gc("Thassa's Oracle", "726e8b29-13e9-4138-b6a9-d2a0d8188d1c", "Creature — Merfolk Wizard", "U", "{U}{U}", "rare", 452L, "thb", "73"),
        gc("The One Ring", "d5806e68-1054-458e-866d-1f2470f682b2", "Legendary Artifact", "", "{4}", "mythic", 96L, "ltr", "246"),
        gc("The Tabernacle at Pendrell Vale", "cd3f7f4e-cb25-4121-96a0-a4dc530420b9", "Legendary Land", "", "", "rare", 8849L, "me3", "212"),
        gc("Underworld Breach", "0e51d796-7279-4c06-87f0-37adbdaa41df", "Enchantment", "R", "{1}{R}", "rare", 389L, "thb", "161"),
        gc("Vampiric Tutor", "34a0203f-9cce-43a4-9cb7-8ce6647895cd", "Instant", "B", "{B}", "mythic", 115L, "dmr", "108"),
        gc("Worldly Tutor", "f39aa2e9-e294-4ce6-bf5e-e1f579101a7a", "Instant", "G", "{G}", "rare", 170L, "dmr", "185"),
    )

    /** By type, in a deck list's order, alphabetical inside each. See `DecksState.byType`. */
    val byType: List<Pair<DeckGroup, List<DeckCard>>> = cards.groupBy { it.group }
        .toList()
        .sortedBy { (group, _) -> group.ordinal }
        .map { (group, list) -> group to list.sortedBy { it.name.lowercase() } }

    /** Every card in the order the page draws it, so the carousel's next is the next row down. */
    val pageOrder: List<DeckCard> = byType.flatMap { (_, cards) -> cards }

    private fun gc(
        name: String,
        scryfallId: String,
        typeLine: String,
        identity: String,
        manaCost: String,
        rarity: String,
        edhrecRank: Long,
        set: String,
        number: String,
    ) = DeckCard(
        name = name,
        qty = 1,
        role = null,
        owned = 0,
        nameNorm = name.lowercase(),
        typeLine = typeLine,
        scryfallId = scryfallId,
        manaCost = manaCost.ifEmpty { null },
        // A colourless identity is NULL in the collection, not "".
        colorIdentity = identity.ifEmpty { null },
        rarity = rarity,
        setCode = set,
        collectorNumber = number,
        edhrecRank = edhrecRank,
    )
}

/**
 * A row of the Game Changers page was tapped: the carousel over the
 * whole list, at that card.
 */
fun AppState.peekGameChanger(card: DeckCard): AppState =
    peekAt(GameChangers.pageOrder.indexOfFirst { it.nameNorm == card.nameNorm }, PeekOf.GAME_CHANGERS)
