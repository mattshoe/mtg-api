package org.mattshoe.mtg.core

/**
 * The people who cross the story's sections, each a short profile.
 * The articles they appear in are found by reading the narration for
 * their `names`, so the list under a profile cannot go stale.
 */
internal val storyPeople: List<StoryPerson> = listOf(
    StoryPerson(
        "urza", "Urza", listOf("Urza"),
        listOf(
            "An artificer of Terisiare who found half of a Thran powerstone in the caves of [Koilos](koilos) and fought his brother Mishra over the other half for the rest of their lives. He ended [the war](the-war) by setting off the Golgothian Sylex at [Argoth](argoth), and the blast lit his planeswalker spark.",
            "He spent the next four thousand years preparing for Phyrexia. He founded the academy on Tolaria, built Karn and the Weatherlight, bred the bloodline that produced Gerrard, and gathered [the Legacy](the-legacy). He died during the [Apocalypse](apocalypse), before the weapon he had planned for millennia was fired.",
        ),
    ),
    StoryPerson(
        "yawgmoth", "Yawgmoth", listOf("Yawgmoth"),
        listOf(
            "A Thran physician who treated a wasting plague by cutting away what was sick and replacing it, and who came to see all flesh as a flaw. Exiled from [Halcyon](halcyon), he took his followers through the portal at Koilos to an artificial plane and made it Phyrexia.",
            "As Father of Machines he ruled Phyrexia for thousands of years, built [Rath](rath-made) to invade Dominaria, and came through at last in the [Invasion](invasion). The Legacy weapon destroyed him in the [Apocalypse](apocalypse), but the faith he founded outlived him on [New Phyrexia](praetors).",
        ),
    ),
    StoryPerson(
        "karn", "Karn", listOf("Karn"),
        listOf(
            "A silver golem built by Urza with a mind and a conscience, and built to refuse violence. He served aboard the Weatherlight and turned out to be the final piece of [the Legacy](the-legacy). When the weapon fired in the [Apocalypse](apocalypse) he gained a spark.",
            "As a planeswalker he made the metal plane of [Argentum](argentum), not knowing he had brought a drop of Phyrexian oil into it. That drop became [New Phyrexia](scars). He has spent the centuries since trying to undo it, and stood against Phyrexia again when it came for every plane in the [Invasion of the Multiverse](invasion-of-the-multiverse).",
        ),
    ),
    StoryPerson(
        "sorin", "Sorin Markov", listOf("Sorin"),
        listOf(
            "A vampire planeswalker from Innistrad, born of the Markov bloodline. Thousands of years ago he joined Ugin and Nahiri to bind the Eldrazi on Zendikar, in [The Binding](the-binding).",
            "On his home plane he made the angel Avacyn to protect humans from his own kind, and built the [Helvault](helvault) to hold the worst of the demons. When Nahiri returned for revenge, he faced her and Emrakul in [Shadows](shadows) and [Eldritch Moon](eldritch-moon), and he fought again through the [Eternal Night](eternal-night).",
        ),
    ),
    StoryPerson(
        "nicol-bolas", "Nicol Bolas", listOf("Bolas"),
        listOf(
            "One of the first [Elder Dragons](elder-dragons) and the twin of Ugin. A planeswalker of vast age and vaster appetite, he spent thousands of years scheming to regain the godlike power that the Mending took from planeswalkers.",
            "He shattered and rejoined [Alara](alara) to harvest its energy, killed Ugin on [Tarkir](tarkir) in one timeline, reshaped [Amonkhet](amonkhet) into a training ground for an undead army, and brought that army to Ravnica in the [War of the Spark](bolas-war-of-the-spark), where he was defeated and imprisoned.",
        ),
    ),
    StoryPerson(
        "ugin", "Ugin", listOf("Ugin"),
        listOf(
            "The Spirit Dragon, an [Elder Dragon](elder-dragons) planeswalker from Tarkir and the twin of Nicol Bolas. With Sorin and Nahiri he bound the Eldrazi on Zendikar in [The Binding](the-binding).",
            "His fate turns on [Tarkir](tarkir), where Bolas killed him in one timeline and Sarkhan Vol saved him in another. He took Bolas prisoner after the [War of the Spark](bolas-war-of-the-spark), and Narset blames his long absence for the wild storms of [Tarkir Dragonstorm](tarkir-dragonstorm).",
        ),
    ),
    StoryPerson(
        "nahiri", "Nahiri", listOf("Nahiri"),
        listOf(
            "A kor stoneforger and planeswalker of Zendikar who helped Sorin and Ugin bind the Eldrazi, then stayed behind as the prison's warden for thousands of years, in [The Binding](the-binding).",
            "When the prison began to fail and no one answered her call, she went to Innistrad to confront Sorin, and he sealed her inside the [Helvault](helvault). Freed when it broke, she drew Emrakul to Innistrad to destroy Sorin's world, in [Emrakul on Innistrad](emrakul-on-innistrad).",
        ),
    ),
    StoryPerson(
        "elesh-norn", "Elesh Norn", listOf("Norn"),
        listOf(
            "The Grand Cenobite of the Machine Orthodoxy, the white faction of New Phyrexia. She rose among the [Praetors](praetors) after the fall of Mirrodin and made herself Mother of Machines.",
            "Her greatest work was Atraxa, a captured Mirran angel she had compleated to be the praetors' voice. Every praetor but Urabrask gave a part of their faction to her, so Atraxa is white, blue, black and green with no red. Once Norn ruled alone, Atraxa became her herald and general.",
            "She led the [Invasion of the Multiverse](invasion-of-the-multiverse), sending the Realmbreaker's roots into every plane at once, and fell when it failed.",
        ),
    ),
    StoryPerson(
        "jace", "Jace Beleren", listOf("Jace"),
        listOf(
            "A telepath and planeswalker from Vryn, raised on Ravnica's streets. He solved the [Implicit Maze](implicit-maze) and became the Living Guildpact, and he was among those who set the Eldrazi free in [Rise](rise).",
            "He was a founding member of the [Gatewatch](gatewatch), stood against Nicol Bolas in the [War of the Spark](ravnica-war-of-the-spark), and was compleated by Phyrexia before the [Invasion of the Multiverse](invasion-of-the-multiverse). Since then he has crossed the Omenpaths after the creature called Loot.",
        ),
    ),
    StoryPerson(
        "teferi", "Teferi", listOf("Teferi"),
        listOf(
            "A Zhalfirin wizard who studied at Urza's academy on Tolaria and spent years trapped in a slowed bubble of time there. He gained a spark, and in the [Time Rifts](time-rifts) gave it up to seal one of the rifts tearing Dominaria apart.",
            "He regained a spark later and stood with Dominaria against the Phyrexians in the present day, and against the [Invasion of the Multiverse](invasion-of-the-multiverse).",
        ),
    ),
    StoryPerson(
        "liliana", "Liliana Vess", listOf("Liliana"),
        listOf(
            "A necromancer of Dominaria who bargained with four demons for youth and power. On Innistrad she broke the [Helvault](helvault) to kill the demon Griselbrand, one of the four.",
            "She joined the [Gatewatch](gatewatch) for her own ends, and in the [War of the Spark](bolas-war-of-the-spark) turned her undead army against Nicol Bolas, at the cost of Gideon's life.",
        ),
    ),
    StoryPerson(
        "gideon", "Gideon Jura", listOf("Gideon"),
        listOf(
            "A soldier and planeswalker from Theros, and the heart of the [Gatewatch](gatewatch), which he helped found during the [Battle for Zendikar](battle-for-zendikar).",
            "He died on Ravnica in the [War of the Spark](ravnica-war-of-the-spark), taking on himself the death Liliana Vess would have suffered when she turned against Nicol Bolas.",
        ),
    ),
    StoryPerson(
        "chandra", "Chandra Nalaar", listOf("Chandra"),
        listOf(
            "A pyromancer from Kaladesh whose spark ignited when soldiers tried to execute her. She was among those who unknowingly freed the Eldrazi in [Rise](rise), and helped destroy two of them in the [Battle for Zendikar](battle-for-zendikar).",
            "She was a founding member of the [Gatewatch](gatewatch) and fought through the [War of the Spark](bolas-war-of-the-spark) and the [Invasion of the Multiverse](invasion-of-the-multiverse).",
        ),
    ),
)
