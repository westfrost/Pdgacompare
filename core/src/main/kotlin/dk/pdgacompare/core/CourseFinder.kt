package dk.pdgacompare.core

import java.text.Normalizer

/** Recognises the same course across Metrix and PDGA, whose names are typed by different people. */
object CourseMatch {

    private val GENERIC = setOf(
        "disc", "golf", "discgolf", "discgolfpark", "golfpark", "park", "parken", "dgc", "dgp", "course", "courses",
        "bane", "banen", "baner", "frisbee", "frisbeegolf", "frisbeegolfbane", "club", "klub", "the", "and", "og",
    )

    private fun words(name: String?): List<String> =
        Normalizer.normalize(name.orEmpty().lowercase(), Normalizer.Form.NFD)
            .replace("ø", "o").replace("æ", "ae").replace("ß", "ss")
            .replace(Regex("\\p{Mn}+"), "")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }

    fun tokens(name: String?): Set<String> = words(name).filter { it.length >= 3 && it !in GENERIC }.toSet()

    /** All words of a search text or name, normalised like [tokens] but keeping generic words. */
    fun searchWords(text: String?): List<String> = words(text)

    private val SUFFIXES = listOf("parken", "park", "banen", "bane", "skoven", "skov", "s")

    /** Words that describe a layout rather than a course. */
    private val LAYOUT_WORDS = setOf(
        "tee", "tees", "layout", "white", "yellow", "red", "blue", "black", "gold", "front", "back", "short", "long",
        "hvid", "gul", "rod", "bla", "sort", "kort", "lang", "new", "old", "pro", "ams",
    )

    /**
     * The word to search PDGA event names with: the course's first distinctive word, without suffixes
     * like "parken" ("Valbyparken" -> "valby"), as events are named "Valbyparkens Sommerliga",
     * "DNA Tour #2 VALBY PARKEN" and so on. The search matches substrings, so shorter is wider.
     */
    fun eventSearchTerms(courseName: String?): List<String> {
        val word = words(courseName).firstOrNull { it.length >= 3 && it !in GENERIC && it !in LAYOUT_WORDS && !it.all(Char::isDigit) }
            ?: return emptyList()
        return listOf(SUFFIXES.firstNotNullOfOrNull { s -> word.removeSuffix(s).takeIf { it != word && it.length >= 4 } } ?: word)
    }

    /** True when all distinctive words of one name occur in the other. */
    fun sameCourse(a: String?, b: String?): Boolean {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        return tb.containsAll(ta) || ta.containsAll(tb)
    }

    private val TEE_COLORS = mapOf(
        "white" to "white", "hvid" to "white", "hvide" to "white", "vit" to "white", "valkoinen" to "white",
        "yellow" to "yellow", "gul" to "yellow", "gule" to "yellow", "gult" to "yellow", "keltainen" to "yellow",
        "red" to "red", "rod" to "red", "rode" to "red", "rodt" to "red", "punainen" to "red",
        "blue" to "blue", "bla" to "blue", "blaa" to "blue", "sininen" to "blue",
        "black" to "black", "sort" to "black", "gold" to "gold", "guld" to "gold",
    )

    /** The tee colour a layout name mentions ("Sommer - Yellow tee", "DNA Tour - Gul"), if exactly one. */
    fun teeColor(name: String?): String? = words(name).mapNotNull { TEE_COLORS[it] }.distinct().singleOrNull()

    fun locationMatches(location: String, city: String): Boolean {
        val cityTokens = tokens(city)
        return cityTokens.isNotEmpty() && tokens(location).containsAll(cityTokens)
    }
}

/** A PDGA layout on the course (as named in one or more events), with the rounds played on it. */
data class Candidate(val layout: PdgaLayoutInfo?, val rounds: List<CalibrationRound>) {
    val eventCount: Int get() = rounds.map { it.eventId }.distinct().size

