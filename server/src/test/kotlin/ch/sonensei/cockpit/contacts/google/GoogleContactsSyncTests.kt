package ch.sonensei.cockpit.contacts.google

import ch.sonensei.cockpit.contacts.domain.Change
import ch.sonensei.cockpit.contacts.domain.Contact
import ch.sonensei.cockpit.contacts.domain.ContactAddress
import ch.sonensei.cockpit.contacts.domain.ContactChanges
import ch.sonensei.cockpit.contacts.domain.ContactEmail
import ch.sonensei.cockpit.contacts.domain.ContactPhone
import ch.sonensei.cockpit.contacts.domain.ContactService
import ch.sonensei.cockpit.contacts.domain.ContactSource
import ch.sonensei.cockpit.contacts.infra.ContactRepository
import ch.sonensei.cockpit.contacts.infra.ContactTrashContributor
import ch.sonensei.cockpit.core.oauth.domain.OAuthAccessToken
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionDisconnected
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionInfo
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionStatus
import ch.sonensei.cockpit.core.oauth.domain.OAuthException
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.core.oauth.domain.OAuthReconnectRequiredException
import ch.sonensei.cockpit.core.oauth.domain.OAuthResource
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import ch.sonensei.cockpit.support.PostgresTestBase
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * [GoogleContactsSync] against WireMock playing the People API (recorded responses in the resources next to this
 * class), persisting to Postgres: full and incremental listings with sync tokens, an expired token, and writes of
 * local changes with the person's etag (update, stale etag, delete, re-create after a restore).
 *
 * The WireMock URL gives this class its own Spring context; [DirtiesContext] closes it (and its pool) afterwards.
 */
