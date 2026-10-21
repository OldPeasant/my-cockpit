package ch.sonensei.cockpit.journal.habits

import ch.sonensei.cockpit.journal.habits.domain.HabitCalendar
import ch.sonensei.cockpit.journal.habits.domain.HabitRule
import ch.sonensei.cockpit.journal.habits.domain.HabitSchedule
import ch.sonensei.cockpit.journal.habits.domain.StreakUnit
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate

class HabitCalendarTests {
	// 2026-03-04 is a Wednesday.
	private val today = LocalDate.parse("2026-03-04")

	private fun daily(target: Int = 1) = HabitRule(HabitSchedule.DAILY, null, emptySet(), target)

	private fun weekly(times: Int) = HabitRule(HabitSchedule.TIMES_PER_WEEK, times, emptySet(), 1)

	private fun weekdays(vararg days: DayOfWeek) = HabitRule(HabitSchedule.WEEKDAYS, null, days.toSet(), 1)

	private fun calendar(
		rule: HabitRule,
		done: List<String>,
		since: String = "2026-01-01",
		on: LocalDate = today,
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

	// --- DAILY

	@Test
	fun `daily streak counts done days in a row up to today`() {
		val stats = calendar(daily(), days("2026-02-26", "2026-03-04")).stats()
		stats.currentStreak shouldBe 7
		stats.longestStreak shouldBe 7
		stats.streakUnit shouldBe StreakUnit.DAYS
		stats.doneToday shouldBe true
		stats.dueToday shouldBe true
	}

	@Test
	fun `an open today keeps the streak, a missed yesterday breaks it`() {
		calendar(daily(), days("2026-02-26", "2026-03-03")).stats().currentStreak shouldBe 6
		val broken = calendar(daily(), days("2026-02-20", "2026-03-02")).stats()
		broken.currentStreak shouldBe 0
		broken.longestStreak shouldBe 11
	}

	@Test
	fun `a day counts as done only when the count reaches the target`() {
		val calendar = calendar(daily(target = 3), days("2026-03-01", "2026-03-04"), count = 2)
		calendar.stats().currentStreak shouldBe 0
		calendar.stats().todayCount shouldBe 2
		calendar.stats().doneToday shouldBe false
		calendar(daily(target = 3), days("2026-03-01", "2026-03-04"), count = 3).stats().currentStreak shouldBe 4
	}

	@Test
	fun `daily streak runs across week and month boundaries`() {
		// Sunday 2026-03-01 and Monday 2026-03-02 are in different weeks; February ends in between too.
		val stats = calendar(daily(), days("2026-02-27", "2026-03-03"), weekStart = MONDAY).stats()
		stats.currentStreak shouldBe 5
	}

	// --- WEEKDAYS

	@Test
	fun `weekday streak skips days the schedule does not name, across the weekend`() {
		// Mon/Wed/Fri: Fri 02-27, Mon 03-02, Wed 03-04 done; the weekend in between neither breaks nor counts.
		val stats = calendar(weekdays(MONDAY, WEDNESDAY, FRIDAY), listOf("2026-02-25", "2026-02-27", "2026-03-02", "2026-03-04")).stats()
		stats.currentStreak shouldBe 4
		stats.dueToday shouldBe true
	}

	@Test
	fun `a missed scheduled weekday breaks the streak, checks on other days do not count`() {
		val rule = weekdays(MONDAY, WEDNESDAY, FRIDAY)
		// Fri 02-27 missed (Sat 02-28 checked instead).
		val stats = calendar(rule, listOf("2026-02-23", "2026-02-25", "2026-02-28", "2026-03-02", "2026-03-04")).stats()
		stats.currentStreak shouldBe 2
		stats.longestStreak shouldBe 2
	}

	@Test
	fun `a weekday habit is not due on other days`() {
		val stats = calendar(weekdays(MONDAY, FRIDAY), emptyList()).stats()
		stats.dueToday shouldBe false
	}

	// --- TIMES_PER_WEEK

	@Test
	fun `weekly streak counts weeks that reached the quota, across week boundaries`() {
		// 3x per week, weeks start Monday: 02-16..02-22, 02-23..03-01 done 3 times each; this week (from 03-02) open.
		val done = listOf("2026-02-16", "2026-02-18", "2026-02-22", "2026-02-23", "2026-02-28", "2026-03-01", "2026-03-02")
		val stats = calendar(weekly(3), done, since = "2026-02-16").stats()
		stats.streakUnit shouldBe StreakUnit.WEEKS
		stats.currentStreak shouldBe 2
		stats.longestStreak shouldBe 2
		stats.dueToday shouldBe true
	}

	@Test
	fun `the week start setting decides which week a day belongs to`() {
		// Sun 03-01 belongs to the week of Mon 02-23 when weeks start Monday, but starts a new week when they start Sunday.
		val done = listOf("2026-02-24", "2026-02-26", "2026-03-01")
		calendar(weekly(3), done, since = "2026-02-23", weekStart = MONDAY).stats().currentStreak shouldBe 1
		// Sunday weeks: 02-22..02-28 has 2 (missed), 03-01..03-07 has 1 so far (open).
		val sundayWeeks = calendar(weekly(3), done, since = "2026-02-23", weekStart = SUNDAY).stats()
		sundayWeeks.currentStreak shouldBe 0
		sundayWeeks.longestStreak shouldBe 0
	}

	@Test
	fun `this week reaching the quota extends the streak, a missed week breaks it`() {
		val done = listOf("2026-02-09", "2026-02-10", "2026-02-23", "2026-02-24", "2026-03-02", "2026-03-03")
		val stats = calendar(weekly(2), done, since = "2026-02-09").stats()
		// 02-09 week done, 02-16 week missed, 02-23 week done, this week done.
		stats.currentStreak shouldBe 2
		stats.longestStreak shouldBe 2
		stats.dueToday shouldBe false
	}

	@Test
	fun `times per week is due on any day until the quota is reached`() {
		val calendar = calendar(weekly(2), listOf("2026-03-02"), since = "2026-03-01")
		calendar.dueOn(today) shouldBe true
		val reached = calendar(weekly(2), listOf("2026-03-02", "2026-03-03"), since = "2026-03-01")
		reached.dueOn(today) shouldBe false
		// The next open day is next week's Monday.
		reached.openDueDays().first() shouldBe LocalDate.parse("2026-03-09")
	}

	// --- 30-day stats

	@Test
	fun `completion of the last 30 days, open today not counted`() {
		// Daily since long ago, done on 15 of the 29 days before today; today open.
		val done = days("2026-02-03", "2026-02-17")
		val stats = calendar(daily(), done).stats()
		stats.dueLast30Days shouldBe 29
		stats.doneLast30Days shouldBe 15
		stats.completionRateLast30Days!! shouldBe (15.0 / 29 plusOrMinus 1e-9)
	}

	@Test
	fun `completion does not count days before the habit existed`() {
		val stats = calendar(daily(), days("2026-03-02", "2026-03-04"), since = "2026-03-02").stats()
		stats.dueLast30Days shouldBe 3
		stats.doneLast30Days shouldBe 3
		stats.completionRateLast30Days shouldBe 1.0
	}

	@Test
	fun `a check before the creation day moves the start back`() {
		val stats = calendar(daily(), listOf("2026-02-28"), since = "2026-03-04").stats()
		stats.dueLast30Days shouldBe 4
		stats.doneLast30Days shouldBe 1
		stats.longestStreak shouldBe 1
	}

	@Test
	fun `a new habit with nothing due yet has no completion rate`() {
		val stats = calendar(daily(), emptyList(), since = "2026-03-04").stats()
		stats.dueLast30Days shouldBe 0
		stats.completionRateLast30Days.shouldBeNull()
		stats.currentStreak shouldBe 0
	}

	@Test
	fun `weekly completion expects the quota per 7 days, capped at 1`() {
		val done = days("2026-02-03", "2026-03-04")
		val stats = calendar(weekly(3), done).stats()
		// 30 days in the window, 3 per week -> 13 expected.
		stats.dueLast30Days shouldBe 13
		stats.doneLast30Days shouldBe 30
		stats.completionRateLast30Days shouldBe 1.0
	}

	@Test
	fun `weekday completion counts only scheduled days`() {
		// Mondays in the window 02-03..03-03 (today open): 02-09, 02-16, 02-23, 03-02.
		val stats = calendar(weekdays(MONDAY), listOf("2026-02-09", "2026-02-16", "2026-02-20")).stats()
		stats.dueLast30Days shouldBe 4
		stats.doneLast30Days shouldBe 2
	}

	// --- open due days (reminders)

	@Test
	fun `open due days skip today once it is done`() {
		calendar(daily(), emptyList()).openDueDays().take(2).map { it.toString() } shouldContainExactly listOf("2026-03-04", "2026-03-05")
		calendar(daily(), listOf("2026-03-04")).openDueDays().first().toString() shouldBe "2026-03-05"
		calendar(weekdays(MONDAY), emptyList()).openDueDays().first().toString() shouldBe "2026-03-09"
	}
}
