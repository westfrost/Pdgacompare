package dk.pdgacompare.core

import kotlinx.serialization.json.JsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.math.roundToInt

data class PdgaEvent(
    val id: Long,
    val name: String,
    /** ISO date (yyyy-MM-dd), empty if unknown. */
    val startDate: String,
    val rounds: List<CalibrationRound>,
) {
    /** Distinct layouts played in the event, each with its rounds. */
    val layouts: List<Pair<PdgaLayoutInfo?, List<CalibrationRound>>>
        get() = rounds.groupBy { it.layout?.key }.values.map { it.first().layout to it }
}

/** One row of the PDGA event search. */
data class PdgaEventSummary(
    val id: Long,
    val name: String,
    /** ISO date, empty if unknown. */
    val date: String,
    val location: String,
)

/** Parses PDGA event pages (www.pdga.com/tour/event/ID) and PDGA Live JSON. */
object PdgaParser {

    private data class Row(val round: Int, val layout: PdgaLayoutInfo?, val result: RatedResult)

    // ---- Event results page (HTML) -----------------------------------------------------------

    fun parseEventHtml(html: String, eventId: Long): PdgaEvent {
        val doc = Jsoup.parse(html)
        val name = doc.selectFirst("h1")?.text()?.trim().orEmpty().ifEmpty { "PDGA event $eventId" }
        val dateText = doc.selectFirst("li.tournament-date")?.text()
            ?: Regex("Date:?\\s*([^\\n]{0,60})").find(doc.text())?.groupValues?.get(1)
        val startDate = parseEventDate(dateText.orEmpty())
        val pageLayouts = findLayoutTexts(doc)

        val rows = mutableListOf<Row>()
        for (table in doc.select("table.results")) {
            val headers = table.select("thead tr").lastOrNull()?.select("th").orEmpty().filter(::isRoundCell)
            val roundNumbers = headers.mapIndexed { i, th ->
                Regex("(\\d+)").find(th.text())?.groupValues?.get(1)?.toIntOrNull() ?: (i + 1)
            }
            val layouts = headers.mapIndexed { i, th ->
                layoutFromHeader(doc, th) ?: layoutForRound(pageLayouts, roundNumbers[i])
            }

            for (tr in table.select("tbody tr")) {
                val scores = mutableMapOf<Int, Int>()
                val ratings = mutableMapOf<Int, Int>()
                var index = -1
                for (td in tr.select("td")) {
                    when {
                        isRoundCell(td) -> {
                            index++
                            td.text().trim().toIntOrNull()?.let { scores[index] = it }
                        }
                        td.hasClass("round-rating") && index >= 0 ->
                            td.text().trim().toIntOrNull()?.let { ratings[index] = it }
                    }
                }
                for ((i, score) in scores) {
                    val rating = ratings[i] ?: continue
                    if (score <= 0 || score >= 500 || rating <= 0) continue
                    val round = roundNumbers.getOrNull(i) ?: (i + 1)
                    val layout = layouts.getOrNull(i) ?: layoutForRound(pageLayouts, round)
                    rows += Row(round, layout, RatedResult(score, rating))
                }
            }
        }
        return PdgaEvent(eventId, name, startDate, group(eventId, name, startDate, rows))
    }

    private fun isRoundCell(e: Element) = e.hasClass("round") && !e.hasClass("round-rating")

    private fun layoutFromHeader(doc: Document, th: Element): PdgaLayoutInfo? {
        val candidates = buildList {
            add(th.attr("title"))
            add(th.attr("data-original-title"))
            add(th.attr("aria-label"))
            val ref = th.attr("data-tooltip-content")
            if (ref.startsWith("#")) doc.getElementById(ref.substring(1))?.let { add(it.text()) } else add(ref)
            th.select("[title]").forEach { add(it.attr("title")) }
            add(th.text())
        }
        return candidates.firstNotNullOfOrNull { parseLayoutText(it) }
    }

    private fun layoutForRound(pageLayouts: List<Pair<Int?, PdgaLayoutInfo>>, round: Int): PdgaLayoutInfo? =
        pageLayouts.firstOrNull { it.first == round }?.second
            ?: pageLayouts.map { it.second }.distinctBy { it.key }.singleOrNull()

    /** Layout descriptions anywhere on the page, with the round they mention (if any). */
    private fun findLayoutTexts(doc: Document): List<Pair<Int?, PdgaLayoutInfo>> {
        fun looksLikeLayout(text: String) = text.length < 400 && HOLES.containsMatchIn(text) && PAR.containsMatchIn(text)
        return doc.body().select("*")
            .filter { el -> looksLikeLayout(el.text()) && el.children().none { looksLikeLayout(it.text()) } }
            .mapNotNull { el ->
                val info = parseLayoutText(el.text()) ?: return@mapNotNull null
                val context = el.text() + " " + el.previousElementSibling()?.text().orEmpty()
                ROUND.find(context)?.groupValues?.get(1)?.toIntOrNull() to info
            }
    }

