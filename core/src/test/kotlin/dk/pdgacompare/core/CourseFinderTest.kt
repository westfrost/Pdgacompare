package dk.pdgacompare.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CourseFinderTest {

    @Test
    fun matchesCourseNamesAcrossSources() {
        assertTrue(CourseMatch.sameCourse("Smørum DiscGolf Park", "Smorum Disc Golf Park"))
        assertTrue(CourseMatch.sameCourse("Valby Park", "Valbyparken Valby"))
        assertTrue(CourseMatch.sameCourse("Ishøj Strandpark", "Ishøj Strandpark DGC"))
        assertFalse(CourseMatch.sameCourse("Valby Park", "Vallensbæk Disc Golf Park"))
        assertFalse(CourseMatch.sameCourse("Disc Golf Park", "Other Disc Golf Park"))
        assertEquals(listOf("vallensbaek"), CourseMatch.eventSearchTerms("Vallensbæk DiscGolfPark"))
        assertEquals(listOf("valby"), CourseMatch.eventSearchTerms("Valbyparken disc golf park"))
        assertTrue(CourseMatch.locationMatches("Køge, Region Zealand, Denmark", "Køge"))
    }

    private fun round(event: Long, course: String, holes: Int, par: Int, name: String = "Main") = CalibrationRound(
        event, "Event $event", "2024-01-0$event", 1, PdgaLayoutInfo(course, name, holes, par), listOf(RatedResult(par, 1000)),
    )

    private fun event(vararg rounds: CalibrationRound) =
        PdgaEvent(rounds.first().eventId, rounds.first().eventName, rounds.first().date, rounds.toList())

    private val metrixLayout = Layout("x", "Valby Park - Main", Layouts.guessHoles(18, 57), courseName = "Valby Park")

    @Test
    fun treatsDifferentlyNamedLayoutsWithSameHolesAndParAsOne() {
        val events = listOf(
            event(round(1, "Valby Park", 18, 57, "Sommer - Yellow tee")),
            event(round(2, "Valby Park", 18, 57, "DNA Tour - Gul")),
        )
        val candidates = PdgaRoundFinder.candidates(metrixLayout, events)
        assertEquals(1, candidates.size)
        assertEquals(2, PdgaRoundFinder.suggest(metrixLayout, candidates)!!.rounds.size)
    }

    @Test
    fun separatesTeesWithSameParByLength() {
        fun r(event: Long, name: String, length: Int) = CalibrationRound(
            event, "E$event", "2025-01-0$event", 1, PdgaLayoutInfo("Kokkedal Disc Golf Course", name, 18, 58, length),
            listOf(RatedResult(58, 900)),
        )
        val groups = PdgaRoundFinder.group(listOf(r(1, "White", 1833), r(1, "Yellow", 1578), r(2, "Kokkedal White", 1840), r(3, "Gul", 1560)))
        assertEquals(listOf(2, 2), groups.map { it.rounds.size })
        assertEquals(setOf(setOf(1833, 1840), setOf(1578, 1560)), groups.map { g -> g.rounds.map { it.layout!!.lengthMeters!! }.toSet() }.toSet())
    }

    @Test
    fun suggestsTheSameTeeColourWhenParIsEqual() {
        val yellow = metrixLayout.copy(name = "Valby Park - Yellow")
        val events = listOf(
            event(round(1, "Valby Park", 18, 57, "Sommer - White tee").let { it.copy(layout = it.layout!!.copy(lengthMeters = 2300)) }),
            event(round(2, "Valby Park", 18, 57, "DNA Tour - Gul").let { it.copy(layout = it.layout!!.copy(lengthMeters = 1900)) }),
        )
        val candidates = PdgaRoundFinder.candidates(yellow, events)
        assertEquals(listOf("DNA Tour - Gul", "Sommer - White tee"), candidates.map { it.layout?.layoutName })
        assertEquals("DNA Tour - Gul", PdgaRoundFinder.suggest(yellow, candidates)!!.layout?.layoutName)
        assertNull(PdgaRoundFinder.linked(yellow, candidates))
        assertEquals("yellow", CourseMatch.teeColor("Eghjorten - Gult layout"))
        assertNull(CourseMatch.teeColor("Hvid/Gul"))
    }

    @Test
    fun picksTheLayoutWithSameHolesAndPar() {
        val events = listOf(
            event(round(1, "Valby Park", 18, 57), round(1, "Valby Park", 24, 75, "Long")),
            event(round(2, "Valby Park", 18, 57), round(2, "Other Park", 18, 57)),
        )
        val candidates = PdgaRoundFinder.candidates(metrixLayout, events)
        assertEquals(listOf(18, 24), candidates.map { it.layout?.holes })
        val pick = PdgaRoundFinder.suggest(metrixLayout, candidates)!!
        assertEquals(2, pick.rounds.size)
        assertEquals(2, pick.eventCount)
    }

    @Test
    fun asksWhenNoLayoutMatchesClearly() {
        // Same holes, but neither has the Metrix layout's par 57.
        val events = listOf(event(round(1, "Valby Park", 18, 56, "Red"), round(1, "Valby Park", 18, 58, "Blue")))
        val candidates = PdgaRoundFinder.candidates(metrixLayout, events)
        assertEquals(2, candidates.size)
        assertNull(PdgaRoundFinder.suggest(metrixLayout, candidates))
        // Once the user has chosen, later searches pick the same one.
        val linked = Layouts.linkCandidate(metrixLayout, candidates[1]).first
        assertEquals(candidates[1].layout, PdgaRoundFinder.linked(linked, candidates)!!.layout)
        assertEquals(candidates[1].layout, PdgaRoundFinder.suggest(linked, candidates)!!.layout)
    }

    @Test
    fun linkingFillsInHolesThatWereGuessed() {
        val ref = MetrixCourseRef(5, 1, "Valby Park → Main", true, "Valby")
        val guessed = Layouts.fromMetrixSearch("x", ref, null, "DK")
        assertTrue(guessed.holesGuessed)
        assertEquals("Valby Park - Main", guessed.name)
        assertEquals("Valby Park", guessed.courseName)

        val candidate = PdgaRoundFinder.candidates(guessed, listOf(event(round(1, "Valby Park", 21, 64)))).single()
        assertEquals(candidate, PdgaRoundFinder.suggest(guessed, listOf(candidate)))
        val (linked, added) = Layouts.linkCandidate(guessed, candidate)
        assertEquals(1, added)
        assertEquals(21, linked.holes.size)
        assertEquals(64, linked.par)
        assertFalse(linked.holesGuessed)
        assertTrue(linked.parsEstimated)
    }

    @Test
    fun parsesMetrixCourseList() {
        // Shape of the real response (content=courses_list&country_code=DK).
        val refs = MetrixParser.parseCourseList(
            """{"courses":[
               {"ID":"18805","ParentID":null,"Name":"Valbyparken disc golf park","Fullname":"Valbyparken disc golf park","Type":"1","City":"København SV","Area":"København","Enddate":null},
               {"ID":"45766","ParentID":"18805","Name":"Yellow Tee","Fullname":"Valbyparken disc golf park &rarr; Yellow Tee","Type":"2","City":"","Area":"","Enddate":null},
               {"ID":"46367","ParentID":"18805","Name":"KFK","Fullname":"Valbyparken disc golf park &rarr; KFK","Type":"2","City":"","Enddate":"2027-01-01"},
               {"ID":"20543","ParentID":"18805","Name":"Old","Fullname":"Valbyparken disc golf park &rarr; Old","Type":"2","City":"","Enddate":"2026-03-26"},
               {"ID":"6664","ParentID":null,"Name":"Valbyparken - Yellow tee","Fullname":"Valbyparken - Yellow tee","Type":"2","City":"","Area":"","Enddate":null},
               {"ID":"700","ParentID":null,"Name":"Smørum DiscGolf Park","Fullname":"Smørum DiscGolf Park","Type":"2","City":"Smørum","Enddate":null}]}""",
            today = LocalDate.of(2026, 10, 6),
        )
        assertEquals(listOf(18805L, 45766L, 46367L, 6664L, 700L), refs.map { it.id })
        assertFalse(refs[0].isLayout)
        val yellow = refs[1]
        assertEquals(18805L, yellow.parentId)
        assertEquals("Valbyparken disc golf park", yellow.courseName)
        assertEquals("Valbyparken disc golf park - Yellow Tee", yellow.displayName)
        assertEquals("København SV", yellow.city)

        // Layouts of a matching course are found although their own names do not match.
        assertEquals(listOf(6664L, 46367L, 45766L), MetrixParser.search(refs, "valby").map { it.id })
        assertEquals(listOf(700L), MetrixParser.search(refs, "smørum").map { it.id })
        assertEquals(listOf(700L), MetrixParser.search(refs, "Smorum disc").map { it.id })
    }

    @Test
    fun parsesPdgaEventSearch() {
        val html = """
            <table class="views-table"><tbody>
              <tr><td class="views-field views-field-OfficialName"><a href="/tour/event/90001">Valby Open</a></td>
                  <td class="views-field views-field-StartDate">13-Apr to 14-Apr-2024</td>
                  <td class="views-field views-field-Location">Valby, Capital Region, Denmark</td></tr>
              <tr><td class="views-field views-field-OfficialName"><a href="https://www.pdga.com/tour/event/90002">Køge Weekly</a></td>
                  <td class="views-field views-field-StartDate">01-Mar-2025</td>
                  <td class="views-field views-field-Location">Køge, Denmark</td></tr>
            </tbody></table>"""
        assertEquals(
            listOf(
                PdgaEventSummary(90001, "Valby Open", "2024-04-13", "Valby, Capital Region, Denmark"),
                PdgaEventSummary(90002, "Køge Weekly", "2025-03-01", "Køge, Denmark"),
            ),
            PdgaParser.parseEventSearch(html),
        )
    }
}
