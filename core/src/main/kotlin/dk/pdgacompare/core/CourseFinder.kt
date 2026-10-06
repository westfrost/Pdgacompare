package dk.pdgacompare.core

import java.text.Normalizer

/** Recognises the same course across Metrix and PDGA, whose names are typed by different people. */
object CourseMatch {

    private val GENERIC = setOf(
        "disc", "golf", "discgolf", "discgolfpark", "golfpark", "park", "parken", "dgc", "dgp", "course", "courses",
        "bane", "banen", "baner", "frisbee", "frisbeegolf", "frisbeegolfbane", "club", "klub", "the", "and", "og",
    )

    fun tokens(name: String?): Set<String> {
        val ascii = Normalizer.normalize(name.orEmpty().lowercase(), Normalizer.Form.NFD)
            .replace("ø", "o").replace("æ", "ae").replace("ß", "ss")
            .replace(Regex("\\p{Mn}+"), "")
        return ascii.split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in GENERIC }
            .toSet()
    }

    /** True when all distinctive words of one name occur in the other. */
    fun sameCourse(a: String?, b: String?): Boolean {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        return tb.containsAll(ta) || ta.containsAll(tb)
    }

    /** The most distinctive word of a course name, for searching PDGA events by name. */
    fun keyword(name: String?): String? = tokens(name).maxByOrNull { it.length }

    fun locationMatches(location: String, city: String): Boolean {
        val cityTokens = tokens(city)
        return cityTokens.isNotEmpty() && tokens(location).containsAll(cityTokens)
    }
}

/** A PDGA layout on the course, with the rounds played on it in the fetched events. */
data class Candidate(val layout: PdgaLayoutInfo?, val rounds: List<CalibrationRound>) {
    val key: String get() = layout?.key.orEmpty()
    val eventCount: Int get() = rounds.map { it.eventId }.distinct().size
}

object PdgaRoundFinder {

    /** PDGA layouts on the layout's course in [events], best match first. */
    fun candidates(layout: Layout, events: List<PdgaEvent>): List<Candidate> {
        val course = layout.courseName.ifBlank { layout.name }
        return events.flatMap { it.rounds }
            .filter { round -> CourseMatch.sameCourse(round.layout?.courseName, course) }
            .groupBy { it.layout?.key.orEmpty() }
            .map { (_, rounds) -> Candidate(rounds.maxByOrNull { it.layout?.holeDetails?.size ?: 0 }?.layout, rounds) }
            .sortedWith(
                compareByDescending<Candidate> { holesMatch(layout, it) }
                    .thenByDescending { it.layout?.par == layout.par }
                    .thenByDescending { it.rounds.size },
            )
    }

    /** The candidate that is clearly the same layout, or null when the user has to choose. */
    fun autoPick(layout: Layout, candidates: List<Candidate>): Candidate? {
        candidates.firstOrNull { it.key in layout.pdgaLayoutKeys }?.let { return it }
        if (layout.holesGuessed) return candidates.singleOrNull()
        val sameHoles = candidates.filter { holesMatch(layout, it) }
        val samePar = sameHoles.filter { it.layout?.par == layout.par }
        return samePar.singleOrNull() ?: sameHoles.singleOrNull()
    }

    private fun holesMatch(layout: Layout, c: Candidate) =
        !layout.holesGuessed && c.layout?.holes == layout.holes.size
}
