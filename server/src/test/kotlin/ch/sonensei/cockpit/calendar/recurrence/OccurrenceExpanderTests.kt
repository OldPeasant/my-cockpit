package ch.sonensei.cockpit.calendar.recurrence

import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

class OccurrenceExpanderTests {
	private val expander = OccurrenceExpander()
	private val zurich = ZoneId.of("Europe/Zurich")
	private val calendarId = UUID.randomUUID()

	private fun at(local: String): Instant = LocalDateTime.parse(local).atZone(zurich).toInstant()

	private fun range(
		from: String,
		to: String,
	) = OccurrenceRange(at(from), at(to), zurich)

	private fun timed(
		start: String,
		end: String,
		rrule: String? = null,
		title: String = "Standup",
	) = CalendarEvent(calendarId, title).apply {
		setTimed(at(start), at(end), zurich)
		this.rrule = rrule
	}

	private fun allDay(
		start: String,
		end: String? = null,
		rrule: String? = null,
	) = CalendarEvent(calendarId, "Holiday").apply {
		val first = LocalDate.parse(start)
		setAllDay(first, end?.let(LocalDate::parse) ?: first.plusDays(1))
		this.rrule = rrule
	}

	private fun localStarts(occurrences: List<Occurrence>) =
		occurrences.map {
			it.start!!
				.atZone(zurich)
				.toLocalDateTime()
				.toString()
		}

	@Test
	fun `single event overlapping the range yields one occurrence, outside none`() {
		val event = timed("2026-03-02T09:00", "2026-03-02T10:00")
		expander.expandSeries(event, emptyList(), range("2026-03-02T09:30", "2026-03-03T00:00")).single().apply {
			start shouldBe at("2026-03-02T09:00")
			end shouldBe at("2026-03-02T10:00")
			seriesId shouldBe null
			occurrenceId shouldBe null
		}
		expander.expandSeries(event, emptyList(), range("2026-03-02T10:00", "2026-03-03T00:00")).shouldBeEmpty()
	}

	@Test
	fun `weekly series keeps its wall-clock time across DST start in Europe-Zurich`() {
		// DST starts on Sunday 2026-03-29 in Zurich (02:00 -> 03:00).
		val event = timed("2026-03-16T09:00", "2026-03-16T10:00", "FREQ=WEEKLY;BYDAY=MO")
		val occurrences = expander.expandSeries(event, emptyList(), range("2026-03-16T00:00", "2026-04-14T00:00"))
		localStarts(occurrences) shouldContainExactly
			listOf("2026-03-16T09:00", "2026-03-23T09:00", "2026-03-30T09:00", "2026-04-06T09:00", "2026-04-13T09:00")
		occurrences[1].start shouldBe Instant.parse("2026-03-23T08:00:00Z")
		occurrences[2].start shouldBe Instant.parse("2026-03-30T07:00:00Z")
		occurrences.forEach {
			(it.end!!.epochSecond - it.start!!.epochSecond) shouldBe 3600
			it.seriesId shouldBe event.id
			it.occurrenceId shouldBe OccurrenceId.of(it.start)
		}
	}

	@Test
	fun `weekly series keeps its wall-clock time across DST end in Europe-Zurich`() {
		// DST ends on Sunday 2026-10-25 in Zurich (03:00 -> 02:00).
		val event = timed("2026-10-18T02:30", "2026-10-18T03:30", "FREQ=WEEKLY")
		val occurrences = expander.expandSeries(event, emptyList(), range("2026-10-18T00:00", "2026-11-02T00:00"))
		localStarts(occurrences) shouldContainExactly listOf("2026-10-18T02:30", "2026-10-25T02:30", "2026-11-01T02:30")
		occurrences.map { it.start } shouldContainExactly
			listOf(
				Instant.parse("2026-10-18T00:30:00Z"),
				// 02:30 exists twice on 2026-10-25; java.time picks the earlier offset (summer time).
				Instant.parse("2026-10-25T00:30:00Z"),
				Instant.parse("2026-11-01T01:30:00Z"),
			)
	}

	@Test
	fun `all-day series recurs by date`() {
		val event = allDay("2026-12-24", "2026-12-26", "FREQ=YEARLY")
		val occurrences = expander.expandSeries(event, emptyList(), range("2027-12-25T12:00", "2029-01-01T00:00"))
		occurrences.map { it.startDate to it.endDate } shouldContainExactly
			listOf(
				LocalDate.parse("2027-12-24") to LocalDate.parse("2027-12-26"),
				LocalDate.parse("2028-12-24") to LocalDate.parse("2028-12-26"),
			)
		occurrences.forEach {
			it.allDay shouldBe true
			it.start shouldBe null
			it.occurrenceId shouldBe OccurrenceId.of(it.startDate!!)
		}
	}

	@Test
	fun `all-day single event and exdates of an all-day series`() {
		val single = allDay("2026-05-01")
		expander.expandSeries(single, emptyList(), range("2026-05-01T23:00", "2026-05-02T00:00")).single().startDate shouldBe
			LocalDate.parse("2026-05-01")
		expander.expandSeries(single, emptyList(), range("2026-05-02T00:00", "2026-05-03T00:00")).shouldBeEmpty()

		val daily = allDay("2026-05-01", rrule = "FREQ=DAILY;COUNT=4")
		daily.exdates.add(OccurrenceId.of(LocalDate.parse("2026-05-02")))
		expander.expandSeries(daily, emptyList(), range("2026-04-01T00:00", "2026-06-01T00:00")).map {
			it.startDate.toString()
		} shouldContainExactly
			listOf("2026-05-01", "2026-05-03", "2026-05-04")
	}

