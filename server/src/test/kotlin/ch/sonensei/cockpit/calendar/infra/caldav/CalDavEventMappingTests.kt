package ch.sonensei.cockpit.calendar.infra.caldav

import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.infra.caldav.CalDavEventMapping.IcsDuration
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.EventTransparency
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.sync.RemoteEvent
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import net.fortuna.ical4j.model.Recur
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.zone.ZoneOffsetTransitionRule
import java.time.zone.ZoneRules

/**
 * [CalDavEventMapping]: iCalendar resources of a CalDAV calendar to remote events (times and zones, recurrence, alarms,
 * attendees, status) and back (in-place writes that keep what the cockpit does not know, overrides, removed
 * occurrences), and the generated VTIMEZONEs. All data is fictitious.
 */
class CalDavEventMappingTests {
	// --- read: times

	@Test
	fun `an all-day event has date values, without DTEND it lasts one day, DURATION counts days`() {
		read("DTSTART;VALUE=DATE:20261224", "DTEND;VALUE=DATE:20261227").timing shouldBe
			Timing.AllDay(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 27))
		read("DTSTART;VALUE=DATE:20261224").timing shouldBe Timing.AllDay(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 25))
		read("DTSTART;VALUE=DATE:20261224", "DURATION:P3D").timing shouldBe Timing.AllDay(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 27))
		// A zero-length one is a day long, a date without VALUE=DATE is a date too.
		read("DTSTART:20261224", "DTEND:20261224").timing shouldBe Timing.AllDay(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 25))
	}

	@Test
	fun `a timed event in a zone, with DTEND, DURATION or neither`() {
		read("DTSTART;TZID=Europe/Zurich:20261103T090000", "DTEND;TZID=Europe/Zurich:20261103T100000").timing shouldBe
			timed("2026-11-03T08:00:00Z", "2026-11-03T09:00:00Z", ZURICH)
		read("DTSTART;TZID=Europe/Zurich:20261103T090000", "DURATION:PT1H30M").timing shouldBe
			timed("2026-11-03T08:00:00Z", "2026-11-03T09:30:00Z", ZURICH)
		read("DTSTART;TZID=Europe/Zurich:20261103T090000").timing shouldBe timed("2026-11-03T08:00:00Z", "2026-11-03T08:00:00Z", ZURICH)
	}

	@Test
	fun `DURATION days are calendar days in the event's zone, hours are exact, across the end of summer time`() {
		// Summer time in Zurich ends on 25 October 2026.
		read("DTSTART;TZID=Europe/Zurich:20261024T090000", "DURATION:P1D").timing shouldBe
			timed("2026-10-24T07:00:00Z", "2026-10-25T08:00:00Z", ZURICH)
		read("DTSTART;TZID=Europe/Zurich:20261024T090000", "DURATION:PT24H").timing shouldBe
			timed("2026-10-24T07:00:00Z", "2026-10-25T07:00:00Z", ZURICH)
	}

	@Test
	fun `DURATION across the start of summer time - days first in wall time, then the exact hours, weeks are 7 days`() {
		// Summer time in Zurich starts on 29 March 2026 (02:00 -> 03:00): that day has 23 hours.
		read("DTSTART;TZID=Europe/Zurich:20260328T090000", "DURATION:P1D").timing shouldBe
			timed("2026-03-28T08:00:00Z", "2026-03-29T07:00:00Z", ZURICH)
		read("DTSTART;TZID=Europe/Zurich:20260328T090000", "DURATION:PT24H").timing shouldBe
			timed("2026-03-28T08:00:00Z", "2026-03-29T08:00:00Z", ZURICH)
		read("DTSTART;TZID=Europe/Zurich:20260328T090000", "DURATION:P1DT1H").timing shouldBe
			timed("2026-03-28T08:00:00Z", "2026-03-29T08:00:00Z", ZURICH)
		read("DTSTART;TZID=Europe/Zurich:20260325T090000", "DURATION:P1W").timing shouldBe
			timed("2026-03-25T08:00:00Z", "2026-04-01T07:00:00Z", ZURICH)
		// In UTC a day is 24 hours.
		read("DTSTART:20260328T090000Z", "DURATION:P1D").timing shouldBe timed("2026-03-28T09:00:00Z", "2026-03-29T09:00:00Z", ZoneOffset.UTC)
	}

	@Test
	fun `an all-day DURATION whose days overflow when its hours are added skips the VEVENT instead of failing the resource`() {
		val data =
			calendar(
				"""
				BEGIN:VEVENT
				UID:overflow
				DTSTART;VALUE=DATE:20261103
				DURATION:P9223372036854775807DT24H
				END:VEVENT
				BEGIN:VEVENT
				UID:overflow
				RECURRENCE-ID;VALUE=DATE:20261110
				DTSTART;VALUE=DATE:20261111
				END:VEVENT
				""".trimIndent(),
			)

		CalDavEventMapping.parse(data, null).apply {
			uid shouldBe "overflow"
			events.shouldBeEmpty()
			// The master and, without a readable master, its override.
			skipped shouldBe 2
		}
		// An all-day DURATION with hours counts whole days of them.
		read("DTSTART;VALUE=DATE:20261103", "DURATION:P1DT48H").timing shouldBe
			Timing.AllDay(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 6))
	}

	@Test
	fun `UTC times are UTC, floating times are read as UTC`() {
		read("DTSTART:20261103T080000Z", "DTEND:20261103T090000Z").timing shouldBe
			timed("2026-11-03T08:00:00Z", "2026-11-03T09:00:00Z", ZoneOffset.UTC)
		read("DTSTART:20261103T090000", "DTEND:20261103T100000").timing shouldBe
			timed("2026-11-03T09:00:00Z", "2026-11-03T10:00:00Z", ZoneOffset.UTC)
	}

	@Test
	fun `TZIDs resolve as IANA ids, Windows names and path forms`() {
		read("DTSTART;TZID=W. Europe Standard Time:20261103T090000").timing shouldBe
			timed("2026-11-03T08:00:00Z", "2026-11-03T08:00:00Z", ZoneId.of("Europe/Berlin"))
		read("DTSTART;TZID=\"Eastern Standard Time\":20261103T090000").timing shouldBe
			timed("2026-11-03T14:00:00Z", "2026-11-03T14:00:00Z", ZoneId.of("America/New_York"))
		read("DTSTART;TZID=/mozilla.org/20050126_1/Europe/Zurich:20261103T090000").timing shouldBe
			timed("2026-11-03T08:00:00Z", "2026-11-03T08:00:00Z", ZURICH)
		read("DTSTART;TZID=/citadel.org/20190914_1/America/New_York:20260703T090000").timing shouldBe
			timed("2026-07-03T13:00:00Z", "2026-07-03T13:00:00Z", ZoneId.of("America/New_York"))
	}

	@Test
	fun `a custom VTIMEZONE resolves by X-LIC-LOCATION, else by its latest standard offset, else UTC`() {
		val located =
			"""
			BEGIN:VTIMEZONE
			TZID:Fictitious Office Time
			X-LIC-LOCATION:Asia/Tokyo
			BEGIN:STANDARD
			DTSTART:19700101T000000
			TZOFFSETFROM:+0100
			TZOFFSETTO:+0100
			END:STANDARD
			END:VTIMEZONE
			"""
		val offsetOnly =
			"""
			BEGIN:VTIMEZONE
			TZID:Fictitious Island Time
			BEGIN:STANDARD
			DTSTART:19500101T000000
			TZOFFSETFROM:+0600
			TZOFFSETTO:+0600
			END:STANDARD
			BEGIN:STANDARD
			DTSTART:19800101T000000
			TZOFFSETFROM:+0600
			TZOFFSETTO:+0530
			END:STANDARD
			BEGIN:DAYLIGHT
			DTSTART:19800601T000000
			TZOFFSETFROM:+0530
			TZOFFSETTO:+0630
			END:DAYLIGHT
			END:VTIMEZONE
			"""
		val daylightOnly =
			"""
			BEGIN:VTIMEZONE
			TZID:Fictitious Summer Time
			BEGIN:DAYLIGHT
			DTSTART:19700101T000000
			TZOFFSETFROM:-0300
			TZOFFSETTO:-0200
			END:DAYLIGHT
			END:VTIMEZONE
			"""

		readIn(located, "DTSTART;TZID=Fictitious Office Time:20261103T090000").timing shouldBe
			timed("2026-11-03T00:00:00Z", "2026-11-03T00:00:00Z", ZoneId.of("Asia/Tokyo"))
		readIn(offsetOnly, "DTSTART;TZID=Fictitious Island Time:20261103T090000").timing shouldBe
			timed("2026-11-03T03:30:00Z", "2026-11-03T03:30:00Z", ZoneOffset.ofHoursMinutes(5, 30))
		readIn(daylightOnly, "DTSTART;TZID=Fictitious Summer Time:20261103T090000").timing shouldBe
			timed("2026-11-03T11:00:00Z", "2026-11-03T11:00:00Z", ZoneOffset.ofHours(-2))
		read("DTSTART;TZID=Nowhere/Fictitious:20261103T090000").timing shouldBe
			timed("2026-11-03T09:00:00Z", "2026-11-03T09:00:00Z", ZoneOffset.UTC)
	}

	// --- read: recurrence

	@Test
	fun `EXDATEs of a timed series - zoned lists, UTC, dates at the series' time of day, other zones`() {
		val series =
			read(
				"DTSTART;TZID=Europe/Zurich:20261102T090000",
				"DTEND;TZID=Europe/Zurich:20261102T091500",
				"RRULE:FREQ=WEEKLY;BYDAY=MO",
				"EXDATE;TZID=Europe/Zurich:20261109T090000,20261116T090000",
				"EXDATE:20261123T080000Z",
				"EXDATE;VALUE=DATE:20261130",
				"EXDATE;TZID=W. Europe Standard Time:20261207T090000",
			)

		series.rrule shouldBe "FREQ=WEEKLY;BYDAY=MO"
		series.exdates shouldBe
			setOf("2026-11-09", "2026-11-16", "2026-11-23", "2026-11-30", "2026-12-07")
				.map { OccurrenceId.of(Instant.parse("${it}T08:00:00Z")) }
				.toSet()
	}

	@Test
	fun `EXDATEs of an all-day series are dates, also when written as times`() {
		val series =
			read(
				"DTSTART;VALUE=DATE:20261224",
				"RRULE:FREQ=YEARLY",
				"EXDATE;VALUE=DATE:20271224",
				"EXDATE;TZID=Europe/Zurich:20281224T000000",
				"EXDATE:20291224T120000Z",
			)

		series.exdates shouldBe setOf(2027, 2028, 2029).map { OccurrenceId.of(LocalDate.of(it, 12, 24)) }.toSet()
	}

	@Test
	fun `an event without RRULE has no EXDATEs`() {
		read("DTSTART:20261103T080000Z", "EXDATE:20261103T080000Z").apply {
			rrule.shouldBeNull()
			exdates.shouldBeEmpty()
		}
	}

	@Test
	fun `a resource is its master first, then its overrides - unreadable VEVENTs and other UIDs are skipped`() {
		val data =
			calendar(
				"""
				BEGIN:VEVENT
				UID:standup
				SUMMARY:Standup
				DTSTART;TZID=Europe/Zurich:20261102T090000
				DTEND;TZID=Europe/Zurich:20261102T091500
				RRULE:FREQ=WEEKLY;BYDAY=MO
				END:VEVENT
				BEGIN:VTODO
				UID:todo-1
				SUMMARY:Not an event
				END:VTODO
				BEGIN:VEVENT
				UID:standup
				RECURRENCE-ID;TZID=Europe/Zurich:20261109T090000
				SUMMARY:Standup (moved)
				DTSTART;TZID=Europe/Zurich:20261109T110000
				DTEND;TZID=Europe/Zurich:20261109T111500
				RRULE:FREQ=DAILY
				END:VEVENT
				BEGIN:VEVENT
				UID:standup
				RECURRENCE-ID;TZID=Europe/Zurich:20261116T090000
				SUMMARY:Broken
				DTSTART;TZID=Europe/Zurich:garbage
				END:VEVENT
				BEGIN:VEVENT
				UID:someone-else
				SUMMARY:Other
				DTSTART:20261103T080000Z
				END:VEVENT
				""",
			)

		val resource = CalDavEventMapping.parse(data, "\"etag-7\"")

		resource.uid shouldBe "standup"
		resource.skipped shouldBe 2
		resource.events.map { it.key } shouldContainExactly
			listOf(RemoteEventKey("standup"), RemoteEventKey("standup", OccurrenceId.of(Instant.parse("2026-11-09T08:00:00Z"))))
		resource.events.forEach { it.etag shouldBe "\"etag-7\"" }
		resource.events[1].apply {
			title shouldBe "Standup (moved)"
			// An override has no recurrence of its own.
			rrule.shouldBeNull()
			timing shouldBe timed("2026-11-09T10:00:00Z", "2026-11-09T10:15:00Z", ZURICH)
		}
	}

	@Test
	fun `overrides without a recurring master and a master that cannot be read are skipped`() {
		val noMaster =
			calendar(
				"""
				BEGIN:VEVENT
				UID:orphan
				RECURRENCE-ID:20261109T080000Z
				DTSTART:20261109T100000Z
				END:VEVENT
				""",
			)
		val unreadableMaster =
			calendar(
				"""
				BEGIN:VEVENT
				UID:broken
				SUMMARY:No start
				RRULE:FREQ=DAILY
				END:VEVENT
				BEGIN:VEVENT
				UID:broken
				RECURRENCE-ID:20261109T080000Z
				DTSTART:20261109T100000Z
				END:VEVENT
				""",
			)
		val singleWithOverride =
			calendar(
				"""
				BEGIN:VEVENT
				UID:single
				DTSTART:20261109T080000Z
				END:VEVENT
				BEGIN:VEVENT
				UID:single
				RECURRENCE-ID:20261109T080000Z
				DTSTART:20261109T100000Z
				END:VEVENT
				""",
			)

		CalDavEventMapping.parse(noMaster, null).apply {
			uid shouldBe "orphan"
			events.shouldBeEmpty()
			skipped shouldBe 1
		}
		CalDavEventMapping.parse(unreadableMaster, null).apply {
			events.shouldBeEmpty()
			skipped shouldBe 2
		}
		CalDavEventMapping.parse(singleWithOverride, null).apply {
			events.map { it.key } shouldContainExactly listOf(RemoteEventKey("single"))
			skipped shouldBe 1
		}
	}

	@Test
	fun `data without a VCALENDAR is refused`() {
		shouldThrow<IllegalArgumentException> { CalDavEventMapping.parse("BEGIN:VEVENT\r\nUID:x\r\nEND:VEVENT\r\n", null) }
		shouldThrow<IllegalArgumentException> { CalDavEventMapping.parse("<html>not found</html>", null) }
	}

	@Test
	fun `a resource without VEVENTs has no UID and no events`() {
		CalDavEventMapping.parse(calendar(""), null).apply {
			uid.shouldBeNull()
			events.shouldBeEmpty()
			skipped shouldBe 0
		}
	}

	// --- read: details

	@Test
	fun `alarms before the start are reminders in minutes - after the start, at the end and absolute ones are not`() {
		val triggers =
			listOf(
				"TRIGGER:-PT15M",
				"TRIGGER;RELATED=START:-P1D",
				"TRIGGER:PT0S",
				"TRIGGER:PT5M",
				"TRIGGER;RELATED=END:-PT5M",
				"TRIGGER;VALUE=DATE-TIME:20261103T070000Z",
				"TRIGGER:-PT1H",
				"TRIGGER:-PT60M",
				"TRIGGER:-P1W",
			)
		val event = read("DTSTART:20261103T080000Z", components = triggers.map { valarm(it) } + valarm("DESCRIPTION:No trigger"))

		event.reminderMinutes shouldContainExactly listOf(0, 15, 60, 1440, 10080)
	}

	@Test
	fun `attendees are mailto addresses with name and participation status`() {
		val event =
			read(
				"DTSTART:20261103T080000Z",
				"ATTENDEE;CN=\"Muster, Sam\";PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT:mailto:sam.muster@example.test",
				"ATTENDEE;PARTSTAT=declined:MAILTO:kim.demo@example.test",
				"ATTENDEE;CN=Alex;PARTSTAT=TENTATIVE:mailto:alex@example.test",
				"ATTENDEE;PARTSTAT=DELEGATED:mailto:robin@example.test",
				"ATTENDEE;PARTSTAT=X-FICTITIOUS;CN= :mailto:jo@example.test",
				"ATTENDEE;CN=Phone:tel:+41000000000",
				"ATTENDEE:mailto:",
			)

		event.attendees shouldContainExactly
			listOf(
				Attendee("sam.muster@example.test", "Muster, Sam", AttendeeStatus.ACCEPTED),
				Attendee("kim.demo@example.test", null, AttendeeStatus.DECLINED),
				Attendee("alex@example.test", "Alex", AttendeeStatus.TENTATIVE),
				Attendee("robin@example.test", null, AttendeeStatus.DELEGATED),
				Attendee("jo@example.test", null, AttendeeStatus.NEEDS_ACTION),
			)
	}

	@Test
	fun `status, transparency, conference link and text fields`() {
		val cancelled =
			read(
				"DTSTART:20261103T080000Z",
				"SUMMARY:Review\\, part 2",
				"DESCRIPTION:Agenda:\\n1. Costs\\; risks",
				"LOCATION:",
				"STATUS:CANCELLED",
				"TRANSP:TRANSPARENT",
				"CONFERENCE;VALUE=URI;FEATURE=PHONE:tel:+41000000000",
				"CONFERENCE;VALUE=URI;FEATURE=VIDEO:https://meet.example.test/fictitious-room",
			)
		cancelled.apply {
			title shouldBe "Review, part 2"
			description shouldBe "Agenda:\n1. Costs; risks"
			location.shouldBeNull()
			status shouldBe EventStatus.CANCELLED
			transparency shouldBe EventTransparency.TRANSPARENT
			conferenceUrl shouldBe "https://meet.example.test/fictitious-room"
		}
		read("DTSTART:20261103T080000Z", "STATUS:tentative").status shouldBe EventStatus.TENTATIVE
		read("DTSTART:20261103T080000Z", "STATUS:X-FICTITIOUS", "TRANSP:OPAQUE").apply {
			status shouldBe EventStatus.CONFIRMED
			transparency shouldBe EventTransparency.OPAQUE
			title shouldBe ""
			conferenceUrl.shouldBeNull()
		}
	}

	@Test
	fun `iCalendar durations`() {
		CalDavEventMapping.duration("P1W") shouldBe IcsDuration(7, Duration.ZERO)
		CalDavEventMapping.duration("-PT15M") shouldBe IcsDuration(0, Duration.ofMinutes(-15))
		CalDavEventMapping.duration("+P1DT2H3M4S") shouldBe IcsDuration(1, Duration.parse("PT2H3M4S"))
		// Days stay nominal: 24 hours are not a day.
		CalDavEventMapping.duration("PT24H") shouldBe IcsDuration(0, Duration.ofHours(24))
		CalDavEventMapping.duration("-P1DT1H") shouldBe IcsDuration(-1, Duration.ofHours(-1))
		CalDavEventMapping.duration("15M").shouldBeNull()
		CalDavEventMapping.duration("PT1.5H").shouldBeNull()
	}

	@Test
	fun `durations out of range are unreadable - such a DURATION skips its VEVENT, such a TRIGGER is no reminder`() {
		CalDavEventMapping.duration("P99999999999999999999D").shouldBeNull()
		CalDavEventMapping.duration("P9999999999999999999W").shouldBeNull()
		CalDavEventMapping.duration("PT9999999999999999H").shouldBeNull()
		CalDavEventMapping.duration("PT1H9223372036854775807S").shouldBeNull()

		listOf("DURATION:P200000000000000D", "DURATION:P99999999999999999999D", "DURATION:-PT9999999999999999H").forEach { duration ->
			val data = calendar("BEGIN:VEVENT\nUID:huge\nDTSTART;TZID=Europe/Zurich:20261103T090000\n$duration\nEND:VEVENT")
			CalDavEventMapping.parse(data, null).apply {
				events.shouldBeEmpty()
				skipped shouldBe 1
			}
		}
		val allDay = calendar("BEGIN:VEVENT\nUID:huge\nDTSTART;VALUE=DATE:20261103\nDURATION:P200000000000000D\nEND:VEVENT")
		CalDavEventMapping.parse(allDay, null).skipped shouldBe 1

		val event =
			read(
				"DTSTART:20261103T080000Z",
				components = listOf(valarm("TRIGGER:-P200000000000000D"), valarm("TRIGGER:-P99999999999999999999D"), valarm("TRIGGER:-PT10M")),
			)
		event.reminderMinutes shouldContainExactly listOf(10)
	}

	// --- write

	@Test
	fun `a new event is written with its zone's VTIMEZONE, escaped text, attendees, alarms and conference, and reads back the same`() {
		val event =
			RemoteEvent(
				key = RemoteEventKey("0192a5f0-0000-7000-8000-0000000000c1"),
				etag = null,
				title = "Planning, Q4",
				timing = timed("2026-10-28T08:00:00Z", "2026-10-28T09:00:00Z", ZURICH),
				description = "Room 3\nBring notes; and pens",
				location = "HQ, floor 2",
				attendees = listOf(Attendee("bob@example.test", "Bob"), Attendee("kim@example.test", "Demo, Kim", AttendeeStatus.ACCEPTED)),
				reminderMinutes = listOf(15, 0),
				status = EventStatus.TENTATIVE,
				transparency = EventTransparency.TRANSPARENT,
				conferenceUrl = "https://meet.example.test/fictitious-room",
			)
		val calendar = CalDavEventMapping.newCalendar()

		CalDavEventMapping.write(calendar, event, NOW)

		calendar.components.map { it.name } shouldContainExactly listOf("VTIMEZONE", "VEVENT")
		val text = calendar.format()
		text shouldContain "PRODID:${CalDavEventMapping.PRODID}\r\n"
		val vevent = unfolded(text)
		vevent shouldContain "DTSTAMP:20261021T080000Z\r\n"
		vevent shouldContain "LAST-MODIFIED:20261021T080000Z\r\n"
		vevent shouldContain "SEQUENCE:0\r\n"
		vevent shouldContain "SUMMARY:Planning\\, Q4\r\n"
		vevent shouldContain "DESCRIPTION:Room 3\\nBring notes\\; and pens\r\n"
		vevent shouldContain "DTSTART;TZID=Europe/Zurich:20261028T090000\r\n"
		vevent shouldContain "DTEND;TZID=Europe/Zurich:20261028T100000\r\n"
		vevent shouldContain "ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:bob@example.test\r\n"
		vevent shouldContain "ATTENDEE;CN=\"Demo, Kim\";PARTSTAT=ACCEPTED:mailto:kim@example.test\r\n"
		vevent shouldContain "TRIGGER:PT0S\r\n"
		vevent shouldContain "TRIGGER:-PT15M\r\n"
		vevent shouldContain "STATUS:TENTATIVE\r\n"
		vevent shouldContain "TRANSP:TRANSPARENT\r\n"
		vevent shouldContain "CONFERENCE;VALUE=URI;FEATURE=VIDEO:https://meet.example.test/fictitious-room\r\n"

		CalDavEventMapping.parse(text, "e1").events.single() shouldBe event.copy(etag = "e1", reminderMinutes = listOf(0, 15))
	}

	@Test
	fun `UTC and fixed offsets are written as UTC times without a VTIMEZONE, all-day events as dates`() {
		val utc = CalDavEventMapping.newCalendar()
		CalDavEventMapping.write(utc, event("utc", timed("2026-10-28T08:00:00Z", "2026-10-28T09:00:00Z", ZoneOffset.UTC)), NOW)
		val offset = CalDavEventMapping.newCalendar()
		CalDavEventMapping.write(offset, event("offset", timed("2026-10-28T08:00:00Z", "2026-10-28T09:00:00Z", ZoneOffset.ofHours(2))), NOW)
		val allDay = CalDavEventMapping.newCalendar()
		CalDavEventMapping.write(allDay, event("holiday", Timing.AllDay(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 27))), NOW)

		listOf(utc, offset).forEach { calendar ->
			calendar.children("VTIMEZONE").shouldBeEmpty()
			calendar.format() shouldContain "DTSTART:20261028T080000Z\r\nDTEND:20261028T090000Z\r\n"
		}
		allDay.children("VTIMEZONE").shouldBeEmpty()
		allDay.format() shouldContain "DTSTART;VALUE=DATE:20261224\r\nDTEND;VALUE=DATE:20261227\r\n"
		readBack(allDay.format()).timing shouldBe
			Timing.AllDay(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 27))
	}

	@Test
	fun `a series is written with its RRULE and sorted EXDATEs like its DTSTART - without RRULE they are dropped`() {
		val occurrences = setOf("2026-11-16", "2026-11-09").map { OccurrenceId.of(Instant.parse("${it}T08:00:00Z")) }.toSet()
		val series =
			event("series", timed("2026-11-02T08:00:00Z", "2026-11-02T08:15:00Z", ZURICH)).copy(
				rrule = "FREQ=WEEKLY;BYDAY=MO",
				exdates = occurrences,
			)
		val calendar = CalDavEventMapping.newCalendar()

		CalDavEventMapping.write(calendar, series, NOW)

		calendar.format() shouldContain
			"RRULE:FREQ=WEEKLY;BYDAY=MO\r\nEXDATE;TZID=Europe/Zurich:20261109T090000\r\nEXDATE;TZID=Europe/Zurich:20261116T090000\r\n"
		readBack(calendar.format()).exdates shouldBe occurrences

		CalDavEventMapping.write(calendar, series.copy(rrule = null), NOW)
		CalDavEventMapping.master(calendar, "series")!!.apply {
			property("RRULE").shouldBeNull()
			property("EXDATE").shouldBeNull()
			text("SEQUENCE") shouldBe "1"
		}
	}

	@Test
	fun `an all-day series is written with date EXDATEs`() {
		val series =
			event("yearly", Timing.AllDay(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 25))).copy(
				rrule = "FREQ=YEARLY",
				exdates = setOf(OccurrenceId.of(LocalDate.of(2027, 12, 24))),
			)
		val calendar = CalDavEventMapping.newCalendar()

		CalDavEventMapping.write(calendar, series, NOW)

		calendar.format() shouldContain "EXDATE;VALUE=DATE:20271224\r\n"
		CalDavEventMapping.parse(calendar.format(), null).events.single() shouldBe series
	}

	@Test
	fun `an edit changes the VEVENT in place - unknown properties, components, alarm details and attendee parameters stay`() {
		val calendar = CalDavEventMapping.calendar(SERVER_SERIES)
		val master = CalDavEventMapping.parse(SERVER_SERIES, "e1").events.first()
		val edited =
			master.copy(
				title = "Daily standup",
				location = "Room 4",
				reminderMinutes = listOf(15, 30),
				attendees = listOf(Attendee("sam@example.test", "Sam", AttendeeStatus.DECLINED), Attendee("alex@example.test", "Alex")),
			)

		CalDavEventMapping.write(calendar, edited, NOW)

		val text = calendar.format()
		text shouldContain "PRODID:-//Example//Fictitious Client//EN\r\n"
		text shouldContain "X-CUSTOM;X-PARAM=keep:kept value\r\n"
		text shouldContain "CATEGORIES:Work,Daily\r\n"
		text shouldContain "BEGIN:VTODO\r\nUID:todo-1\r\nSUMMARY:Unrelated task\r\nEND:VTODO\r\n"
		calendar.children("VTIMEZONE").size shouldBe 1
		val vevent = CalDavEventMapping.master(calendar, "standup")!!
		vevent.text("SEQUENCE") shouldBe "4"
		vevent.text("SUMMARY") shouldBe "Daily standup"
		vevent.property("DURATION").shouldBeNull()
		vevent.property("RDATE").shouldBeNull()
		vevent.property("DTEND")!!.format() shouldBe "DTEND;TZID=Europe/Zurich:20261102T091500"
		vevent.all("ATTENDEE").map { it.format() } shouldContainExactly
			listOf(
				"ATTENDEE;CN=Sam;ROLE=CHAIR;RSVP=TRUE;PARTSTAT=DECLINED:mailto:sam@example.test",
				"ATTENDEE;CN=Alex;PARTSTAT=NEEDS-ACTION:mailto:alex@example.test",
			)
		val alarms = vevent.children("VALARM")
		alarms.map { it.text("TRIGGER") } shouldContainExactly listOf("-PT15M", "20261101T080000Z", "-PT30M")
		alarms[0].text("ACTION") shouldBe "AUDIO"
		alarms[0].text("X-ALARM-ID") shouldBe "fictitious-alarm-1"
		// The override is untouched.
		val override = calendar.children("VEVENT").single { it.property("RECURRENCE-ID") != null }
		override.text("SUMMARY") shouldBe "Standup (moved)"
		override.text("X-OVERRIDE") shouldBe "kept too"

		CalDavEventMapping.parse(text, "e2").events.first() shouldBe edited.copy(etag = "e2")
	}

	@Test
	fun `a new override gets a RECURRENCE-ID with the TZID of the master's DTSTART`() {
		val calendar = CalDavEventMapping.calendar(SERVER_SERIES)
		val occurrence = OccurrenceId.of(Instant.parse("2026-11-23T08:00:00Z"))
		val override =
			event("standup", timed("2026-11-23T10:00:00Z", "2026-11-23T10:15:00Z", ZURICH))
				.copy(key = RemoteEventKey("standup", occurrence))

		CalDavEventMapping.write(calendar, override, NOW)

		val written = calendar.children("VEVENT").last()
		written.property("RECURRENCE-ID")!!.format() shouldBe "RECURRENCE-ID;TZID=Europe/Zurich:20261123T090000"
		written.property("RRULE").shouldBeNull()
		written.property("EXDATE").shouldBeNull()
		CalDavEventMapping
			.parse(calendar.format(), null)
			.events
			.single { it.key == override.key } shouldBe override
	}

	@Test
	fun `an existing override is changed in place`() {
		val calendar = CalDavEventMapping.calendar(SERVER_SERIES)
		val key = RemoteEventKey("standup", OccurrenceId.of(Instant.parse("2026-11-09T08:00:00Z")))
		val override = CalDavEventMapping.parse(SERVER_SERIES, null).events.single { it.key == key }

		CalDavEventMapping.write(calendar, override.copy(title = "Standup (moved again)"), NOW)

		val overrides = calendar.children("VEVENT").filter { it.property("RECURRENCE-ID") != null }
		overrides.size shouldBe 1
		overrides.single().text("SUMMARY") shouldBe "Standup (moved again)"
		overrides.single().text("X-OVERRIDE") shouldBe "kept too"
		overrides.single().text("SEQUENCE") shouldBe "0"
	}

	@Test
	fun `RECURRENCE-IDs follow UTC, custom-zone, floating and date DTSTARTs of the master`() {
		fun recurrenceId(
			dtstart: String,
			occurrence: OccurrenceId,
			timing: Timing,
			extra: String = "",
		): String {
			val data =
				calendar(
					extra +
						"""
						BEGIN:VEVENT
						UID:series
						$dtstart
						RRULE:FREQ=WEEKLY
						END:VEVENT
						""",
				)
			val calendar = CalDavEventMapping.calendar(data)
			val override = event("series", timing).copy(key = RemoteEventKey("series", occurrence))
			CalDavEventMapping.write(calendar, override, NOW)
			// What was written is read back as the same override.
			CalDavEventMapping
				.parse(calendar.format(), null)
				.events
				.map { it.key } shouldContainExactly listOf(RemoteEventKey("series"), override.key)
			return calendar
				.children("VEVENT")
				.last()
				.property("RECURRENCE-ID")!!
				.format()
		}
		val at = OccurrenceId.of(Instant.parse("2026-11-23T08:00:00Z"))
		val timing = timed("2026-11-23T10:00:00Z", "2026-11-23T11:00:00Z", ZURICH)
		val custom =
			"""
			BEGIN:VTIMEZONE
			TZID:Fictitious Office; Zurich
			X-LIC-LOCATION:Europe/Zurich
			END:VTIMEZONE
			"""

		recurrenceId("DTSTART:20261102T080000Z", at, timing) shouldBe "RECURRENCE-ID:20261123T080000Z"
		recurrenceId("DTSTART;TZID=\"Fictitious Office; Zurich\":20261102T090000", at, timing, custom) shouldBe
			"RECURRENCE-ID;TZID=\"Fictitious Office; Zurich\":20261123T090000"
		recurrenceId("DTSTART:20261102T080000", at, timing) shouldBe "RECURRENCE-ID:20261123T080000"
		recurrenceId(
			"DTSTART;VALUE=DATE:20261102",
			OccurrenceId.of(LocalDate.of(2026, 11, 23)),
			Timing.AllDay(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 25)),
		) shouldBe "RECURRENCE-ID;VALUE=DATE:20261123"
	}

	@Test
	fun `an override of a series that is not in the resource is refused`() {
		val calendar = CalDavEventMapping.newCalendar()
		val override =
			event("gone", timed("2026-11-23T10:00:00Z", "2026-11-23T11:00:00Z", ZURICH))
				.copy(key = RemoteEventKey("gone", OccurrenceId.of(Instant.parse("2026-11-23T08:00:00Z"))))

		shouldThrow<IllegalArgumentException> { CalDavEventMapping.write(calendar, override, NOW) }
	}

	@Test
	fun `removing an occurrence drops its override and adds an EXDATE like the DTSTART`() {
		val calendar = CalDavEventMapping.calendar(SERVER_SERIES)
		val moved = OccurrenceId.of(Instant.parse("2026-11-09T08:00:00Z"))

		CalDavEventMapping.removeOccurrence(calendar, "standup", moved, NOW)

		calendar.children("VEVENT").size shouldBe 1
		val master = CalDavEventMapping.master(calendar, "standup")!!
		master.all("EXDATE").map { it.format() } shouldContainExactly
			listOf(
				"EXDATE;TZID=Europe/Zurich:20261116T090000,20261130T090000",
				"EXDATE;TZID=Europe/Zurich:20261109T090000",
			)
		master.text("DTSTAMP") shouldBe "20261021T080000Z"
		readBack(calendar.format()).exdates shouldBe
			setOf("2026-11-09", "2026-11-16", "2026-11-30").map { OccurrenceId.of(Instant.parse("${it}T08:00:00Z")) }.toSet()
	}

	@Test
	fun `removing an occurrence that already is an EXDATE changes nothing`() {
		val calendar = CalDavEventMapping.calendar(SERVER_SERIES)
		val before = calendar.format()

		CalDavEventMapping.removeOccurrence(calendar, "standup", OccurrenceId.of(Instant.parse("2026-11-30T08:00:00Z")), NOW)

		calendar.format() shouldBe before
	}

	@Test
	fun `removing an occurrence of an all-day series adds a date EXDATE - without master only the override goes`() {
		val allDay =
			CalDavEventMapping.calendar(
				calendar(
					"""
					BEGIN:VEVENT
					UID:yearly
					DTSTART;VALUE=DATE:20261224
					RRULE:FREQ=YEARLY
					END:VEVENT
					""",
				),
			)
		CalDavEventMapping.removeOccurrence(allDay, "yearly", OccurrenceId.of(LocalDate.of(2027, 12, 24)), NOW)
		CalDavEventMapping.master(allDay, "yearly")!!.property("EXDATE")!!.format() shouldBe "EXDATE;VALUE=DATE:20271224"

		val orphan =
			CalDavEventMapping.calendar(
				calendar(
					"""
					BEGIN:VEVENT
					UID:orphan
					RECURRENCE-ID:20261109T080000Z
					DTSTART:20261109T100000Z
					END:VEVENT
					""",
				),
			)
		CalDavEventMapping.removeOccurrence(orphan, "orphan", OccurrenceId.of(Instant.parse("2026-11-09T08:00:00Z")), NOW)
		orphan.children("VEVENT").shouldBeEmpty()
	}

	// --- VTIMEZONE

	@Test
	fun `the VTIMEZONE of Europe Zurich has the EU summer time rules`() {
		val vtimezone = CalDavEventMapping.vtimezone(ZURICH)

		vtimezone.text("TZID") shouldBe "Europe/Zurich"
		val daylight = vtimezone.children("DAYLIGHT").single()
		daylight.text("DTSTART") shouldBe "19700329T020000"
		daylight.text("TZOFFSETFROM") shouldBe "+0100"
		daylight.text("TZOFFSETTO") shouldBe "+0200"
		daylight.text("RRULE") shouldBe "FREQ=YEARLY;BYMONTH=3;BYDAY=SU;BYMONTHDAY=25,26,27,28,29,30,31"
		val standard = vtimezone.children("STANDARD").single()
		standard.text("DTSTART") shouldBe "19701025T030000"
		standard.text("TZOFFSETFROM") shouldBe "+0200"
		standard.text("TZOFFSETTO") shouldBe "+0100"
		standard.text("RRULE") shouldBe "FREQ=YEARLY;BYMONTH=10;BYDAY=SU;BYMONTHDAY=25,26,27,28,29,30,31"
	}

	@Test
	fun `the VTIMEZONE of America New_York has the US rules`() {
		val vtimezone = CalDavEventMapping.vtimezone(ZoneId.of("America/New_York"))

		vtimezone.children("DAYLIGHT").single().apply {
			text("DTSTART") shouldBe "19700308T020000"
			text("TZOFFSETFROM") shouldBe "-0500"
			text("TZOFFSETTO") shouldBe "-0400"
			text("RRULE") shouldBe "FREQ=YEARLY;BYMONTH=3;BYDAY=SU;BYMONTHDAY=8,9,10,11,12,13,14"
		}
		vtimezone.children("STANDARD").single().apply {
			text("DTSTART") shouldBe "19701101T020000"
			text("TZOFFSETFROM") shouldBe "-0400"
			text("TZOFFSETTO") shouldBe "-0500"
			text("RRULE") shouldBe "FREQ=YEARLY;BYMONTH=11;BYDAY=SU;BYMONTHDAY=1,2,3,4,5,6,7"
		}
	}

	@Test
	fun `a zone without summer time has one STANDARD observance without RRULE`() {
		val vtimezone = CalDavEventMapping.vtimezone(ZoneId.of("Asia/Tokyo"))

		vtimezone.children("DAYLIGHT").shouldBeEmpty()
		vtimezone.children("STANDARD").single().apply {
			text("DTSTART") shouldBe "19700101T000000"
			text("TZOFFSETFROM") shouldBe "+0900"
			text("TZOFFSETTO") shouldBe "+0900"
			property("RRULE").shouldBeNull()
		}
		val kolkata = CalDavEventMapping.vtimezone(ZoneId.of("Asia/Kolkata"))
		kolkata.children("STANDARD").single().text("TZOFFSETTO") shouldBe "+0530"
	}

	@Test
	fun `a written zone resolves to itself when read back`() {
		listOf("Europe/Zurich", "America/New_York", "Asia/Tokyo", "Australia/Sydney").map(ZoneId::of).forEach { zone ->
			val calendar = CalDavEventMapping.newCalendar()
			CalDavEventMapping.write(calendar, event("zone", timed("2026-07-01T08:00:00Z", "2026-07-01T09:00:00Z", zone)), NOW)

			calendar.format() shouldContain "BEGIN:VTIMEZONE\r\nTZID:${zone.id}\r\n"
			readBack(calendar.format()).timing shouldBe
				timed("2026-07-01T08:00:00Z", "2026-07-01T09:00:00Z", zone)
		}
	}

	@Test
	fun `the RRULEs of every generated VTIMEZONE give the transitions of the Java zone rules`() {
		// Checked with ical4j's recurrence expansion, an implementation independent of the one under test.
		// Per zone and transition rule the first year whose transition its observances miss. A rule may have several
		// observances (one per month its wall-clock day can fall in) or list its transitions as RDATEs.
		val mismatches = sortedMapOf<String, String>()
		for (id in ZoneId.getAvailableZoneIds().sorted()) {
			val zone = ZoneId.of(id)
			if (zone.rules.transitionRules.isEmpty()) continue
			val vtimezone = CalDavEventMapping.vtimezone(zone)
			val observances = vtimezone.children("STANDARD") + vtimezone.children("DAYLIGHT")
			val byRule =
				observances.groupBy { observance ->
					val seed = LocalDateTime.parse(observance.text("DTSTART")!!, LOCAL)
					// Every observance starts at a transition of exactly one rule.
					zone.rules.transitionRules.single {
						it.createTransition(seed.year).dateTimeBefore == seed &&
							offsetText(it.offsetBefore) == observance.text("TZOFFSETFROM") &&
							offsetText(it.offsetAfter) == observance.text("TZOFFSETTO")
					}
				}
			for (rule in zone.rules.transitionRules) {
				val own = byRule[rule].orEmpty()
				for (year in 2027..2040) {
					val expected = rule.createTransition(year).dateTimeBefore
					val dates = own.flatMap { onsets(it, year) }.sorted()
					if (dates != listOf(expected)) mismatches.putIfAbsent("$id $rule", "$year: expected $expected, observances give $dates")
				}
			}
		}

		mismatches.entries.joinToString("\n") { (key, example) -> "$key ($example)" } shouldBe ""
	}

	@Test
	fun `a wall-clock transition that crosses into the next month gets an observance per month`() {
		// Egypt: last Thursday of October at 24:00, a Friday from 26 October to 1 November.
		val standard = CalDavEventMapping.vtimezone(ZoneId.of("Africa/Cairo")).children("STANDARD")

		standard.map { it.text("RRULE") } shouldContainExactly
			listOf("FREQ=YEARLY;BYMONTH=10;BYDAY=FR;BYMONTHDAY=26,27,28,29,30,31", "FREQ=YEARLY;BYMONTH=11;BYDAY=FR;BYMONTHDAY=1")
		standard.forEach { it.text("TZOFFSETTO") shouldBe "+0200" }
	}

	@Test
	fun `a wall-clock transition on the day before the rule's date shifts its weekday and days`() {
		// Nuuk: summer time starts on the last Sunday of March at 01:00 UTC, that is Saturday 23:00 local time.
		val daylight = CalDavEventMapping.vtimezone(ZoneId.of("America/Nuuk")).children("DAYLIGHT").single()

		daylight.text("RRULE") shouldBe "FREQ=YEARLY;BYMONTH=3;BYDAY=SA;BYMONTHDAY=24,25,26,27,28,29,30"
		daylight.text("DTSTART")!! shouldEndWith "T230000"
	}

	@Test
	fun `transitions whose month depends on leap years are listed as RDATEs`() {
		// A fictitious zone: summer time from the Sunday on or after 26 February, at 23:00 the day before in wall time.
		val rule =
			ZoneOffsetTransitionRule.of(
				Month.FEBRUARY,
				26,
				DayOfWeek.SUNDAY,
				LocalTime.of(1, 0),
				false,
				ZoneOffsetTransitionRule.TimeDefinition.UTC,
				ZoneOffset.ofHours(-2),
				ZoneOffset.ofHours(-2),
				ZoneOffset.ofHours(-1),
			)
		val rules = ZoneRules.of(ZoneOffset.ofHours(-2), ZoneOffset.ofHours(-2), emptyList(), emptyList(), listOf(rule))

		val daylight = CalDavEventMapping.vtimezone("Fictitious/Leap", rules).children("DAYLIGHT").single()

		daylight.property("RRULE").shouldBeNull()
		daylight.text("DTSTART") shouldBe rule.createTransition(1970).dateTimeBefore.format(LOCAL)
		for (year in 1971..2100) onsets(daylight, year) shouldContainExactly listOf(rule.createTransition(year).dateTimeBefore)
	}

	@Test
	fun `a last-weekday transition at 24 00 that moves into the next month gets an observance there`() {
		// A fictitious zone: summer time ends on the last Sunday of October at 24:00 wall time, that is Monday 00:00,
		// which falls on 1 November in some years (e.g. 2027, 2032).
		val rule =
			ZoneOffsetTransitionRule.of(
				Month.OCTOBER,
				-1,
				DayOfWeek.SUNDAY,
				LocalTime.MIDNIGHT,
				true,
				ZoneOffsetTransitionRule.TimeDefinition.WALL,
				ZoneOffset.ofHours(1),
				ZoneOffset.ofHours(2),
				ZoneOffset.ofHours(1),
			)
		val rules = ZoneRules.of(ZoneOffset.ofHours(1), ZoneOffset.ofHours(2), emptyList(), emptyList(), listOf(rule))

		val standard = CalDavEventMapping.vtimezone("Fictitious/Midnight", rules).children("STANDARD")

		standard.map { it.text("RRULE") } shouldContainExactly
			listOf("FREQ=YEARLY;BYMONTH=10;BYDAY=MO;BYMONTHDAY=-6,-5,-4,-3,-2,-1", "FREQ=YEARLY;BYMONTH=11;BYDAY=MO;BYMONTHDAY=1")
		standard.forEach {
			it.text("TZOFFSETFROM") shouldBe "+0200"
			it.text("TZOFFSETTO") shouldBe "+0100"
		}
		for (year in 1971..2100) {
			standard.flatMap { onsets(it, year) }.sorted() shouldContainExactly listOf(rule.createTransition(year).dateTimeBefore)
		}
	}

	@Test
	fun `a wall-clock transition on the day before reaches back into the previous month, counted from the start or the end`() {
		// Fictitious zones at UTC-3: summer time starts at 01:00 UTC on a Sunday, that is Saturday 22:00 local time.
		fun rules(
			month: Month,
			indicator: Int,
		): Pair<ZoneOffsetTransitionRule, ZoneRules> {
			val minus3 = ZoneOffset.ofHours(-3)
			val rule =
				ZoneOffsetTransitionRule.of(
					month,
					indicator,
					DayOfWeek.SUNDAY,
					LocalTime.of(1, 0),
					false,
					ZoneOffsetTransitionRule.TimeDefinition.UTC,
					minus3,
					minus3,
					ZoneOffset.ofHours(-2),
				)
			return rule to ZoneRules.of(minus3, minus3, emptyList(), emptyList(), listOf(rule))
		}

		// The Sunday on or after 1 April (1 to 7 April): Saturday 31 March to 6 April.
		val (onOrAfter, onOrAfterRules) = rules(Month.APRIL, 1)
		val fromStart = CalDavEventMapping.vtimezone("Fictitious/OnOrAfter", onOrAfterRules).children("DAYLIGHT")
		fromStart.map { it.text("RRULE") } shouldContainExactly
			listOf("FREQ=YEARLY;BYMONTH=3;BYDAY=SA;BYMONTHDAY=-1", "FREQ=YEARLY;BYMONTH=4;BYDAY=SA;BYMONTHDAY=1,2,3,4,5,6")

		// The Sunday on or before 6 April (the 25th day from the end; 31 March to 6 April): Saturday 30 March to 5 April.
		val (onOrBefore, onOrBeforeRules) = rules(Month.APRIL, -25)
		val fromEnd = CalDavEventMapping.vtimezone("Fictitious/OnOrBefore", onOrBeforeRules).children("DAYLIGHT")
		fromEnd.map { it.text("RRULE") } shouldContainExactly
			listOf("FREQ=YEARLY;BYMONTH=3;BYDAY=SA;BYMONTHDAY=-2,-1", "FREQ=YEARLY;BYMONTH=4;BYDAY=SA;BYMONTHDAY=-30,-29,-28,-27,-26")

		// Counted from the end of February, the days before depend on leap years: listed as RDATEs.
		val (february, februaryRules) = rules(Month.FEBRUARY, -28)
		val leap = CalDavEventMapping.vtimezone("Fictitious/February", februaryRules).children("DAYLIGHT").single()
		leap.property("RRULE").shouldBeNull()

		for ((rule, observances) in listOf(onOrAfter to fromStart, onOrBefore to fromEnd, february to listOf(leap))) {
			for (year in 1971..2100) {
				observances.flatMap { onsets(it, year) }.sorted() shouldContainExactly listOf(rule.createTransition(year).dateTimeBefore)
			}
		}
	}

	// --- helpers

	private fun timed(
		start: String,
		end: String,
		zone: ZoneId,
	) = Timing.Timed(Instant.parse(start), Instant.parse(end), zone)

	private fun event(
		uid: String,
		timing: Timing,
	) = RemoteEvent(key = RemoteEventKey(uid), etag = null, title = "Fictitious $uid", timing = timing)

	private fun valarm(vararg lines: String) = (listOf("BEGIN:VALARM", "ACTION:DISPLAY") + lines + "END:VALARM").joinToString("\n")

	/** One VEVENT with UID `read-1` and [lines] (and [components]) in an otherwise empty VCALENDAR, read. */
	private fun read(
		vararg lines: String,
		components: List<String> = emptyList(),
	): RemoteEvent = readIn("", *lines, components = components)

	private fun readIn(
		vtimezones: String,
		vararg lines: String,
		components: List<String> = emptyList(),
	): RemoteEvent {
		val vevent = (listOf("BEGIN:VEVENT", "UID:read-1") + lines + components + "END:VEVENT").joinToString("\n")
		return CalDavEventMapping.parse(calendar(vtimezones + "\n" + vevent), "etag-1").events.single()
	}

	/** The only event of iCalendar [text], read without etag. */
	private fun readBack(text: String): RemoteEvent = CalDavEventMapping.parse(text, null).events.single()

	/** The lines of [body] (indentation and blank lines removed) wrapped in a VCALENDAR, with CRLF line ends. */
	private fun calendar(body: String): String =
		(
			listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Example//Fictitious Client//EN") +
				body.lines().map(String::trim).filter(String::isNotEmpty) +
				"END:VCALENDAR"
		).joinToString("\r\n", postfix = "\r\n")

	private fun unfolded(text: String) = text.replace("\r\n ", "")

	/** The onsets of VTIMEZONE [observance] in [year]: its DTSTART, RRULE (expanded by ical4j) and RDATEs. */
	private fun onsets(
		observance: IcsComponent,
		year: Int,
	): List<LocalDateTime> {
		val seed = LocalDateTime.parse(observance.text("DTSTART")!!, LOCAL)
		val from = LocalDateTime.of(year, 1, 1, 0, 0)
		val to = LocalDateTime.of(year + 1, 1, 1, 0, 0)
		val recurring = observance.text("RRULE")?.let { Recur<LocalDateTime>(it).getDates(seed, from, to) }.orEmpty()
		val listed = observance.all("RDATE").flatMap { it.value.split(',') }.map { LocalDateTime.parse(it.trim(), LOCAL) }
		return (recurring + (listed + seed).filter { it.year == year }).distinct()
	}

	private fun offsetText(offset: ZoneOffset): String {
		val seconds = offset.totalSeconds
		val abs = Math.abs(seconds)
		val base = (if (seconds < 0) "-" else "+") + "%02d%02d".format(abs / 3600, abs % 3600 / 60)
		return if (abs % 60 != 0) base + "%02d".format(abs % 60) else base
	}

	companion object {
		private val NOW: Instant = Instant.parse("2026-10-21T08:00:00Z")
		private val ZURICH: ZoneId = ZoneId.of("Europe/Zurich")
		private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

		/** A series as another client stored it: X- properties, a VTODO, alarm details, attendee parameters, an override. */
		private val SERVER_SERIES =
			"""
			BEGIN:VCALENDAR
			VERSION:2.0
			PRODID:-//Example//Fictitious Client//EN
			BEGIN:VTIMEZONE
			TZID:Europe/Zurich
			BEGIN:STANDARD
			DTSTART:19701025T030000
			TZOFFSETFROM:+0200
			TZOFFSETTO:+0100
			RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
			END:STANDARD
			BEGIN:DAYLIGHT
			DTSTART:19700329T020000
			TZOFFSETFROM:+0100
			TZOFFSETTO:+0200
			RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU
			END:DAYLIGHT
			END:VTIMEZONE
			BEGIN:VEVENT
			UID:standup
			DTSTAMP:20261001T000000Z
			SEQUENCE:3
			SUMMARY:Standup
			X-CUSTOM;X-PARAM=keep:kept value
			CATEGORIES:Work,Daily
			DTSTART;TZID=Europe/Zurich:20261102T090000
			DURATION:PT15M
			RRULE:FREQ=WEEKLY;BYDAY=MO
			RDATE;TZID=Europe/Zurich:20261104T090000
			EXDATE;TZID=Europe/Zurich:20261116T090000,20261130T090000
			ATTENDEE;CN=Sam;ROLE=CHAIR;RSVP=TRUE;PARTSTAT=ACCEPTED:mailto:sam@example.test
			ATTENDEE;CN=Kim:mailto:kim@example.test
			BEGIN:VALARM
			ACTION:AUDIO
			TRIGGER:-PT15M
			X-ALARM-ID:fictitious-alarm-1
			END:VALARM
			BEGIN:VALARM
			ACTION:DISPLAY
			DESCRIPTION:Absolute
			TRIGGER;VALUE=DATE-TIME:20261101T080000Z
			END:VALARM
			BEGIN:VALARM
			ACTION:DISPLAY
			DESCRIPTION:Day before
			TRIGGER:-P1D
			END:VALARM
			END:VEVENT
			BEGIN:VEVENT
			UID:standup
			DTSTAMP:20261001T000000Z
			RECURRENCE-ID;TZID=Europe/Zurich:20261109T090000
			SUMMARY:Standup (moved)
			X-OVERRIDE:kept too
			DTSTART;TZID=Europe/Zurich:20261109T110000
			DTEND;TZID=Europe/Zurich:20261109T111500
			END:VEVENT
			BEGIN:VTODO
			UID:todo-1
			SUMMARY:Unrelated task
			END:VTODO
			END:VCALENDAR
			""".trimIndent().replace("\n", "\r\n") + "\r\n"
	}
}
