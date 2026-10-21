package ch.sonensei.cockpit.calendar.infra.google

import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.sync.RemoteCalendar
import ch.sonensei.cockpit.calendar.sync.RemoteEvent
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** [GoogleEventMapping] on hand-written Calendar API v3 resources (fictitious data), without HTTP. */
class GoogleEventMappingTests {
	private val json = JsonMapper.builder().build()
	private val zurich = ZoneId.of("Europe/Zurich")

	private fun read(
		resource: String,
		calendarZone: ZoneId? = null,
		defaults: List<Int> = emptyList(),
	): RemoteEvent = GoogleEventMapping.toRemote(json.readTree(resource), calendarZone, defaults)

	private fun weekly(vararg recurrence: String) =
		read(
			"""
			{"id": "series09", "summary": "Standup",
			 "start": {"dateTime": "2026-10-26T09:00:00+01:00", "timeZone": "Europe/Zurich"},
			 "end": {"dateTime": "2026-10-26T09:15:00+01:00", "timeZone": "Europe/Zurich"},
			 "recurrence": [${recurrence.joinToString(",") { "\"$it\"" }}]}
			""",
		)

	private fun allDaySeries(vararg recurrence: String) =
		read(
			"""
			{"id": "daily09", "summary": "Holiday camp", "start": {"date": "2026-11-02"}, "end": {"date": "2026-11-03"},
			 "recurrence": [${recurrence.joinToString(",") { "\"$it\"" }}]}
			""",
		)

	private fun at(instant: String) = OccurrenceId.of(Instant.parse(instant))

	// --- recurrence lines

	@Test
	fun `EXDATE with TZID is read in that zone, several values per line and several lines`() {
		val series =
			weekly(
				"RRULE:FREQ=WEEKLY;BYDAY=MO",
				"EXDATE;TZID=Europe/Zurich:20261109T090000,20261116T090000",
				"EXDATE:20261123T080000Z",
			)

		series.rrule shouldBe "FREQ=WEEKLY;BYDAY=MO"
		series.exdates shouldBe setOf(at("2026-11-09T08:00:00Z"), at("2026-11-16T08:00:00Z"), at("2026-11-23T08:00:00Z"))
	}

	@Test
	fun `a floating EXDATE is in the series' zone and a date EXDATE of a timed series means that day's occurrence`() {
		val series = weekly("RRULE:FREQ=WEEKLY;BYDAY=MO", "EXDATE:20261109T090000", "EXDATE;VALUE=DATE:20261116")

		series.exdates shouldBe setOf(at("2026-11-09T08:00:00Z"), at("2026-11-16T08:00:00Z"))
	}

