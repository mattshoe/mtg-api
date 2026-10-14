package org.mattshoe.mtg.core

/** One of the five colours: what it wants, and what it does well and badly at the table. */
data class WheelColour(val pip: String, val name: String, val wants: String, val goodAt: String, val badAt: String)

/** Two colours, named as a deck of them is named, and why they get along or do not. */
data class WheelPair(val pips: String, val name: String, val why: String)

/**
 * A shard of Alara or a clan of Tarkir: its land, its people, and
 * what it is like without the two colours it lacks.
 */
data class WheelLore(val pips: String, val name: String, val land: String, val people: String, val without: String)

/**
 * The colour wheel, for the Guide.
 *
 * Matt: "i want a guide section on the color wheel. i know nothing
 * about it and it'll be cool to have ... it gets its own dedicated
 * page"
 *
 * The five colours sit round a circle in WUBRG order, clockwise from
 * white at the top. A colour's neighbours are its allies and the two
 * across from it are its enemies, so both are worked out from the
 * order rather than written down twice. Both shells draw it from
 * here.
 */
object ColourWheel {

    val intro: List<String> = listOf(
        "Magic has five colours, and they sit round a circle in a fixed order: white, blue, black, red, green. That is where WUBRG comes from. Blue is U because B was taken by black.",
        "The two colours beside a colour are its allies. They want similar things, so they share a lot and play well together. The two across the wheel are its enemies. They want opposite things, and each is often best at beating the other.",
        "Every colour is good at some things and bad at others, on purpose. A deck of two or three colours is covering one colour's weak spots with another's strengths.",
    )

    val colours: List<WheelColour> = listOf(
        WheelColour(
            "W", "White",
            wants = "Peace through order. Rules, fairness, the group over the one.",
            goodAt = "Lots of small creatures that work together, gaining life, taxing and stopping attacks, exiling a problem card, and wiping the whole board.",
            badAt = "Drawing extra cards, and getting back up after its board is wiped.",
        ),
        WheelColour(
            "U", "Blue",
            wants = "Perfection through knowledge. Think it through, then act.",
            goodAt = "Drawing cards, countering spells, bouncing things back to hand, copying, and flying creatures.",
            badAt = "Getting rid of a creature for good once it has landed, and fast, cheap aggression.",
        ),
        WheelColour(
            "B", "Black",
            wants = "Power, at any cost. Ambition, and doing whatever it takes.",
            goodAt = "Killing creatures, bringing them back from the graveyard, searching the library for a card, and paying life to get ahead.",
            badAt = "Dealing with artifacts and enchantments.",
        ),
        WheelColour(
            "R", "Red",
            wants = "Freedom, on impulse. Feel it and do it now.",
            goodAt = "Damage to anything, haste and fast attacks, smashing artifacts, and one-turn bursts of extra cards or mana.",
            badAt = "Enchantments, and the long game. It runs out of steam.",
        ),
        WheelColour(
            "G", "Green",
            wants = "Growth, and the natural order. Things as they are meant to be.",
            goodAt = "Extra mana, the biggest creatures, pumping them, and destroying artifacts and enchantments.",
            badAt = "Fliers, and removing a creature except by fighting it.",
        ),
    )

    /** The five neighbouring pairs, round the wheel from white. */
    val allied: List<WheelPair> = listOf(
        WheelPair("WU", "Azorius", "Both like a plan and rules to follow. Law and order."),
        WheelPair("UB", "Dimir", "Both are patient and play the long game. Secrets and schemes."),
        WheelPair("BR", "Rakdos", "Both put what they want first, and rules can go hang."),
        WheelPair("RG", "Gruul", "Both trust gut and instinct over thinking. Big and angry."),
        WheelPair("GW", "Selesnya", "Both care for the whole group, and for tradition."),
    )

    /** The five pairs across the wheel, from white. */
    val enemy: List<WheelPair> = listOf(
        WheelPair("WB", "Orzhov", "White serves the group, black serves itself. Together, a church that sells forgiveness."),
        WheelPair("UR", "Izzet", "Blue thinks first, red acts first. Together, mad science."),
        WheelPair("BG", "Golgari", "Black cheats death, green accepts it as part of life. Together, rot and regrowth."),
        WheelPair("RW", "Boros", "Red wants freedom, white wants rules. Together, an army that charges in."),
        WheelPair("GU", "Simic", "Green takes the world as it is, blue wants to improve it. Together, engineered nature."),
    )

    const val SHARDS = "The shards of Alara"
    const val SHARDS_ABOUT = "Alara was one world, split into five. Each shard kept a colour and its two allies, and lost its two enemies."
    const val CLANS = "The clans of Tarkir"
    const val CLANS_ABOUT = "Each clan is a colour and its two enemies, so each one lives without a pair of allied colours."

