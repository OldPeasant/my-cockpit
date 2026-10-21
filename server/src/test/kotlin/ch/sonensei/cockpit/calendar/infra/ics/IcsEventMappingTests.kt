package ch.sonensei.cockpit.calendar.infra.ics

import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.EventTransparency
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.sync.RemoteEvent
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Mapping the VEVENTs of a parsed feed to remote events (RFC 5545), without HTTP or persistence. */
class IcsEventMappingTests {
	private val zurich = ZoneId.of("Europe/Zurich")
	private val newYork = ZoneId.of("America/New_York")

	/** A VCALENDAR with [header] lines (e.g. `X-WR-TIMEZONE`) and [events] (each the body of a VEVENT, or a whole component). */
	private fun feed(
		vararg events: String,
		header: String = "",
	): String =
		buildString {
			append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Fictitious Org//Test//EN\r\n")
			if (header.isNotBlank()) append(header.trimIndent().replace("\n", "\r\n")).append("\r\n")
			for (event in events) {
				val body = event.trimIndent().replace("\n", "\r\n")
				if (body.startsWith("BEGIN:")) {
					append(body).append("\r\n")
				} else {
					append("BEGIN:VEVENT\r\n").append(body).append("\r\nEND:VEVENT\r\n")
				}
			}
			append("END:VCALENDAR\r\n")
		}

	private fun map(
		text: String,
		skipped: MutableList<RuntimeException> = mutableListOf(),
	): List<RemoteEvent> = IcsEventMapping.events(IcsParser.parse(text)) { skipped += it }

	private fun one(
		vararg events: String,
		header: String = "",
	): RemoteEvent = map(feed(*events, header = header)).single()

	private fun timed(event: RemoteEvent): Timing.Timed = event.timing.shouldBeInstanceOf<Timing.Timed>()

	private fun zurichTime(text: String): Instant = LocalDateTime.parse(text).atZone(zurich).toInstant()

	// --- Basic fields ---

	@Test
	fun `maps the fields of a timed event`() {
		val event =
			one(
				"""
				UID:review@example.test
				DTSTAMP:20261001T080000Z
				DTSTART;TZID=Europe/Zurich:20261105T090000
				DTEND;TZID=Europe/Zurich:20261105T103000
				SUMMARY: Design review\, round 2
				DESCRIPTION:Agenda:\n1. Mock-ups\n2. Next steps
				LOCATION:  Room Matterhorn\; 3rd floor
				STATUS:TENTATIVE
				TRANSP:TRANSPARENT
				CONFERENCE;VALUE=URI:https://meet.example.test/abc-defg
				""",
			)

		event.key shouldBe RemoteEventKey("review@example.test", null)
		event.title shouldBe "Design review, round 2"
		event.description shouldBe "Agenda:\n1. Mock-ups\n2. Next steps"
		event.location shouldBe "Room Matterhorn; 3rd floor"
		event.status shouldBe EventStatus.TENTATIVE
		event.transparency shouldBe EventTransparency.TRANSPARENT
		event.conferenceUrl shouldBe "https://meet.example.test/abc-defg"
		event.timing shouldBe Timing.Timed(zurichTime("2026-11-05T09:00"), zurichTime("2026-11-05T10:30"), zurich)
		event.etag.shouldNotBeNull() shouldMatch Regex("[0-9a-f]{64}")
	}

	@Test
	fun `defaults are confirmed, opaque, no title, no description, location or conference`() {
		val event =
			one(
				"""
				UID:bare@example.test
				DTSTART:20261105T090000Z
				DESCRIPTION:\n
				LOCATION:${"   "}
				""",
			)

		event.title shouldBe ""
		event.status shouldBe EventStatus.CONFIRMED
		event.transparency shouldBe EventTransparency.OPAQUE
		event.description.shouldBeNull()
		event.location.shouldBeNull()
		event.conferenceUrl.shouldBeNull()
		event.rrule.shouldBeNull()
		event.exdates.shouldBeEmpty()
		event.attendees.shouldBeEmpty()
		event.reminderMinutes.shouldBeEmpty()
	}

