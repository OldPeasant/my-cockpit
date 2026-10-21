package ch.sonensei.cockpit.calendar.infra.microsoft

import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.EventTransparency
import ch.sonensei.cockpit.calendar.sync.RemoteCalendar
import ch.sonensei.cockpit.calendar.sync.RemoteEvent
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** [GraphEventMapping]: Graph events to remote events and back, and the diff that keeps updates small. Fictitious data. */
class GraphEventMappingTests {
	private val json = JsonMapper.builder().build()
	private val key = RemoteEventKey("AAMkMapping1")

	/** A Graph event with [fields] (JSON members, without braces) next to an id and a type. */
	private fun event(fields: String): JsonNode = json.readTree("""{"id": "AAMkMapping1", "type": "singleInstance", $fields}""")

	private fun utc(
		start: String,
		end: String,
	) = """"start": {"dateTime": "$start", "timeZone": "UTC"}, "end": {"dateTime": "$end", "timeZone": "UTC"}"""

	private fun read(fields: String) = GraphEventMapping.toRemote(event(fields), key)

	// --- reading

	@Test
	fun `an html body is read as its text, entities decoded`() {
		val read =
			read(
				"""${utc("2026-10-22T07:30:00", "2026-10-22T08:00:00")},
				"body": {"contentType": "HTML", "content": "<html><body><p>Bring &amp; share</p></body></html>"}""",
			)

		read.description shouldBe "Bring & share"
	}

	@Test
	fun `a blank or missing body is no description`() {
		read("""${utc("2026-10-22T07:30:00", "2026-10-22T08:00:00")}, "body": {"contentType": "text", "content": "  \r\n "}""")
			.description
			.shouldBeNull()
		read(utc("2026-10-22T07:30:00", "2026-10-22T08:00:00")).description.shouldBeNull()
	}

