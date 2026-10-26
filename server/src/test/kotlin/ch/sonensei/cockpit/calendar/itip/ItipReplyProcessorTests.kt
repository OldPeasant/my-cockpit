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
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
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
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.ZoneId
import java.util.Optional
import java.util.Properties

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
	private val indexer = mockk<CalendarEventIndexer>(relaxed = true)
	private val changes = mockk<ChangeEventPublisher>(relaxed = true)
	private val processor =
		ItipReplyProcessor(mail, loader, events, calendars, sources, indexer, changes, mockk<PlatformTransactionManager>(relaxed = true))

	private val source = CalendarSource(CalendarSourceType.LOCAL, "Local")
	private val calendar = Calendar(source.id, "Work")
	private val eventId = UuidV7.generate()
	private val messageId = UuidV7.generate()

	/** A fresh copy of the stored event on every load, as JPA gives it in a new transaction. */
	private fun loaded(): CalendarEvent =
		CalendarEvent(calendar.id, "Project kickoff", eventId).apply {
			setTimed(Instant.parse("2026-11-02T08:00:00Z"), Instant.parse("2026-11-02T09:00:00Z"), ZoneId.of("Europe/Zurich"))
			attendees = listOf(Attendee(ANNA, "Anna Example"))
		}

	@BeforeEach
	fun setUp() {
		every { calendars.findById(calendar.id) } returns Optional.of(calendar)
		every { sources.findById(source.id) } returns Optional.of(source)
		every { events.findById(eventId) } answers { Optional.of(loaded()) }
		every { events.findByParentEventIdOrderByIdAsc(eventId) } returns emptyList()
	}

	private fun receive(vararg calendarParts: String) {
		val message = MailMessage(UuidV7.generate(), UuidV7.generate(), 1L, messageId).apply { from = listOf(MailAddress(ANNA)) }
		every { mail.inInbox(messageId) } returns message
		val mime =
			MimeMessage(Session.getInstance(Properties())).apply {
				setFrom(InternetAddress(ANNA))
				setContent(
					MimeMultipart(
						*calendarParts.map { MimeBodyPart(CALENDAR_HEADERS, it.toByteArray()) }.toTypedArray(),
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
	): String =
		listOf(
			"BEGIN:VCALENDAR",
			"VERSION:2.0",
			"PRODID:-//Example//Fictitious Mail//EN",
			"METHOD:REPLY",
			"BEGIN:VEVENT",
			"UID:$eventId",
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

	private companion object {
		const val ANNA = "anna@example.test"

		/** Parts as read from a server (raw bytes): plain JavaMail has no handler to write a text/calendar String. */
		val CALENDAR_HEADERS = InternetHeaders().apply { addHeader("Content-Type", "text/calendar; charset=UTF-8; method=REPLY") }
	}
}
