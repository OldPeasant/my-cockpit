package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.mail.imap.MailAccountService
import ch.sonensei.cockpit.mail.imap.MailFolderService
import ch.sonensei.cockpit.mail.imap.MailServer
import ch.sonensei.cockpit.mail.imap.NewMailAccount
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.mail.sync.MailHeaderSyncService
import ch.sonensei.cockpit.support.ExternalTest
import ch.sonensei.cockpit.support.PostgresTestBase
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetupTest
import io.kotest.matchers.shouldBe
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Properties
import java.util.UUID

/** Loading mail older than the sync window from GreenMail through the API, then listing past the window. */
@ExternalTest
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@DirtiesContext
class MailLoadOlderGreenMailTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountService,
	@Autowired private val folderService: MailFolderService,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val sync: MailHeaderSyncService,
) : PostgresTestBase() {
	private lateinit var account: MailAccount

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from mail_account")
		greenMail.setUser(EMAIL, USER, PASSWORD)
		account =
			accounts.create(
				NewMailAccount(
					type = MailAccountType.IMAP_SMTP,
					displayName = "GreenMail",
					emailAddress = EMAIL,
					imap = MailServer(HOST, greenMail.imap.port, MailSecurity.NONE),
					smtp = MailServer(HOST, greenMail.smtp.port, MailSecurity.NONE),
					username = USER,
					password = PASSWORD,
					oauthConnectionId = null,
					signatureMd = null,
					notifyNewMail = null,
					syncWindowMonths = null,
				),
			)
	}

	private fun <T> remote(block: (Store) -> T): T =
		Session.getInstance(Properties()).getStore("imap").use { store ->
			store.connect(HOST, greenMail.imap.port, USER, PASSWORD)
			block(store)
		}

	private fun mail(
		messageId: String,
		subject: String,
		received: Instant,
	): MimeMessage {
		val message =
			object : MimeMessage(Session.getInstance(Properties())) {
				override fun getReceivedDate(): Date = Date.from(received)

				// Keep the Message-ID we set instead of generating one.
				override fun updateMessageID() = Unit
			}
		message.setHeader("Message-ID", messageId)
		message.setFrom(InternetAddress("ann.example@example.test", "Ann Example"))
		message.setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(EMAIL)))
		message.subject = subject
		message.setText("Hello from the fictitious test.")
		message.saveChanges()
		return message
	}

	/** INBOX with one message inside the 12-month sync window and two older ones, synced. */
	private fun syncedInbox(): MailFolder {
		val now = Instant.now()
		remote { store ->
			store.getFolder("INBOX").appendMessages(
				arrayOf(
					mail("<older@example.test>", "Older", now.minus(500, ChronoUnit.DAYS)),
					mail("<old@example.test>", "Old", now.minus(400, ChronoUnit.DAYS)),
					mail("<new@example.test>", "New", now.minus(1, ChronoUnit.DAYS)),
				),
			)
		}
		folderService.sync(account.id)
		val inbox = checkNotNull(folders.findByAccountIdAndPath(account.id, "INBOX"))
		sync.syncFolder(inbox.id)
		return inbox
	}

	private fun list(
		folderId: UUID,
		cursor: String? = null,
	): JsonNode =
		mockMvc
			.get("/api/v1/mail/messages") {
				param("folderId", folderId.toString())
				param("limit", "1")
				cursor?.let { param("cursor", it) }
			}.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let(json::readTree)

	private fun loadOlder(
		folderId: UUID,
		limit: Int,
	): JsonNode =
		mockMvc
			.post("/api/v1/mail/folders/$folderId/load-older") {
				with(csrf())
				param("limit", "$limit")
			}.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let(json::readTree)

	/** Every subject of the folder list, page by page, and `hasOlder` of the last page. */
	private fun walk(folderId: UUID): Pair<List<String>, Boolean> {
		val subjects = mutableListOf<String>()
		var page = list(folderId)
		while (true) {
			page["items"].forEach { subjects += it["subject"].asString() }
			val next = page["nextCursor"]
			if (next.isNull) return subjects to page["hasOlder"].asBoolean()
			page = list(folderId, next.asString())
		}
	}

	@Test
	fun `loading older mail lets the folder list continue past the sync window`() {
		val inbox = syncedInbox()
		walk(inbox.id) shouldBe (listOf("New") to true)

		val first = loadOlder(inbox.id, limit = 1)
		first["loaded"].asInt() shouldBe 1
		first["hasMore"].asBoolean() shouldBe true
		walk(inbox.id) shouldBe (listOf("New", "Old") to true)

		val second = loadOlder(inbox.id, limit = 5)
		second["loaded"].asInt() shouldBe 1
		second["hasMore"].asBoolean() shouldBe false
		walk(inbox.id) shouldBe (listOf("New", "Old", "Older") to false)
	}

	@Test
	fun `an unknown folder is 404, a folder deleted on the server too`() {
		mockMvc.post("/api/v1/mail/folders/${UUID.randomUUID()}/load-older") { with(csrf()) }.andExpect {
			status { isNotFound() }
			content { contentTypeCompatibleWith("application/problem+json") }
		}

		remote { store -> store.getFolder("Archive").create(Folder.HOLDS_MESSAGES) }
		folderService.sync(account.id)
		val archive = checkNotNull(folders.findByAccountIdAndPath(account.id, "Archive"))
		remote { store -> store.getFolder("Archive").delete(true) }
		mockMvc.post("/api/v1/mail/folders/${archive.id}/load-older") { with(csrf()) }.andExpect {
			status { isNotFound() }
			content { contentTypeCompatibleWith("application/problem+json") }
		}
	}

	@Test
	fun `an unreachable server is 502 with an error code`() {
		val inbox = syncedInbox()
		greenMail.stop()
		try {
			mockMvc.post("/api/v1/mail/folders/${inbox.id}/load-older") { with(csrf()) }.andExpect {
				status { isBadGateway() }
				jsonPath("$.errorCode") { value("CONNECTION_FAILED") }
			}
		} finally {
			greenMail.start()
		}
	}

	companion object {
		private const val HOST = "localhost"
		private const val EMAIL = "jane.doe@example.test"
		private const val USER = "jane"
		private const val PASSWORD = "fictitious-password"

		@JvmField
		@RegisterExtension
		val greenMail: GreenMailExtension =
			GreenMailExtension(arrayOf(ServerSetupTest.SMTP, ServerSetupTest.IMAP).map { it.dynamicPort() }.toTypedArray())
	}
}