	@ParameterizedTest
	@CsvSource(
		// Midnight in Tokyo (UTC+9), sent in UTC: the dates of Tokyo.
		"Tokyo Standard Time, 2026-11-01T15:00:00, 2026-11-03T15:00:00, 2026-11-02, 2026-11-04",
		// Midnight in Los Angeles (UTC-8 after 1 November 2026).
		"Pacific Standard Time, 2026-11-02T08:00:00, 2026-11-03T08:00:00, 2026-11-02, 2026-11-03",
		// Midnight in UTC stays the UTC date, whatever the event's zone.
		"Tokyo Standard Time, 2026-11-02T00:00:00, 2026-11-04T00:00:00, 2026-11-02, 2026-11-04",
	)
	fun `all-day boundaries converted to UTC read as the dates of the event's zone`(
		zone: String,
		start: String,
		end: String,
		first: String,
		endDate: String,
	) {
		val read = read("""${utc(start, end)}, "isAllDay": true, "originalStartTimeZone": "$zone"""")

		read.timing shouldBe Timing.AllDay(LocalDate.parse(first), LocalDate.parse(endDate))
	}

	@Test
	fun `an all-day event without a later end lasts one day`() {
		read("""${utc("2026-11-02T00:00:00", "2026-11-02T00:00:00")}, "isAllDay": true""").timing shouldBe
			Timing.AllDay(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-03"))
		read(""""start": {"dateTime": "2026-11-02T00:00:00", "timeZone": "UTC"}, "isAllDay": true""").timing shouldBe
			Timing.AllDay(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-03"))
	}

	@Test
	fun `a timed event ending before it starts, or without end, ends at its start`() {
		val start = Instant.parse("2026-10-22T07:30:00Z")
		(read(utc("2026-10-22T07:30:00", "2026-10-22T07:00:00")).timing as Timing.Timed).end shouldBe start
		(read(""""start": {"dateTime": "2026-10-22T07:30:00", "timeZone": "UTC"}""").timing as Timing.Timed).end shouldBe start
	}

	@ParameterizedTest
	@CsvSource(
		"Customized Time Zone, UTC",
		"tzone://Microsoft/Custom, UTC",
		"Europe/Zurich, Europe/Zurich",
		"W. Europe Standard Time, Europe/Berlin",
	)
	fun `the event's zone is its original zone, unknown zones fall back to the start's zone`(
		original: String,
		zone: String,
	) {
		val read = read("""${utc("2026-10-22T07:30:00", "2026-10-22T08:00:00")}, "originalStartTimeZone": "$original"""")

		val timing = read.timing as Timing.Timed
		timing.zone shouldBe ZoneId.of(zone)
		timing.start shouldBe Instant.parse("2026-10-22T07:30:00Z")
	}

	@Test
	fun `without any known zone the event is in UTC`() {
		val read =
			read(
				""""originalStartTimeZone": "Customized Time Zone",
				"start": {"dateTime": "2026-10-22T07:30:00"}, "end": {"dateTime": "2026-10-22T08:00:00"}""",
			)

		read.timing shouldBe Timing.Timed(Instant.parse("2026-10-22T07:30:00Z"), Instant.parse("2026-10-22T08:00:00Z"), ZoneOffset.UTC)
	}

	@Test
	fun `a start in another zone than UTC is converted with that zone`() {
		val read =
			read(
				""""start": {"dateTime": "2026-10-22T09:30:00", "timeZone": "W. Europe Standard Time"},
				"end": {"dateTime": "2026-10-22T10:00:00", "timeZone": "Europe/Berlin"}""",
			)

		read.timing shouldBe
			Timing.Timed(Instant.parse("2026-10-22T07:30:00Z"), Instant.parse("2026-10-22T08:00:00Z"), ZoneId.of("Europe/Berlin"))
	}

	@Test
	fun `status, attendees, reminder and conference read like Outlook shows them`() {
		val read =
			read(
				"""${utc("2026-10-22T07:30:00", "2026-10-22T08:00:00")},
				"isCancelled": true, "showAs": "tentative", "isReminderOn": false, "reminderMinutesBeforeStart": 15,
				"onlineMeeting": null, "onlineMeetingUrl": "https://meet.example.test/fictitious",
				"location": {"displayName": "  "},
				"attendees": [
				  {"emailAddress": {"address": "org@example.test", "name": "Org"}, "status": {"response": "organizer"}},
				  {"emailAddress": {"address": "no@example.test"}, "status": {"response": "declined"}},
				  {"emailAddress": {"name": "Without address"}, "status": {"response": "accepted"}},
				  {"emailAddress": {"address": "later@example.test"}}
				]""",
			)

		read.status shouldBe EventStatus.CANCELLED
		read.transparency shouldBe EventTransparency.OPAQUE
		read.reminderMinutes shouldBe emptyList()
		read.conferenceUrl shouldBe "https://meet.example.test/fictitious"
		read.location.shouldBeNull()
		read.attendees shouldContainExactly
			listOf(
				Attendee("org@example.test", "Org", AttendeeStatus.ACCEPTED),
				Attendee("no@example.test", null, AttendeeStatus.DECLINED),
				Attendee("later@example.test", null, AttendeeStatus.NEEDS_ACTION),
			)
	}

	@Test
	fun `an event without start cannot be read`() {
		shouldThrow<IllegalArgumentException> { read(""""subject": "No start"""") }
	}

	@Test
	fun `only a series master carries its rule and EXDATEs`() {
		val recurrence = """"recurrence": {"pattern": {"type": "daily", "interval": 1}, "range": {"type": "noEnd"}}"""
		val fields = """${utc("2026-10-22T07:30:00", "2026-10-22T08:00:00")}, $recurrence"""
		val exdates = setOf(MicrosoftCalendarAdapterTests.at("2026-10-23T07:30:00Z"))

		val master =
			GraphEventMapping.toRemote(json.readTree("""{"id": "AAMkMapping1", "type": "seriesMaster", $fields}"""), key, exdates)
		master.rrule shouldBe "FREQ=DAILY"
		master.exdates shouldBe exdates
		val occurrence = GraphEventMapping.toRemote(json.readTree("""{"id": "AAMkMapping1", "type": "occurrence", $fields}"""), key, exdates)
		occurrence.rrule.shouldBeNull()
		occurrence.exdates shouldBe emptySet()
	}

	@Test
	fun `the original start is originalStart with its offset, else the start`() {
		GraphEventMapping.originalStart(
			event("""${utc("2026-10-22T07:30:00", "2026-10-22T08:00:00")}, "originalStart": "2026-10-21T09:00:00+02:00""""),
		) shouldBe Instant.parse("2026-10-21T07:00:00Z")
		GraphEventMapping.originalStart(event(utc("2026-10-22T07:30:00", "2026-10-22T08:00:00"))) shouldBe Instant.parse("2026-10-22T07:30:00Z")
	}

	@Test
	fun `a calendar without name is named by its id, a blank color is none`() {
		GraphEventMapping.toCalendar(json.readTree("""{"id": "AAMkCalX", "hexColor": "", "canEdit": true}""")) shouldBe
			RemoteCalendar("AAMkCalX", "AAMkCalX", null, readOnly = false)
		GraphEventMapping.toCalendar(json.readTree("""{"id": "AAMkCalY", "name": "Shared"}""")).readOnly shouldBe true
	}

	// --- writing

	private fun planning(
		timing: Timing = Timing.Timed(Instant.parse("2026-10-28T08:00:00Z"), Instant.parse("2026-10-28T09:00:00Z"), ZoneId.of("Europe/Berlin")),
	) = RemoteEvent(key = key, etag = null, title = "Planning", timing = timing)

	@Test
	fun `a fixed offset zone is written in UTC`() {
		val body =
			GraphEventMapping.toBody(
				json,
				planning(Timing.Timed(Instant.parse("2026-10-28T08:00:00Z"), Instant.parse("2026-10-28T09:00:00Z"), ZoneOffset.ofHours(2))),
				recurrence = false,
			)

		body.get("start").get("dateTime").asString() shouldBe "2026-10-28T08:00:00"
		body.get("start").get("timeZone").asString() shouldBe "UTC"
		body.get("end").get("dateTime").asString() shouldBe "2026-10-28T09:00:00"
	}

	@Test
	fun `a golden zone is written with its Windows name and local times`() {
		val body = GraphEventMapping.toBody(json, planning(), recurrence = false)

		body.get("start").get("dateTime").asString() shouldBe "2026-10-28T09:00:00"
		body.get("start").get("timeZone").asString() shouldBe "W. Europe Standard Time"
	}

	@Test
	fun `an all-day event is written from midnight to midnight in UTC`() {
		val body = GraphEventMapping.toBody(json, planning(Timing.AllDay(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-04"))), false)

		body.get("isAllDay").asBoolean() shouldBe true
		body.get("start").get("dateTime").asString() shouldBe "2026-11-02T00:00:00"
		body.get("end").get("dateTime").asString() shouldBe "2026-11-04T00:00:00"
		body.get("end").get("timeZone").asString() shouldBe "UTC"
	}

	@Test
	fun `showAs, the first reminder only, attendees and a series without rule are written`() {
		val event =
			planning().copy(
				reminderMinutes = listOf(10, 60),
				status = EventStatus.TENTATIVE,
				attendees = listOf(Attendee("sam.sample@example.test", "Sam Sample", AttendeeStatus.ACCEPTED)),
			)

		val body = GraphEventMapping.toBody(json, event, recurrence = true)
		body.get("showAs").asString() shouldBe "tentative"
		body.get("reminderMinutesBeforeStart").asInt() shouldBe 10
		body.get("isReminderOn").asBoolean() shouldBe true
		body.get("recurrence").isNull shouldBe true
		body.at("/attendees/0/emailAddress/address").asString() shouldBe "sam.sample@example.test"
		body.at("/attendees/0/type").asString() shouldBe "required"

		val free = GraphEventMapping.toBody(json, event.copy(transparency = EventTransparency.TRANSPARENT, reminderMinutes = emptyList()), false)
		free.get("showAs").asString() shouldBe "free"
		free.get("isReminderOn").asBoolean() shouldBe false
		free.has("reminderMinutesBeforeStart") shouldBe false
		free.has("recurrence") shouldBe false
	}

	@Test
	fun `a series in a fixed offset zone starts its pattern on the day of the start Graph gets`() {
		// Monday 01:00 at UTC+2 is Sunday 23:00 in UTC, which is how the times go to Graph.
		val event =
			planning(Timing.Timed(Instant.parse("2026-10-04T23:00:00Z"), Instant.parse("2026-10-04T23:30:00Z"), ZoneOffset.ofHours(2)))
				.copy(rrule = "FREQ=WEEKLY")

		val body = GraphEventMapping.toBody(json, event, recurrence = true)

		body.get("start").get("dateTime").asString() shouldBe "2026-10-04T23:00:00"
		// Graph: the range's startDate must be the date of the series' start.
		body.at("/recurrence/range/startDate").asString() shouldBe "2026-10-04"
		body
			.get("recurrence")
			.get("pattern")
			.get("daysOfWeek")
			.values()
			.map { it.asString() } shouldBe listOf("sunday")
	}

	/** The recurrence Graph gets for [rule] on a series starting at [start] (30 minutes) in the fixed [offset]. */
	private fun fixedOffsetRecurrence(
		rule: String,
		start: String,
		offset: Int,
	): JsonNode {
		val at = Instant.parse(start)
		val event = planning(Timing.Timed(at, at.plusSeconds(1800), ZoneOffset.ofHours(offset))).copy(rrule = rule)
		return GraphEventMapping.toBody(json, event, recurrence = true).get("recurrence")
	}

	private fun JsonNode.days() = at("/pattern/daysOfWeek").values().map { it.asString() }

	@Test
	fun `explicit weekdays of a fixed offset series move to the days Graph gets`() {
		// Monday and Wednesday 01:00 at UTC+2 are Sunday and Tuesday 23:00 in UTC.
		val east = fixedOffsetRecurrence("FREQ=WEEKLY;BYDAY=MO,WE", "2026-10-04T23:00:00Z", 2)
		east.days() shouldBe listOf("sunday", "tuesday")
		east.at("/range/startDate").asString() shouldBe "2026-10-04"
		east.at("/range/recurrenceTimeZone").asString() shouldBe "UTC"

		// Friday and Sunday 21:00 at UTC-5 are Saturday and Monday 02:00 in UTC.
		val west = fixedOffsetRecurrence("FREQ=DAILY;BYDAY=FR,SU", "2026-10-03T02:00:00Z", -5)
		west.days() shouldBe listOf("saturday", "monday")
		west.at("/range/startDate").asString() shouldBe "2026-10-03"
	}

	@Test
	fun `the first day of the week moves with the days, so every other week stays the same weeks`() {
		val recurrence = fixedOffsetRecurrence("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO;WKST=MO", "2026-10-04T23:00:00Z", 2)

		recurrence.days() shouldBe listOf("sunday")
		recurrence.at("/pattern/firstDayOfWeek").asString() shouldBe "sunday"
		recurrence.at("/pattern/interval").asInt() shouldBe 2
	}

	@Test
	fun `a fixed offset series on the same day in UTC keeps its days`() {
		// Monday 10:00 at UTC+2 is Monday 08:00 in UTC.
		val recurrence = fixedOffsetRecurrence("FREQ=WEEKLY;BYDAY=MO,WE", "2026-10-05T08:00:00Z", 2)

		recurrence.days() shouldBe listOf("monday", "wednesday")
		recurrence.at("/pattern/firstDayOfWeek").asString() shouldBe "monday"
		recurrence.at("/range/startDate").asString() shouldBe "2026-10-05"
	}

	@ParameterizedTest
	@CsvSource(
		// The 15th, 01:00 at UTC+2, is the 14th in UTC: explicit and from the start.
		"FREQ=MONTHLY;BYMONTHDAY=15, 2026-10-14T23:00:00Z, 2, 14",
		"FREQ=MONTHLY, 2026-10-14T23:00:00Z, 2, 14",
		// The 15th, 21:00 at UTC-5, is the 16th in UTC.
		"FREQ=MONTHLY;BYMONTHDAY=15, 2026-10-16T02:00:00Z, -5, 16",
		"FREQ=YEARLY;BYMONTH=10;BYMONTHDAY=15, 2026-10-14T23:00:00Z, 2, 14",
	)
	fun `a day of the month of a fixed offset series moves to the day Graph gets`(
		rule: String,
		start: String,
		offset: Int,
		day: Int,
	) {
		val recurrence = fixedOffsetRecurrence(rule, start, offset)

		recurrence.at("/pattern/dayOfMonth").asInt() shouldBe day
		recurrence.at("/range/startDate").asString() shouldBe
			Instant
				.parse(start)
				.atZone(ZoneOffset.UTC)
				.toLocalDate()
				.toString()
	}

	@ParameterizedTest
	@CsvSource(
		// The 1st at UTC+2 is the last day of the month before in UTC: not one day of the month.
		"FREQ=MONTHLY;BYMONTHDAY=1, 2026-10-14T23:00:00Z, 2",
		"FREQ=MONTHLY, 2026-09-30T23:00:00Z, 2",
		// The 28th at UTC-5 is the 29th in UTC, which February does not always have.
		"FREQ=MONTHLY;BYMONTHDAY=28, 2026-10-16T02:00:00Z, -5",
		// The second Monday at UTC+2 is not always the second Sunday in UTC.
		"FREQ=MONTHLY;BYDAY=2MO, 2026-10-11T23:00:00Z, 2",
		"'FREQ=YEARLY;BYMONTH=11;BYDAY=MO,TU;BYSETPOS=1', 2026-10-04T23:00:00Z, 2",
	)
	fun `a fixed offset pattern that cannot move to the days Graph gets is refused`(
		rule: String,
		start: String,
		offset: Int,
	) {
		shouldThrow<IllegalArgumentException> { fixedOffsetRecurrence(rule, start, offset) }
	}

	@Test
	fun `the end of a fixed offset series is the day of its last occurrence in UTC`() {
		// The last occurrence, Monday 16 November 01:00 at UTC+2, is Sunday 15 November 23:00 in UTC.
		val recurrence = fixedOffsetRecurrence("FREQ=WEEKLY;BYDAY=MO;UNTIL=20261115T230000Z", "2026-10-04T23:00:00Z", 2)

		recurrence.at("/range/endDate").asString() shouldBe "2026-11-15"
	}

	// --- changes

	@Test
	fun `no change is an empty diff`() {
		val body = GraphEventMapping.toBody(json, planning(), false)

		GraphEventMapping.changes(json, body, GraphEventMapping.toBody(json, planning(), false)).isEmpty shouldBe true
	}

	@Test
	fun `changed reminder minutes bring the reminder switch`() {
		val current = GraphEventMapping.toBody(json, planning().copy(reminderMinutes = listOf(10)), false)
		val desired = GraphEventMapping.toBody(json, planning().copy(reminderMinutes = listOf(30)), false)

		GraphEventMapping.changes(json, desired, current).propertyNames().toList() shouldContainExactlyInAnyOrder
			listOf("reminderMinutesBeforeStart", "isReminderOn")
	}

	@Test
	fun `a reminder switched off sends the switch only`() {
		val current = GraphEventMapping.toBody(json, planning().copy(reminderMinutes = listOf(10)), false)
		val desired = GraphEventMapping.toBody(json, planning(), false)

		val diff = GraphEventMapping.changes(json, desired, current)
		diff.propertyNames().toList() shouldContainExactly listOf("isReminderOn")
		diff.get("isReminderOn").asBoolean() shouldBe false
	}

	@Test
	fun `a changed end sends start, end and isAllDay together`() {
		val current = GraphEventMapping.toBody(json, planning(), false)
		val longer =
			planning(Timing.Timed(Instant.parse("2026-10-28T08:00:00Z"), Instant.parse("2026-10-28T10:00:00Z"), ZoneId.of("Europe/Berlin")))
				.copy(title = "Longer planning")

		val diff = GraphEventMapping.changes(json, GraphEventMapping.toBody(json, longer, false), current)
		diff.propertyNames().toList() shouldContainExactlyInAnyOrder listOf("subject", "start", "end", "isAllDay")
		diff.get("end").get("dateTime").asString() shouldBe "2026-10-28T11:00:00"
		diff.get("start").get("dateTime").asString() shouldBe "2026-10-28T09:00:00"
	}

	@Test
	fun `unchanged attendees are not sent again, changed ones are`() {
		val sam = Attendee("sam.sample@example.test", "Sam Sample", AttendeeStatus.ACCEPTED)
		val current = GraphEventMapping.toBody(json, planning().copy(attendees = listOf(sam)), false)

		GraphEventMapping
			.changes(json, GraphEventMapping.toBody(json, planning().copy(attendees = listOf(sam), location = "Room 1"), false), current)
			.propertyNames()
			.toList() shouldContainExactly listOf("location")
		val kim = Attendee("kim.demo@example.test", null, AttendeeStatus.NEEDS_ACTION)
		GraphEventMapping
			.changes(json, GraphEventMapping.toBody(json, planning().copy(attendees = listOf(sam, kim)), false), current)
			.propertyNames()
			.toList() shouldContainExactly listOf("attendees")
	}

	// --- attendee types

	/** The `type` of each attendee in [changes], by address as sent. */
	private fun typesOf(changes: JsonNode): Map<String, String> =
		changes.get("attendees").associate { it.at("/emailAddress/address").asString() to it.get("type").asString() }

	@Test
	fun `attendee types are matched by email ignoring case`() {
		val sam = Attendee("sam.sample@example.test", "Sam Sample", AttendeeStatus.ACCEPTED)
		val room = Attendee("ROOM.1@example.test", "Room 1", AttendeeStatus.ACCEPTED)
		val changes = GraphEventMapping.toBody(json, planning().copy(attendees = listOf(sam, room)), false)
		val graph =
			event(
				"""
				"attendees": [
				  {"type": "optional", "emailAddress": {"address": "Sam.Sample@Example.TEST"}},
				  {"type": "resource", "emailAddress": {"address": "room.1@EXAMPLE.test"}}]
				""".trimIndent(),
			)

		GraphEventMapping.keepAttendeeTypes(changes, graph)

		typesOf(changes) shouldBe mapOf("sam.sample@example.test" to "optional", "ROOM.1@example.test" to "resource")
	}

	@Test
	fun `attendees Graph has without type or address leave the sent attendees required`() {
		val sam = Attendee("sam.sample@example.test", null, AttendeeStatus.NEEDS_ACTION)
		val kim = Attendee("kim.demo@example.test", null, AttendeeStatus.NEEDS_ACTION)
		val changes = GraphEventMapping.toBody(json, planning().copy(attendees = listOf(sam, kim)), false)
		val graph =
			event(
				"""
				"attendees": [
				  {"emailAddress": {"address": "sam.sample@example.test"}},
				  {"type": "optional", "emailAddress": {"name": "Kim Demo"}},
				  {"type": "optional"}]
				""".trimIndent(),
			)

		GraphEventMapping.keepAttendeeTypes(changes, graph)

		typesOf(changes) shouldBe mapOf("sam.sample@example.test" to "required", "kim.demo@example.test" to "required")
	}

	@Test
	fun `a diff without attendees is left as it is`() {
		val changes = json.createObjectNode().put("subject", "Planning (moved)")
		val graph = event(""""attendees": [{"type": "optional", "emailAddress": {"address": "sam.sample@example.test"}}]""")

		GraphEventMapping.keepAttendeeTypes(changes, graph)

		changes.propertyNames().toList() shouldContainExactly listOf("subject")
	}
}