	@Test
	fun `Google's X-GOOGLE-CONFERENCE is the conference URL when there is no CONFERENCE`() {
		one(
			"""
			UID:g@example.test
			DTSTART:20261105T090000Z
			X-GOOGLE-CONFERENCE:https://meet.example.test/xyz
			""",
		).conferenceUrl shouldBe "https://meet.example.test/xyz"
	}

	// --- Time zones ---

	@Test
	fun `a UTC time is that instant and its zone is the TZID or else the feed's default`() {
		val withTzid = one("UID:a@example.test\nDTSTART;TZID=America/New_York:20261105T140000Z")
		timed(withTzid).start shouldBe Instant.parse("2026-11-05T14:00:00Z")
		timed(withTzid).zone shouldBe newYork

		val withDefault = one("UID:b@example.test\nDTSTART:20261105T140000Z", header = "X-WR-TIMEZONE:Europe/Zurich")
		timed(withDefault).start shouldBe Instant.parse("2026-11-05T14:00:00Z")
		timed(withDefault).zone shouldBe zurich
	}

	@Test
	fun `a TZID with a vendor prefix is read as the Area-City at its end`() {
		val event =
			one(
				"""
				UID:prefixed@example.test
				DTSTART;TZID=/mozilla.org/20050126_1/Europe/Zurich:20260705T090000
				""",
			)

		timed(event).zone shouldBe zurich
		timed(event).start shouldBe Instant.parse("2026-07-05T07:00:00Z")
	}

	@Test
	fun `a three-part Area-City id behind a prefix is found`() {
		val event =
			one(
				"""
				UID:ba@example.test
				DTSTART;TZID=/softwarestudio.org/Olson_20011030_5/America/Argentina/Buenos_Aires:20261105T090000
				""",
			)

		timed(event).zone shouldBe ZoneId.of("America/Argentina/Buenos_Aires")
	}

	@Test
	fun `a Windows time zone name is resolved`() {
		val event = one("UID:win@example.test\nDTSTART;TZID=W. Europe Standard Time:20261105T090000")

		timed(event).zone shouldBe ZoneId.of("Europe/Berlin")
		timed(event).start shouldBe Instant.parse("2026-11-05T08:00:00Z")
	}

	@Test
	fun `a custom TZID is read through the X-LIC-LOCATION of the feed's VTIMEZONE`() {
		val event =
			one(
				"""
				BEGIN:VTIMEZONE
				TZID:Eastern (custom)
				X-LIC-LOCATION:America/New_York
				BEGIN:STANDARD
				DTSTART:19701101T020000
				TZOFFSETFROM:-0400
				TZOFFSETTO:-0500
				END:STANDARD
				END:VTIMEZONE
				""",
				"UID:custom@example.test\nDTSTART;TZID=\"Eastern (custom)\":20261105T090000",
			)

		timed(event).zone shouldBe newYork
		timed(event).start shouldBe Instant.parse("2026-11-05T14:00:00Z")
	}

	@Test
	fun `VTIMEZONEs without TZID or X-LIC-LOCATION and an empty TZID fall back to the default zone`() {
		val events =
			map(
				feed(
					"BEGIN:VTIMEZONE\nX-LIC-LOCATION:America/New_York\nEND:VTIMEZONE",
					"BEGIN:VTIMEZONE\nTZID:Office Time\nEND:VTIMEZONE",
					"UID:office@example.test\nDTSTART;TZID=Office Time:20261105T090000",
					"UID:empty@example.test\nDTSTART;TZID=:20261105T090000",
					header = "X-WR-TIMEZONE:Europe/Zurich",
				),
			)

		events.map { timed(it).zone } shouldContainExactly listOf(zurich, zurich)
	}

	@Test
	fun `empty RRULE and conference values are none`() {
		val event = one("UID:e@example.test\nDTSTART:20261105T090000Z\nRRULE: \nCONFERENCE:")

		event.rrule.shouldBeNull()
		event.conferenceUrl.shouldBeNull()
	}

