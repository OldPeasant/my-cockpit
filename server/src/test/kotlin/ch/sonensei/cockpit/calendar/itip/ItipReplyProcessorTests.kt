package ch.sonensei.cockpit.calendar.itip

import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.Calendar
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarEventIndexer
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.mail.api.MailContentLoader
import ch.sonensei.cockpit.mail.api.MailReadService
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailMessage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.InternetHeaders
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Optional
import java.util.Properties
import java.util.UUID

/**
 * [ItipReplyProcessor] with its collaborators mocked: the mail module is only reached through [MailReadService] and
 * [MailContentLoader], answers in one message are taken newest first, and an answer that loses an optimistic-locking
 * race is applied again on the reloaded event. All addresses are fictitious.
 */
class ItipReplyProcessorTests {
	private val mail = mockk<MailReadService>()
	private val loader = mockk<MailContentLoader>()
	private val events = mockk<CalendarEventRepository>()
	private val calendars = mockk<CalendarRepository>()
	private val sources = mockk<CalendarSourceRepository>()
	private val stamps = mockk<ItipReplyStampRepository>()
	private val storedStamps = mutableMapOf<List<Any?>, ItipReplyStamp>()
	private val indexer = mockk<CalendarEventIndexer>(relaxed = true)
	private val changes = mockk<ChangeEventPublisher>(relaxed = true)
	private val processor =
		ItipReplyProcessor(mail, loader, events, calendars, sources, stamps, indexer, changes, mockk<PlatformTransactionManager>(relaxed = true))

	private val source = CalendarSource(CalendarSourceType.LOCAL, "Local")
	private val calendar = Calendar(source.id, "Work")
	private val eventId = UuidV7.generate()
	private val otherEventId = UuidV7.generate()
	private val messageId = UuidV7.generate()

	/** A fresh copy of the stored event on every load, as JPA gives it in a new transaction. */
	private fun loaded(id: UUID = eventId): CalendarEvent =
		CalendarEvent(calendar.id, "Project kickoff", id).apply {
			setTimed(Instant.parse("2026-11-02T08:00:00Z"), Instant.parse("2026-11-02T09:00:00Z"), ZoneId.of("Europe/Zurich"))
			attendees = listOf(Attendee(ANNA, "Anna Example"))
		}

	@BeforeEach
	fun setUp() {
		every { calendars.findById(calendar.id) } returns Optional.of(calendar)
		every { sources.findById(source.id) } returns Optional.of(source)
		every { events.findById(eventId) } answers { Optional.of(loaded()) }
		every { events.findByParentEventIdOrderByIdAsc(eventId) } returns emptyList()
		every { events.findById(otherEventId) } answers { Optional.of(loaded(otherEventId)) }
		every { events.findByParentEventIdOrderByIdAsc(otherEventId) } returns emptyList()
		every { stamps.findByEventIdAndRecurrenceIdAtAndRecurrenceIdDateAndEmail(any(), any(), any(), any()) } answers {
			storedStamps[listOf(firstArg<UUID>(), secondArg<Instant?>(), thirdArg<LocalDate?>(), arg<String>(3))]
		}
		every { stamps.save(any<ItipReplyStamp>()) } answers {
			firstArg<ItipReplyStamp>().also {
				storedStamps[listOf(it.eventId, it.recurrenceIdAt, it.recurrenceIdDate, it.email)] = it
			}
		}
	}

	private fun receive(
		vararg calendarParts: String,
		sender: String? = ANNA,
		headers: InternetHeaders = CALENDAR_HEADERS,
	) {
		val message =
			MailMessage(UuidV7.generate(), UuidV7.generate(), 1L, messageId).apply { from = listOfNotNull(sender?.let(::MailAddress)) }
		every { mail.inInbox(messageId) } returns message
		val mime =
			MimeMessage(Session.getInstance(Properties())).apply {
				setFrom(InternetAddress(ANNA))
				setContent(
					MimeMultipart(
						*calendarParts.map { MimeBodyPart(headers, it.toByteArray()) }.toTypedArray(),
					),
				)
				saveChanges()
			}
		every { loader.withRemoteMessage<Any?>(message, any()) } answers { secondArg<(Part) -> Any?>().invoke(mime) }
	}

