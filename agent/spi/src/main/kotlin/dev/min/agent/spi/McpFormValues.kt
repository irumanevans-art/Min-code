package dev.min.agent.spi

import kotlinx.serialization.json.*
import java.net.URI
import java.time.LocalDate
import java.time.OffsetDateTime

/** CLI 与内核使用同一套字段校验，避免终端收下的值到了服务器边界才整份作废。 */
fun mcpFormValue(schema: JsonObject, answer: QuestionAnswer): JsonElement? {
    if (schema["type"]?.jsonPrimitive?.content == "array") {
        if (answer.other != null || answer.selected.toSet().size != answer.selected.size) return null
        val items = schema["items"] as? JsonObject ?: return null
        val values = answer.selected.map { mcpFormValue(items, QuestionAnswer(other=it)) ?: return null }
        if (values.size < (schema["minItems"]?.jsonPrimitive?.intOrNull ?: 0) || values.size > (schema["maxItems"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE)) return null
        return JsonArray(values)
    }
    val text = answer.other ?: answer.selected.singleOrNull() ?: return null
    val value = when (schema["type"]?.jsonPrimitive?.content) {
        "string" -> JsonPrimitive(text)
        "integer" -> JsonPrimitive(text.toLongOrNull() ?: return null)
        "number" -> JsonPrimitive(text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null)
        "boolean" -> JsonPrimitive(text.toBooleanStrictOrNull() ?: return null)
        else -> return null
    }
    val enums = schema["enum"] as? JsonArray
    val matched = enums?.firstOrNull { it == value || (!value.isString && it is JsonPrimitive && !it.isString && it.doubleOrNull == value.doubleOrNull) }
    if (enums != null && matched == null) return null
    val number = if (value.isString) null else value.doubleOrNull
    if (number != null && (number < (schema["minimum"]?.jsonPrimitive?.doubleOrNull ?: Double.NEGATIVE_INFINITY) || number > (schema["maximum"]?.jsonPrimitive?.doubleOrNull ?: Double.POSITIVE_INFINITY))) return null
    if (value.isString) {
        if (text.length < (schema["minLength"]?.jsonPrimitive?.intOrNull ?: 0) || text.length > (schema["maxLength"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE)) return null
        val valid = when (schema["format"]?.jsonPrimitive?.content) {
            null -> true
            "email" -> Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(text)
            "uri" -> catchingNonCancellation { URI(text).isAbsolute }.getOrDefault(false)
            "date" -> Regex("\\d{4}-\\d{2}-\\d{2}").matches(text) && catchingNonCancellation { LocalDate.parse(text) }.isSuccess
            "date-time" -> catchingNonCancellation { OffsetDateTime.parse(text) }.isSuccess
            else -> false
        }
        if (!valid) return null
    }
    return matched ?: value
}