    /**
     * The five shards of Alara, one for each colour in WUBRG order.
     *
     * Matt: "we should add lore inserts for the shards of alara and the
     * tribes of tarkir. information that talks about the land scapes
     * and culture with the missing colors of magic."
     *
     * Alara was one world, split into five. Each shard kept a colour and
     * its two allies and lost its two enemies, so each is what a world
     * looks like with two colours gone.
     */
    val shards: List<WheelLore> = listOf(
        WheelLore(
            "GWU", "Bant",
            land = "Rolling green fields and farmland under a bright sky, dotted with castles and walled cities.",
            people = "Knights, monks and angels in a strict order of castes. Honour is everything, and a deed earns you a sigil that shows your worth.",
            without = "No black and no red, so no greed, no rage and hardly any death magic. Even its wars were fought by rules of honour, and it had all but forgotten what evil looked like.",
        ),
        WheelLore(
            "WUB", "Esper",
            land = "Misty plains and gleaming cities of glass and metal, where the land itself is shaped by mages.",
            people = "Wizards and scholars who graft etherium, a magical blue metal, into their own bodies to perfect them. Sphinxes and the Mechanists lead the pursuit of knowledge.",
            without = "No red and no green, so nothing wild or impulsive is allowed. Nature is a flaw to be fixed and feeling is kept under control.",
        ),
        WheelLore(
            "UBR", "Grixis",
            land = "A dark, dying wasteland of ash and ruins. Living things are rare and the life energy they carry is precious.",
            people = "Necromancers, demons and liches rule over hordes of the dead. The few living people hide, and are hunted for the life inside them.",
            without = "No green and no white, so no growth, no healing and no one looking out for anyone else. Life itself is running out.",
        ),
        WheelLore(
            "BRG", "Jund",
            land = "Volcanoes, steaming jungles and tar pits, with dragons at the top of the food chain.",
            people = "Human hunting clans, viashino and goblins, all fighting to survive another day. Strength is the only law.",
            without = "No white and no blue, so no laws, no plans and no thought for tomorrow. Eat or be eaten.",
        ),
        WheelLore(
            "RGW", "Naya",
            land = "Endless jungle and mountains, where giant beasts taller than the trees roam free.",
            people = "Elves who worship the giant beasts as gods, leonin nacatl, and humans in jungle cities. They live close to nature and its old ways.",
            without = "No blue and no black, so no hunger for knowledge or power. Little technology and few schemes, just a simple life in the wild.",
        ),
    )

    /**
     * The five clans of Tarkir, in the order the cards list them.
     *
     * Each clan is a colour and its two enemies, and lacks that colour's
     * two allies: Abzan is black with white and green, and has no blue
     * or red. These are the clans under the khans, before the dragons
     * took over, which is where the names come from.
     */
    val clans: List<WheelLore> = listOf(
        WheelLore(
            "WBG", "Abzan",
            land = "Harsh deserts and salt flats, where the clan builds stone fortresses to hold out against the wastes.",
            people = "Big families bound by the Kin-Tree, honouring the spirits of their ancestors. Endurance is what they prize: they hold the line and do not break.",
            without = "No blue and no red, so little interest in new ideas or sudden moves. Tradition and patience win out, and they stand their ground.",
        ),
        WheelLore(
            "URW", "Jeskai",
            land = "High mountain monasteries among peaks and waterfalls.",
            people = "Monks and martial artists who train mind and body together, seeking enlightenment. Cunning is what they prize: outthink the enemy.",
            without = "No black and no green, so no selfish greed and no trust in raw instinct. Discipline over appetite.",
        ),
        WheelLore(
            "BGU", "Sultai",
            land = "Steaming jungle swamps and rivers, full of grand palaces and sunken temples.",
            people = "Naga and their rakshasa masters, living in luxury on the work of zombie servants. Ruthlessness is what they prize: whatever works.",
            without = "No white and no red, so no mercy, no fairness and no passion. Cold, rich and decadent.",
        ),
        WheelLore(
            "RWB", "Mardu",
            land = "Wide open grassy steppes, crossed by roaming war camps.",
            people = "A nomad horde of humans, orcs and goblins who live to raid. Speed is what they prize: strike first, strike fast, and win glory in battle.",
            without = "No green and no blue, so no patience, no plans and no place to settle. They are always on the move.",
        ),
        WheelLore(
            "GUR", "Temur",
            land = "Frozen tundra and snowy mountains, wild and cold.",
            people = "Bear riders, ainok and shamans who whisper to the land. Savagery is what they prize: the strength of the wild.",
            without = "No white and no black, so no rulers and no cruelty or ambition. Loose families living free in the wild.",
        ),
    )

    /** The colours a combination does not have, in WUBRG order. */
    fun missing(pips: String): List<WheelColour> = colours.filter { it.pip !in pips.uppercase() }

    /** "No Black or Red": how a page says the colours a shard or clan lacks. */
    fun lacking(pips: String): String = "No " + missing(pips).joinToString(" or ") { it.name }

    private fun at(i: Int): WheelColour = colours[(i + colours.size) % colours.size]

    private fun index(pip: String): Int = colours.indexOfFirst { it.pip == pip.uppercase() }

    /** The two colours beside this one. */
    fun allies(pip: String): List<WheelColour> = index(pip).let { if (it < 0) emptyList() else listOf(at(it - 1), at(it + 1)) }

    /** The two colours across the wheel from this one. */
    fun enemies(pip: String): List<WheelColour> = index(pip).let { if (it < 0) emptyList() else listOf(at(it + 2), at(it - 2)) }

    /** "Blue and Green": how a page says a colour's allies. */
    fun alliesOf(pip: String): String = allies(pip).joinToString(" and ") { it.name }

    /** "Black and Red": how a page says a colour's enemies. */
    fun enemiesOf(pip: String): String = enemies(pip).joinToString(" and ") { it.name }

    /**
     * Where a colour sits on the wheel, on a circle of radius 1 round
     * the middle: white at the top, then clockwise, with y running down
     * as it does on a screen. Both shells place the symbols from this.
     */
    fun point(pip: String): Pair<Double, Double> {
        val a = 2 * kotlin.math.PI * index(pip) / colours.size
        return kotlin.math.sin(a) to -kotlin.math.cos(a)
    }
}