	@Test
	fun `an unknown TZID and a floating time fall back to X-WR-TIMEZONE`() {
		val events =
			map(
				feed(
					"UID:unknown@example.test\nDTSTART;TZID=Somewhere Else:20261105T090000",
					"UID:floating@example.test\nDTSTART:20261105T090000",
					header = "X-WR-TIMEZONE:America/New_York",
				),
			)

		events.map { timed(it).zone } shouldContainExactly listOf(newYork, newYork)
		events.map { timed(it).start } shouldContainExactly
			listOf(Instant.parse("2026-11-05T14:00:00Z"), Instant.parse("2026-11-05T14:00:00Z"))
	}

	@Test
	fun `without a usable X-WR-TIMEZONE an unknown TZID and a floating time are UTC`() {
		val events =
			map(
				feed(
					"UID:unknown@example.test\nDTSTART;TZID=Somewhere Else:20261105T090000",
					"UID:floating@example.test\nDTSTART:20261105T090000",
					header = "X-WR-TIMEZONE:Not/AZone",
				),
			)

		events.map { timed(it).zone } shouldContainExactly listOf(ZoneOffset.UTC, ZoneOffset.UTC)
		events.map { timed(it).start } shouldContainExactly
			listOf(Instant.parse("2026-11-05T09:00:00Z"), Instant.parse("2026-11-05T09:00:00Z"))
	}

	@Test
	fun `each VCALENDAR has its own default zone`() {
		val text =
			feed("UID:one@example.test\nDTSTART:20261105T090000", header = "X-WR-TIMEZONE:Europe/Zurich") +
				feed("UID:two@example.test\nDTSTART:20261105T090000", header = "X-WR-TIMEZONE:America/New_York")

		map(text).map { timed(it).zone } shouldContainExactly listOf(zurich, newYork)
	}

	// --- End, DURATION and all-day defaults ---

	@Test
	fun `DURATION gives the end of a timed event when DTEND is missing, including weeks`() {
		val hours = one("UID:h@example.test\nDTSTART:20261105T090000Z\nDURATION:PT1H30M")
		timed(hours).end shouldBe Instant.parse("2026-11-05T10:30:00Z")

		val mixed = one("UID:m@example.test\nDTSTART:20261105T090000Z\nDURATION:P1DT2H")
		timed(mixed).end shouldBe Instant.parse("2026-11-06T11:00:00Z")

		val weeks = one("UID:w@example.test\nDTSTART:20261105T090000Z\nDURATION:P2W")
		timed(weeks).end shouldBe Instant.parse("2026-11-19T09:00:00Z")
	}

	@Test
	fun `a timed event without DTEND and DURATION, or with an unreadable DURATION, takes no time`() {
		val none = one("UID:n@example.test\nDTSTART:20261105T090000Z")
		timed(none).end shouldBe timed(none).start

		val broken = one("UID:b@example.test\nDTSTART:20261105T090000Z\nDURATION:soon")
		timed(broken).end shouldBe timed(broken).start
	}

	@Test
	fun `DTEND wins over DURATION`() {
		val event = one("UID:x@example.test\nDTSTART:20261105T090000Z\nDTEND:20261105T091500Z\nDURATION:PT8H")

		timed(event).end shouldBe Instant.parse("2026-11-05T09:15:00Z")
	}

	@Test
	fun `a day in DURATION is a calendar day across a daylight saving change (RFC 5545 3_3_6)`() {
		// Europe/Zurich leaves summer time on 2026-10-25: P1D from 09:00 ends at 09:00 local the next day (25 h).
		val event = one("UID:dst@example.test\nDTSTART;TZID=Europe/Zurich:20261024T090000\nDURATION:P1D")

		timed(event).end shouldBe zurichTime("2026-10-25T09:00")
	}

