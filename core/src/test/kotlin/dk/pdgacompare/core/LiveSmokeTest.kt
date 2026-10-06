package dk.pdgacompare.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs the whole course search against the real Metrix and PDGA sites. Skipped unless LIVE=1:
 *   LIVE=1 gradle -p core test --tests '*LiveSmokeTest*' -i
 * Optional: LIVE_COURSE (default "Valbyparken"), LIVE_LAYOUT (part of the layout name), LIVE_COUNTRY (default DK).
 */
class LiveSmokeTest {

    @Test
    fun findsCourseAndEstimatesRating() = runBlocking {
        assumeTrue(System.getenv("LIVE") == "1")
        val query = System.getenv("LIVE_COURSE") ?: "Valbyparken"
        val layoutHint = System.getenv("LIVE_LAYOUT") ?: "Yellow"
        val country = System.getenv("LIVE_COUNTRY") ?: "DK"
        val metrix = MetrixClient()
        val pdga = PdgaClient()

        val refs = metrix.searchCourses(query, country)
        println("Metrix layouts for \"$query\": ${refs.size}")
        refs.forEach { println("  ${it.id} ${it.displayName} [${it.city}]") }
        val ref = refs.firstOrNull { it.fullName.contains(layoutHint, ignoreCase = true) } ?: refs.first()
        val holes = metrix.fetchCourseHoles(ref.id, "")
        val layout = Layouts.fromMetrixSearch("x", ref, holes, country)
        println("Picked ${layout.name}: ${layout.holes.size} holes, par ${layout.par}, ${layout.lengthMeters} m, course \"${layout.courseName}\", town \"${layout.city}\"")

        estimateFor(layout, country)
    }

    @Test
    fun findsCourseOnlyKnownToPdga() = runBlocking {
        assumeTrue(System.getenv("LIVE") == "1")
        val query = System.getenv("LIVE_PDGA_COURSE") ?: "TreeGrip"
        val layoutHint = System.getenv("LIVE_LAYOUT") ?: "White"
        val country = System.getenv("LIVE_COUNTRY") ?: "DK"
        val pdga = PdgaClient()
        val to = LocalDate.now()
        val events = pdga.searchEvents(country, to.minusYears(4), to, query, maxPages = 2).sortedByDescending { it.date }.take(8)
        val results = PdgaCourseSearch.group(events.flatMap { e -> pdga.fetchEventLayouts(e.id).map { it to e } })
        println("PDGA layouts for \"$query\": ${results.size}")
        results.forEach { println("  ${it.layout.label} [${it.town}] in ${it.eventCount} events, ${it.layout.holeDetails.size} hole details") }
        val result = results.firstOrNull { it.layout.layoutName.orEmpty().contains(layoutHint, ignoreCase = true) } ?: results.first()
        val layout = Layouts.fromPdgaSearch("x", result, country)
        println("Picked ${layout.name}: ${layout.holes.map { it.par }} par ${layout.par}, estimated pars: ${layout.parsEstimated}")
        estimateFor(layout, country)
    }

    private suspend fun estimateFor(layout: Layout, country: String) {
        val pdga = PdgaClient()
        val terms = CourseMatch.eventSearchTerms(layout.courseName)
        val to = LocalDate.now()
        val summaries = terms.flatMap { pdga.searchEvents(country, to.minusYears(4), to, it) }.distinctBy { it.id }
            .sortedByDescending { it.date }
        val courseEvents = summaries.map { it.id }.toSet()
        println("PDGA events for $terms: ${summaries.size}")
        summaries.take(5).forEach { println("  ${it.id} ${it.date} ${it.name} [${it.location}]") }

        val events = mutableListOf<PdgaEvent>()
        for (s in summaries.take(12)) {
            val e = runCatching { pdga.fetchEvent(s.id) }.onFailure { println("  ${s.id} failed: $it") }.getOrNull() ?: continue
            println("  ${e.id} ${e.startDate} ${e.name}: " + e.layouts.joinToString { (l, r) -> "${l?.label} x${r.size} (${r.sumOf { it.results.size }} results)" })
            events += e
        }
        val candidates = PdgaRoundFinder.candidates(layout, events, courseEvents)
        candidates.forEach { println("Candidate ${it.layout?.label}: ${it.rounds.size} rounds in ${it.eventCount} events") }
        val pick = PdgaRoundFinder.suggest(layout, candidates)
        println("Linked: ${PdgaRoundFinder.linked(layout, candidates)?.layout?.label}, suggested: ${pick?.layout?.label}")
        assertTrue(pick != null, "no PDGA layout picked")

        val linked = Layouts.linkCandidate(layout, pick).first
        for (toPar in listOf(-3, 0, 5, 9, 15)) {
            val est = RatingEstimator.estimate(linked.calibrationRounds, linked.par + toPar, linked.holes.size)!!
            println("Score ${linked.par + toPar} (${if (toPar >= 0) "+" else ""}$toPar): rating ${est.rating} (range ${est.min}-${est.max})")
            est.perRound.forEach { r ->
                println("    ${r.round.date} ${r.round.eventName} Rd${r.round.round}: ${"%.1f".format(r.rating)} ${r.method}, ${r.exactMatches} exact, used ${r.used.map { it.score to it.rating }}")
            }
        }
    }
}
