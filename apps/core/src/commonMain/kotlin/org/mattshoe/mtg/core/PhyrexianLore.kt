package org.mattshoe.mtg.core

/** A line from the cards, and who said it where. */
data class LoreQuote(val words: String, val source: String)

/** One chapter of the story: a short name for its tab, a title, and the telling. */
data class LoreEra(val slug: String, val tab: String, val title: String, val story: List<String>, val quote: LoreQuote? = null)

/**
 * The story of Phyrexia, for the Guide, one era to a tab.
 *
 * Matt: "i want a new guide section on phyrexian lore. Lore the whole
 * thing. from thran to modern times and everything in between ... i
 * want this particular guide to be tabbed ... this should read as
 * literature. I'm not too concerned with dates or anything ... it
 * should be written as an enthralling story, not dry textbook. feel
 * free to quote actual mtg literature if you like"
 *
 * Told as a story, so it leaves out dates and most of the names a
 * wiki would list. The quotes are flavour text from the cards named
 * beside them. Both shells draw it from here, and the tab on screen is
 * in the address, so a bookmark and back both work.
 */
object PhyrexianLore {

    val eras: List<LoreEra> = listOf(
        LoreEra(
            "thran", "The Thran", "A healer in a dying empire",
            listOf(
                "Long before Urza, before the ice, before anyone alive could say the word Phyrexia without lowering their voice, there was the Thran. They were an empire of glass and light on Dominaria, and they were brilliant. Their artificer Glacian learned to bottle power itself inside crystal, and the powerstones he made ran their cities, their ships and their war machines. It was an age that believed every problem would yield to a clever enough mind.",
                "Then came the sickness. The Thran called it phthisis, a wasting rot that ate the body from the inside, and their doctors could do nothing about it. Out of the provinces came a man who could. Yawgmoth was a healer with cold hands and a colder eye, and where others saw patients he saw machines that had broken down. He cut the disease out. He replaced what he cut. The afflicted loved him for it, and they followed him in their thousands.",
                "The people at the top of the empire did not love him. They saw a physician who treated flesh as a draft to be corrected, and a following that called him something closer to a god. Rebbec, Glacian's wife and a great artificer in her own right, came to understand exactly what he was. Yawgmoth was cast out, and he took his faithful with him.",
                "Under the mountains, in the caves of Koilos, Yawgmoth found a door. Beyond it lay a world that someone else had built, an artificial plane of nested spheres, metal on metal, oil running in its veins. It was empty and it was waiting. Yawgmoth walked into it and made it his. In time its maker was forgotten and the plane was simply Phyrexia, and Yawgmoth was its father.",
                "There he perfected his art. The weak flesh of his followers was stripped away and replaced with steel, piece by piece, and the replacement was not a punishment. It was a gift. Phyrexians would come to call it compleation. They no longer died. They no longer doubted. They simply served.",
                "Yawgmoth came back for the Thran, as everyone in the story always knew he would. The war that followed tore the empire apart. In its last hour Rebbec sealed the door at Koilos with a powerstone, trapping the Father of Machines on the far side. The Thran did not survive their victory. Their cities fell silent and their crystal dimmed, and the world forgot them. Phyrexia did not forget anything. Behind the sealed door, in the dark, it waited for someone to open it again.",
            ),
        ),
        LoreEra(
            "brothers", "The Brothers' War", "Two brothers and a stone that broke in half",
            listOf(
                "Thousands of years later, two boys came to dig in the ruins. Urza was the elder, careful and proud, a mind that saw the world as a puzzle he was owed the answer to. Mishra was the younger, quick and warm and hungry to be more than his brother's shadow. They were apprentices to the scholar Tocasia, and in the caves of Koilos, near a door neither of them understood, they found a powerstone of terrible strength.",
                "It split in their hands. Urza held one half and Mishra the other, and each heard a different song from it. Urza's half would be called the Mightstone. Mishra's was the Weakstone. They fought over the halves that same night, and Tocasia died trying to stop them. Neither brother ever forgave the other, and neither ever stopped blaming himself.",
                "They went their separate ways and became powers. Urza built in the west, an artificer prince with an army of machines. Mishra rose among the desert peoples to the east and built his own. What started as a quarrel between brothers became a war that ran across a continent and lasted for decades. Forests went into the furnaces to feed it. Whole nations were melted down for parts.",
                "And somewhere in all of it, the door at Koilos opened a crack. A Phyrexian demon named Gix came through, and he found Mishra. He offered what Phyrexia always offers, strength without limits and the promise that weakness could be cut away. Mishra took it. By the end of the war his engines were not entirely his own, and neither was he.",
                "The war ended on the island of Argoth, an ancient forest Urza had been stripping bare. Out of options and out of mercy, he set off a relic of the Thran called the Golgothian Sylex. The blast wiped out Argoth, both armies and Mishra with them. It knocked the world's weather sideways and buried Dominaria under centuries of ice.",
                "Urza did not die. In the heart of that blast something woke inside him, a planeswalker's spark, and the two halves of the stone fused into his eyes. With those eyes he looked one last time at what was left of his brother and saw the Phyrexian metal beneath the skin. He finally knew who his real enemy had been all along. He would spend the next four thousand years making them pay.",
            ),
        ),
        LoreEra(
            "urza", "Urza's Long War", "The man who planned for millennia",
            listOf(
                "Urza walked between worlds now, and his first act as a planeswalker was a reckless one. He went to Phyrexia alone. He forced his way through its spheres, past the furnaces and the vat-grown horrors, all the way down toward the Father of Machines himself. He barely escaped with his mind in one piece. He had learned two things. Phyrexia was far larger and far crueller than he had imagined, and one man, however powerful, could not beat it.",
                "So Urza stopped being a man in a hurry and became a man with a plan. He spent centuries wandering, half mad, gathering knowledge. He found a Phyrexian named Xantcha, built to infiltrate and betray, who chose instead to stand beside him. He found rest in the realm of the angel Serra. And on the island of Tolaria he founded an academy, gathered the brightest young minds of Dominaria, and set them to work against an enemy most of them did not believe was real.",
                "His experiments with time went badly wrong. One of them tore Tolaria open, leaving pockets where years went by in a heartbeat and heartbeats lasted for years. Urza called it a setback and kept going. He built a silver golem with a gentle mind and named him Karn. He built a flying ship called the Weatherlight. He scattered powerful artifacts across the world, pieces of something he called the Legacy, and told almost no one what they were for.",
                "His strangest project was people. Urza bred bloodlines across the generations, guiding marriages and births with a cold patience that would have horrified the families involved if they had known. He was trying to grow a hero, someone born with the strength to wield the Legacy when the moment came. After generations of it, he got one. His name was Gerrard Capashen.",
                "Meanwhile the Phyrexians did not sit idle. Their sleeper agents were everywhere, wearing human faces in human courts, waiting for a signal. Urza knew this and could not root them all out. He could only prepare, and hope that his planning outran theirs. It was a long game, played over thousands of years, and both sides knew it would end in one great invasion.",
            ),
        ),
        LoreEra(
            "rath", "Rath", "A ship, a crew and a stronghold",
            listOf(
                "Gerrard Capashen did not want to be a hero. He had been raised alongside a boy named Vuel, and the two had become brothers and then enemies, and Gerrard had walked away from all of it, from destiny and from the Weatherlight, to live the life of an ordinary man. Then Sisay, the captain of the Weatherlight and a woman Gerrard owed more than he could repay, was taken. He came back for her.",
                "She had been taken to Rath. Rath was not a natural world. Phyrexia had built it as a staging ground, a plane of flowstone that could shift and reshape at the will of its ruler, and its ruler was the Evincar Volrath. Gerrard soon learned who Volrath really was. His lost brother Vuel had gone looking for power and Phyrexia had found him first.",
                "The Weatherlight crew was a strange family. There was Karn, the gentle silver golem who would not fight. Hanna the navigator, whom Gerrard loved. Mirri the cat warrior, his oldest friend. Tahngarth the minotaur, Orim the healer, Ertai the arrogant young wizard, and Squee the goblin, who could not die no matter how hard the universe tried. And there was Crovax, a nobleman haunted by a curse, who followed them into the dark looking for redemption.",
                "They stormed Volrath's Stronghold and they rescued Sisay. They also paid for it. Ertai was left behind and taken by the Phyrexians. Crovax's curse finally claimed him, and in his hunger he killed Mirri. Gerrard held her as she died. When the Weatherlight escaped, Rath's new ruler was Crovax, crowned Evincar by Phyrexia itself, and it would not be the last time the crew faced him.",
                "What they had seen in the Stronghold was worse than any of their losses. Rath was a weapon. It was meant to be laid over Dominaria like a blanket of flowstone, bringing the armies of Phyrexia with it in a single moment. The crew fled across worlds, was stranded in the merchant city of Mercadia, and fought their way home again. They returned to a Dominaria that had run out of time. The sky above it was already tearing.",
            ),
        ),
        LoreEra(
            "invasion", "The Invasion", "Phyrexia comes to Dominaria",
            listOf(
                "It began with the overlay. Pieces of Rath dropped out of the sky onto Dominaria, whole regions swallowed by black flowstone, and out of each one poured the armies of Phyrexia. Plague engines. Stitched horrors. Priests whose faces had been replaced by metal. There was no warning that mattered. Every nation of Dominaria was at war by nightfall.",
                "Urza had spent four thousand years for this. His Metathran soldiers, bred in vats for one war, marched out to meet the enemy. Nine planeswalkers climbed into nine titanic war machines he had built, and walked into battle as giants. The Weatherlight and its crew flew everywhere at once, gathering the scattered pieces of the Legacy. Dominaria's elves, its merfolk, its kingdoms and its cities fought shoulder to shoulder for the first time in history, because the only other choice was extinction.",
                "And still it was not enough. One of Urza's planeswalkers betrayed the others. Cities fell. Then the Father of Machines himself came through, not as a man but as a black wind that blew across the world, and everything it touched simply died. Whole armies were gone in an afternoon. Gerrard watched the people he loved fall one by one, and Hanna among them.",
                "The end came in the ruins, at the very edge of hope. Urza did not live to see it. The pieces of the Legacy were assembled around Karn, the gentle golem who had been the final piece all along, and Gerrard stood at its heart. The weapon woke. It was the last thing the Thran ever made, the work of four thousand years, and it was pointed at one target. Yawgmoth, Father of Machines, was burned out of existence. Gerrard did not survive it either.",
                "In the quiet afterward, something woke in Karn. The weapon had given him a spark, and the silver golem who would not fight became a planeswalker. He left Dominaria grieving, carrying the memories of everyone who had died. He did not know that he was carrying something else too. Somewhere deep in his silver body was a single drop of black oil.",
            ),
            LoreQuote("An audience of one with the malice of thousands.", "Phyrexian Arena"),
        ),
        LoreEra(
            "mirrodin", "Mirrodin", "A world of metal, and the drop of oil",
            listOf(
                "Karn built a world. He wanted a plane of his own, a peaceful place of metal where he could rest, and he made Argentum, a perfect silver sphere. He set a guardian named Memnarch to tend it and went off to walk the multiverse. It was a kind thing to make, and it was made by a man who did not know he was infected.",
                "The oil had come with him. Phyrexian oil is not just a substance, it is a seed, and given time and quiet it grows. It crept into Memnarch's mind, and the guardian went mad with longing. Memnarch began stealing creatures from other worlds and bringing them to Argentum, and he made the world over into something wild and crowded, with five suns in its sky and a hollow heart. The people who lived there called it Mirrodin.",
                "The Mirrans had no idea where they came from or what lived beneath their feet. There were the leonin and the vedalken, the goblins of the furnaces and the elves of the tangle. They fought each other and survived Memnarch's madness and built their lives. And all the while, deep in the core, the oil was building Phyrexia again.",
                "When the Phyrexians came up out of the ground it was already too late. They called the Mirrans to compleation and turned their own neighbours against them. A brave few held out. The planeswalkers Elspeth, Koth and Venser came to help, and Karn himself came back to the world he had made, half corrupted by the oil inside him. Venser gave his own spark, and his life, to burn the sickness out of his friend.",
                "It was not enough. The Mirran resistance was broken and driven into hiding, and the five suns went on shining over a plane that no longer belonged to them. Karn fled home to Dominaria, carrying the guilt. Mirrodin was gone. In its place stood New Phyrexia, born from a single drop of oil that a hero had carried without knowing.",
            ),
            LoreQuote("A symbol of what Mirrodin once was and hope for what it will be again.", "Mirran Crusader"),
        ),
        LoreEra(
            "praetors", "New Phyrexia", "Five praetors and a mother of machines",
            listOf(
                "The new Phyrexia had no Father. Yawgmoth was gone and nobody had come to take his place, so five rulers rose up to share it, the praetors, one for each colour. Elesh Norn of the Machine Orthodoxy, a pale and towering zealot who preached perfect unity. Jin-Gitaxias, who wanted to understand everything by taking it apart. Sheoldred, who built her power in the depths with the patience of a spider. Urabrask, who wanted his people free to burn as they chose. And Vorinclex, who hunted whatever he was told was weak.",
                "They hated each other. For a while that was all that kept the multiverse safe, five monsters on one world circling one another. Then Elesh Norn won. She took the others under her rule, one way or another, and called herself the Mother of Machines. Under her, Phyrexia had one voice again, and one creed. All will be one.",
                "She was not content with one world. Her sleepers slipped back into Dominaria, and Sheoldred came behind them with an army, and for a terrible season Dominaria fought the same war it thought it had won. Its heroes held, barely. But they had been looking in the wrong direction. Norn's real prize was not a world. It was the planeswalkers.",
                "A planeswalker who has been compleated keeps their spark. That was the secret. One by one she took them, through trickery and through war, the leonin Ajani among the first, until her army included some of the most powerful minds in the multiverse. A team of heroes went to New Phyrexia to stop her before she could act. Many of them did not come home as themselves.",
                "And all the while her greatest weapon grew. It was a tree. Phyrexia called it Realmbreaker, and its roots were driven through the space between worlds, and every branch was a road. When it was ready, every one of those roads would lead somewhere Phyrexia wanted to go.",
            ),
            LoreQuote(
                "The Gitaxians whisper among themselves of other worlds. If they exist, we must bring Phyrexia's magnificence to them.",
                "Elesh Norn, Grand Cenobite",
            ),
        ),
        LoreEra(
            "machine", "March of the Machine", "Every world at once",
            listOf(
                "The branches came down on every world in the same moment. On Ravnica, on Kaldheim, on Innistrad and Zendikar and a dozen others, the sky split open and Phyrexia stepped out. There was no front line because the front line was everywhere. The planes that had always been separate, that had never needed each other, found themselves fighting the same war on the same day.",
                "The planeswalkers who were left gathered whoever they could. Some went to defend their own worlds. A few went straight for the heart of it, to New Phyrexia itself, to do the one thing that could end it. Among them was Elspeth Tirel, who had fled Mirrodin once as it fell and had sworn never to run again.",
                "It cost everything. Heroes died on worlds that were not their own. Old friends had to fight the compleated shells of the people they had loved. Elspeth was given a light out of another world's heaven, a glow that made her more than mortal, and she carried it into the Mother of Machines' own sanctum. There, at last, Elesh Norn fell.",
                "The tree burned from within. When Realmbreaker came apart it unmade the bond between Phyrexia and the worlds it had stabbed into, and something else broke with it. Most planeswalkers felt their sparks gutter and go out. The compleated ones woke up as themselves, wounded and grieving and free. The invaders on every world went still.",
                "The cost was a multiverse changed for good. Roads now run between the planes where only a planeswalker could once walk, and ordinary people are learning to travel them. Phyrexia is broken. It is not gone. Somewhere in the dark, oil still drips, and in the quiet places of every world there are things that used to serve it. Perhaps they have forgotten. Perhaps they are only waiting, as Phyrexia has always waited, for someone to open the door again.",
            ),
            LoreQuote("All will be one.", "The creed of New Phyrexia"),
        ),
    )

    fun era(slug: String): LoreEra? = eras.firstOrNull { it.slug == slug }

    /** The era after this one, for a Read on button at the foot of the page. Null at the end. */
    fun next(era: LoreEra): LoreEra? = eras.getOrNull(eras.indexOf(era) + 1)
}

/** The era on screen, when the Phyrexia tile is open: the one in the address, or the first. */
val AppState.loreEra: LoreEra?
    get() = if (guideTile?.slug == Guide.LORE) {
        PhyrexianLore.era(route.rest.substringAfter('/', "")) ?: PhyrexianLore.eras.first()
    } else null

/** An era, at its own address, `#/guide/phyrexia/<slug>`. */
fun AppState.openEra(era: LoreEra): AppState = navigate(Route(View.GUIDE, "${Guide.LORE}/${era.slug}"))
