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
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.mail.api.MailContentLoader
import ch.sonensei.cockpit.mail.api.MailReadService
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetHeaders
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Properties
import java.util.UUID

/**
 * [ItipReplyProcessor] against the real database (Postgres Testcontainer): the `calendar_itip_reply` stamps are looked up
 * by the derived query with null and non-null RECURRENCE-ID columns, compared case-insensitively by address, rolled
 * back with an optimistic-locking retry and removed with their event. Only the mail module is mocked
 * ([MailReadService], [MailContentLoader]). All people and addresses are fictitious.
 */
@SpringBootTest
class ItipReplyStampPersistenceTests(
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val sources: CalendarSourceRepository,
	@Autowired private val calendars: CalendarRepository,
	@Autowired private val events: CalendarEventRepository,
	@Autowired private val stamps: ItipReplyStampRepository,
	@Autowired private val indexer: CalendarEventIndexer,
	@Autowired private val changes: ChangeEventPublisher,
	@Autowired private val transactionManager: PlatformTransactionManager,
) : PostgresTestBase() {
	private val mail = mockk<MailReadService>()
	private val loader = mockk<MailContentLoader>()
	private val messageId = UuidV7.generate()
	private lateinit var calendar: Calendar
	private lateinit var processor: ItipReplyProcessor

	@BeforeEach
	fun setUp() {
		jdbc.execute("delete from calendar_source")
		val source = sources.save(CalendarSource(CalendarSourceType.LOCAL, "Cockpit"))
		calendar = calendars.save(Calendar(source.id, "Work"))
		processor = processor(events)
	}

	@AfterEach
	fun tearDown() {
		processor.destroy()
	}

	private fun processor(repository: CalendarEventRepository) =
		ItipReplyProcessor(mail, loader, repository, calendars, sources, stamps, indexer, changes, transactionManager)

	private fun single(): CalendarEvent =
		events.saveAndFlush(
			CalendarEvent(calendar.id, "Project kickoff").apply {
				setTimed(Instant.parse("2026-11-02T08:00:00Z"), Instant.parse("2026-11-02T09:00:00Z"), ZURICH)
				attendees = listOf(Attendee(ANNA, "Anna Example"))
			},
		)

	private fun status(id: UUID): AttendeeStatus =
		events
			.findById(id)
			.get()
			.attendees
			.single()
			.status

	/** The stamps stored, as (event id, recurrence_id_at, recurrence_id_date, email, sequence, stamp). */
	private fun storedStamps(): List<List<Any?>> =
		jdbc.query(
			"select * from calendar_itip_reply order by recurrence_id_at nulls first, recurrence_id_date nulls first",
		) { rs, _ ->
			listOf(
				rs.getObject("event_id", UUID::class.java),
				rs.getTimestamp("recurrence_id_at")?.toInstant(),
				rs.getObject("recurrence_id_date", LocalDate::class.java),
				rs.getString("email"),
				rs.getObject("sequence") as Int?,
				rs.getTimestamp("stamp")?.toInstant(),
			)
		}

	private fun receive(
		ics: String,
		sender: String = ANNA,
	) {
		val message = MailMessage(UuidV7.generate(), UuidV7.generate(), 1L, messageId).apply { from = listOf(MailAddress(sender)) }
		every { mail.inInbox(messageId) } returns message
		val mime =
			MimeMessage(Session.getInstance(Properties())).apply {
				setContent(MimeMultipart(MimeBodyPart(CALENDAR_HEADERS, ics.toByteArray())))
				saveChanges()
			}
		every { loader.withRemoteMessage<Any?>(message, any()) } answers { secondArg<(Part) -> Any?>().invoke(mime) }
	}

	private fun reply(
		uid: UUID,
		partStat: String,
		sequence: Int? = null,
		stamp: String? = null,
		recurrenceId: String? = null,
		attendee: String = ANNA,
	): String =
		listOfNotNull(
			"BEGIN:VCALENDAR",
			"VERSION:2.0",
			"PRODID:-//Example//Fictitious Mail//EN",
			"METHOD:REPLY",
			"BEGIN:VEVENT",
			"UID:$uid",
			recurrenceId?.let { "RECURRENCE-ID$it" },
			sequence?.let { "SEQUENCE:$it" },
			stamp?.let { "DTSTAMP:$it" },
			"ATTENDEE;PARTSTAT=$partStat:mailto:$attendee",
			"END:VEVENT",
			"END:VCALENDAR",
		).joinToString("\r\n", postfix = "\r\n")

	private fun answer(
		ics: String,
		sender: String = ANNA,
	): List<UUID> {
		receive(ics, sender)
		return processor.process(messageId)
	}

	@Test
	fun `the stamp of an address in other letter case is the same stamp, and an older REPLY from it is ignored`() {
		val event = single()

		answer(
			reply(event.id, "ACCEPTED", 2, "20261026T120000Z", attendee = "Anna@Example.TEST"),
			sender = "ANNA@example.test",
		) shouldContainExactly
			listOf(event.id)
		answer(reply(event.id, "DECLINED", 1, "20261027T120000Z")).shouldBeEmpty()

		status(event.id) shouldBe AttendeeStatus.ACCEPTED
		storedStamps() shouldContainExactly listOf(listOf(event.id, null, null, ANNA, 2, Instant.parse("2026-10-26T12:00:00Z")))
	}

	@Test
	fun `a REPLY without SEQUENCE counts as SEQUENCE 0, and one without DTSTAMP as older than one with it`() {
		val event = single()

		answer(reply(event.id, "TENTATIVE")) shouldContainExactly listOf(event.id)
		storedStamps().single().takeLast(2) shouldBe listOf(null, null)
		// Same SEQUENCE (0), and a DTSTAMP is newer than none.
		answer(reply(event.id, "ACCEPTED", 0, "20261026T120000Z")) shouldContainExactly listOf(event.id)
		// A missing SEQUENCE is 0: a newer DTSTAMP is taken, an older one is not.
		answer(reply(event.id, "DECLINED", stamp = "20261030T120000Z")) shouldContainExactly listOf(event.id)
		answer(reply(event.id, "ACCEPTED", stamp = "20261029T120000Z")).shouldBeEmpty()
		// Same SEQUENCE without DTSTAMP is older than one with a DTSTAMP.
		answer(reply(event.id, "ACCEPTED", 0)).shouldBeEmpty()
		storedStamps().single().takeLast(2) shouldBe listOf(null, Instant.parse("2026-10-30T12:00:00Z"))
		// A higher SEQUENCE wins over a missing one, whatever the DTSTAMP.
		answer(reply(event.id, "ACCEPTED", 1, "20261020T120000Z")) shouldContainExactly listOf(event.id)
		answer(reply(event.id, "DECLINED", stamp = "20261031T120000Z")).shouldBeEmpty()

		status(event.id) shouldBe AttendeeStatus.ACCEPTED
		storedStamps().single().takeLast(2) shouldBe listOf(1, Instant.parse("2026-10-20T12:00:00Z"))
	}

	@Test
	fun `a REPLY equal to the last one taken is applied again`() {
		val event = single()
		answer(reply(event.id, "ACCEPTED", 1, "20261026T120000Z"))

		answer(reply(event.id, "DECLINED", 1, "20261026T120000Z")) shouldContainExactly listOf(event.id)

		status(event.id) shouldBe AttendeeStatus.DECLINED
		storedStamps().size shouldBe 1
	}

	@Test
	fun `occurrence stamps of timed and all-day series are found again in the database and kept apart from the series stamp`() {
		val timed =
			events.saveAndFlush(
				CalendarEvent(calendar.id, "Weekly sync").apply {
					setTimed(Instant.parse("2026-11-02T08:00:00Z"), Instant.parse("2026-11-02T09:00:00Z"), ZURICH)
					rrule = "FREQ=WEEKLY"
					attendees = listOf(Attendee(ANNA))
				},
			)
		val at = Instant.parse("2026-11-09T08:00:00Z")
		val timedOverride =
			events.saveAndFlush(
				CalendarEvent(calendar.id, "Weekly sync (longer)").apply {
					setTimed(at, Instant.parse("2026-11-09T10:00:00Z"), ZURICH)
					attendees = listOf(Attendee(ANNA))
					overrideOf(timed, OccurrenceId.of(at))
				},
			)
		val allDay =
			events.saveAndFlush(
				CalendarEvent(calendar.id, "Offsite day").apply {
					setAllDay(LocalDate.parse("2026-11-02"))
					rrule = "FREQ=WEEKLY"
					attendees = listOf(Attendee(ANNA))
				},
			)
		val date = LocalDate.parse("2026-11-09")
		val allDayOverride =
			events.saveAndFlush(
				CalendarEvent(calendar.id, "Offsite day (remote)").apply {
					setAllDay(date)
					attendees = listOf(Attendee(ANNA))
					overrideOf(allDay, OccurrenceId.of(date))
				},
			)

		answer(reply(timed.id, "ACCEPTED", 3, "20261026T120000Z")) shouldContainExactly listOf(timed.id, timedOverride.id)
		answer(reply(timed.id, "DECLINED", 3, "20261026T130000Z", recurrenceId = ":20261109T080000Z")) shouldContainExactly
			listOf(timedOverride.id)
		answer(reply(allDay.id, "TENTATIVE", 1, "20261026T120000Z", recurrenceId = ";VALUE=DATE:20261109")) shouldContainExactly
			listOf(allDayOverride.id)
		// Older answers for the two occurrences: the stamps are found again, so they are ignored (no unique violation).
		answer(reply(timed.id, "TENTATIVE", 3, "20261026T110000Z", recurrenceId = ":20261109T080000Z")).shouldBeEmpty()
		answer(reply(allDay.id, "ACCEPTED", 0, "20261027T120000Z", recurrenceId = ";VALUE=DATE:20261109")).shouldBeEmpty()
		// A newer answer for the occurrence updates its stamp in place.
		answer(reply(timed.id, "ACCEPTED", 4, "20261026T100000Z", recurrenceId = ":20261109T080000Z")) shouldContainExactly
			listOf(timedOverride.id)

		status(timed.id) shouldBe AttendeeStatus.ACCEPTED
		status(timedOverride.id) shouldBe AttendeeStatus.ACCEPTED
		status(allDayOverride.id) shouldBe AttendeeStatus.TENTATIVE
		storedStamps() shouldContainExactlyInAnyOrder
			listOf(
				listOf(timed.id, null, null, ANNA, 3, Instant.parse("2026-10-26T12:00:00Z")),
				listOf(timed.id, at, null, ANNA, 4, Instant.parse("2026-10-26T10:00:00Z")),
				listOf(allDay.id, null, date, ANNA, 1, Instant.parse("2026-10-26T12:00:00Z")),
			)
	}

	@Test
	fun `no stamp is stored for an answer that is not taken`() {
		val event = single()
		val series =
			events.saveAndFlush(
				CalendarEvent(calendar.id, "Weekly sync").apply {
					setTimed(Instant.parse("2026-11-02T08:00:00Z"), Instant.parse("2026-11-02T09:00:00Z"), ZURICH)
					rrule = "FREQ=WEEKLY"
					attendees = listOf(Attendee(ANNA))
				},
			)

		// From someone the event does not invite.
		answer(reply(event.id, "ACCEPTED", 1, "20261026T120000Z", attendee = "mallory@example.test"), sender = "mallory@example.test")
			.shouldBeEmpty()
		// For an occurrence without override.
		answer(reply(series.id, "ACCEPTED", 1, "20261026T120000Z", recurrenceId = ":20261109T080000Z")).shouldBeEmpty()
		// For an event in the Trash.
		events.saveAndFlush(events.findById(event.id).get().apply { softDelete(Clock.systemUTC()) })
		answer(reply(event.id, "ACCEPTED", 1, "20261026T120000Z")).shouldBeEmpty()

		storedStamps().shouldBeEmpty()
	}

	@Test
	fun `a REPLY retried after an optimistic-locking conflict stores its stamp once and is still applied`() {
		val event = single()
		var calls = 0
		// The first save writes the stamp and the event, then the transaction fails as if a concurrent edit had won.
		val racing =
			object : CalendarEventRepository by events {
				override fun <S : CalendarEvent> saveAndFlush(entity: S): S {
					val saved = events.saveAndFlush(entity)
					if (++calls == 1) throw OptimisticLockingFailureException("changed concurrently")
					return saved
				}
			}
		processor.destroy()
		processor = processor(racing)

		answer(reply(event.id, "ACCEPTED", 2, "20261026T120000Z")) shouldContainExactly listOf(event.id)

		calls shouldBe 2
		status(event.id) shouldBe AttendeeStatus.ACCEPTED
		storedStamps() shouldContainExactly listOf(listOf(event.id, null, null, ANNA, 2, Instant.parse("2026-10-26T12:00:00Z")))
	}

	@Test
	fun `a REPLY whose retries all fail stores no stamp, so a later copy of it is still taken`() {
		val event = single()
		val failing =
			object : CalendarEventRepository by events {
				override fun <S : CalendarEvent> saveAndFlush(entity: S): S {
					events.saveAndFlush(entity)
					throw OptimisticLockingFailureException("changed concurrently")
				}
			}
		val failingProcessor = processor(failing)
		receive(reply(event.id, "ACCEPTED", 2, "20261026T120000Z"))
		try {
			runCatching { failingProcessor.process(messageId) }.isFailure shouldBe true
		} finally {
			failingProcessor.destroy()
		}
		storedStamps().shouldBeEmpty()

		answer(reply(event.id, "ACCEPTED", 2, "20261026T120000Z")) shouldContainExactly listOf(event.id)
		status(event.id) shouldBe AttendeeStatus.ACCEPTED
	}

	@Test
	fun `the stamps go with their event when it is deleted for good, and with its calendar source`() {
		val first = single()
		val second = single()
		answer(reply(first.id, "ACCEPTED", 1, "20261026T120000Z"))
		answer(reply(second.id, "ACCEPTED", 1, "20261026T120000Z"))
		storedStamps().size shouldBe 2

		jdbc.update("delete from calendar_event where id = ?", first.id)
		storedStamps().map { it.first() } shouldContainExactly listOf(second.id)

		jdbc.execute("delete from calendar_source")
		storedStamps().shouldBeEmpty()
	}

	@Test
	fun `the stamp survives an edit of the event's attendees that keeps the attendee`() {
		val event = single()
		answer(reply(event.id, "ACCEPTED", 2, "20261026T120000Z"))

		events.saveAndFlush(
			events.findById(event.id).get().apply {
				title = "Project kickoff (moved)"
				attendees = listOf(Attendee(ANNA, "Anna Example", AttendeeStatus.ACCEPTED), Attendee("ben@example.test"))
			},
		)
		answer(reply(event.id, "DECLINED", 1, "20261027T120000Z")).shouldBeEmpty()

		events
			.findById(event.id)
			.get()
			.attendees
			.first()
			.status shouldBe AttendeeStatus.ACCEPTED
		storedStamps().single()[4] shouldBe 2
	}

	private companion object {
		const val ANNA = "anna@example.test"
		val ZURICH: ZoneId = ZoneId.of("Europe/Zurich")
		val CALENDAR_HEADERS = InternetHeaders().apply { addHeader("Content-Type", "text/calendar; charset=UTF-8; method=REPLY") }
	}
}
