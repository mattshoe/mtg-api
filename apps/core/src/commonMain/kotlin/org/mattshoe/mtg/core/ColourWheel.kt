package org.mattshoe.mtg.core

/** One of the five colours: what it wants, and what it does well and badly at the table. */
data class WheelColour(val pip: String, val name: String, val wants: String, val goodAt: String, val badAt: String)

/** Two colours, named as a deck of them is named, and why they get along or do not. */
data class WheelPair(val pips: String, val name: String, val why: String)

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
