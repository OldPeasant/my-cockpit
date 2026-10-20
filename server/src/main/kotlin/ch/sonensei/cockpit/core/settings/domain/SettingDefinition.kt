package ch.sonensei.cockpit.core.settings.domain

import java.time.DayOfWeek
import java.time.ZoneId

/**
 * One user setting (SPEC.md §4.7): its [key], the [type] that validates values sent by clients, and the [default]
 * used while the owner has not changed it. Modules declare their own settings as beans (or call
 * [SettingsService.register]) and read them type-safely with [SettingsService.get]:
 *
 * ```
 * @Bean fun calendarDefaultView() = SettingDefinition("calendarDefaultView", SettingType.OneOf("day", "week"), "week", "...")
 * ```
 */
class SettingDefinition<T : Any>(
	val key: String,
	val type: SettingType<T>,
	val default: T,
	val description: String,
) {
	init {
		require(KEY.matches(key)) { "Setting keys are lowerCamelCase: $key" }
		require(type.parse(default) == default) { "The default of setting $key is not a valid value" }
	}

	override fun toString(): String = "SettingDefinition($key)"

	private companion object {
		val KEY = Regex("[a-z][A-Za-z0-9]{0,63}")
	}
}

/**
 * The value type of a setting. [parse] turns a JSON-decoded value (String, Boolean, Number, List, Map) into a valid
 * value or returns `null`; [expected] describes valid values for the 400 response. Values are plain JSON types, so
 * they are stored and returned as they are.
 */
sealed class SettingType<T : Any>(
	val expected: String,
) {
	abstract fun parse(value: Any?): T?

	/** Free text up to [maxLength] characters. */
	class Text(
		private val maxLength: Int = 200,
	) : SettingType<String>("a string of at most $maxLength characters") {
		override fun parse(value: Any?): String? = (value as? String)?.takeIf { it.length <= maxLength }
	}

	data object Flag : SettingType<Boolean>("true or false") {
		override fun parse(value: Any?): Boolean? = value as? Boolean
	}

	/** A whole number in [range]. */
	class WholeNumber(
		private val range: IntRange,
	) : SettingType<Int>("a whole number from ${range.first} to ${range.last}") {
		override fun parse(value: Any?): Int? =
			when (value) {
				is Int, is Long, is Short, is Byte -> (value as Number).toLong()
				is java.math.BigInteger -> value.takeIf { it.bitLength() < 64 }?.toLong()
				else -> null
			}?.takeIf { it in range.first..range.last }?.toInt()
	}

	/** One of a fixed set of string values. */
	class OneOf(
		val values: List<String>,
	) : SettingType<String>("one of ${values.joinToString(", ")}") {
		constructor(vararg values: String) : this(values.toList())

		override fun parse(value: Any?): String? = (value as? String)?.takeIf { it in values }
	}

	/** An IANA time zone id such as `Europe/Zurich`. */
	data object TimeZone : SettingType<String>("an IANA time zone id such as Europe/Zurich") {
		private val ids = ZoneId.getAvailableZoneIds()

		override fun parse(value: Any?): String? = (value as? String)?.takeIf { it in ids }
	}

	/** A display label for each of a fixed set of [keys] (e.g. energy `LOW`/`MEDIUM`/`HIGH`); all keys are required. */
	class Labels(
		val keys: List<String>,
		private val maxLength: Int = 40,
	) : SettingType<Map<String, String>>(
			"an object with a non-blank label of at most $maxLength characters for each of ${keys.joinToString(", ")}",
		) {
		override fun parse(value: Any?): Map<String, String>? {
			val map = value as? Map<*, *> ?: return null
			if (map.keys != keys.toSet()) return null
			val labels = keys.associateWith { (map[it] as? String)?.trim() }
			if (labels.values.any { it.isNullOrEmpty() || it.length > maxLength }) return null
			return labels.mapValues { it.value!! }
		}
	}

	/** A list of up to [maxItems] distinct non-blank strings of at most [maxLength] characters. */
	class TextList(
		private val maxItems: Int = 200,
		private val maxLength: Int = 200,
	) : SettingType<List<String>>("a list of at most $maxItems distinct non-blank strings of at most $maxLength characters") {
		override fun parse(value: Any?): List<String>? {
			val list = value as? List<*> ?: return null
			if (list.size > maxItems) return null
			val items = list.map { (it as? String)?.trim() }
			if (items.any { it.isNullOrEmpty() || it.length > maxLength }) return null
			return items.filterNotNull().takeIf { it.distinct().size == it.size }
		}
	}
}

/** The settings of the core module (SPEC.md §4.7). Other modules register theirs the same way. */
object CoreSettings {
	val TIME_ZONE =
		SettingDefinition("timeZone", SettingType.TimeZone, "Europe/Zurich", "IANA time zone for dates, reminders and daily jobs")
	val WEEK_START =
		SettingDefinition(
			"weekStart",
			SettingType.OneOf(DayOfWeek.entries.map { it.name }),
			DayOfWeek.MONDAY.name,
			"First day of the week in calendars and date pickers",
		)
	val THEME = SettingDefinition("theme", SettingType.OneOf("light", "dark", "system"), "system", "Color theme of the clients")
	val ENERGY_LABELS =
		SettingDefinition(
			"energyLabels",
			SettingType.Labels(listOf("LOW", "MEDIUM", "HIGH")),
			mapOf("LOW" to "Low", "MEDIUM" to "Medium", "HIGH" to "High"),
			"Display labels of the task energy levels",
		)
	val CREATIVITY_LABELS =
		SettingDefinition(
			"creativityLabels",
			SettingType.Labels(listOf("ROUTINE", "MIXED", "CREATIVE")),
			mapOf("ROUTINE" to "Routine", "MIXED" to "Mixed", "CREATIVE" to "Creative"),
			"Display labels of the task creativity levels",
		)
	val MAIL_IMAGE_POLICY =
		SettingDefinition(
			"mailImagePolicy",
			SettingType.OneOf("block", "allow"),
			"block",
			"Remote images in mail: blocked (load on request) or always loaded",
		)
	val MAIL_IMAGE_ALLOWED_SENDERS =
		SettingDefinition(
			"mailImageAllowedSenders",
			SettingType.TextList(maxItems = 1000, maxLength = 320),
			emptyList(),
			"Sender addresses or domains whose remote images are always loaded",
		)

	val ALL: List<SettingDefinition<*>> =
		listOf(TIME_ZONE, WEEK_START, THEME, ENERGY_LABELS, CREATIVITY_LABELS, MAIL_IMAGE_POLICY, MAIL_IMAGE_ALLOWED_SENDERS)
}