	@Test
	fun `weeks are calendar days too, the time part of a day-and-time DURATION is exact time`() {
		val week = one("UID:w@example.test\nDTSTART;TZID=Europe/Zurich:20261020T090000\nDURATION:P1W")
		timed(week).end shouldBe zurichTime("2026-10-27T09:00")

		val mixed = one("UID:m@example.test\nDTSTART;TZID=Europe/Zurich:20261024T090000\nDURATION:P1DT1H30M")
		timed(mixed).end shouldBe zurichTime("2026-10-25T10:30")
	}

	@Test
	fun `a day in DURATION keeps the local time across the change to summer time too`() {
		// Europe/Zurich starts summer time on 2027-03-28: P1D from 09:00 ends at 09:00 local the next day (23 h).
		val event = one("UID:dst-spring@example.test\nDTSTART;TZID=Europe/Zurich:20270327T090000\nDURATION:P1D")

		timed(event).end shouldBe zurichTime("2027-03-28T09:00")
		Duration.between(timed(event).start, timed(event).end) shouldBe Duration.ofHours(23)
	}

	@Test
	fun `an hour-based DURATION across a daylight saving change is exact time`() {
		val event = one("UID:dst-h@example.test\nDTSTART;TZID=Europe/Zurich:20261024T090000\nDURATION:PT24H")

		timed(event).end shouldBe zurichTime("2026-10-24T09:00").plus(Duration.ofHours(24))
	}

	@Test
	fun `a timed event with a date DTEND ends at the start of that day in its zone`() {
		val event = one("UID:x@example.test\nDTSTART;TZID=Europe/Zurich:20261105T090000\nDTEND;VALUE=DATE:20261106")

		timed(event).end shouldBe zurichTime("2026-11-06T00:00")
	}