@SpringBootTest
@Import(GoogleContactsSyncTests.TokensConfiguration::class)
@DirtiesContext
class GoogleContactsSyncTests(
	@Autowired private val sync: GoogleContactsSync,
	@Autowired private val service: ContactService,
	@Autowired private val contacts: ContactRepository,
	@Autowired private val trash: ContactTrashContributor,
	@Autowired private val links: GoogleContactLinkRepository,
	@Autowired private val accounts: GoogleContactsAccountRepository,
	@Autowired private val events: ApplicationEventPublisher,
	@Autowired private val tokens: OAuthTokenProvider,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val tx: TransactionTemplate,
) : PostgresTestBase() {
	@TestConfiguration(proxyBeanMethods = false)
	class TokensConfiguration {
		@Bean
		@Primary
		fun fixedTokens(): OAuthTokenProvider = FixedTokens()
	}

	/** A valid token for [CONNECTION] unless [failure] is set. */
	class FixedTokens : OAuthTokenProvider {
		var failure: OAuthException? = null

		override fun accessToken(
			connectionId: UUID,
			resource: OAuthResource,
		): OAuthAccessToken {
			failure?.let { throw it }
			require(connectionId == CONNECTION) { "unknown connection" }
			return OAuthAccessToken(TOKEN, Instant.parse("2030-01-01T00:00:00Z"))
		}

		override fun connection(connectionId: UUID): OAuthConnectionInfo? =
			OAuthConnectionInfo(
				id = connectionId,
				provider = OAuthProvider.GOOGLE,
				email = "alex.example@example.test",
				displayName = null,
				scopes = listOf(GoogleContactsSync.CONTACTS_SCOPE),
				status = OAuthConnectionStatus.CONNECTED,
				lastError = null,
				lastRefreshedAt = null,
				createdAt = Instant.parse("2026-01-01T00:00:00Z"),
				version = 0,
			)
	}

	@BeforeEach
	fun reset() {
		jdbc.execute("delete from contacts_google_link")
		jdbc.execute("delete from contacts_google_account")
		jdbc.execute("delete from contact")
		jdbc.execute("delete from search_document where item_type = 'CONTACT'")
		wireMock.resetAll()
		(tokens as FixedTokens).failure = null
	}

	// --- helpers

	private fun stub(
		mapping: MappingBuilder,
		body: String,
		status: Int = 200,
	) {
		wireMock.stubFor(mapping.willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)))
	}

	private fun recording(name: String): String =
		checkNotNull(GoogleContactsSyncTests::class.java.getResource(name)) { "no recording $name" }.readText()

	private fun listing() = get(urlPathEqualTo(CONNECTIONS))

	/** The recorded two-page full listing (c1001, c1002, then c1003; token `CONTACTS-sync-1`). */
	private fun stubFullListing() {
		stub(listing().withQueryParam("syncToken", absent()).withQueryParam("pageToken", absent()), recording("connections-full-page1.json"))
		stub(
			listing().withQueryParam("syncToken", absent()).withQueryParam("pageToken", equalTo("CONTACTS-page-2")),
			recording("connections-full-page2.json"),
		)
	}

	private fun stubIncremental(body: String) = stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-1")), body)

	private fun syncOk(): GoogleContactsAccount = sync.sync(CONNECTION).also { it.lastError.shouldBeNull() }

	private fun contact(resourceName: String): Contact = contacts.findById(link(resourceName).contactId).get()

	private fun link(resourceName: String): GoogleContactLink =
		links.findByConnectionIdAndResourceName(CONNECTION, resourceName).shouldNotBeNull()

	private fun linkOf(contact: Contact): GoogleContactLink = links.findById(contact.id).get()

	private fun active(): List<String> = contacts.findAllActive().map { it.displayName }

	private fun writes(): List<String> =
		wireMock.allServeEvents
			.map { it.request }
			.filter { it.method.value() != "GET" }
			.reversed()
			.map { "${it.method.value()} ${it.url.substringBefore('?')}" }

	private fun rename(
		contact: Contact,
		familyName: String,
	): Contact = service.update(contact.id, ContactChanges(familyName = Change(familyName), displayName = Change(null))) {}

	// --- pull

	@Test
	fun `a first sync lists every contact page by page and keeps the sync token`() {
		stubFullListing()

		val state = syncOk()

		state.syncToken shouldBe "CONTACTS-sync-1"
		state.lastSuccessAt.shouldNotBeNull()
		active() shouldContainExactlyInAnyOrder listOf("Alex Muster", "+41 44 000 00 02", "Sam Beispiel")
		val alex = contact("people/c1001")
		alex.source shouldBe ContactSource.GOOGLE
		alex.externalId shouldBe "people/c1001"
		alex.givenName shouldBe "Alex"
		alex.familyName shouldBe "Muster"
		// The malformed address Google accepted is dropped.
		alex.emails shouldContainExactly listOf(ContactEmail("alex.muster@example.test", "work"))
		alex.phones shouldContainExactly listOf(ContactPhone("+41 44 000 00 01", "mobile"))
		alex.addresses shouldContainExactly
			listOf(ContactAddress(label = "home", street = "Beispielweg 1", city = "Zürich", postalCode = "8000", country = "CH"))
		alex.organization shouldBe "Example AG"
		alex.title shouldBe "Engineer"
		alex.birthday shouldBe LocalDate.of(1990, 4, 12)
		alex.notesMd shouldBe "Met at the fictitious conference."
		link("people/c1001").etag shouldBe "%EgcBAgkLLjc9GgQBAgUHIgxQaEx1bWJ1dGZ2Zz0="
		link("people/c1001").syncedVersion shouldBe alex.version
		// Only a phone number and a birthday without a year.
		contact("people/c1002").birthday.shouldBeNull()

		val first = wireMock.allServeEvents.map { it.request }.single { it.queryParameter("pageToken").isPresent.not() }
		first.getHeader("Authorization") shouldBe "Bearer $TOKEN"
		first.queryParameter("requestSyncToken").firstValue() shouldBe "true"
		first.queryParameter("personFields").firstValue() shouldContain "emailAddresses"
		writes().shouldBeEmpty()
	}

	@Test
	fun `a later sync applies only the changes since the sync token, deleted persons go to the Trash`() {
		stubFullListing()
		syncOk()
		stubIncremental(recording("connections-incremental.json"))

		val state = syncOk()

		state.syncToken shouldBe "CONTACTS-sync-2"
		active() shouldContainExactlyInAnyOrder listOf("Alex Muster-Meier", "+41 44 000 00 02", "Fictional GmbH")
		contact("people/c1001").phones.shouldBeEmpty()
		link("people/c1001").etag shouldBe "%EgcBAgkLLjc9GgQBAgUHIgxNZXdldGFnMDAxPQ=="
		val sam = contacts.findAll().single { it.displayName == "Sam Beispiel" }
		sam.isDeleted shouldBe true
		linkOf(sam).resourceName.shouldBeNull()
		contact("people/c1004").organization shouldBe "Fictional GmbH"
		writes().shouldBeEmpty()
	}

	@Test
	fun `a person listed with the etag already stored is not changed again`() {
		stubFullListing()
		syncOk()
		val before = contact("people/c1003")
		stubIncremental(recording("connections-full-page2.json").replace("CONTACTS-sync-1", "CONTACTS-sync-2"))

		syncOk().syncToken shouldBe "CONTACTS-sync-2"

		contact("people/c1003").version shouldBe before.version
	}

	@Test
	fun `an expired sync token lists everything again and trashes the contacts Google no longer has`() {
		stubFullListing()
		syncOk()
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-1")), recording("error-400-expired-sync-token.json"), 400)
		stub(
			listing().withQueryParam("syncToken", absent()).withQueryParam("pageToken", absent()),
			recording("connections-full-page2.json").replace("CONTACTS-sync-1", "CONTACTS-sync-3"),
		)

		syncOk().syncToken shouldBe "CONTACTS-sync-3"

		active() shouldContainExactly listOf("Sam Beispiel")
	}

	@Test
	fun `410 Gone for the sync token also starts a full listing`() {
		stubFullListing()
		syncOk()
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-1")), """{"error": {"code": 410}}""", 410)
		stub(listing().withQueryParam("syncToken", absent()).withQueryParam("pageToken", absent()), """{"nextSyncToken": "CONTACTS-sync-4"}""")

		syncOk().syncToken shouldBe "CONTACTS-sync-4"

		active().shouldBeEmpty()
	}

	@Test
	fun `a contact restored from a backup is adopted by the full listing, not duplicated`() {
		val restored =
			service.createSynced(ContactSource.GOOGLE, "people/c1003", ContactChanges(displayName = Change("Sam (from backup)")))
		stubFullListing()

		syncOk()

		linkOf(restored).resourceName shouldBe "people/c1003"
		contacts.findById(restored.id).get().displayName shouldBe "Sam Beispiel"
		contacts.findAll().count { it.externalId == "people/c1003" } shouldBe 1
	}

	@Test
	fun `a rate limit fails the run with a safe message and keeps the sync token`() {
		stubFullListing()
		syncOk()
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-1")), recording("error-429-rate-limit.json"), 429)

		val state = sync.sync(CONNECTION)

		state.lastError shouldBe "Google Contacts rate limit reached (HTTP 429), retrying later"
		state.syncToken shouldBe "CONTACTS-sync-1"
	}

	@Test
	fun `a connection that must be connected again fails the run without calling Google`() {
		(tokens as FixedTokens).failure = OAuthReconnectRequiredException("invalid_grant")

		sync.sync(CONNECTION).lastError shouldBe "The Google account has to be connected again"

		wireMock.allServeEvents.shouldBeEmpty()
	}

	// --- push

	@Test
	fun `a local change is written to Google with the person's etag and not again on the next run`() {
		stubFullListing()
		syncOk()
		val changed = rename(contact("people/c1001"), "Muster-Neu")
		stub(
			patch(urlPathEqualTo("/v1/people/c1001:updateContact")),
			"""{"resourceName": "people/c1001", "etag": "%after-update"}""",
		)
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")

		syncOk()

		writes() shouldContainExactly listOf("PATCH /v1/people/c1001:updateContact")
		val request = wireMock.allServeEvents.map { it.request }.single { it.method.value() == "PATCH" }
		request.queryParameter("updatePersonFields").firstValue() shouldContain "names"
		val body = jsonMapper.readTree(request.bodyAsString)
		body.get("etag").asString() shouldBe "%EgcBAgkLLjc9GgQBAgUHIgxQaEx1bWJ1dGZ2Zz0="
		body
			.get("names")
			.get(0)
			.get("familyName")
			.asString() shouldBe "Muster-Neu"
		body
			.get("emailAddresses")
			.get(0)
			.get("value")
			.asString() shouldBe "alex.muster@example.test"
		body
			.get("birthdays")
			.get(0)
			.get("date")
			.get("year")
			.asInt() shouldBe 1990
		linkOf(changed).etag shouldBe "%after-update"
		linkOf(changed).syncedVersion shouldBe changed.version

		wireMock.resetRequests()
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-2")), """{"nextSyncToken": "CONTACTS-sync-3"}""")
		syncOk()
		writes().shouldBeEmpty()
	}

	@Test
	fun `when the person changed at Google meanwhile, Google's version wins`() {
		stubFullListing()
		syncOk()
		rename(contact("people/c1001"), "Changed-Here")
		stub(patch(urlPathEqualTo("/v1/people/c1001:updateContact")), recording("error-400-etag-mismatch.json"), 400)
		stub(get(urlPathEqualTo("/v1/people/c1001")), recording("person-c1001-current.json"))
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")

		syncOk()

		val alex = contact("people/c1001")
		alex.displayName shouldBe "Alex Changed-At-Google"
		link("people/c1001").etag shouldBe "%EgcBAgkLLjc9GgQBAgUHIgxHb29nbGVXaW5zPQ=="
		link("people/c1001").syncedVersion shouldBe alex.version
	}

	@Test
	fun `a contact deleted at Google before the push goes to the Trash`() {
		stubFullListing()
		syncOk()
		val changed = rename(contact("people/c1001"), "Changed-Here")
		stub(patch(urlPathEqualTo("/v1/people/c1001:updateContact")), """{"error": {"code": 404, "status": "NOT_FOUND"}}""", 404)
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")

		syncOk()

		contacts.findById(changed.id).get().isDeleted shouldBe true
		linkOf(changed).resourceName.shouldBeNull()
	}

	@Test
	fun `a contact moved to the Trash is deleted at Google, and created there again when restored`() {
		stubFullListing()
		syncOk()
		val sam = contact("people/c1003")
		service.delete(sam.id) {}
		stub(delete(urlPathEqualTo("/v1/people/c1003:deleteContact")), "{}")
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")

		syncOk()

		writes() shouldContainExactly listOf("DELETE /v1/people/c1003:deleteContact")
		linkOf(sam).resourceName.shouldBeNull()

		tx.execute { trash.restore(sam.id) }
		val restored = contacts.findById(sam.id).get()
		wireMock.resetRequests()
		stub(post(urlPathEqualTo("/v1/people:createContact")), """{"resourceName": "people/c2001", "etag": "%created"}""")
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-2")), """{"nextSyncToken": "CONTACTS-sync-3"}""")

		syncOk()

		writes() shouldContainExactly listOf("POST /v1/people:createContact")
		val created =
			jsonMapper.readTree(
				wireMock.allServeEvents
					.single { it.request.method.value() == "POST" }
					.request.bodyAsString,
			)
		created
			.get("names")
			.get(0)
			.get("givenName")
			.asString() shouldBe "Sam"
		created.has("etag") shouldBe false
		val after = contacts.findById(sam.id).get()
		after.externalId shouldBe "people/c2001"
		after.version shouldBe restored.version
		linkOf(after).etag shouldBe "%created"
		linkOf(after).syncedVersion shouldBe after.version
	}

	@Test
	fun `a write Google refuses is skipped and reported, the rest of the run goes on`() {
		stubFullListing()
		syncOk()
		rename(contact("people/c1001"), "Changed-Here")
		stub(patch(urlPathEqualTo("/v1/people/c1001:updateContact")), """{"error": {"code": 400, "status": "INVALID_ARGUMENT"}}""", 400)
		stubIncremental(recording("connections-incremental.json"))

		val state = sync.sync(CONNECTION)

		state.lastError shouldBe "1 contacts could not be synced with Google"
		state.syncToken shouldBe "CONTACTS-sync-2"
		contact("people/c1004").organization shouldBe "Fictional GmbH"
	}

	// --- disconnect

	@Test
	fun `disconnecting the Google account forgets its sync state, the contacts stay`() {
		stubFullListing()
		syncOk()

		tx.executeWithoutResult { events.publishEvent(OAuthConnectionDisconnected(CONNECTION, OAuthProvider.GOOGLE)) }

		accounts.findById(CONNECTION).isPresent shouldBe false
		links.findAll().shouldBeEmpty()
		active().size shouldBe 3
	}

	companion object {
		const val TOKEN = "fictitious-access-token"
		const val CONNECTIONS = "/v1/people/me/connections"
		val CONNECTION: UUID = UUID.fromString("0192a5f0-0000-7000-8000-0000000000c1")

		val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

		@JvmStatic
		@AfterAll
		fun stop() = wireMock.stop()

		@JvmStatic
		@DynamicPropertySource
		fun googleApi(registry: DynamicPropertyRegistry) {
			registry.add("cockpit.contacts.google.api-url") { wireMock.baseUrl() }
		}
	}
}
