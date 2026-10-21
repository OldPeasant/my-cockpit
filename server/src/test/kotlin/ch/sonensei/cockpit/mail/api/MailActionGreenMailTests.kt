package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.mail.imap.MailAccountService
import ch.sonensei.cockpit.mail.imap.MailFolderService
import ch.sonensei.cockpit.mail.imap.MailServer
import ch.sonensei.cockpit.mail.imap.NewMailAccount
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailMessage
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
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
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
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.Properties
import java.util.UUID

/** Every triage action against GreenMail: the server and the header cache agree afterwards. */
@ExternalTest
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@DirtiesContext
class MailActionGreenMailTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountService,
	@Autowired private val folderService: MailFolderService,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailSyncRepository,
	@Autowired private val sync: MailHeaderSyncService,
	@Autowired private val links: LinkService,
) : PostgresTestBase() {
	private lateinit var account: MailAccount

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from mail_account")
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

	private fun mail(n: Int): MimeMessage =
		MimeMessage(Session.getInstance(Properties())).apply {
			setFrom(InternetAddress("sender@example.test"))
			setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(EMAIL)))
			subject = "Fictitious message $n"
			setText("Body $n")
			saveChanges()
			setHeader("Message-ID", "<message-$n@example.test>")
		}

	/** Creates [extraFolders], appends [count] messages to INBOX and syncs; returns the cached INBOX rows by subject. */
	private fun inboxWith(
		count: Int,
		vararg extraFolders: String = arrayOf("Archive", "Trash", "Junk", "Projects"),
	): List<MailMessage> {
		remote { store ->
			extraFolders.forEach { store.getFolder(it).create(Folder.HOLDS_MESSAGES) }
			store.getFolder("INBOX").appendMessages((1..count).map(::mail).toTypedArray())
		}
		folderService.sync(account.id)
		syncAll()
		return cached("INBOX")
	}

	private fun syncAll() = folders.findByAccountIdOrderByPathAsc(account.id).forEach { sync.syncFolder(it.id) }

	private fun folder(path: String): MailFolder = checkNotNull(folders.findByAccountIdAndPath(account.id, path))

	private fun cached(path: String): List<MailMessage> = messages.findByFolderIdAndUidIsNotNull(folder(path).id).sortedBy { it.subject }

	private fun act(
		action: String,
		vararg ids: UUID,
		targetFolderId: UUID? = null,
	): JsonNode {
		val body =
			mapOf("ids" to ids.toList(), "action" to action, "targetFolderId" to targetFolderId)
				.filterValues { it != null }
		return mockMvc
			.post("/api/v1/mail/messages/actions") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = json.writeValueAsString(body)
			}.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let(json::readTree)
	}

	/** Subject → (seen, flagged) of the messages in a server folder. */
	private fun onServer(path: String): Map<String, Pair<Boolean, Boolean>> =
		remote { store ->
			val folder = store.getFolder(path)
			folder.open(Folder.READ_ONLY)
			try {
				folder.messages.associate { it.subject to (it.isSet(Flags.Flag.SEEN) to it.isSet(Flags.Flag.FLAGGED)) }
			} finally {
				folder.close(false)
			}
		}

	private fun uidsOnServer(path: String): Map<String, Long> =
		remote { store ->
			val folder = store.getFolder(path)
			folder.open(Folder.READ_ONLY)
			try {
				folder.messages.associate { it.subject to (folder as UIDFolder).getUID(it) }
			} finally {
				folder.close(false)
			}
		}

	private fun JsonNode.okIds(): List<String> = this["results"].filter { it["ok"].asBoolean() }.map { it["id"].asString() }

	private fun reloaded(message: MailMessage): MailMessage = messages.findById(message.id).orElseThrow()

	@Test
	fun `read, unread, flag and unflag change the server flags and the cache`() {
		val (first, second) = inboxWith(2)

		act("READ", first.id, second.id).okIds() shouldContainExactly listOf(first.id.toString(), second.id.toString())
		onServer("INBOX").values.map { it.first } shouldBe listOf(true, true)
		reloaded(first).seen shouldBe true

		act("UNREAD", second.id)
		act("FLAG", first.id)
		onServer("INBOX") shouldBe mapOf("Fictitious message 1" to (true to true), "Fictitious message 2" to (false to false))
		reloaded(second).seen shouldBe false
		reloaded(first).flagged shouldBe true

		act("UNFLAG", first.id)
		onServer("INBOX").getValue("Fictitious message 1") shouldBe (true to false)
		reloaded(first).flagged shouldBe false

		// The next sync finds nothing to change: cache and server agree.
		sync.syncFolder(folder("INBOX").id).updated shouldBe 0
	}

	@Test
	fun `move relocates the cached row to the new UID and keeps its id`() {
		val (first, second) = inboxWith(2)
		val projects = folder("Projects")

		val result = act("MOVE", first.id, targetFolderId = projects.id)

		result.okIds() shouldContainExactly listOf(first.id.toString())
		uidsOnServer("INBOX").keys shouldBe setOf("Fictitious message 2")
		val moved = reloaded(first)
		moved.folderId shouldBe projects.id
		moved.uid shouldBe uidsOnServer("Projects").getValue("Fictitious message 1")
		cached("INBOX").map { it.id } shouldContainExactly listOf(second.id)

		// Syncing both folders afterwards neither duplicates nor drops the moved message.
		syncAll()
		cached("Projects").map { it.id } shouldContainExactly listOf(first.id)
		cached("INBOX").map { it.id } shouldContainExactly listOf(second.id)
	}

	@Test
	fun `archive and spam move to the role folders`() {
		val (first, second) = inboxWith(2)

		act("ARCHIVE", first.id).okIds() shouldContainExactly listOf(first.id.toString())
		act("SPAM", second.id).okIds() shouldContainExactly listOf(second.id.toString())

		uidsOnServer("INBOX").keys.shouldBeEmpty()
		uidsOnServer("Archive").keys shouldBe setOf("Fictitious message 1")
		uidsOnServer("Junk").keys shouldBe setOf("Fictitious message 2")
		reloaded(first).folderId shouldBe folder("Archive").id
		reloaded(second).folderId shouldBe folder("Junk").id

		// Archiving what is already archived changes nothing.
		act("ARCHIVE", first.id).okIds() shouldContainExactly listOf(first.id.toString())
		uidsOnServer("Archive").keys shouldBe setOf("Fictitious message 1")
	}

	@Test
	fun `delete moves to Trash, and deleting in Trash removes the message for good`() {
		val (first) = inboxWith(1)

		act("DELETE", first.id).okIds() shouldContainExactly listOf(first.id.toString())
		uidsOnServer("INBOX").keys.shouldBeEmpty()
		uidsOnServer("Trash").keys shouldBe setOf("Fictitious message 1")
		reloaded(first).folderId shouldBe folder("Trash").id

		act("DELETE", first.id).okIds() shouldContainExactly listOf(first.id.toString())
		uidsOnServer("Trash").keys.shouldBeEmpty()
		messages.findById(first.id).isPresent shouldBe false
	}

	@Test
	fun `partial failures are reported per id`() {
		val (first, second) = inboxWith(2)
		val unknown = UUID.randomUUID()
		// The second message was deleted on the server since the last sync.
		remote { store ->
			val inbox = store.getFolder("INBOX")
			inbox.open(Folder.READ_WRITE)
			inbox.messages.single { it.subject == "Fictitious message 2" }.setFlag(Flags.Flag.DELETED, true)
			inbox.close(true)
		}

		val results = act("FLAG", first.id, unknown, second.id)["results"]

		results.toList().map { it["id"].asString() } shouldBe listOf(first.id, unknown, second.id).map { it.toString() }
		results.toList().map { it["ok"].asBoolean() } shouldBe listOf(true, false, false)
		results[1]["errorCode"].asString() shouldBe "NOT_FOUND"
		results[2]["errorCode"].asString() shouldBe "NOT_FOUND"
		onServer("INBOX").getValue("Fictitious message 1") shouldBe (false to true)
	}

	@Test
	fun `an action needing a missing role folder or a folder of another account fails for those ids`() {
		val (first) = inboxWith(1, "Projects")
		greenMail.setUser("john.doe@example.test", "john", PASSWORD)
		val other = account("john.doe@example.test", "john")
		remote("john") { it.getFolder("Elsewhere").create(Folder.HOLDS_MESSAGES) }
		folderService.sync(other.id)
		val elsewhere = checkNotNull(folders.findByAccountIdAndPath(other.id, "Elsewhere"))

		act("ARCHIVE", first.id)["results"][0]["errorCode"].asString() shouldBe "NO_ROLE_FOLDER"
		act("MOVE", first.id, targetFolderId = elsewhere.id)["results"][0]["errorCode"].asString() shouldBe "OTHER_ACCOUNT"
		uidsOnServer("INBOX").keys shouldBe setOf("Fictitious message 1")
		reloaded(first).folderId shouldBe folder("INBOX").id
	}

	@Test
	fun `an unreachable server fails every id with its error code`() {
		val (first) = inboxWith(1)
		greenMail.stop()
		try {
			val result = act("READ", first.id)["results"][0]
			result["ok"].asBoolean() shouldBe false
			result["errorCode"].asString() shouldBe "CONNECTION_FAILED"
		} finally {
			greenMail.start()
		}
		reloaded(first).seen shouldBe false
	}

	private fun JsonNode.codes(): List<String?> =
		this["results"].toList().map { if (it["errorCode"].isNull) null else it["errorCode"].asString() }

	private fun deleteOnServer(
		path: String,
		subject: String,
	) = remote { store ->
		val folder = store.getFolder(path)
		folder.open(Folder.READ_WRITE)
		folder.messages.single { it.subject == subject }.setFlag(Flags.Flag.DELETED, true)
		folder.close(true)
	}

	@Test
	fun `duplicate ids are acted on and reported once`() {
		val (first) = inboxWith(1)

		val result = act("READ", first.id, first.id, first.id)

		result["results"].size() shouldBe 1
		result.okIds() shouldContainExactly listOf(first.id.toString())
		onServer("INBOX").getValue("Fictitious message 1") shouldBe (true to false)
	}

	@Test
	fun `a linked message deleted for good from Trash stays as a detached row`() {
		val (first, second) = inboxWith(2)
		links.create(ItemRef(ItemType.EMAIL, first.id), ItemRef(ItemType.EMAIL, second.id), null)

		act("DELETE", first.id).okIds() shouldContainExactly listOf(first.id.toString())
		act("DELETE", first.id).okIds() shouldContainExactly listOf(first.id.toString())

		uidsOnServer("Trash").keys.shouldBeEmpty()
		val kept = reloaded(first)
		kept.uid shouldBe null
		kept.isLocated shouldBe false
		links.linksOf(ItemRef(ItemType.EMAIL, first.id)).size shouldBe 1
		// The link keeps the row, but the id now stands for nothing on the server.
		act("FLAG", first.id).codes() shouldBe listOf("NOT_FOUND")
	}

	@Test
	fun `a detached id is acted on at its located copy`() {
		// Another client moved the message to Projects; the sync of Projects cached it there as a new row, and the
		// INBOX row lost its location.
		remote { store ->
			store.getFolder("Projects").create(Folder.HOLDS_MESSAGES)
			store.getFolder("INBOX").appendMessages(arrayOf(mail(1)))
		}
		folderService.sync(account.id)
		syncAll()
		val (first) = cached("INBOX")
		remote { store ->
			val inbox = store.getFolder("INBOX")
			inbox.open(Folder.READ_WRITE)
			inbox.copyMessages(inbox.messages, store.getFolder("Projects"))
			inbox.messages.forEach { it.setFlag(Flags.Flag.DELETED, true) }
			inbox.close(true)
		}
		sync.syncFolder(folder("Projects").id)
		jdbc.update("update mail_message set uid = null where id = ?", first.id)
		val copy = cached("Projects").single()
		check(copy.id != first.id)

		val result = act("FLAG", first.id)

		result.okIds() shouldContainExactly listOf(first.id.toString())
		onServer("Projects").getValue("Fictitious message 1") shouldBe (false to true)
		reloaded(copy).flagged shouldBe true
	}

	@Test
	fun `a command the server rejects fails the ids with REJECTED and changes nothing`() {
		val (first) = inboxWith(1)
		// The target folder was deleted on the server since the last folder sync.
		remote { it.getFolder("Projects").delete(false) }

		val result = act("MOVE", first.id, targetFolderId = folder("Projects").id)

		result.codes() shouldBe listOf("REJECTED")
		result["results"][0]["detail"].asString() shouldBe MailActionError.REJECTED.message
		uidsOnServer("INBOX").keys shouldBe setOf("Fictitious message 1")
		reloaded(first).folderId shouldBe folder("INBOX").id
	}

	@Test
	fun `a source folder whose UIDVALIDITY changed fails the ids with NOT_FOUND`() {
		val (first) = inboxWith(1)
		jdbc.update("update mail_folder set uidvalidity = uidvalidity + 1 where id = ?", folder("INBOX").id)

		act("READ", first.id).codes() shouldBe listOf("NOT_FOUND")

		onServer("INBOX").getValue("Fictitious message 1") shouldBe (false to false)
		reloaded(first).seen shouldBe false
	}

	@Test
	fun `a source folder deleted on the server fails the ids with NOT_FOUND`() {
		val (first) = inboxWith(1)
		act("MOVE", first.id, targetFolderId = folder("Projects").id)
		remote { it.getFolder("Projects").delete(true) }

		act("READ", first.id).codes() shouldBe listOf("NOT_FOUND")
	}

	@Test
	fun `a move moves the messages still on the server and reports the others as NOT_FOUND`() {
		val (first, second, third) = inboxWith(3)
		deleteOnServer("INBOX", "Fictitious message 2")

		val result = act("ARCHIVE", first.id, second.id, third.id)

		result.codes() shouldBe listOf(null, "NOT_FOUND", null)
		uidsOnServer("Archive").keys shouldBe setOf("Fictitious message 1", "Fictitious message 3")
		reloaded(first).folderId shouldBe folder("Archive").id
		reloaded(third).folderId shouldBe folder("Archive").id
		// The vanished message is not moved in the cache; the next INBOX sync removes it.
		reloaded(second).folderId shouldBe folder("INBOX").id
	}

	@Test
	fun `an action on messages all gone from the server fails each with NOT_FOUND`() {
		val (first) = inboxWith(1)
		deleteOnServer("INBOX", "Fictitious message 1")

		act("DELETE", first.id).codes() shouldBe listOf("NOT_FOUND")
		uidsOnServer("Trash").keys.shouldBeEmpty()
	}

	@Test
	fun `messages of several folders are acted on per folder in one request`() {
		val (first, second) = inboxWith(2)
		act("ARCHIVE", second.id)

		val result = act("FLAG", first.id, second.id)

		result.okIds() shouldContainExactly listOf(first.id.toString(), second.id.toString())
		onServer("INBOX").getValue("Fictitious message 1") shouldBe (false to true)
		onServer("Archive").getValue("Fictitious message 2") shouldBe (false to true)
		reloaded(first).flagged shouldBe true
		reloaded(second).flagged shouldBe true
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
