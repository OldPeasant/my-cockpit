package ch.sonensei.cockpit.contacts.carddav

import ch.sonensei.cockpit.contacts.domain.Change
import ch.sonensei.cockpit.contacts.domain.Contact
import ch.sonensei.cockpit.contacts.domain.ContactChanges
import ch.sonensei.cockpit.contacts.domain.ContactEmail
import ch.sonensei.cockpit.contacts.domain.ContactPhone
import ch.sonensei.cockpit.contacts.domain.ContactService
import ch.sonensei.cockpit.contacts.domain.ContactSource
import ch.sonensei.cockpit.contacts.infra.ContactRepository
import ch.sonensei.cockpit.support.ExternalTest
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.util.Base64

/**
 * [CardDavContactsSync] against a Radicale server (SPEC.md §8.3, §12): discovery of the address book, pulling with
 * sync-collection (first run, changes, removals, an invalid token), local changes, creations and deletions arriving on
 * the server with If-Match / If-None-Match, and a conflict where the server's version wins. Every test starts with an
 * empty address book `contacts` of user alice and one account pointing at the server root.
 */
@ExternalTest
@SpringBootTest
class CardDavRadicaleSyncTests(
	@Autowired private val sync: CardDavContactsSync,
	@Autowired private val accountService: CardDavAccountService,
	@Autowired private val accounts: CardDavAccountRepository,
	@Autowired private val links: CardDavLinkRepository,
	@Autowired private val contacts: ContactRepository,
	@Autowired private val service: ContactService,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private lateinit var account: CardDavAccount

	@BeforeEach
	fun reset() {
		jdbc.execute("delete from contacts_carddav_link")
		jdbc.execute("delete from contacts_carddav_account")
		jdbc.execute("delete from contact")
		jdbc.execute("delete from search_document where item_type = 'CONTACT'")
		dav("DELETE", BOOK)
		dav(
			"MKCOL",
			BOOK,
			"""<?xml version="1.0" encoding="utf-8"?><D:mkcol xmlns:D="DAV:" xmlns:CR="urn:ietf:params:xml:ns:carddav">""" +
				"<D:set><D:prop><D:resourcetype><D:collection/><CR:addressbook/></D:resourcetype>" +
				"<D:displayname>Contacts</D:displayname></D:prop></D:set></D:mkcol>",
		).statusCode() shouldBe 201
		account = accountService.add("Radicale", "${radicale.baseUrl()}/", USER, PASSWORD)
	}

	private fun syncOk(): CardDavAccount = sync.sync(account.id).also { it.lastError.shouldBeNull() }

	private fun local(name: String): Contact? = contacts.findAll().singleOrNull { it.displayName == name }

	private fun linkOf(contact: Contact): CardDavLink = links.findByContactId(contact.id).shouldNotBeNull()

	// --- pull

	@Test
	fun `discovery finds the address book from the server root, and a first sync stores every card`() {
		put("ada.vcf", card("ada", "Ada Example", "ada@example.test"))
		put("bob.vcf", card("bob", "Bob Example", "bob@example.test", extra = "TEL;TYPE=cell:+41 79 000 00 00\r\nBDAY:1980-04-01\r\n"))

		val state = syncOk()

		state.addressbookUrl shouldBe "${radicale.baseUrl()}$BOOK"
		state.syncToken.shouldNotBeNull()
		state.lastSuccessAt.shouldNotBeNull()
		local("Ada Example").shouldNotBeNull().apply {
			source shouldBe ContactSource.CARDDAV
			givenName shouldBe "Ada"
			familyName shouldBe "Example"
			emails shouldContainExactly listOf(ContactEmail("ada@example.test", "work"))
			externalId shouldBe "${radicale.baseUrl()}${BOOK}ada.vcf"
			linkOf(this).etag shouldBe etagOf("ada.vcf")
		}
		local("Bob Example").shouldNotBeNull().apply {
			phones shouldContainExactly listOf(ContactPhone("+41 79 000 00 00", "cell"))
			birthday shouldBe LocalDate.of(1980, 4, 1)
		}
	}

	@Test
	fun `a later sync applies only what changed at the server, through the sync token`() {
		put("ada.vcf", card("ada", "Ada Example", "ada@example.test"))
		put("bob.vcf", card("bob", "Bob Example", "bob@example.test"))
		val first = syncOk().syncToken
		val bob = local("Bob Example").shouldNotBeNull()
		val bobVersion = bob.version

		put("ada.vcf", card("ada", "Ada Example", "ada.new@example.test"))
		dav("DELETE", "${BOOK}bob.vcf").statusCode() shouldBe 200
		put("cleo.vcf", card("cleo", "Cleo Example", "cleo@example.test"))
		val second = syncOk().syncToken

		second shouldNotBe first
		local("Ada Example").shouldNotBeNull().emails.map { it.address } shouldContainExactly listOf("ada.new@example.test")
		contacts.findById(bob.id).get().apply {
			isDeleted shouldBe true
			version shouldBe bobVersion + 1
		}
		linkOf(bob).etag.shouldBeNull()
		local("Cleo Example").shouldNotBeNull()

		// Nothing changed: nothing is written.
		val ada = local("Ada Example")!!
		syncOk()
		contacts.findById(ada.id).get().version shouldBe ada.version
	}

	@Test
	fun `a token the server no longer accepts lists every card and trashes the contacts whose card is missing`() {
		put("ada.vcf", card("ada", "Ada Example", "ada@example.test"))
		put("bob.vcf", card("bob", "Bob Example", "bob@example.test"))
		syncOk()
		dav("DELETE", "${BOOK}bob.vcf")
		jdbc.update("update contacts_carddav_account set sync_token = ? where id = ?", "http://radicale.org/ns/sync/unknown", account.id)

		val state = syncOk()

		state.syncToken shouldNotBe "http://radicale.org/ns/sync/unknown"
		local("Ada Example").shouldNotBeNull().isDeleted shouldBe false
		contacts.findAll().single { it.displayName == "Bob Example" }.isDeleted shouldBe true
	}

	// --- push

	@Test
	fun `a local change is written into the server's card with If-Match, keeping what the cockpit does not model`() {
		put("ada.vcf", card("ada", "Ada Example", "ada@example.test", extra = "NICKNAME:Countess\r\nX-CUSTOM:kept\r\n"))
		syncOk()
		val ada = local("Ada Example")!!

		service.update(
			ada.id,
			ContactChanges(emails = Change(listOf(ContactEmail("ada@home.example.test", "home"))), title = Change("Analyst")),
		) {}
		syncOk()

		val text = get("ada.vcf").body()
		text shouldContain "ada@home.example.test"
		text shouldNotContain "ada@example.test"
		text shouldContain "TITLE:Analyst"
		text shouldContain "NICKNAME:Countess"
		text shouldContain "X-CUSTOM:kept"
		text shouldContain "UID:ada"
		val link = linkOf(ada)
		link.etag shouldBe etagOf("ada.vcf")
		link.syncedVersion shouldBe contacts.findById(ada.id).get().version
		// The echo of the push is no change for the cockpit.
		val version = contacts.findById(ada.id).get().version
		syncOk()
		contacts.findById(ada.id).get().version shouldBe version
	}

	@Test
	fun `a contact changed here and at the server keeps the server's version`() {
		put("ada.vcf", card("ada", "Ada Example", "ada@example.test"))
		syncOk()
		val ada = local("Ada Example")!!

		service.update(ada.id, ContactChanges(title = Change("Local title"))) {}
		put("ada.vcf", card("ada", "Ada Example", "ada@server.example.test", extra = "TITLE:Server title\r\n"))
		syncOk()

		contacts.findById(ada.id).get().apply {
			title shouldBe "Server title"
			emails.map { it.address } shouldContainExactly listOf("ada@server.example.test")
		}
		get("ada.vcf").body() shouldNotContain "Local title"
		linkOf(ada).etag shouldBe etagOf("ada.vcf")
	}

	@Test
	fun `a contact created for the account is written to the server, and a deleted one is removed there`() {
		syncOk()
		val created =
			sync.createContact(
				account.id,
				ContactChanges(givenName = Change("Dora"), familyName = Change("Example"), emails = Change(listOf(ContactEmail("dora@example.test")))),
			)
		created.source shouldBe ContactSource.CARDDAV
		val href = linkOf(created).href

		syncOk()

		val name = href.substringAfterLast('/')
		val text = get(name).body()
		text shouldContain "FN:Dora Example"
		text shouldContain "dora@example.test"
		text shouldContain "UID:urn:uuid:${created.id}"
		linkOf(created).etag shouldBe etagOf(name)
		contacts.findById(created.id).get().externalId shouldBe "${radicale.baseUrl()}$href"

		service.delete(created.id) {}
		syncOk()

		get(name).statusCode() shouldBe 404
		linkOf(created).etag.shouldBeNull()
		contacts.findById(created.id).get().isDeleted shouldBe true
	}

	@Test
	fun `a purged contact's card is deleted at the server by the next push`() {
		put("ada.vcf", card("ada", "Ada Example", "ada@example.test"))
		syncOk()
		val ada = local("Ada Example")!!
		service.delete(ada.id) {}
		jdbc.update("delete from contact where id = ?", ada.id)
		links
			.findByAccountId(account.id)
			.single()
			.contactId
			.shouldBeNull()

		syncOk()

		get("ada.vcf").statusCode() shouldBe 404
		links.findByAccountId(account.id).shouldBeEmpty()
	}

	@Test
	fun `a wrong password fails the run with a message that holds no secret`() {
		val wrong = accountService.add("Wrong", "${radicale.baseUrl()}/", USER, "not-the-password")

		val state = sync.sync(wrong.id)

		state.lastError.shouldNotBeNull().apply {
			this shouldContain "rejected the user name or password"
			this shouldNotContain "not-the-password"
		}
		state.lastSuccessAt.shouldBeNull()
		accounts
			.findById(wrong.id)
			.get()
			.lastAttemptAt
			.shouldNotBeNull()
	}

	@Test
	fun `removing the account forgets its links and keeps its contacts`() {
		put("ada.vcf", card("ada", "Ada Example", "ada@example.test"))
		syncOk()

		accountService.remove(account.id)

		links.findAll().shouldBeEmpty()
		contacts.findAll().map { it.displayName } shouldContainExactlyInAnyOrder listOf("Ada Example")
	}

	// --- Radicale

	private fun put(
		name: String,
		vcard: String,
	) {
		val response = dav("PUT", "$BOOK$name", vcard, "text/vcard; charset=utf-8")
		(response.statusCode() in 200..299) shouldBe true
	}

	private fun get(name: String): HttpResponse<String> = dav("GET", "$BOOK$name")

	private fun etagOf(name: String): String = get(name).headers().firstValue("ETag").get()

	private fun card(
		uid: String,
		name: String,
		email: String,
		extra: String = "",
	): String {
		val (given, family) = name.split(' ')
		return "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:$uid\r\nFN:$name\r\nN:$family;$given;;;\r\n" +
			"EMAIL;TYPE=work:$email\r\n${extra}END:VCARD\r\n"
	}

	companion object {
		private const val USER = "alice"
		private const val PASSWORD = "fictitious-secret"
		private const val BOOK = "/$USER/contacts/"
		private const val PORT = 5232

		private val radicale: GenericContainer<*> =
			GenericContainer(DockerImageName.parse("docker.io/tomsquest/docker-radicale:3.5.4.0"))
				.withExposedPorts(PORT)
				.withCopyToContainer(
					Transferable.of(
						"""
						[server]
						hosts = 0.0.0.0:$PORT
						[auth]
						type = htpasswd
						htpasswd_filename = /etc/radicale/users
						htpasswd_encryption = plain
						[rights]
						type = owner_only
						[storage]
						filesystem_folder = /data/collections
						""".trimIndent(),
					),
					"/etc/radicale/config",
				).withCopyToContainer(Transferable.of("$USER:$PASSWORD\n"), "/etc/radicale/users")
				// Not into /config: the image fills that volume with its default config (no authentication) at start.
				.withCommand("/venv/bin/radicale", "--config", "/etc/radicale/config")
				.waitingFor(Wait.forListeningPort())
				// A socket-activated Podman API service exits when idle while the Spring context starts; the first
				// create then fails with a broken pipe on the stale pooled connection, a second attempt reconnects.
				.withStartupAttempts(2)
				.also { it.start() }

		private fun GenericContainer<*>.baseUrl() = "http://$host:${getMappedPort(PORT)}"

		private val http: HttpClient = HttpClient.newHttpClient()

		/**
		 * [method] on [path] at Radicale as alice, sent once more when Radicale drops the connection (as the client
		 * does). Every request here may repeat: the tests check the outcome, not the status of a repeated write.
		 */
		private fun dav(
			method: String,
			path: String,
			body: String? = null,
			contentType: String = "application/xml; charset=utf-8",
		): HttpResponse<String> {
			val request =
				HttpRequest
					.newBuilder(URI.create(radicale.baseUrl() + path))
					.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
					.header("Authorization", "Basic " + Base64.getEncoder().encodeToString("$USER:$PASSWORD".toByteArray()))
					.apply { if (body != null) header("Content-Type", contentType) }
					.build()
			return try {
				http.send(request, HttpResponse.BodyHandlers.ofString())
			} catch (e: IOException) {
				http.send(request, HttpResponse.BodyHandlers.ofString())
			}
		}
	}
}
