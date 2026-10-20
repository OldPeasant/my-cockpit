package ch.sonensei.cockpit.calendar.api

import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.EventTransparency
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.core.web.InvalidRequestException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Time resolution and series arithmetic of [Timing], and [EventContent] propagation; no Spring context. */
class EventCommandsTest {
	private val zurich = ZoneId.of("Europe/Zurich")
	private val london = ZoneId.of("Europe/London")
	private val newYork = ZoneId.of("America/New_York")

	private fun at(value: String) = Instant.parse(value)

	private fun date(value: String) = LocalDate.parse(value)

	private fun timed(
		start: String,
		end: String,
		zone: ZoneId = zurich,
	) = Timing.Timed(at(start), at(end), zone)

	private fun allDay(
		start: String,
		end: String,
	) = Timing.AllDay(date(start), date(end))

	private fun fieldsOf(block: () -> Unit): List<String> = shouldThrow<InvalidRequestException>(block).fieldErrors.map { it.field }

	@Nested
	inner class Resolve {
		@Test
		fun `a new timed event lasts one hour in the default zone`() {
			Timing.resolve(null, TimingInput(start = at("2026-03-02T13:00:00Z")), zurich) shouldBe
				timed("2026-03-02T13:00:00Z", "2026-03-02T14:00:00Z")
		}

		@Test
		fun `a sent zone wins over the default and the current zone`() {
			val current = timed("2026-03-02T13:00:00Z", "2026-03-02T13:30:00Z")

			Timing.resolve(current, TimingInput(timeZone = "America/New_York"), zurich) shouldBe
				timed("2026-03-02T13:00:00Z", "2026-03-02T13:30:00Z", newYork)
		}

		@Test
		fun `moving the start keeps the current duration and zone`() {
			val current = timed("2026-03-02T13:00:00Z", "2026-03-02T13:30:00Z", london)

			Timing.resolve(current, TimingInput(start = at("2026-03-05T08:00:00Z")), zurich) shouldBe
				timed("2026-03-05T08:00:00Z", "2026-03-05T08:30:00Z", london)
		}

		@Test
		fun `start and end are truncated to microseconds`() {
			val result =
				Timing.resolve(
					null,
					TimingInput(start = at("2026-03-02T13:00:00.123456789Z"), end = at("2026-03-02T14:00:00.000000999Z")),
					zurich,
				) as Timing.Timed

			result.start shouldBe at("2026-03-02T13:00:00.123456Z")
			result.end shouldBe at("2026-03-02T14:00:00Z")
		}

		@Test
		fun `an end equal to the start is allowed, one before it is not`() {
			val start = at("2026-03-02T13:00:00Z")
			(Timing.resolve(null, TimingInput(start = start, end = start), zurich) as Timing.Timed).duration shouldBe Duration.ZERO

			fieldsOf { Timing.resolve(null, TimingInput(start = start, end = start.minusMillis(1)), zurich) } shouldBe listOf("end")
		}

		@Test
		fun `every timed field error is reported at once`() {
			fieldsOf {
				Timing.resolve(
					null,
					TimingInput(timeZone = "Mars/Olympus", startDate = date("2026-03-02"), endDate = date("2026-03-03")),
					zurich,
				)
			} shouldContainExactlyInAnyOrder listOf("startDate", "endDate", "timeZone", "start")
		}

		@Test
		fun `a malformed zone id is a field error, not a server error`() {
			fieldsOf { Timing.resolve(null, TimingInput(start = at("2026-03-02T13:00:00Z"), timeZone = "+99:00"), zurich) } shouldBe
				listOf("timeZone")
			fieldsOf { Timing.resolve(null, TimingInput(start = at("2026-03-02T13:00:00Z"), timeZone = ""), zurich) } shouldBe
				listOf("timeZone")
		}

		@Test
		fun `a new all-day event lasts one day`() {
			Timing.resolve(null, TimingInput(allDay = true, startDate = date("2026-03-02")), zurich) shouldBe
				allDay("2026-03-02", "2026-03-03")
		}

		@Test
		fun `moving an all-day event keeps its number of days`() {
			val current = allDay("2026-07-06", "2026-07-11")

			Timing.resolve(current, TimingInput(startDate = date("2026-08-03")), zurich) shouldBe allDay("2026-08-03", "2026-08-08")
			Timing.resolve(current, TimingInput(endDate = date("2026-07-07")), zurich) shouldBe allDay("2026-07-06", "2026-07-07")
		}

		@Test
		fun `turning a timed event all-day takes the day of its start in its own zone`() {
			// 23:30Z on 2 March is 00:30 on 3 March in Zurich.
			val current = timed("2026-03-02T23:30:00Z", "2026-03-03T00:30:00Z")

			Timing.resolve(current, TimingInput(allDay = true), ZoneId.of("UTC")) shouldBe allDay("2026-03-03", "2026-03-04")
		}

		@Test
		fun `turning an all-day event timed needs a start and uses the default zone`() {
			val current = allDay("2026-03-02", "2026-03-05")

			fieldsOf { Timing.resolve(current, TimingInput(allDay = false), zurich) } shouldBe listOf("start")
			Timing.resolve(current, TimingInput(allDay = false, start = at("2026-03-02T08:00:00Z")), london) shouldBe
				timed("2026-03-02T08:00:00Z", "2026-03-02T09:00:00Z", london)
		}

		@Test
		fun `every all-day field error is reported at once`() {
			fieldsOf {
				Timing.resolve(
					null,
					TimingInput(allDay = true, start = at("2026-03-02T08:00:00Z"), end = at("2026-03-02T09:00:00Z"), timeZone = "UTC"),
					zurich,
				)
			} shouldContainExactlyInAnyOrder listOf("start", "end", "timeZone", "startDate")
			fieldsOf {
				Timing.resolve(null, TimingInput(allDay = true, startDate = date("2026-03-05"), endDate = date("2026-03-04")), zurich)
			} shouldBe listOf("endDate")
		}

		@Test
		fun `nothing sent keeps the current times`() {
			val current = allDay("2026-03-02", "2026-03-05")
			TimingInput().isSent shouldBe false
			Timing.resolve(current, TimingInput(), zurich) shouldBe current
		}
	}

