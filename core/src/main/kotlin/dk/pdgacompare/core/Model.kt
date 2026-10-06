package dk.pdgacompare.core

import kotlinx.serialization.Serializable

@Serializable
data class Hole(
    val number: Int,
    val par: Int,
    val lengthMeters: Int? = null,
)

/** Layout description as reported by PDGA for a tournament round. */
@Serializable
data class PdgaLayoutInfo(
    val courseName: String? = null,
    val layoutName: String? = null,
    val holes: Int? = null,
    val par: Int? = null,
    val lengthMeters: Int? = null,
    val holeDetails: List<Hole> = emptyList(),
) {
    /** Identifies "the same layout" across events. */
    val key: String
        get() = listOf(normalize(courseName), normalize(layoutName), holes?.toString().orEmpty(), par?.toString().orEmpty())
            .joinToString("|")

    val label: String
        get() {
            val name = listOfNotNull(courseName, layoutName).filter { it.isNotBlank() }.distinct().joinToString(" - ")
                .ifBlank { "Unknown layout" }
            val facts = listOfNotNull(
                holes?.let { "$it holes" },
                par?.let { "par $it" },
                lengthMeters?.let { "$it m" },
            )
            return if (facts.isEmpty()) name else "$name (${facts.joinToString(", ")})"
        }

    private fun normalize(s: String?): String =
        s.orEmpty().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}

@Serializable
data class RatedResult(
    val score: Int,
    val rating: Int,
)

/** All rated results on one layout in one round of one PDGA event (all divisions combined). */
@Serializable
data class CalibrationRound(
    val eventId: Long,
    val eventName: String,
    /** ISO date (yyyy-MM-dd) of the round, or of the event start if the round date is unknown. */
    val date: String,
    val round: Int,
    val layout: PdgaLayoutInfo? = null,
    val results: List<RatedResult>,
) {
    val id: String get() = "$eventId-$round-${layout?.key.orEmpty()}"
}

@Serializable
data class Layout(
    val id: String,
    val name: String,
    val holes: List<Hole>,
    /** PDGA layouts (see [PdgaLayoutInfo.key]) that count as this layout. */
    val pdgaLayoutKeys: Set<String> = emptySet(),
    val calibrationRounds: List<CalibrationRound> = emptyList(),
    val source: String = "Manual",
    /** True when hole pars were guessed from a total par and should be checked by the user. */
    val parsEstimated: Boolean = false,
    /** True when even the number of holes is unknown (taken from PDGA once rounds are linked). */
    val holesGuessed: Boolean = false,
    /** Course (not layout) name, used to recognise the course in PDGA results. */
    val courseName: String = "",
    val city: String = "",
    val countryCode: String = "",
) {
    val par: Int get() = holes.sumOf { it.par }
}

@Serializable
data class PlayerCard(
    val name: String,
    /** One entry per hole; null = not entered yet. */
    val scores: List<Int?>,
) {
    val isComplete: Boolean get() = scores.all { it != null }
    val total: Int get() = scores.sumOf { it ?: 0 }

    fun toPar(holes: List<Hole>): Int =
        scores.zip(holes).sumOf { (s, h) -> if (s == null) 0 else s - h.par }
}

@Serializable
data class PlayedRound(
    val id: String,
    val layoutId: String,
    val layoutName: String,
    val holes: List<Hole>,
    val startedAt: Long,
    val players: List<PlayerCard>,
    val finished: Boolean = false,
)

@Serializable
data class AppState(
    val layouts: List<Layout> = emptyList(),
    val rounds: List<PlayedRound> = emptyList(),
    val knownPlayers: List<String> = emptyList(),
    val metrixCode: String = "",
    val countryCode: String = "DK",
)
