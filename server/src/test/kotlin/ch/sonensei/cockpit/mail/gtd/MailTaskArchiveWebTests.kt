package ch.sonensei.cockpit.mail.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageRepository
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Mail → task & archive without a mail server: validation, the list, `archive: false`, and archiving outcomes that
 * need no server (already in Archive, no Archive folder). The real move is in [MailTaskArchiveGreenMailTests].
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class MailTaskArchiveWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailMessageRepository,
) : PostgresTestBase() {
	private lateinit var account: MailAccount
	private lateinit var inbox: MailFolder
	private var nextUid = 1L

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
	}

	private fun folder(
		path: String,
		role: MailFolderRole,
	) = folders.save(MailFolder(account.id, path, path).apply { this.role = role })

	private fun message(
		folder: MailFolder,
		subject: String = "Quarterly report",
		messageId: String = "<${UUID.randomUUID()}@example.test>",
	) = messages.save(
		MailMessage(account.id, folder.id, nextUid++).apply {
			this.subject = subject
			this.messageIdHeader = messageId
		},
	)

	private fun count(table: String): Long = jdbc.queryForObject("select count(*) from $table", Long::class.java)!!

	private fun post(body: Any): ResultActionsDsl =
		mockMvc.post("/api/v1/mail/messages/to-tasks") {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = if (body is String) body else json.writeValueAsString(body)
		}

	private fun ok(body: Any): JsonNode =
		post(body)
			.andExpect { status { isOk() } }
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

	@Test
	fun `without an Archive folder the task is created in Next, linked, and the archive failure is reported`() {
		val mail = message(inbox)

		val result = ok(mapOf("ids" to listOf(mail.id)))["results"].single()

		result["id"].asString() shouldBe mail.id.toString()
		result["messageId"].asString() shouldBe mail.id.toString()
		result["task"]["title"].asString() shouldBe "Quarterly report"
		result["task"]["list"].asString() shouldBe "NEXT"
		result["archived"].asBoolean() shouldBe false
		result["archiveErrorCode"].asString() shouldBe "NO_ROLE_FOLDER"
		getJson("/api/v1/tasks/${result["task"]["id"].asString()}")["list"].asString() shouldBe "NEXT"
		val link = getJson("/api/v1/links?type=TASK&id=${result["task"]["id"].asString()}")["items"].single()
		link["id"].asString() shouldBe result["linkId"].asString()
		messages.findById(mail.id).orElseThrow().folderId shouldBe inbox.id
	}

	@Test
	fun `several mails, one already archived, keep request order and get one task each`() {
		val archive = folder("Archive", MailFolderRole.ARCHIVE)
		val archived = message(archive, "Already filed")
		val other = message(inbox, "Still in Inbox")

		val results = ok(mapOf("ids" to listOf(archived.id, other.id, archived.id)))["results"]

		results.size() shouldBe 2
		results.items().map { it["id"].asString() } shouldBe listOf(archived.id.toString(), other.id.toString())
		results.items().map { it["task"]["title"].asString() } shouldBe listOf("Already filed", "Still in Inbox")
		results[0]["archived"].asBoolean() shouldBe true
		results[0]["archiveErrorCode"].isNull shouldBe true
		count("task") shouldBe 2
		count("link") shouldBe 2
	}

	@Test
	fun `archive false and list INBOX create an Inbox task and leave the mail where it is`() {
		folder("Archive", MailFolderRole.ARCHIVE)
		val mail = message(inbox)

		val result = ok(mapOf("ids" to listOf(mail.id), "list" to "INBOX", "archive" to false))["results"].single()

		result["task"]["list"].asString() shouldBe "INBOX"
		result["archived"].asBoolean() shouldBe false
		result["archiveErrorCode"].isNull shouldBe true
		messages.findById(mail.id).orElseThrow().folderId shouldBe inbox.id
	}

	@Test
	fun `an unknown id gives 404 and creates nothing, also for the known ids`() {
		val mail = message(inbox)

		post(mapOf("ids" to listOf(mail.id, UUID.randomUUID())))
			.andExpect {
				status { isNotFound() }
				content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			}

		count("task") shouldBe 0
		count("link") shouldBe 0
	}

	@Test
	fun `invalid requests give 400 and create nothing`() {
		val mail = message(inbox)

		post(mapOf("ids" to emptyList<UUID>())).andExpect { status { isBadRequest() } }
		post(mapOf<String, Any>()).andExpect { status { isBadRequest() } }
		post("""{"ids":[null]}""").andExpect { status { isBadRequest() } }
		post("""{"ids":["not-a-uuid"]}""").andExpect { status { isBadRequest() } }
		post(mapOf("ids" to listOf(mail.id), "list" to "WAITING")).andExpect { status { isBadRequest() } }
		post(mapOf("ids" to listOf(mail.id), "list" to "SCHEDULED")).andExpect { status { isBadRequest() } }
		post(mapOf("ids" to listOf(mail.id), "list" to "NOPE")).andExpect { status { isBadRequest() } }
		post(mapOf("ids" to (1..101).map { UUID.randomUUID() })).andExpect { status { isBadRequest() } }

		count("task") shouldBe 0
	}

	@Test
	fun `the old id of a moved mail and its current id share one task linked to the current copy`() {
		val archive = folder("Archive", MailFolderRole.ARCHIVE)
		val original = message(inbox, messageId = "<moved-to-tasks@example.test>")
		messages.save(original.apply { detach() })
		val copy = message(archive, messageId = "<moved-to-tasks@example.test>")

		val results = ok(mapOf("ids" to listOf(original.id, copy.id)))["results"]

		results.items().map { it["id"].asString() } shouldBe listOf(original.id.toString(), copy.id.toString())
		results.items().map { it["messageId"].asString() } shouldBe listOf(copy.id.toString(), copy.id.toString())
		results[0]["task"]["id"].asString() shouldBe results[1]["task"]["id"].asString()
		results.items().map { it["archived"].asBoolean() } shouldBe listOf(true, true)
		count("task") shouldBe 1
		count("link") shouldBe 1
		val link = getJson("/api/v1/links?type=TASK&id=${results[0]["task"]["id"].asString()}")["items"].single()
		val mailEnd = if (link["source"]["type"].asString() == "EMAIL") link["source"] else link["target"]
		mailEnd["id"].asString() shouldBe copy.id.toString()
	}

	@Test
	fun `a mail that is no longer on the server among known ones gives 404 and creates nothing`() {
		val known = message(inbox, "Still here")
		val gone = message(inbox, "Gone")
		messages.save(gone.apply { detach() })

		post(mapOf("ids" to listOf(known.id, gone.id)))
			.andExpect {
				status { isNotFound() }
				content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			}

		count("task") shouldBe 0
		count("link") shouldBe 0
	}

	@Test
	fun `SOMEDAY and an explicit NEXT are accepted lists`() {
		val someday = message(inbox, "Maybe later")
		val next = message(inbox, "Do soon")

		val somedayResult =
			ok(mapOf("ids" to listOf(someday.id), "list" to "SOMEDAY", "archive" to false))["results"].single()
		val nextResult = ok(mapOf("ids" to listOf(next.id), "list" to "NEXT", "archive" to false))["results"].single()

		somedayResult["task"]["list"].asString() shouldBe "SOMEDAY"
		getJson("/api/v1/tasks/${somedayResult["task"]["id"].asString()}")["list"].asString() shouldBe "SOMEDAY"
		nextResult["task"]["list"].asString() shouldBe "NEXT"
		getJson("/api/v1/tasks/${nextResult["task"]["id"].asString()}")["list"].asString() shouldBe "NEXT"
	}

	@Test
	fun `exactly 100 ids pass validation and are resolved`() {
		val mail = message(inbox)
		// 99 unknown ids plus one known: the size limit admits the request, the unknown ids then give 404.
		post(mapOf("ids" to listOf(mail.id) + (1..99).map { UUID.randomUUID() }))
			.andExpect { status { isNotFound() } }

		count("task") shouldBe 0
	}

	@Test
	fun `a request without CSRF token is rejected`() {
		val mail = message(inbox)

		mockMvc
			.post("/api/v1/mail/messages/to-tasks") {
				contentType = MediaType.APPLICATION_JSON
				content = json.writeValueAsString(mapOf("ids" to listOf(mail.id)))
			}.andExpect { status { isForbidden() } }

		count("task") shouldBe 0
	}
}