	private fun reply(
		partStat: String,
		stamp: String,
		sequence: Int = 0,
		uid: UUID = eventId,
		recurrenceId: String? = null,
	): String =
		listOfNotNull(
			"BEGIN:VCALENDAR",
			"VERSION:2.0",
			"PRODID:-//Example//Fictitious Mail//EN",
			"METHOD:REPLY",
			"BEGIN:VEVENT",
			"UID:$uid",
			recurrenceId?.let { "RECURRENCE-ID:$it" },
			"SEQUENCE:$sequence",
			"DTSTAMP:$stamp",
			"ATTENDEE;PARTSTAT=$partStat:mailto:$ANNA",
			"END:VEVENT",
			"END:VCALENDAR",
		).joinToString("\r\n", postfix = "\r\n")

	@Test
	fun `a message that is not in an Inbox is not read`() {
		every { mail.inInbox(messageId) } returns null

		processor.process(messageId).shouldBeEmpty()

		verify(exactly = 0) { loader.withRemoteMessage<Any?>(any(), any()) }
	}

	@Test
	fun `a REPLY that loses an optimistic-locking race is applied again on the reloaded event`() {
		receive(reply("ACCEPTED", "20261026T120000Z"))
		val saved = slot<CalendarEvent>()
		var calls = 0
		every { events.saveAndFlush(capture(saved)) } answers {
			if (++calls == 1) throw OptimisticLockingFailureException("changed concurrently")
			saved.captured
		}

		processor.process(messageId) shouldContainExactly listOf(eventId)

		verify(exactly = 2) { events.findById(eventId) }
		verify(exactly = 2) { events.saveAndFlush(any()) }
		saved.captured.attendees
			.single()
			.status shouldBe AttendeeStatus.ACCEPTED
		verify(exactly = 1) { indexer.index(any()) }
	}

	@Test
	fun `a REPLY that keeps losing the race is given up after three attempts`() {
		receive(reply("ACCEPTED", "20261026T120000Z"))
		every { events.saveAndFlush(any<CalendarEvent>()) } throws OptimisticLockingFailureException("changed concurrently")

		assertThrows<OptimisticLockingFailureException> { processor.process(messageId) }

		verify(exactly = 3) { events.saveAndFlush(any()) }
		verify(exactly = 0) { indexer.index(any()) }
	}

	@Test
	fun `of several answers of one attendee in a message, only the newest is applied`() {
		receive(
			reply("ACCEPTED", "20261027T120000Z"),
			reply("DECLINED", "20261026T120000Z"),
			reply("TENTATIVE", "20261028T120000Z", sequence = -1),
		)
		val saved = mutableListOf<CalendarEvent>()
		every { events.saveAndFlush(capture(saved)) } answers { firstArg() }

		processor.process(messageId) shouldContainExactly listOf(eventId)

		saved.map { it.attendees.single().status } shouldContainExactly listOf(AttendeeStatus.ACCEPTED)
	}

	// --- added by test-writer (re-run on the fixer's code) --------------------------------------------------------------

	@Test
	fun `answers for different events in one message are each applied, not merged as one attendee's answers`() {
		receive(reply("ACCEPTED", "20261026T120000Z"), reply("DECLINED", "20261026T110000Z", uid = otherEventId))
		val saved = mutableListOf<CalendarEvent>()
		every { events.saveAndFlush(capture(saved)) } answers { firstArg() }

		processor.process(messageId) shouldContainExactly listOf(eventId, otherEventId)

		saved.map { it.id to it.attendees.single().status } shouldContainExactly
			listOf(eventId to AttendeeStatus.ACCEPTED, otherEventId to AttendeeStatus.DECLINED)
	}

