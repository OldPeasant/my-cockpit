package ch.sonensei.cockpit.calendar.itip

import ch.sonensei.cockpit.calendar.infra.caldav.CalDavEventMapping
import ch.sonensei.cockpit.calendar.infra.caldav.IcsComponent
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.core.persistence.BaseEntity
import ch.sonensei.cockpit.core.persistence.UuidV7
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** [ItipMessages]: the REQUEST/CANCEL objects the cockpit sends as organizer and the REPLYs it reads. */
class ItipMessagesTests {
	private val calendarId = UuidV7.generate()
	private val zurich = ZoneId.of("Europe/Zurich")
	private val now = Instant.parse("2026-10-26T12:00:00Z")
	private val organizer = ItipOrganizer("jane.doe@example.test", "Jane Doe")

	private fun event(
		title: String = "Project kickoff",
		version: Long = 0,
		attendees: List<Attendee> =
			listOf(
				Attendee("anna@example.test", "Anna Example"),
				Attendee("bob@example.test", "Bob Example", AttendeeStatus.ACCEPTED),
			),
	): CalendarEvent =
		CalendarEvent(calendarId, title).apply {
			setTimed(Instant.parse("2026-11-02T08:00:00Z"), Instant.parse("2026-11-02T09:00:00Z"), zurich)
			this.attendees = attendees
			reminderMinutes = listOf(15, 60)
			withVersion(version)
		}

	private fun CalendarEvent.withVersion(version: Long): CalendarEvent {
		BaseEntity::class.java
			.getDeclaredField("version")
			.apply { isAccessible = true }
			.setLong(this, version)
		return this
	}

	private fun weekly(): CalendarEvent = event(title = "Team meeting").apply { rrule = "FREQ=WEEKLY" }

	private fun overrideOf(
		master: CalendarEvent,
		occurrence: Instant,
		version: Long,
	): CalendarEvent =
		CalendarEvent(calendarId, "Team meeting (room B)").apply {
			setTimed(occurrence.plusSeconds(3600), occurrence.plusSeconds(7200), zurich)
			attendees = master.attendees
			overrideOf(master, OccurrenceId.of(occurrence))
			withVersion(version)
		}

	private fun parsed(text: String): IcsComponent = CalDavEventMapping.calendar(text)

	private fun IcsComponent.vevents() = children("VEVENT")

	// --- build: REQUEST ------------------------------------------------------------------------------------------------

	@Test
	fun `a REQUEST for a single event carries the method, the event id as UID and its version as SEQUENCE`() {
		val event = event(version = 3)

		val calendar = parsed(ItipMessages.build(ItipMethod.REQUEST, listOf(event), null, organizer, now))

		calendar.text("METHOD") shouldBe "REQUEST"
		calendar.text("VERSION") shouldBe "2.0"
		val vevent = calendar.vevents().single()
		vevent.text("UID") shouldBe event.id.toString()
		vevent.text("SEQUENCE") shouldBe "3"
		vevent.text("SUMMARY") shouldBe "Project kickoff"
		vevent.property("DTSTART")!!.param("TZID") shouldBe "Europe/Zurich"
		vevent.property("DTSTART")!!.value shouldBe "20261102T090000"
		calendar.children("VTIMEZONE").map { it.text("TZID") } shouldContainExactly listOf("Europe/Zurich")
		vevent.property("RECURRENCE-ID").shouldBeNull()
	}

	@Test
	fun `a REQUEST names the organizer with CN and asks attendees that have not answered for an RSVP`() {
		val calendar = parsed(ItipMessages.build(ItipMethod.REQUEST, listOf(event()), null, organizer, now))

		val vevent = calendar.vevents().single()
		val organizerLine = vevent.property("ORGANIZER")!!
		organizerLine.value shouldBe "mailto:jane.doe@example.test"
		organizerLine.param("CN") shouldBe "Jane Doe"
		val (anna, bob) = vevent.all("ATTENDEE")
		anna.value shouldBe "mailto:anna@example.test"
		anna.param("PARTSTAT") shouldBe "NEEDS-ACTION"
		anna.param("RSVP") shouldBe "TRUE"
		anna.param("ROLE") shouldBe "REQ-PARTICIPANT"
		anna.param("CN") shouldBe "Anna Example"
		bob.param("PARTSTAT") shouldBe "ACCEPTED"
		bob.param("RSVP").shouldBeNull()
		bob.param("ROLE") shouldBe "REQ-PARTICIPANT"
	}

