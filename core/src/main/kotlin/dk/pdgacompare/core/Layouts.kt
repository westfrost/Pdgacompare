package dk.pdgacompare.core

import kotlinx.serialization.encodeToString

object Layouts {

    fun manual(id: String, name: String, holeCount: Int): Layout =
        Layout(id, name.trim().ifEmpty { "New layout" }, (1..holeCount).map { Hole(it, 3) })

    fun fromMetrix(id: String, course: MetrixCourse): Layout =
        Layout(id, course.name, course.holes, source = "Disc Golf Metrix", courseName = metrixCourseName(course.name))

    /** A layout found by name in Metrix. Without [course] (holes could not be loaded) the holes are a guess. */
    fun fromMetrixSearch(id: String, ref: MetrixCourseRef, course: MetrixCourse?, countryCode: String): Layout = Layout(
        id = id,
        name = ref.displayName,
        holes = course?.holes ?: guessHoles(18, null),
        lengthMeters = course?.lengthMeters ?: course?.holes?.takeIf { h -> h.all { it.lengthMeters != null } }?.sumOf { it.lengthMeters!! },
        source = "Disc Golf Metrix",
        parsEstimated = course == null,
        holesGuessed = course == null,
        courseName = ref.courseName,
        city = ref.city,
        countryCode = countryCode,
    )

    /** Declares the candidate PDGA layout to be the same as [layout] and adds its rounds. */
    fun linkCandidate(layout: Layout, candidate: Candidate): Pair<Layout, Int> {
        var linked = layout.copy(pdgaLayouts = (layout.pdgaLayouts + candidate.layouts).distinctBy { it.key })
        val pdga = candidate.layout
        if (layout.holesGuessed && pdga?.holes != null) {
            val details = pdga.holeDetails.takeIf { it.size == pdga.holes }
            linked = linked.copy(
                holes = details ?: guessHoles(pdga.holes, pdga.par),
                holesGuessed = false,
                parsEstimated = details == null,
            )
        }
        return addRounds(linked, candidate.rounds)
    }

    /** A layout based on a PDGA layout, calibrated with the event's rounds on it. */
    fun fromPdga(id: String, event: PdgaEvent, layout: PdgaLayoutInfo?): Layout {
        val holes = layout?.holeDetails?.takeIf { it.isNotEmpty() }
        val generated = holes == null
        return Layout(
            id = id,
            name = layout?.let { listOfNotNull(it.courseName, it.layoutName).joinToString(" - ") }
                ?.ifBlank { null } ?: event.name,
            holes = holes ?: guessHoles(layout?.holes ?: 18, layout?.par),
            pdgaLayouts = listOf(layout ?: PdgaLayoutInfo()),
            calibrationRounds = event.rounds.filter { (layout ?: PdgaLayoutInfo()).sameAs(it.layout) },
            source = "PDGA",
            parsEstimated = generated && layout?.par != null,
            courseName = layout?.courseName.orEmpty(),
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
    fun addMatchingRounds(layout: Layout, event: PdgaEvent): Pair<Layout, Int> =
        addRounds(layout, event.rounds.filter { layout.isLinkedTo(it.layout) })

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
