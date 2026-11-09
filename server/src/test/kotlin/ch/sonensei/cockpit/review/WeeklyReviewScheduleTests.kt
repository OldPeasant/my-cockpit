package ch.sonensei.cockpit.review

import ch.sonensei.cockpit.review.domain.WeeklyReviewSchedule
import ch.sonensei.cockpit.review.infra.ReviewSettings
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class WeeklyReviewScheduleTests {
	private val type = ReviewSettings.WEEKLY_REVIEW_REMINDER.type
	private val zurich = ZoneId.of("Europe/Zurich")

	@Test
	fun `parses off and valid schedules`() {
		type.parse("off") shouldBe "off"
		type.parse("SUNDAY@18:00") shouldBe "SUNDAY@18:00"
		type.parse("MONDAY@00:00") shouldBe "MONDAY@00:00"
		WeeklyReviewSchedule.parse("SATURDAY@23:59") shouldBe WeeklyReviewSchedule(DayOfWeek.SATURDAY, LocalTime.of(23, 59))
		WeeklyReviewSchedule.parse("off").shouldBeNull()
		WeeklyReviewSchedule(DayOfWeek.FRIDAY, LocalTime.of(7, 5)).toString() shouldBe "FRIDAY@07:05"
	}

	@ParameterizedTest
	@ValueSource(
		strings = ["", "OFF", "on", "sunday@18:00", "SUNDAYS@18:00", "FUNDAY@18:00", "SUNDAY@24:00", "SUNDAY@18:60", "SUNDAY@8:00", "SUNDAY 18:00", "SUNDAY@18:00:00", " SUNDAY@18:00"],
	)
	fun `rejects invalid day or time`(value: String) {
		type.parse(value).shouldBeNull()
	}

	@Test
	fun `rejects non-strings`() {
		type.parse(null).shouldBeNull()
		type.parse(5).shouldBeNull()
		type.parse(true).shouldBeNull()
	}

	@Test
	fun `next occurrence is later today, next week, or strictly after now`() {
		val schedule = WeeklyReviewSchedule(DayOfWeek.SUNDAY, LocalTime.of(18, 0))
		// Sunday 2026-03-01 10:00 Zurich (UTC+1).
		schedule.nextAfter(Instant.parse("2026-03-01T09:00:00Z"), zurich) shouldBe Instant.parse("2026-03-01T17:00:00Z")
		// Exactly at the time: next week.
		schedule.nextAfter(Instant.parse("2026-03-01T17:00:00Z"), zurich) shouldBe Instant.parse("2026-03-08T17:00:00Z")
		// Wednesday.
		schedule.nextAfter(Instant.parse("2026-03-04T08:00:00Z"), zurich) shouldBe Instant.parse("2026-03-08T17:00:00Z")
	}

	@Test
	fun `next occurrence honours the time zone`() {
		val schedule = WeeklyReviewSchedule(DayOfWeek.MONDAY, LocalTime.of(8, 0))
		// Sunday 2026-03-01 23:30 UTC is already Monday 08:30 in Tokyo: next Monday there.
		val now = Instant.parse("2026-03-01T23:30:00Z")
		schedule.nextAfter(now, ZoneId.of("Asia/Tokyo")) shouldBe Instant.parse("2026-03-08T23:00:00Z")
		schedule.nextAfter(now, ZoneId.of("America/New_York")) shouldBe Instant.parse("2026-03-02T13:00:00Z")
	}

	@Test
	fun `next occurrence honours DST changes`() {
		val schedule = WeeklyReviewSchedule(DayOfWeek.SUNDAY, LocalTime.of(18, 0))
		// Before the switch to summer time on Sunday 2026-03-29: 18:00 is then UTC+2.
		schedule.nextAfter(Instant.parse("2026-03-23T12:00:00Z"), zurich) shouldBe Instant.parse("2026-03-29T16:00:00Z")
		// Back to UTC+1 on Sunday 2026-10-25.
		schedule.nextAfter(Instant.parse("2026-10-20T12:00:00Z"), zurich) shouldBe Instant.parse("2026-10-25T17:00:00Z")
	}

	@Test
	fun `a time in the DST gap moves forward, one in the overlap fires at its first occurrence`() {
		// 02:30 does not exist in Zurich on 2026-03-29: 03:30 CEST.
		WeeklyReviewSchedule(DayOfWeek.SUNDAY, LocalTime.of(2, 30))
			.nextAfter(Instant.parse("2026-03-28T12:00:00Z"), zurich) shouldBe Instant.parse("2026-03-29T01:30:00Z")
		// 02:30 exists twice on 2026-10-25: the first, CEST.
		WeeklyReviewSchedule(DayOfWeek.SUNDAY, LocalTime.of(2, 30))
			.nextAfter(Instant.parse("2026-10-24T12:00:00Z"), zurich) shouldBe Instant.parse("2026-10-25T00:30:00Z")
	}
}
