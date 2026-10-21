package ch.sonensei.cockpit.journal.habits

import ch.sonensei.cockpit.journal.habits.domain.HabitCalendar
import ch.sonensei.cockpit.journal.habits.domain.HabitRule
import ch.sonensei.cockpit.journal.habits.domain.HabitSchedule
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.TUESDAY
import java.time.LocalDate

/** Edge cases of [HabitCalendar]: week starts, year/leap boundaries, quotas, the 30-day window and reminder days. */
class HabitCalendarEdgeCaseTests {
	// 2026-03-04 is a Wednesday.
	private val wednesday = LocalDate.parse("2026-03-04")

	private fun daily(target: Int = 1) = HabitRule(HabitSchedule.DAILY, null, emptySet(), target)

	private fun weekly(times: Int) = HabitRule(HabitSchedule.TIMES_PER_WEEK, times, emptySet(), 1)

	private fun weekdays(vararg days: DayOfWeek) = HabitRule(HabitSchedule.WEEKDAYS, null, days.toSet(), 1)

	private fun calendar(
		rule: HabitRule,
		done: List<String>,
		since: String = "2026-01-01",
		on: LocalDate = wednesday,
		weekStart: DayOfWeek = MONDAY,
		count: Int = rule.targetCount,
	) = HabitCalendar(rule, done.associate { LocalDate.parse(it) to count }, LocalDate.parse(since), on, weekStart)

	private fun days(
		from: String,
		to: String,
	): List<String> {
		val end = LocalDate.parse(to)
		return generateSequence(LocalDate.parse(from)) { it.plusDays(1) }.takeWhile { it <= end }.map { it.toString() }.toList()
	}

	// --- TIMES_PER_WEEK and the week start

	@Test
	fun `weekly streak runs across the year boundary and depends on the week start`() {
		val today = LocalDate.parse("2026-01-07") // Wednesday
		val done = listOf("2025-12-22", "2025-12-24", "2025-12-28", "2026-01-01", "2026-01-04", "2026-01-06")
		// Monday weeks: 12-22..28 (3), 12-29..01-04 (2), 01-05.. (1, still open).
		val monday = calendar(weekly(2), done, since = "2025-12-22", on = today, weekStart = MONDAY).stats()
		monday.currentStreak shouldBe 2
		monday.longestStreak shouldBe 2
		monday.dueToday shouldBe true
		// Sunday weeks: 12-21..27 (2), 12-28..01-03 (2), 01-04..10 (2, reached).
		val sunday = calendar(weekly(2), done, since = "2025-12-22", on = today, weekStart = SUNDAY).stats()
		sunday.currentStreak shouldBe 3
		sunday.longestStreak shouldBe 3
		sunday.dueToday shouldBe false
	}

	@Test
	fun `a missed past week breaks the weekly streak while an open current week does not`() {
		// 2x per week: 02-16 week done, 02-23 week only once (missed), this week once so far (open).
		val stats = calendar(weekly(2), listOf("2026-02-16", "2026-02-17", "2026-02-24", "2026-03-02"), since = "2026-02-16").stats()
		stats.currentStreak shouldBe 0
		stats.longestStreak shouldBe 1
		stats.dueToday shouldBe true
	}

	@Test
	fun `a Sunday check counts for the week that the week start setting gives it`() {
		// 1x per week, Sunday 03-01 checked.
		val sundayWeeks = calendar(weekly(1), listOf("2026-03-01"), since = "2026-02-01", weekStart = SUNDAY)
		sundayWeeks.dueOn(wednesday) shouldBe false
		sundayWeeks.openDueDays().first() shouldBe LocalDate.parse("2026-03-08")
		val mondayWeeks = calendar(weekly(1), listOf("2026-03-01"), since = "2026-02-01", weekStart = MONDAY)
		mondayWeeks.dueOn(wednesday) shouldBe true
		mondayWeeks.openDueDays().first() shouldBe wednesday
	}

	@Test
	fun `seven times per week stays due every day until the week is full`() {
		val two = calendar(weekly(7), listOf("2026-03-02", "2026-03-03"), since = "2026-03-02")
		two.dueOn(wednesday) shouldBe true
		two.openDueDays().first() shouldBe wednesday
		// Today done: the next open day is tomorrow, not next week.
		val three = calendar(weekly(7), days("2026-03-02", "2026-03-04"), since = "2026-03-02")
		three.stats().dueToday shouldBe true
		three.stats().doneToday shouldBe true
		three.openDueDays().first() shouldBe LocalDate.parse("2026-03-05")
	}

