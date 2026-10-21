package ch.sonensei.cockpit.calendar.infra.microsoft

import ch.sonensei.cockpit.calendar.api.Timing
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** [GraphRecurrence]: RRULE to Graph pattern and back, for every pattern type Graph has. */
class GraphRecurrenceTests {
	private val json = JsonMapper.builder().build()

	/** Mondays 09:00 in Zurich, from Monday 5 October 2026. */
	private val timed = Timing.Timed(Instant.parse("2026-10-05T07:00:00Z"), Instant.parse("2026-10-05T07:30:00Z"), ZoneId.of("Europe/Zurich"))
	private val allDay = Timing.AllDay(LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-06"))

	private fun roundTrip(
		rule: String,
		timing: Timing = timed,
	): String? = GraphRecurrence.toRule(GraphRecurrence.toRecurrence(json, rule, timing, "W. Europe Standard Time"), timing)

	@ParameterizedTest
	@ValueSource(
		strings = [
			"FREQ=DAILY",
			"FREQ=DAILY;INTERVAL=3;COUNT=10",
			"FREQ=WEEKLY;BYDAY=MO,WE,FR",
			"FREQ=WEEKLY;BYDAY=MO;WKST=SU;INTERVAL=2",
			"FREQ=MONTHLY;BYMONTHDAY=15",
			"FREQ=MONTHLY;BYDAY=2TU;INTERVAL=2",
			"FREQ=MONTHLY;BYDAY=-1FR",
			"FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1",
			"FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=1",
			"FREQ=YEARLY;BYMONTH=11;BYDAY=4TH",
			"FREQ=WEEKLY;BYDAY=MO;UNTIL=20261228T225959Z",
		],
	)
	fun `rules Graph can express survive the round trip`(rule: String) {
		// Order of the parts is Graph's: FREQ, BY..., INTERVAL, end.
		val parts = { r: String -> r.split(';').toSet() }
		parts(roundTrip(rule)!!) shouldBe parts(rule)
	}

	@ParameterizedTest
	@CsvSource(
		"FREQ=WEEKLY, FREQ=WEEKLY;BYDAY=MO",
		"FREQ=MONTHLY, FREQ=MONTHLY;BYMONTHDAY=5",
		"FREQ=YEARLY, FREQ=YEARLY;BYMONTH=10;BYMONTHDAY=5",
		"'FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR', 'FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR'",
		"'RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=4', 'FREQ=WEEKLY;BYDAY=MO;COUNT=4'",
		"FREQ=MONTHLY;BYDAY=FR;BYSETPOS=2, FREQ=MONTHLY;BYDAY=2FR",
	)
	fun `implicit parts come from the start, and equivalent forms are normalized`(
		rule: String,
		expected: String,
	) {
		roundTrip(rule) shouldBe expected
	}

	@ParameterizedTest
	@CsvSource(
		// A minute before the occurrence of 16 November (09:00 CET): the 9th is the last.
		"20261116T075900Z, 2026-11-15",
		"20261116T080000Z, 2026-11-16",
		"20261116, 2026-11-16",
		"20261116T090000, 2026-11-16",
	)
	fun `an UNTIL becomes the date of the last occurrence it allows`(
		until: String,
		endDate: String,
	) {
		val recurrence = GraphRecurrence.toRecurrence(json, "FREQ=WEEKLY;BYDAY=MO;UNTIL=$until", timed, "W. Europe Standard Time")
		recurrence.get("range").get("type").asString() shouldBe "endDate"
		recurrence.get("range").get("endDate").asString() shouldBe endDate
		recurrence.get("range").get("startDate").asString() shouldBe "2026-10-05"
	}

	@ParameterizedTest
	@ValueSource(strings = ["FREQ=WEEKLY;BYDAY=MO;UNTIL=20261228"])
	fun `all-day series keep a date UNTIL`(rule: String) {
		roundTrip(rule, allDay) shouldBe rule
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			"FREQ=HOURLY",
			"FREQ=DAILY;BYHOUR=9",
			"FREQ=MONTHLY;BYMONTHDAY=1,15",
			"FREQ=MONTHLY;BYDAY=5MO",
			"FREQ=MONTHLY;BYDAY=1MO,3MO",
			"FREQ=WEEKLY;BYDAY=1MO",
			"FREQ=DAILY;INTERVAL=2;BYDAY=MO",
			"FREQ=YEARLY;BYMONTH=1,7",
			"INTERVAL=2",
		],
	)
	fun `rules Graph cannot express are refused`(rule: String) {
		shouldThrow<IllegalArgumentException> { GraphRecurrence.toRecurrence(json, rule, timed, "UTC") }.message shouldContain "Microsoft"
	}

	@ParameterizedTest
	@ValueSource(strings = ["FREQ=DAILY;UNTIL=2026-11-16", "FREQ=DAILY;UNTIL=20261316T080000Z", "FREQ=DAILY;COUNT=0", "FREQ=DAILY;INTERVAL=0"])
	fun `invalid ends and intervals are refused`(rule: String) {
		shouldThrow<IllegalArgumentException> { GraphRecurrence.toRecurrence(json, rule, timed, "UTC") }.message shouldContain "Invalid"
	}

	@ParameterizedTest
	@ValueSource(strings = ["""{"pattern": {"type": "hourly"}, "range": {"type": "noEnd"}}""", """{"pattern": {"type": "absoluteMonthly"}}"""])
	fun `patterns that cannot be read are refused`(recurrence: String) {
		shouldThrow<IllegalArgumentException> { GraphRecurrence.toRule(json.readTree(recurrence), timed) }
	}
}
