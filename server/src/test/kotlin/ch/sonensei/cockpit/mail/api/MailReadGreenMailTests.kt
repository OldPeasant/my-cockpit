package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.mail.imap.MailAccountService
import ch.sonensei.cockpit.mail.imap.MailFolderService
import ch.sonensei.cockpit.mail.imap.MailServer
import ch.sonensei.cockpit.mail.imap.NewMailAccount
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailBodyCache
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.mail.sync.MailHeaderSyncService
import ch.sonensei.cockpit.mail.sync.MailSyncRepository
import ch.sonensei.cockpit.support.ExternalTest
import ch.sonensei.cockpit.support.PostgresTestBase
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetupTest
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.activation.DataHandler
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.Properties

/** Reading bodies and attachments from GreenMail: fetch on first read, cache afterwards, never mark as seen. */
@ExternalTest
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@DirtiesContext
class MailReadGreenMailTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountService,
	@Autowired private val folderService: MailFolderService,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailSyncRepository,
	@Autowired private val sync: MailHeaderSyncService,
	@Autowired private val cache: MailBodyCache,
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

	private fun newsletter(): MimeMessage {
		val message = MimeMessage(Session.getInstance(Properties()))
		message.setHeader("Message-ID", "<newsletter@example.test>")
		message.setFrom(InternetAddress("news@example.test", "Example News"))
		message.setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(EMAIL)))
		message.subject = "Grüezi newsletter"
		val alternative =
			MimeMultipart(
				"alternative",
				MimeBodyPart().apply { setText("Grüezi in plain text", "UTF-8") },
				MimeBodyPart().apply {
					setText(
						"""<p>Grüezi <b>HTML</b><img src="cid:logo@example.test"><script>alert(1)</script></p>""",
						"UTF-8",
						"html",
					)
				},
			)
		val related =
			MimeMultipart(
				"related",
				MimeBodyPart().apply { setContent(alternative) },
				MimeBodyPart().apply {
					dataHandler = DataHandler(ByteArrayDataSource(PNG, "image/png"))
					contentID = "<logo@example.test>"
					disposition = MimeBodyPart.INLINE
					fileName = "logo.png"
				},
			)
		val mixed =
			MimeMultipart(
				MimeBodyPart().apply { setContent(related) },
				MimeBodyPart().apply {
					dataHandler = DataHandler(ByteArrayDataSource(CSV, "text/csv"))
					fileName = "budget.csv"
					disposition = MimeBodyPart.ATTACHMENT
				},
			)
		message.setContent(mixed)
		message.saveChanges()
		message.setHeader("Message-ID", "<newsletter@example.test>")
		return message
	}

	private fun synced(): MailMessage {
		remote { store ->
			val inbox = store.getFolder("INBOX")
			inbox.appendMessages(arrayOf(newsletter()))
		}
		folderService.sync(account.id)
		val inbox = checkNotNull(folders.findByAccountIdAndPath(account.id, "INBOX"))
		sync.syncFolder(inbox.id)
		return messages.findByFolderIdAndUidIsNotNull(inbox.id).single()
	}

	private fun read(message: MailMessage): JsonNode =
		mockMvc
			.get("/api/v1/mail/messages/${message.id}")
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let(json::readTree)

	@Test
	fun `the body is fetched from the server, sanitized and cached without marking the message as seen`() {
		val message = synced()

		val read = read(message)

		read["subject"].asString() shouldBe "Grüezi newsletter"
		read["body"]["text"].asString() shouldBe "Grüezi in plain text"
		val html = read["body"]["html"].asString()
		html shouldContain "Grüezi <b>HTML</b>"
		(html.contains("script")) shouldBe false
		val attachments = read["attachments"]
		attachments.size() shouldBe 2
		attachments[0]["filename"].asString() shouldBe "logo.png"
		attachments[0]["inline"].asBoolean() shouldBe true
		attachments[0]["contentId"].asString() shouldBe "logo@example.test"
		attachments[1]["filename"].asString() shouldBe "budget.csv"
		attachments[1]["inline"].asBoolean() shouldBe false
		attachments[1]["contentType"].asString() shouldBe "text/csv"
		val logoPart = attachments[0]["partId"].asString()
		html shouldContain "/api/v1/mail/messages/${message.id}/attachments/$logoPart?inline=true"
		cache.get(message.id).shouldNotBeNull().textPlain shouldBe "Grüezi in plain text"
		seenOnServer() shouldBe false

		// Reopening needs no server: the same content comes from the cache.
		greenMail.stop()
		try {
			read(message)["body"]["text"].asString() shouldBe "Grüezi in plain text"
		} finally {
			greenMail.start()
		}
	}

	@Test
	fun `attachments stream from the server as downloads, inline only for images`() {
		val message = synced()
		val attachments = read(message)["attachments"]
		val csvPart = attachments[1]["partId"].asString()
		val logoPart = attachments[0]["partId"].asString()

		val csv = mockMvc.get("/api/v1/mail/messages/${message.id}/attachments/$csvPart?inline=true").andReturn().response
		csv.status shouldBe 200
		csv.contentAsByteArray.toList() shouldBe CSV.toList()
		csv.contentType shouldBe "application/octet-stream"
		csv.getHeader(HttpHeaders.CONTENT_DISPOSITION).shouldNotBeNull() shouldContain "attachment"
		csv.getHeader(HttpHeaders.CONTENT_DISPOSITION).shouldNotBeNull() shouldContain "budget.csv"
		csv.getHeader("X-Content-Type-Options") shouldBe "nosniff"

		val logo = mockMvc.get("/api/v1/mail/messages/${message.id}/attachments/$logoPart?inline=true").andReturn().response
		logo.status shouldBe 200
		logo.contentAsByteArray.toList() shouldBe PNG.toList()
		logo.contentType shouldBe "image/png"
		logo.getHeader(HttpHeaders.CONTENT_DISPOSITION).shouldNotBeNull() shouldContain "inline"
		seenOnServer() shouldBe false
	}

	@Test
	fun `a message expunged on the server since the last sync is 404`() {
		val message = synced()
		remote { store ->
			val inbox = store.getFolder("INBOX")
			inbox.open(Folder.READ_WRITE)
			inbox.messages.forEach { it.setFlag(Flags.Flag.DELETED, true) }
			inbox.close(true)
		}

		mockMvc.get("/api/v1/mail/messages/${message.id}").andExpect { status { isNotFound() } }
	}

	@Test
	fun `an unreachable server is 502 with an error code`() {
		val message = synced()
		greenMail.stop()
		try {
			mockMvc.get("/api/v1/mail/messages/${message.id}").andExpect {
				status { isBadGateway() }
				jsonPath("$.errorCode") { value("CONNECTION_FAILED") }
			}
		} finally {
			greenMail.start()
		}
	}

	private fun seenOnServer(): Boolean =
		remote { store ->
			val inbox = store.getFolder("INBOX")
			inbox.open(Folder.READ_ONLY)
			try {
				inbox.messages.single().isSet(Flags.Flag.SEEN)
			} finally {
				inbox.close(false)
			}
		}

	companion object {
		private const val HOST = "localhost"
		private const val EMAIL = "jane.doe@example.test"
		private const val USER = "jane"
		private const val PASSWORD = "fictitious-password"
		private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
		// CRLF: text parts are transmitted with canonical line ends.
		private val CSV = "item,amount\r\nfictitious,42".toByteArray()

		@JvmField
		@RegisterExtension
		val greenMail: GreenMailExtension =
			GreenMailExtension(arrayOf(ServerSetupTest.SMTP, ServerSetupTest.IMAP).map { it.dynamicPort() }.toTypedArray())
	}
}
