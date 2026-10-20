package ch.sonensei.cockpit.calendar.recurrence

import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** Edge cases of [OccurrenceExpander]: DST gaps, range boundaries, zones, overrides and inconsistent inputs. */
class OccurrenceExpanderEdgeCaseTests {
	private val expander = OccurrenceExpander()
	private val zurich = ZoneId.of("Europe/Zurich")
	private val newYork = ZoneId.of("America/New_York")
	private val auckland = ZoneId.of("Pacific/Auckland")
	private val calendarId = UUID.randomUUID()
	private val fixedClock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), zurich)

	private fun at(
		local: String,
		zone: ZoneId = zurich,
	): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

	private fun range(
		from: String,
		to: String,
		zone: ZoneId = zurich,
	) = OccurrenceRange(at(from, zone), at(to, zone), zone)

	private fun timed(
		start: String,
		end: String,
		rrule: String? = null,
		zone: ZoneId = zurich,
		title: String = "Team sync",
	) = CalendarEvent(calendarId, title).apply {
		setTimed(at(start, zone), at(end, zone), zone)
		this.rrule = rrule
	}

	private fun allDay(
		start: String,
		end: String? = null,
		rrule: String? = null,
		title: String = "Fictitious holiday",
	) = CalendarEvent(calendarId, title).apply {
		val first = LocalDate.parse(start)
		setAllDay(first, end?.let(LocalDate::parse) ?: first.plusDays(1))
		this.rrule = rrule
	}

	private fun localStarts(
		occurrences: List<Occurrence>,
		zone: ZoneId = zurich,
	) = occurrences.map {
		it.start!!
			.atZone(zone)
			.toLocalDateTime()
			.toString()
	}

	private fun startDates(occurrences: List<Occurrence>) = occurrences.map { it.startDate.toString() }

	// --- DST ---

	@Test
	fun `nonexistent local time at DST start is shifted forward for that week only`() {
		// 2026-03-29 02:30 does not exist in Zurich (02:00 -> 03:00).
		val event = timed("2026-03-22T02:30", "2026-03-22T03:00", "FREQ=WEEKLY")
		val occurrences = expander.expandSeries(event, emptyList(), range("2026-03-22T00:00", "2026-04-13T00:00"))
		localStarts(occurrences) shouldContainExactly
			listOf("2026-03-22T02:30", "2026-03-29T03:30", "2026-04-05T02:30", "2026-04-12T02:30")
		occurrences[1].start shouldBe Instant.parse("2026-03-29T01:30:00Z")
		occurrences.forEach { Duration.between(it.start, it.end) shouldBe Duration.ofMinutes(30) }
	}

	@Test
	fun `occurrence spanning the DST start keeps the master's exact duration`() {
		val event = timed("2026-03-22T01:30", "2026-03-22T03:30", "FREQ=WEEKLY;COUNT=2")
		val occurrences = expander.expandSeries(event, emptyList(), range("2026-03-01T00:00", "2026-04-30T00:00"))
		localStarts(occurrences) shouldContainExactly listOf("2026-03-22T01:30", "2026-03-29T01:30")
		occurrences.map { it.end } shouldContainExactly listOf(at("2026-03-22T03:30"), at("2026-03-29T04:30"))
		occurrences.forEach { Duration.between(it.start, it.end) shouldBe Duration.ofHours(2) }
	}

	@Test
	fun `series in another zone keeps its own wall-clock time while the user's zone differs`() {
		// US DST starts 2026-03-08, EU DST 2026-03-29: between the two, New York 09:00 is Zurich 14:00.
		val event = timed("2026-03-02T09:00", "2026-03-02T10:00", "FREQ=WEEKLY", zone = newYork)
		val occurrences = expander.expandSeries(event, emptyList(), range("2026-03-01T00:00", "2026-04-07T00:00"))
		localStarts(occurrences, newYork) shouldContainExactly
			listOf("2026-03-02T09:00", "2026-03-09T09:00", "2026-03-16T09:00", "2026-03-23T09:00", "2026-03-30T09:00", "2026-04-06T09:00")
		localStarts(occurrences, zurich) shouldContainExactly
			listOf("2026-03-02T15:00", "2026-03-09T14:00", "2026-03-16T14:00", "2026-03-23T14:00", "2026-03-30T15:00", "2026-04-06T15:00")
	}

	// --- Long-running series ---

	@Test
	fun `long-running daily series is expanded correctly far from its start`() {
		val event = timed("2016-01-04T09:00", "2016-01-04T09:15", "FREQ=DAILY")
		val occurrences = expander.expandSeries(event, emptyList(), range("2036-07-01T00:00", "2036-07-03T00:00"))
		localStarts(occurrences) shouldContainExactly listOf("2036-07-01T09:00", "2036-07-02T09:00")
		occurrences.first().start shouldBe Instant.parse("2036-07-01T07:00:00Z")
	}

	@Test
	fun `exhausted COUNT series yields nothing far after its end`() {
		val event = timed("2016-01-04T09:00", "2016-01-04T09:15", "FREQ=DAILY;COUNT=10")
		expander.expandSeries(event, emptyList(), range("2026-01-01T00:00", "2026-02-01T00:00")).shouldBeEmpty()
	}

	@Test
	fun `excluded occurrences still count toward COUNT`() {
		val event = timed("2026-01-05T08:00", "2026-01-05T09:00", "FREQ=DAILY;COUNT=3")
		event.exdates.add(OccurrenceId.of(at("2026-01-06T08:00")))
		localStarts(expander.expandSeries(event, emptyList(), range("2026-01-01T00:00", "2026-02-01T00:00"))) shouldContainExactly
			listOf("2026-01-05T08:00", "2026-01-07T08:00")
	}

	// --- Zero-duration events and range boundaries ---

	@Test
	fun `zero-duration occurrence belongs to the range it starts in`() {
		val event = timed("2026-04-01T12:00", "2026-04-01T12:00", "FREQ=DAILY")
		// Starts exactly at the range start: included.
		localStarts(expander.expandSeries(event, emptyList(), range("2026-04-03T12:00", "2026-04-03T13:00"))) shouldContainExactly
			listOf("2026-04-03T12:00")
		// Starts exactly at the (exclusive) range end: excluded.
		expander.expandSeries(event, emptyList(), range("2026-04-03T11:00", "2026-04-03T12:00")).shouldBeEmpty()
		expander.expandSeries(event, emptyList(), range("2026-04-03T12:00", "2026-04-03T13:00")).single().apply {
			start shouldBe end
		}
	}

	@Test
	fun `zero-duration single event at the range boundaries`() {
		val event = timed("2026-04-01T12:00", "2026-04-01T12:00")
		expander.expandSeries(event, emptyList(), range("2026-04-01T12:00", "2026-04-01T13:00")).size shouldBe 1
		expander.expandSeries(event, emptyList(), range("2026-04-01T11:00", "2026-04-01T12:00")).shouldBeEmpty()
		expander.expandSeries(event, emptyList(), range("2026-04-01T12:00:00.001", "2026-04-01T13:00")).shouldBeEmpty()
	}

	@Test
	fun `occurrence ending exactly at the range start or starting exactly at its end is excluded`() {
		val event = timed("2026-04-01T09:00", "2026-04-01T10:00", "FREQ=DAILY")
		expander.expandSeries(event, emptyList(), range("2026-04-02T10:00", "2026-04-03T09:00")).shouldBeEmpty()
		localStarts(expander.expandSeries(event, emptyList(), range("2026-04-02T09:59", "2026-04-03T09:01"))) shouldContainExactly
			listOf("2026-04-02T09:00", "2026-04-03T09:00")
	}

	@Test
	fun `occurrences longer than the interval overlap and are all returned`() {
		// Every Monday, lasting ten days: two occurrences cover any moment after the second week.
		val event = timed("2026-01-05T08:00", "2026-01-15T08:00", "FREQ=WEEKLY")
		val occurrences = expander.expandSeries(event, emptyList(), range("2026-02-03T00:00", "2026-02-04T00:00"))
		localStarts(occurrences) shouldContainExactly listOf("2026-01-26T08:00", "2026-02-02T08:00")
	}

	@Test
	fun `the range must end after it starts`() {
		assertThrows<IllegalArgumentException> { range("2026-04-01T00:00", "2026-04-01T00:00") }
		assertThrows<IllegalArgumentException> { range("2026-04-02T00:00", "2026-04-01T00:00") }
	}

	// --- All-day events and the user's zone ---

	@Test
	fun `all-day range days follow the user's zone, not UTC`() {
		// 2026-05-01T00:00 in Auckland is 2026-04-30T12:00Z: the range covers May 1 and 2 there.
		val range = range("2026-05-01T00:00", "2026-05-03T00:00", auckland)
		range.fromDate shouldBe LocalDate.parse("2026-05-01")
		range.toDate shouldBe LocalDate.parse("2026-05-03")
		val daily = allDay("2026-04-25", rrule = "FREQ=DAILY")
		startDates(expander.expandSeries(daily, emptyList(), range)) shouldContainExactly listOf("2026-05-01", "2026-05-02")
	}

	@Test
	fun `range ending one minute after midnight in the user's zone touches the next day`() {
		val daily = allDay("2026-04-25", rrule = "FREQ=DAILY")
		startDates(expander.expandSeries(daily, emptyList(), range("2026-05-01T23:00", "2026-05-02T00:01", newYork))) shouldContainExactly
			listOf("2026-05-01", "2026-05-02")
		startDates(expander.expandSeries(daily, emptyList(), range("2026-05-01T23:00", "2026-05-02T00:00", newYork))) shouldContainExactly
			listOf("2026-05-01")
	}

	@Test
	fun `multi-day all-day occurrence that started before the range is included`() {
		// A three-day retreat every month, starting on the 28th.
		val retreat = allDay("2026-01-28", "2026-01-31", "FREQ=MONTHLY")
		val occurrences = expander.expandSeries(retreat, emptyList(), range("2026-03-01T00:00", "2026-03-02T00:00"))
		occurrences.map { it.startDate to it.endDate } shouldContainExactly
			listOf(LocalDate.parse("2026-02-28") to LocalDate.parse("2026-03-03"))
	}

	@Test
	fun `all-day override is shown on its new date`() {
		val master = allDay("2026-06-01", rrule = "FREQ=WEEKLY;COUNT=3")
		val moved =
			allDay("2026-06-10", title = "Fictitious holiday (moved)").apply {
				overrideOf(master, OccurrenceId.of(LocalDate.parse("2026-06-08")))
			}
		val occurrences = expander.expand(listOf(master, moved), range("2026-06-01T00:00", "2026-07-01T00:00"))
		startDates(occurrences) shouldContainExactly listOf("2026-06-01", "2026-06-10", "2026-06-15")
		occurrences[1].apply {
			event shouldBe moved
			seriesId shouldBe master.id
			occurrenceId shouldBe OccurrenceId.of(LocalDate.parse("2026-06-08"))
		}
	}

	@Test
	fun `all-day and timed occurrences on the same day are sorted by the start of the day in the user's zone`() {
		val timedEarly = timed("2026-07-02T00:30", "2026-07-02T01:00", zone = auckland)
		val holiday = allDay("2026-07-02")
		val occurrences = expander.expand(listOf(timedEarly, holiday), range("2026-07-02T00:00", "2026-07-03T00:00", auckland))
		occurrences.map { it.event } shouldContainExactly listOf(holiday, timedEarly)
	}

	@Test
	fun `all-day series starting on or after the last day of the range has no occurrences`() {
		val daily = allDay("2026-05-03", rrule = "FREQ=DAILY")
		expander.expandSeries(daily, emptyList(), range("2026-05-01T00:00", "2026-05-03T00:00")).shouldBeEmpty()
		startDates(expander.expandSeries(daily, emptyList(), range("2026-05-01T00:00", "2026-05-03T00:01"))) shouldContainExactly
			listOf("2026-05-03")
	}

	@Test
	fun `occurrences starting at the same moment are ordered by event id`() {
		val first = timed("2026-07-02T10:00", "2026-07-02T11:00", title = "Fictitious review A")
		val second = timed("2026-07-02T10:00", "2026-07-02T10:30", title = "Fictitious review B")
		val expected = listOf(first, second).sortedBy { it.id }
		expander.expand(listOf(second, first), range("2026-07-02T00:00", "2026-07-03T00:00")).map { it.event } shouldContainExactly expected
		expander.expand(listOf(first, second), range("2026-07-02T00:00", "2026-07-03T00:00")).map { it.event } shouldContainExactly expected

		val master = timed("2026-07-01T10:00", "2026-07-01T11:00", "FREQ=DAILY")
		val movedOnto =
			timed("2026-07-02T10:00", "2026-07-02T11:00").apply { overrideOf(master, OccurrenceId.of(at("2026-07-03T10:00"))) }
		expander.expandSeries(master, listOf(movedOnto), range("2026-07-02T00:00", "2026-07-03T00:00")).map { it.event } shouldContainExactly
			listOf(master, movedOnto).sortedBy { it.id }
	}

	// --- Overrides ---

	@Test
	fun `deleted override no longer replaces its occurrence`() {
		val master = timed("2026-06-01T09:00", "2026-06-01T10:00", "FREQ=DAILY;COUNT=3")
		val moved =
			timed("2026-06-02T15:00", "2026-06-02T16:00").apply {
				overrideOf(master, OccurrenceId.of(at("2026-06-02T09:00")))
				softDelete(fixedClock)
			}
		val cancelled =
			timed("2026-06-03T09:00", "2026-06-03T10:00").apply {
				overrideOf(master, OccurrenceId.of(at("2026-06-03T09:00")))
				status = EventStatus.CANCELLED
				softDelete(fixedClock)
			}
		val viaExpand = expander.expand(listOf(master, moved, cancelled), range("2026-06-01T00:00", "2026-06-05T00:00"))
		localStarts(viaExpand) shouldContainExactly listOf("2026-06-01T09:00", "2026-06-02T09:00", "2026-06-03T09:00")
		viaExpand.forEach { it.event shouldBe master }
		val viaSeries = expander.expandSeries(master, listOf(moved, cancelled), range("2026-06-01T00:00", "2026-06-05T00:00"))
		localStarts(viaSeries) shouldContainExactly listOf("2026-06-01T09:00", "2026-06-02T09:00", "2026-06-03T09:00")
	}

	@Test
	fun `override is shown even when its recurrence id is also excluded or no longer matches the rule`() {
		val master = timed("2026-06-01T09:00", "2026-06-01T10:00", "FREQ=WEEKLY")
		master.exdates.add(OccurrenceId.of(at("2026-06-08T09:00")))
		val excludedButOverridden =
			timed("2026-06-09T09:00", "2026-06-09T10:00").apply { overrideOf(master, OccurrenceId.of(at("2026-06-08T09:00"))) }
		val stale =
			timed("2026-06-17T11:00", "2026-06-17T12:00").apply { overrideOf(master, OccurrenceId.of(at("2026-06-16T09:00"))) }
		val occurrences = expander.expand(listOf(master, excludedButOverridden, stale), range("2026-06-01T00:00", "2026-06-22T00:00"))
		localStarts(occurrences) shouldContainExactly
			listOf("2026-06-01T09:00", "2026-06-09T09:00", "2026-06-15T09:00", "2026-06-17T11:00")
	}

	@Test
	fun `expandSeries ignores overrides of another series`() {
		val master = timed("2026-06-01T09:00", "2026-06-01T10:00", "FREQ=DAILY;COUNT=2")
		val other = timed("2026-06-01T09:00", "2026-06-01T10:00", "FREQ=DAILY;COUNT=2")
		val foreign =
			timed("2026-06-02T18:00", "2026-06-02T19:00").apply { overrideOf(other, OccurrenceId.of(at("2026-06-02T09:00"))) }
		val occurrences = expander.expandSeries(master, listOf(foreign), range("2026-06-01T00:00", "2026-06-03T00:00"))
		localStarts(occurrences) shouldContainExactly listOf("2026-06-01T09:00", "2026-06-02T09:00")
		occurrences.forEach { it.event shouldBe master }
	}

	@Test
	fun `expandSeries rejects an override and returns nothing for a deleted master`() {
		val master = timed("2026-06-01T09:00", "2026-06-01T10:00", "FREQ=DAILY")
		val override =
			timed("2026-06-02T18:00", "2026-06-02T19:00").apply { overrideOf(master, OccurrenceId.of(at("2026-06-02T09:00"))) }
		assertThrows<IllegalArgumentException> {
			expander.expandSeries(override, emptyList(), range("2026-06-01T00:00", "2026-06-03T00:00"))
		}
		master.softDelete(fixedClock)
		expander.expandSeries(master, emptyList(), range("2026-06-01T00:00", "2026-06-03T00:00")).shouldBeEmpty()
	}

	@Test
	fun `override whose master is not among the candidates is shown standalone`() {
		// The series starts on 2026-06-10 (after the range); its first occurrence was moved into the range.
		val master = timed("2026-06-10T09:00", "2026-06-10T10:00", "FREQ=DAILY")
		val movedEarlier =
			timed("2026-06-05T09:00", "2026-06-05T10:00").apply { overrideOf(master, OccurrenceId.of(at("2026-06-10T09:00"))) }
		val outside =
			timed("2026-06-20T09:00", "2026-06-20T10:00").apply { overrideOf(master, OccurrenceId.of(at("2026-06-11T09:00"))) }
		val occurrences = expander.expand(listOf(movedEarlier, outside), range("2026-06-01T00:00", "2026-06-08T00:00"))
		occurrences.single().apply {
			event shouldBe movedEarlier
			seriesId shouldBe null
			occurrenceId shouldBe null
			start shouldBe at("2026-06-05T09:00")
		}
	}

	@Test
	fun `cancelled override is never shown, also when its master is not among the candidates`() {
		// Same series as above, but the moved occurrence was then cancelled. With the master present it is hidden
		// (expandSeries skips cancelled overrides); without it, it must not reappear as a standalone event.
		val master = timed("2026-06-10T09:00", "2026-06-10T10:00", "FREQ=DAILY")
		val movedThenCancelled =
			timed("2026-06-05T09:00", "2026-06-05T10:00").apply {
				overrideOf(master, OccurrenceId.of(at("2026-06-10T09:00")))
				status = EventStatus.CANCELLED
			}
		expander
			.expand(listOf(master, movedThenCancelled), range("2026-06-01T00:00", "2026-06-11T00:00"))
			.shouldBeEmpty()
		expander.expand(listOf(movedThenCancelled), range("2026-06-01T00:00", "2026-06-08T00:00")).shouldBeEmpty()
	}

	// --- Rules ical4j cannot parse or expand (CR-1) ---

	@Test
	fun `a series with an invalid rule keeps its first occurrence and does not break the other events`() {
		val single = timed("2026-06-03T12:00", "2026-06-03T13:00", title = "Fictitious lunch")
		listOf("FREQ=FORTNIGHTLY", "", "FREQ=WEEKLY;BYDAY=XX").forEach { rule ->
			val bad = timed("2026-06-01T09:00", "2026-06-01T10:00", rule)
			val occurrences = expander.expand(listOf(bad, single), range("2026-06-01T00:00", "2026-06-15T00:00"))
			occurrences.map { it.event } shouldContainExactly listOf(bad, single)
			occurrences.first().apply {
				start shouldBe at("2026-06-01T09:00")
				end shouldBe at("2026-06-01T10:00")
				seriesId shouldBe bad.id
				occurrenceId shouldBe OccurrenceId.of(at("2026-06-01T09:00"))
			}
			// The first occurrence is still subject to the range.
			expander.expand(listOf(bad, single), range("2026-06-02T00:00", "2026-06-15T00:00")).map { it.event } shouldContainExactly
				listOf(single)
		}
	}

	@Test
	fun `an all-day series with an invalid rule keeps its first occurrence`() {
		val bad = allDay("2026-06-01", rrule = "FREQ=FORTNIGHTLY")
		startDates(expander.expandSeries(bad, emptyList(), range("2026-06-01T00:00", "2026-07-01T00:00"))) shouldContainExactly
			listOf("2026-06-01")
	}

	@Test
	fun `overrides still apply to a series with an invalid rule`() {
		val bad = timed("2026-06-01T09:00", "2026-06-01T10:00", "FREQ=FORTNIGHTLY")
		val moved =
			timed("2026-06-01T15:00", "2026-06-01T16:00").apply { overrideOf(bad, OccurrenceId.of(at("2026-06-01T09:00"))) }
		val occurrences = expander.expand(listOf(bad, moved), range("2026-06-01T00:00", "2026-06-15T00:00"))
		occurrences.single().apply {
			event shouldBe moved
			start shouldBe at("2026-06-01T15:00")
			seriesId shouldBe bad.id
		}
	}

	@Test
	fun `a date-only UNTIL on a timed series includes that whole day in the event's zone`() {
		val event = timed("2026-01-05T09:00", "2026-01-05T10:00", "FREQ=DAILY;UNTIL=20260108")
		localStarts(expander.expandSeries(event, emptyList(), range("2026-01-01T00:00", "2026-02-01T00:00"))) shouldContainExactly
			listOf("2026-01-05T09:00", "2026-01-06T09:00", "2026-01-07T09:00", "2026-01-08T09:00")
		val late = timed("2026-01-05T23:30", "2026-01-05T23:45", "UNTIL=20260106;FREQ=DAILY")
		localStarts(expander.expandSeries(late, emptyList(), range("2026-01-01T00:00", "2026-02-01T00:00"))) shouldContainExactly
			listOf("2026-01-05T23:30", "2026-01-06T23:30")
	}

	// --- Ambiguous local times at DST end (CR-3) ---

	@Test
	fun `ambiguous time at DST end uses the earlier offset also for a series starting in winter time`() {
		// 2026-10-25 02:30 exists twice in Zurich (+02:00 and +01:00); the seed is in winter time (+01:00).
		val event = timed("2026-01-04T02:30", "2026-01-04T03:00", "FREQ=WEEKLY;BYDAY=SU")
		val occurrences = expander.expandSeries(event, emptyList(), range("2026-10-18T00:00", "2026-11-02T00:00"))
		occurrences.map { it.start } shouldContainExactly
			listOf(
				Instant.parse("2026-10-18T00:30:00Z"),
				Instant.parse("2026-10-25T00:30:00Z"),
				Instant.parse("2026-11-01T01:30:00Z"),
			)
		occurrences[1].occurrenceId shouldBe OccurrenceId.of(Instant.parse("2026-10-25T00:30:00Z"))
	}

	@Test
	fun `a series starting at the later of two ambiguous times keeps its start as the first occurrence`() {
		val laterStart = Instant.parse("2026-10-25T01:30:00Z") // 02:30+01:00, the second 02:30 in Zurich
		val event =
			CalendarEvent(calendarId, "Fictitious night shift").apply {
				setTimed(laterStart, laterStart.plus(Duration.ofMinutes(30)), zurich)
				rrule = "FREQ=WEEKLY;COUNT=2"
			}
		expander.expandSeries(event, emptyList(), range("2026-10-24T00:00", "2026-11-03T00:00")).map { it.start } shouldContainExactly
			listOf(laterStart, Instant.parse("2026-11-01T01:30:00Z"))
	}

	@Test
	fun `expand with no events or only deleted events yields nothing`() {
		expander.expand(emptyList(), range("2026-06-01T00:00", "2026-06-08T00:00")).shouldBeEmpty()
		val deleted = timed("2026-06-02T09:00", "2026-06-02T10:00", "FREQ=DAILY").apply { softDelete(fixedClock) }
		expander.expand(listOf(deleted), range("2026-06-01T00:00", "2026-06-08T00:00")).shouldBeEmpty()
	}
}
