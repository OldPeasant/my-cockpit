package ch.sonensei.cockpit.journal.habits.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** What decides whether a habit is due and done: its schedule and the count per day that makes a day done. */
data class HabitRule(
	val schedule: HabitSchedule,
	val timesPerWeek: Int?,
	val weekdays: Set<DayOfWeek>,
	val targetCount: Int,
) {
	/** The streak unit: weeks for [HabitSchedule.TIMES_PER_WEEK] (a week is done with enough done days), else days. */
	val streakUnit: StreakUnit get() = if (schedule == HabitSchedule.TIMES_PER_WEEK) StreakUnit.WEEKS else StreakUnit.DAYS

	/** Whether the schedule names [date] (always for DAILY and TIMES_PER_WEEK, which may be done on any day). */
	fun scheduledOn(date: LocalDate): Boolean = schedule != HabitSchedule.WEEKDAYS || date.dayOfWeek in weekdays
}

enum class StreakUnit { DAYS, WEEKS }

/**
 * A habit's state on [today] (SPEC.md §8.5). Streaks count due days that were done in a row (for TIMES_PER_WEEK: weeks
 * with at least `timesPerWeek` done days, weeks starting on the owner's week start). Today, or this week, does not
 * break a streak while it is not done yet: it is still open. Days the schedule does not name neither extend nor break a
 * streak. The 30-day completion looks at the last 30 days up to today (today only once done), but not before the habit
 * existed: [completionRateLast30Days] is `doneLast30Days / dueLast30Days` (at most 1), `null` without due days.
 */
data class HabitStats(
	val today: LocalDate,
	val todayCount: Int,
	val dueToday: Boolean,
	val doneToday: Boolean,
	val currentStreak: Int,
	val longestStreak: Int,
	val streakUnit: StreakUnit,
	val doneLast30Days: Int,
	val dueLast30Days: Int,
	val completionRateLast30Days: Double?,
)

/**
 * Due days, streaks and completion of one habit, from its checks ([counts]: count per date). [since] is the first day
 * the habit counts (the day it was created; earlier checks move it back); [weekStart] the first day of a week.
 */
class HabitCalendar(
	private val rule: HabitRule,
	private val counts: Map<LocalDate, Int>,
	since: LocalDate,
	private val today: LocalDate,
	private val weekStart: DayOfWeek,
) {
	private val first: LocalDate = listOfNotNull(since, counts.keys.minOrNull(), today).min()

	fun count(date: LocalDate): Int = counts[date] ?: 0

	fun done(date: LocalDate): Boolean = count(date) >= rule.targetCount

	private fun weekOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(weekStart))

	/**
	 * Whether the habit is due on [date] (today or later): a scheduled day, for TIMES_PER_WEEK any day of a week whose
	 * quota is not reached yet (only days up to today count, later ones are not done yet).
	 */
	fun dueOn(date: LocalDate): Boolean {
		if (!rule.scheduledOn(date)) return false
		if (rule.schedule != HabitSchedule.TIMES_PER_WEEK) return true
		val quota = rule.timesPerWeek ?: 1
		val doneBefore = days(weekOf(date), minOf(date.minusDays(1), today)).count(::done)
		return doneBefore < quota
	}

	/** Days from today on (for the next [horizonDays]) on which the habit is due and not done yet, earliest first. */
	fun openDueDays(horizonDays: Long = REMINDER_HORIZON_DAYS): List<LocalDate> =
		days(today, today.plusDays(horizonDays - 1)).filter { dueOn(it) && !(it == today && done(today)) }

	fun stats(): HabitStats {
		val (current, longest) = if (rule.streakUnit == StreakUnit.WEEKS) weekStreaks() else dayStreaks()
		val window = days(maxOf(today.minusDays(WINDOW_DAYS - 1), first), today).filter { it != today || done(today) }
		val (doneDays, dueDays) =
			if (rule.schedule == HabitSchedule.TIMES_PER_WEEK) {
				val quota = rule.timesPerWeek ?: 1
				val due = if (window.isEmpty()) 0 else Math.round(quota * window.size / 7.0).toInt().coerceAtLeast(1)
				window.count(::done) to due
			} else {
				val scheduled = window.filter(rule::scheduledOn)
				scheduled.count(::done) to scheduled.size
			}
		return HabitStats(
			today = today,
			todayCount = count(today),
			dueToday = dueOn(today),
			doneToday = done(today),
			currentStreak = current,
			longestStreak = longest,
			streakUnit = rule.streakUnit,
			doneLast30Days = doneDays,
			dueLast30Days = dueDays,
			completionRateLast30Days = if (dueDays == 0) null else minOf(1.0, doneDays.toDouble() / dueDays),
		)
	}

	/** Current and longest run of done scheduled days; an open today keeps the run. */
	private fun dayStreaks(): Pair<Int, Int> {
		var run = 0
		var longest = 0
		days(first, today).filter(rule::scheduledOn).forEach { day ->
			if (done(day)) {
				run++
				longest = maxOf(longest, run)
			} else if (day != today) {
				run = 0
			}
		}
		return run to longest
	}

	/** Current and longest run of weeks with the quota reached; an open current week keeps the run. */
	private fun weekStreaks(): Pair<Int, Int> {
		val quota = rule.timesPerWeek ?: 1
		val thisWeek = weekOf(today)
		var run = 0
		var longest = 0
		generateSequence(weekOf(first)) { it.plusWeeks(1) }.takeWhile { it <= thisWeek }.forEach { week ->
			if (days(week, minOf(week.plusDays(6), today)).count(::done) >= quota) {
				run++
				longest = maxOf(longest, run)
			} else if (week != thisWeek) {
				run = 0
			}
		}
		return run to longest
	}

	private fun days(
		from: LocalDate,
		to: LocalDate,
	): List<LocalDate> = if (to < from) emptyList() else (0..ChronoUnit.DAYS.between(from, to)).map(from::plusDays)

	companion object {
		const val WINDOW_DAYS = 30L

		/** Every valid rule has a due day within two weeks. */
		const val REMINDER_HORIZON_DAYS = 15L
	}
}
