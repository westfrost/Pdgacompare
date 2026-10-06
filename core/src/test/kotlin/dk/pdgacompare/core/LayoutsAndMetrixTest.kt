package dk.pdgacompare.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutsAndMetrixTest {

    private val main = PdgaLayoutInfo("Valby Park", "Main 18", 18, 57)
    private val long = PdgaLayoutInfo("Valby Park", "Long", 18, 60)

    private fun event(id: Long, vararg rounds: Pair<Int, PdgaLayoutInfo>) = PdgaEvent(
        id, "Event $id", "2024-0$id-01",
        rounds.map { (n, layout) -> CalibrationRound(id, "Event $id", "2024-0$id-01", n, layout, listOf(RatedResult(57, 1000))) },
    )

    @Test
    fun guessesHolePars() {
        val holes = Layouts.guessHoles(18, 57)
        assertEquals(57, holes.sumOf { it.par })
        assertEquals(listOf(3, 4, 4, 4), holes.map { it.par }.takeLast(4))
        assertEquals(54, Layouts.guessHoles(18, null).sumOf { it.par })
    }

    @Test
    fun createsLayoutFromPdgaAndLinksMoreEvents() {
        var layout = Layouts.fromPdga("x", event(1, 1 to main, 2 to long, 3 to main), main)
        assertEquals("Valby Park - Main 18", layout.name)
        assertEquals(18, layout.holes.size)
        assertTrue(layout.parsEstimated)
        assertEquals(listOf(1, 3), layout.calibrationRounds.map { it.round })

        val (withMatch, added) = Layouts.addMatchingRounds(layout, event(2, 1 to long, 2 to main))
        assertEquals(1, added)
        layout = withMatch

        // Re-adding the same event adds nothing new.
        assertEquals(0, Layouts.addMatchingRounds(layout, event(2, 1 to long, 2 to main)).second)

        // The same layout under another name in another event is picked up too.
        val renamed = main.copy(layoutName = "Main course")
        val (withRenamed, renamedCount) = Layouts.addMatchingRounds(layout, event(3, 1 to renamed))
        assertEquals(1, renamedCount)
        assertEquals(4, withRenamed.calibrationRounds.size)

        // Linking another layout makes its rounds count as well.
        val (linked, linkedCount) = Layouts.linkCandidate(withRenamed, PdgaRoundFinder.group(event(4, 1 to long).rounds).single())
        assertEquals(1, linkedCount)
        assertEquals(2, linked.pdgaLayouts.size)
        assertEquals(1, Layouts.addMatchingRounds(linked, event(5, 1 to long)).second)
    }

    @Test
    fun stateRoundTrips() {
        val state = AppState(
            layouts = listOf(Layouts.fromPdga("x", event(1, 1 to main), main)),
            rounds = listOf(PlayedRound("r", "x", "Valby", listOf(Hole(1, 3)), 0, listOf(PlayerCard("Anna", listOf(3))))),
            knownPlayers = listOf("Anna"),
        )
        assertEquals(state, StateCodec.decode(StateCodec.encode(state)))
    }

    @Test
    fun parsesMetrixCourse() {
        val course = MetrixParser.parse(
            """{"course":{"ID":"123","Name":"Main","Fullname":"Valby Park → Main"},"baskets":[{"Number":"1","Par":"3","Length":"78"},{"Number":"2","Par":"4","Length":"140"}]}""",
        )!!
        assertEquals("Valby Park → Main", course.name)
        assertEquals(listOf(Hole(1, 3, 78), Hole(2, 4, 140)), course.holes)
    }

    @Test
    fun parsesMetrixCompetition() {
        val course = MetrixParser.parse(
            """{"Competition":{"Name":"Weekly","CourseName":"Valby Park → Main","Tracks":[{"Number":"1","Par":"3"},{"Number":"2","Par":"3"}]}}""",
        )!!
        assertEquals("Valby Park → Main", course.name)
        assertEquals(listOf(Hole(1, 3), Hole(2, 3)), course.holes)
    }

    @Test
    fun parsesMetrixReferences() {
        assertEquals(MetrixClient.Reference(123, true), MetrixClient.parseReference("https://discgolfmetrix.com/course/123"))
        assertEquals(MetrixClient.Reference(2345678, false), MetrixClient.parseReference("https://discgolfmetrix.com/2345678"))
        assertEquals(MetrixClient.Reference(55, null), MetrixClient.parseReference(" 55 "))
    }
}
