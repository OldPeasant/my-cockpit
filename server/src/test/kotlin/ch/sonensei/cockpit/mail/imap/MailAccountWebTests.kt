package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnection
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.core.oauth.infra.OAuthConnectionRepository
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** The account and folder API without a mail server. */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class MailAccountWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val connections: OAuthConnectionRepository,
) : PostgresTestBase() {
	private lateinit var googleConnection: UUID
	private lateinit var microsoftConnection: UUID

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from mail_account")
		jdbc.update("delete from oauth_connection")
		googleConnection = connection(OAuthProvider.GOOGLE, "jane@gmail.example")
		microsoftConnection = connection(OAuthProvider.MICROSOFT, null)
	}

	private fun connection(
		provider: OAuthProvider,
		email: String?,
	): UUID = connections.save(OAuthConnection(provider, "subject-${provider.slug}", email, "Jane", "openid", null)).id

	private val imapSmtpAccount =
		mapOf(
			"type" to "IMAP_SMTP",
			"displayName" to "Work",
			"emailAddress" to "jane.doe@example.test",
			"imap" to mapOf("host" to "imap.example.test", "port" to 993, "security" to "SSL_TLS"),
			"smtp" to mapOf("host" to "smtp.example.test", "port" to 587, "security" to "STARTTLS"),
			"username" to "jane",
			"password" to "fictitious-secret-1",
		)

	private fun create(body: Map<String, Any?>) =
		mockMvc.post(MailAccountController.BASE) {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = json.writeValueAsString(body)
		}

	private fun createOk(body: Map<String, Any?> = imapSmtpAccount): JsonNode =
		json.readTree(
			create(body)
				.andExpect { status { isCreated() } }
				.andReturn()
				.response.contentAsString,
		)

	private fun patch(
		id: Any,
		body: Map<String, Any?>,
		ifMatch: String? = "\"0\"",
	) = mockMvc.patch("${MailAccountController.BASE}/$id") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = json.writeValueAsString(body)
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun ResultActionsDsl.fields(
		array: String,
		field: String,
	): List<String> {
		val items = json.readTree(andReturn().response.contentAsString)[array]
		return (0 until items.size()).map { items[it][field].asString() }
	}

	@Test
	fun `create returns the account without the password and stores it encrypted`() {
		val response =
			create(imapSmtpAccount)
				.andExpect {
					status { isCreated() }
					header { string(HttpHeaders.ETAG, "\"0\"") }
					jsonPath("$.type") { value("IMAP_SMTP") }
					jsonPath("$.imap.host") { value("imap.example.test") }
					jsonPath("$.smtp.security") { value("STARTTLS") }
					jsonPath("$.hasPassword") { value(true) }
					jsonPath("$.password") { doesNotExist() }
					jsonPath("$.notifyNewMail") { value(true) }
					jsonPath("$.syncWindowMonths") { value(12) }
				}.andReturn()
				.response
		response.contentAsString shouldNotContain "fictitious-secret-1"
		val id = json.readTree(response.contentAsString)["id"].asString()
		response.getHeader(HttpHeaders.LOCATION) shouldBe "http://localhost${MailAccountController.BASE}/$id"
		val stored = jdbc.queryForObject("select password from mail_account where id = ?::uuid", String::class.java, id)
		stored shouldNotContain "fictitious-secret-1"
		accounts
			.findById(UUID.fromString(id))
			.get()
			.password!!
			.reveal() shouldBe "fictitious-secret-1"
	}

	@Test
	fun `list and get never contain the password`() {
		val id = createOk()["id"].asString()

		mockMvc
			.get(MailAccountController.BASE)
			.andExpect {
				status { isOk() }
				jsonPath("$.items.length()") { value(1) }
				jsonPath("$.items[0].id") { value(id) }
			}.andReturn()
			.response.contentAsString shouldNotContain "fictitious-secret-1"
		mockMvc
			.get("${MailAccountController.BASE}/$id")
			.andExpect {
				status { isOk() }
				header { string(HttpHeaders.ETAG, "\"0\"") }
			}.andReturn()
			.response.contentAsString shouldNotContain "fictitious-secret-1"
	}

	@Test
	fun `IMAP_SMTP accounts need servers, username and password`() {
		create(mapOf("type" to "IMAP_SMTP", "emailAddress" to "jane.doe@example.test"))
			.andExpect { status { isBadRequest() } }
			.fields("errors", "field") shouldBe listOf("imapHost", "password", "smtpHost", "username")
	}

	@Test
	fun `Bean Validation rejects bad ports, emails and missing type`() {
		create(
			imapSmtpAccount +
				mapOf("type" to null, "emailAddress" to "not-an-email", "imap" to mapOf("host" to "", "port" to 0, "security" to "SSL_TLS")),
		).andExpect { status { isBadRequest() } }
			.fields("errors", "field") shouldBe listOf("emailAddress", "imap.host", "imap.port", "type")
	}

	@Test
	fun `an OAuth connection is rejected on password accounts`() {
		create(imapSmtpAccount + ("oauthConnectionId" to googleConnection.toString())).andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("oauthConnectionId") }
		}
	}

	@Test
	fun `a Google account defaults to Gmail servers and the connection's email as username`() {
		createOk(
			mapOf("type" to "GOOGLE", "emailAddress" to "jane@gmail.example", "oauthConnectionId" to googleConnection.toString()),
		).let {
			it["imap"]["host"].asString() shouldBe "imap.gmail.com"
			it["imap"]["port"].asInt() shouldBe 993
			it["smtp"]["host"].asString() shouldBe "smtp.gmail.com"
			it["username"].asString() shouldBe "jane@gmail.example"
			it["hasPassword"].asBoolean() shouldBe false
			it["displayName"].asString() shouldBe "jane@gmail.example"
		}
	}

	@Test
	fun `a Microsoft account without connection email uses the account address as username`() {
		createOk(
			mapOf("type" to "MICROSOFT", "emailAddress" to "jane@outlook.example", "oauthConnectionId" to microsoftConnection.toString()),
		).let {
			it["imap"]["host"].asString() shouldBe "outlook.office365.com"
			it["smtp"]["security"].asString() shouldBe "STARTTLS"
			it["username"].asString() shouldBe "jane@outlook.example"
		}
	}

	@Test
	fun `OAuth accounts need a connection of their provider and no password`() {
		create(mapOf("type" to "GOOGLE", "emailAddress" to "jane@gmail.example")).andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("oauthConnectionId") }
		}
		create(
			mapOf("type" to "GOOGLE", "emailAddress" to "jane@gmail.example", "oauthConnectionId" to microsoftConnection.toString()),
		).andExpect { status { isBadRequest() } }
		create(
			mapOf("type" to "GOOGLE", "emailAddress" to "jane@gmail.example", "oauthConnectionId" to UuidV7.generate().toString()),
		).andExpect { status { isBadRequest() } }
		create(
			mapOf(
				"type" to "GOOGLE",
				"emailAddress" to "jane@gmail.example",
				"oauthConnectionId" to googleConnection.toString(),
				"password" to "x",
			),
		).andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("password") }
		}
	}

	@Test
	fun `patch changes fields, replaces the password and needs If-Match`() {
		val id = createOk()["id"].asString()

		patch(id, mapOf("displayName" to "Office"), ifMatch = null).andExpect { status { isEqualTo(428) } }
		patch(id, mapOf("displayName" to "Office"), ifMatch = "\"7\"").andExpect { status { isEqualTo(412) } }
		patch(
			id,
			mapOf(
				"displayName" to "Office",
				"imap" to mapOf("host" to "mail.example.test", "port" to 143, "security" to "STARTTLS"),
				"password" to "fictitious-secret-2",
				"signatureMd" to "-- Jane",
				"syncWindowMonths" to 3,
			),
		).andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"1\"") }
			jsonPath("$.displayName") { value("Office") }
			jsonPath("$.imap.port") { value(143) }
			jsonPath("$.smtp.host") { value("smtp.example.test") }
			jsonPath("$.signatureMd") { value("-- Jane") }
			jsonPath("$.syncWindowMonths") { value(3) }
		}
		accounts
			.findById(UUID.fromString(id))
			.get()
			.password!!
			.reveal() shouldBe "fictitious-secret-2"

		patch(id, mapOf("signatureMd" to ""), ifMatch = "\"1\"").andExpect { jsonPath("$.signatureMd") { doesNotExist() } }
	}

	@Test
	fun `patch rejects an empty password, blank username and an OAuth connection on password accounts`() {
		val id = createOk()["id"].asString()

		patch(id, mapOf("password" to "", "username" to " ", "oauthConnectionId" to googleConnection.toString()))
			.andExpect {
				status { isBadRequest() }
			}.fields("errors", "field") shouldBe listOf("oauthConnectionId", "password", "username")
	}

	@Test
	fun `delete removes the account with its folders and needs If-Match`() {
		val id = UUID.fromString(createOk()["id"].asString())
		folders.save(MailFolder(id, "INBOX", "INBOX"))

		mockMvc.delete("${MailAccountController.BASE}/$id") { with(csrf()) }.andExpect { status { isEqualTo(428) } }
		mockMvc
			.delete("${MailAccountController.BASE}/$id") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "\"0\"")
			}.andExpect { status { isNoContent() } }

		accounts.existsById(id) shouldBe false
		folders.findByAccountIdOrderByPathAsc(id) shouldBe emptyList()
		mockMvc.get("${MailAccountController.BASE}/$id").andExpect { status { isNotFound() } }
	}

	@Test
	fun `folders are listed by path and a role can be set by hand`() {
		val accountId = UUID.fromString(createOk()["id"].asString())
		val inbox = folders.save(MailFolder(accountId, "INBOX", "INBOX").apply { role = MailFolderRole.INBOX })
		val old = folders.save(MailFolder(accountId, "Old", "Old").apply { role = MailFolderRole.ARCHIVE })
		val archive = folders.save(MailFolder(accountId, "Archive 2026", "Archive 2026"))
		val base = "${MailAccountController.BASE}/$accountId/folders"

		mockMvc
			.get(base)
			.andExpect {
				status { isOk() }
				jsonPath("$.items[1].role") { value("INBOX") }
			}.fields("items", "path") shouldBe listOf("Archive 2026", "INBOX", "Old")

		mockMvc
			.patch("$base/${archive.id}") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"role":"ARCHIVE"}"""
				header(HttpHeaders.IF_MATCH, "\"0\"")
			}.andExpect {
				status { isOk() }
				jsonPath("$.role") { value("ARCHIVE") }
				jsonPath("$.roleOverridden") { value(true) }
			}
		folders.findById(old.id).get().role shouldBe null
		folders.findById(inbox.id).get().role shouldBe MailFolderRole.INBOX

		mockMvc
			.patch("$base/${archive.id}") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"automatic":true}"""
				header(HttpHeaders.IF_MATCH, "\"1\"")
			}.andExpect {
				status { isOk() }
				jsonPath("$.roleOverridden") { value(false) }
			}
	}

	@Test
	fun `a folder of another account is not found`() {
		val first = UUID.fromString(createOk()["id"].asString())
		val second = UUID.fromString(createOk(imapSmtpAccount + ("displayName" to "Second"))["id"].asString())
		val folder = folders.save(MailFolder(first, "INBOX", "INBOX"))

		mockMvc
			.patch("${MailAccountController.BASE}/$second/folders/${folder.id}") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"role":"INBOX"}"""
				header(HttpHeaders.IF_MATCH, "\"0\"")
			}.andExpect { status { isNotFound() } }
		mockMvc.get("${MailAccountController.BASE}/${UuidV7.generate()}/folders").andExpect { status { isNotFound() } }
	}
}
