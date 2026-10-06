package dk.pdgacompare.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Lenient accessors for third-party JSON whose exact shape (key casing, numbers as strings) varies.

internal val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun JsonObject.field(vararg names: String): JsonElement? {
    for (name in names) {
        val value = this[name]
        if (value != null && value !is JsonNull) return value
    }
    val wanted = names.map { it.lowercase() }.toSet()
    return entries.firstOrNull { it.key.lowercase() in wanted && it.value !is JsonNull }?.value
}

internal fun JsonElement?.str(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

internal fun JsonElement?.int(): Int? = str()?.replace(",", "")?.toDoubleOrNull()?.toInt()

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

/** Arrays, or objects used as maps (`{"1": {...}, "2": {...}}`), as a list of objects. */
internal fun JsonElement?.objects(): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> values.mapNotNull { it as? JsonObject }
    else -> emptyList()
}
