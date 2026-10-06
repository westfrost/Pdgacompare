package dk.pdgacompare.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException

data class MetrixCourse(val name: String, val holes: List<Hole>)

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
                val url = "https://discgolfmetrix.com/api.php".toHttpUrl().newBuilder()
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

    data class Reference(val id: Long, val isCourse: Boolean?)

    companion object {
        fun parseReference(input: String): Reference? {
            val text = input.trim()
            Regex("course/(\\d+)").find(text)?.let { return Reference(it.groupValues[1].toLong(), true) }
            Regex("discgolfmetrix\\.com/(\\d+)").find(text)?.let { return Reference(it.groupValues[1].toLong(), false) }
            return text.toLongOrNull()?.let { Reference(it, null) }
        }
    }
}

object MetrixParser {
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

    private fun hole(h: JsonObject, index: Int) = Hole(
        number = h.field("Number", "Num").int() ?: (index + 1),
        par = h.field("Par").int() ?: 3,
        lengthMeters = h.field("Length", "Distance").int()?.takeIf { it > 0 },
    )
}