	@Test
	fun `a failure that is no optimistic-locking conflict is not retried`() {
		receive(reply("ACCEPTED", "20261026T120000Z"))
		every { events.saveAndFlush(any<CalendarEvent>()) } throws DataIntegrityViolationException("broken row")

		assertThrows<DataIntegrityViolationException> { processor.process(messageId) }

		verify(exactly = 1) { events.saveAndFlush(any()) }
	}

	@Test
	fun `a REPLY whose answer is already stored changes nothing`() {
		receive(reply("NEEDS-ACTION", "20261026T120000Z"))

		processor.process(messageId).shouldBeEmpty()

		verify(exactly = 0) { events.saveAndFlush(any()) }
		verify(exactly = 0) { changes.publish(any<Enum<*>>(), any(), any()) }
	}

	@Test
	fun `a message without a sender is not read`() {
		receive(reply("ACCEPTED", "20261026T120000Z"), sender = null)

		processor.process(messageId).shouldBeEmpty()

		verify(exactly = 0) { loader.withRemoteMessage<Any?>(any(), any()) }
	}

	@Test
	fun `a series REPLY updates the master and its live overrides, not an override in the Trash`() {
		val series = loaded().apply { rrule = "FREQ=WEEKLY" }
		val live = loaded(UuidV7.generate()).apply { overrideOf(series, OccurrenceId(Instant.parse("2026-11-09T08:00:00Z"), null)) }
		val trashed =
			loaded(UuidV7.generate()).apply {
				overrideOf(series, OccurrenceId(Instant.parse("2026-11-16T08:00:00Z"), null))
				softDelete(Clock.fixed(Instant.parse("2026-10-25T09:00:00Z"), ZoneOffset.UTC))
			}
		every { events.findById(eventId) } returns Optional.of(series)
		every { events.findByParentEventIdOrderByIdAsc(eventId) } returns listOf(live, trashed)
		every { events.saveAndFlush(any<CalendarEvent>()) } answers { firstArg() }
		receive(reply("ACCEPTED", "20261026T120000Z"))

		processor.process(messageId) shouldContainExactly listOf(eventId, live.id)

		trashed.attendees.single().status shouldBe AttendeeStatus.NEEDS_ACTION
		verify(exactly = 0) { events.saveAndFlush(trashed) }
	}

	// --- REPLYs across messages (itip-reply-ordering) -----------------------------------------------------------------

	@Test
	fun `a REPLY older than the one taken from an earlier message is ignored`() {
		val saved = mutableListOf<CalendarEvent>()
		every { events.saveAndFlush(capture(saved)) } answers { firstArg() }
		receive(reply("ACCEPTED", "20261026T120000Z", sequence = 1))
		processor.process(messageId) shouldContainExactly listOf(eventId)

		// Same SEQUENCE, older DTSTAMP.
		receive(reply("DECLINED", "20261026T110000Z", sequence = 1))
		processor.process(messageId).shouldBeEmpty()
		// Lower SEQUENCE, newer DTSTAMP.
		receive(reply("TENTATIVE", "20261027T120000Z", sequence = 0))
		processor.process(messageId).shouldBeEmpty()

		saved.map { it.attendees.single().status } shouldContainExactly listOf(AttendeeStatus.ACCEPTED)
		storedStamps.values.single().let {
			it.sequence shouldBe 1
			it.stamp shouldBe Instant.parse("2026-10-26T12:00:00Z")
		}
	}

	@Test
	fun `a newer REPLY from a later message is applied and becomes the last one taken`() {
		val saved = mutableListOf<CalendarEvent>()
		every { events.saveAndFlush(capture(saved)) } answers { firstArg() }
		receive(reply("ACCEPTED", "20261026T120000Z", sequence = 1))
		processor.process(messageId)

		receive(reply("DECLINED", "20261026T130000Z", sequence = 1))
		processor.process(messageId) shouldContainExactly listOf(eventId)
		receive(reply("TENTATIVE", "20261026T080000Z", sequence = 2))
		processor.process(messageId) shouldContainExactly listOf(eventId)

		saved.map { it.attendees.single().status } shouldContainExactly
			listOf(AttendeeStatus.ACCEPTED, AttendeeStatus.DECLINED, AttendeeStatus.TENTATIVE)
		storedStamps.values.single().sequence shouldBe 2
	}

