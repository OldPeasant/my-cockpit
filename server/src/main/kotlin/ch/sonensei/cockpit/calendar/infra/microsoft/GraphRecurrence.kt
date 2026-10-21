package ch.sonensei.cockpit.calendar.infra.microsoft

import ch.sonensei.cockpit.calendar.api.Timing
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Translates between Graph's `patternedRecurrence` and RRULEs (RFC 5545), for the patterns Graph has: daily, weekly,
 * absolute/relative monthly and yearly, each with an interval, ending on a date, after a number of occurrences or never.
 *
 * - Relative patterns read as `BYDAY=2MO` (one day) or `BYDAY=MO,TU;BYSETPOS=2` (several); `last` is -1.
 * - `endDate` (a date, inclusive) reads as UNTIL: the date for all-day series, the end of that day in the series' zone
 *   (as UTC) for timed ones. Written back, an UNTIL becomes the date of the last occurrence it allows.
 * - A rule Graph cannot express (other frequencies, BYHOUR, several BYMONTHDAYs, ...) is refused with an
 *   [IllegalArgumentException] when written; `DAILY;BYDAY=...` becomes a weekly pattern.
 */
internal object GraphRecurrence {
	private val UTC_BASIC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
	private val LOCAL_BASIC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
	private val INDEX = mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "last" to -1)
	private val DAYS = DayOfWeek.entries.associateBy { it.name.lowercase() }
	private val SUPPORTED = setOf("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "BYMONTH", "BYSETPOS", "COUNT", "UNTIL", "WKST")

	/**
	 * The RRULE of [recurrence] for a series with [timing]; null without one.
	 *
	 * @throws IllegalArgumentException for a pattern that cannot be read
	 */
	fun toRule(
		recurrence: JsonNode,
		timing: Timing,
	): String? {
		if (!recurrence.isObject) return null
		val pattern = recurrence.path("pattern")
		val range = recurrence.path("range")
		val parts = LinkedHashMap<String, String>()
		val interval = pattern.path("interval").asInt(1).coerceAtLeast(1)
		val days = pattern.path("daysOfWeek").mapNotNull { DAYS[it.asString("").lowercase()] }
		val index = INDEX[pattern.path("index").asString("first").lowercase()] ?: 1
		val month = pattern.path("month").asInt(0)
		val dayOfMonth = pattern.path("dayOfMonth").asInt(0)

		fun relative() {
			require(days.isNotEmpty()) { "A relative recurrence without days" }
			if (days.size == 1) {
				parts["BYDAY"] = "$index${code(days.single())}"
			} else {
				parts["BYDAY"] = days.joinToString(",") { code(it) }
				parts["BYSETPOS"] = index.toString()
			}
		}
		when (val type = pattern.path("type").asString("")) {
			"daily" -> parts["FREQ"] = "DAILY"
			"weekly" -> {
				parts["FREQ"] = "WEEKLY"
				if (days.isNotEmpty()) parts["BYDAY"] = days.joinToString(",") { code(it) }
				val weekStart = DAYS[pattern.path("firstDayOfWeek").asString("").lowercase()]
				if (interval > 1 && weekStart != null && weekStart != DayOfWeek.MONDAY) parts["WKST"] = code(weekStart)
			}
			"absoluteMonthly" -> {
				parts["FREQ"] = "MONTHLY"
				require(dayOfMonth in 1..31) { "A monthly recurrence without a day" }
				parts["BYMONTHDAY"] = dayOfMonth.toString()
			}
			"relativeMonthly" -> {
				parts["FREQ"] = "MONTHLY"
				relative()
			}
			"absoluteYearly" -> {
				parts["FREQ"] = "YEARLY"
				require(month in 1..12 && dayOfMonth in 1..31) { "A yearly recurrence without a date" }
				parts["BYMONTH"] = month.toString()
				parts["BYMONTHDAY"] = dayOfMonth.toString()
			}
			"relativeYearly" -> {
				parts["FREQ"] = "YEARLY"
				require(month in 1..12) { "A yearly recurrence without a month" }
				parts["BYMONTH"] = month.toString()
				relative()
			}
			else -> throw IllegalArgumentException("Unknown recurrence pattern '$type'")
		}
		if (interval > 1) parts["INTERVAL"] = interval.toString()
		when (range.path("type").asString("noEnd")) {
			"endDate" -> {
				val end = LocalDate.parse(range.path("endDate").asString(""))
				parts["UNTIL"] =
					when (timing) {
						is Timing.AllDay -> DateTimeFormatter.BASIC_ISO_DATE.format(end)
						is Timing.Timed ->
							UTC_BASIC.format(
								end
									.atTime(LocalTime.MAX)
									.withNano(0)
									.atZone(timing.zone)
									.toInstant(),
							)
					}
			}
			"numbered" ->
				parts["COUNT"] =
					range
						.path("numberOfOccurrences")
						.asInt(1)
						.coerceAtLeast(1)
						.toString()
		}
		return parts.entries.joinToString(";") { "${it.key}=${it.value}" }
	}

	/**
	 * [rule] as a `patternedRecurrence` for a series with [timing]; [zoneName] is the series' zone as Graph names it and
	 * [zone] the zone its times are sent in (the series' zone, or UTC for a fixed offset Graph has no name for).
	 *
	 * Graph reads the pattern in the zone of the times it gets: when that start falls on another day than in the
	 * series' zone (a fixed offset sent in UTC), the pattern moves by that day. The range starts on the day Graph gets,
	 * the days of the week (and the week's first day) move with it, and a day of the month moves when it stays within
	 * the 1st to the 28th (else the day before or after does not exist in every month). A relative pattern (`2TU`,
	 * `BYSETPOS`) has no such day and is refused.
	 *
	 * @throws IllegalArgumentException when Graph cannot express [rule]
	 */
	fun toRecurrence(
		json: JsonMapper,
		rule: String,
		timing: Timing,
		zoneName: String,
		zone: ZoneId = (timing as? Timing.Timed)?.zone ?: ZoneOffset.UTC,
	): ObjectNode {
		val parts =
			rule
				.removePrefix("RRULE:")
				.split(';')
				.filter { it.isNotBlank() }
				.associate { it.substringBefore('=').trim().uppercase() to it.substringAfter('=', "").trim().uppercase() }
		val unsupported = parts.keys - SUPPORTED
		require(unsupported.isEmpty()) { "Microsoft calendars cannot repeat by ${unsupported.sorted().joinToString()}" }
		// The start as Graph gets it, and how many days it lies after the start in the series' zone.
		val sent =
			when (timing) {
				is Timing.AllDay -> timing.localStart
				is Timing.Timed -> LocalDateTime.ofInstant(timing.start, zone)
			}
		val start = sent.toLocalDate()
		val shift = ChronoUnit.DAYS.between(timing.localStart.toLocalDate(), start)
		val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
		require(interval >= 1) { "Invalid INTERVAL" }
		val byDay = parts["BYDAY"]?.split(',')?.map(::weekday).orEmpty()
		val monthDays = parts["BYMONTHDAY"]?.split(',')?.map { it.toIntOrNull() ?: 0 }.orEmpty()
		val months = parts["BYMONTH"]?.split(',')?.map { it.toIntOrNull() ?: 0 }.orEmpty()
		val setPos = parts["BYSETPOS"]?.split(',')?.map { it.toIntOrNull() ?: 0 }.orEmpty()
		require(monthDays.size <= 1 && months.size <= 1 && setPos.size <= 1) { "Microsoft calendars cannot express this repeat rule" }
		require(monthDays.all { it in 1..31 } && months.all { it in 1..12 }) { "Microsoft calendars cannot express this repeat rule" }

		val recurrence = json.createObjectNode()
		val pattern = recurrence.putObject("pattern")
		pattern.put("interval", interval)

		fun days(list: List<Pair<Int?, DayOfWeek>>) {
			val array = pattern.putArray("daysOfWeek")
			for ((_, day) in list) {
				val sentDay = day.plus(shift)
				array.add(sentDay.name.lowercase())
			}
		}

		/** Day of the month [day] of the series' zone on the day Graph gets. */
		fun monthDay(day: Int): Int {
			if (shift == 0L) return day
			require(day in 1..28 && day + shift in 1..28) { "Microsoft calendars cannot express this repeat rule at this time of the day" }
			return day + shift.toInt()
		}

		/** The relative part (`index` and days) of a monthly/yearly rule with BYDAY. */
		fun relative() {
			require(shift == 0L) { "Microsoft calendars cannot express this repeat rule at this time of the day" }
			val numbers = byDay.mapNotNull { it.first }.distinct()
			val position =
				when {
					setPos.isNotEmpty() -> {
						require(numbers.isEmpty()) { "Microsoft calendars cannot express this repeat rule" }
						setPos.single()
					}
					else -> {
						require(numbers.size == 1 && byDay.size == 1) { "Microsoft calendars cannot express this repeat rule" }
						numbers.single()
					}
				}
			val index = INDEX.entries.firstOrNull { it.value == position }?.key
			require(index != null) { "Microsoft calendars only repeat on the first to fourth or the last weekday" }
			pattern.put("index", index)
			days(byDay)
		}
		when (val freq = parts["FREQ"]) {
			"DAILY" -> {
				require(monthDays.isEmpty() && months.isEmpty() && setPos.isEmpty()) { "Microsoft calendars cannot express this repeat rule" }
				if (byDay.isEmpty()) {
					pattern.put("type", "daily")
				} else {
					require(interval == 1 && byDay.all { it.first == null }) { "Microsoft calendars cannot express this repeat rule" }
					pattern.put("type", "weekly")
					pattern.put("firstDayOfWeek", "monday")
					days(byDay)
				}
			}
			"WEEKLY" -> {
				require(monthDays.isEmpty() && months.isEmpty() && setPos.isEmpty() && byDay.all { it.first == null }) {
					"Microsoft calendars cannot express this repeat rule"
				}
				pattern.put("type", "weekly")
				val weekStart = parts["WKST"]?.let { weekday(it).second } ?: DayOfWeek.MONDAY
				pattern.put("firstDayOfWeek", weekStart.plus(shift).name.lowercase())
				days(byDay.ifEmpty { listOf(null to timing.localStart.dayOfWeek) })
			}
			"MONTHLY" -> {
				require(months.isEmpty()) { "Microsoft calendars cannot express this repeat rule" }
				if (byDay.isEmpty()) {
					require(setPos.isEmpty()) { "Microsoft calendars cannot express this repeat rule" }
					pattern.put("type", "absoluteMonthly")
					pattern.put("dayOfMonth", monthDay(monthDays.singleOrNull() ?: timing.localStart.dayOfMonth))
				} else {
					require(monthDays.isEmpty()) { "Microsoft calendars cannot express this repeat rule" }
					pattern.put("type", "relativeMonthly")
					relative()
				}
			}
			"YEARLY" -> {
				pattern.put("month", months.singleOrNull() ?: timing.localStart.monthValue)
				if (byDay.isEmpty()) {
					require(setPos.isEmpty()) { "Microsoft calendars cannot express this repeat rule" }
					pattern.put("type", "absoluteYearly")
					pattern.put("dayOfMonth", monthDay(monthDays.singleOrNull() ?: timing.localStart.dayOfMonth))
				} else {
					require(monthDays.isEmpty()) { "Microsoft calendars cannot express this repeat rule" }
					pattern.put("type", "relativeYearly")
					relative()
				}
			}
			else -> throw IllegalArgumentException("Microsoft calendars cannot repeat ${freq?.lowercase() ?: "without a frequency"}")
		}

		val range = recurrence.putObject("range")
		range.put("startDate", start.toString())
		range.put("recurrenceTimeZone", zoneName)
		val count = parts["COUNT"]
		val until = parts["UNTIL"]
		when {
			count != null -> {
				val n = count.toIntOrNull()
				require(n != null && n >= 1) { "Invalid COUNT" }
				range.put("type", "numbered")
				range.put("numberOfOccurrences", n)
			}
			until != null -> {
				range.put("type", "endDate")
				range.put("endDate", lastDate(until, timing, zone, sent.toLocalTime()).toString())
			}
			else -> range.put("type", "noEnd")
		}
		return recurrence
	}

	/**
	 * The date (in [zone], where the series starts at [startTime]) of the last occurrence an UNTIL allows. Graph's
	 * patterns recur at most once a day, at the series' start time: an UNTIL before that time on its day ends the series
	 * the day before.
	 */
	private fun lastDate(
		until: String,
		timing: Timing,
		zone: ZoneId,
		startTime: LocalTime,
	): LocalDate {
		try {
			val date = if (until.length == 8) LocalDate.parse(until, DateTimeFormatter.BASIC_ISO_DATE) else null
			if (timing is Timing.AllDay && date != null) return date
			// A date or a floating UNTIL is in the series' zone.
			val seriesZone: ZoneId = (timing as? Timing.Timed)?.zone ?: ZoneOffset.UTC
			val instant: Instant =
				when {
					date != null -> date.atTime(LocalTime.MAX).atZone(seriesZone).toInstant()
					until.endsWith("Z") -> LocalDateTime.parse(until.dropLast(1), LOCAL_BASIC).toInstant(ZoneOffset.UTC)
					else -> LocalDateTime.parse(until, LOCAL_BASIC).atZone(seriesZone).toInstant()
				}
			val local = instant.atZone(zone).toLocalDateTime()
			return when (timing) {
				is Timing.AllDay -> local.toLocalDate()
				is Timing.Timed -> if (local.toLocalTime() < startTime) local.toLocalDate().minusDays(1) else local.toLocalDate()
			}
		} catch (e: DateTimeParseException) {
			throw IllegalArgumentException("Invalid UNTIL", e)
		}
	}

	/** `MO`, `2MO` or `-1FR` as (number or null, day). */
	private fun weekday(value: String): Pair<Int?, DayOfWeek> {
		val code = value.takeLast(2)
		val day =
			DayOfWeek.entries.firstOrNull { code(it) == code } ?: throw IllegalArgumentException("Invalid weekday '$value'")
		val number = value.dropLast(2).removePrefix("+")
		return (if (number.isEmpty()) null else number.toIntOrNull() ?: throw IllegalArgumentException("Invalid weekday '$value'")) to day
	}

	private fun code(day: DayOfWeek): String = day.name.take(2)
}