	@Test
	fun `an all-day event without DTEND lasts one day, DURATION gives its days`() {
		one("UID:a@example.test\nDTSTART;VALUE=DATE:20261224").timing shouldBe
			Timing.AllDay(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 25))
		one("UID:b@example.test\nDTSTART;VALUE=DATE:20261224\nDURATION:P3D").timing shouldBe
			Timing.AllDay(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 27))
		one("UID:c@example.test\nDTSTART;VALUE=DATE:20261224\nDURATION:P1W").timing shouldBe
			Timing.AllDay(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 31))
	}

	@Test
	fun `an all-day event of less than a day, or with DTEND equal to DTSTART, lasts one day`() {
		val oneDay = Timing.AllDay(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 25))

		one("UID:a@example.test\nDTSTART;VALUE=DATE:20261224\nDURATION:PT4H").timing shouldBe oneDay
		one("UID:b@example.test\nDTSTART;VALUE=DATE:20261224\nDURATION:P0D").timing shouldBe oneDay
		one("UID:c@example.test\nDTSTART;VALUE=DATE:20261224\nDTEND;VALUE=DATE:20261224").timing shouldBe oneDay
		one("UID:d@example.test\nDTSTART;VALUE=DATE:20261224\nDTEND;VALUE=DATE:20261220").timing shouldBe oneDay
	}

	@Test
	fun `an 8-digit value is a date even without VALUE=DATE, a date-time DTEND of an all-day event is its date`() {
		val event = one("UID:a@example.test\nDTSTART:20261224\nDTEND:20261226T120000Z", header = "X-WR-TIMEZONE:Europe/Zurich")

		event.timing shouldBe Timing.AllDay(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 26))
	}

	// --- Duration values ---

	@Test
	fun `duration reads RFC 5545 durations and refuses others`() {
		IcsEventMapping.duration("PT15M") shouldBe Duration.ofMinutes(15)
		IcsEventMapping.duration("-PT15M") shouldBe Duration.ofMinutes(-15)
		IcsEventMapping.duration(" p1dt2h ") shouldBe Duration.ofHours(26)
		IcsEventMapping.duration("P1W") shouldBe Duration.ofDays(7)
		IcsEventMapping.duration("-P2W") shouldBe Duration.ofDays(-14)
		IcsEventMapping.duration("+P1W") shouldBe Duration.ofDays(7)
		IcsEventMapping.duration("").shouldBeNull()
		IcsEventMapping.duration("P").shouldBeNull()
		IcsEventMapping.duration("1 hour").shouldBeNull()
		IcsEventMapping.duration("P99999999999999999999W").shouldBeNull()
	}

	// --- Recurrence ---

	@Test
	fun `a series keeps its RRULE and all EXDATEs, in lists and over several properties`() {
		val event =
			one(
				"""
				UID:standup@example.test
				DTSTART;TZID=Europe/Zurich:20261102T090000
				DTEND;TZID=Europe/Zurich:20261102T091500
				RRULE:FREQ=WEEKLY;BYDAY=MO,WE
				EXDATE;TZID=Europe/Zurich:20261104T090000,20261109T090000
				EXDATE:20261111T080000Z
				EXDATE;TZID=Europe/Zurich:not-a-date,,20261116T090000
				""",
			)

		event.rrule shouldBe "FREQ=WEEKLY;BYDAY=MO,WE"
		event.exdates shouldBe
			setOf(
				OccurrenceId.of(zurichTime("2026-11-04T09:00")),
				OccurrenceId.of(zurichTime("2026-11-09T09:00")),
				OccurrenceId.of(Instant.parse("2026-11-11T08:00:00Z")),
				OccurrenceId.of(zurichTime("2026-11-16T09:00")),
			)
	}

	@Test
	fun `EXDATEs of an all-day series are dates`() {
		val event =
			one(
				"""
				UID:gym@example.test
				DTSTART;VALUE=DATE:20261102
				RRULE:FREQ=DAILY;COUNT=5
				EXDATE;VALUE=DATE:20261103,20261105
				""",
			)

		event.exdates shouldBe setOf(OccurrenceId.of(LocalDate.of(2026, 11, 3)), OccurrenceId.of(LocalDate.of(2026, 11, 5)))
	}

	@Test
	fun `an exception has its recurrence id and neither RRULE nor EXDATEs`() {
		val events =
			map(
				feed(
					"UID:standup@example.test\nDTSTART;TZID=Europe/Zurich:20261102T090000\nRRULE:FREQ=WEEKLY",
					"""
				UID:standup@example.test
				RECURRENCE-ID;TZID=Europe/Zurich:20261109T090000
				DTSTART;TZID=Europe/Zurich:20261109T100000
				DTEND;TZID=Europe/Zurich:20261109T101500
				RRULE:FREQ=WEEKLY
				EXDATE;TZID=Europe/Zurich:20261116T090000
				SUMMARY:Stand-up (later)
				""",
				),
			)
		val event = events.single { it.key.recurrenceId != null }

		event.key shouldBe RemoteEventKey("standup@example.test", OccurrenceId.of(zurichTime("2026-11-09T09:00")))
		event.rrule.shouldBeNull()
		event.exdates.shouldBeEmpty()
	}

	@Test
	fun `a cancelled series or single event is left out, a cancelled exception stays`() {
		val events =
			map(
				feed(
					"UID:gone@example.test\nDTSTART:20261105T090000Z\nRRULE:FREQ=DAILY\nSTATUS:CANCELLED",
					"UID:single@example.test\nSTATUS: cancelled ",
					"UID:series@example.test\nDTSTART:20261102T090000Z\nRRULE:FREQ=DAILY",
					"UID:series@example.test\nRECURRENCE-ID:20261103T090000Z\nDTSTART:20261103T090000Z\nSTATUS:CANCELLED",
				),
			)

		events shouldHaveSize 2
		events.map { it.key.externalId } shouldContainExactly listOf("series@example.test", "series@example.test")
		val cancelled = events.single { it.key.recurrenceId != null }
		cancelled.status shouldBe EventStatus.CANCELLED
		cancelled.key.recurrenceId shouldBe OccurrenceId.of(Instant.parse("2026-11-03T09:00:00Z"))
	}

	@Test
	fun `exceptions without a recurring series in the feed are left out`() {
		val events =
			map(
				feed(
					// The series is cancelled.
					"UID:gone@example.test\nDTSTART:20261102T090000Z\nRRULE:FREQ=DAILY\nSTATUS:CANCELLED",
					"UID:gone@example.test\nRECURRENCE-ID:20261103T090000Z\nDTSTART:20261103T100000Z",
					// The "series" has no RRULE (an RDATE series is not supported).
					"UID:rdate@example.test\nDTSTART:20261102T090000Z\nRDATE:20261104T090000Z",
					"UID:rdate@example.test\nRECURRENCE-ID:20261104T090000Z\nDTSTART:20261104T100000Z",
					// There is no series at all.
					"UID:alone@example.test\nRECURRENCE-ID:20261103T090000Z\nDTSTART:20261103T100000Z",
					// The series comes after its exception: kept.
					"UID:series@example.test\nRECURRENCE-ID:20261103T090000Z\nDTSTART:20261103T100000Z",
					"UID:series@example.test\nDTSTART:20261102T090000Z\nRRULE:FREQ=DAILY",
				),
			)

		events.map { it.key } shouldContainExactly
			listOf(
				RemoteEventKey("rdate@example.test"),
				RemoteEventKey("series@example.test", OccurrenceId.of(Instant.parse("2026-11-03T09:00:00Z"))),
				RemoteEventKey("series@example.test"),
			)
	}

	@Test
	fun `an exception of a series that cannot be read is left out, and only the series is reported as skipped`() {
		val skipped = mutableListOf<RuntimeException>()
		val events =
			map(
				feed(
					"UID:broken@example.test\nDTSTART:soon\nRRULE:FREQ=DAILY",
					"UID:broken@example.test\nRECURRENCE-ID:20261103T090000Z\nDTSTART:20261103T100000Z",
					"UID:fine@example.test\nDTSTART:20261105T090000Z",
				),
				skipped,
			)

		events.map { it.key } shouldContainExactly listOf(RemoteEventKey("fine@example.test"))
		skipped shouldHaveSize 1
	}

	// --- Attendees and reminders ---

	@Test
	fun `attendees are the mailto addresses with name and participation status`() {
		val event =
			one(
				"""
				UID:a@example.test
				DTSTART:20261105T090000Z
				ATTENDEE;CN="Muster, Erika";PARTSTAT=ACCEPTED:mailto:erika@example.test
				ATTENDEE;PARTSTAT=declined:MAILTO:max@example.test
				ATTENDEE;PARTSTAT=TENTATIVE;CN=  :mailto:  tina@example.test
				ATTENDEE;PARTSTAT=DELEGATED:mailto:dora@example.test
				ATTENDEE;PARTSTAT=X-UNKNOWN:mailto:nina@example.test
				ATTENDEE:mailto:nobody@example.test
				ATTENDEE;CN=Phone:tel:+41000000000
				ATTENDEE;CN=Room:urn:uuid:00000000-0000-0000-0000-000000000000
				""",
			)

		event.attendees shouldContainExactly
			listOf(
				Attendee("erika@example.test", "Muster, Erika", AttendeeStatus.ACCEPTED),
				Attendee("max@example.test", null, AttendeeStatus.DECLINED),
				Attendee("tina@example.test", null, AttendeeStatus.TENTATIVE),
				Attendee("dora@example.test", null, AttendeeStatus.DELEGATED),
				Attendee("nina@example.test", null, AttendeeStatus.NEEDS_ACTION),
				Attendee("nobody@example.test", null, AttendeeStatus.NEEDS_ACTION),
			)
	}

	@Test
	fun `reminders are the alarms before the start, distinct and sorted`() {
		val event =
			one(
				"""
				BEGIN:VEVENT
				UID:r@example.test
				DTSTART:20261105T090000Z
				BEGIN:VALARM
				ACTION:DISPLAY
				TRIGGER:-PT1H
				END:VALARM
				BEGIN:VALARM
				TRIGGER;RELATED=START:-PT15M
				END:VALARM
				BEGIN:VALARM
				TRIGGER:-PT15M
				END:VALARM
				BEGIN:VALARM
				TRIGGER:-P1W
				END:VALARM
				BEGIN:VALARM
				TRIGGER:-P1DT2H
				END:VALARM
				BEGIN:VALARM
				TRIGGER:PT0S
				END:VALARM
				END:VEVENT
				""",
			)

		event.reminderMinutes shouldContainExactly listOf(0, 15, 60, 1560, 10080)
	}

	@Test
	fun `alarms after the start, at an absolute time, relative to the end, unreadable or without trigger are no reminders`() {
		val event =
			one(
				"""
				BEGIN:VEVENT
				UID:r@example.test
				DTSTART:20261105T090000Z
				BEGIN:VALARM
				TRIGGER:PT10M
				END:VALARM
				BEGIN:VALARM
				TRIGGER;VALUE=DATE-TIME:20261105T080000Z
				END:VALARM
				BEGIN:VALARM
				TRIGGER;RELATED=END:-PT5M
				END:VALARM
				BEGIN:VALARM
				TRIGGER:whenever
				END:VALARM
				BEGIN:VALARM
				ACTION:AUDIO
				END:VALARM
				END:VEVENT
				""",
			)

		event.reminderMinutes.shouldBeEmpty()
	}

	// --- Identity and etag ---

	@Test
	fun `an event without UID gets a stable generated one from start and title`() {
		val first = one("DTSTART:20261105T090000Z\nSUMMARY:Lunch")
		val again = one("UID:   \nDTSTART:20261105T090000Z\nSUMMARY:Lunch\nDTSTAMP:20261020T120000Z")
		val otherTitle = one("DTSTART:20261105T090000Z\nSUMMARY:Dinner")
		val otherStart = one("DTSTART:20261106T090000Z\nSUMMARY:Lunch")

		first.key.externalId shouldStartWith "generated-"
		first.key.externalId shouldMatch Regex("generated-[0-9a-f]{32}")
		again.key shouldBe first.key
		otherTitle.key shouldNotBe first.key
		otherStart.key shouldNotBe first.key
	}

	@Test
	fun `the etag is stable across DTSTAMP changes and changes with the content`() {
		val body = "UID:e@example.test\nDTSTART:20261105T090000Z\nDTEND:20261105T100000Z\nSUMMARY:Planning"
		val original = one("$body\nDTSTAMP:20261001T000000Z")
		val redownloaded = one("$body\nDTSTAMP:20261021T101500Z")
		val retitled = one(body.replace("Planning", "Planning v2") + "\nDTSTAMP:20261001T000000Z")
		val moved = one(body.replace("T100000Z", "T110000Z"))

		redownloaded.etag shouldBe original.etag
		retitled.etag shouldNotBe original.etag
		moved.etag shouldNotBe original.etag
	}

	// --- Broken events ---

	@Test
	fun `events without or with an unreadable DTSTART are skipped and reported, the others are kept`() {
		val skipped = mutableListOf<RuntimeException>()
		val events =
			map(
				feed(
					"UID:nostart@example.test\nSUMMARY:No start",
					"UID:badstart@example.test\nDTSTART:2026-11-05 09:00",
					"UID:baddate@example.test\nDTSTART;VALUE=DATE:2026",
					"UID:badrecurrence@example.test\nRECURRENCE-ID:yesterday\nDTSTART:20261105T090000Z",
					"UID:fine@example.test\nDTSTART:20261105T090000Z",
				),
				skipped,
			)

		events.map { it.key.externalId } shouldContainExactly listOf("fine@example.test")
		skipped shouldHaveSize 4
	}

	@Test
	fun `only VEVENTs of VCALENDARs are read`() {
		val text =
			"BEGIN:VEVENT\r\nUID:loose@example.test\r\nDTSTART:20261105T090000Z\r\nEND:VEVENT\r\n" +
				feed(
					"""
					BEGIN:VTODO
					UID:todo@example.test
					DTSTART:20261105T090000Z
					END:VTODO
					""",
					"UID:event@example.test\nDTSTART:20261105T090000Z",
				)

		map(text).map { it.key.externalId } shouldContainExactly listOf("event@example.test")
	}
}
