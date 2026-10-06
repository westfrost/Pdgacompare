package dk.pdgacompare.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.JsonArray
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.time.LocalDate

data class MetrixCourse(val name: String, val holes: List<Hole>, val lengthMeters: Int? = null)

private val METRIX_ARROWS = arrayOf("→", "->", "&rarr;")

/** Metrix names layouts "Course → Layout"; this is the course part. */
internal fun metrixCourseName(fullName: String): String = fullName.split(*METRIX_ARROWS).first().trim()

internal fun metrixDisplayName(fullName: String): String =
    fullName.split(*METRIX_ARROWS).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" - ")

/** A course or layout from the Metrix course list. */
data class MetrixCourseRef(
    val id: Long,
    val parentId: Long?,
    /** E.g. "Valby Park → Main". */
    val fullName: String,
    /** False for a course that has layouts below it. */
    val isLayout: Boolean,
    val city: String,
) {
    /** Course part of the name. Entries without a parent often carry the layout after a dash or comma. */
    val courseName: String
        get() = metrixCourseName(fullName).let { name ->
            if (parentId != null) name else name.split(" - ", " – ", ", ", " (").first().trim()
        }
    val displayName: String get() = metrixDisplayName(fullName)
}

/** Imports hole layouts (par and length) from Disc Golf Metrix. */
class MetrixClient internal constructor(private val http: OkHttpClient) {
    constructor() : this(defaultHttpClient())

