package ch.sonensei.cockpit.mail.search

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.mail.imap.MailAccountService
import ch.sonensei.cockpit.mail.imap.MailFolderService
import ch.sonensei.cockpit.mail.imap.MailServer
import ch.sonensei.cockpit.mail.imap.NewMailAccount
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.mail.sync.MailHeaderSyncService
import ch.sonensei.cockpit.mail.sync.MailSyncRepository
import ch.sonensei.cockpit.support.ExternalTest
import ch.sonensei.cockpit.support.PostgresTestBase
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetupTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Properties

/**
 * Mail search against GreenMail: synced headers and bodies loaded by reading are found in the cache; `server=true`
 * finds (and caches) a message older than the sync window by a word of its body, also a non-ASCII one.
 */
@ExternalTest
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@DirtiesContext
class MailSearchGreenMailTests(
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
		jdbc.update("delete from search_document where item_type = 'EMAIL'")
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
					syncWindowMonths = 12,
				),
			)
	}

	/** A message that arrived (INTERNALDATE, set by APPEND) at [received]. */
	private class DatedMessage(
		private val received: Instant,
	) : MimeMessage(Session.getInstance(Properties())) {
		override fun getReceivedDate(): Date = Date.from(received)
	}

	private fun message(
		subject: String,
		text: String,
		received: Instant,
	): MimeMessage =
		DatedMessage(received).apply {
			setFrom(InternetAddress("ann.example@example.test", "Ann Example"))
			setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(EMAIL)))
			setSubject(subject, "UTF-8")
			setText(text, "UTF-8")
			sentDate = Date.from(received)
			saveChanges()
		}

	private fun remote(block: (Store) -> Unit) =
		Session.getInstance(Properties()).getStore("imap").use { store ->
			store.connect(HOST, greenMail.imap.port, USER, PASSWORD)
			block(store)
		}

	private fun search(params: String): JsonNode =
		mockMvc
			.get(URI("/api/v1/mail/search?$params"))
			.andExpect { status { isOk() } }
			.andReturn()
			.response
			.contentAsString
			.let(json::readTree)

	private fun JsonNode.strings(field: String) = (0 until size()).map { this[it][field].asString() }

	@Test
	fun `finds synced headers, bodies after reading, and older mail on the server`() {
		val now = Instant.now()
		remote { store ->
			store.getFolder("INBOX").appendMessages(
				arrayOf(
					// The word is past what the list snippet holds, so only the cached body finds it.
					message("Kiwi report", "Numbers follow. ".repeat(300) + "The pineapple numbers are in.", now.minus(2, ChronoUnit.DAYS)),
					message("Old receipt", "Zebra crossing fee, Müsli included.", now.minus(800, ChronoUnit.DAYS)),
					// Only on the server until searched for: the non-ASCII hit depends on IMAP SEARCH CHARSET UTF-8.
					message("Recipe card", "Bircher-Müesli wie in Zürich.", now.minus(900, ChronoUnit.DAYS)),
				),
			)
		}
		folderService.sync(account.id)
		val inbox = checkNotNull(folders.findByAccountIdAndPath(account.id, "INBOX"))
		sync.syncFolder(inbox.id)
		val kiwi = messages.findByFolderIdAndUidIsNotNull(inbox.id).single()

		search("q=kiwi")["items"].strings("subject") shouldContainExactly listOf("Kiwi report")
		search("q=pineapple")["items"].strings("subject").shouldBeEmpty()
		mockMvc.get("/api/v1/mail/messages/${kiwi.id}").andExpect { status { isOk() } }
		search("q=pineapple")["items"].strings("subject") shouldContainExactly listOf("Kiwi report")

		search("q=zebra")["items"].strings("subject").shouldBeEmpty()
		val onServer = search("q=zebra&server=true")
		onServer["items"].strings("subject") shouldContainExactly listOf("Old receipt")
		onServer["server"]["loaded"].asInt() shouldBe 1
		onServer["server"]["failures"].size() shouldBe 0
		messages.findByFolderIdAndUidIsNotNull(inbox.id).size shouldBe 2

		val umlaut = search("q=m%C3%BCsli&folderId=${inbox.id}&server=true")
		umlaut["items"].strings("subject") shouldContainExactly listOf("Old receipt")
		umlaut["server"]["loaded"].asInt() shouldBe 0
		umlaut["server"]["searchedFolders"].asInt() shouldBe 1
		umlaut["server"]["failures"].size() shouldBe 0
		search("q=z%C3%BCrich")["items"].strings("subject").shouldBeEmpty()
		val uncached = search("q=z%C3%BCrich&folderId=${inbox.id}&server=true")
		uncached["items"].strings("subject") shouldContainExactly listOf("Recipe card")
		uncached["server"]["loaded"].asInt() shouldBe 1
		uncached["server"]["searchedFolders"].asInt() shouldBe 1
		uncached["server"]["failures"].size() shouldBe 0
		search("q=nothing%20matches&server=true")["items"].strings("subject").shouldBeEmpty()
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
