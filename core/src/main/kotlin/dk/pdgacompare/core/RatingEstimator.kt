package dk.pdgacompare.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Estimates the PDGA round rating of a score from real PDGA results on the same layout.
 *
 * Within one PDGA round, rating is a linear function of score (one SSA per layout per round).
 * For each calibration round we therefore take the results on and around the score, fit a line
 * through them and read off the rating at the score. The estimate is the average over the most
 * recent rounds.
 */
object RatingEstimator {

    const val DEFAULT_ROUND_COUNT = 5

    /** Results within this many strokes of the score are always used. */
    private const val BASE_WINDOW = 2

    /** The window grows (up to this) until the results contain at least two different scores. */
    private const val MAX_WINDOW = 40

    enum class Method {
        /** The score lies between results that were used. */
        INTERPOLATED,

        /** The score lies outside the scores shot in the round. */
        EXTRAPOLATED,

        /** Only results with exactly the score were available. */
        EXACT_ONLY,
    }

    data class RoundEstimate(
        val round: CalibrationRound,
        val rating: Double,
        val method: Method,
        /** Results used for the estimate. */
        val used: List<RatedResult>,
        /** Number of results with exactly the score. */
        val exactMatches: Int,
    )

    data class Estimate(
        val score: Int,
        val rating: Int,
        val perRound: List<RoundEstimate>,
    ) {
        val min: Int get() = perRound.minOf { it.rating }.roundToInt()
        val max: Int get() = perRound.maxOf { it.rating }.roundToInt()
    }

    fun estimate(
        rounds: List<CalibrationRound>,
        score: Int,
        holes: Int? = null,
        roundCount: Int = DEFAULT_ROUND_COUNT,
    ): Estimate? {
        val perRound = mostRecent(rounds, holes)
            .asSequence()
            .mapNotNull { estimateRound(it, score) }
            .take(roundCount)
            .toList()
        if (perRound.isEmpty()) return null
        return Estimate(score, perRound.map { it.rating }.average().roundToInt(), perRound)
    }

    /** Rounds sorted newest first, leaving out rounds known to have a different number of holes. */
    fun mostRecent(rounds: List<CalibrationRound>, holes: Int? = null): List<CalibrationRound> =
        rounds
            .filter { holes == null || it.layout?.holes == null || it.layout.holes == holes }
            .sortedWith(compareByDescending<CalibrationRound> { it.date }.thenByDescending { it.round })

    fun estimateRound(round: CalibrationRound, score: Int): RoundEstimate? {
        val results = round.results.filter { it.rating > 0 && it.score > 0 }
        if (results.isEmpty()) return null
        val exact = results.filter { it.score == score }

        var window = BASE_WINDOW
        while (window <= MAX_WINDOW) {
            val used = results.filter { abs(it.score - score) <= window }
            if (used.map { it.score }.distinct().size >= 2) {
                val fit = fitLine(used)
                // Better scores must give higher ratings; otherwise widen the window to drown the noise.
                if (fit.slope < 0) {
                    val inside = score >= used.minOf { it.score } && score <= used.maxOf { it.score }
                    return RoundEstimate(
                        round = round,
                        rating = fit.at(score),
                        method = if (inside) Method.INTERPOLATED else Method.EXTRAPOLATED,
                        used = used,
                        exactMatches = exact.size,
                    )
                }
            }
            window++
        }

        if (exact.isNotEmpty()) {
            return RoundEstimate(round, exact.map { it.rating }.average(), Method.EXACT_ONLY, exact, exact.size)
        }
        return null
    }

    private class Line(val slope: Double, val intercept: Double) {
        fun at(x: Int): Double = slope * x + intercept
    }

    private fun fitLine(points: List<RatedResult>): Line {
        val meanX = points.map { it.score.toDouble() }.average()
        val meanY = points.map { it.rating.toDouble() }.average()
        var sxy = 0.0
        var sxx = 0.0
        for (p in points) {
            val dx = p.score - meanX
            sxy += dx * (p.rating - meanY)
            sxx += dx * dx
        }
        val slope = sxy / sxx
        return Line(slope, meanY - slope * meanX)
    }
}
