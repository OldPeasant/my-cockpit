package ch.sonensei.cockpit.gtd.task

import java.util.Locale

/** How a repeating task gets its next date (SPEC.md §5.2). */
enum class RecurrenceMode {
    /** On schedule: the next date follows the rule from the task's date. */
    FIXED,

    /** The next date follows the rule from the day the task was completed. */
    AFTER_COMPLETION,
}

/** The repeat editor's choices; [CUSTOM] keeps any other RFC 5545 rule as text. */
enum class RepeatPreset(
    /** The rule's `FREQ`, `null` for [NONE] and [CUSTOM]. */
    val freq: String?,
) {
    NONE(null),
    DAILY("DAILY"),
    WEEKDAYS("WEEKLY"),
    WEEKLY("WEEKLY"),
    MONTHLY("MONTHLY"),
    YEARLY("YEARLY"),
    CUSTOM(null),
    ;

    /** Whether the preset takes an interval ("every 2 weeks"). */
    val hasInterval: Boolean get() = freq != null
}

/** The repeat part of the edit form. [interval] and [custom] are the texts typed. */
data class RepeatDraft(
    val preset: RepeatPreset = RepeatPreset.NONE,
    val interval: String = "1",
    val mode: RecurrenceMode = RecurrenceMode.FIXED,
    val custom: String = "",
) {
    /** The rule sent to the server, `null` for no repeat (or a custom rule left empty). */
    fun rule(): String? {
        val freq = preset.freq
        if (freq == null) return if (preset == RepeatPreset.CUSTOM) normalizeRule(custom).ifEmpty { null } else null
        val every = interval.trim().toIntOrNull()?.takeIf { it > 1 }
        return buildList {
            add("FREQ=$freq")
            every?.let { add("INTERVAL=$it") }
            if (preset == RepeatPreset.WEEKDAYS) add("BYDAY=$WEEKDAYS")
        }.joinToString(";")
    }

    /** The mode sent with the rule: none without a rule (the server refuses one). */
    fun modeOrNull(): RecurrenceMode? = mode.takeIf { rule() != null }

    /** Whether this draft repeats the same way as [other] (same rule and mode), however the texts are typed. */
    fun sameAs(other: RepeatDraft): Boolean = rule() == other.rule() && modeOrNull() == other.modeOrNull()

    /** Why the draft cannot be sent, or `null`. */
    fun problem(): FieldProblem? =
        when {
            preset == RepeatPreset.CUSTOM && normalizeRule(custom).isEmpty() -> FieldProblem.Required
            preset == RepeatPreset.CUSTOM && !normalizeRule(custom).split(';').any { it.startsWith("FREQ=") } -> FieldProblem.Invalid
            preset.hasInterval && interval.trim().toIntOrNull()?.takeIf { it in 1..MAX_INTERVAL } == null -> FieldProblem.Invalid
            else -> null
        }

    companion object {
        const val MAX_INTERVAL = 999
        private const val WEEKDAYS = "MO,TU,WE,TH,FR"
        private val SIMPLE_KEYS = setOf("FREQ", "INTERVAL", "BYDAY")

        /** The editor showing [rule]: a preset when it is one (with any interval), otherwise the rule as custom text. */
        fun of(
            rule: String?,
            mode: RecurrenceMode?,
        ): RepeatDraft {
            val normalized = normalizeRule(rule.orEmpty())
            val chosenMode = mode ?: RecurrenceMode.FIXED
            if (normalized.isEmpty()) return RepeatDraft(mode = chosenMode)
            val parts = normalized.split(';').map { it.split('=', limit = 2) }
            val custom = RepeatDraft(RepeatPreset.CUSTOM, mode = chosenMode, custom = rule.orEmpty().trim())
            if (parts.any { it.size != 2 || it[0] !in SIMPLE_KEYS }) return custom
            val values = parts.associate { it[0] to it[1] }
            if (values.size != parts.size) return custom
            val interval = values["INTERVAL"]?.let { text -> text.toIntOrNull()?.takeIf { it >= 1 } ?: return custom } ?: 1
            val byDay = values["BYDAY"]
            val preset =
                when {
                    byDay != null -> RepeatPreset.WEEKDAYS.takeIf { values["FREQ"] == "WEEKLY" && byDay == WEEKDAYS }
                    else -> RepeatPreset.entries.firstOrNull { it != RepeatPreset.WEEKDAYS && it.freq != null && it.freq == values["FREQ"] }
                } ?: return custom
            return RepeatDraft(preset, interval.toString(), chosenMode)
        }

        /** [rule] trimmed, upper-cased and without empty parts, as the server stores it. */
        fun normalizeRule(rule: String): String =
            rule
                .trim()
                .uppercase(Locale.ROOT)
                .split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(";")
    }
}
