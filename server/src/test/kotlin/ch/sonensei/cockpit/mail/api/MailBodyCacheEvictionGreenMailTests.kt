package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.auth.WithCockpitUser
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
import io.kotest.matchers.shouldBe
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.Properties
import java.util.UUID

/**
 * The size bound of the body cache with attachment metadata (SPEC.md §7.2): with a 1-byte budget every read evicts
 * the previously cached message, body and `mail_body_attachments` row together, and an evicted message is read from
 * the mail server again.
 */
@ExternalTest
@SpringBootTest(properties = ["cockpit.mail.body-cache-max-bytes=1"])
@AutoConfigureMockMvc
@WithCockpitUser
@DirtiesContext
class MailBodyCacheEvictionGreenMailTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountService,
	@Autowired private val folderService: MailFolderService,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailSyncRepository,
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

	private fun withAttachment(
		subject: String,
		filename: String,
	): MimeMessage =
		MimeMessage(Session.getInstance(Properties())).apply {
			setFrom(InternetAddress("sender@example.test"))
			setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(EMAIL)))
			this.subject = subject
			setContent(
				MimeMultipart(
					MimeBodyPart().apply { setText("Text of $subject", "UTF-8") },
					MimeBodyPart().apply {
						dataHandler = DataHandler(ByteArrayDataSource("fictitious,1".toByteArray(), "text/csv"))
						fileName = filename
						disposition = MimeBodyPart.ATTACHMENT
					},
				),
			)
			saveChanges()
		}

	/** Messages A and B in the synced Inbox, by subject. */
	private fun synced(): Map<String, MailMessage> {
		Session.getInstance(Properties()).getStore("imap").use { store ->
			store.connect(HOST, greenMail.imap.port, USER, PASSWORD)
			store.getFolder("INBOX").appendMessages(arrayOf(withAttachment("A", "a.csv"), withAttachment("B", "b.csv")))
		}
		folderService.sync(account.id)
		val inbox = checkNotNull(folders.findByAccountIdAndPath(account.id, "INBOX"))
		sync.syncFolder(inbox.id)
		return messages.findByFolderIdAndUidIsNotNull(inbox.id).associateBy { checkNotNull(it.subject) }
	}

	private fun read(message: MailMessage): JsonNode =
		mockMvc
			.get("/api/v1/mail/messages/${message.id}")
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let(json::readTree)

	private fun rows(
		table: String,
		messageId: UUID,
	): Int = jdbc.queryForObject("select count(*) from $table where message_id = ?", Int::class.java, messageId)!!

	private fun cached(message: MailMessage): Boolean {
		val body = rows("mail_body", message.id)
		val attachments = rows("mail_body_attachments", message.id)
		// Never one without the other.
		body shouldBe attachments
		return body == 1
	}

	@Test
	fun `reading B evicts A with its attachment metadata, and A is read from the server again`() {
		val synced = synced()
		val a = synced.getValue("A")
		val b = synced.getValue("B")

		val firstA = read(a)
		cached(a) shouldBe true

		read(b)["attachments"][0]["filename"].asString() shouldBe "b.csv"
		cached(a) shouldBe false
		cached(b) shouldBe true

		val againA = read(a)
		againA["attachments"] shouldBe firstA["attachments"]
		againA["attachments"][0]["filename"].asString() shouldBe "a.csv"
		againA["body"]["text"].asString() shouldBe "Text of A"
		cached(a) shouldBe true
		cached(b) shouldBe false
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