	@Nested
	inner class Occurrences {
		@Test
		fun `an occurrence of a timed series starts at its id with the master's duration`() {
			timed("2026-03-02T08:00:00Z", "2026-03-02T08:45:00Z").occurrence(OccurrenceId.of(at("2026-03-30T07:00:00Z"))) shouldBe
				timed("2026-03-30T07:00:00Z", "2026-03-30T07:45:00Z")
		}

		@Test
		fun `an occurrence of an all-day series has the master's number of days`() {
			allDay("2026-03-02", "2026-03-04").occurrence(OccurrenceId.of(date("2026-03-09"))) shouldBe allDay("2026-03-09", "2026-03-11")
		}

		@Test
		fun `the occurrence id of a master is its start`() {
			timed("2026-03-02T08:00:00Z", "2026-03-02T09:00:00Z").occurrenceId() shouldBe OccurrenceId.of(at("2026-03-02T08:00:00Z"))
			allDay("2026-03-02", "2026-03-03").occurrenceId() shouldBe OccurrenceId.of(date("2026-03-02"))
		}

		@Test
		fun `occurrence ids order by time, dates by day`() {
			(OccurrenceId.of(at("2026-03-02T08:00:00Z")) < OccurrenceId.of(at("2026-03-02T08:00:01Z"))) shouldBe true
			(OccurrenceId.of(at("2026-03-02T08:00:00Z")).compareTo(OccurrenceId.of(at("2026-03-02T08:00:00Z")))) shouldBe 0
			(OccurrenceId.of(date("2026-03-03")) > OccurrenceId.of(date("2026-03-02"))) shouldBe true
		}
	}

	@Nested
	inner class Shifted {
		/** Every Monday 09:00-10:00 in Zurich from 2 March 2026 (CET, +01:00). */
		private val master = timed("2026-03-02T08:00:00Z", "2026-03-02T09:00:00Z")

		@Test
		fun `moving an occurrence after the DST change moves the master by the same wall-clock offset`() {
			// 30 March (CEST, +02:00): 09:00 local is 07:00Z; dragged to 10:30 local, 30 minutes long.
			val from = master.occurrence(OccurrenceId.of(at("2026-03-30T07:00:00Z")))
			val to = timed("2026-03-30T08:30:00Z", "2026-03-30T09:00:00Z")

			master.shifted(from, to) shouldBe timed("2026-03-02T09:30:00Z", "2026-03-02T10:00:00Z")
		}

		@Test
		fun `a new zone keeps the local offset in that zone`() {
			val to = timed("2026-03-02T08:00:00Z", "2026-03-02T09:00:00Z", london)

			// 09:00 Zurich local becomes 08:00 London local: 08:00Z in March.
			master.shifted(master, to) shouldBe timed("2026-03-02T08:00:00Z", "2026-03-02T09:00:00Z", london)
		}

		@Test
		fun `a timed master turned all-day by one occurrence moves by days`() {
			val from = master.occurrence(OccurrenceId.of(at("2026-03-16T08:00:00Z")))

			master.shifted(from, allDay("2026-03-17", "2026-03-19")) shouldBe allDay("2026-03-03", "2026-03-05")
		}

		@Test
		fun `an all-day master moves by days and takes the new length`() {
			val gym = allDay("2026-03-02", "2026-03-03")

			gym.shifted(gym.occurrence(OccurrenceId.of(date("2026-03-04"))), allDay("2026-03-06", "2026-03-08")) shouldBe
				allDay("2026-03-04", "2026-03-06")
		}

		@Test
		fun `an all-day master turned timed starts at the new local time on its moved day`() {
			val gym = allDay("2026-03-02", "2026-03-03")
			val from = gym.occurrence(OccurrenceId.of(date("2026-03-04")))

			// 4 March 07:30 local (06:30Z); the master moves to 2 March 07:30 local.
			gym.shifted(from, timed("2026-03-04T06:30:00Z", "2026-03-04T07:00:00Z")) shouldBe
				timed("2026-03-02T06:30:00Z", "2026-03-02T07:00:00Z")
		}
	}

