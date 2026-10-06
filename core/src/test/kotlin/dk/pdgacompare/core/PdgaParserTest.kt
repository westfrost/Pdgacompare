package dk.pdgacompare.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PdgaParserTest {

    private fun resource(name: String) = javaClass.classLoader.getResource(name)!!.readText()

    @Test
    fun parsesEventResultsPage() {
        val event = PdgaParser.parseEventHtml(resource("pdga-event.html"), 80000)
        assertEquals("Valby Open 2024", event.name)
        assertEquals("2024-04-13", event.startDate)

        val main = PdgaLayoutInfo("Valby Park", "Main 18", 18, 57, 1823)
        val long = PdgaLayoutInfo("Valby Park", "Long", 18, 60, 2225)
        assertEquals(listOf(1 to main.key, 2 to long.key, 2 to main.key), event.rounds.map { it.round to it.layout!!.key })

        val round1 = event.rounds.single { it.round == 1 }
        assertEquals(main, round1.layout)
        // Both divisions are combined; the DNF player's round 1 still counts.
        assertEquals(
            listOf(RatedResult(53, 1012), RatedResult(59, 952), RatedResult(62, 922), RatedResult(63, 912)),
            round1.results,
        )
        // The 999 without rating is dropped.
        assertEquals(listOf(RatedResult(58, 998), RatedResult(61, 968)), event.rounds.single { it.round == 2 && it.layout == long }.results)
        assertEquals(2, event.layouts.size)
    }

    @Test
    fun parsesLayoutTexts() {
        assertEquals(
            PdgaLayoutInfo("Valby Park", "Main 18", 18, 57, 1823),
            PdgaParser.parseLayoutText("Course: Valby Park - Layout: Main 18; 18 holes; Par 57; 5,980 ft."),
        )
        assertEquals(
            PdgaLayoutInfo("Smørum DiscGolf Park", "A-pool", 21, 63, 1650),
            PdgaParser.parseLayoutText("Rd2: Smørum DiscGolf Park - A-pool; 21 holes; Par 63; 1650 m"),
        )
        assertEquals(PdgaLayoutInfo(null, null, 18, 54, null), PdgaParser.parseLayoutText("18 holes; Par 54"))
        assertNull(PdgaParser.parseLayoutText("Rd1"))
    }

    @Test
    fun parsesEventDates() {
        assertEquals("2024-04-13", PdgaParser.parseEventDate("Date: 13-Apr to 15-Apr-2024"))
        assertEquals("2023-09-30", PdgaParser.parseEventDate("Date: 30-Sep-2023 to 01-Oct-2023"))
        assertEquals("2022-06-05", PdgaParser.parseEventDate("5-Jun-2022"))
        assertEquals("2025-03-01", PdgaParser.parseEventDate("2025-03-01T00:00:00"))
        assertEquals("", PdgaParser.parseEventDate("TBA"))
    }

    @Test
    fun parsesLiveRound() {
        val results = PdgaParser.parseLiveRound(resource("pdga-live-round.json"))
        assertEquals(listOf(RatedResult(9, 1010), RatedResult(11, 950)), results.map { it.second })
        val layout = results.first().first!!
        assertEquals("Valby Park", layout.courseName)
        assertEquals("Main 18", layout.layoutName)
        assertEquals(3, layout.holes)
        assertEquals(10, layout.par)
        assertEquals(274, layout.lengthMeters)
        assertEquals(listOf(Hole(1, 3, 76), Hole(2, 4, 122), Hole(3, 3, 76)), layout.holeDetails)
    }

    @Test
    fun parsesLiveEvent() {
        val event = PdgaParser.parseLiveEvent(
            """{"data":{"Name":"Valby Open","StartDate":"2024-04-13","Divisions":[{"Division":"MPO","LatestRound":"3"},{"Division":"FA2","LatestRound":2}]}}""",
        )
        assertEquals("Valby Open", event.name)
        assertEquals("2024-04-13", event.startDate)
        assertEquals(listOf("MPO", "FA2"), event.divisions)
        assertEquals(3, event.rounds)
    }

    @Test
    fun extractsEventIds() {
        assertEquals(
            listOf(77775L, 81234L, 900L),
            PdgaParser.parseEventIds("https://www.pdga.com/tour/event/77775\n81234, https://www.pdga.com/apps/tournament/live/event?eventId=900&view=Scores"),
        )
    }
}
