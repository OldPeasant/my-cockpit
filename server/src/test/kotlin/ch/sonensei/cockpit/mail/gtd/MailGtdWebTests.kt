package ch.sonensei.cockpit.mail.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.mail.api.MailBodyAttachmentStore
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailBodyCache
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailIdentity
import ch.sonensei.cockpit.mail.model.MailIdentityRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageRepository
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@WithCockpitUser
class MailGtdWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val identities: MailIdentityRepository,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailMessageRepository,
	@Autowired private val bodies: MailBodyCache,
	@Autowired private val attachments: MailBodyAttachmentStore,
) : PostgresTestBase() {
	private lateinit var account: MailAccount
	private lateinit var inbox: MailFolder
	private lateinit var sent: MailFolder
	private lateinit var archive: MailFolder
	private var nextUid = 1L

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
		account =
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
		inbox = folder("INBOX", MailFolderRole.INBOX)
		sent = folder("Sent", MailFolderRole.SENT)
		archive = folder("Archive", MailFolderRole.ARCHIVE)
	}

	private fun folder(
		path: String,
		role: MailFolderRole,
	) = folders.save(MailFolder(account.id, path, path).apply { this.role = role })

	private fun message(
		folder: MailFolder,
		subject: String? = "Quarterly report",
		from: List<MailAddress> = listOf(MailAddress("bob@example.test", "Bob Example")),
		to: List<MailAddress> = listOf(MailAddress("ann@work.example", "Ann Example")),
		messageId: String = "<${UUID.randomUUID()}@example.test>",
	) = messages.save(
		MailMessage(account.id, folder.id, nextUid++).apply {
			this.subject = subject
			this.from = from
			this.to = to
			this.sentAt = Instant.parse("2026-02-20T09:30:00Z")
			this.receivedAt = Instant.parse("2026-02-20T09:31:00Z")
			this.messageIdHeader = messageId
		},
	)

	private fun post(
		id: Any,
		action: String,
	) = mockMvc.post("/api/v1/mail/messages/$id/$action") { with(csrf()) }

	private fun created(
		id: UUID,
		action: String,
	): JsonNode =
		post(id, action)
			.andExpect { status { isCreated() } }
			.andReturn()
			.response
			.let { response ->
				json.readTree(response.contentAsString).also {
					response.getHeader(HttpHeaders.LOCATION)!! shouldEndWith "/${it["id"].asString()}"
				}
			}

	private fun getJson(url: String): JsonNode =
		mockMvc
			.get(url)
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let(json::readTree)

	/** The linked items of [type]:[id] as seen from it (the other end of each link). */
	private fun linkedEnds(
		type: String,
		id: String,
	): List<JsonNode> {
		val items = getJson("/api/v1/links?type=$type&id=$id")["items"]
		return (0 until items.size()).map { items[it] }.map { link ->
			if (link["source"]["id"].asString() == id) link["target"] else link["source"]
		}
	}

	@Test
	fun `to-task creates an Inbox task linked both ways to the mail`() {
		val mail = message(inbox)

		val result = created(mail.id, "to-task")

		result["type"].asString() shouldBe "TASK"
		result["title"].asString() shouldBe "Quarterly report"
		result["messageId"].asString() shouldBe mail.id.toString()
		val taskId = result["id"].asString()
		val task = getJson("/api/v1/tasks/$taskId")
		task["list"].asString() shouldBe "INBOX"
		task["title"].asString() shouldBe "Quarterly report"
		val fromTask = linkedEnds("TASK", taskId).single()
		fromTask["type"].asString() shouldBe "EMAIL"
		fromTask["id"].asString() shouldBe mail.id.toString()
		fromTask["deepLink"].asString() shouldBe "/mail/messages/${mail.id}"
		val fromMail = linkedEnds("EMAIL", mail.id.toString()).single()
		fromMail["type"].asString() shouldBe "TASK"
		fromMail["id"].asString() shouldBe taskId
	}

	@Test
	fun `a mail without subject gives a placeholder title`() {
		val mail = message(inbox, subject = "  ")

		created(mail.id, "to-task")["title"].asString() shouldBe "(no subject)"
	}

	@Test
	fun `to-waiting from a sent mail waits for its recipients since the mail's date`() {
		val mail =
			message(
				sent,
				from = listOf(MailAddress("ann@work.example", "Ann Example")),
				to = listOf(MailAddress("bob@example.test", "Bob Example"), MailAddress("carol@example.test")),
			)

		val result = created(mail.id, "to-waiting")

		val task = getJson("/api/v1/tasks/${result["id"].asString()}")
		task["list"].asString() shouldBe "WAITING"
		task["waitingForText"].asString() shouldBe "Bob Example, carol@example.test"
		task["waitingSince"].asString() shouldBe "2026-02-20"
		linkedEnds("TASK", result["id"].asString()).single()["id"].asString() shouldBe mail.id.toString()
	}

	@Test
	fun `a mail from one of the account's identities counts as sent outside the Sent folder`() {
		identities.save(MailIdentity(account.id, "Ann Alias", "Ann.Alias@work.example"))
		val mail =
			message(
				archive,
				from = listOf(MailAddress("ann.alias@work.example")),
				to = listOf(MailAddress("dave@example.test", "Dave Example")),
			)

		val result = created(mail.id, "to-waiting")

		getJson("/api/v1/tasks/${result["id"].asString()}")["waitingForText"].asString() shouldBe "Dave Example"
	}

	@Test
	fun `to-waiting from a received mail waits for its sender`() {
		val mail = message(inbox)

		val result = created(mail.id, "to-waiting")

		getJson("/api/v1/tasks/${result["id"].asString()}")["waitingForText"].asString() shouldBe "Bob Example"
	}

	@Test
	fun `to-note creates a note with the mail's headers and text, linked to the mail`() {
		val mail = message(inbox)
		bodies.put(mail.id, "Hello Ann,\nthe report is attached.", null)
		attachments.save(mail.id, emptyList())

		val result = created(mail.id, "to-note")

		result["type"].asString() shouldBe "NOTE"
		val note = getJson("/api/v1/notes/${result["id"].asString()}")
		note["title"].asString() shouldBe "Quarterly report"
		val body = note["bodyMd"].asString()
		body shouldContain "**From:** Bob Example <bob@example.test>"
		body shouldContain "**To:** Ann Example <ann@work.example>"
		body shouldContain "**Date:** 2026-02-20 10:30"
		body shouldContain "Hello Ann,\nthe report is attached."
		linkedEnds("NOTE", result["id"].asString()).single()["id"].asString() shouldBe mail.id.toString()
	}

	@Test
	fun `to-note uses the HTML part as text when there is no plain text`() {
		val mail = message(inbox)
		bodies.put(mail.id, null, "<p>Hello <b>Ann</b></p>")
		attachments.save(mail.id, emptyList())

		val result = created(mail.id, "to-note")

		getJson("/api/v1/notes/${result["id"].asString()}")["bodyMd"].asString() shouldContain "Hello Ann"
	}

	@Test
	fun `links survive a move that gives the message a new row, re-resolved by Message-ID`() {
		val original = message(inbox, messageId = "<moved@example.test>")
		val taskId = created(original.id, "to-task")["id"].asString()

		// The sync no longer finds the message in the Inbox and caches its copy in the Archive as a new row.
		messages.save(original.apply { detach() })
		val copy = message(archive, messageId = "<moved@example.test>")

		val end = linkedEnds("TASK", taskId).single()
		end["available"].asBoolean() shouldBe true
		end["id"].asString() shouldBe original.id.toString()
		end["title"].asString() shouldBe "Quarterly report"
		end["deepLink"].asString() shouldBe "/mail/messages/${copy.id}"
	}

	@Test
	fun `links survive a move that keeps the row`() {
		val mail = message(inbox)
		val taskId = created(mail.id, "to-task")["id"].asString()

		messages.save(mail.apply { locate(archive.id, 500) })

		val end = linkedEnds("TASK", taskId).single()
		end["available"].asBoolean() shouldBe true
		end["deepLink"].asString() shouldBe "/mail/messages/${mail.id}"
	}

	@Test
	fun `a moved message requested by its old id links its current copy`() {
		val original = message(inbox, messageId = "<old@example.test>")
		messages.save(original.apply { detach() })
		val copy = message(archive, messageId = "<old@example.test>")

		val result = created(original.id, "to-task")

		result["messageId"].asString() shouldBe copy.id.toString()
		linkedEnds("TASK", result["id"].asString()).single()["id"].asString() shouldBe copy.id.toString()
	}

	@Test
	fun `a message that is gone gives 404 and creates nothing`() {
		val gone = message(inbox, messageId = "<gone@example.test>")
		messages.save(gone.apply { detach() })

		for (action in listOf("to-task", "to-waiting", "to-note")) {
			post(gone.id, action).andExpect { status { isNotFound() } }
			post(UUID.randomUUID(), action).andExpect { status { isNotFound() } }
		}
		jdbc.queryForObject("select count(*) from task", Long::class.java) shouldBe 0
		jdbc.queryForObject("select count(*) from note", Long::class.java) shouldBe 0
		jdbc.queryForObject("select count(*) from link", Long::class.java) shouldBe 0
	}

	@Test
	fun `a malformed id gives 400`() {
		post("not-a-uuid", "to-task").andExpect { status { isBadRequest() } }
	}
}
