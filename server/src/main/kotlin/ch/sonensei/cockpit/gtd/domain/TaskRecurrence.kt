package ch.sonensei.cockpit.gtd.domain

import net.fortuna.ical4j.model.Recur
import net.fortuna.ical4j.transform.recurrence.Frequency
import java.time.LocalDate

/**
 * The recurrence rules of tasks (SPEC.md §5.2): an iCalendar RRULE (without the `RRULE:` prefix) over days, parsed
 * by ical4j. Tasks are dated by day, so only DAILY, WEEKLY, MONTHLY and YEARLY rules are allowed.
 */
object TaskRecurrence {
	private val DAY_FREQUENCIES = setOf(Frequency.DAILY, Frequency.WEEKLY, Frequency.MONTHLY, Frequency.YEARLY)
	private val COUNT = Regex("(?i)(^|;)COUNT=(\\d+)")

	// Any day works to check that ical4j can expand the rule (e.g. a time-of-day part like BYHOUR=9 cannot be applied to days).
	private val PROBE_DAY = LocalDate.of(2000, 1, 1)

	/** Why [rule] is not a valid task recurrence rule, or `null` when it is. */
	fun problem(rule: String): String? {
		if (rule.startsWith("RRULE:", ignoreCase = true)) return "must not start with RRULE:"
		val recur = parse(rule) ?: return "is not a valid RRULE"
		if (recur.frequency !in DAY_FREQUENCIES) return "must repeat DAILY, WEEKLY, MONTHLY or YEARLY"
		if (runCatching { recur.getNextDate(PROBE_DAY, PROBE_DAY) }.isFailure) return "is not a valid RRULE"
		if (count(rule) == 0) return "must have a COUNT of at least 1"
		return null
	}

	/**
	 * The first day of [rule] after [after], for a series starting on [seed]; `null` when the series has ended
	 * (UNTIL) or the rule is invalid.
	 */
	fun nextDate(
		rule: String,
		seed: LocalDate,
		after: LocalDate,
	): LocalDate? = parse(rule)?.let { recur -> runCatching { recur.getNextDate(seed, after) }.getOrNull() }

	/**
	 * The rule for the occurrence after the current one: a COUNT counts the remaining occurrences including the current
	 * one, so it goes down by one. `null` when the current occurrence is the last one (COUNT=1).
	 */
	fun remainingRule(rule: String): String? {
		val count = count(rule) ?: return rule
		if (count <= 1) return null
		return rule.replace(COUNT) { "${it.groupValues[1]}COUNT=${count - 1}" }
	}

	private fun count(rule: String): Int? =
		COUNT
			.find(rule)
			?.groupValues
			?.get(2)
			?.toIntOrNull()

	private fun parse(rule: String): Recur<LocalDate>? = runCatching { Recur<LocalDate>(rule) }.getOrNull()
}
