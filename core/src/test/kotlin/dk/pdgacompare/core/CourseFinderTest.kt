package dk.pdgacompare.core

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
        assertEquals("vallensbaek", CourseMatch.keyword("Vallensbæk DiscGolfPark"))
        assertTrue(CourseMatch.locationMatches("Køge, Region Zealand, Denmark", "Køge"))
    }

    private fun round(event: Long, course: String, holes: Int, par: Int, name: String = "Main") = CalibrationRound(
        event, "Event $event", "2024-01-0$event", 1, PdgaLayoutInfo(course, name, holes, par), listOf(RatedResult(par, 1000)),
    )

    private fun event(vararg rounds: CalibrationRound) =
        PdgaEvent(rounds.first().eventId, rounds.first().eventName, rounds.first().date, rounds.toList())

    private val metrixLayout = Layout("x", "Valby Park - Main", Layouts.guessHoles(18, 57), courseName = "Valby Park")

    @Test
    fun picksTheLayoutWithSameHolesAndPar() {
        val events = listOf(
            event(round(1, "Valby Park", 18, 57), round(1, "Valby Park", 24, 75, "Long")),
            event(round(2, "Valby Park", 18, 57), round(2, "Other Park", 18, 57)),
        )
        val candidates = PdgaRoundFinder.candidates(metrixLayout, events)
        assertEquals(listOf(18, 24), candidates.map { it.layout?.holes })
        val pick = PdgaRoundFinder.autoPick(metrixLayout, candidates)!!
        assertEquals(2, pick.rounds.size)
        assertEquals(2, pick.eventCount)
    }

    @Test
    fun asksWhenTwoLayoutsLookTheSame() {
        val events = listOf(event(round(1, "Valby Park", 18, 57, "Red"), round(1, "Valby Park", 18, 57, "Blue")))
        val candidates = PdgaRoundFinder.candidates(metrixLayout, events)
        assertEquals(2, candidates.size)
        assertNull(PdgaRoundFinder.autoPick(metrixLayout, candidates))
        // Once the user has chosen, later searches pick the same one.
        val linked = Layouts.linkCandidate(metrixLayout, candidates[1]).first
        assertEquals(candidates[1].key, PdgaRoundFinder.autoPick(linked, candidates)!!.key)
    }

    @Test
    fun linkingFillsInHolesThatWereGuessed() {
        val ref = MetrixCourseRef(5, 1, "Valby Park → Main", true, "Valby")
        val guessed = Layouts.fromMetrixSearch("x", ref, null, "DK")
        assertTrue(guessed.holesGuessed)
        assertEquals("Valby Park - Main", guessed.name)
        assertEquals("Valby Park", guessed.courseName)

        val candidate = PdgaRoundFinder.candidates(guessed, listOf(event(round(1, "Valby Park", 21, 64)))).single()
        assertEquals(candidate, PdgaRoundFinder.autoPick(guessed, listOf(candidate)))
        val (linked, added) = Layouts.linkCandidate(guessed, candidate)
        assertEquals(1, added)
        assertEquals(21, linked.holes.size)
        assertEquals(64, linked.par)
        assertFalse(linked.holesGuessed)
        assertTrue(linked.parsEstimated)
    }

    @Test
    fun parsesMetrixCourseList() {
        val refs = MetrixParser.parseCourseList(
            """[{"ID":"100","ParentID":"","Fullname":"Valby Park","Type":"1","CountryCode":"DK","City":"Valby","Enddate":null},
               {"ID":"101","ParentID":"100","Fullname":"Valby Park → Main","Type":"2","CountryCode":"DK","City":"Valby","Enddate":"0000-00-00"},
               {"ID":"102","ParentID":"100","Fullname":"Valby Park → Old","Type":"2","City":"Valby","Enddate":"2020-01-01"}]""",
        )
        assertEquals(listOf(100L, 101L), refs.map { it.id })
        assertFalse(refs[0].isLayout)
        assertEquals(100L, refs[1].parentId)
        assertEquals("Valby Park", refs[1].courseName)
        assertEquals("Valby Park - Main", refs[1].displayName)
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