	@Test
	fun `the user's reminders are not sent to the attendees`() {
		val text = ItipMessages.build(ItipMethod.REQUEST, listOf(event()), null, organizer, now)

		parsed(text)
			.vevents()
			.single()
			.children("VALARM")
			.shouldBeEmpty()
		text shouldNotContain "VALARM"
	}

	@Test
	fun `an organizer without a name has no CN, a name with special characters is quoted`() {
		val unnamed = parsed(ItipMessages.build(ItipMethod.REQUEST, listOf(event()), null, ItipOrganizer("jane.doe@example.test", null), now))
		unnamed
			.vevents()
			.single()
			.property("ORGANIZER")!!
			.param("CN")
			.shouldBeNull()

		val text = ItipMessages.build(ItipMethod.REQUEST, listOf(event()), null, ItipOrganizer("jane.doe@example.test", "Doe, Jane; Team"), now)
		parsed(text)
			.vevents()
			.single()
			.property("ORGANIZER")!!
			.param("CN") shouldBe "Doe, Jane; Team"
	}

	@Test
	fun `a REQUEST for an event without attendees is still a valid object with no ATTENDEE lines`() {
		val calendar = parsed(ItipMessages.build(ItipMethod.REQUEST, listOf(event(attendees = emptyList())), null, organizer, now))

		calendar
			.vevents()
			.single()
			.all("ATTENDEE")
			.shouldBeEmpty()
	}

	@Test
	fun `an all-day event is sent with DATE values`() {
		val allDay =
			CalendarEvent(calendarId, "Offsite").apply {
				setAllDay(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 7))
				attendees = listOf(Attendee("anna@example.test"))
			}

		val vevent = parsed(ItipMessages.build(ItipMethod.REQUEST, listOf(allDay), null, organizer, now)).vevents().single()