	@Test
	fun `a weekly quota reached today moves the next open day to the next week`() {
		val reached = calendar(weekly(2), listOf("2026-03-02", "2026-03-04"), since = "2026-03-01")
		// Today is still due (the quota was not reached before today), but done.
		reached.stats().dueToday shouldBe true
		reached.stats().doneToday shouldBe true
		reached.stats().currentStreak shouldBe 1
		reached.openDueDays().first() shouldBe LocalDate.parse("2026-03-09")
	}

	// --- DAILY / WEEKDAYS

	@Test
	fun `daily streak runs across a leap day and a daylight saving switch`() {
		calendar(daily(), days("2028-02-27", "2028-03-01"), since = "2028-01-01", on = LocalDate.parse("2028-03-01"))
			.stats()
			.currentStreak shouldBe 4
		// Europe switches to summer time on 2026-03-29: local dates are unaffected.
		calendar(daily(), days("2026-03-27", "2026-03-30"), on = LocalDate.parse("2026-03-30")).stats().currentStreak shouldBe 4
	}

	@Test
	fun `a count above the target still makes the day done`() {
		val stats = calendar(daily(target = 3), days("2026-03-02", "2026-03-04"), count = 5).stats()
		stats.doneToday shouldBe true
		stats.currentStreak shouldBe 3
	}

	@Test
	fun `the longest streak is kept after the current one restarted`() {
		val stats = calendar(daily(), days("2026-02-01", "2026-02-10") + days("2026-02-28", "2026-03-04")).stats()
		stats.currentStreak shouldBe 5
		stats.longestStreak shouldBe 10
	}

	@Test
	fun `on a day the schedule does not name, the weekday habit is not due and keeps its streak`() {
		val stats = calendar(weekdays(MONDAY, TUESDAY), listOf("2026-02-23", "2026-02-24", "2026-03-02", "2026-03-03")).stats()
		stats.dueToday shouldBe false
		stats.currentStreak shouldBe 4
		calendar(weekdays(MONDAY, TUESDAY), emptyList()).openDueDays().first() shouldBe LocalDate.parse("2026-03-09")
	}

	@Test
	fun `a weekday habit on all seven days behaves like a daily habit`() {
		val done = days("2026-02-20", "2026-03-03")
		val all = calendar(weekdays(*DayOfWeek.entries.toTypedArray()), done).stats()
		val daily = calendar(daily(), done).stats()
		all.copy(streakUnit = daily.streakUnit) shouldBe daily
	}

	// --- 30-day window

	@Test
	fun `the 30-day window includes today once done and the 29 days before, not the day before that`() {
		val stats = calendar(daily(), days("2026-02-02", "2026-03-04")).stats()
		stats.dueLast30Days shouldBe 30
		stats.doneLast30Days shouldBe 30
		stats.completionRateLast30Days shouldBe 1.0
		val open = calendar(daily(), days("2026-02-02", "2026-03-03")).stats()
		open.dueLast30Days shouldBe 29
		open.doneLast30Days shouldBe 29
	}

	@Test
	fun `weekly completion expects at least one done day as soon as the window has a day`() {
		val created = calendar(weekly(1), listOf("2026-03-04"), since = "2026-03-04").stats()
		created.dueLast30Days shouldBe 1
		created.completionRateLast30Days shouldBe 1.0
		val open = calendar(weekly(3), emptyList(), since = "2026-03-04").stats()
		open.dueLast30Days shouldBe 0
		open.completionRateLast30Days.shouldBeNull()
	}

	@Test
	fun `weekly completion rounds the quota over a partial window`() {
		// Created 02-19, today open: 13 days in the window, 2 per week -> round(26 / 7) = 4 expected.
		val stats = calendar(weekly(2), listOf("2026-02-19", "2026-02-26"), since = "2026-02-19").stats()
		stats.dueLast30Days shouldBe 4
		stats.doneLast30Days shouldBe 2
		stats.completionRateLast30Days shouldBe 0.5
	}

	// --- reminder days

	@Test
	fun `open due days stay within the reminder horizon`() {
		calendar(weekdays(SUNDAY), emptyList()).openDueDays() shouldContainExactly
			listOf(LocalDate.parse("2026-03-08"), LocalDate.parse("2026-03-15"))
		calendar(daily(), emptyList()).openDueDays().size shouldBe HabitCalendar.REMINDER_HORIZON_DAYS.toInt()
	}
}
