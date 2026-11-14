package ch.sonensei.cockpit.mail.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.mail.imap.ImapConnectionPool
import ch.sonensei.cockpit.mail.imap.MailAccountService
import ch.sonensei.cockpit.mail.imap.MailFolderService
import ch.sonensei.cockpit.mail.imap.MailServer
import ch.sonensei.cockpit.mail.imap.MailSessions
import ch.sonensei.cockpit.mail.imap.NewMailAccount
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageLocator
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.mail.sync.MailHeaderSyncService
import ch.sonensei.cockpit.mail.sync.MailSyncRepository
import ch.sonensei.cockpit.support.ExternalTest
import ch.sonensei.cockpit.support.PostgresTestBase
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetupTest
import com.ninjasquad.springmockk.MockkSpyBean
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.URLName
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.eclipse.angus.mail.imap.IMAPStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.Properties
import java.util.UUID

/** Mail → task & archive against GreenMail: the mails really move to Archive and the task links follow them. */
@ExternalTest
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@DirtiesContext
class MailTaskArchiveGreenMailTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountService,
	@Autowired private val folderService: MailFolderService,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailSyncRepository,
	@Autowired private val sync: MailHeaderSyncService,
	@Autowired private val pool: ImapConnectionPool,
	@Autowired private val locator: MailMessageLocator,
) : PostgresTestBase() {
	@MockkSpyBean
	private lateinit var sessions: MailSessions

	private lateinit var account: MailAccount

	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("delete from link")
		jdbc.execute("delete from task_context")
		jdbc.execute("delete from task")
		jdbc.execute("delete from search_document")
		jdbc.execute("delete from mail_account")
	}

	@BeforeEach
	fun setUp() {
		greenMail.setUser(EMAIL, USER, PASSWORD)
		account = account(EMAIL, USER)
	}

	private fun account(
		email: String,
		user: String,
	) = accounts.create(
		NewMailAccount(
			type = MailAccountType.IMAP_SMTP,
			displayName = email,
			emailAddress = email,
			imap = MailServer(HOST, greenMail.imap.port, MailSecurity.NONE),
			smtp = MailServer(HOST, greenMail.smtp.port, MailSecurity.NONE),
			username = user,
			password = PASSWORD,
			oauthConnectionId = null,
			signatureMd = null,
			notifyNewMail = null,
			syncWindowMonths = null,
		),
	)

	private fun <T> remote(
		user: String = USER,
		block: (Store) -> T,
	): T =
		Session.getInstance(Properties()).getStore("imap").use { store ->
			store.connect(HOST, greenMail.imap.port, user, PASSWORD)
			block(store)
		}

	private fun mail(
		n: Int,
		to: String,
	): MimeMessage =
		MimeMessage(Session.getInstance(Properties())).apply {
			setFrom(InternetAddress("sender@example.test"))
			setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(to)))
			subject = "Fictitious request $n"
			setText("Body $n")
			saveChanges()
			setHeader("Message-ID", "<request-$n-$to>")
		}

	/** Appends [count] mails to the INBOX of [of] (creating [extraFolders]) and syncs; returns the cached INBOX rows. */
	private fun inboxWith(
		count: Int,
		of: MailAccount = account,
		user: String = USER,
		vararg extraFolders: String = arrayOf("Archive"),
	): List<MailMessage> {
		remote(user) { store ->
			extraFolders.forEach { store.getFolder(it).create(Folder.HOLDS_MESSAGES) }
			store.getFolder("INBOX").appendMessages((1..count).map { mail(it, of.emailAddress) }.toTypedArray())
		}
		folderService.sync(of.id)
		folders.findByAccountIdOrderByPathAsc(of.id).forEach { sync.syncFolder(it.id) }
		return cached(of, "INBOX")
	}

	private fun folder(
		of: MailAccount,
		path: String,
	): MailFolder = checkNotNull(folders.findByAccountIdAndPath(of.id, path))

	private fun cached(
		of: MailAccount,
		path: String,
	): List<MailMessage> = messages.findByFolderIdAndUidIsNotNull(folder(of, path).id).sortedBy { it.subject }

	private fun subjectsOnServer(
		path: String,
		user: String = USER,
	): Set<String> =
		remote(user) { store ->
			val folder = store.getFolder(path)
			folder.open(Folder.READ_ONLY)
			try {
				folder.messages.map { it.subject }.toSet()
			} finally {
				folder.close(false)
			}
		}

	private fun toTasks(vararg ids: UUID): JsonNode =
		mockMvc
			.post("/api/v1/mail/messages/to-tasks") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = json.writeValueAsString(mapOf("ids" to ids.toList()))
			}.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let(json::readTree)

	private fun JsonNode.items(): List<JsonNode> = (0 until size()).map { this[it] }

	private fun getJson(url: String): JsonNode =
		mockMvc
			.get(url)
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let(json::readTree)

	/** The mail the task's single link points at, as the links API resolves it. */
	private fun linkedMail(taskId: String): JsonNode {
		val link = getJson("/api/v1/links?type=TASK&id=$taskId")["items"].single()
		return if (link["source"]["id"].asString() == taskId) link["target"] else link["source"]
	}

	/** The folder of the message the link's mail id resolves to now (as the EMAIL link resolver does). */
	private fun resolvedFolder(mailId: String): String = checkNotNull(locator.current(UUID.fromString(mailId))?.folderId).toString()

	@Test
	fun `one mail becomes a Next task and moves to Archive, and the link resolves to the moved mail`() {
		val (mail) = inboxWith(1)

		val result = toTasks(mail.id)["results"].single()

		result["archived"].asBoolean() shouldBe true
		result["archiveErrorCode"].isNull shouldBe true
		result["task"]["list"].asString() shouldBe "NEXT"
		result["task"]["title"].asString() shouldBe "Fictitious request 1"
		getJson("/api/v1/tasks/${result["task"]["id"].asString()}")["list"].asString() shouldBe "NEXT"
		subjectsOnServer("INBOX").shouldBeEmpty()
		subjectsOnServer("Archive") shouldBe setOf("Fictitious request 1")
		val linked = linkedMail(result["task"]["id"].asString())
		linked["type"].asString() shouldBe "EMAIL"
		resolvedFolder(linked["id"].asString()) shouldBe folder(account, "Archive").id.toString()
	}

	@Test
	fun `several mails each get a task and are archived`() {
		val (first, second) = inboxWith(2)

		val results = toTasks(first.id, second.id)["results"]

		results.items().map { it["id"].asString() } shouldBe listOf(first.id.toString(), second.id.toString())
		results.items().map { it["archived"].asBoolean() } shouldBe listOf(true, true)
		results.items().map { it["task"]["title"].asString() } shouldBe listOf("Fictitious request 1", "Fictitious request 2")
		subjectsOnServer("Archive") shouldBe setOf("Fictitious request 1", "Fictitious request 2")
		results.items().forEach { result ->
			resolvedFolder(linkedMail(result["task"]["id"].asString())["id"].asString()) shouldBe folder(account, "Archive").id.toString()
		}
	}

	@Test
	fun `a mail of an account without Archive keeps its task and reports why it was not archived`() {
		greenMail.setUser(OTHER_EMAIL, OTHER_USER, PASSWORD)
		val other = account(OTHER_EMAIL, OTHER_USER)
		val (archivable) = inboxWith(1)
		val (stuck) = inboxWith(1, other, OTHER_USER, *emptyArray<String>())

		val results = toTasks(archivable.id, stuck.id)["results"]

		results[0]["archived"].asBoolean() shouldBe true
		results[1]["archived"].asBoolean() shouldBe false
		results[1]["archiveErrorCode"].asString() shouldBe "NO_ROLE_FOLDER"
		results[1]["archiveDetail"].asString().isNotBlank() shouldBe true
		getJson("/api/v1/tasks/${results[1]["task"]["id"].asString()}")["list"].asString() shouldBe "NEXT"
		subjectsOnServer("INBOX", OTHER_USER) shouldBe setOf("Fictitious request 1")
		linkedMail(results[1]["task"]["id"].asString())["id"].asString() shouldBe stuck.id.toString()
	}

	@Test
	fun `without MOVE the mail is copied and expunged, and the link still resolves to the archived mail`() {
		pool.evict(account.id)
		val stores = mutableListOf<NoMoveImapStore>()
		every { sessions.connectImap(any()) } answers {
			val of = firstArg<MailAccount>()
			NoMoveImapStore(Session.getInstance(sessions.imapProperties(of, false))).apply {
				connect(of.imapHost, of.imapPort, USER, PASSWORD)
				stores += this
			}
		}
		val (mail) = inboxWith(1)

		val result = toTasks(mail.id)["results"].single()

		stores.any { it.askedForMove } shouldBe true
		result["archived"].asBoolean() shouldBe true
		subjectsOnServer("INBOX").shouldBeEmpty()
		subjectsOnServer("Archive") shouldBe setOf("Fictitious request 1")
		// Also after the next sync of both folders (which re-resolves a row the move could not relocate).
		folders.findByAccountIdOrderByPathAsc(account.id).forEach { sync.syncFolder(it.id) }
		val linked = linkedMail(result["task"]["id"].asString())
		resolvedFolder(linked["id"].asString()) shouldBe folder(account, "Archive").id.toString()
		cached(account, "Archive").single().subject shouldBe "Fictitious request 1"
		result["archiveErrorCode"].isNull shouldBe true
		cached(account, "INBOX").shouldBeEmpty()
	}

	/** An IMAP store that hides the server's MOVE capability, so moves fall back to COPY + EXPUNGE. */
	private class NoMoveImapStore(
		session: Session,
	) : IMAPStore(session, URLName("imap", null, -1, null, null, null)) {
		@Volatile var askedForMove = false

		override fun hasCapability(capability: String): Boolean {
			if (capability.equals("MOVE", ignoreCase = true)) {
				askedForMove = true
				return false
			}
			return super.hasCapability(capability)
		}
	}

	companion object {
		private const val HOST = "localhost"
		private const val EMAIL = "jane.doe@example.test"
		private const val USER = "jane"
		private const val OTHER_EMAIL = "john.roe@example.test"
		private const val OTHER_USER = "john"
		private const val PASSWORD = "fictitious-password"

		@JvmField
		@RegisterExtension
		val greenMail: GreenMailExtension =
			GreenMailExtension(arrayOf(ServerSetupTest.SMTP, ServerSetupTest.IMAP).map { it.dynamicPort() }.toTypedArray())
	}
}
