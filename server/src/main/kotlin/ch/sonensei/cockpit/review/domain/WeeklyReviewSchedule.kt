package ch.sonensei.cockpit.review.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * When the weekly review reminder fires (SPEC.md §8.6): every [day] at [time] in the owner's time zone. Stored in the
 * `weeklyReviewReminder` setting as `<DAY>@HH:mm` (e.g. `SUNDAY@18:00`), or `off`.
 */
data class WeeklyReviewSchedule(
	val day: DayOfWeek,
	val time: LocalTime,
) {
	override fun toString(): String = "${day.name}@${FORMAT.format(time)}"

	/**
	 * The first occurrence strictly after [now] in [zone]. A time that does not exist on that day (DST gap) moves forward
	 * by the gap's length; a time that exists twice (DST overlap) fires at its first occurrence.
	 */
	fun nextAfter(
		now: Instant,
		zone: ZoneId,
	): Instant {
		val today = LocalDate.ofInstant(now, zone)
		return (0L..7L)
			.asSequence()
			.map { today.plusDays(it) }
			.filter { it.dayOfWeek == day }
			.map { ZonedDateTime.of(it, time, zone).toInstant() }
			.first { it > now }
	}

	companion object {
		const val OFF = "off"

		private val FORMAT = DateTimeFormatter.ofPattern("HH:mm")
		private val PATTERN = Regex("([A-Z]+)@([01][0-9]|2[0-3]):([0-5][0-9])")
		private val DAYS = DayOfWeek.entries.associateBy { it.name }

		/** The schedule of a setting value; `null` for [OFF] and for anything that is not `<DAY>@HH:mm`. */
		fun parse(value: String): WeeklyReviewSchedule? {
			val match = PATTERN.matchEntire(value) ?: return null
			val (dayName, hours, minutes) = match.destructured
			val day = DAYS[dayName] ?: return null
			return WeeklyReviewSchedule(day, LocalTime.of(hours.toInt(), minutes.toInt()))
		}

		/** Whether [value] is a valid setting value: [OFF] or a schedule. */
		fun isValid(value: String): Boolean = value == OFF || parse(value) != null
	}
}