    /** The layout descriptions of all its rounds, which link it to a layout. */
    val layouts: List<PdgaLayoutInfo> get() = rounds.map { it.layout ?: PdgaLayoutInfo() }.distinctBy { it.key }
}

object PdgaRoundFinder {

    /** Lengths within this fraction of each other count as the same layout when comparing with Metrix. */
    private const val LENGTH_TOLERANCE = 0.08

    /**
     * PDGA layouts on the layout's course in [events], best match first. Rounds count when PDGA's
     * course name matches, or when the event is in [courseEvents] (found by the course's name), since
     * PDGA sometimes names the course differently ("Fredtoften" is "Kokkedal Disc Golf Course").
     */
    fun candidates(layout: Layout, events: List<PdgaEvent>, courseEvents: Set<Long> = emptySet()): List<Candidate> {
        val course = layout.courseName.ifBlank { layout.name }
        val rounds = events.flatMap { it.rounds }
            .filter { round -> round.eventId in courseEvents || CourseMatch.sameCourse(round.layout?.courseName, course) }
        return group(rounds).sortedWith(
            compareByDescending<Candidate> { holesMatch(layout, it) }
                .thenByDescending { it.layout?.par == layout.par }
                .thenByDescending { colorMatch(layout, it) }
                .thenBy { lengthDifference(layout, it) ?: Double.MAX_VALUE }
                .thenByDescending { it.rounds.size },
        )
    }

    /** Groups rounds whose layouts are the same ([PdgaLayoutInfo.sameAs]) although named differently. */
    fun group(rounds: List<CalibrationRound>): List<Candidate> {
        val groups = mutableListOf<MutableList<CalibrationRound>>()
        for (round in rounds.sortedByDescending { it.date }) {
            val group = groups.firstOrNull { g -> g.first().layout?.sameAs(round.layout) ?: (round.layout == null) }
            if (group != null) group += round else groups += mutableListOf(round)
        }
        return groups.map { g ->
            // Describe it by its most common naming.
            val layout = g.groupBy { it.layout?.key }.maxByOrNull { it.value.size }?.value?.first()?.layout
            Candidate(layout, g)
        }
    }

    /** The candidate already linked to the layout, if any: it is used again without asking. */
    fun linked(layout: Layout, candidates: List<Candidate>): Candidate? =
        candidates.firstOrNull { c -> c.layouts.any { layout.isLinkedTo(it) } }

    /**
     * The candidate that looks like the same layout (holes, par, tee colour and length agree), or null.
     * Only a suggestion: tees with the same par are easily confused, so the user confirms it.
     */
    fun suggest(layout: Layout, candidates: List<Candidate>): Candidate? {
        linked(layout, candidates)?.let { return it }
        if (layout.holesGuessed) return candidates.singleOrNull()
        return candidates.firstOrNull { c ->
            holesMatch(layout, c) && c.layout?.par == layout.par && colorMatch(layout, c) >= 0 &&
                lengthDifference(layout, c).let { it == null || it <= LENGTH_TOLERANCE }
        }
    }

    /** 1 when both name the same tee colour, -1 when they name different ones, 0 when unknown. */
    private fun colorMatch(layout: Layout, c: Candidate): Int {
        val mine = CourseMatch.teeColor(layout.name) ?: return 0
        val theirs = c.layouts.mapNotNull { CourseMatch.teeColor(it.layoutName) }.distinct().singleOrNull() ?: return 0
        return if (mine == theirs) 1 else -1
    }

    private fun holesMatch(layout: Layout, c: Candidate) =
        !layout.holesGuessed && c.layout?.holes == layout.holes.size

    /** Relative length difference, or null when either length is unknown. */
    private fun lengthDifference(layout: Layout, c: Candidate): Double? {
        val a = layout.lengthMeters ?: return null
        val b = c.layout?.lengthMeters ?: return null
        return kotlin.math.abs(a - b).toDouble() / maxOf(a, b)
    }
}
