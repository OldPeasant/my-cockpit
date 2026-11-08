package ch.sonensei.cockpit.gtd.next

import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.util.UUID

/** The due windows of the Next filter, as the server and the web app define them (`GET /api/v1/tasks?due=`). */
enum class DueWindow(
    val wire: String,
) {
    /** Due before today. */
    OVERDUE("overdue"),

    /** Due today. */
    TODAY("today"),

    /** Due today or in the six days after it. */
    WEEK("week"),
    ;

    fun contains(
        due: LocalDate,
        today: LocalDate,
    ): Boolean =
        when (this) {
            OVERDUE -> due < today
            TODAY -> due == today
            WEEK -> due >= today && due <= today.plusDays(WEEK_DAYS - 1)
        }

    companion object {
        private const val WEEK_DAYS = 7L

        fun of(wire: String?): DueWindow? = entries.firstOrNull { it.wire == wire }
    }
}

/** A saved filter of the Next Actions view, e.g. "Low energy @home ≤ 15 min". */
data class NextPreset(
    val name: String,
    val filter: NextFilter,
)

/**
 * The saved Next filters in the shared `nextActionPresets` setting: one JSON text `{"name": …, "filter": {…}}` each, in
 * the format of the web app (`filter-presets.ts`), so both show the same presets. Texts the app cannot read are kept
 * as they are when the list is written back.
 */
object NextPresets {
    /** The server's limits: at most 50 presets; the web app allows names of at most 80 characters. */
    const val MAX_PRESETS = 50
    const val MAX_NAME = 80

    private const val NAME = "name"
    private const val FILTER = "filter"
    private const val CONTEXT = "context"
    private const val ENERGY = "energy"
    private const val CREATIVITY = "creativity"
    private const val MAX_MINUTES = "maxMinutes"
    private const val AREA = "area"
    private const val PROJECT = "project"
    private const val DUE = "due"

    /** The readable presets of [texts], in their order. */
    fun read(texts: List<String>): List<NextPreset> = texts.mapNotNull(::parse)

    /** The preset in [text]; `null` if it has no name or is not JSON. Unknown or invalid filter values are left out. */
    fun parse(text: String): NextPreset? {
        val json =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: return null
        val name = json.text(NAME) ?: return null
        val filter = json[FILTER] as? JsonObject ?: JsonObject(emptyMap())
        return NextPreset(
            name,
            NextFilter(
                contextId = filter.uuid(CONTEXT),
                energy = filter.text(ENERGY)?.let { v -> Energy.entries.firstOrNull { it.name == v } },
                creativity = filter.text(CREATIVITY)?.let { v -> Creativity.entries.firstOrNull { it.name == v } },
                maxMinutes = filter.minutes(),
                areaId = filter.uuid(AREA),
                projectId = filter.uuid(PROJECT),
                due = DueWindow.of(filter.text(DUE)),
            ),
        )
    }

    /** [preset] as the web app writes it (`JSON.stringify`): keys in its order, empty fields left out. */
    fun encode(preset: NextPreset): String =
        buildJsonObject {
            put(NAME, JsonPrimitive(preset.name.trim()))
            put(
                FILTER,
                buildJsonObject {
                    val f = preset.filter
                    f.contextId?.let { put(CONTEXT, JsonPrimitive(it.toString())) }
                    f.energy?.let { put(ENERGY, JsonPrimitive(it.name)) }
                    f.creativity?.let { put(CREATIVITY, JsonPrimitive(it.name)) }
                    f.maxMinutes?.let { put(MAX_MINUTES, JsonPrimitive(it)) }
                    f.areaId?.let { put(AREA, JsonPrimitive(it.toString())) }
                    f.projectId?.let { put(PROJECT, JsonPrimitive(it.toString())) }
                    f.due?.let { put(DUE, JsonPrimitive(it.wire)) }
                },
            )
        }.toString()

    /** [texts] with [preset] saved: a preset of the same name (any case) is replaced in place, otherwise it is appended. */
    fun saved(
        texts: List<String>,
        preset: NextPreset,
    ): List<String> {
        val encoded = encode(preset)
        val index = texts.indexOfFirst { text -> parse(text)?.let { sameName(it.name, preset.name) } == true }
        return if (index >= 0) texts.toMutableList().apply { set(index, encoded) } else texts + encoded
    }

    /** [texts] without the presets called [name] (any case). */
    fun removed(
        texts: List<String>,
        name: String,
    ): List<String> = texts.filterNot { text -> parse(text)?.let { sameName(it.name, name) } == true }

    fun sameName(
        a: String,
        b: String,
    ): Boolean = a.trim().lowercase() == b.trim().lowercase()

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.uuid(key: String): UUID? =
        text(key)?.let {
            try {
                UUID.fromString(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    /** A positive whole number, as a JSON number or text (the web app reads both). */
    private fun JsonObject.minutes(): Int? {
        val value = this[MAX_MINUTES] as? JsonPrimitive ?: return null
        val minutes = if (value.isString) value.content.trim().toIntOrNull() else value.intOrNull
        return minutes?.takeIf { it > 0 }
    }
}