    /**
     * [input] is a Metrix course URL (discgolfmetrix.com/course/ID), a competition URL
     * (discgolfmetrix.com/ID) or a bare id, which is tried as a course first.
     */
    suspend fun fetch(input: String, apiCode: String): MetrixCourse = withContext(Dispatchers.IO) {
        val ref = parseReference(input) ?: throw IOException("Not a Metrix course or competition: $input")
        val attempts = when {
            ref.isCourse == true -> listOf("course")
            ref.isCourse == false -> listOf("result")
            else -> listOf("course", "result")
        }
        var lastError: Exception? = null
        for (content in attempts) {
            try {
                val url = API.toHttpUrl().newBuilder()
                    .addQueryParameter("content", content)
                    .addQueryParameter("id", ref.id.toString())
                    .apply { if (apiCode.isNotBlank()) addQueryParameter("code", apiCode.trim()) }
                    .build()
                MetrixParser.parse(http.getText(url.toString()))?.let { return@withContext it }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("Metrix returned no holes for ${ref.id}" + (lastError?.message?.let { ": $it" } ?: ""), lastError)
    }

    private val courseLists = mutableMapOf<String, List<MetrixCourseRef>>()

    /**
     * Current layouts in [countryCode] matching [name], including the layouts of matching courses.
     * Metrix' own name filter misses layouts (their names lack the course name) and letters like ø,
     * so the country's whole list (a few hundred entries) is fetched once and filtered here.
     */
    suspend fun searchCourses(name: String, countryCode: String): List<MetrixCourseRef> = withContext(Dispatchers.IO) {
        val code = countryCode.trim().uppercase()
        val all = courseLists[code] ?: run {
            val url = "$API".toHttpUrl().newBuilder()
                .addQueryParameter("content", "courses_list")
                .addQueryParameter("country_code", code)
                .build()
            MetrixParser.parseCourseList(http.getText(url.toString()), LocalDate.now()).also { courseLists[code] = it }
        }
        MetrixParser.search(all, name)
    }

    /** Holes of a Metrix course or layout, read from its public page (the API needs a personal code). */
    suspend fun fetchCourseHoles(id: Long, apiCode: String): MetrixCourse = withContext(Dispatchers.IO) {
        val fromPage = runCatching { MetrixParser.parseCoursePage(http.getText("https://discgolfmetrix.com/course/$id")) }
        fromPage.getOrNull() ?: if (apiCode.isNotBlank()) fetch("https://discgolfmetrix.com/course/$id", apiCode)
        else throw IOException("Could not read the holes of Metrix course $id", fromPage.exceptionOrNull())
    }

    data class Reference(val id: Long, val isCourse: Boolean?)

    companion object {
        private const val API = "https://discgolfmetrix.com/api.php"

        fun parseReference(input: String): Reference? {
            val text = input.trim()
            Regex("course/(\\d+)").find(text)?.let { return Reference(it.groupValues[1].toLong(), true) }
            Regex("discgolfmetrix\\.com/(\\d+)").find(text)?.let { return Reference(it.groupValues[1].toLong(), false) }
            return text.toLongOrNull()?.let { Reference(it, null) }
        }
    }
}

object MetrixParser {
    private const val MIN_METERS_PER_HOLE = 25
    private val HOLE_KEYS = arrayOf("baskets", "Baskets", "holes", "Holes", "Tracks", "tracks")

    /** Returns null when the response contains no holes. */
    fun parse(json: String): MetrixCourse? {
        val root = lenientJson.parseToJsonElement(json).obj() ?: return null
        root.field("Errors", "Error", "error").str()?.let { throw IOException("Metrix: $it") }
        val containers = listOfNotNull(
            root,
            root.field("course", "Course").obj(),
            root.field("Competition", "competition").obj(),
        )
        val holes = containers.firstNotNullOfOrNull { c ->
            HOLE_KEYS.firstNotNullOfOrNull { key -> c[key].objects().takeIf { it.isNotEmpty() } }
        }?.mapIndexed { i, h -> hole(h, i) } ?: return null

        val name = containers.firstNotNullOfOrNull { it.field("Fullname", "CourseName", "Name").str() } ?: "Metrix course"
        return MetrixCourse(name, holes)
    }

    /** Parses `content=courses_list`, leaving out courses that had ended by [today]. */
    fun parseCourseList(json: String, today: LocalDate): List<MetrixCourseRef> {
        val root = lenientJson.parseToJsonElement(json)
        val items = if (root is JsonArray) root.objects() else root.obj()?.field("courses", "Courses", "data").objects()
        val refs = items.mapNotNull { c ->
            val id = c.field("ID", "Id").str()?.toLongOrNull() ?: return@mapNotNull null
            val ended = c.field("Enddate", "EndDate").str()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (ended != null && ended < today) return@mapNotNull null
            MetrixCourseRef(
                id = id,
                parentId = c.field("ParentID", "ParentId").str()?.toLongOrNull()?.takeIf { it > 0 },
                fullName = c.field("Fullname", "Name").str()?.let { Parser.unescapeEntities(it, false) } ?: return@mapNotNull null,
                isLayout = c.field("Type").str() != "1",
                city = c.field("City").str() ?: c.field("Area").str().orEmpty(),
            )
        }
        // Layouts often have no town of their own; use their course's.
        val byId = refs.associateBy { it.id }
        return refs.map { r -> if (r.city.isBlank()) r.copy(city = r.parentId?.let { byId[it]?.city }.orEmpty()) else r }
    }

    /** Layouts (and courses without layouts) whose name, or whose course's name, matches [query]. */
    fun search(all: List<MetrixCourseRef>, query: String): List<MetrixCourseRef> {
        val words = CourseMatch.searchWords(query)
        if (words.isEmpty()) return emptyList()
        fun matches(r: MetrixCourseRef) = CourseMatch.searchWords(r.fullName).let { name -> words.all { w -> name.any { it.contains(w) } } }
        val matchingCourses = all.filter { !it.isLayout && matches(it) }.map { it.id }.toSet()
        return all.filter { it.isLayout && (matches(it) || it.parentId in matchingCourses) }
            .sortedBy { it.fullName.lowercase() }
    }

    /** Reads hole pars from a course page (discgolfmetrix.com/course/ID): the first "Par" row of the scorecard. */
    fun parseCoursePage(html: String): MetrixCourse {
        val doc = Jsoup.parse(html)
        val name = doc.selectFirst("h1")?.text()?.trim().orEmpty().ifEmpty { "Metrix course" }
        val parRow = doc.selectFirst("tr.par") ?: throw IOException("No scorecard on the Metrix course page")
        val pars = parRow.select("td.center").mapNotNull { it.text().trim().toIntOrNull() }
        if (pars.isEmpty()) throw IOException("No hole pars on the Metrix course page")
        val length = Regex("Length:\\s*([\\d.,]+)\\s*m\\b").find(doc.text())?.groupValues?.get(1)?.replace(Regex("[.,]"), "")?.toIntOrNull()
            // Some courses carry nonsense like "18 baskets, 75m".
            ?.takeIf { it >= pars.size * MIN_METERS_PER_HOLE }
        return MetrixCourse(name, pars.mapIndexed { i, par -> Hole(i + 1, par) }, length)
    }

    private fun hole(h: JsonObject, index: Int) = Hole(
        number = h.field("Number", "Num").int() ?: (index + 1),
        par = h.field("Par").int() ?: 3,
        lengthMeters = h.field("Length", "Distance").int()?.takeIf { it > 0 },
    )
}
