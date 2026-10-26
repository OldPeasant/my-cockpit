package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailIdentityRepository
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
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** The identity API of a mail account (no mail server). */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@RecordApplicationEvents
class MailIdentityWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val identities: MailIdentityRepository,
) : PostgresTestBase() {
	private lateinit var accountId: UUID

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from mail_account")
		accountId = account("jane.doe@example.test").id
	}

	private fun account(email: String): MailAccount =
		accounts.saveAndFlush(
			MailAccount(
				type = MailAccountType.IMAP_SMTP,
				displayName = "Work",
				emailAddress = email,
				imapHost = "imap.example.test",
				imapPort = 993,
				imapSecurity = MailSecurity.SSL_TLS,
				smtpHost = "smtp.example.test",
				smtpPort = 587,
				smtpSecurity = MailSecurity.STARTTLS,
				username = "jane",
			),
		)

	private fun base(account: UUID = accountId) = "/api/v1/mail/accounts/$account/identities"

	private fun create(
		body: Map<String, Any?>,
		account: UUID = accountId,
	) = mockMvc.post(base(account)) {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = json.writeValueAsString(body)
	}

	private fun createOk(body: Map<String, Any?>): JsonNode =
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
		account: UUID = accountId,
	) = mockMvc.patch("${base(account)}/$id") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = json.writeValueAsString(body)
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun delete(
		id: Any,
		ifMatch: String? = "\"0\"",
	) = mockMvc.delete("${base()}/$id") {
		with(csrf())
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun listEmails(): List<String> {
		val items =
			json.readTree(
				mockMvc
					.get(base())
					.andReturn()
					.response.contentAsString,
			)["items"]
		return (0 until items.size()).map { items[it]["email"].asString() }
	}

	private fun ApplicationEvents.identityEvents(op: ChangeOp) =
		stream(ChangeEvent::class.java).filter { it.type == MailIdentityService.IDENTITY_EVENT && it.op == op }.count()

	@Test
	fun `create returns the identity with ETag and Location and lists it in order`(events: ApplicationEvents) {
		create(
			mapOf(
				"name" to "Jane Doe",
				"email" to "info@example.test",
				"replyTo" to "support@example.test",
				"signatureMd" to "**Jane**",
			),
		).andExpect {
			status { isCreated() }
			header { string(HttpHeaders.ETAG, "\"0\"") }
			header { exists(HttpHeaders.LOCATION) }
			jsonPath("$.accountId") { value(accountId.toString()) }
			jsonPath("$.name") { value("Jane Doe") }
			jsonPath("$.email") { value("info@example.test") }
			jsonPath("$.replyTo") { value("support@example.test") }
			jsonPath("$.signatureMd") { value("**Jane**") }
			jsonPath("$.isDefault") { value(false) }
			jsonPath("$.position") { value(0) }
		}
		createOk(mapOf("name" to "Jane", "email" to "billing@example.test"))["position"].asInt() shouldBe 1

		listEmails() shouldContainExactly listOf("info@example.test", "billing@example.test")
		events.identityEvents(ChangeOp.CREATED) shouldBe 2
	}

	@Test
	fun `get returns one identity with its ETag`() {
		val id = createOk(mapOf("name" to "Jane", "email" to "info@example.test"))["id"].asString()
		mockMvc.get("${base()}/$id").andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"0\"") }
			jsonPath("$.email") { value("info@example.test") }
		}
	}

	@Test
	fun `invalid requests are 400 with field errors`() {
		create(mapOf("name" to " ", "email" to "not-an-address", "replyTo" to "nope")).andExpect {
			status { isBadRequest() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.errors[?(@.field == 'name')]") { exists() }
			jsonPath("$.errors[?(@.field == 'email')]") { exists() }
			jsonPath("$.errors[?(@.field == 'replyTo')]") { exists() }
		}
		create(mapOf("email" to "info@example.test")).andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("name") }
		}
		val id = createOk(mapOf("name" to "Jane", "email" to "info@example.test"))["id"].asString()
		patch(id, mapOf("name" to "  ")).andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("name") }
		}
		patch(id, mapOf("email" to "broken")).andExpect { status { isBadRequest() } }
		patch(id, mapOf("email" to "   ")).andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("email") }
		}
	}

	@Test
	fun `an address is used once per account, ignoring case, but may repeat in another account`() {
		val first = createOk(mapOf("name" to "Jane", "email" to "info@example.test"))["id"].asString()
		create(mapOf("name" to "Jane", "email" to "INFO@example.test")).andExpect {
			status { isConflict() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
		}
		val second = createOk(mapOf("name" to "Jane", "email" to "other@example.test"))["id"].asString()
		patch(second, mapOf("email" to "Info@Example.test")).andExpect { status { isConflict() } }
		// Changing the case of its own address is fine.
		patch(first, mapOf("email" to "Info@example.test")).andExpect {
			status { isOk() }
			jsonPath("$.email") { value("Info@example.test") }
		}

		val otherAccount = account("john@example.test").id
		create(mapOf("name" to "Jane", "email" to "info@example.test"), otherAccount).andExpect { status { isCreated() } }
	}

	@Test
	fun `update changes the sent fields, empty values remove reply-to and signature`(events: ApplicationEvents) {
		val id =
			createOk(
				mapOf("name" to "Jane", "email" to "info@example.test", "replyTo" to "support@example.test", "signatureMd" to "Sig"),
			)["id"].asString()

		patch(id, mapOf("name" to "Jane Doe")).andExpect {
			status { isOk() }
			header { string(HttpHeaders.ETAG, "\"1\"") }
			jsonPath("$.name") { value("Jane Doe") }
			jsonPath("$.replyTo") { value("support@example.test") }
			jsonPath("$.signatureMd") { value("Sig") }
		}
		patch(id, mapOf("replyTo" to "", "signatureMd" to ""), ifMatch = "\"1\"").andExpect {
			status { isOk() }
			jsonPath("$.replyTo") { doesNotExist() }
			jsonPath("$.signatureMd") { doesNotExist() }
			jsonPath("$.name") { value("Jane Doe") }
			jsonPath("$.email") { value("info@example.test") }
		}
		events.identityEvents(ChangeOp.UPDATED) shouldBe 2
	}

	@Test
	fun `isDefault moves the default flag from the previous default`() {
		val first = createOk(mapOf("name" to "Jane", "email" to "a@example.test", "isDefault" to true))
		first["isDefault"].asBoolean() shouldBe true
		val second = createOk(mapOf("name" to "Jane", "email" to "b@example.test", "isDefault" to true))["id"].asString()

		identities.findById(UUID.fromString(first["id"].asString())).get().isDefault shouldBe false
		identities.findById(UUID.fromString(second)).get().isDefault shouldBe true

		patch(first["id"].asString(), mapOf("isDefault" to true), ifMatch = "\"1\"").andExpect {
			status { isOk() }
			jsonPath("$.isDefault") { value(true) }
		}
		identities.findById(UUID.fromString(second)).get().isDefault shouldBe false

		patch(first["id"].asString(), mapOf("isDefault" to false), ifMatch = "\"2\"").andExpect {
			jsonPath("$.isDefault") { value(false) }
		}
		identities.findAll().none { it.isDefault } shouldBe true
	}

	@Test
	fun `update and delete need a current If-Match`(events: ApplicationEvents) {
		val id = createOk(mapOf("name" to "Jane", "email" to "info@example.test"))["id"].asString()
		patch(id, mapOf("name" to "X"), ifMatch = null).andExpect { status { isPreconditionRequired() } }
		patch(id, mapOf("name" to "X"), ifMatch = "\"7\"").andExpect { status { isPreconditionFailed() } }
		delete(id, ifMatch = null).andExpect { status { isPreconditionRequired() } }
		delete(id, ifMatch = "\"7\"").andExpect { status { isPreconditionFailed() } }

		delete(id).andExpect { status { isNoContent() } }
		listEmails() shouldBe emptyList()
		events.identityEvents(ChangeOp.DELETED) shouldBe 1
	}

	@Test
	fun `unknown accounts and identities of another account are 404`() {
		val unknown = UuidV7.generate()
		mockMvc.get(base(unknown)).andExpect {
			status { isNotFound() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
		}
		create(mapOf("name" to "Jane", "email" to "info@example.test"), unknown).andExpect { status { isNotFound() } }

		val id = createOk(mapOf("name" to "Jane", "email" to "info@example.test"))["id"].asString()
		val otherAccount = account("john@example.test").id
		mockMvc.get("${base(otherAccount)}/$id").andExpect { status { isNotFound() } }
		patch(id, mapOf("name" to "X"), account = otherAccount).andExpect { status { isNotFound() } }
		mockMvc.get("${base()}/$unknown").andExpect { status { isNotFound() } }
		delete(unknown).andExpect { status { isNotFound() } }
	}

	@Test
	fun `create trims the name and stores empty reply-to and signature as absent`() {
		create(mapOf("name" to "  Jane Doe  ", "email" to "info@example.test", "replyTo" to "", "signatureMd" to "")).andExpect {
			status { isCreated() }
			jsonPath("$.name") { value("Jane Doe") }
			jsonPath("$.email") { value("info@example.test") }
			jsonPath("$.replyTo") { doesNotExist() }
			jsonPath("$.signatureMd") { doesNotExist() }
		}
		val stored = identities.findAll().single()
		stored.replyTo shouldBe null
		stored.signatureMd shouldBe null
	}

	@Test
	fun `a missing email or a name over 200 characters is 400 and stores nothing`() {
		create(mapOf("name" to "Jane")).andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("email") }
		}
		create(mapOf("name" to "x".repeat(201), "email" to "info@example.test")).andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("name") }
		}
		create(mapOf("name" to "x".repeat(200), "email" to "info@example.test")).andExpect { status { isCreated() } }
		identities.count() shouldBe 1
	}

	@Test
	fun `a new default publishes the change of the previous default`(events: ApplicationEvents) {
		val first = createOk(mapOf("name" to "Jane", "email" to "a@example.test", "isDefault" to true))["id"].asString()
		createOk(mapOf("name" to "Jane", "email" to "b@example.test", "isDefault" to true))

		events
			.stream(ChangeEvent::class.java)
			.filter { it.type == MailIdentityService.IDENTITY_EVENT && it.op == ChangeOp.UPDATED }
			.map { it.id.toString() }
			.toList() shouldContainExactly listOf(first)
		mockMvc.get("${base()}/$first").andExpect { header { string(HttpHeaders.ETAG, "\"1\"") } }
	}

	@Test
	fun `making the current default the default again keeps the others unchanged`() {
		val first = createOk(mapOf("name" to "Jane", "email" to "a@example.test", "isDefault" to true))["id"].asString()
		val second = createOk(mapOf("name" to "Jane", "email" to "b@example.test"))["id"].asString()

		patch(first, mapOf("isDefault" to true)).andExpect {
			status { isOk() }
			jsonPath("$.isDefault") { value(true) }
		}
		val stored = identities.findById(UUID.fromString(second)).get()
		stored.isDefault shouldBe false
		stored.version shouldBe 0
	}

	@Test
	fun `a stale If-Match changes nothing`() {
		val id = createOk(mapOf("name" to "Jane", "email" to "info@example.test", "isDefault" to true))["id"].asString()
		patch(id, mapOf("name" to "Changed", "isDefault" to false), ifMatch = "\"5\"").andExpect {
			status { isPreconditionFailed() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
		}
		val stored = identities.findById(UUID.fromString(id)).get()
		stored.name shouldBe "Jane"
		stored.isDefault shouldBe true
	}

	@Test
	fun `a new identity comes after the highest position, also after a removal`() {
		createOk(mapOf("name" to "Jane", "email" to "a@example.test"))
		val middle = createOk(mapOf("name" to "Jane", "email" to "b@example.test"))["id"].asString()
		createOk(mapOf("name" to "Jane", "email" to "c@example.test"))
		delete(middle).andExpect { status { isNoContent() } }

		createOk(mapOf("name" to "Jane", "email" to "d@example.test"))["position"].asInt() shouldBe 3
		listEmails() shouldContainExactly listOf("a@example.test", "c@example.test", "d@example.test")
	}

	@Test
	fun `a removed address can be added again`() {
		val id = createOk(mapOf("name" to "Jane", "email" to "info@example.test"))["id"].asString()
		delete(id).andExpect { status { isNoContent() } }
		create(mapOf("name" to "Jane", "email" to "INFO@example.test")).andExpect { status { isCreated() } }
	}

	@Test
	fun `an identity cannot be removed through another account`() {
		val id = createOk(mapOf("name" to "Jane", "email" to "info@example.test"))["id"].asString()
		val otherAccount = account("john@example.test").id
		mockMvc
			.delete("${base(otherAccount)}/$id") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "\"0\"")
			}.andExpect { status { isNotFound() } }
		listEmails() shouldContainExactly listOf("info@example.test")
	}

	@Test
	fun `update sets a new reply-to and signature override and keeps the other fields`() {
		val id = createOk(mapOf("name" to "Jane", "email" to "info@example.test"))["id"].asString()

		patch(id, mapOf("replyTo" to "help@example.test", "signatureMd" to "-- \nJane")).andExpect {
			status { isOk() }
			jsonPath("$.replyTo") { value("help@example.test") }
			jsonPath("$.signatureMd") { value("-- \nJane") }
			jsonPath("$.name") { value("Jane") }
			jsonPath("$.email") { value("info@example.test") }
		}
		val stored = identities.findById(UUID.fromString(id)).get()
		stored.replyTo shouldBe "help@example.test"
		stored.signatureMd shouldBe "-- \nJane"
	}

	@Test
	fun `an update cannot empty the address of an identity`() {
		val id = createOk(mapOf("name" to "Jane", "email" to "info@example.test"))["id"].asString()

		patch(id, mapOf("email" to "")).andExpect {
			status { isBadRequest() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.errors[0].field") { value("email") }
		}
		identities.findById(UUID.fromString(id)).get().email shouldBe "info@example.test"
	}

	@Test
	fun `changing an unknown identity of the account is 404 Problem Details`() {
		val unknown = UuidV7.generate()
		patch(unknown, mapOf("name" to "X")).andExpect {
			status { isNotFound() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
		}
		patch(unknown, mapOf("name" to "X"), account = UuidV7.generate()).andExpect { status { isNotFound() } }
	}

	@Test
	fun `a rejected change stores nothing and publishes no event`(events: ApplicationEvents) {
		createOk(mapOf("name" to "Jane", "email" to "info@example.test"))
		val id = createOk(mapOf("name" to "Jane", "email" to "other@example.test", "isDefault" to true))["id"].asString()

		patch(id, mapOf("name" to "Changed", "email" to "info@example.test", "isDefault" to false)).andExpect {
			status { isConflict() }
		}
		patch(id, mapOf("name" to "Changed", "email" to " ")).andExpect { status { isBadRequest() } }
		delete(id, ifMatch = "\"9\"").andExpect { status { isPreconditionFailed() } }

		val stored = identities.findById(UUID.fromString(id)).get()
		stored.name shouldBe "Jane"
		stored.email shouldBe "other@example.test"
		stored.isDefault shouldBe true
		stored.version shouldBe 0
		events.identityEvents(ChangeOp.UPDATED) shouldBe 0
		events.identityEvents(ChangeOp.DELETED) shouldBe 0
	}

	@Test
	fun `a duplicate new default does not take the flag from the current default`() {
		val first = createOk(mapOf("name" to "Jane", "email" to "info@example.test", "isDefault" to true))["id"].asString()
		create(mapOf("name" to "Jane", "email" to "INFO@example.test", "isDefault" to true)).andExpect { status { isConflict() } }

		val stored = identities.findById(UUID.fromString(first)).get()
		stored.isDefault shouldBe true
		stored.version shouldBe 0
	}

	@Test
	fun `removing the default identity leaves no default`() {
		val id = createOk(mapOf("name" to "Jane", "email" to "info@example.test", "isDefault" to true))["id"].asString()
		createOk(mapOf("name" to "Jane", "email" to "other@example.test"))
		delete(id).andExpect { status { isNoContent() } }

		identities.findAll().none { it.isDefault } shouldBe true
		listEmails() shouldContainExactly listOf("other@example.test")
	}

	@Test
	@WithAnonymousUser
	fun `the identity API requires login`() {
		val id = UuidV7.generate()
		mockMvc.get(base()).andExpect { status { isUnauthorized() } }
		create(mapOf("name" to "Jane", "email" to "info@example.test")).andExpect { status { isUnauthorized() } }
		patch(id, mapOf("name" to "X")).andExpect { status { isUnauthorized() } }
		delete(id).andExpect { status { isUnauthorized() } }
		identities.count() shouldBe 0
	}

	@Test
	fun `identities go with their account`() {
		createOk(mapOf("name" to "Jane", "email" to "info@example.test"))
		accounts.deleteById(accountId)
		identities.count() shouldBe 0
	}
}
