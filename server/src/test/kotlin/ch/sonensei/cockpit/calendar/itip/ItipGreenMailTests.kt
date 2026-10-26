package ch.sonensei.cockpit.calendar.itip

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.id
import ch.sonensei.cockpit.calendar.infra.caldav.CalDavEventMapping
import ch.sonensei.cockpit.calendar.infra.caldav.IcsComponent
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.Calendar
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.web.ApiException
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.imap.MailAccountService
import ch.sonensei.cockpit.mail.imap.MailCredentials
import ch.sonensei.cockpit.mail.imap.MailFolderService
import ch.sonensei.cockpit.mail.imap.MailServer
import ch.sonensei.cockpit.mail.imap.MailSessions
import ch.sonensei.cockpit.mail.imap.NewMailAccount
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailIdentity
import ch.sonensei.cockpit.mail.model.MailIdentityRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.mail.sync.MailHeaderSyncService
import ch.sonensei.cockpit.mail.sync.MailSyncRepository
import ch.sonensei.cockpit.support.ExternalTest
import ch.sonensei.cockpit.support.PostgresTestBase
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetupTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneId
import java.util.Properties
import java.util.UUID

/**
 * Invitations for local events (iTIP over iMIP) against GreenMail (SMTP + IMAP): [ItipService] sends REQUEST and CANCEL
 * mails that arrive at the attendees with a `text/calendar` part, and [ItipReplyProcessor] applies the REPLYs that
 * arrive in the organizer's Inbox, triggered by the real header sync.
 *
 * GreenMail speaks no XOAUTH2, so [MailSessions.credentials] is stubbed to the GreenMail password; everything else
 * (SMTP, IMAP, header sync, change events, the processor's background executor) is the real code. All people and
 * addresses are fictitious.
 */
