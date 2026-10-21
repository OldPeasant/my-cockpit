package ch.sonensei.cockpit.mail.api

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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Triage actions with the real service and database but without a mail server: everything decided before the server
 * is contacted (validation of the target folder, unknown ids, missing role folders, other accounts, no-op moves, and
 * which copy a detached id stands for). The accounts point at unreachable hosts, so a test that reached IMAP would
 * report CONNECTION_FAILED instead of the expected outcome.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class MailActionWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailMessageRepository,
) : PostgresTestBase() {
	private lateinit var work: MailAccount
	private lateinit var home: MailAccount
	private lateinit var workInbox: MailFolder
	private lateinit var workArchive: MailFolder
	private lateinit var homeInbox: MailFolder
	private var nextUid = 1L

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from mail_account")
		work = account("Work", "ann@work.example")
		home = account("Home", "ann@home.example")
		workInbox = folder(work, "INBOX", MailFolderRole.INBOX)
		workArchive = folder(work, "Archive", MailFolderRole.ARCHIVE)
		homeInbox = folder(home, "INBOX", MailFolderRole.INBOX)
	}

	private fun account(
		name: String,
		email: String,
	) = accounts.save(
		MailAccount(
			MailAccountType.IMAP_SMTP,
			name,
			email,
			"imap.unreachable.example.test",
			993,
			MailSecurity.SSL_TLS,
			"smtp.unreachable.example.test",
			587,
			MailSecurity.STARTTLS,
			email,
		),
	)

	private fun folder(
		account: MailAccount,
		path: String,
		role: MailFolderRole?,
		selectable: Boolean = true,
	) = folders.save(
		MailFolder(account.id, path, path).apply {
			this.role = role
			this.selectable = selectable
		},
	)

	private fun message(
		folder: MailFolder,
		messageId: String = "<${UUID.randomUUID()}@example.test>",
		uid: Long? = nextUid++,
	) = messages.save(
		MailMessage(folder.accountId, folder.id, uid).apply {
			subject = "Fictitious subject $messageId"
			messageIdHeader = messageId
		},
	)

	private fun post(body: Map<String, Any?>): MockHttpServletResponse =
		mockMvc
			.post("/api/v1/mail/messages/actions") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = json.writeValueAsString(body.filterValues { it != null })
			}.andReturn()
			.response

	private fun act(
		action: String,
		vararg ids: UUID,
		targetFolderId: UUID? = null,
	): MockHttpServletResponse = post(mapOf("ids" to ids.toList(), "action" to action, "targetFolderId" to targetFolderId))

	private fun MockHttpServletResponse.body(): JsonNode = json.readTree(contentAsString)

	private fun MockHttpServletResponse.results(): List<JsonNode> {
		status shouldBe 200
		return body()["results"].toList()
	}

	private fun MockHttpServletResponse.errorFields(): List<String> = body()["errors"].toList().map { it["field"].asString() }

	private fun List<JsonNode>.codes(): List<String?> = map { if (it["errorCode"].isNull) null else it["errorCode"].asString() }

	@Test
	fun `MOVE to an unknown folder is a 404`() {
		val message = message(workInbox)

		val response = act("MOVE", message.id, targetFolderId = UUID.randomUUID())

		response.status shouldBe 404
		response.contentType shouldBe MediaType.APPLICATION_PROBLEM_JSON_VALUE
		response.body()["detail"].asString() shouldBe "Mail folder not found"
	}

	@Test
	fun `MOVE to a folder that cannot hold messages is a 400`() {
		val message = message(workInbox)
		val parent = folder(work, "Clients", null, selectable = false)

		val response = act("MOVE", message.id, targetFolderId = parent.id)

		response.status shouldBe 400
		response.errorFields() shouldBe listOf("targetFolderId")
	}

	@Test
	fun `targetFolderId is required for MOVE and refused for every other action`() {
		val message = message(workInbox)

		act("MOVE", message.id).let {
			it.status shouldBe 400
			it.errorFields() shouldBe listOf("targetFolderId")
		}
		MailAction.entries.filter { it != MailAction.MOVE }.forEach { action ->
			act(action.name, message.id, targetFolderId = workArchive.id).let {
				it.status shouldBe 400
				it.errorFields() shouldBe listOf("targetFolderId")
			}
		}
	}

	@Test
	fun `request validation errors are 400 Problem Details`() {
		post(mapOf("ids" to emptyList<UUID>(), "action" to "READ")).let {
			it.status shouldBe 400
			it.contentType shouldBe MediaType.APPLICATION_PROBLEM_JSON_VALUE
			it.errorFields() shouldBe listOf("ids")
		}
		post(mapOf("ids" to (0..MailActionRequest.MAX_IDS).map { UUID.randomUUID() }, "action" to "READ")).let {
			it.status shouldBe 400
			it.errorFields() shouldBe listOf("ids")
		}
		post(mapOf("ids" to listOf(UUID.randomUUID(), null), "action" to "READ")).let {
			it.status shouldBe 400
			it.errorFields() shouldBe listOf("ids")
		}
		post(mapOf("ids" to listOf(UUID.randomUUID()))).let {
			it.status shouldBe 400
			it.errorFields() shouldBe listOf("action")
		}
		post(mapOf("ids" to listOf(UUID.randomUUID()), "action" to "SHRED")).status shouldBe 400
	}

	@Test
	fun `unknown ids fail with NOT_FOUND and duplicates count once, in request order`() {
		val unknown = UUID.randomUUID()
		val other = UUID.randomUUID()

		val results = act("READ", unknown, other, unknown).results()

		results.map { it["id"].asString() } shouldBe listOf(unknown, other).map { it.toString() }
		results.codes() shouldBe listOf("NOT_FOUND", "NOT_FOUND")
		results.map { it["ok"].asBoolean() } shouldBe listOf(false, false)
		results[0]["detail"].asString() shouldBe MailActionError.NOT_FOUND.message
	}

	@Test
	fun `a message gone from its folder without another copy is NOT_FOUND`() {
		val detached = message(workInbox, uid = null)

		act("FLAG", detached.id).results().codes() shouldBe listOf("NOT_FOUND")
	}

	@Test
	fun `archiving what is already in Archive succeeds without contacting the server`() {
		val archived = message(workArchive)

		act("ARCHIVE", archived.id).results().codes() shouldBe listOf(null)
		messages.findById(archived.id).orElseThrow().folderId shouldBe workArchive.id
	}

	@Test
	fun `MOVE to the folder the message is in succeeds as a no-op`() {
		val message = message(workInbox)

		act("MOVE", message.id, targetFolderId = workInbox.id).results().codes() shouldBe listOf(null)
	}

	@Test
	fun `a detached id is acted on at its located copy`() {
		val messageId = "<moved-elsewhere@example.test>"
		val detached = message(workInbox, messageId, uid = null)
		val copy = message(workArchive, messageId)

		val results = act("ARCHIVE", detached.id).results()

		// The id of the result is the requested one; the located copy is already in Archive, so nothing is to do.
		results.map { it["id"].asString() } shouldBe listOf(detached.id.toString())
		results.codes() shouldBe listOf(null)
		messages.findById(copy.id).orElseThrow().folderId shouldBe workArchive.id
	}

	@Test
	fun `actions needing a missing or non-selectable role folder fail with NO_ROLE_FOLDER`() {
		val home = message(homeInbox)
		folder(this.home, "Trash", MailFolderRole.TRASH, selectable = false)

		listOf("ARCHIVE", "DELETE", "SPAM").forEach { action ->
			act(action, home.id).results().codes() shouldBe listOf("NO_ROLE_FOLDER")
		}
	}

	@Test
	fun `MOVE to a folder of another account fails with OTHER_ACCOUNT`() {
		val message = message(workInbox)

		val result = act("MOVE", message.id, targetFolderId = homeInbox.id).results().single()

		result["ok"].asBoolean() shouldBe false
		result["errorCode"].asString() shouldBe "OTHER_ACCOUNT"
		result["detail"].asString() shouldBe MailActionError.OTHER_ACCOUNT.message
		messages.findById(message.id).orElseThrow().folderId shouldBe workInbox.id
	}

	@Test
	fun `ids of several folders and accounts keep their request order and their own outcome`() {
		val archived = message(workArchive)
		val homeMessage = message(homeInbox)
		val unknown = UUID.randomUUID()

		val results = act("ARCHIVE", homeMessage.id, unknown, archived.id).results()

		results.map { it["id"].asString() } shouldBe listOf(homeMessage.id, unknown, archived.id).map { it.toString() }
		results.codes() shouldBe listOf("NO_ROLE_FOLDER", "NOT_FOUND", null)
	}

	@Test
	fun `a mail server error fails the id with its error code and leaves the cache unchanged`() {
		// The account has no stored password, so the pool cannot log in.
		val message = message(workInbox)

		val result = act("READ", message.id).results().single()

		result["ok"].asBoolean() shouldBe false
		result["errorCode"].asString() shouldBe "AUTHENTICATION_FAILED"
		messages.findById(message.id).orElseThrow().seen shouldBe false
	}

	@Test
	fun `a POST without CSRF token is forbidden`() {
		mockMvc
			.post("/api/v1/mail/messages/actions") {
				contentType = MediaType.APPLICATION_JSON
				content = """{"ids":["${UUID.randomUUID()}"],"action":"READ"}"""
			}.andExpect { status { isForbidden() } }
	}

	@Test
	@WithAnonymousUser
	fun `actions need a login`() {
		act("READ", UUID.randomUUID()).status shouldBe 401
	}
}
