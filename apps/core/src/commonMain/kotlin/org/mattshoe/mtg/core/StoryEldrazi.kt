package org.mattshoe.mtg.core

internal val storyEldrazi = StorySection(
    "eldrazi", "Eldrazi and Zendikar",
    "Three hungers older than any world were chained beneath Zendikar, and everyone who has touched that chain since has paid for it.",
    listOf(
        StoryArticle(
            "the-binding", "The Binding",
            listOf(
                StoryTab(
                    "a-dead-plane", "A Dead Plane",
                    listOf(
                        "Long ago, when [Sorin](who/sorin) Markov was already old, a young kor planeswalker arrived on his world of Innistrad, confused and newly ignited. He could have chased her off. Instead he taught her. Her name was [Nahiri](who/nahiri), a lithomancer who could shape stone the way a potter shapes clay, and Sorin showed her the Multiverse one plane at a time until she was something close to a daughter to him.",
                        "On one of those journeys they watched a world die. Something vast was eating it, a thing called Ulamog, and when it was finished nothing was left. Soon afterward they met [Ugin](who/ugin), the spirit dragon, who knew what they had seen. There were three such titans, he told them, the Eldrazi, beings native to the [Blind Eternities](the-blind-eternities) between the planes, with no true form and no color, that drifted from world to world devouring mana and life until nothing remained.",
                        "The titans were larger than they looked. Each one pushed only part of itself into a plane, along with drones that were extensions of its own body, the way a man reaching into a pond shows the fish only his hand. Destroying them was the obvious answer. Ugin thought it a bad one, and in any case the three planeswalkers could not kill what they could not fully reach. So they would force the titans into physical form, and then they would keep them.",
                        "That meant a prison the size of a world, and a world willing to be one. It meant choosing somebody's home.",
                    ),
                ),
                StoryTab(
                    "forty-years", "Forty Years",
                    listOf(
                        "The plan needed a plane rich enough to lure the Eldrazi in and strong enough to hold them, and they chose Nahiri's own Zendikar, a world of wild, restless mana. They spent forty years preparing. Nahiri tore huge stones out of Zendikar's bedrock and shaped them into hedrons, and Ugin carved each one with draconic runes and set them across the world to steer the plane's leylines. The network was lure and cage at once, and it was never meant to be disturbed.",
                        "Nahiri warned her world before the hunger came. Merfolk, kor, humans, elves and even the surrakar heard her out, and angels kept watch in the skies. When the titans finally arrived, they dwarfed everything that saw them. {Ulamog, the Infinite Gyre}, {Kozilek, Butcher of Truth} and the towering {Emrakul, the Aeons Torn} came down on Zendikar together.",
                        "Ugin met them with ghostfire, his colorless breath. Sorin drained their strength and drew their hunger toward the trap. Zendikar's peoples fought the spawn that poured out around them. Step by step, at a cost the stories do not bother to count, the titans were herded toward the heart of the network, a cavern deep in the mountains of Akoum.",
                    ),
                    quote = StoryQuote(
                        "The spirit dragon Ugin arranged the hedrons of Zendikar to direct leylines of energy. To disrupt one is to unleash devastation and chaos.",
                        "Perilous Vault",
                    ),
                ),
                StoryTab(
                    "the-eye", "The Eye",
                    listOf(
                        "The cavern would be called the {Eye of Ugin}. There the three planeswalkers joined their magic and woke the whole network at once. Every hedron on Zendikar shifted into place. The titans were caught in a prison of stone and mana, and the earth closed over them, swallowing them into the mountains, where they sank into a dormancy that looked very much like death.",
                        "To make sure nobody could undo it, the Three sealed the chamber with a lock of their own design. Only three planeswalker sparks together, and the colorless breath of Ugin himself, could open it again. Then they parted. Sorin left Zendikar, and so did Ugin. Nahiri stayed to keep the watch, on the understanding that the others would come back if the prison ever stirred.",
                        "Zendikar learned to live in the shadows of the hedrons. In time explorers came to know it as a deathtrap of a world, rich in treasure and lethal to visit, and almost none of them knew the reason. It was a prison, and they were walking around on its roof.",
                        "For years the published story named the third jailer only as a mysterious lithomancer, and Commander 2014 revealed her as Nahiri, which is how the story is told today.",
                    ),
                    quote = StoryQuote("An eye closes. A race awakens.", "Eye of Ugin"),
                ),
                StoryTab(
                    "the-warden", "The Warden",
                    listOf(
                        "Nahiri lived among her people for a long time and kept the prison. Zendikar's wild mana drew other visitors, and she drove off the ones who meant it harm. The worst was the demon conqueror Ob Nixilis, whose power she bound with a hedron before he could do much damage. She took pupils and taught them to tend the network.",
                        "Centuries passed. She grew tired of living and withdrew into a meditative slumber, and without her the teaching went wrong. The kor came to worship Talib, Kamsa and Mangeni, and the merfolk Cosi, Emeria and Ula, gods half remembered from Kozilek, Emrakul and Ulamog, while Nahiri herself was remembered as a prophet. Then vampires, a people Zendikar had not known in the early days, built a shrine on a nexus of the hedron network, and the prison cracked. Brood spilled out across the land. Newer accounts tell this first awakening as the work of a cult of Ulamog in the mountains of Akoum, whose twelve survivors became the first vampire bloodchiefs.",
                        "Nahiri woke and called for Sorin and Ugin. Neither came. She found the fault in the network herself, repaired it and put the titans back to sleep, alone. Then she went to find out why her oldest friend had not answered.",
                        "On Innistrad, Sorin told her that her call had probably been swallowed by the [Helvault](helvault), the vault he had made to protect his world. He did not apologize. When she pressed him, he reminded her who had raised her and told her to go and bother Ugin instead. She threatened him, his angel Avacyn came to his defense, and the fight ended when Sorin sealed Nahiri inside the Helvault. The warden of the Eldrazi spent the following ages locked in a vault on someone else's world, and Zendikar's prison went unwatched.",
                    ),
                ),
            ),
            listOf(
                StorySource("Set", "Commander 2014"),
                StorySource("Set", "Rise of the Eldrazi"),
                StorySource("Story", "The Lithomancer, by Kelly Digges, Magic Story on magic.wizards.com"),
                StorySource("Reference", "MTG Wiki: Imprisonment of the Eldrazi"),
                StorySource("Reference", "MTG Wiki: Nahiri"),
                StorySource("Reference", "MTG Wiki: Eldrazi"),
            ),
        ),
        StoryArticle(
            "rise", "Rise",
            listOf(
                StoryTab(
                    "the-dragon-scroll", "The Dragon Scroll",
                    listOf(
                        "Six thousand years after [the binding](the-binding), three planeswalkers walked into the Eye of Ugin without knowing what it was. [Chandra](who/chandra) Nalaar came first, following a map she had found on the plane of Kephalai, an old scroll that hinted at a hidden fire. [Jace](who/jace) Beleren came after her. The vampire ruin sage Anowon guided them both through Akoum, and he had opinions about the scroll in Chandra's hands.",
                        "Waiting in the caves was Sarkhan Vol, a dragon-obsessed shaman who served [Nicol Bolas](who/nicol-bolas). Long before, on [Tarkir](tarkir), Bolas had torn the secrets of the Eldrazi prison out of [Ugin](who/ugin)'s mind and left him for dead, and he had sent Sarkhan to guard the Eye. Sarkhan had spent too long alone in its tunnels, talking to voices and wondering what failure he was being punished for.",
                        "He came to his senses in time to save Chandra from Anowon, and then to threaten her himself. She set off a spell beside him that failed spectacularly, and he was impressed. He led her deeper into the Eye, explaining what it was, and then took the shape of a dragon and attacked, meaning to sacrifice her to the voices. Jace arrived in the middle of it, with no idea the dragon was a man.",
                    ),
                    quote = StoryQuote(
                        "So many have died in search of that map. And now it appears in the hands of the arrogant child Chandra Nalaar.",
                        "Anowon, the Ruin Sage",
                    ),
                ),
                StoryTab(
                    "the-unsealing", "The Unsealing",
                    listOf(
                        "The three of them together in the chamber were the first part of the key. A hedron at its heart woke and began soaking up their magic, and colored spells died in the air. Chandra and Jace remembered what the scroll had hinted at, an invisible fire, and turned it on Sarkhan. It beat him. It also opened the last lock on the Eldrazi.",
                        "Chandra left in a hurry. Jace made sure the unconscious shaman, human again, was alive, and followed her. Sarkhan woke alone, climbed to the top of the mountain and found Eldrazi spawn being born out of a dead dragon's rotting body. He broke a large shard off the central hedron and planeswalked to his master.",
                        "In the Meditation Realm Bolas let {Sarkhan the Mad} kneel and then explained. He had meant for the Eldrazi to be freed all along, and had arranged the meeting of three sparks in the Eye himself. He claimed to have dealt with Ugin already. He did not want the shard. He dismissed his servant with a smile, after showing him the twisted body of Tezzeret, another of his servants, and assuring him that he always took care of his minions.",
                        "On Zendikar the hedrons began to move.",
                    ),
                ),
                StoryTab(
                    "sorin-and-nissa", "Sorin and Nissa",
                    listOf(
                        "The prison loosened, and one of its builders answered the old agreement. [Sorin](who/sorin) Markov came back to Zendikar alone. Ugin did not come, because he lay sealed in a cocoon of hedrons on Tarkir after his battle with Bolas. [Nahiri](who/nahiri) did not come, because Sorin had locked her in the Helvault and could not free her without destroying it.",
                        "Brood were already attacking elf villages, and Sorin found an ally in {Nissa Revane}, a young Joraga elf planeswalker trying to stop them. He freed Anowon, who had been enslaved, and the three set out for the Eye. It was a long and hungry road, down the Makindi Trench, across the sea past a moon kraken that remembered Sorin and feared him, through an elf enclave that tried to sacrifice them, and across a wasteland where they nearly died. Along the way Sorin told Nissa what was buried under Akoum, and that his mission was to keep it there.",
                        "At the Eye, Nissa did something else. She believed the titans would simply leave Zendikar if they were let go, and instead of helping Sorin reinforce the spell, she shattered the key hedron and broke the enchantment. She was wrong. Sorin washed his hands of her and of the whole plane, and left.",
                    ),
                ),
                StoryTab(
                    "the-brood", "The Brood",
                    listOf(
                        "Across Zendikar the dormant hedrons came alive, twisting and joining into strange structures that bent the laws of nature around them. The titans rose, and each spawned its own brood lineage, drones and lesser horrors made in its image. The carnage was immediate. Among those who threw themselves into the fighting was [Gideon](who/gideon) Jura, a planeswalker who would not leave a world to burn.",
                        "Within six months Emrakul and Kozilek had both vanished from sight, leaving a few spawn behind. Ulamog stayed and fed. Within two years Sea Gate, the great city of Tazeem, had fallen, and the whole continents of Sejiri and Bala Ged were overrun.",
                        "Sorin had not entirely washed his hands of the matter. He went looking for the missing third of the Three and found Ugin on Tarkir, alive in his hedron cocoon, because Sarkhan had travelled back in time and saved him there. Sorin woke him and told him the Eldrazi were free. Ugin asked first about Nahiri, saw that Sorin was hiding something, and told him to find her, warning that he did not wish to see Sorin's face without hers. Sorin left in a temper.",
                        "Not long after, the Helvault broke open on Innistrad, in [Avacyn Restored](avacyn-restored), and Nahiri was free. She went home to a ruined world, and nobody had come to help it. That would have consequences for Innistrad.",
                    ),
                ),
            ),
            listOf(
                StorySource("Set", "Zendikar"),
                StorySource("Set", "Worldwake"),
                StorySource("Set", "Rise of the Eldrazi"),
                StorySource("Novel", "Zendikar: In the Teeth of Akoum, by Robert B. Wintermute"),
                StorySource("Story", "Journey to the Eye, Awakenings and Enter the Eldrazi, by Brady Dommermuth, on magic.wizards.com"),
                StorySource("Reference", "MTG Wiki: Eye of Ugin"),
                StorySource("Reference", "MTG Wiki: Sarkhan Vol"),
                StorySource("Reference", "MTG Wiki: Sorin Markov"),
            ),
        ),
        StoryArticle(
            "battle-for-zendikar", "Battle for Zendikar",
            listOf(
                StoryTab(
                    "gideons-army", "Gideon's Army",
                    listOf(
                        "[Gideon](who/gideon) Jura came to Sea Gate after the slaughter had already happened, and all he could do was get the living out. He took command of what Zendikar had left. Battle-hardened veterans and desperate recruits gathered to his banner, and so did peoples who had little love for each other. The vampires of Malakir came under their leader Drana. The Roil mages of Noyan Dar came. Kiora, a merfolk planeswalker born on Zendikar, brought an army of merfolk and sea monsters.",
                        "Gideon also brought help from off the plane. On Ravnica he had met [Jace](who/jace) Beleren, and Jace came to Zendikar to fight. Gideon's merfolk friend Jori En, a relic hunter, had found clues that the hedrons and the leylines between them were the key to stopping the Eldrazi, and every leyline led to the Eye of Ugin.",
                        "Jace and Jori set out for Akoum. On the way they hid with a band of survivors who turned out to be cultists, still worshipping Ulamog in his old guise as the god Ula. The two escaped and saw that the titan was heading straight for Sea Gate, where Gideon and the survivors were. Jace forced his way into Jori's mind and made her leave him and run back with the warning. Then he went on to the Eye alone.",
                    ),
                    quote = StoryQuote(
                        "Though Gideon arrived at Sea Gate too late to prevent the massacre, he still had enough time to lead its survivors to relative safety.",
                        "Gruesome Slaughter",
                    ),
                ),
                StoryTab(
                    "ugins-warning", "Ugin's Warning",
                    listOf(
                        "In the chamber at the heart of [the binding](the-binding) Jace found [Ugin](who/ugin) himself, freed from his long sleep on Tarkir and rebuilding the Eye and the hedron network. With [Sorin](who/sorin) and [Nahiri](who/nahiri) both missing, the spirit dragon was doing the work of three. He showed Jace how the network could be used to pin the titans down again, as it had once before.",
                        "Jace saw something Ugin had not intended. If the hedrons could hold a titan still, the same trick might be the first step toward killing one. Ugin warned him off. The Eldrazi were older and stranger than anyone understood, and destroying them might cost the Multiverse more than leaving them alive. He reminded Jace that they had come as three, and only one was in sight.",
                        "Jace made Ugin a promise to neither harm the titans nor let them escape. Then he left the Eye and made his way back toward Sea Gate with the knowledge he needed, and a promise he would not be able to keep.",
                    ),
                    quote = StoryQuote(
                        "You speak to me of the threat of Ulamog. But do not forget: they came as three.",
                        "Ugin, to Jace Beleren, Ugin's Insight",
                    ),
                ),
                StoryTab(
                    "sea-gate", "Sea Gate",
                    listOf(
                        "Gideon's army, Kiora's merfolk and her leviathans attacked the Eldrazi holding Sea Gate, and Nissa Revane, the elf whose choice at the Eye had loosed the titans, came to fight beside them. She had lost her bond with the land for a time, and won it back by freeing Zendikar's soul from a hedron prison that Ob Nixilis had built at Khalni Heart. {Drana, Liberator of Malakir} and her vampires fought beside kor and merfolk. The Roil mages of Noyan Dar turned Zendikar's own violent upheavals against the brood. Beside Nissa walked Ashaya, an elemental of vines and living plants, a fragment of the world's soul. The Eldrazi were driven out of the city.",
                        "Sea Gate's ramparts were rebuilt and fortified, and the soldiers of Zendikar celebrated a victory they had paid for heavily. For a night it looked as if the plane might be won back piece by piece. It was the first real victory anyone on Zendikar could remember in two years of retreat, and it had been won by peoples who did not like each other, fighting under a planeswalker none of them had known a short time before.",
                        "Then Jori En arrived with the news that Ulamog himself was coming. The defenders had a city, an army and a few planeswalkers. Against them came a titan who consumed everything in his path, and the brood that moved with him like a tide.",
                    ),
                    quote = StoryQuote(
                        "I will not live as a slave. If you would be free, then fight alongside me.",
                        "Drana, Liberator of Malakir",
                    ),
                ),
                StoryTab(
                    "hedron-alignment", "Hedron Alignment",
                    listOf(
                        "The planeswalkers tried what Jace had learned at the Eye. While Gideon's troops and Kiora's forces held Sea Gate against the assault, Nissa called on the power of the earth to raise sunken hedrons out of the ground and move them into a ring around the titan. Jace woke the hedrons' magic. {Ulamog, the Ceaseless Hunger} was trapped.",
                        "Then it all collapsed. Ob Nixilis appeared, the demon whose spark Nahiri had bound with a hedron ages before. He had followed Nissa from Bala Ged, and he had wanted off Zendikar for a very long time. Gleaming with joy, he slaughtered the kor guarding the ring and drained the power of the hedrons into himself, and his spark came back to life.",
                        "{Ob Nixilis Reignited} took one last revenge on the world that had held him. He destroyed the hedron network. Ulamog was loose again, and beneath the ground the second titan stirred. Kozilek, who had vanished months before, rose out of the earth.",
                        "In quick succession the planeswalkers who had come to stop the Eldrazi were defeated or lost. Two titans walked free on Zendikar, and nobody was left in command.",
                    ),
                ),
                StoryTab(
                    "an-oath-sworn", "An Oath Sworn",
                    listOf(
                        "Somebody took command anyway. Beset by Kozilek's distortions, General Tazri survived behind the protection of her halo, regrouped with Noyan Dar, Drana and the kor squad leader Munda, and rallied what was left of the army. {Tazri, Beacon of Unity} held the line long enough for help to arrive.",
                        "The help was [Chandra](who/chandra) Nalaar, arriving from the plane of Regatha. She freed her captured friends, and together the planeswalkers drove Ob Nixilis off Zendikar.",
                        "Then the four of them, Gideon, Jace, Chandra and Nissa, said out loud what the last days had taught them. Alone, each was helpless against a threat this size. Together they might stand against almost anything the Multiverse could send. So they swore an oath to keep watch over the planes and stand together against what no one else could handle, and [the Gatewatch](gatewatch) was born on a battlefield on Zendikar.",
                        "An oath does not kill a titan. They still had two of them to deal with, and Jace had an idea that would break his promise to Ugin.",
                    ),
                    quote = StoryQuote("For justice and peace, I will keep watch.", "Oath of Gideon"),
                ),
                StoryTab(
                    "last-stand", "Last Stand",
                    listOf(
                        "Jace could not keep both halves of his promise, so he chose. With Nissa he worked out a pattern of leylines that would bind {Kozilek, the Great Distortion} and Ulamog to Zendikar and draw the bulk of each titan out of the Blind Eternities and into the plane, where its energy could be dispersed and the titan killed. What was left of Zendikar's defenders would serve as bait.",
                        "At first it worked. Gideon kept the swarms off the army, Kiora cleared the rest, and Chandra supported them all. But once the titans were anchored, their destructive essence threatened to drag Zendikar into themselves. Frightened, Kiora urged Nissa to let them go and flee, and Jace refused. Chandra offered to burn them instead. When Kiora turned on Nissa, Chandra stopped her, and Jace agreed.",
                        "Nissa opened herself to Chandra, and Chandra poured her fire through Zendikar's leylines straight into the titans. In one blaze, Ulamog and Kozilek were incinerated, and their ashes fell out of Zendikar's sky. Tazri led the rebuilding. Ugin was furious. He told the Gatewatch they had killed what they should have trapped, and that nobody knew what the consequences would be.",
                        "There had been three. Ugin confirmed what the stories had warned, that Emrakul had been gone from Zendikar for some time. The largest of the titans had been called somewhere else, [by someone with a grudge](emrakul-on-innistrad).",
                    ),
                ),
            ),
            listOf(
                StorySource("Set", "Battle for Zendikar"),
                StorySource("Set", "Oath of the Gatewatch"),
                StorySource("Story", "Battle for Zendikar and Oath of the Gatewatch, Magic Story on magic.wizards.com"),
                StorySource("Reference", "MTG Wiki: Zendikar (plane)"),
                StorySource("Reference", "MTG Wiki: Eldrazi"),
                StorySource("Reference", "MTG Wiki: Ugin"),
            ),
        ),
        StoryArticle(
            "emrakul-on-innistrad", "Emrakul on Innistrad",
            listOf(
                StoryTab(
                    "nahiris-return", "Nahiri's Return",
                    listOf(
                        "When the Helvault broke in [Avacyn Restored](avacyn-restored), [Nahiri](who/nahiri) fled home to Zendikar. She found the Eldrazi loose, whole continents laid waste, and no sign that anyone she had trusted had come to help. She had kept the prison for thousands of years and been locked away for answering the one time it mattered. She swore she would make Innistrad bleed the way Zendikar had bled.",
                        "She began with [Sorin](who/sorin)'s family. Nahiri struck Markov Manor with her lithomancy and twisted it into impossible shapes, sealing its vampires alive inside the walls in endless agony, a message in stone for Sorin to find when he came home. Some of Innistrad's people even began to worship her, a few of them convinced she was some ancient vampire herself.",
                        "Then she set about something larger. She did not want to hurt Sorin's world a little. She wanted it to suffer the same hunger Zendikar had. Somewhere out in the Blind Eternities was the one titan nobody had seen in months, and Nahiri knew how leylines worked better than almost anyone alive.",
                    ),
                    quote = StoryQuote("You brought this upon yourself, Sorin!", "Nahiri's Wrath"),
                ),
                StoryTab(
                    "cryptoliths", "Cryptoliths",
                    listOf(
                        "Across Innistrad, Nahiri raised cryptoliths, standing stones that channelled the plane's leyline energy toward a single purpose. At the focus of them all she built the {Drownyard Temple}, with help from the necromancer Gisa's ghouls. Through them she called Emrakul across the void between the planes.",
                        "As the titan drew near, Innistrad's guardian failed. Avacyn and her angels went mad, and the protector of humanity turned on the people she had protected. That part of the story belongs to [Shadows](shadows). Jace Beleren had come to Innistrad, and with the moonfolk planeswalker Tamiyo he confronted the archangel at Thraben Cathedral and nearly died there.",
                        "Sorin arrived in time. He had come home to the ruin of Markov Manor and, to win the vampires' help against Nahiri, had agreed to destroy Avacyn. He tried to reason with her first. She blamed him, her maker, for everything she had become. In the end Sorin unmade the angel he had created.",
                        "Avacyn's presence had been the only thing holding Emrakul back. With her gone, the titan came through. As [Jace](who/jace) tried to ask why, Sorin realized what Nahiri had done, and that he had just finished it for her.",
                    ),
                    quote = StoryQuote(
                        "Sorin had created Avacyn, so it was a cruelty beyond imagining, a pain beyond description, that it fell upon him to end her forever.",
                        "Anguished Unmaking",
                    ),
                ),
                StoryTab(
                    "markov-manor", "Markov Manor",
                    listOf(
                        "Sorin went to war. With the vampire lord Olivia Voldaren he raised the bloodlines and marched on the ruins of Markov Manor, where Nahiri waited with an army of cultists and Eldrazi abominations. Behind them, Emrakul was already twisting Innistrad into something else. Sorin had bought this army with his angel. Olivia's price for calling the bloodlines had been Avacyn's destruction, and he had paid it in Thraben Cathedral with his own hands. Elsewhere on the plane Liliana Vess was raising the dead to save what was left of the living, but Sorin had no interest in saving anyone that night. He wanted Nahiri.",
                        "The two old friends fought in the wreck of Sorin's ancestral home. {Nahiri, the Harbinger} took grave wounds, but she won. She encased Sorin in stone, as she had once been sealed away in silver, and left him there to watch Emrakul devour his world. Then she planeswalked away from Innistrad.",
                        "Olivia stood over the trapped Sorin, proclaimed herself the new lord of Innistrad, and took his sword. Innistrad's maker was a statue in a ruin, its guardian angel was dead, and the largest of the Eldrazi was loose above it. Whatever saved the plane would have to come from outside.",
                    ),
                    quote = StoryQuote(
                        "There is no saving this world, not anymore. But we shall be its bloody vengeance.",
                        "Sorin Markov, Campaign of Vengeance",
                    ),
                ),
                StoryTab(
                    "battle-of-thraben", "Battle of Thraben",
                    listOf(
                        "Emrakul did not eat Innistrad the way her siblings had eaten Zendikar. She changed it. She twisted every living thing she touched, plants, animals and people, into tentacled horrors marked with her fibrous lattice, and she twisted minds along with bodies. Her horrors and her cultists overran the walls of Thraben, the holy city, and much of it was destroyed.",
                        "Jace went back to Zendikar for the rest of the [Gatewatch](gatewatch). [Gideon](who/gideon), [Chandra](who/chandra) and Nissa came, and on Innistrad they found an unlikely ally in [Liliana](who/liliana) Vess, whose hordes of undead had no minds for Emrakul to corrupt. The Gatewatch and Liliana confronted the titan in the ruins of Thraben, and Emrakul's mental assault nearly brought Liliana down.",
                        "Jace reached toward Emrakul's mind and met a figure of her that wore the shape of Emeria, the merfolk goddess Zendikar had made out of a half memory of her. It told him she was incomplete, and that Innistrad should welcome her, not reject her.",
                        "Tamiyo had studied Innistrad's moon for years. She told the others that Emrakul could not be killed the way the other titans had been, and would have to be sealed. She pointed to the moon, the source of the silver that made the Helvault, as the only vault large enough. What happened to the plane in those days is told under [Eldritch Moon](eldritch-moon).",
                    ),
                    quote = StoryQuote(
                        "Hordes of Eldrazi and cultists swarmed over Thraben's defenses, and the members of the Gatewatch found themselves overrun.",
                        "Emrakul's Influence",
                    ),
                ),
                StoryTab(
                    "the-silver-moon", "The Silver Moon",
                    listOf(
                        "Tamiyo, Jace and Nissa cast the spell together. At first it seemed to fail. Then Tamiyo took out one of her iron-clad scrolls, one she had sworn never to read, and read it. The spell took hold, and {Emrakul, the Promised End} was drawn into Innistrad's moon and sealed there. A great sigil appeared on the moon's face.",
                        "Gideon offered Tamiyo a place in the Gatewatch. Shaken, she declined. Later she met Jace in an abandoned chapel and told him the truth. She had been possessed. The spell on that scroll had not been hers. It had been Emrakul's. The titan had let herself be imprisoned, telling them through Tamiyo that she no longer wanted to play, that the soil had not been receptive, and that it was not her time. Not yet.",
                        "Nobody knows what Emrakul is waiting for. In time the sigil faded from view, but the titan still sleeps inside the moon. Astronomancers and the witches of the Dawnhart Coven came to believe that her presence there had something to do with the long darkness that later fell on the plane, in [the Eternal Night](eternal-night).",
                        "Two of the three titans were ash. The third was sealed in silver above a world that had never asked to hold her, put there by a grudge between two old friends.",
                    ),
                    quote = StoryQuote("Only one vault was great enough to hold Emrakul.", "Imprisoned in the Moon"),
                ),
            ),
            listOf(
                StorySource("Set", "Shadows over Innistrad"),
                StorySource("Set", "Eldritch Moon"),
                StorySource("Story", "Shadows over Innistrad and Eldritch Moon, Magic Story on magic.wizards.com"),
                StorySource("Reference", "MTG Wiki: Emrakul"),
                StorySource("Reference", "MTG Wiki: Nahiri"),
                StorySource("Reference", "MTG Wiki: Tamiyo"),
            ),
        ),
    ),
)