	@Nested
	inner class MoveOccurrenceId {
		private val master = timed("2026-03-02T08:00:00Z", "2026-03-02T09:00:00Z")

		@Test
		fun `a timed id keeps its wall-clock offset across DST`() {
			val later = timed("2026-03-02T09:00:00Z", "2026-03-02T10:00:00Z")

			// 30 March 09:00 CEST (07:00Z) moves to 10:00 CEST (08:00Z).
			master.moveOccurrenceId(OccurrenceId.of(at("2026-03-30T07:00:00Z")), later) shouldBe
				OccurrenceId.of(at("2026-03-30T08:00:00Z"))
		}

		@Test
		fun `a timed id moved to another zone keeps the local time there`() {
			val inNewYork = timed("2026-03-02T14:00:00Z", "2026-03-02T15:00:00Z", newYork)

			// 09:00 Zurich -> 09:00 New York; on 9 March New York is already on EDT (-04:00).
			master.moveOccurrenceId(OccurrenceId.of(at("2026-03-09T08:00:00Z")), inNewYork) shouldBe
				OccurrenceId.of(at("2026-03-09T13:00:00Z"))
		}

		@Test
		fun `a timed id is truncated to microseconds`() {
			val to = timed("2026-03-02T08:00:00.000000500Z", "2026-03-02T09:00:00Z")

			master.moveOccurrenceId(OccurrenceId.of(at("2026-03-09T08:00:00Z")), to)!!.at!!.nano shouldBe 0
		}

		@Test
		fun `an all-day id moves by days`() {
			allDay("2026-03-02", "2026-03-03").moveOccurrenceId(OccurrenceId.of(date("2026-03-09")), allDay("2026-03-04", "2026-03-05")) shouldBe
				OccurrenceId.of(date("2026-03-11"))
		}

		@Test
		fun `a change of kind or a mismatching id has no moved id`() {
			master.moveOccurrenceId(OccurrenceId.of(at("2026-03-09T08:00:00Z")), allDay("2026-03-02", "2026-03-03")) shouldBe null
			allDay("2026-03-02", "2026-03-03").moveOccurrenceId(OccurrenceId.of(date("2026-03-09")), master) shouldBe null
			master.moveOccurrenceId(OccurrenceId.of(date("2026-03-09")), master) shouldBe null
			allDay(
				"2026-03-02",
				"2026-03-03",
			).moveOccurrenceId(OccurrenceId.of(at("2026-03-09T08:00:00Z")), allDay("2026-03-03", "2026-03-04")) shouldBe
				null
		}
	}

	@Nested
	inner class Content {
		private fun event(title: String) =
			CalendarEvent(UUID.fromString("01900000-0000-7000-8000-000000000001"), title).apply {
				description = "Agenda"
				location = "Room A"
				attendees = listOf(Attendee("anna@example.com", "Anna"))
				reminderMinutes = listOf(10)
				conferenceUrl = "https://meet.example.com/a"
			}

		@Test
		fun `propagate changes only the fields an override still had at the old value`() {
			val master = event("Weekly")
			val before = EventContent.of(master)
			val override =
				event("Weekly").apply {
					location = "Room B"
					status = EventStatus.TENTATIVE
				}
			master.title = "Team sync"
			master.location = "Room C"
			master.description = null
			master.status = EventStatus.CONFIRMED
			master.transparency = EventTransparency.TRANSPARENT
			master.attendees = emptyList()
			master.reminderMinutes = listOf(5, 15)
			master.conferenceUrl = null

			EventContent.of(master).propagate(before, override)

			override.title shouldBe "Team sync"
			override.location shouldBe "Room B"
			override.description shouldBe null
			override.status shouldBe EventStatus.TENTATIVE
			override.transparency shouldBe EventTransparency.TRANSPARENT
			override.attendees shouldBe emptyList()
			override.reminderMinutes shouldBe listOf(5, 15)
			override.conferenceUrl shouldBe null
		}

		@Test
		fun `applyTo copies every content field`() {
			val source = event("Weekly").apply { transparency = EventTransparency.TRANSPARENT }
			val target = CalendarEvent(source.calendarId, "Other")

			EventContent.of(source).applyTo(target)

			EventContent.of(target) shouldBe EventContent.of(source)
			target.id shouldNotBe source.id
		}
	}
}
