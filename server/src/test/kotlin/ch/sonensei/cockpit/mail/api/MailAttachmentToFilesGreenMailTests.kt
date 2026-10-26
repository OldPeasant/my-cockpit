package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.files.domain.SpecialFolder
import ch.sonensei.cockpit.mail.imap.MailAccountService
import ch.sonensei.cockpit.mail.imap.MailFolderService
import ch.sonensei.cockpit.mail.imap.MailServer
import ch.sonensei.cockpit.mail.imap.NewMailAccount
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.mail.sync.MailHeaderSyncService
import ch.sonensei.cockpit.mail.sync.MailSyncRepository
import ch.sonensei.cockpit.support.ExternalTest
import ch.sonensei.cockpit.support.PostgresTestBase
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetupTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import jakarta.activation.DataHandler
import jakarta.mail.Message
import jakarta.mail.Session
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.Properties
import java.util.UUID

/** "Save to Files" against GreenMail: the part lands in `/Attachments/Mail`, linked to the message. */
@ExternalTest
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@DirtiesContext
class MailAttachmentToFilesGreenMailTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountService,
	@Autowired private val folderService: MailFolderService,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailSyncRepository,
	@Autowired private val sync: MailHeaderSyncService,
	@Autowired private val files: FilesService,
	@Autowired private val links: LinkService,
) : PostgresTestBase() {
	private lateinit var account: MailAccount

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from link")
		jdbc.update("delete from mail_account")
		jdbc.execute("delete from file")
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

	private fun synced(): MailMessage {
		val message = MimeMessage(Session.getInstance(Properties()))
		message.setFrom(InternetAddress("finance@example.test"))
		message.setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(EMAIL)))
		message.subject = "Budget"
		message.setContent(
			MimeMultipart(
				MimeBodyPart().apply { setText("See attachment", "UTF-8") },
				MimeBodyPart().apply {
					dataHandler = DataHandler(ByteArrayDataSource(CSV, "text/csv"))
					fileName = "budget.csv"
					disposition = MimeBodyPart.ATTACHMENT
				},
			),
		)
		message.saveChanges()
		Session.getInstance(Properties()).getStore("imap").use { store ->
			store.connect(HOST, greenMail.imap.port, USER, PASSWORD)
			store.getFolder("INBOX").appendMessages(arrayOf(message))
		}
		folderService.sync(account.id)
		val inbox = checkNotNull(folders.findByAccountIdAndPath(account.id, "INBOX"))
		sync.syncFolder(inbox.id)
		return messages.findByFolderIdAndUidIsNotNull(inbox.id).single()
	}

	private fun save(
		message: MailMessage,
		partId: String,
	) = mockMvc.post("/api/v1/mail/messages/${message.id}/attachments/$partId/save-to-files") { with(csrf()) }.andReturn().response

	@Test
	fun `the attachment is stored in Attachments-Mail, linked to the message, a taken name gets a number`() {
		val message = synced()

		val first = save(message, CSV_PART)

		first.status shouldBe 201
		val body: JsonNode = json.readTree(first.contentAsString)
		val fileId = UUID.fromString(body["id"].asString())
		body["name"].asString() shouldBe "budget.csv"
		body["mime"].asString() shouldBe "text/csv"
		body["size"].asLong() shouldBe CSV.size.toLong()
		first.getHeader(HttpHeaders.LOCATION).shouldNotBeNull() shouldEndWith "/api/v1/files/$fileId"
		first.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
		val mailFolder = files.specialFolder(SpecialFolder.MAIL)
		body["folderId"].asString() shouldBe mailFolder.id.toString()
		files.pathOf(mailFolder.id) shouldBe "/Attachments/Mail"
		files.open(fileId).use { it.readAllBytes().toList() } shouldBe CSV.toList()
		links.linksOf(ItemRef(ItemType.EMAIL, message.id)).map { it.source.ref } shouldContainExactly
			listOf(ItemRef(ItemType.FILE, fileId))

		val second = save(message, CSV_PART)

		second.status shouldBe 201
		json.readTree(second.contentAsString)["name"].asString() shouldBe "budget (2).csv"
	}

	@Test
	fun `an unknown message or part is 404 Problem Details`() {
		val message = synced()

		mockMvc.post("/api/v1/mail/messages/${message.id}/attachments/9/save-to-files") { with(csrf()) }.andExpect {
			status { isNotFound() }
			content { contentTypeCompatibleWith("application/problem+json") }
		}
		mockMvc.post("/api/v1/mail/messages/${UUID.randomUUID()}/attachments/2/save-to-files") { with(csrf()) }.andExpect {
			status { isNotFound() }
		}
		jdbc.queryForObject("select count(*) from file", Long::class.java) shouldBe 0
	}

	@Test
	fun `an unreachable server is 502 and stores nothing`() {
		val message = synced()
		// Cache the body and its attachment list, so only the part download needs the server.
		mockMvc.post("/api/v1/mail/messages/${message.id}/attachments/$CSV_PART/save-to-files") { with(csrf()) }
		jdbc.execute("delete from link")
		jdbc.execute("delete from file")
		greenMail.stop()
		try {
			mockMvc.post("/api/v1/mail/messages/${message.id}/attachments/$CSV_PART/save-to-files") { with(csrf()) }.andExpect {
				status { isBadGateway() }
				jsonPath("$.errorCode") { value("CONNECTION_FAILED") }
			}
		} finally {
			greenMail.start()
		}
		jdbc.queryForObject("select count(*) from file", Long::class.java) shouldBe 0
	}

	companion object {
		private const val HOST = "localhost"
		private const val EMAIL = "jane.doe@example.test"
		private const val USER = "jane"
		private const val PASSWORD = "fictitious-password"
		private const val CSV_PART = "2"
		private val CSV = "item,amount\r\nfictitious,42".toByteArray()

		@JvmField
		@RegisterExtension
		val greenMail: GreenMailExtension =
			GreenMailExtension(arrayOf(ServerSetupTest.SMTP, ServerSetupTest.IMAP).map { it.dynamicPort() }.toTypedArray())
	}
}