    private val HOLES = Regex("(\\d+)\\s*holes?", RegexOption.IGNORE_CASE)
    private val PAR = Regex("\\bpar\\s*:?\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val LENGTH = Regex("(\\d[\\d.,]*)\\s*(ft|feet|foot|m|meters|metres)\\b", RegexOption.IGNORE_CASE)
    private val ROUND = Regex("\\b(?:Rd|Round|R)\\s*(\\d+)\\b", RegexOption.IGNORE_CASE)

    /**
     * Parses texts like "Course: Valby Park - Layout: Main 18; 18 holes; Par 57; 5,980 ft."
     * Returns null when the text mentions neither holes nor par.
     */
    fun parseLayoutText(raw: String): PdgaLayoutInfo? {
        val text = raw.replace(' ', ' ').trim()
        val holes = HOLES.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val par = PAR.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (holes == null && par == null) return null
        val length = LENGTH.find(text)?.let { m ->
            val value = m.groupValues[1].replace(Regex("[.,]"), "").toIntOrNull() ?: return@let null
            if (m.groupValues[2].lowercase().startsWith("f")) (value * FEET_TO_METERS).roundToInt() else value
        }

        val firstFact = listOfNotNull(HOLES.find(text)?.range?.first, PAR.find(text)?.range?.first).min()
        var namePart = text.substring(0, firstFact).substringBefore(';').trim()
        namePart = namePart.replace(Regex("^\\s*(?:Rd|Round|R)\\s*\\d+\\s*[:\\-–]?\\s*", RegexOption.IGNORE_CASE), "")
        namePart = namePart.trim(' ', ';', ',', '-', '–', '·', ':')

        val course: String?
        val layout: String?
        val layoutLabel = Regex("Layout\\s*:", RegexOption.IGNORE_CASE).find(namePart)
        if (layoutLabel != null) {
            course = namePart.substring(0, layoutLabel.range.first)
            layout = namePart.substring(layoutLabel.range.last + 1)
        } else {
            val parts = namePart.split(Regex("\\s+[-–]\\s+"), limit = 2)
            course = parts[0]
            layout = parts.getOrNull(1)
        }
        fun clean(s: String?) = s
            ?.replace(Regex("Course\\s*:", RegexOption.IGNORE_CASE), "")
            ?.trim(' ', ';', ',', '-', '–', '·', ':')
            ?.takeIf { it.isNotEmpty() }

        return PdgaLayoutInfo(clean(course), clean(layout), holes, par, length)
    }

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** Parses PDGA date texts like "13-Apr to 15-Apr-2024" or "30-Sep-2023 to 01-Oct-2023" (start date). */
    fun parseEventDate(text: String): String {
        val iso = Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(text)
        if (iso != null) return iso.value
        val matches = Regex("(\\d{1,2})[- ]([A-Za-z]{3})[A-Za-z]*(?:[- ,]+(\\d{4}))?").findAll(text).toList()
        val first = matches.firstOrNull() ?: return ""
        val month = MONTHS.indexOf(first.groupValues[2].lowercase()) + 1
        if (month == 0) return ""
        val year = matches.firstNotNullOfOrNull { it.groupValues[3].takeIf(String::isNotEmpty) }
            ?: Regex("(\\d{4})").find(text)?.value
            ?: return ""
        return "%s-%02d-%02d".format(year, month, first.groupValues[1].toInt())
    }

    // ---- Event search (www.pdga.com/tour/search) ---------------------------------------------

    fun parseEventSearch(html: String): List<PdgaEventSummary> {
        val doc = Jsoup.parse(html)
        return doc.select("a[href*=/tour/event/]").mapNotNull { link ->
            val id = Regex("/tour/event/(\\d+)").find(link.attr("href"))?.groupValues?.get(1)?.toLongOrNull()
                ?: return@mapNotNull null
            val row = link.closest("tr") ?: link.parent() ?: return@mapNotNull null
            val dateText = row.selectFirst("td[class*=date], td[class*=Date]")?.text() ?: row.text()
            PdgaEventSummary(
                id = id,
                name = link.text().trim(),
                date = parseEventDate(dateText),
                location = row.selectFirst("td[class*=ocation]")?.text()?.trim() ?: row.text(),
            )
        }.filter { it.name.isNotEmpty() }.distinctBy { it.id }
    }

    // ---- PDGA Live (JSON) --------------------------------------------------------------------

    data class LiveEvent(val name: String?, val startDate: String, val divisions: List<String>, val rounds: Int?)

    fun parseLiveEvent(json: String): LiveEvent {
        val root = lenientJson.parseToJsonElement(json).obj() ?: error("Unexpected PDGA Live response")
        val data = root.field("data", "Data").obj() ?: root
        val divisionObjects = data.field("Divisions", "divisions").objects()
        val divisions = divisionObjects.mapNotNull { it.field("Division", "DivisionCode", "Code").str() }.distinct()
        val rounds = data.field("Rounds", "RoundCount", "NumRounds").int()
            ?: divisionObjects.mapNotNull { it.field("LatestRound", "Rounds").int() }.maxOrNull()
        return LiveEvent(
            name = data.field("Name", "TournamentName", "EventName").str(),
            startDate = parseEventDate(data.field("StartDate", "DateStart", "StartDt", "Date").str().orEmpty()),
            divisions = divisions,
            rounds = rounds,
        )
    }

    /** Rated results of one division in one round, each with the layout it was played on. */
    fun parseLiveRound(json: String): List<Pair<PdgaLayoutInfo?, RatedResult>> {
        val root = lenientJson.parseToJsonElement(json).obj() ?: return emptyList()
        val data = root.field("data", "Data").obj() ?: root
        val layoutObjects = data.field("layouts", "Layouts").objects()
        val layouts = layoutObjects.associate { (it.field("LayoutID", "LayoutId", "id").str() ?: "") to liveLayout(it) }
        val single = layouts.values.singleOrNull()

        return data.field("scores", "Scores").objects().mapNotNull { s ->
            val score = s.field("RoundScore", "Score").int() ?: return@mapNotNull null
            val rating = s.field("RoundRating").int() ?: return@mapNotNull null
            if (score <= 0 || score >= 500 || rating <= 0) return@mapNotNull null
            val layout = s.field("LayoutID", "LayoutId").str()?.let { layouts[it] } ?: single
            val played = s.field("Completed", "Played", "HolesPlayed").int()
            if (played != null && layout?.holes != null && played < layout.holes) return@mapNotNull null
            layout to RatedResult(score, rating)
        }
    }

    private fun liveLayout(o: JsonObject): PdgaLayoutInfo {
        val inMeters = o.field("Units").str()?.lowercase()?.startsWith("m") == true
        fun meters(v: Int?) = v?.let { if (inMeters) it else (it * FEET_TO_METERS).roundToInt() }
        val details = o.field("Detail", "Details", "HoleDetail").objects().mapIndexed { i, h ->
            Hole(
                number = h.field("HoleOrdinal", "Ordinal").int() ?: (i + 1),
                par = h.field("Par").int() ?: 3,
                lengthMeters = meters(h.field("Length").int()),
            )
        }
        return PdgaLayoutInfo(
            courseName = o.field("CourseName", "Course").str(),
            layoutName = o.field("Name", "LayoutName").str(),
            holes = o.field("Holes", "HoleCount").int() ?: details.size.takeIf { it > 0 },
            par = o.field("Par").int() ?: details.takeIf { it.isNotEmpty() }?.sumOf { it.par },
            lengthMeters = meters(o.field("Length").int())
                ?: details.takeIf { d -> d.isNotEmpty() && d.all { it.lengthMeters != null } }?.sumOf { it.lengthMeters!! },
            holeDetails = details,
        )
    }

    fun buildLiveEvent(
        eventId: Long,
        event: LiveEvent,
        rounds: List<Pair<Int, List<Pair<PdgaLayoutInfo?, RatedResult>>>>,
    ): PdgaEvent {
        val name = event.name ?: "PDGA event $eventId"
        val rows = rounds.flatMap { (round, results) -> results.map { Row(round, it.first, it.second) } }
        return PdgaEvent(eventId, name, event.startDate, group(eventId, name, event.startDate, rows))
    }

    // ---- Shared ------------------------------------------------------------------------------

    private fun group(eventId: Long, name: String, date: String, rows: List<Row>): List<CalibrationRound> =
        rows.groupBy { it.round to it.layout?.key }
            .map { (key, group) ->
                // Prefer the most detailed description of the layout.
                val layout = group.mapNotNull { it.layout }.maxByOrNull { it.holeDetails.size }
                CalibrationRound(eventId, name, date, key.first, layout, group.map { it.result })
            }
            .sortedBy { it.round }

    /** Extracts PDGA event ids from pasted text: URLs or bare numbers, separated by anything. */
    fun parseEventIds(text: String): List<Long> =
        Regex("(?:event/|eventId=|TournID=)(\\d+)|\\b(\\d{3,7})\\b", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNull { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toLongOrNull() }
            .distinct()
            .toList()

    private const val FEET_TO_METERS = 0.3048
}
