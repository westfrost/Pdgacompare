package dk.pdgacompare.core

import kotlinx.serialization.encodeToString

object Layouts {

    fun manual(id: String, name: String, holeCount: Int): Layout =
        Layout(id, name.trim().ifEmpty { "New layout" }, (1..holeCount).map { Hole(it, 3) })

    fun fromMetrix(id: String, course: MetrixCourse): Layout =
        Layout(id, course.name, course.holes, source = "Disc Golf Metrix")

    /** A layout based on a PDGA layout, calibrated with the event's rounds on it. */
    fun fromPdga(id: String, event: PdgaEvent, layout: PdgaLayoutInfo?): Layout {
        val holes = layout?.holeDetails?.takeIf { it.isNotEmpty() }
        val generated = holes == null
        return Layout(
            id = id,
            name = layout?.let { listOfNotNull(it.courseName, it.layoutName).joinToString(" - ") }
                ?.ifBlank { null } ?: event.name,
            holes = holes ?: guessHoles(layout?.holes ?: 18, layout?.par),
            pdgaLayoutKeys = setOf(layout?.key.orEmpty()),
            calibrationRounds = event.rounds.filter { it.layout?.key == layout?.key },
            source = "PDGA",
            parsEstimated = generated && layout?.par != null,
        )
    }

    /** Spreads a total par over the holes (par 3 everywhere, extra strokes on the last holes). */
    fun guessHoles(count: Int, totalPar: Int?): List<Hole> {
        val par = totalPar ?: (count * 3)
        val base = par / count
        val extra = par % count
        return (1..count).map { Hole(it, if (it > count - extra) base + 1 else base) }
    }

    /** Adds the event's rounds played on the layout's PDGA layouts. Returns the layout and how many rounds were added. */
    fun addMatchingRounds(layout: Layout, event: PdgaEvent): Pair<Layout, Int> {
        val matching = event.rounds.filter { (it.layout?.key ?: "") in layout.pdgaLayoutKeys }
        return addRounds(layout, matching)
    }

    /** Declares [pdgaLayout] to be the same as [layout] and adds the event's rounds on it. */
    fun linkPdgaLayout(layout: Layout, event: PdgaEvent, pdgaLayout: PdgaLayoutInfo?): Pair<Layout, Int> {
        val key = pdgaLayout?.key.orEmpty()
        val linked = layout.copy(pdgaLayoutKeys = layout.pdgaLayoutKeys + key)
        return addRounds(linked, event.rounds.filter { (it.layout?.key ?: "") == key })
    }

    private fun addRounds(layout: Layout, rounds: List<CalibrationRound>): Pair<Layout, Int> {
        val existing = layout.calibrationRounds.associateBy { it.id }
        val fresh = rounds.filter { it.id !in existing }
        // Re-fetched rounds replace the stored copy (ratings may have become official since).
        val updated = existing + rounds.associateBy { it.id }
        return layout.copy(calibrationRounds = updated.values.toList()) to fresh.size
    }
}

object StateCodec {
    fun encode(state: AppState): String = lenientJson.encodeToString(state)
    fun decode(text: String): AppState = lenientJson.decodeFromString(AppState.serializer(), text)
}
