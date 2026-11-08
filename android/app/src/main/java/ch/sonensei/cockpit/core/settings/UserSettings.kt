package ch.sonensei.cockpit.core.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.ZoneId

/** The `theme` setting: light, dark, or the system's choice. */
enum class ThemeSetting(
    val wire: String,
) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system"),
    ;

    companion object {
        fun of(wire: String?): ThemeSetting? = entries.firstOrNull { it.wire == wire }
    }
}

/** The `mailImagePolicy` setting: whether remote images in mails load without asking. */
enum class MailImagePolicy(
    val wire: String,
) {
    BLOCK("block"),
    ALLOW("allow"),
    ;

    companion object {
        fun of(wire: String?): MailImagePolicy? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The synced user settings of `GET /api/v1/settings` (SPEC.md §4.7) the app uses. A value the server sends that the app
 * does not understand falls back to the default, so a newer server never breaks the app.
 */
data class UserSettings(
    val theme: ThemeSetting = ThemeSetting.SYSTEM,
    /** `null`: not known, the device locale decides. */
    val weekStart: DayOfWeek? = null,
    /** `null`: not known, the device's zone is used. */
    val timeZone: ZoneId? = null,
    /** Labels of LOW, MEDIUM, HIGH as the user named them; missing or default labels are not in the map. */
    val energyLabels: Map<String, String> = emptyMap(),
    /** Labels of ROUTINE, MIXED, CREATIVE as the user named them; missing or default labels are not in the map. */
    val creativityLabels: Map<String, String> = emptyMap(),
    val mailImagePolicy: MailImagePolicy = MailImagePolicy.BLOCK,
    val mailImageAllowedSenders: List<String> = emptyList(),
) {
    companion object {
        const val THEME = "theme"
        const val WEEK_START = "weekStart"
        const val TIME_ZONE = "timeZone"
        const val ENERGY_LABELS = "energyLabels"
        const val CREATIVITY_LABELS = "creativityLabels"
        const val MAIL_IMAGE_POLICY = "mailImagePolicy"
        const val MAIL_IMAGE_ALLOWED_SENDERS = "mailImageAllowedSenders"

        /**
         * The server's English default labels (`CoreSettings` on the server). A label equal to its default counts as
         * not renamed, so the app shows its own translation instead of the English word.
         */
        val DEFAULT_ENERGY_LABELS = mapOf("LOW" to "Low", "MEDIUM" to "Medium", "HIGH" to "High")
        val DEFAULT_CREATIVITY_LABELS = mapOf("ROUTINE" to "Routine", "MIXED" to "Mixed", "CREATIVE" to "Creative")

        fun parse(json: JsonObject): UserSettings =
            UserSettings(
                theme = ThemeSetting.of(json.string(THEME)) ?: ThemeSetting.SYSTEM,
                weekStart = json.string(WEEK_START)?.let { name -> DayOfWeek.entries.firstOrNull { it.name == name } },
                timeZone = json.string(TIME_ZONE)?.let(::zoneOrNull),
                energyLabels = labels(json[ENERGY_LABELS], DEFAULT_ENERGY_LABELS),
                creativityLabels = labels(json[CREATIVITY_LABELS], DEFAULT_CREATIVITY_LABELS),
                mailImagePolicy = MailImagePolicy.of(json.string(MAIL_IMAGE_POLICY)) ?: MailImagePolicy.BLOCK,
                mailImageAllowedSenders =
                    (json[MAIL_IMAGE_ALLOWED_SENDERS] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty(),
            )

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun zoneOrNull(id: String): ZoneId? =
            try {
                ZoneId.of(id)
            } catch (_: DateTimeException) {
                null
            }

        private fun labels(
            element: JsonElement?,
            defaults: Map<String, String>,
        ): Map<String, String> {
            val obj = element as? JsonObject ?: return emptyMap()
            return defaults.keys
                .mapNotNull { key ->
                    val label = obj.string(key)?.trim()
                    if (label.isNullOrEmpty() || label == defaults[key]) null else key to label
                }.toMap()
        }
    }
}

/**
 * A typed change for `PATCH /api/v1/settings`: only the non-null fields are sent, the others stay as they are on the
 * server. Only what the app may change is offered (time zone, week start and labels are edited in the web app).
 */
data class UserSettingsChange(
    val theme: ThemeSetting? = null,
    val mailImagePolicy: MailImagePolicy? = null,
    val mailImageAllowedSenders: List<String>? = null,
) {
    val isEmpty: Boolean get() = theme == null && mailImagePolicy == null && mailImageAllowedSenders == null

    fun toJson(): JsonObject =
        buildJsonObject {
            theme?.let { put(UserSettings.THEME, JsonPrimitive(it.wire)) }
            mailImagePolicy?.let { put(UserSettings.MAIL_IMAGE_POLICY, JsonPrimitive(it.wire)) }
            mailImageAllowedSenders?.let { senders ->
                put(UserSettings.MAIL_IMAGE_ALLOWED_SENDERS, JsonArray(senders.map(::JsonPrimitive)))
            }
        }
}