	@Test
	fun `EXDATEs of an all-day series are dates, also when Google sends them as times`() {
		val series = allDaySeries("RRULE:FREQ=DAILY;COUNT=5", "EXDATE;VALUE=DATE:20261103,20261105", "EXDATE:20261106T000000Z")

		series.timing shouldBe Timing.AllDay(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-03"))
		series.exdates shouldBe setOf("2026-11-03", "2026-11-05", "2026-11-06").map { OccurrenceId.of(LocalDate.parse(it)) }.toSet()
	}

	@Test
	fun `unreadable EXDATE values and unsupported recurrence lines are dropped, the rest is kept`() {
		val series =
			weekly(
				"RRULE:FREQ=WEEKLY;BYDAY=MO",
				"EXDATE:garbage,20261109T080000Z",
				"EXDATE-without-colon",
				"EXDATE;VALUE=DATE:20261399",
				"RDATE:20261201T080000Z",
				"EXRULE:FREQ=MONTHLY",
			)

		series.rrule shouldBe "FREQ=WEEKLY;BYDAY=MO"
		series.exdates shouldBe setOf(at("2026-11-09T08:00:00Z"))
	}

	@Test
	fun `an instance never carries the recurrence of its series`() {
		val instance =
			read(
				"""
				{"id": "series09_20261109T080000Z", "recurringEventId": "series09",
				 "originalStartTime": {"dateTime": "2026-11-09T09:00:00+01:00", "timeZone": "Europe/Zurich"},
				 "start": {"dateTime": "2026-11-09T10:00:00+01:00"}, "end": {"dateTime": "2026-11-09T10:15:00+01:00"},
				 "recurrence": ["RRULE:FREQ=DAILY"]}
				""",
			)

		instance.key shouldBe RemoteEventKey("series09", at("2026-11-09T08:00:00Z"))
		instance.rrule.shouldBeNull()
		instance.exdates shouldBe emptySet()
	}

	@Test
	fun `a cancelled occurrence of an all-day series is keyed by its date`() {
		val node =
			json.readTree(
				"""
				{"id": "daily09_20261104", "status": "cancelled", "recurringEventId": "daily09",
				 "originalStartTime": {"date": "2026-11-04"}}
				""",
			)

		GoogleEventMapping.isCancelled(node) shouldBe true
		GoogleEventMapping.key(node) shouldBe RemoteEventKey("daily09", OccurrenceId.of(LocalDate.parse("2026-11-04")))
	}

	// --- times, zones and details

	@Test
	fun `the zone is the event's, else the calendar's, else the offset of the time`() {
		fun event(start: String) = """{"id": "e1", "start": $start, "end": {"dateTime": "2026-10-22T11:00:00+02:00"}}"""
		val own = event("""{"dateTime": "2026-10-22T10:00:00+02:00", "timeZone": "America/New_York"}""")
		val unknownZone = event("""{"dateTime": "2026-10-22T10:00:00+02:00", "timeZone": "Not/AZone"}""")
		val none = event("""{"dateTime": "2026-10-22T10:00:00+02:00"}""")

		(read(own, zurich).timing as Timing.Timed).zone shouldBe ZoneId.of("America/New_York")
		(read(unknownZone, zurich).timing as Timing.Timed).zone shouldBe zurich
		(read(none).timing as Timing.Timed).zone shouldBe ZoneOffset.ofHours(2)
		GoogleEventMapping.zone("Not/AZone").shouldBeNull()
		GoogleEventMapping.zone(null).shouldBeNull()
	}

	@Test
	fun `a missing end is the start for timed events and the next day for all-day events`() {
		read("""{"id": "e1", "start": {"dateTime": "2026-10-22T10:00:00Z"}}""").timing shouldBe
			Timing.Timed(Instant.parse("2026-10-22T10:00:00Z"), Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC)
		read("""{"id": "e2", "start": {"date": "2026-10-22"}}""").timing shouldBe
			Timing.AllDay(LocalDate.parse("2026-10-22"), LocalDate.parse("2026-10-23"))
	}

	@Test
	fun `the status is read as tentative or cancelled, anything else is confirmed`() {
		fun status(value: String?) =
			read(
				"""{"id": "e1", ${value?.let { "\"status\": \"$it\"," } ?: ""} "start": {"dateTime": "2026-10-22T10:00:00Z"}}""",
			).status

		status("tentative") shouldBe EventStatus.TENTATIVE
		status("cancelled") shouldBe EventStatus.CANCELLED
		status("confirmed") shouldBe EventStatus.CONFIRMED
		status("something-new") shouldBe EventStatus.CONFIRMED
		status(null) shouldBe EventStatus.CONFIRMED
	}

	@Test
	fun `events without id, without start or with an invalid date cannot be read`() {
		shouldThrow<IllegalArgumentException> { read("""{"start": {"dateTime": "2026-10-22T10:00:00Z"}}""") }
		shouldThrow<IllegalArgumentException> { read("""{"id": "e1", "start": {}}""") }
		shouldThrow<DateTimeException> { read("""{"id": "e1", "start": {"date": "2026-13-45"}}""") }
		shouldThrow<DateTimeException> { read("""{"id": "e1", "start": {"dateTime": "tomorrow"}}""") }
	}

	@Test
	fun `only popup and e-mail reminders with minutes are kept, once each, and attendees need an e-mail address`() {
		val event =
			read(
				"""
				{"id": "e1", "start": {"dateTime": "2026-10-22T10:00:00Z"}, "end": {"dateTime": "2026-10-22T11:00:00Z"},
				 "reminders": {"useDefault": false, "overrides": [
				   {"method": "popup", "minutes": 30}, {"method": "email", "minutes": 30}, {"method": "sms", "minutes": 5},
				   {"method": "popup", "minutes": "soon"}, {"method": "email", "minutes": 1440}]},
				 "attendees": [{"email": "kim.test@example.test", "responseStatus": "declined"}, {"displayName": "No address"},
				   {"email": "room@example.test", "responseStatus": "something-new"}]}
				""",
				defaults = listOf(10),
			)

		event.reminderMinutes shouldContainExactly listOf(30, 1440)
		event.attendees shouldContainExactly
			listOf(
				Attendee("kim.test@example.test", null, AttendeeStatus.DECLINED),
				Attendee("room@example.test", null, AttendeeStatus.NEEDS_ACTION),
			)
	}

	@Test
	fun `without a Meet link the conference URL is the first video entry point`() {
		val event =
			read(
				"""
				{"id": "e1", "start": {"dateTime": "2026-10-22T10:00:00Z"}, "end": {"dateTime": "2026-10-22T11:00:00Z"},
				 "conferenceData": {"entryPoints": [
				   {"entryPointType": "phone", "uri": "tel:+41-00-000-00-00"},
				   {"entryPointType": "video", "uri": "https://video.example.test/room-1"}]}}
				""",
			)

		event.conferenceUrl shouldBe "https://video.example.test/room-1"
		event.title shouldBe ""
	}

	@Test
	fun `a calendar without a name is named by its id, and only owners and writers may write`() {
		GoogleEventMapping.toCalendar(json.readTree("""{"id": "shared@example.test", "accessRole": "reader"}""")) shouldBe
			RemoteCalendar("shared@example.test", "shared@example.test", null, readOnly = true)
		GoogleEventMapping.toCalendar(json.readTree("""{"id": "c1", "summary": "Writers", "accessRole": "writer"}""")).readOnly shouldBe false
		shouldThrow<IllegalArgumentException> { GoogleEventMapping.toCalendar(json.readTree("""{"summary": "No id"}""")) }
	}

	// --- writing

	@Test
	fun `an all-day series is sent with a date EXDATE line, sorted`() {
		val event =
			RemoteEvent(
				key = RemoteEventKey("daily09"),
				etag = null,
				title = "Holiday camp",
				timing = Timing.AllDay(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-03")),
				rrule = "FREQ=DAILY;COUNT=5",
				exdates = setOf(OccurrenceId.of(LocalDate.parse("2026-11-05")), OccurrenceId.of(LocalDate.parse("2026-11-03"))),
			)

		val body = GoogleEventMapping.toBody(json, event, clearRecurrence = false)

		body.get("recurrence").values().map { it.asString() } shouldContainExactly
			listOf("RRULE:FREQ=DAILY;COUNT=5", "EXDATE;VALUE=DATE:20261103,20261105")
		body.get("start").get("date").asString() shouldBe "2026-11-02"
		body.get("start").get("dateTime").isNull shouldBe true
	}

	@Test
	fun `a timed event is sent with an IANA zone name, UTC as UTC and a fixed offset without zone`() {
		fun startOf(zone: ZoneId) =
			GoogleEventMapping
				.toBody(
					json,
					RemoteEvent(
						key = RemoteEventKey("e1"),
						etag = null,
						title = "Call",
						timing = Timing.Timed(Instant.parse("2026-10-22T08:00:00Z"), Instant.parse("2026-10-22T09:00:00Z"), zone),
					),
					clearRecurrence = false,
				).get("start")

		startOf(zurich).get("dateTime").asString() shouldBe "2026-10-22T10:00:00+02:00"
		startOf(zurich).get("timeZone").asString() shouldBe "Europe/Zurich"
		startOf(ZoneOffset.UTC).get("dateTime").asString() shouldBe "2026-10-22T08:00:00Z"
		startOf(ZoneOffset.UTC).get("timeZone").asString() shouldBe "UTC"
		startOf(ZoneOffset.ofHours(5)).get("dateTime").asString() shouldBe "2026-10-22T13:00:00+05:00"
		startOf(ZoneOffset.ofHours(5)).get("timeZone").isNull shouldBe true
	}

	@Test
	fun `a single event's insert has no recurrence, an instance never gets one, and reminders are popups`() {
		val single =
			RemoteEvent(
				key = RemoteEventKey("e1"),
				etag = null,
				title = "Call",
				timing = Timing.Timed(Instant.parse("2026-10-22T08:00:00Z"), Instant.parse("2026-10-22T09:00:00Z"), zurich),
				reminderMinutes = listOf(10, 60),
				status = EventStatus.CANCELLED,
			)
		val instance = single.copy(key = RemoteEventKey("series09", at("2026-11-09T08:00:00Z")), rrule = "FREQ=DAILY")

		val body = GoogleEventMapping.toBody(json, single, clearRecurrence = false)
		body.has("recurrence") shouldBe false
		body.get("status").asString() shouldBe "cancelled"
		val overrides = body.get("reminders").get("overrides").values()
		overrides.map { it.get("method").asString() to it.get("minutes").asInt() } shouldContainExactly listOf("popup" to 10, "popup" to 60)
		GoogleEventMapping.toBody(json, instance, clearRecurrence = true).has("recurrence") shouldBe false
	}

	@Test
	fun `attendees are sent with Google's response statuses`() {
		val event =
			RemoteEvent(
				key = RemoteEventKey("e1"),
				etag = null,
				title = "Call",
				timing = Timing.Timed(Instant.parse("2026-10-22T08:00:00Z"), Instant.parse("2026-10-22T09:00:00Z"), zurich),
				attendees =
					listOf(
						Attendee("ana.accepts@example.test", null, AttendeeStatus.ACCEPTED),
						Attendee("dan.declines@example.test", "Dan", AttendeeStatus.DECLINED),
						Attendee("tia.maybe@example.test", null, AttendeeStatus.TENTATIVE),
						Attendee("nia.open@example.test", null, AttendeeStatus.NEEDS_ACTION),
					),
			)
		val body = GoogleEventMapping.toBody(json, event, clearRecurrence = false)

		body.get("attendees").values().map { it.get("responseStatus").asString() } shouldContainExactly
			listOf("accepted", "declined", "tentative", "needsAction")
		// What is sent reads back unchanged.
		read(json.writeValueAsString(body.put("id", "e1"))).attendees shouldContainExactly event.attendees
	}

	@Test
	fun `instance ids use the UTC start of timed and the date of all-day occurrences`() {
		GoogleEventMapping.instanceId("series09", at("2026-11-09T08:00:00Z")) shouldBe "series09_20261109T080000Z"
		GoogleEventMapping.instanceId("daily09", OccurrenceId.of(LocalDate.parse("2026-11-04"))) shouldBe "daily09_20261104"
	}

	@Test
	fun `Google ids are derived only from ids in Google's alphabet`() {
		GoogleCalendarAdapter.googleId("0192A5F0-C3E4-7B6A-8D1E-2F3A4B5C6D7E") shouldBe "0192a5f0c3e47b6a8d1e2f3a4b5c6d7e"
		GoogleCalendarAdapter.googleId("abcd").shouldBeNull()
		GoogleCalendarAdapter.googleId("event_with_underscore").shouldBeNull()
		GoogleCalendarAdapter.googleId("wxyz1").shouldBeNull()
	}
}
