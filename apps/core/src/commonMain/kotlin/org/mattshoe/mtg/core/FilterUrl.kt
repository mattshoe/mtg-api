package org.mattshoe.mtg.core

/**
 * Filters to a query string and back, so a search is always a link.
 *
 * A port of `toHash`/`fromHash`. Only what differs from the defaults is
 * written, which keeps the common case a bare `#/search` rather than
 * sixty parameters spelling out "everything".
 *
 * Android has no address bar, but it has deep links and saved searches,
 * and the codec is what makes a search something you can hand to the
 * other platform. It is shared for that reason and not only for tidiness.
 */
object FilterUrl {

    private val DEFAULT = Filters()

    /**
     * Percent-encoding, by hand.
     *
     * `encodeURIComponent` is a browser function and this has to run on
     * Android and iOS too. The unreserved set is from RFC 3986; a space
     * becomes `+` the way a query string expects.
     */
    private fun enc(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt().toChar()
            when {
                c.isLetterOrDigit() && b.toInt() in 0..127 -> append(c)
                c in "-_.~" -> append(c)
                c == ' ' -> append('+')
                else -> append('%').append(((b.toInt() and 0xFF)).toString(16).uppercase().padStart(2, '0'))
            }
        }
    }

    private fun dec(s: String): String {
        val out = mutableListOf<Byte>()
        var i = 0
        while (i < s.length) {
            when {
                s[i] == '+' -> { out += ' '.code.toByte(); i++ }
                s[i] == '%' && i + 2 < s.length -> {
                    val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex == null) { out += s[i].code.toByte(); i++ } else { out += hex.toByte(); i += 3 }
                }
                else -> { out += s[i].code.toByte(); i++ }
            }
        }
        return out.toByteArray().decodeToString()
    }

    private fun pairs(f: Filters): List<Pair<String, String>> = buildList {
        fun put(key: String, v: String, d: String) { if (v != d) add(key to v) }
        fun putList(key: String, v: List<String>, d: List<String>) {
            if (v != d) add(key to v.joinToString(","))
        }

        put("owner", f.owner, DEFAULT.owner)
        put("q", f.q, DEFAULT.q)
        put("text", f.text, DEFAULT.text)
        put("textLike", f.textLike, DEFAULT.textLike)
        put("flavor", f.flavor, DEFAULT.flavor)
        put("artist", f.artist, DEFAULT.artist)
        put("watermark", f.watermark, DEFAULT.watermark)
        put("typeLine", f.typeLine, DEFAULT.typeLine)
        put("manaCost", f.manaCost, DEFAULT.manaCost)
        put("collnum", f.collnum, DEFAULT.collnum)
        put("deck", f.deck, DEFAULT.deck)
        put("finish", f.finish, DEFAULT.finish)
        put("format", f.format, DEFAULT.format)
        put("legality", f.legality, DEFAULT.legality)
        // `adv` is deliberately absent. The query box was removed, so
        // a link carrying one would apply a filter that no control
        // shows and no control can clear.

        put("qtyMin", f.qtyMin, ""); put("qtyMax", f.qtyMax, "")
        put("freeMin", f.freeMin, "")
        put("ciMin", f.ciMin, ""); put("ciMax", f.ciMax, "")
        put("cmcMin", f.cmcMin, ""); put("cmcMax", f.cmcMax, "")
        put("pow", f.pow, ""); put("powOp", f.powOp, DEFAULT.powOp)
        put("tou", f.tou, ""); put("touOp", f.touOp, DEFAULT.touOp)
        put("loy", f.loy, ""); put("loyOp", f.loyOp, DEFAULT.loyOp)
        put("yearMin", f.yearMin, ""); put("yearMax", f.yearMax, "")
        put("priceMin", f.priceMin, ""); put("priceMax", f.priceMax, "")
        put("edhrecMin", f.edhrecMin, ""); put("edhrecMax", f.edhrecMax, "")

        if (f.pool != DEFAULT.pool) add("pool" to f.pool.slug)
        if (f.colorTarget != DEFAULT.colorTarget) add("colorTarget" to f.colorTarget.slug)
        if (f.colorMode != DEFAULT.colorMode) add("colorMode" to f.colorMode.slug)
        if (f.hasRulings != DEFAULT.hasRulings) add("hasRulings" to f.hasRulings.name.lowercase())

        putList("colors", f.colors, DEFAULT.colors)
        putList("produces", f.produces, DEFAULT.produces)
        putList("types", f.types, DEFAULT.types)
        putList("typesNot", f.typesNot, DEFAULT.typesNot)
        putList("supertypes", f.supertypes, DEFAULT.supertypes)
        putList("rarities", f.rarities, DEFAULT.rarities)
        putList("sets", f.sets, DEFAULT.sets)
        putList("setTypes", f.setTypes, DEFAULT.setTypes)
        putList("layouts", f.layouts, DEFAULT.layouts)
        putList("frames", f.frames, DEFAULT.frames)
        putList("borders", f.borders, DEFAULT.borders)
        putList("games", f.games, DEFAULT.games)
        putList("keywords", f.keywords, DEFAULT.keywords)
        putList("tags", f.tags, DEFAULT.tags)

        Flag.entries.forEach { flag ->
            val t = f.flags[flag] ?: Tri.ANY
            if (t != Tri.ANY) add(flag.slug to t.name.lowercase())
        }

        if (f.sort != DEFAULT.sort) add("sort" to f.sort.slug)
        if (f.descending != DEFAULT.descending) add("dir" to if (f.descending) "desc" else "asc")
        if (f.page != DEFAULT.page) add("page" to f.page.toString())
    }

    /** `#/search?...`, or just `#/search` when nothing differs. */
    fun toHash(f: Filters): String {
        val q = pairs(f).joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return if (q.isEmpty()) "#/search" else "#/search?$q"
    }

    fun fromHash(queryString: String?): Filters {
        val q = queryString.orEmpty().removePrefix("?")
        if (q.isBlank()) return Filters()

        val m = q.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else dec(it.substring(0, i)) to dec(it.substring(i + 1))
        }.toMap()

        fun list(key: String) = m[key]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        fun tri(key: String) = when (m[key]) {
            "yes" -> Tri.YES
            "no" -> Tri.NO
            else -> Tri.ANY
        }

        val flags = Flag.entries.mapNotNull { f ->
            tri(f.slug).takeIf { it != Tri.ANY }?.let { f to it }
        }.toMap()

        return Filters(
            owner = m["owner"] ?: DEFAULT.owner,
            qtyMin = m["qtyMin"].orEmpty(), qtyMax = m["qtyMax"].orEmpty(),
            pool = Pool.entries.firstOrNull { it.slug == m["pool"] } ?: DEFAULT.pool,
            freeMin = m["freeMin"].orEmpty(),
            deck = m["deck"].orEmpty(),
            finish = m["finish"].orEmpty(),
            q = m["q"].orEmpty(),
            text = m["text"].orEmpty(),
            textLike = m["textLike"].orEmpty(),
            flavor = m["flavor"].orEmpty(),
            artist = m["artist"].orEmpty(),
            watermark = m["watermark"].orEmpty(),
            typeLine = m["typeLine"].orEmpty(),
            colorTarget = ColorTarget.entries.firstOrNull { it.slug == m["colorTarget"] } ?: DEFAULT.colorTarget,
            colorMode = m["colorMode"]?.let { ColorMode.of(it) } ?: DEFAULT.colorMode,
            colors = list("colors"),
            ciMin = m["ciMin"].orEmpty(), ciMax = m["ciMax"].orEmpty(),
            produces = list("produces"),
            cmcMin = m["cmcMin"].orEmpty(), cmcMax = m["cmcMax"].orEmpty(),
            manaCost = m["manaCost"].orEmpty(),
            powOp = m["powOp"] ?: DEFAULT.powOp, pow = m["pow"].orEmpty(),
            touOp = m["touOp"] ?: DEFAULT.touOp, tou = m["tou"].orEmpty(),
            loyOp = m["loyOp"] ?: DEFAULT.loyOp, loy = m["loy"].orEmpty(),
            types = list("types"), typesNot = list("typesNot"),
            supertypes = list("supertypes"),
            rarities = list("rarities"), sets = list("sets"), setTypes = list("setTypes"),
            layouts = list("layouts"), frames = list("frames"), borders = list("borders"),
            games = list("games"),
            yearMin = m["yearMin"].orEmpty(), yearMax = m["yearMax"].orEmpty(),
            collnum = m["collnum"].orEmpty(),
            flags = flags,
            priceMin = m["priceMin"].orEmpty(), priceMax = m["priceMax"].orEmpty(),
            keywords = list("keywords"), tags = list("tags"),
            format = m["format"].orEmpty(), legality = m["legality"] ?: DEFAULT.legality,
            edhrecMin = m["edhrecMin"].orEmpty(), edhrecMax = m["edhrecMax"].orEmpty(),
            hasRulings = tri("hasRulings"),
            // `adv` is deliberately not read — see `pairs`.
            sort = m["sort"]?.let { Sort.of(it) } ?: DEFAULT.sort,
            // Only the two spellings mean anything; `dir=sideways` keeps
        // the default rather than silently reversing the order.
        descending = when (m["dir"]) {
            "desc" -> true
            "asc" -> false
            else -> DEFAULT.descending
        },
            page = m["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
        )
    }
}
