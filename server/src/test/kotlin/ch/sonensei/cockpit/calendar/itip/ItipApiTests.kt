package ch.sonensei.cockpit.calendar.itip

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.id
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.isProblem
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.mail.compose.MailSendRejectedException
import ch.sonensei.cockpit.mail.compose.MailSender
import ch.sonensei.cockpit.mail.imap.MailAccountService
import ch.sonensei.cockpit.mail.imap.MailConnectionError
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import ch.sonensei.cockpit.mail.imap.MailServer
import ch.sonensei.cockpit.mail.imap.NewMailAccount
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.support.PostgresTestBase
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.hamcrest.Matchers.contains
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.util.UUID

/**
 * `POST /api/v1/calendar/events/{id}/invitations` with the real [ItipService] and Postgres; only the SMTP [MailSender]
 * is mocked (the mails themselves are covered against GreenMail in [ItipGreenMailTests]). All people and addresses are
 * fictitious.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class ItipApiTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountService,
) : PostgresTestBase() {
	@MockkBean(relaxed = true)
	private lateinit var sender: MailSender

	private val api = CalendarApi(mockMvc, jsonMapper)
	private lateinit var account: MailAccount
	private lateinit var calendarId: String

	@BeforeEach
	fun setUp() {
		CalendarApi.resetCalendarTables(jdbc)
		jdbc.update("delete from mail_account")
		account =
			accounts.create(
				NewMailAccount(
					type = MailAccountType.IMAP_SMTP,
					displayName = "Jane Doe",
					emailAddress = JANE,
					imap = MailServer("imap.example.test", 993, MailSecurity.SSL_TLS),
					smtp = MailServer("smtp.example.test", 465, MailSecurity.SSL_TLS),
					username = "jane",
					password = "fictitious-password",
					oauthConnectionId = null,
					signatureMd = null,
					notifyNewMail = null,
					syncWindowMonths = null,
				),
			)
		calendarId = api.createCalendar().id()
	}

	private fun createEvent(attendees: List<String>): String {
		val people = attendees.joinToString(",") { """{"email":"$it"}""" }
		return api
			.createEvent(
				"""
				{"calendarId":"$calendarId","title":"Project kickoff",
				 "start":"2026-11-02T08:00:00Z","end":"2026-11-02T09:00:00Z","timeZone":"Europe/Zurich",
				 "attendees":[$people]}
				""".trimIndent(),
			).id()
	}

	private fun send(
		event: String,
		body: String,
	) = mockMvc.post("/api/v1/calendar/events/$event/invitations") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	@Test
	fun `a REQUEST goes to every attendee but the organizer over the chosen account`() {
		val event = createEvent(listOf(ANNA, BOB, JANE))
		val mail = slot<MimeMessage>()
		every { sender.send(any(), capture(mail)) } returns Unit

		send(event, """{"method":"REQUEST","accountId":"${account.id}"}""").andExpect {
			status { isOk() }
			jsonPath("$.method") { value("REQUEST") }
			jsonPath("$.recipients") { value(contains(ANNA, BOB)) }
		}

		verify(exactly = 1) { sender.send(match { it.id == account.id }, any()) }
		mail.captured.getRecipients(Message.RecipientType.TO).map { (it as InternetAddress).address } shouldContainExactly listOf(ANNA, BOB)
		mail.captured.subject shouldBe "Invitation: Project kickoff"
	}

	@Test
	fun `a CANCEL to chosen recipients goes only to them`() {
		val event = createEvent(listOf(ANNA, BOB))

		send(event, """{"method":"CANCEL","accountId":"${account.id}","recipients":["$BOB"]}""").andExpect {
			status { isOk() }
			jsonPath("$.method") { value("CANCEL") }
			jsonPath("$.recipients") { value(contains(BOB)) }
		}
	}

	@Test
	fun `missing method or account and invalid recipients answer 400 Problem Details without sending`() {
		val event = createEvent(listOf(ANNA))

		send(event, """{"accountId":"${account.id}"}""").isProblem(400, "method")
		send(event, """{"method":"REQUEST"}""").isProblem(400, "accountId")
		send(event, """{"method":"INVITE","accountId":"${account.id}"}""").andExpect { status { isBadRequest() } }
		send(event, """{"method":"REQUEST","accountId":"${account.id}","recipients":[]}""").isProblem(400, "recipients")
		send(event, """{"method":"REQUEST","accountId":"${UUID.randomUUID()}"}""").isProblem(400, "accountId")
		send(event, """{"method":"REQUEST","accountId":"${account.id}","recipients":["$CAROL"]}""").isProblem(400, "recipients")

		verify(exactly = 0) { sender.send(any(), any()) }
	}

	@Test
	fun `an unknown event answers 404 and an event without guests 409`() {
		send(UUID.randomUUID().toString(), """{"method":"REQUEST","accountId":"${account.id}"}""").andExpect {
			status { isNotFound() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
		}
		send(createEvent(emptyList()), """{"method":"REQUEST","accountId":"${account.id}"}""").andExpect {
			status { isConflict() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
		}
	}

	@Test
	fun `a refusal by the SMTP server answers 422 SEND_REJECTED with the rejected addresses`() {
		val event = createEvent(listOf(ANNA))
		every { sender.send(any(), any()) } throws MailSendRejectedException(listOf(ANNA), IOException("550 fictitious refusal"))

		send(event, """{"method":"REQUEST","accountId":"${account.id}"}""").andExpect {
			status { isUnprocessableContent() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.errorCode") { value("SEND_REJECTED") }
			jsonPath("$.rejectedRecipients") { value(contains(ANNA)) }
		}
	}

	@Test
	fun `a failing mail server answers 502 with its error code, an exhausted pool 503`() {
		val event = createEvent(listOf(ANNA))
		every { sender.send(any(), any()) } throws
			MailConnectionException(MailConnectionError.AUTHENTICATION_FAILED, IOException("fictitious-server-secret"))

		val body =
			send(event, """{"method":"REQUEST","accountId":"${account.id}"}""")
				.andExpect {
					status { isBadGateway() }
					content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
					jsonPath("$.errorCode") { value("AUTHENTICATION_FAILED") }
					jsonPath("$.detail") { value(MailConnectionError.AUTHENTICATION_FAILED.message) }
				}.andReturn()
				.response.contentAsString
		body shouldNotContain "fictitious-server-secret"

		every { sender.send(any(), any()) } throws MailConnectionException(MailConnectionError.POOL_EXHAUSTED)
		send(event, """{"method":"REQUEST","accountId":"${account.id}"}""").andExpect {
			status { isServiceUnavailable() }
			jsonPath("$.errorCode") { value("POOL_EXHAUSTED") }
		}
	}

	companion object {
		private const val JANE = "jane.doe@example.test"
		private const val ANNA = "anna@example.test"
		private const val BOB = "bob@example.test"
		private const val CAROL = "carol@example.test"
	}
}