@ExternalTest
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@DirtiesContext
class ItipGreenMailTests(
	@Autowired mockMvc: MockMvc,
	@Autowired jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountService,
	@Autowired private val folderService: MailFolderService,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailSyncRepository,
	@Autowired private val sync: MailHeaderSyncService,
	@Autowired private val identities: MailIdentityRepository,
	@Autowired private val sources: CalendarSourceRepository,
	@Autowired private val calendars: CalendarRepository,
	@Autowired private val events: CalendarEventRepository,
	@Autowired private val itip: ItipService,
	@Autowired private val processor: ItipReplyProcessor,
) : PostgresTestBase() {
	@MockkSpyBean
	private lateinit var sessions: MailSessions

	private val api = CalendarApi(mockMvc, jsonMapper)
	private lateinit var account: MailAccount
	private lateinit var calendarId: String

	@BeforeEach
	fun setUp() {
		every { sessions.credentials(any()) } returns MailCredentials(JANE_USER, PASSWORD, false)
		CalendarApi.resetCalendarTables(jdbc)
		jdbc.update("delete from mail_account")
		greenMail.setUser(JANE, JANE_USER, PASSWORD)
		greenMail.setUser(ANNA, ANNA_USER, PASSWORD)
		greenMail.setUser(BOB, BOB_USER, PASSWORD)
		greenMail.setUser(CAROL, CAROL_USER, PASSWORD)
		account =
			accounts.create(
				NewMailAccount(
					type = MailAccountType.IMAP_SMTP,
					displayName = "Jane Doe",
					emailAddress = JANE,
					imap = MailServer(HOST, greenMail.imap.port, MailSecurity.NONE),
					smtp = MailServer(HOST, greenMail.smtp.port, MailSecurity.NONE),
					username = JANE_USER,
					password = PASSWORD,
					oauthConnectionId = null,
					signatureMd = null,
					notifyNewMail = null,
					syncWindowMonths = null,
				),
			)
		remote(JANE_USER) { store -> store.getFolder("Archive").create(Folder.HOLDS_MESSAGES) }
		folderService.sync(account.id)
		syncAll()
		calendarId = api.createCalendar().id()
	}

	// --- sending REQUEST -----------------------------------------------------------------------------------------------

	@Test
	fun `a REQUEST arrives at every attendee as a text calendar part with method REQUEST`() {
		val event = createEvent(attendees = listOf(ANNA, BOB, JANE))

		val sent = itip.send(UUID.fromString(event), ItipMethod.REQUEST, account.id, null)

		sent shouldBe SentInvitation(ItipMethod.REQUEST, listOf(ANNA, BOB))
		val received = onServer("INBOX", ANNA_USER).single()
		onServer("INBOX", BOB_USER) shouldHaveSize 1
		// The organizer is an attendee too, but gets no invitation from herself.
		onServer("INBOX", JANE_USER).shouldBeEmpty()
		received.subject shouldBe "Invitation: Project kickoff"
		(received.from.single() as InternetAddress).address shouldBe JANE
		received.getRecipients(Message.RecipientType.TO).map { (it as InternetAddress).address } shouldContainExactly listOf(ANNA, BOB)
		received.isMimeType("multipart/alternative") shouldBe true
		val (text, calendarPart) = leaves(received)
		text.isMimeType("text/plain") shouldBe true
		(text.content as String) shouldContain "You are invited to: Project kickoff"
		(text.content as String) shouldContain "Where: Room 4"
		val type = ContentType(calendarPart.contentType)
		type.baseType shouldBe "text/calendar"
		type.getParameter("method") shouldBe "REQUEST"
		type.getParameter("charset").uppercase() shouldBe "UTF-8"
		val ics = ics(calendarPart)
		ics.text("METHOD") shouldBe "REQUEST"
		val vevent = ics.children("VEVENT").single()
		vevent.text("UID") shouldBe event
		vevent.text("SUMMARY") shouldBe "Project kickoff"
		vevent.text("SEQUENCE") shouldBe "0"
		vevent.property("ORGANIZER")!!.value shouldBe "mailto:$JANE"
		vevent.all("ATTENDEE").map { it.value } shouldContainExactly listOf("mailto:$ANNA", "mailto:$BOB", "mailto:$JANE")
		vevent.all("ATTENDEE").first().param("RSVP") shouldBe "TRUE"
		vevent.children("VALARM").shouldBeEmpty()
	}

	@Test
	fun `a REQUEST to chosen attendees only goes to them, and the SEQUENCE follows the event's version`() {
		val event = createEvent(attendees = listOf(ANNA, BOB))
		api.patchEventOk(event, """{"title":"Project kickoff (moved)"}""")

		val sent = itip.send(UUID.fromString(event), ItipMethod.REQUEST, account.id, listOf(BOB, "Bob@Example.test"))

		sent.recipients shouldContainExactly listOf(BOB)
		onServer("INBOX", ANNA_USER).shouldBeEmpty()
		val received = onServer("INBOX", BOB_USER).single()
		received.subject shouldBe "Invitation: Project kickoff (moved)"
		ics(calendarPart(received)).children("VEVENT").single().text("SEQUENCE") shouldBe "1"
	}

	@Test
	fun `the account's default identity is the sender and the organizer`() {
		identities.saveAndFlush(MailIdentity(account.id, "Jane Support", SUPPORT).apply { isDefault = true })
		val event = createEvent(attendees = listOf(ANNA))

		itip.send(UUID.fromString(event), ItipMethod.REQUEST, account.id, null)

		val received = onServer("INBOX", ANNA_USER).single()
		(received.from.single() as InternetAddress).address shouldBe SUPPORT
		val organizer =
			ics(calendarPart(received))
				.children("VEVENT")
				.single()
				.property("ORGANIZER")!!
		organizer.value shouldBe "mailto:$SUPPORT"
		organizer.param("CN") shouldBe "Jane Support"
	}

	@Test
	fun `a REQUEST for a series carries its overrides with the series UID`() {
		val series = createEvent(attendees = listOf(ANNA), rrule = "FREQ=WEEKLY")
		val override = api.patchEventOk(series, """{"title":"Kickoff (room B)"}""", "?scope=THIS&occurrence=2026-11-09T08:00:00Z").id()

		itip.send(UUID.fromString(series), ItipMethod.REQUEST, account.id, null)

		val vevents = ics(calendarPart(onServer("INBOX", ANNA_USER).single())).children("VEVENT")
		vevents.map { it.text("UID") } shouldContainExactly listOf(series, series)
		vevents[0].text("RRULE") shouldBe "FREQ=WEEKLY"
		vevents[1].text("SUMMARY") shouldBe "Kickoff (room B)"
		vevents[1].property("RECURRENCE-ID")!!.value shouldBe "20261109T090000"

		// An override on its own goes out as just that occurrence.
		itip.send(UUID.fromString(override), ItipMethod.REQUEST, account.id, null)
		val single = ics(calendarPart(onServer("INBOX", ANNA_USER).last())).children("VEVENT").single()
		single.text("UID") shouldBe series
		single.text("SUMMARY") shouldBe "Kickoff (room B)"
	}

	// --- sending CANCEL ------------------------------------------------------------------------------------------------

	@Test
	fun `a CANCEL arrives as a text calendar part with method CANCEL and the event cancelled`() {
		val event = createEvent(attendees = listOf(ANNA, BOB))

		val sent = itip.send(UUID.fromString(event), ItipMethod.CANCEL, account.id, null)

		sent shouldBe SentInvitation(ItipMethod.CANCEL, listOf(ANNA, BOB))
		val received = onServer("INBOX", ANNA_USER).single()
		received.subject shouldBe "Cancelled: Project kickoff"
		(leaves(received).first().content as String) shouldContain "This event was cancelled: Project kickoff"
		val part = calendarPart(received)
		ContentType(part.contentType).getParameter("method") shouldBe "CANCEL"
		val ics = ics(part)
		ics.text("METHOD") shouldBe "CANCEL"
		val vevent = ics.children("VEVENT").single()
		vevent.text("UID") shouldBe event
		vevent.text("STATUS") shouldBe "CANCELLED"
		vevent.all("ATTENDEE").map { it.value } shouldContainExactly listOf("mailto:$ANNA", "mailto:$BOB")
		onServer("INBOX", BOB_USER) shouldHaveSize 1
	}

	@Test
	fun `a CANCEL to someone no longer invited lists only them and does not cancel the event`() {
		val event = createEvent(attendees = listOf(ANNA))

		itip.send(UUID.fromString(event), ItipMethod.CANCEL, account.id, listOf(CAROL))

		onServer("INBOX", ANNA_USER).shouldBeEmpty()
		val vevent = ics(calendarPart(onServer("INBOX", CAROL_USER).single())).children("VEVENT").single()
		vevent.all("ATTENDEE").map { it.value } shouldContainExactly listOf("mailto:$CAROL")
		vevent.all("STATUS").shouldBeEmpty()
	}

	@Test
	fun `a CANCEL for a live event to attendees and people no longer invited at once is a bad request (400)`() {
		val event = UUID.fromString(createEvent(attendees = listOf(ANNA, BOB)))

		val error = assertThrows<InvalidRequestException> { itip.send(event, ItipMethod.CANCEL, account.id, listOf(ANNA, CAROL)) }

		error.status shouldBe HttpStatus.BAD_REQUEST
		error.fieldErrors.map { it.field } shouldContainExactly listOf("recipients")
		nothingSent()
	}

	@Test
	fun `a CANCEL for an event in the Trash may go to attendees and people no longer invited, cancelled for all`() {
		val event = createEvent(attendees = listOf(ANNA))
		api.deleteEvent(event).andExpect { status { is2xxSuccessful() } }

		itip.send(UUID.fromString(event), ItipMethod.CANCEL, account.id, listOf(ANNA, CAROL))

		listOf(ANNA_USER, CAROL_USER).forEach { user ->
			val vevent = ics(calendarPart(onServer("INBOX", user).single())).children("VEVENT").single()
			vevent.text("STATUS") shouldBe "CANCELLED"
			vevent.all("ATTENDEE").map { it.value } shouldContainExactly listOf("mailto:$ANNA")
		}
	}

	@Test
	fun `a CANCEL is sent for an event in the Trash, a REQUEST is not`() {
		val event = createEvent(attendees = listOf(ANNA))
		api.deleteEvent(event).andExpect { status { is2xxSuccessful() } }

		assertThrows<NotFoundException> { itip.send(UUID.fromString(event), ItipMethod.REQUEST, account.id, null) }
		itip.send(UUID.fromString(event), ItipMethod.CANCEL, account.id, null)

		val received = onServer("INBOX", ANNA_USER).single()
		ics(calendarPart(received)).text("METHOD") shouldBe "CANCEL"
	}

	// --- send errors ---------------------------------------------------------------------------------------------------

	@Test
	fun `an event of a synced calendar is not sent (409)`() {
		val synced = syncedEvent(attendees = listOf(ANNA))

		val error = assertThrows<ConflictException> { itip.send(synced.id, ItipMethod.REQUEST, account.id, null) }

		error.status shouldBe HttpStatus.CONFLICT
		assertThrows<ConflictException> { itip.send(synced.id, ItipMethod.CANCEL, account.id, null) }
		nothingSent()
	}

	@Test
	fun `an event without attendees has nobody to send to (409)`() {
		val alone = createEvent(attendees = emptyList())
		val onlyOrganizer = createEvent(attendees = listOf(JANE))

		assertThrows<ConflictException> { itip.send(UUID.fromString(alone), ItipMethod.REQUEST, account.id, null) }
		assertThrows<ConflictException> { itip.send(UUID.fromString(onlyOrganizer), ItipMethod.REQUEST, account.id, null) }
		assertThrows<ConflictException> { itip.send(UUID.fromString(alone), ItipMethod.REQUEST, account.id, emptyList()) }
		nothingSent()
	}

	@Test
	fun `an unknown mail account is a bad request (400)`() {
		val event = createEvent(attendees = listOf(ANNA))

		val error = assertThrows<InvalidRequestException> { itip.send(UUID.fromString(event), ItipMethod.REQUEST, UUID.randomUUID(), null) }

		error.status shouldBe HttpStatus.BAD_REQUEST
		error.fieldErrors.map { it.field } shouldContainExactly listOf("accountId")
		nothingSent()
	}

	@Test
	fun `a REQUEST to someone who is not an attendee, or to an invalid address, is a bad request (400)`() {
		val event = UUID.fromString(createEvent(attendees = listOf(ANNA)))

		val notAttendee = assertThrows<InvalidRequestException> { itip.send(event, ItipMethod.REQUEST, account.id, listOf(ANNA, CAROL)) }
		notAttendee.fieldErrors.map { it.field } shouldContainExactly listOf("recipients")
		listOf("not-an-address", "Anna <$ANNA>", "").forEach { invalid ->
			val error = assertThrows<InvalidRequestException> { itip.send(event, ItipMethod.CANCEL, account.id, listOf(invalid)) }
			error.fieldErrors.map { it.field } shouldContainExactly listOf("recipients")
		}
		nothingSent()
	}

	@Test
	fun `an unknown event is not found (404)`() {
		val error = assertThrows<ApiException> { itip.send(UUID.randomUUID(), ItipMethod.REQUEST, account.id, null) }

		error.status shouldBe HttpStatus.NOT_FOUND
		nothingSent()
	}

	// --- incoming REPLY ------------------------------------------------------------------------------------------------

	@Test
	fun `a REPLY mailed by the attendee into the Inbox updates the attendee status after header sync`() {
		val event = createEvent(attendees = listOf(ANNA, BOB))
		val before = stored(event).version

		smtpSend(replyMail(from = ANNA, uid = event, attendee = ANNA, partStat = "ACCEPTED"))
		syncAll()

		awaitStatus(event, ANNA, AttendeeStatus.ACCEPTED)
		val after = stored(event)
		after.attendees.single { it.email == BOB }.status shouldBe AttendeeStatus.NEEDS_ACTION
		after.version shouldBe before + 1
		api.event(event)["attendees"].first()["status"].asString() shouldBe "ACCEPTED"
		// The same answer again changes nothing.
		processor.process(cachedInbox().single().id).shouldBeEmpty()
	}

	@Test
	fun `process applies a REPLY directly and returns the changed event, matching the address case-insensitively`() {
		val event = createEvent(attendees = listOf(ANNA))
		appendTo("INBOX", replyMail(from = "Anna@Example.test", uid = event, attendee = "anna@EXAMPLE.test", partStat = "TENTATIVE"))
		fullSyncInbox()

		processor.process(cachedInbox().single().id) shouldContainExactly listOf(UUID.fromString(event))

		stored(event).attendees.single().status shouldBe AttendeeStatus.TENTATIVE
	}

	@Test
	fun `a REPLY as a single text calendar part or an ics attachment is read too`() {
		val first = createEvent(attendees = listOf(ANNA))
		val second = createEvent(attendees = listOf(ANNA))
		val calendarOnly =
			message(from = ANNA, subject = "Declined").apply {
				setContent(replyIcs(first, ANNA, "DECLINED"), "text/calendar; charset=UTF-8; method=REPLY")
				saveChanges()
			}
		val attachment =
			message(from = ANNA, subject = "Accepted").apply {
				setContent(
					MimeMultipart(
						MimeBodyPart().apply { setText("See the attachment.", "UTF-8") },
						MimeBodyPart().apply {
							setContent(replyIcs(second, ANNA, "ACCEPTED"), "application/ics")
							disposition = Part.ATTACHMENT
							fileName = "reply.ics"
						},
					),
				)
				saveChanges()
			}

		smtpSend(calendarOnly)
		smtpSend(attachment)
		syncAll()

		awaitStatus(first, ANNA, AttendeeStatus.DECLINED)
		awaitStatus(second, ANNA, AttendeeStatus.ACCEPTED)
	}

	@Test
	fun `a REPLY whose sender is not the attendee is ignored`() {
		val event = createEvent(attendees = listOf(ANNA, BOB))
		val control = createEvent(attendees = listOf(CAROL))

		appendTo("INBOX", replyMail(from = MALLORY, uid = event, attendee = ANNA, partStat = "DECLINED"))
		// Bob may not answer for Anna either.
		appendTo("INBOX", replyMail(from = BOB, uid = event, attendee = ANNA, partStat = "DECLINED"))
		syncAll()
		awaitControl(control)

		stored(event).attendees.map { it.status } shouldContainExactly listOf(AttendeeStatus.NEEDS_ACTION, AttendeeStatus.NEEDS_ACTION)
		cachedInbox().forEach { processor.process(it.id).shouldBeEmpty() }
	}

	@Test
	fun `a REPLY for an event of a synced calendar is ignored`() {
		val synced = syncedEvent(attendees = listOf(ANNA))
		val control = createEvent(attendees = listOf(CAROL))

		appendTo("INBOX", replyMail(from = ANNA, uid = synced.id.toString(), attendee = ANNA, partStat = "ACCEPTED"))
		syncAll()
		awaitControl(control)

		events
			.findById(synced.id)
			.orElseThrow()
			.attendees
			.single()
			.status shouldBe AttendeeStatus.NEEDS_ACTION
		processor.process(cachedInbox().first().id).shouldBeEmpty()
	}

	@Test
	fun `a REPLY from someone the event does not invite, for an unknown or trashed event, is ignored`() {
		val event = createEvent(attendees = listOf(ANNA))
		val trashed = createEvent(attendees = listOf(ANNA))
		api.deleteEvent(trashed).andExpect { status { is2xxSuccessful() } }
		val control = createEvent(attendees = listOf(CAROL))

		appendTo("INBOX", replyMail(from = BOB, uid = event, attendee = BOB, partStat = "ACCEPTED"))
		appendTo("INBOX", replyMail(from = ANNA, uid = UUID.randomUUID().toString(), attendee = ANNA, partStat = "ACCEPTED"))
		appendTo("INBOX", replyMail(from = ANNA, uid = "foreign-uid@example.test", attendee = ANNA, partStat = "ACCEPTED"))
		appendTo("INBOX", replyMail(from = ANNA, uid = trashed, attendee = ANNA, partStat = "ACCEPTED"))
		syncAll()
		awaitControl(control)

		stored(event).attendees.map { it.email to it.status } shouldContainExactly listOf(ANNA to AttendeeStatus.NEEDS_ACTION)
		stored(trashed).attendees.single().status shouldBe AttendeeStatus.NEEDS_ACTION
	}

	@Test
	fun `a REPLY outside the Inbox is not applied`() {
		val event = createEvent(attendees = listOf(ANNA))
		val control = createEvent(attendees = listOf(CAROL))

		appendTo("Archive", replyMail(from = ANNA, uid = event, attendee = ANNA, partStat = "ACCEPTED"))
		syncAll()
		awaitControl(control)

		stored(event).attendees.single().status shouldBe AttendeeStatus.NEEDS_ACTION
		val archived = messages.findByFolderIdAndUidIsNotNull(checkNotNull(folders.findByAccountIdAndPath(account.id, "Archive")).id).single()
		processor.process(archived.id).shouldBeEmpty()
	}

	@Test
	fun `mail that is no REPLY is ignored, a broken calendar part does not stop the other parts`() {
		val event = createEvent(attendees = listOf(ANNA))
		val control = createEvent(attendees = listOf(CAROL))
		val broken =
			message(from = ANNA, subject = "Mixed").apply {
				setContent(
					MimeMultipart(
						MimeBodyPart().apply { setContent("this is not iCalendar", "text/calendar; charset=UTF-8") },
						MimeBodyPart().apply {
							setContent(replyIcs(event, ANNA, "ACCEPTED").replace("METHOD:REPLY", "METHOD:REQUEST"), "text/calendar; method=REQUEST")
						},
					),
				)
				saveChanges()
			}
		val plain = message(from = ANNA, subject = "See you there").apply { setText("Plain text, no invitation answer.", "UTF-8") }

		appendTo("INBOX", broken)
		appendTo("INBOX", plain)
		syncAll()
		awaitControl(control)

		stored(event).attendees.single().status shouldBe AttendeeStatus.NEEDS_ACTION
	}

	@Test
	fun `a series REPLY updates the master and its overrides, an occurrence REPLY only its override`() {
		val series = createEvent(attendees = listOf(ANNA, BOB), rrule = "FREQ=WEEKLY")
		val override = api.patchEventOk(series, """{"title":"Kickoff (room B)"}""", "?scope=THIS&occurrence=2026-11-09T08:00:00Z").id()
		stored(override).attendees.map { it.email } shouldContainExactly listOf(ANNA, BOB)

		appendTo("INBOX", replyMail(from = ANNA, uid = series, attendee = ANNA, partStat = "ACCEPTED"))
		syncAll()
		awaitStatus(series, ANNA, AttendeeStatus.ACCEPTED)
		awaitStatus(override, ANNA, AttendeeStatus.ACCEPTED)

		appendTo("INBOX", replyMail(from = ANNA, uid = series, attendee = ANNA, partStat = "DECLINED", recurrenceId = "20261109T080000Z"))
		// An occurrence without an override: no override is created for an answer.
		appendTo("INBOX", replyMail(from = ANNA, uid = series, attendee = ANNA, partStat = "TENTATIVE", recurrenceId = "20261116T080000Z"))
		syncAll()
		awaitStatus(override, ANNA, AttendeeStatus.DECLINED)
		awaitControl(createEvent(attendees = listOf(CAROL)))

		stored(series).attendees.map { it.status } shouldContainExactly listOf(AttendeeStatus.ACCEPTED, AttendeeStatus.NEEDS_ACTION)
		stored(override).attendees.map { it.status } shouldContainExactly listOf(AttendeeStatus.DECLINED, AttendeeStatus.NEEDS_ACTION)
		events.findByParentEventIdOrderByIdAsc(UUID.fromString(series)).map { it.id.toString() } shouldContainExactlyInAnyOrder listOf(override)
	}

	@Test
	fun `a REPLY naming an override's own id as UID is ignored`() {
		val series = createEvent(attendees = listOf(ANNA), rrule = "FREQ=WEEKLY")
		val override = api.patchEventOk(series, """{"title":"Kickoff (room B)"}""", "?scope=THIS&occurrence=2026-11-09T08:00:00Z").id()
		val control = createEvent(attendees = listOf(CAROL))

		appendTo("INBOX", replyMail(from = ANNA, uid = override, attendee = ANNA, partStat = "ACCEPTED"))
		syncAll()
		awaitControl(control)

		stored(override).attendees.single().status shouldBe AttendeeStatus.NEEDS_ACTION
	}

	@Test
	fun `a message that is gone from the cache gives no answers`() {
		processor.process(UUID.randomUUID()).shouldBeEmpty()
	}

	// --- added by test-writer: edge, security and round-trip cases ---------------------------------------------------

	@Test
	fun `a CANCEL for one override goes out as just that occurrence, cancelled, with the series UID`() {
		val series = createEvent(attendees = listOf(ANNA), rrule = "FREQ=WEEKLY")
		val override = api.patchEventOk(series, """{"title":"Kickoff (room B)"}""", "?scope=THIS&occurrence=2026-11-09T08:00:00Z").id()

		val sent = itip.send(UUID.fromString(override), ItipMethod.CANCEL, account.id, null)

		sent shouldBe SentInvitation(ItipMethod.CANCEL, listOf(ANNA))
		val received = onServer("INBOX", ANNA_USER).single()
		received.subject shouldBe "Cancelled: Kickoff (room B)"
		val vevent = ics(calendarPart(received)).children("VEVENT").single()
		vevent.text("UID") shouldBe series
		vevent.property("RECURRENCE-ID")!!.value shouldBe "20261109T090000"
		vevent.text("STATUS") shouldBe "CANCELLED"
		vevent.text("RRULE") shouldBe null
	}

	@Test
	fun `the text part of an all-day invitation names its dates, the conference link and the description`() {
		val event =
			api
				.createEvent(
					"""
					{"calendarId":"$calendarId","title":"Team offsite","allDay":true,"startDate":"2026-11-02","endDate":"2026-11-05",
					 "conferenceUrl":"https://meet.example.test/offsite","description":"Bring warm clothes.",
					 "attendees":[{"email":"$ANNA"}]}
					""".trimIndent(),
				).id()

		itip.send(UUID.fromString(event), ItipMethod.REQUEST, account.id, null)

		val received = onServer("INBOX", ANNA_USER).single()
		val text = leaves(received).first().content as String
		text shouldContain "When: Nov 2, 2026 – Nov 4, 2026"
		text shouldContain "Join: https://meet.example.test/offsite"
		text shouldContain "Bring warm clothes."
		val vevent = ics(calendarPart(received)).children("VEVENT").single()
		vevent.property("DTSTART")!!.value shouldBe "20261102"
	}

	@Test
	fun `a title with a line break cannot inject mail headers or extra recipients`() {
		greenMail.setUser(MALLORY, MALLORY_USER, PASSWORD)
		val event = createEvent(attendees = listOf(ANNA))
		val stored = stored(event)
		stored.title = "Kickoff\r\nBcc: $MALLORY\r\nX-Injected: yes"
		events.saveAndFlush(stored)

		itip.send(UUID.fromString(event), ItipMethod.REQUEST, account.id, null)

		onServer("INBOX", MALLORY_USER).shouldBeEmpty()
		greenMail.receivedMessages.flatMap { message -> message.allRecipients.map { (it as InternetAddress).address } } shouldContainExactly
			listOf(ANNA)
		val received = onServer("INBOX", ANNA_USER).single()
		received.getHeader("X-Injected") shouldBe null
		received.getHeader("Bcc") shouldBe null
	}

	@Test
	fun `an occurrence REPLY with the RECURRENCE-ID as the cockpit sent it (TZID) updates its override`() {
		val series = createEvent(attendees = listOf(ANNA), rrule = "FREQ=WEEKLY")
		val override = api.patchEventOk(series, """{"title":"Kickoff (room B)"}""", "?scope=THIS&occurrence=2026-11-09T08:00:00Z").id()
		itip.send(UUID.fromString(override), ItipMethod.REQUEST, account.id, null)
		val sentRecurrenceId =
			ics(calendarPart(onServer("INBOX", ANNA_USER).single())).children("VEVENT").single().property("RECURRENCE-ID")!!
		val tzid = sentRecurrenceId.param("TZID")
		tzid shouldBe "Europe/Zurich"
		val reply =
			message(from = ANNA, subject = "Accepted: Kickoff (room B)").apply {
				setContent(
					replyIcs(series, ANNA, "ACCEPTED")
						.replace("BEGIN:VEVENT\r\n", "BEGIN:VEVENT\r\nRECURRENCE-ID;TZID=$tzid:${sentRecurrenceId.value}\r\n"),
					"text/calendar; charset=UTF-8; method=REPLY",
				)
				saveChanges()
			}
		appendTo("INBOX", reply)
		fullSyncInbox()

		processor.process(cachedInbox().single().id) shouldContainExactly listOf(UUID.fromString(override))

		stored(override).attendees.single().status shouldBe AttendeeStatus.ACCEPTED
		stored(series).attendees.single().status shouldBe AttendeeStatus.NEEDS_ACTION
	}

	@Test
	fun `a REPLY listing several attendees only applies the answer of the sender`() {
		val event = createEvent(attendees = listOf(ANNA, BOB))
		val reply =
			message(from = ANNA, subject = "Answers").apply {
				setContent(
					replyIcs(event, ANNA, "ACCEPTED").replace(
						"ATTENDEE;PARTSTAT=ACCEPTED:mailto:$ANNA\r\n",
						"ATTENDEE;PARTSTAT=ACCEPTED:mailto:$ANNA\r\nATTENDEE;PARTSTAT=DECLINED:mailto:$BOB\r\n",
					),
					"text/calendar; charset=UTF-8; method=REPLY",
				)
				saveChanges()
			}
		appendTo("INBOX", reply)
		fullSyncInbox()

		processor.process(cachedInbox().single().id) shouldContainExactly listOf(UUID.fromString(event))

		stored(event).attendees.map { it.email to it.status } shouldContainExactly
			listOf(ANNA to AttendeeStatus.ACCEPTED, BOB to AttendeeStatus.NEEDS_ACTION)
	}

	@Test
	fun `a sender whose display name shows the attendee's address is not taken for the attendee`() {
		val event = createEvent(attendees = listOf(ANNA))
		val spoofed =
			replyMail(from = MALLORY, uid = event, attendee = ANNA, partStat = "DECLINED").apply {
				setFrom(InternetAddress(MALLORY, ANNA))
				saveChanges()
			}
		appendTo("INBOX", spoofed)
		fullSyncInbox()

		processor.process(cachedInbox().single().id).shouldBeEmpty()

		stored(event).attendees.single().status shouldBe AttendeeStatus.NEEDS_ACTION
	}

	@Test
	fun `a series REPLY skips overrides that no longer list the attendee`() {
		val series = createEvent(attendees = listOf(ANNA, BOB), rrule = "FREQ=WEEKLY")
		val withoutAnna =
			api
				.patchEventOk(
					series,
					"""{"attendees":[{"email":"$BOB"}]}""",
					"?scope=THIS&occurrence=2026-11-09T08:00:00Z",
				).id()
		appendTo("INBOX", replyMail(from = ANNA, uid = series, attendee = ANNA, partStat = "ACCEPTED"))
		fullSyncInbox()

		processor.process(cachedInbox().single().id) shouldContainExactly listOf(UUID.fromString(series))

		stored(withoutAnna).attendees.map { it.email to it.status } shouldContainExactly listOf(BOB to AttendeeStatus.NEEDS_ACTION)
	}

	// --- REPLYs across messages (itip-reply-ordering) -----------------------------------------------------------------

	@Test
	fun `a REPLY older than one taken from an earlier message is ignored, also after the event was edited`() {
		val event = createEvent(attendees = listOf(ANNA))
		appendTo("INBOX", replyMail(from = ANNA, uid = event, attendee = ANNA, partStat = "ACCEPTED", sequence = 2))
		fullSyncInbox()
		processor.process(cachedInbox().single().id) shouldContainExactly listOf(UUID.fromString(event))
		// The client sends the attendees back as it shows them, with a new one.
		api.patchEventOk(
			event,
			"""{"title":"Project kickoff (moved)","attendees":[{"email":"$ANNA","status":"ACCEPTED"},{"email":"$BOB"}]}""",
		)

		val taken = cachedInbox().map { it.id }.toSet()
		// Same SEQUENCE and an older DTSTAMP, and a lower SEQUENCE: both answered before the one taken.
		appendTo("INBOX", replyMail(from = ANNA, uid = event, attendee = ANNA, partStat = "DECLINED", sequence = 2, stamp = "20261026T110000Z"))
		appendTo("INBOX", replyMail(from = ANNA, uid = event, attendee = ANNA, partStat = "TENTATIVE", sequence = 1, stamp = "20261027T120000Z"))
		fullSyncInbox()
		val late = cachedInbox().map { it.id } - taken
		late.size shouldBe 2
		late.forEach { processor.process(it).shouldBeEmpty() }
		stored(event).attendees.map { it.status } shouldContainExactly listOf(AttendeeStatus.ACCEPTED, AttendeeStatus.NEEDS_ACTION)

		// A newer answer is still taken.
		val seen = taken + late
		appendTo("INBOX", replyMail(from = ANNA, uid = event, attendee = ANNA, partStat = "DECLINED", sequence = 3, stamp = "20261026T100000Z"))
		fullSyncInbox()
		processor.process((cachedInbox().map { it.id } - seen).single()) shouldContainExactly listOf(UUID.fromString(event))
		stored(event).attendees.first().status shouldBe AttendeeStatus.DECLINED
		jdbc.queryForObject("select sequence from calendar_itip_reply where event_id = ?::uuid", Int::class.java, event) shouldBe 3
	}

	@Test
	fun `older series and occurrence REPLYs stay ignored after the series and its override are edited via the API`() {
		val series = createEvent(attendees = listOf(ANNA), rrule = "FREQ=WEEKLY")
		val override = api.patchEventOk(series, """{"title":"Kickoff (room B)"}""", "?scope=THIS&occurrence=2026-11-09T08:00:00Z").id()
		// First the series answer (it sets every override too), then the one for the occurrence.
		appendTo("INBOX", replyMail(from = ANNA, uid = series, attendee = ANNA, partStat = "ACCEPTED", sequence = 2))
		fullSyncInbox()
		processor.process(cachedInbox().single().id) shouldContainExactly listOf(UUID.fromString(series), UUID.fromString(override))
		val seriesAnswer = cachedInbox().single().id
		appendTo(
			"INBOX",
			replyMail(from = ANNA, uid = series, attendee = ANNA, partStat = "DECLINED", recurrenceId = "20261109T080000Z", sequence = 2),
		)
		fullSyncInbox()
		processor.process(cachedInbox().map { it.id }.single { it != seriesAnswer }) shouldContainExactly listOf(UUID.fromString(override))
		val taken = cachedInbox().map { it.id }
		stored(override).attendees.single().status shouldBe AttendeeStatus.DECLINED

		// The whole series and the occurrence get their attendees replaced, as a client sends them back with a new one.
		api.patchEventOk(series, """{"attendees":[{"email":"$ANNA","status":"ACCEPTED"},{"email":"$BOB"}]}""", "?scope=ALL")
		api.patchEventOk(override, """{"attendees":[{"email":"$ANNA","status":"DECLINED"},{"email":"$BOB"}]}""")
		val before = listOf(series, override).associateWith { stored(it).attendees.map { a -> a.email to a.status } }

		// Answered before the ones taken: lower SEQUENCE for the series, same SEQUENCE and older DTSTAMP for the occurrence.
		appendTo("INBOX", replyMail(from = ANNA, uid = series, attendee = ANNA, partStat = "TENTATIVE", sequence = 1, stamp = "20261027T120000Z"))
		appendTo(
			"INBOX",
			replyMail(
				from = ANNA,
				uid = series,
				attendee = ANNA,
				partStat = "ACCEPTED",
				recurrenceId = "20261109T080000Z",
				sequence = 2,
				stamp = "20261026T110000Z",
			),
		)
		fullSyncInbox()
		val late = cachedInbox().map { it.id } - taken.toSet()
		late.size shouldBe 2
		late.forEach { processor.process(it).shouldBeEmpty() }

		listOf(series, override).associateWith { stored(it).attendees.map { a -> a.email to a.status } } shouldBe before
		before.getValue(series).first() shouldBe (ANNA to AttendeeStatus.ACCEPTED)
		before.getValue(override).first() shouldBe (ANNA to AttendeeStatus.DECLINED)
		jdbc.queryForObject("select count(*) from calendar_itip_reply where event_id = ?::uuid", Int::class.java, series) shouldBe 2
	}

	// --- helpers -------------------------------------------------------------------------------------------------------

	private fun createEvent(
		attendees: List<String>,
		rrule: String? = null,
	): String {
		val people = attendees.joinToString(",") { """{"email":"$it"}""" }
		val recurrence = rrule?.let { ""","rrule":"$it"""" } ?: ""
		return api
			.createEvent(
				"""
				{"calendarId":"$calendarId","title":"Project kickoff","location":"Room 4",
				 "start":"2026-11-02T08:00:00Z","end":"2026-11-02T09:00:00Z","timeZone":"Europe/Zurich",
				 "attendees":[$people]$recurrence}
				""".trimIndent(),
			).id()
	}

	/** An event of a Google calendar (stored directly; no provider is contacted in these tests). */
	private fun syncedEvent(attendees: List<String>): CalendarEvent {
		val source =
			sources.saveAndFlush(
				CalendarSource(CalendarSourceType.GOOGLE, "Google").apply {
					config =
						"""{"connectionId": "${UUID.randomUUID()}"}"""
				},
			)
		val calendar = calendars.saveAndFlush(Calendar(source.id, "Work").apply { externalId = "primary" })
		return events.saveAndFlush(
			CalendarEvent(calendar.id, "Synced meeting").apply {
				setTimed(Instant.parse("2026-11-03T08:00:00Z"), Instant.parse("2026-11-03T09:00:00Z"), ZoneId.of("Europe/Zurich"))
				this.attendees = attendees.map { Attendee(it) }
				externalId = "synced-1"
			},
		)
	}

	private fun stored(id: String): CalendarEvent = events.findById(UUID.fromString(id)).orElseThrow()

	/** Waits for the background processor (it handles one message after another). */
	private fun awaitStatus(
		event: String,
		email: String,
		status: AttendeeStatus,
	) {
		val deadline = System.nanoTime() + AWAIT_NANOS
		while (stored(event).attendees.single { it.email == email }.status != status) {
			check(System.nanoTime() < deadline) { "Attendee $email of event $event did not become $status" }
			Thread.sleep(POLL_MILLIS)
		}
	}

	/**
	 * Sends a valid REPLY for [control] and waits until it is applied: the processor handles messages in order, so every
	 * message announced before it has been looked at by then.
	 */
	private fun awaitControl(control: String) {
		appendTo("INBOX", replyMail(from = CAROL, uid = control, attendee = CAROL, partStat = "ACCEPTED"))
		syncAll()
		awaitStatus(control, CAROL, AttendeeStatus.ACCEPTED)
	}

	private fun nothingSent() {
		greenMail.receivedMessages.shouldBeEmpty()
	}

	private fun syncAll() {
		folders.findByAccountIdOrderByPathAsc(account.id).forEach { sync.syncFolder(it.id) }
	}

	/** Syncs the Inbox from scratch: a full sync caches the messages without announcing them as arrived. */
	private fun fullSyncInbox() {
		val inbox = checkNotNull(folders.findByAccountIdAndPath(account.id, "INBOX"))
		jdbc.update("update mail_folder set uidvalidity = null, highestmodseq = null where id = ?", inbox.id)
		sync.syncFolder(inbox.id)
	}

	private fun cachedInbox(): List<MailMessage> =
		messages.findByFolderIdAndUidIsNotNull(checkNotNull(folders.findByAccountIdAndPath(account.id, "INBOX")).id)

	private fun message(
		from: String,
		subject: String,
	): MimeMessage =
		MimeMessage(Session.getInstance(Properties())).apply {
			setFrom(InternetAddress(from))
			setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(JANE)))
			setSubject(subject)
		}

	private fun replyIcs(
		uid: String,
		attendee: String,
		partStat: String,
		recurrenceId: String? = null,
		stamp: String = "20261026T120000Z",
		sequence: Int? = null,
	): String =
		listOfNotNull(
			"BEGIN:VCALENDAR",
			"VERSION:2.0",
			"PRODID:-//Example//Fictitious Mail//EN",
			"METHOD:REPLY",
			"BEGIN:VEVENT",
			"UID:$uid",
			recurrenceId?.let { "RECURRENCE-ID:$it" },
			sequence?.let { "SEQUENCE:$it" },
			"DTSTAMP:$stamp",
			"ORGANIZER:mailto:$JANE",
			"ATTENDEE;PARTSTAT=$partStat:mailto:$attendee",
			"END:VEVENT",
			"END:VCALENDAR",
		).joinToString("\r\n", postfix = "\r\n")

	/** A REPLY like mail clients send it: text and the calendar object as alternatives. */
	private fun replyMail(
		from: String,
		uid: String,
		attendee: String,
		partStat: String,
		recurrenceId: String? = null,
		stamp: String = "20261026T120000Z",
		sequence: Int? = null,
	): MimeMessage =
		message(from, "Reply: Project kickoff").apply {
			setContent(
				MimeMultipart(
					"alternative",
					MimeBodyPart().apply { setText("$attendee answered $partStat.", "UTF-8") },
					MimeBodyPart().apply {
						setContent(replyIcs(uid, attendee, partStat, recurrenceId, stamp, sequence), "text/calendar; charset=UTF-8; method=REPLY")
					},
				),
			)
			saveChanges()
		}

	/** Sends [message] to GreenMail's SMTP server, which delivers it to the organizer's Inbox. */
	private fun smtpSend(message: MimeMessage) {
		val session =
			Session.getInstance(
				Properties().apply {
					put("mail.smtp.host", HOST)
					put("mail.smtp.port", greenMail.smtp.port.toString())
				},
			)
		session.getTransport("smtp").use { transport ->
			transport.connect()
			transport.sendMessage(message, message.allRecipients)
		}
	}

	private fun appendTo(
		path: String,
		message: MimeMessage,
	) {
		remote(JANE_USER) { store -> store.getFolder(path).appendMessages(arrayOf(message)) }
	}

	private fun <T> remote(
		user: String,
		block: (Store) -> T,
	): T =
		Session.getInstance(Properties()).getStore("imap").use { store ->
			store.connect(HOST, greenMail.imap.port, user, PASSWORD)
			block(store)
		}

	private fun onServer(
		path: String,
		user: String,
	): List<MimeMessage> =
		remote(user) { store ->
			val folder = store.getFolder(path)
			folder.open(Folder.READ_ONLY)
			try {
				folder.messages.map { MimeMessage(it as MimeMessage) }
			} finally {
				folder.close(false)
			}
		}

	private fun leaves(part: Part): List<Part> =
		if (part.isMimeType("multipart/*")) {
			val multipart = part.content as Multipart
			(0 until multipart.count).flatMap { leaves(multipart.getBodyPart(it)) }
		} else {
			listOf(part)
		}

	private fun calendarPart(message: MimeMessage): Part = leaves(message).single { it.isMimeType("text/calendar") }

	private fun ics(part: Part): IcsComponent {
		val text = part.inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) }
		text shouldStartWith "BEGIN:VCALENDAR"
		return CalDavEventMapping.calendar(text)
	}

	companion object {
		private const val HOST = "localhost"
		private const val PASSWORD = "fictitious-password"
		private const val JANE = "jane.doe@example.test"
		private const val JANE_USER = "jane"
		private const val SUPPORT = "support@example.test"
		private const val ANNA = "anna@example.test"
		private const val ANNA_USER = "anna"
		private const val BOB = "bob@example.test"
		private const val BOB_USER = "bob"
		private const val CAROL = "carol@example.test"
		private const val CAROL_USER = "carol"
		private const val MALLORY = "mallory@example.test"
		private const val MALLORY_USER = "mallory"
		private const val AWAIT_NANOS = 15_000_000_000L
		private const val POLL_MILLIS = 50L

		@JvmField
		@RegisterExtension
		val greenMail: GreenMailExtension =
			GreenMailExtension(arrayOf(ServerSetupTest.SMTP, ServerSetupTest.IMAP).map { it.dynamicPort() }.toTypedArray())
	}
}