		vevent.property("DTSTART")!!.value shouldBe "20261105"
		vevent.property("DTSTART")!!.param("VALUE") shouldBe "DATE"
		vevent.property("DTEND")!!.value shouldBe "20261107"
	}

	@Test
	fun `a series is sent with its overrides, all with the master's UID and their own SEQUENCE`() {
		val master = weekly().withVersion(2)
		val override = overrideOf(master, Instant.parse("2026-11-09T08:00:00Z"), version = 5)

		val calendar = parsed(ItipMessages.build(ItipMethod.REQUEST, listOf(master, override), null, organizer, now))

		val (first, second) = calendar.vevents().also { it shouldHaveSize 2 }
		first.text("UID") shouldBe master.id.toString()
		first.text("RRULE") shouldBe "FREQ=WEEKLY"
		first.text("SEQUENCE") shouldBe "2"
		first.property("RECURRENCE-ID").shouldBeNull()
		second.text("UID") shouldBe master.id.toString()
		second.text("SEQUENCE") shouldBe "5"
		second.text("SUMMARY") shouldBe "Team meeting (room B)"
		second.property("RECURRENCE-ID")!!.value shouldBe "20261109T090000"
		second.property("RECURRENCE-ID")!!.param("TZID") shouldBe "Europe/Zurich"
		listOf(first, second).forEach { it.property("ORGANIZER")!!.value shouldBe "mailto:jane.doe@example.test" }
	}

	@Test
	fun `one override is sent on its own with its master's UID and a RECURRENCE-ID written like the series start`() {
		val master = weekly().withVersion(2)
		val override = overrideOf(master, Instant.parse("2026-11-09T08:00:00Z"), version = 1)

		val calendar = parsed(ItipMessages.build(ItipMethod.REQUEST, listOf(override), master, organizer, now))

		val vevent = calendar.vevents().single()
		vevent.text("UID") shouldBe master.id.toString()
		vevent.text("SEQUENCE") shouldBe "1"
		vevent.text("SUMMARY") shouldBe "Team meeting (room B)"
		vevent.property("RECURRENCE-ID")!!.value shouldBe "20261109T090000"
		vevent.property("RECURRENCE-ID")!!.param("TZID") shouldBe "Europe/Zurich"
		vevent.property("RRULE").shouldBeNull()
	}

	// --- build: CANCEL -------------------------------------------------------------------------------------------------

	@Test
	fun `a CANCEL marks the event cancelled, keeps all attendees and asks for no RSVP`() {
		val event = event(version = 4)

		val calendar = parsed(ItipMessages.build(ItipMethod.CANCEL, listOf(event), null, organizer, now))

		calendar.text("METHOD") shouldBe "CANCEL"
		val vevent = calendar.vevents().single()
		vevent.text("STATUS") shouldBe "CANCELLED"
		vevent.text("UID") shouldBe event.id.toString()
		vevent.text("SEQUENCE") shouldBe "4"
		vevent.all("ATTENDEE").map { it.value } shouldContainExactly listOf("mailto:anna@example.test", "mailto:bob@example.test")
		vevent.all("ATTENDEE").forEach { it.param("RSVP").shouldBeNull() }
		vevent.children("VALARM").shouldBeEmpty()
	}

	@Test
	fun `a CANCEL to people no longer invited lists only them as attendees and does not cancel the event`() {
		val calendar =
			parsed(
				ItipMessages.build(
					ItipMethod.CANCEL,
					listOf(event()),
					null,
					organizer,
					now,
					attendeesOnly = listOf("carol@example.test", "dave@example.test"),
				),
			)

		val vevent = calendar.vevents().single()
		// Uninviting does not cancel the event (RFC 5546 §3.2.5): no STATUS at all.
		vevent.text("STATUS").shouldBeNull()
		vevent.all("STATUS").shouldBeEmpty()
		vevent.all("ATTENDEE").map { it.value } shouldContainExactly listOf("mailto:carol@example.test", "mailto:dave@example.test")
		vevent.all("ATTENDEE").forEach {
			it.param("ROLE") shouldBe "REQ-PARTICIPANT"
			it.param("RSVP").shouldBeNull()
		}
	}

	@Test
	fun `attendeesOnly is ignored for a REQUEST`() {
		val calendar =
			parsed(ItipMessages.build(ItipMethod.REQUEST, listOf(event()), null, organizer, now, attendeesOnly = listOf("carol@example.test")))

		calendar
			.vevents()
			.single()
			.all("ATTENDEE")
			.map { it.value } shouldContainExactly
			listOf("mailto:anna@example.test", "mailto:bob@example.test")
		calendar.vevents().single().text("STATUS") shouldBe "CONFIRMED"
	}

	// --- uid / eventId -------------------------------------------------------------------------------------------------

	@Test
	fun `the UID of an override is its master's id, an event's own id otherwise`() {
		val master = weekly()
		val override = overrideOf(master, Instant.parse("2026-11-09T08:00:00Z"), version = 0)

		ItipMessages.uid(master) shouldBe master.id.toString()
		ItipMessages.uid(override) shouldBe master.id.toString()
	}

	@Test
	fun `eventId reads cockpit UIDs and rejects foreign ones`() {
		val id = UUID.fromString("01937a3c-4c1e-7a00-8000-000000000001")

		ItipMessages.eventId(id.toString()) shouldBe id
		ItipMessages.eventId("  $id ") shouldBe id
		ItipMessages.eventId("040000008200E00074C5B7101A82E008@outlook.example").shouldBeNull()
		ItipMessages.eventId("").shouldBeNull()
	}

	// --- replies -------------------------------------------------------------------------------------------------------

	private fun reply(
		vararg vevents: String,
		method: String = "REPLY",
		extra: String = "",
	): String =
		buildString {
			append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Example//Mail//EN\r\n")
			if (method.isNotEmpty()) append("METHOD:$method\r\n")
			append(extra)
			vevents.forEach { append("BEGIN:VEVENT\r\n").append(it.trimIndent().replace("\n", "\r\n")).append("\r\nEND:VEVENT\r\n") }
			append("END:VCALENDAR\r\n")
		}

	@Test
	fun `a REPLY gives the attendee's answer for the event`() {
		val text =
			reply(
				"""
				UID:$UID
				SEQUENCE:2
				DTSTAMP:20261026T120000Z
				ATTENDEE;PARTSTAT=ACCEPTED;CN=Anna Example:mailto:anna@example.test
				""",
			)

		ItipMessages.replies(text) shouldContainExactly
			listOf(ItipReply(UID, null, "anna@example.test", AttendeeStatus.ACCEPTED, 2, Instant.parse("2026-10-26T12:00:00Z")))
	}

	@Test
	fun `a missing or unreadable SEQUENCE or DTSTAMP is read as unknown`() {
		val text =
			reply(
				"UID:$UID\nSEQUENCE:one\nDTSTAMP:2026-10-26 12:00\nATTENDEE;PARTSTAT=ACCEPTED:mailto:anna@example.test",
				"UID:$UID\nDTSTAMP:20261026T120000\nATTENDEE;PARTSTAT=DECLINED:mailto:bob@example.test",
			)

		ItipMessages.replies(text).map { it.sequence to it.stamp } shouldContainExactly listOf(null to null, null to null)
	}

	@Test
	fun `answers are ordered by SEQUENCE (0 when missing), then DTSTAMP (missing first)`() {
		val base = ItipReply(UID, null, "anna@example.test", AttendeeStatus.ACCEPTED)
		val early = Instant.parse("2026-10-26T12:00:00Z")
		val late = Instant.parse("2026-10-27T12:00:00Z")
		val replies =
			listOf(
				base.copy(sequence = 1, stamp = early),
				base.copy(sequence = 0, stamp = late),
				base,
				base.copy(sequence = 1, stamp = late),
				base.copy(sequence = 1),
				base.copy(stamp = late),
				base.copy(sequence = 0, stamp = early),
			)

		replies.sortedWith(ItipReply.ORDER).map { it.sequence to it.stamp } shouldContainExactly
			listOf(null to null, 0 to early, 0 to late, null to late, 1 to null, 1 to early, 1 to late)
		base.copy(email = "Anna@Example.test", uid = " $UID").key shouldBe base.key
	}

	@Test
	fun `every PARTSTAT the cockpit knows is read, case-insensitively`() {
		val statuses =
			mapOf(
				"ACCEPTED" to AttendeeStatus.ACCEPTED,
				"declined" to AttendeeStatus.DECLINED,
				"Tentative" to AttendeeStatus.TENTATIVE,
				"DELEGATED" to AttendeeStatus.DELEGATED,
				"NEEDS-ACTION" to AttendeeStatus.NEEDS_ACTION,
			)
		statuses.forEach { (partStat, status) ->
			val text = reply("UID:$UID\nATTENDEE;PARTSTAT=$partStat:mailto:anna@example.test")

			ItipMessages.replies(text).single().status shouldBe status
		}
	}

	@Test
	fun `attendees with an unknown or missing PARTSTAT are skipped`() {
		val text =
			reply(
				"""
				UID:$UID
				ATTENDEE;PARTSTAT=IN-PROCESS:mailto:anna@example.test
				ATTENDEE:mailto:bob@example.test
				ATTENDEE;PARTSTAT=DECLINED:mailto:carol@example.test
				""",
			)

		ItipMessages.replies(text) shouldContainExactly listOf(ItipReply(UID, null, "carol@example.test", AttendeeStatus.DECLINED))
	}

	@Test
	fun `only mailto attendees with an address count, the scheme is case-insensitive and blanks are trimmed`() {
		val text =
			reply(
				"""
				UID:$UID
				ATTENDEE;PARTSTAT=ACCEPTED:urn:uuid:01937a3c-4c1e-7a00-8000-000000000009
				ATTENDEE;PARTSTAT=ACCEPTED:mailto:
				ATTENDEE;PARTSTAT=ACCEPTED:MAILTO:Anna@Example.test
				ATTENDEE;PARTSTAT=TENTATIVE:mailto: bob@example.test
				""",
			)

		ItipMessages.replies(text) shouldContainExactly
			listOf(
				ItipReply(UID, null, "Anna@Example.test", AttendeeStatus.ACCEPTED),
				ItipReply(UID, null, "bob@example.test", AttendeeStatus.TENTATIVE),
			)
	}

	@Test
	fun `messages with another method or none give no answers`() {
		val vevent = "UID:$UID\nATTENDEE;PARTSTAT=ACCEPTED:mailto:anna@example.test"

		ItipMessages.replies(reply(vevent, method = "REQUEST")).shouldBeEmpty()
		ItipMessages.replies(reply(vevent, method = "COUNTER")).shouldBeEmpty()
		ItipMessages.replies(reply(vevent, method = "")).shouldBeEmpty()
		ItipMessages.replies(reply(vevent, method = " reply ")).shouldHaveSize(1)
	}

	@Test
	fun `text that is no iCalendar object is rejected`() {
		assertThrows<IllegalArgumentException> { ItipMessages.replies("Thanks, I will be there!") }
		assertThrows<IllegalArgumentException> { ItipMessages.replies("") }
	}

	@Test
	fun `VEVENTs without a UID are skipped, the others are read`() {
		val text =
			reply(
				"ATTENDEE;PARTSTAT=ACCEPTED:mailto:anna@example.test",
				"UID:  \nATTENDEE;PARTSTAT=ACCEPTED:mailto:anna@example.test",
				"UID:$UID\nATTENDEE;PARTSTAT=DECLINED:mailto:anna@example.test",
			)

		ItipMessages.replies(text) shouldContainExactly listOf(ItipReply(UID, null, "anna@example.test", AttendeeStatus.DECLINED))
	}

	@Test
	fun `a REPLY for one occurrence names it by its RECURRENCE-ID in UTC, in a TZID or as a date`() {
		val text =
			reply(
				"UID:$UID\nRECURRENCE-ID:20261109T080000Z\nATTENDEE;PARTSTAT=ACCEPTED:mailto:anna@example.test",
				"UID:$UID\nRECURRENCE-ID;TZID=Europe/Zurich:20261116T090000\nATTENDEE;PARTSTAT=DECLINED:mailto:anna@example.test",
				"UID:$UID\nRECURRENCE-ID;VALUE=DATE:20261123\nATTENDEE;PARTSTAT=TENTATIVE:mailto:anna@example.test",
			)

		ItipMessages.replies(text).map { it.recurrenceId } shouldContainExactly
			listOf(
				OccurrenceId.of(Instant.parse("2026-11-09T08:00:00Z")),
				OccurrenceId.of(Instant.parse("2026-11-16T08:00:00Z")),
				OccurrenceId.of(LocalDate.of(2026, 11, 23)),
			)
	}

	@Test
	fun `a RECURRENCE-ID in a custom VTIMEZONE of the reply is read in that zone`() {
		val zone =
			"""
			BEGIN:VTIMEZONE
			TZID:Custom Central
			BEGIN:STANDARD
			DTSTART:19701025T030000
			TZOFFSETFROM:+0200
			TZOFFSETTO:+0100
			END:STANDARD
			END:VTIMEZONE
			""".trimIndent().replace("\n", "\r\n") + "\r\n"
		val text =
			reply(
				"UID:$UID\nRECURRENCE-ID;TZID=\"Custom Central\":20261109T090000\nATTENDEE;PARTSTAT=ACCEPTED:mailto:anna@example.test",
				extra = zone,
			)

		ItipMessages.replies(text).single().recurrenceId shouldBe OccurrenceId.of(Instant.parse("2026-11-09T08:00:00Z"))
	}

	@Test
	fun `a VEVENT with an unreadable RECURRENCE-ID is skipped, not taken as an answer for the series`() {
		val text =
			reply(
				"UID:$UID\nRECURRENCE-ID:next-monday\nATTENDEE;PARTSTAT=DECLINED:mailto:anna@example.test",
				"UID:$UID\nATTENDEE;PARTSTAT=ACCEPTED:mailto:bob@example.test",
			)

		ItipMessages.replies(text) shouldContainExactly listOf(ItipReply(UID, null, "bob@example.test", AttendeeStatus.ACCEPTED))
	}

	@Test
	fun `a REPLY written by a client from a cockpit REQUEST is read back`() {
		val event = event()
		val request = ItipMessages.build(ItipMethod.REQUEST, listOf(event), null, organizer, now)
		// A client answers by sending the VEVENT back with METHOD:REPLY and only its own attendee line.
		val calendar = parsed(request)
		calendar.set("METHOD", "REPLY")
		val vevent = calendar.vevents().single()
		vevent.properties.removeAll { it.name == "ATTENDEE" && it.value.endsWith("bob@example.test") }
		vevent.property("ATTENDEE")!!.setParam("PARTSTAT", "ACCEPTED")
		val answer = calendar.format()

		val replies = ItipMessages.replies(answer)

		replies.single().uid shouldBe event.id.toString()
		replies.single().email shouldBe "anna@example.test"
		replies.single().status shouldBe AttendeeStatus.ACCEPTED
		ItipMessages.eventId(replies.single().uid).shouldNotBeNull() shouldBe event.id
	}

	companion object {
		private const val UID = "01937a3c-4c1e-7a00-8000-0000000000aa"
	}
}
