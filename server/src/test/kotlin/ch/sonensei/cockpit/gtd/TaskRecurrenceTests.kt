package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.gtd.domain.TaskRecurrence
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The RRULE rules of recurring tasks ([TaskRecurrence]), without a database. */
class TaskRecurrenceTests {
	@Test
	fun `day-based rules are valid`() {
		listOf(
			"FREQ=DAILY",
			"FREQ=WEEKLY;BYDAY=MO",
			"FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH",
			"FREQ=MONTHLY;BYMONTHDAY=1",
			"FREQ=MONTHLY;BYDAY=-1FR",
			"FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=15",
			"FREQ=DAILY;COUNT=3",
			"FREQ=WEEKLY;UNTIL=20261231",
		).forEach { TaskRecurrence.problem(it) shouldBe null }
	}

	@Test
	fun `invalid rules, the RRULE prefix, sub-daily frequencies and COUNT=0 are rejected`() {
		listOf("", "nonsense", "FREQ=FORTNIGHTLY", "BYDAY=MO", "FREQ=WEEKLY;BYDAY=XX", "FREQ=DAILY;INTERVAL=x")
			.forEach { TaskRecurrence.problem(it) shouldBe "is not a valid RRULE" }
		TaskRecurrence.problem("RRULE:FREQ=DAILY") shouldBe "must not start with RRULE:"
		listOf("FREQ=HOURLY", "FREQ=MINUTELY", "FREQ=SECONDLY")
			.forEach { TaskRecurrence.problem(it) shouldBe "must repeat DAILY, WEEKLY, MONTHLY or YEARLY" }
		TaskRecurrence.problem("FREQ=DAILY;COUNT=0") shouldBe "must have a COUNT of at least 1"
	}

	@Test
	fun `the next date is strictly after the given day`() {
		val monday = LocalDate.of(2026, 10, 19)
		TaskRecurrence.nextDate("FREQ=WEEKLY;BYDAY=MO", monday, monday) shouldBe LocalDate.of(2026, 10, 26)
		TaskRecurrence.nextDate("FREQ=WEEKLY;BYDAY=MO", monday, monday.plusDays(2)) shouldBe LocalDate.of(2026, 10, 26)
		TaskRecurrence.nextDate("FREQ=DAILY;INTERVAL=3", monday, monday) shouldBe LocalDate.of(2026, 10, 22)
		TaskRecurrence.nextDate("FREQ=MONTHLY;BYMONTHDAY=31", LocalDate.of(2026, 1, 31), LocalDate.of(2026, 1, 31)) shouldBe
			LocalDate.of(2026, 3, 31)
		TaskRecurrence.nextDate("FREQ=YEARLY", LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29)) shouldNotBe null
	}

	@Test
	fun `no next date after UNTIL or for an invalid rule`() {
		val day = LocalDate.of(2026, 10, 19)
		TaskRecurrence.nextDate("FREQ=DAILY;UNTIL=20261019", day, day) shouldBe null
		TaskRecurrence.nextDate("FREQ=DAILY;UNTIL=20261020", day, day) shouldBe LocalDate.of(2026, 10, 20)
		TaskRecurrence.nextDate("nonsense", day, day) shouldBe null
	}

	@Test
	fun `COUNT goes down by one per occurrence and ends at 1`() {
		TaskRecurrence.remainingRule("FREQ=DAILY") shouldBe "FREQ=DAILY"
		TaskRecurrence.remainingRule("FREQ=DAILY;COUNT=3;BYDAY=MO") shouldBe "FREQ=DAILY;COUNT=2;BYDAY=MO"
		TaskRecurrence.remainingRule("COUNT=2;FREQ=DAILY") shouldBe "COUNT=1;FREQ=DAILY"
		TaskRecurrence.remainingRule("FREQ=DAILY;COUNT=1") shouldBe null
	}
}
