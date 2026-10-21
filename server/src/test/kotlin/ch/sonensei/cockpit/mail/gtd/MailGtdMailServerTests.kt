package ch.sonensei.cockpit.mail.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.api.MailContent
import ch.sonensei.cockpit.mail.api.MailContentLoader
import ch.sonensei.cockpit.mail.imap.MailConnectionError
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageRepository
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.support.PostgresTestBase
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.time.Instant

/**
 * "Create note" when the body has to come from the mail server (SPEC.md §7.3), with the content loader replaced: server
 * failures map to 503/502 and create nothing, and text from the server is cleaned before it is stored.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class MailGtdMailServerTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailMessageRepository,
) : PostgresTestBase() {
	@MockkBean
	private lateinit var loader: MailContentLoader

	private lateinit var mail: MailMessage

	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("delete from link")
		jdbc.execute("delete from task_context")
		jdbc.execute("delete from task")
		jdbc.execute("delete from note")
		jdbc.execute("delete from search_document")
		jdbc.execute("delete from mail_account")
	}

	@BeforeEach
	fun setUp() {
		val account =
			accounts.save(
				MailAccount(
					MailAccountType.IMAP_SMTP,
					"Work",
					"ann@work.example",
					"imap.example.test",
					993,
					MailSecurity.SSL_TLS,
					"smtp.example.test",
					587,
					MailSecurity.STARTTLS,
					"ann",
				),
			)
		val inbox = folders.save(MailFolder(account.id, "INBOX", "INBOX").apply { role = MailFolderRole.INBOX })
		mail =
			messages.save(
				MailMessage(account.id, inbox.id, 1).apply {
					subject = "Server copy only"
					from = listOf(MailAddress("bob@example.test", "Bob Example"))
					to = listOf(MailAddress("ann@work.example", "Ann Example"))
					sentAt = Instant.parse("2026-02-20T09:30:00Z")
					messageIdHeader = "<server-only@example.test>"
				},
			)
	}

	private fun toNote() = mockMvc.post("/api/v1/mail/messages/${mail.id}/to-note") { with(csrf()) }

	private fun count(table: String): Long = jdbc.queryForObject("select count(*) from $table", Long::class.java)!!

	/** Runs to-note, expects 201, and returns the stored body of the new note. */
	private fun createdNoteBody(): String {
		val response =
			toNote()
				.andExpect { status { isCreated() } }
				.andReturn()
				.response.contentAsString
		val created = json.readTree(response)
		val note =
			mockMvc
				.get("/api/v1/notes/${created["id"].asString()}")
				.andExpect { status { isOk() } }
				.andReturn()
				.response.contentAsString
		return json.readTree(note)["bodyMd"].asString()
	}

	private fun shouldHaveCreatedNothing() {
		count("note") shouldBe 0
		count("link") shouldBe 0
	}

	@Test
	fun `busy connections give 503 with POOL_EXHAUSTED and create no note`() {
		every { loader.load(any()) } throws MailConnectionException(MailConnectionError.POOL_EXHAUSTED)

		toNote().andExpect {
			status { isServiceUnavailable() }
			content { contentTypeCompatibleWith("application/problem+json") }
			jsonPath("$.errorCode") { value("POOL_EXHAUSTED") }
			jsonPath("$.detail") { value(MailConnectionError.POOL_EXHAUSTED.message) }
		}

		shouldHaveCreatedNothing()
	}

	@Test
	fun `other mail server failures give 502 without the server response and create no note`() {
		val errors = listOf(MailConnectionError.TIMEOUT, MailConnectionError.CONNECTION_FAILED, MailConnectionError.AUTHENTICATION_FAILED)
		for (error in errors) {
			every { loader.load(any()) } throws
				MailConnectionException(error, IOException("* BYE secret response for jane@example.test"))

			val response =
				toNote()
					.andExpect {
						status { isBadGateway() }
						content { contentTypeCompatibleWith("application/problem+json") }
						jsonPath("$.errorCode") { value(error.name) }
						jsonPath("$.detail") { value(error.message) }
					}.andReturn()
					.response

			response.contentAsString shouldNotContain "secret response"
		}

		shouldHaveCreatedNothing()
	}

	@Test
	fun `a message that is no longer on the server gives 404 and creates no note`() {
		every { loader.load(any()) } throws NotFoundException("Message not found")

		toNote().andExpect { status { isNotFound() } }

		shouldHaveCreatedNothing()
	}

	@Test
	fun `NUL characters in the text from the server are removed from the note body`() {
		every { loader.load(any()) } returns MailContent("Hello\u0000 Ann,\nsee\u0000 you.\u0000", null, emptyList())

		val body = createdNoteBody()

		body shouldContain "Hello Ann,\nsee you."
		body shouldNotContain "\u0000"
		count("link") shouldBe 1
	}

	@Test
	fun `NUL characters in an HTML-only body from the server are removed from the note body`() {
		every { loader.load(any()) } returns MailContent(null, "<p>Fictitious\u0000 offer</p>", emptyList())

		val body = createdNoteBody()

		body shouldContain "offer"
		body shouldNotContain "\u0000"
	}
}
