package ch.sonensei.cockpit.contacts.carddav

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.crypto.SecretCipher
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
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
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The CardDAV account API (`/api/v1/contacts/carddav-accounts`, SPEC.md §8.3): list, add, get, remove, sync now and
 * creating a contact in an account's address book. Passwords are write-only. No real network: the unreachable server
 * is `127.0.0.1:1`, and contacts are created in accounts whose address book is already known (set with JDBC), which
 * needs no HTTP. Names, URLs and passwords are fictitious.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class CardDavAccountWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val cipher: SecretCipher,
) : PostgresTestBase() {
	@BeforeEach
	@AfterEach
	fun reset() {
		// Removing the accounts removes their links (on delete cascade).
		jdbc.execute("delete from contacts_carddav_account")
		jdbc.execute("delete from contacts_carddav_link")
		jdbc.execute("delete from contact")
		jdbc.execute("delete from search_document where item_type = 'CONTACT'")
	}

	// --- helpers

	private fun add(body: String) =
		mockMvc.post(ACCOUNTS) {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = body
		}

	private fun syncNow(id: Any?) = mockMvc.post("$ACCOUNTS/$id/sync") { with(csrf()) }

	private fun createContact(
		accountId: Any,
		body: String,
	) = mockMvc.post("$ACCOUNTS/$accountId/contacts") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun ResultActionsDsl.json(): JsonNode = andReturn().response.contentAsString.let(jsonMapper::readTree)

	private fun ResultActionsDsl.body(): String = andReturn().response.contentAsString

	private fun ResultActionsDsl.isProblem(
		status: Int,
		vararg fields: String,
	) = andExpect {
		status { isEqualTo(status) }
		content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		fields.forEach { field -> jsonPath("$.errors[?(@.field == '$field')]") { isNotEmpty() } }
	}

	private fun JsonNode.text(name: String): String? = get(name)?.takeUnless { it.isNull }?.asString()

	private fun newAccount(body: String = HOME): JsonNode = add(body).andExpect { status { isCreated() } }.json()

	/** An account inserted with JDBC, so its address book can be known without a sync. */
	private fun insertAccount(
		name: String,
		url: String,
		addressbookUrl: String?,
		username: String? = null,
	): UUID {
		val id = UuidV7.generate()
		jdbc.update(
			"""insert into contacts_carddav_account (id, name, url, username, password, addressbook_url)
			values (?, ?, ?, ?, ?, ?)""",
			id,
			name,
			url,
			username,
			cipher.encrypt(PASSWORD),
			addressbookUrl,
		)
		return id
	}

	private fun count(table: String): Int = jdbc.queryForObject("select count(*) from $table", Int::class.java)!!

	private fun storedPassword(id: String): String? =
		jdbc.queryForObject("select password from contacts_carddav_account where id = ?::uuid", String::class.java, id)

	// --- add, list, get

	@Test
	fun `POST adds an account with Location, never returns the password and stores it encrypted`() {
		val result =
			add(HOME)
				.andExpect {
					status { isCreated() }
					content { contentType(MediaType.APPLICATION_JSON) }
					jsonPath("$.name") { value("Home server") }
					jsonPath("$.url") { value("https://dav.example.test/dav/") }
					jsonPath("$.username") { value("alice") }
					jsonPath("$.hasPassword") { value(true) }
					jsonPath("$.sync.state") { value("NEVER") }
				}.andReturn()
		val body = result.response.contentAsString
		val json = jsonMapper.readTree(body)
		val id = json.text("id").shouldNotBeNull()
		result.response.getHeader(HttpHeaders.LOCATION) shouldBe "http://localhost$ACCOUNTS/$id"
		json.has("password") shouldBe false
		body shouldNotContain PASSWORD
		json.text("addressbookUrl").shouldBeNull()
		json["sync"].text("lastAttemptAt").shouldBeNull()
		json["sync"].text("lastSuccessAt").shouldBeNull()
		json["sync"].text("lastError").shouldBeNull()

		val stored = storedPassword(id).shouldNotBeNull()
		stored shouldNotBe PASSWORD
		stored shouldNotContain PASSWORD
		cipher.decrypt(stored) shouldBe PASSWORD
	}

	@Test
	fun `POST trims the name and URL, an empty password and a blank user name are stored as none`() {
		val json =
			newAccount("""{"name":"  Open server ","url":" http://localhost:5232/ ","username":"  ","password":""}""")

		json.text("name") shouldBe "Open server"
		json.text("url") shouldBe "http://localhost:5232/"
		json.text("username").shouldBeNull()
		json["hasPassword"].asBoolean() shouldBe false
		storedPassword(json.text("id")!!).shouldBeNull()
	}

	@Test
	fun `POST without user name and password adds an account without them`() {
		val json = newAccount("""{"name":"Open server","url":"http://localhost:5232/"}""")

		json["hasPassword"].asBoolean() shouldBe false
		json.text("username").shouldBeNull()
	}

	@Test
	fun `POST rejects a missing or blank name, a missing or invalid URL and an unreadable body`() {
		add("""{}""").isProblem(400, "name", "url")
		add("""{"url":"https://dav.example.test/"}""").isProblem(400, "name")
		add("""{"name":"Home server"}""").isProblem(400, "url")
		add("""{"name":"   ","url":"https://dav.example.test/"}""").isProblem(400, "name")
		add("""{"name":"Home server","url":"ftp://dav.example.test/"}""").isProblem(400, "url")
		add("""{"name":"Home server","url":"dav.example.test/dav/"}""").isProblem(400, "url")
		add("""{"name":"Home server","url":"https://alice:$PASSWORD@dav.example.test/"}""")
			.isProblem(400, "url")
			.body() shouldNotContain PASSWORD
		// Unreadable JSON (cut off): the password in it is not echoed.
		add("{\"name\":\"Home server\",\"url\":\"https://dav.example.test/\",\"password\":\"$PASSWORD")
			.isProblem(400)
			.body() shouldNotContain PASSWORD
		add("""{"name":"Home server","url":["https://dav.example.test/"]}""").isProblem(400)

		count("contacts_carddav_account") shouldBe 0
	}

	@Test
	fun `GET lists the accounts in the order they were added, without passwords`() {
		val home = newAccount()
		val open = newAccount("""{"name":"Open server","url":"http://localhost:5232/"}""")

		val result = mockMvc.get(ACCOUNTS).andExpect { status { isOk() } }
		val body = result.body()
		val items = result.json()["items"].values().toList()

		items.map { it.text("id") } shouldContainExactly listOf(home.text("id"), open.text("id"))
		items.map { it.text("name") } shouldContainExactly listOf("Home server", "Open server")
		items.map { it["hasPassword"].asBoolean() } shouldContainExactly listOf(true, false)
		items.forEach {
			it.has("password") shouldBe false
			it["sync"].text("state") shouldBe "NEVER"
		}
		body shouldNotContain PASSWORD
	}

	@Test
	fun `GET lists no accounts as an empty list`() {
		mockMvc.get(ACCOUNTS).andExpect {
			status { isOk() }
			jsonPath("$.items") { isArray() }
			jsonPath("$.items.length()") { value(0) }
		}
	}

	@Test
	fun `GET of one account returns it, an unknown one is 404 and a malformed id 400`() {
		val id = newAccount().text("id")

		val body =
			mockMvc
				.get("$ACCOUNTS/$id")
				.andExpect {
					status { isOk() }
					jsonPath("$.id") { value(id) }
					jsonPath("$.name") { value("Home server") }
					jsonPath("$.hasPassword") { value(true) }
					jsonPath("$.sync.state") { value("NEVER") }
				}.body()
		body shouldNotContain PASSWORD
		jsonMapper.readTree(body).has("password") shouldBe false

		mockMvc.get("$ACCOUNTS/$UNKNOWN").isProblem(404)
		mockMvc.get("$ACCOUNTS/not-an-id").isProblem(400)
	}

	@Test
	fun `the sync state is OK after a successful run and ERROR with the error after a failed one`() {
		val ok = insertAccount("Home server", "https://dav.example.test/dav/", null)
		jdbc.update(
			"update contacts_carddav_account set last_attempt_at = now(), last_success_at = now() where id = ?",
			ok,
		)
		val failed = insertAccount("Work server", "https://cards.example.test/", null)
		jdbc.update(
			"update contacts_carddav_account set last_attempt_at = now(), last_success_at = now() - interval '1 day', " +
				"last_error = 'Listing the contacts failed (HTTP 403)' where id = ?",
			failed,
		)

		mockMvc.get("$ACCOUNTS/$ok").andExpect {
			jsonPath("$.sync.state") { value("OK") }
			jsonPath("$.sync.lastSuccessAt") { isNotEmpty() }
		}
		mockMvc.get("$ACCOUNTS/$failed").andExpect {
			jsonPath("$.sync.state") { value("ERROR") }
			jsonPath("$.sync.lastError") { value("Listing the contacts failed (HTTP 403)") }
			jsonPath("$.sync.lastSuccessAt") { isNotEmpty() }
		}
	}

	// --- remove

	@Test
	fun `DELETE removes the account and its links but keeps its contacts, then it is 404`() {
		val account = insertAccount("Home server", "https://dav.example.test/dav/", ADDRESSBOOK, "alice")
		val contactId =
			createContact(account, """{"displayName":"Robin Example"}""")
				.andExpect { status { isCreated() } }
				.json()
				.text("id")
		count("contacts_carddav_link") shouldBe 1

		mockMvc.delete("$ACCOUNTS/$account") { with(csrf()) }.andExpect { status { isNoContent() } }

		mockMvc.get("$ACCOUNTS/$account").isProblem(404)
		count("contacts_carddav_account") shouldBe 0
		count("contacts_carddav_link") shouldBe 0
		mockMvc.get("/api/v1/contacts/$contactId").andExpect {
			status { isOk() }
			jsonPath("$.displayName") { value("Robin Example") }
		}
		mockMvc.delete("$ACCOUNTS/$account") { with(csrf()) }.isProblem(404)
	}

	@Test
	fun `DELETE of an unknown account is 404 and removes nothing`() {
		newAccount()

		mockMvc.delete("$ACCOUNTS/$UNKNOWN") { with(csrf()) }.isProblem(404)

		count("contacts_carddav_account") shouldBe 1
	}

	// --- sync now

	@Test
	fun `sync of an unreachable server answers 200 with state ERROR and the error, without the password`() {
		val id =
			newAccount("""{"name":"Offline server","url":"$UNREACHABLE","username":"alice","password":"$PASSWORD"}""")
				.text("id")

		val result =
			syncNow(id).andExpect {
				status { isOk() }
				jsonPath("$.id") { value(id) }
				jsonPath("$.sync.state") { value("ERROR") }
				jsonPath("$.sync.lastAttemptAt") { isNotEmpty() }
			}
		val body = result.body()
		val sync = jsonMapper.readTree(body)["sync"]
		sync.text("lastError") shouldBe UNREACHABLE_ERROR
		sync.text("lastSuccessAt").shouldBeNull()
		body shouldNotContain PASSWORD
		jsonMapper.readTree(body).has("password") shouldBe false

		// The state is kept: a later GET shows it too.
		mockMvc.get("$ACCOUNTS/$id").andExpect {
			jsonPath("$.sync.state") { value("ERROR") }
			jsonPath("$.sync.lastError") { value(UNREACHABLE_ERROR) }
		}
	}

	@Test
	fun `sync of an unknown account is 404`() {
		syncNow(UNKNOWN).isProblem(404)
	}

	// --- create a contact in an account

	@Test
	fun `POST contacts creates a CARDDAV contact with ETag and Location and links it to a new card`() {
		val account = insertAccount("Home server", "https://dav.example.test/dav/", ADDRESSBOOK, "alice")

		val result =
			createContact(account, ROBIN)
				.andExpect {
					status { isCreated() }
					header { string(HttpHeaders.ETAG, "\"0\"") }
					jsonPath("$.displayName") { value("Robin Example") }
					jsonPath("$.emails[0].address") { value("robin@example.test") }
					jsonPath("$.source") { value("CARDDAV") }
				}.andReturn()
		val json = jsonMapper.readTree(result.response.contentAsString)
		val id = json.text("id").shouldNotBeNull()
		result.response.getHeader(HttpHeaders.LOCATION) shouldBe "http://localhost/api/v1/contacts/$id"
		val externalId = json.text("externalId").shouldNotBeNull()
		externalId shouldStartWith ADDRESSBOOK
		externalId.removePrefix(ADDRESSBOOK) shouldContain ".vcf"

		val link = jdbc.queryForMap("select * from contacts_carddav_link")
		link["contact_id"] shouldBe UUID.fromString(id)
		link["account_id"] shouldBe account
		link["href"] shouldBe "/dav/alice/contacts/" + externalId.removePrefix(ADDRESSBOOK)
		link["etag"].shouldBeNull()
		link["synced_version"] shouldBe 0L

		mockMvc.get("/api/v1/contacts/$id").andExpect {
			status { isOk() }
			jsonPath("$.source") { value("CARDDAV") }
		}
	}

	@Test
	fun `POST contacts rejects unknown fields and invalid values and creates nothing`() {
		val account = insertAccount("Home server", "https://dav.example.test/dav/", ADDRESSBOOK)

		createContact(account, """{"displayName":"Robin","source":"LOCAL"}""").isProblem(400, "source")
		createContact(account, """{"displayName":"Robin","externalId":"x"}""").isProblem(400, "externalId")
		createContact(account, """{"displayName":"Robin","emails":[{"address":"not-an-email"}]}""")
			.isProblem(400, "emails[0].address")
		createContact(account, """{"displayName":"Robin","birthday":"tomorrow"}""").isProblem(400, "birthday")
		createContact(account, """{}""").isProblem(400, "displayName")

		count("contact") shouldBe 0
		count("contacts_carddav_link") shouldBe 0
	}

	@Test
	fun `POST contacts checks the fields before looking up the address book, so invalid input is 400, not 409`() {
		val account = insertAccount("Offline server", UNREACHABLE, null, "alice")

		createContact(account, """{}""").isProblem(400, "displayName")
		createContact(account, """{"displayName":"Robin","emails":[{"address":"not-an-email"}]}""")
			.isProblem(400, "emails[0].address")

		count("contact") shouldBe 0
		jdbc
			.queryForList("select addressbook_url from contacts_carddav_account where id = ?", String::class.java, account)
			.single()
			.shouldBeNull()
	}

	@Test
	fun `POST contacts to an unknown account is 404`() {
		createContact(UNKNOWN, """{"displayName":"Robin"}""").isProblem(404)

		count("contact") shouldBe 0
	}

	@Test
	fun `POST contacts answers 409 with the CardDAV error when the address book cannot be found, creating nothing`() {
		val account = insertAccount("Offline server", UNREACHABLE, null, "alice")

		val body =
			createContact(account, """{"displayName":"Robin Example"}""")
				.isProblem(409)
				.andExpect { jsonPath("$.detail") { value(UNREACHABLE_ERROR) } }
				.body()

		body shouldNotContain PASSWORD
		count("contact") shouldBe 0
		count("contacts_carddav_link") shouldBe 0
		jdbc
			.queryForList("select addressbook_url from contacts_carddav_account where id = ?", String::class.java, account)
			.single()
			.shouldBeNull()
	}

	@Test
	fun `no response of the account API contains a password`() {
		val id = newAccount().text("id")
		val bodies =
			listOf(
				mockMvc.get(ACCOUNTS).body(),
				mockMvc.get("$ACCOUNTS/$id").body(),
				syncNow(id).body(),
			)

		bodies.forEach {
			it shouldContain "\"hasPassword\":true"
			it shouldNotContain PASSWORD
			it shouldNotContain "\"password\""
		}
	}

	@Test
	fun `the add request's toString redacts the password`() {
		val request = AddCardDavAccountRequest("Home server", "https://dav.example.test/dav/", "alice", PASSWORD)

		request.toString() shouldNotContain PASSWORD
		request.toString() shouldContain "password=******"
		request.toString() shouldContain "alice"
	}

	companion object {
		private const val ACCOUNTS = "/api/v1/contacts/carddav-accounts"
		private const val PASSWORD = "fictitious-carddav-pw-7Q"
		private const val HOME =
			"""{"name":"Home server","url":"https://dav.example.test/dav/","username":"alice","password":"$PASSWORD"}"""
		private const val ADDRESSBOOK = "https://dav.example.test/dav/alice/contacts/"
		private const val ROBIN =
			"""{"givenName":"Robin","familyName":"Example","emails":[{"address":"robin@example.test"}]}"""
		private const val UNREACHABLE_ERROR = "Finding the address book: the CardDAV server could not be reached"

		/** Nothing listens on port 1: the connection is refused at once, no real network is used. */
		private const val UNREACHABLE = "http://127.0.0.1:1/"
		private val UNKNOWN: UUID = UUID.fromString("0192f000-0000-7000-8000-0000000000ff")
	}
}
