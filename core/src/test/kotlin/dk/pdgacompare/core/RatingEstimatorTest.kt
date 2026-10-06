package dk.pdgacompare.core

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RatingEstimatorTest {

    /** A PDGA-like round: rating = 1000 + (ssa - score) * pointsPerThrow, rounded. */
    private fun round(
        date: String,
        scores: List<Int>,
        ssa: Double = 54.0,
        pointsPerThrow: Double = 10.0,
        roundNo: Int = 1,
        holes: Int? = 18,
    ) = CalibrationRound(
        eventId = date.replace("-", "").toLong(),
        eventName = "Event $date",
        date = date,
        round = roundNo,
        layout = PdgaLayoutInfo("Park", "Main", holes, 54),
        results = scores.map { RatedResult(it, (1000 + (ssa - it) * pointsPerThrow).roundToInt()) },
    )

    @Test
    fun exactScoreGivesThatRating() {
        val r = round("2024-05-01", listOf(55, 58, 60, 63, 63, 66))
        val estimate = RatingEstimator.estimateRound(r, 63)!!
        assertEquals(910, estimate.rating.roundToInt())
        assertEquals(2, estimate.exactMatches)
        assertEquals(RatingEstimator.Method.INTERPOLATED, estimate.method)
    }

    @Test
    fun missingScoreIsInterpolatedFromNeighbours() {
        val r = round("2024-05-01", listOf(50, 58, 61, 65, 70), ssa = 56.3, pointsPerThrow = 9.2)
        val estimate = RatingEstimator.estimateRound(r, 63)!!
        assertEquals((1000 + (56.3 - 63) * 9.2), estimate.rating, 1.0)
        assertEquals(listOf(61, 65), estimate.used.map { it.score })
    }

    @Test
    fun scoreOutsideTheFieldIsExtrapolated() {
        val r = round("2024-05-01", listOf(60, 61, 62, 64))
        val estimate = RatingEstimator.estimateRound(r, 52)!!
        assertEquals(RatingEstimator.Method.EXTRAPOLATED, estimate.method)
        assertEquals(1020.0, estimate.rating, 1.0)
    }

    @Test
    fun singleExactScoreWithNothingAround() {
        val r = CalibrationRound(1, "E", "2024-01-01", 1, null, listOf(RatedResult(60, 940), RatedResult(60, 941)))
        val estimate = RatingEstimator.estimateRound(r, 60)!!
        assertEquals(RatingEstimator.Method.EXACT_ONLY, estimate.method)
        assertEquals(940.5, estimate.rating, 0.01)
        assertNull(RatingEstimator.estimateRound(r, 62))
    }

    @Test
    fun usesTheFiveMostRecentRoundsAndAveragesThem() {
        val rounds = listOf(
            round("2023-01-01", listOf(55, 60, 65), ssa = 40.0), // oldest: must be left out
            round("2024-01-01", listOf(55, 60, 65), ssa = 54.0),
            round("2024-02-01", listOf(55, 60, 65), ssa = 55.0),
            round("2024-03-01", listOf(55, 60, 65), ssa = 56.0, roundNo = 1),
            round("2024-03-01", listOf(55, 60, 65), ssa = 57.0, roundNo = 2),
            round("2024-04-01", listOf(55, 60, 65), ssa = 58.0),
        )
        val estimate = RatingEstimator.estimate(rounds, 60, holes = 18)!!
        assertEquals(5, estimate.perRound.size)
        // Ratings at 60: 940, 950, 960, 970, 980.
        assertEquals(960, estimate.rating)
        assertEquals(940, estimate.min)
        assertEquals(980, estimate.max)
        assertEquals(
            listOf("2024-04-01" to 1, "2024-03-01" to 2, "2024-03-01" to 1, "2024-02-01" to 1, "2024-01-01" to 1),
            estimate.perRound.map { it.round.date to it.round.round },
        )
    }

    @Test
    fun roundsWithAnotherHoleCountAreIgnored() {
        val rounds = listOf(
            round("2024-01-01", listOf(55, 60, 65), holes = 18),
            round("2024-06-01", listOf(55, 60, 65), holes = 21, ssa = 70.0),
        )
        val estimate = RatingEstimator.estimate(rounds, 60, holes = 18)!!
        assertEquals(1, estimate.perRound.size)
        assertEquals(940, estimate.rating)
    }

    @Test
    fun noRatedResultsGivesNoEstimate() {
        assertNull(RatingEstimator.estimate(emptyList(), 60))
        val unrated = CalibrationRound(1, "E", "2024-01-01", 1, null, listOf(RatedResult(60, 0)))
        assertNull(RatingEstimator.estimate(listOf(unrated), 60))
    }
}