	@Test
	fun `a REPLY that changes no status still counts as the last one taken`() {
		every { events.saveAndFlush(any<CalendarEvent>()) } answers { firstArg() }
		receive(reply("NEEDS-ACTION", "20261026T120000Z", sequence = 3))
		processor.process(messageId).shouldBeEmpty()

		receive(reply("ACCEPTED", "20261026T110000Z", sequence = 3))
		processor.process(messageId).shouldBeEmpty()

		verify(exactly = 0) { events.saveAndFlush(any()) }
	}

	@Test
	fun `REPLYs are ordered per occurrence, per event and per attendee`() {
		val series = loaded().apply { rrule = "FREQ=WEEKLY" }
		val occurrence = OccurrenceId(Instant.parse("2026-11-09T08:00:00Z"), null)
		val override = loaded(UuidV7.generate()).apply { overrideOf(series, occurrence) }
		every { events.findById(eventId) } returns Optional.of(series)
		every { events.findByParentEventIdOrderByIdAsc(eventId) } returns listOf(override)
		every { events.saveAndFlush(any<CalendarEvent>()) } answers { firstArg() }
		receive(reply("ACCEPTED", "20261026T120000Z", sequence = 5))
		processor.process(messageId) shouldContainExactly listOf(eventId, override.id)

		// An older answer for one occurrence has its own order, so does one for another event.
		receive(reply("DECLINED", "20261026T110000Z", sequence = 1, recurrenceId = "20261109T080000Z"))
		processor.process(messageId) shouldContainExactly listOf(override.id)
		receive(reply("DECLINED", "20261026T110000Z", sequence = 1, uid = otherEventId))
		processor.process(messageId) shouldContainExactly listOf(otherEventId)

		storedStamps.keys.map { it.take(3) }.toSet() shouldBe
			setOf(listOf(eventId, null, null), listOf(eventId, occurrence.at, null), listOf(otherEventId, null, null))
	}

	@Test
	fun `a calendar part with an unknown charset is read as UTF-8, not dropped`() {
		receive(
			reply("TENTATIVE", "20261026T120000Z"),
			headers = InternetHeaders().apply { addHeader("Content-Type", "text/calendar; charset=x-fictitious-charset; method=REPLY") },
		)
		val saved = slot<CalendarEvent>()
		every { events.saveAndFlush(capture(saved)) } answers { saved.captured }

		processor.process(messageId) shouldContainExactly listOf(eventId)

		saved.captured.attendees
			.single()
			.status shouldBe AttendeeStatus.TENTATIVE
	}

	@Test
	fun `only newly created mail messages are queued for checking`() {
		every { mail.inInbox(any()) } returns null
		val at = Instant.parse("2026-10-26T12:00:00Z")
		try {
			processor.onChange(ChangeEvent(ItemType.EVENT, UuidV7.generate(), ChangeOp.CREATED, at))
			processor.onChange(ChangeEvent(ItemType.EMAIL, UuidV7.generate(), ChangeOp.UPDATED, at))
			processor.onChange(ChangeEvent(ItemType.EMAIL, UuidV7.generate(), ChangeOp.DELETED, at))
			processor.onChange(ChangeEvent(ItemType.EMAIL, messageId, ChangeOp.CREATED, at))

			// One background thread works in order: had an earlier change been queued, it would have been read first.
			verify(timeout = 5_000) { mail.inInbox(messageId) }
			verify(exactly = 1) { mail.inInbox(any()) }
		} finally {
			processor.destroy()
		}
	}

	private companion object {
		const val ANNA = "anna@example.test"

		/** Parts as read from a server (raw bytes): plain JavaMail has no handler to write a text/calendar String. */
		val CALENDAR_HEADERS = InternetHeaders().apply { addHeader("Content-Type", "text/calendar; charset=UTF-8; method=REPLY") }
	}
}