	@Test
	fun `moved occurrence is shown at its new time, cancelled and excluded ones are removed`() {
		val master = timed("2026-06-01T09:00", "2026-06-01T09:30", "FREQ=DAILY;COUNT=5")
		master.exdates.add(OccurrenceId.of(at("2026-06-02T09:00")))
		val moved =
			timed("2026-06-03T14:00", "2026-06-03T15:00", title = "Standup (moved)").apply {
				overrideOf(master, OccurrenceId.of(at("2026-06-03T09:00")))
			}
		val cancelled =
			timed("2026-06-04T09:00", "2026-06-04T09:30").apply {
				overrideOf(master, OccurrenceId.of(at("2026-06-04T09:00")))
				status = EventStatus.CANCELLED
			}
		val occurrences = expander.expand(listOf(master, moved, cancelled), range("2026-06-01T00:00", "2026-06-08T00:00"))
		localStarts(occurrences) shouldContainExactly listOf("2026-06-01T09:00", "2026-06-03T14:00", "2026-06-05T09:00")
		occurrences[1].apply {
			event shouldBe moved
			seriesId shouldBe master.id
			occurrenceId shouldBe OccurrenceId.of(at("2026-06-03T09:00"))
			end shouldBe at("2026-06-03T15:00")
		}
	}

	@Test
	fun `occurrence moved into or out of the range follows its new time`() {
		val master = timed("2026-06-01T09:00", "2026-06-01T10:00", "FREQ=WEEKLY")
		val movedIn =
			timed("2026-06-05T09:00", "2026-06-05T10:00").apply { overrideOf(master, OccurrenceId.of(at("2026-06-08T09:00"))) }
		val occurrences = expander.expand(listOf(master, movedIn), range("2026-06-02T00:00", "2026-06-09T00:00"))
		occurrences.single().event shouldBe movedIn
		expander.expand(listOf(master, movedIn), range("2026-06-07T00:00", "2026-06-09T00:00")).shouldBeEmpty()
	}

	@Test
	fun `COUNT limits the series, also when the range starts after its first occurrences`() {
		val event = timed("2026-01-05T08:00", "2026-01-05T09:00", "FREQ=WEEKLY;COUNT=3")
		localStarts(expander.expandSeries(event, emptyList(), range("2026-01-01T00:00", "2026-03-01T00:00"))) shouldContainExactly
			listOf("2026-01-05T08:00", "2026-01-12T08:00", "2026-01-19T08:00")
		localStarts(expander.expandSeries(event, emptyList(), range("2026-01-13T00:00", "2026-03-01T00:00"))) shouldContainExactly
			listOf("2026-01-19T08:00")
	}

	@Test
	fun `UNTIL ends the series inclusively`() {
		val event = timed("2026-01-05T08:00", "2026-01-05T09:00", "FREQ=DAILY;UNTIL=20260108T070000Z")
		localStarts(expander.expandSeries(event, emptyList(), range("2026-01-01T00:00", "2026-03-01T00:00"))) shouldContainExactly
			listOf("2026-01-05T08:00", "2026-01-06T08:00", "2026-01-07T08:00", "2026-01-08T08:00")

		val holidays = allDay("2026-01-05", rrule = "FREQ=WEEKLY;UNTIL=20260119")
		expander.expandSeries(holidays, emptyList(), range("2026-01-01T00:00", "2026-03-01T00:00")).map {
			it.startDate.toString()
		} shouldContainExactly
			listOf("2026-01-05", "2026-01-12", "2026-01-19")
	}

	@Test
	fun `monthly by weekday`() {
		// Second Tuesday and last Friday of each month.
		val secondTuesday = timed("2026-01-13T18:00", "2026-01-13T19:00", "FREQ=MONTHLY;BYDAY=2TU")
		localStarts(expander.expandSeries(secondTuesday, emptyList(), range("2026-01-01T00:00", "2026-05-01T00:00"))) shouldContainExactly
			listOf("2026-01-13T18:00", "2026-02-10T18:00", "2026-03-10T18:00", "2026-04-14T18:00")
		val lastFriday = timed("2026-01-30T17:00", "2026-01-30T18:00", "FREQ=MONTHLY;BYDAY=-1FR;COUNT=3")
		localStarts(expander.expandSeries(lastFriday, emptyList(), range("2026-01-01T00:00", "2027-01-01T00:00"))) shouldContainExactly
			listOf("2026-01-30T17:00", "2026-02-27T17:00", "2026-03-27T17:00")
	}

	@Test
	fun `occurrence still running at the range start is included`() {
		val event = timed("2026-02-02T22:00", "2026-02-03T02:00", "FREQ=DAILY")
		localStarts(expander.expandSeries(event, emptyList(), range("2026-02-05T00:00", "2026-02-06T00:00"))) shouldContainExactly
			listOf("2026-02-04T22:00", "2026-02-05T22:00")
	}

	@Test
	fun `expand mixes single events and series sorted by start, ignoring deleted events`() {
		val series = timed("2026-07-01T10:00", "2026-07-01T11:00", "FREQ=DAILY")
		val single = timed("2026-07-02T08:00", "2026-07-02T09:00")
		val holiday = allDay("2026-07-02")
		val deleted = timed("2026-07-02T07:00", "2026-07-02T08:00").apply { softDelete(java.time.Clock.systemUTC()) }
		val occurrences = expander.expand(listOf(series, single, holiday, deleted), range("2026-07-02T00:00", "2026-07-03T00:00"))
		occurrences.map { it.event } shouldContainExactly listOf(holiday, single, series)
	}

	@Test
	fun `series starting after the range has no occurrences`() {
		val event = timed("2026-09-01T10:00", "2026-09-01T11:00", "FREQ=DAILY")
		expander.expandSeries(event, emptyList(), range("2026-08-01T00:00", "2026-09-01T10:00")).shouldBeEmpty()
	}
}
