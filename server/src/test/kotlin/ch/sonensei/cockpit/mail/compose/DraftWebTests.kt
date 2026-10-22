package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailIdentity
import ch.sonensei.cockpit.mail.model.MailIdentityRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageRepository
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Compose with the real service and database but without a mail server: everything decided before the server is
 * contacted (If-Match, unknown drafts, messages outside Drafts, missing Drafts folder, identities of other accounts).
 * The accounts point at unreachable hosts, so a request that reached IMAP or SMTP would fail with 502 instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class DraftWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val identities: MailIdentityRepository,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailMessageRepository,
) : PostgresTestBase() {
	private lateinit var work: MailAccount
	private lateinit var home: MailAccount
	private lateinit var workDrafts: MailFolder
	private lateinit var workInbox: MailFolder
	private lateinit var draft: MailMessage

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from mail_account")
		work = account("jane.doe@work.example.test")
		home = account("jane@home.example.test")
		workDrafts = folder(work, "Drafts", MailFolderRole.DRAFTS)
		workInbox = folder(work, "INBOX", MailFolderRole.INBOX)
		draft = message(workDrafts, uid = 42)
	}

	private fun account(email: String) =
		accounts.save(
			MailAccount(
				MailAccountType.IMAP_SMTP,
				"Account $email",
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
		uid: Long,
	) = messages.save(
		MailMessage(folder.accountId, folder.id, uid).apply {
			subject = "Fictitious draft"
			messageIdHeader = "<${UUID.randomUUID()}@work.example.test>"
			to =
				listOf(
					ch.sonensei.cockpit.mail.model
						.MailAddress("bob@example.test"),
				)
		},
	)

	private fun request(
		method: String,
		path: String,
		ifMatch: String? = null,
		body: Map<String, Any?>? = null,
	): MockHttpServletResponse {
		val dsl: MockHttpServletRequestDsl.() -> Unit = {
			with(csrf())
			ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
			body?.let {
				contentType = MediaType.APPLICATION_JSON
				content = json.writeValueAsString(it)
			}
		}
		return when (method) {
			"POST" -> mockMvc.post(path, dsl = dsl)
			"PUT" -> mockMvc.put(path, dsl = dsl)
			"DELETE" -> mockMvc.delete(path, dsl = dsl)
			else -> mockMvc.get(path, dsl = dsl)
		}.andReturn().response
	}

	private fun upload(
		id: UUID,
		ifMatch: String?,
	): MockHttpServletResponse =
		mockMvc
			.multipart("/api/v1/mail/drafts/$id/attachments") {
				file(MockMultipartFile("file", "notes.txt", "text/plain", "Fictitious notes".toByteArray()))
				ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
				with(csrf())
			}.andReturn()
			.response

	private fun MockHttpServletResponse.body(): JsonNode = json.readTree(contentAsString)

	private fun MockHttpServletResponse.errorFields(): List<String> = body()["errors"].toList().map { it["field"].asString() }

	/** Every request that changes or sends a draft, with [ifMatch]. */
	private fun changes(
		id: UUID,
		ifMatch: String?,
	): List<MockHttpServletResponse> =
		listOf(
			request("PUT", "/api/v1/mail/drafts/$id", ifMatch, mapOf("subject" to "Changed")),
			upload(id, ifMatch),
			request("DELETE", "/api/v1/mail/drafts/$id", ifMatch),
			request("POST", "/api/v1/mail/drafts/$id/send", ifMatch),
		)

	@Test
	fun `changing or sending a draft without If-Match is 428`() {
		changes(draft.id, null).map { it.status } shouldContainExactly listOf(428, 428, 428, 428)
		changes(draft.id, " ").map { it.status } shouldContainExactly listOf(428, 428, 428, 428)
	}

	@Test
	fun `changing or sending with a stale If-Match is 412`() {
		changes(draft.id, "\"41\"").map { it.status } shouldContainExactly listOf(412, 412, 412, 412)
	}

	@Test
	fun `a weak ETag never matches`() {
		changes(draft.id, "W/\"42\"").map { it.status } shouldContainExactly listOf(412, 412, 412, 412)
	}

	@Test
	fun `an unknown draft is 404 whatever the If-Match`() {
		val unknown = UUID.fromString("01900000-0000-7000-8000-00000000dead")

		changes(unknown, "\"42\"").map { it.status } shouldContainExactly listOf(404, 404, 404, 404)
		request("GET", "/api/v1/mail/drafts/$unknown").status shouldBe 404
	}

	@Test
	fun `a message outside the Drafts folder is no draft`() {
		val received = message(workInbox, uid = 7)

		changes(received.id, "\"7\"").map { it.status } shouldContainExactly listOf(404, 404, 404, 404)
		request("GET", "/api/v1/mail/drafts/${received.id}").status shouldBe 404
	}

	@Test
	fun `a draft no longer on the server is no draft`() {
		draft.detach()
		messages.save(draft)

		request("POST", "/api/v1/mail/drafts/${draft.id}/send", "\"42\"").status shouldBe 404
	}

	@Test
	fun `creating a draft for an unknown account is 404`() {
		request("POST", "/api/v1/mail/drafts", body = mapOf("accountId" to UUID.fromString("01900000-0000-7000-8000-00000000beef")))
			.status shouldBe 404
	}

	@Test
	fun `creating a draft for an account without Drafts folder is 409`() {
		request("POST", "/api/v1/mail/drafts", body = mapOf("accountId" to home.id)).status shouldBe 409

		folder(home, "Drafts", MailFolderRole.DRAFTS, selectable = false)
		request("POST", "/api/v1/mail/drafts", body = mapOf("accountId" to home.id)).status shouldBe 409
	}

	@Test
	fun `replying to a message of an account without Drafts folder is 409`() {
		val homeInbox = folder(home, "INBOX", MailFolderRole.INBOX)
		val received = message(homeInbox, uid = 3)

		request("POST", "/api/v1/mail/messages/${received.id}/reply").status shouldBe 409
	}

	@Test
	fun `replying to an unknown message is 404`() {
		request("POST", "/api/v1/mail/messages/01900000-0000-7000-8000-00000000dead/forward").status shouldBe 404
	}

	@Test
	fun `an identity of another account is rejected`() {
		val other = identities.save(MailIdentity(home.id, "Jane Home", "jane@home.example.test"))

		val response = request("POST", "/api/v1/mail/drafts", body = mapOf("accountId" to work.id, "identityId" to other.id))

		response.status shouldBe 400
		response.errorFields() shouldContainExactly listOf("identityId")
	}

	@Test
	fun `a new draft cannot keep attachments by partId`() {
		val response =
			request("POST", "/api/v1/mail/drafts", body = mapOf("accountId" to work.id, "attachments" to listOf(mapOf("partId" to "2"))))

		response.status shouldBe 400
		response.errorFields() shouldContainExactly listOf("attachments")
	}

	@Test
	fun `correspondents need no mail server`() {
		request("GET", "/api/v1/mail/correspondents").status shouldBe 200
		request("GET", "/api/v1/mail/correspondents?limit=0").status shouldBe 400
	}
}
