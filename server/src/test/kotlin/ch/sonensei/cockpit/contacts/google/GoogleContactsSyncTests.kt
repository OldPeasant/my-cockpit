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
import ch.sonensei.cockpit.core.persistence.UuidV7
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
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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
	@Autowired private val context: ApplicationContext,
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

		/** When set, a token request counts [entered] down and waits for [gate] (to hold a run in the middle). */
		var entered: CountDownLatch? = null
		var gate: CountDownLatch? = null

		/** When set, runs before the token of the n-th Google call of a run is handed out (n counts from 1). */
		var beforeCall: ((Int) -> Unit)? = null
		var calls = 0

		override fun accessToken(
			connectionId: UUID,
			resource: OAuthResource,
		): OAuthAccessToken {
			calls++
			beforeCall?.invoke(calls)
			entered?.countDown()
			gate?.let { check(it.await(10, TimeUnit.SECONDS)) { "gate not opened" } }
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
		(tokens as FixedTokens).apply {
			failure = null
			entered = null
			gate = null
			beforeCall = null
			calls = 0
		}
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

	private fun contact(resourceName: String): Contact = contacts.findById(checkNotNull(link(resourceName).contactId)).get()

	private fun link(resourceName: String): GoogleContactLink =
		links.findByConnectionIdAndResourceName(CONNECTION, resourceName).shouldNotBeNull()

	private fun linkOf(contact: Contact): GoogleContactLink = links.findByContactId(contact.id).shouldNotBeNull()

	/** The person [resourceName] as the recorded listing [recording] has it (with the etag the sync stored). */
	private fun listed(
		resourceName: String,
		recording: String = if (resourceName == "people/c1003") "connections-full-page2.json" else "connections-full-page1.json",
	): String {
		val person =
			jsonMapper
				.readTree(recording(recording))
				.path("connections")
				.values()
				.single { it.path("resourceName").asString() == resourceName }
		return jsonMapper.writeValueAsString(person)
	}

	/** `people.get` of [resourceName] answers [body] (by default: unchanged since the recorded full listing). */
	private fun stubPerson(
		resourceName: String,
		body: String = listed(resourceName),
		status: Int = 200,
	) = stub(get(urlPathEqualTo("/v1/$resourceName")), body, status)

	/**
	 * Alex (people/c1001) is unchanged at Google when the push reads him, then changes there before the write: the
	 * write answers [writeBody] with [writeStatus], and the read after it answers [afterBody] with [afterStatus].
	 */
	private fun stubChangedBeforeWrite(
		writeBody: String,
		writeStatus: Int,
		afterBody: String,
		afterStatus: Int,
	) {
		val person = get(urlPathEqualTo("/v1/people/c1001")).inScenario("write")
		stub(person.whenScenarioStateIs(Scenario.STARTED), listed("people/c1001"))
		stub(
			patch(urlPathEqualTo("/v1/people/c1001:updateContact")).inScenario("write").willSetStateTo("changed"),
			writeBody,
			writeStatus,
		)
		stub(get(urlPathEqualTo("/v1/people/c1001")).inScenario("write").whenScenarioStateIs("changed"), afterBody, afterStatus)
	}

	private fun patchRequest() = wireMock.allServeEvents.map { it.request }.single { it.method.value() == "PATCH" }

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
	fun `an incremental listing over several pages sends the sync token with every page and trashes nothing it did not list`() {
		stubFullListing()
		syncOk()
		stub(
			listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-1")).withQueryParam("pageToken", absent()),
			"""
			{"connections": [
			  {"resourceName": "people/c1001", "etag": "%inc-c1001",
			   "names": [{"metadata": {"primary": true}, "displayName": "Alex Muster-Meier",
			              "givenName": "Alex", "familyName": "Muster-Meier"}]}
			], "nextPageToken": "CONTACTS-inc-page-2"}
			""",
		)
		stub(
			listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-1")).withQueryParam("pageToken", equalTo("CONTACTS-inc-page-2")),
			"""
			{"connections": [
			  {"resourceName": "people/c1004", "etag": "%inc-c1004", "organizations": [{"name": "Fictional GmbH"}]}
			], "nextSyncToken": "CONTACTS-sync-2"}
			""",
		)
		wireMock.resetRequests()

		syncOk().syncToken shouldBe "CONTACTS-sync-2"

		// Persons an incremental listing does not mention did not change; they are not moved to the Trash.
		active() shouldContainExactlyInAnyOrder listOf("Alex Muster-Meier", "+41 44 000 00 02", "Sam Beispiel", "Fictional GmbH")
		val pages = wireMock.allServeEvents.map { it.request }.filter { it.url.startsWith(CONNECTIONS) }
		pages.size shouldBe 2
		pages.forEach {
			it.queryParameter("syncToken").firstValue() shouldBe "CONTACTS-sync-1"
			it.queryParameter("requestSyncToken").firstValue() shouldBe "true"
		}
		link("people/c1001").etag shouldBe "%inc-c1001"
	}

	@Test
	fun `a full listing after an expired token that fails on a later page trashes nothing`() {
		stubFullListing()
		syncOk()
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-1")), recording("error-400-expired-sync-token.json"), 400)
		stub(
			listing().withQueryParam("syncToken", absent()).withQueryParam("pageToken", equalTo("CONTACTS-page-2")),
			"""{"error": {"code": 503, "status": "UNAVAILABLE"}}""",
			503,
		)

		val state = sync.sync(CONNECTION)

		state.lastError shouldBe "Google Contacts failed (HTTP 503)"
		// Sam (people/c1003) is on the page that failed: an incomplete listing must not count him as gone.
		active() shouldContainExactlyInAnyOrder listOf("Alex Muster", "+41 44 000 00 02", "Sam Beispiel")
		link("people/c1003").contactId.shouldNotBeNull()
	}

	@Test
	fun `a full listing after an expired token leaves a restored contact that waits for its create alone`() {
		val sam = trashedAndRestoredSam()
		stub(post(urlPathEqualTo("/v1/people:createContact")), """{"error": {"code": 400, "status": "INVALID_ARGUMENT"}}""", 400)
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-2")), recording("error-400-expired-sync-token.json"), 400)
		stub(listing().withQueryParam("syncToken", absent()).withQueryParam("pageToken", absent()), recording("connections-full-page1.json"))
		stub(
			listing().withQueryParam("syncToken", absent()).withQueryParam("pageToken", equalTo("CONTACTS-page-2")),
			"""{"nextSyncToken": "CONTACTS-sync-5"}""",
		)

		val state = sync.sync(CONNECTION)

		state.lastError shouldBe "1 contacts could not be synced with Google"
		state.syncToken shouldBe "CONTACTS-sync-5"
		contacts.findById(sam.id).get().isDeleted shouldBe false
		linkOf(sam).resourceName.shouldBeNull()
		active() shouldContainExactlyInAnyOrder listOf("Alex Muster", "+41 44 000 00 02", "Sam Beispiel")
	}

	@Test
	fun `a full listing after an expired token that lags behind does not trash the contact the push just created`() {
		val sam = trashedAndRestoredSam()
		stub(post(urlPathEqualTo("/v1/people:createContact")), """{"resourceName": "people/c2001", "etag": "%created"}""")
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-2")), recording("error-400-expired-sync-token.json"), 400)
		// The listing does not show people/c2001 yet: Google's listings lag behind writes.
		stub(listing().withQueryParam("syncToken", absent()).withQueryParam("pageToken", absent()), recording("connections-full-page1.json"))
		stub(
			listing().withQueryParam("syncToken", absent()).withQueryParam("pageToken", equalTo("CONTACTS-page-2")),
			"""{"nextSyncToken": "CONTACTS-sync-5"}""",
		)

		syncOk().syncToken shouldBe "CONTACTS-sync-5"

		writes() shouldContainExactly listOf("POST /v1/people:createContact")
		val after = contacts.findById(sam.id).get()
		after.isDeleted shouldBe false
		after.externalId shouldBe "people/c2001"
		linkOf(after).resourceName shouldBe "people/c2001"
		active() shouldContainExactlyInAnyOrder listOf("Alex Muster", "+41 44 000 00 02", "Sam Beispiel")
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
		stubPerson("people/c1001")
		stub(
			patch(urlPathEqualTo("/v1/people/c1001:updateContact")),
			"""{"resourceName": "people/c1001", "etag": "%after-update"}""",
		)
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")

		syncOk()

		writes() shouldContainExactly listOf("PATCH /v1/people/c1001:updateContact")
		val request = patchRequest()
		// Only the changed field group: the others (emails, the birthday, ...) stay as they are at Google.
		request.queryParameter("updatePersonFields").firstValue() shouldBe "names"
		val body = jsonMapper.readTree(request.bodyAsString)
		body.get("etag").asString() shouldBe "%EgcBAgkLLjc9GgQBAgUHIgxQaEx1bWJ1dGZ2Zz0="
		body.propertyNames().asSequence().toList() shouldContainExactlyInAnyOrder listOf("etag", "names")
		val name = body.get("names").get(0)
		name.get("familyName").asString() shouldBe "Muster-Neu"
		name.get("givenName").asString() shouldBe "Alex"
		name.has("unstructuredName") shouldBe false
		name.has("displayName") shouldBe false
		linkOf(changed).etag shouldBe "%after-update"
		linkOf(changed).syncedVersion shouldBe changed.version

		wireMock.resetRequests()
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-2")), """{"nextSyncToken": "CONTACTS-sync-3"}""")
		syncOk()
		writes().shouldBeEmpty()
	}

	@Test
	fun `editing only the note of a phone-only contact writes only the biography, not its year-less birthday or a name`() {
		stubFullListing()
		syncOk()
		val phoneOnly = contact("people/c1002")
		val changed = service.update(phoneOnly.id, ContactChanges(notesMd = Change("Call after five."))) {}
		stubPerson("people/c1002")
		stub(patch(urlPathEqualTo("/v1/people/c1002:updateContact")), """{"resourceName": "people/c1002", "etag": "%noted"}""")
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")

		syncOk()

		val request = patchRequest()
		request.queryParameter("updatePersonFields").firstValue() shouldBe "biographies"
		val body = jsonMapper.readTree(request.bodyAsString)
		body.has("birthdays") shouldBe false
		body.has("names") shouldBe false
		body.has("phoneNumbers") shouldBe false
		val biography = body.get("biographies").get(0)
		biography.get("value").asString() shouldBe "Call after five."
		biography.get("contentType").asString() shouldBe "TEXT_PLAIN"
		linkOf(changed).etag shouldBe "%noted"
		contacts.findById(changed.id).get().displayName shouldBe "+41 44 000 00 02"
	}

	@Test
	fun `editing the note of an organization-only contact does not give it the organization as a name`() {
		stubFullListing()
		syncOk()
		stubIncremental(recording("connections-incremental.json"))
		syncOk()
		val company = contact("people/c1004")
		company.displayName shouldBe "Fictional GmbH"
		service.update(company.id, ContactChanges(notesMd = Change("Invoices quarterly."))) {}
		stubPerson("people/c1004", listed("people/c1004", "connections-incremental.json"))
		stub(patch(urlPathEqualTo("/v1/people/c1004:updateContact")), """{"resourceName": "people/c1004", "etag": "%noted"}""")
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-2")), """{"nextSyncToken": "CONTACTS-sync-3"}""")

		syncOk()

		patchRequest().queryParameter("updatePersonFields").firstValue() shouldBe "biographies"
		jsonMapper.readTree(patchRequest().bodyAsString).has("names") shouldBe false
	}

	@Test
	fun `an organization-only contact edited twice in a row never gets its old organization as a name`() {
		stubFullListing()
		syncOk()
		stubIncremental(recording("connections-incremental.json"))
		syncOk()
		val company = contact("people/c1004")
		// First edit: the organization (the display name was derived from it).
		service.update(company.id, ContactChanges(organization = Change("Other GmbH"))) {}
		stubPerson("people/c1004", listed("people/c1004", "connections-incremental.json"))
		stub(patch(urlPathEqualTo("/v1/people/c1004:updateContact")), """{"resourceName": "people/c1004", "etag": "%renamed"}""")
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-2")), """{"nextSyncToken": "CONTACTS-sync-3"}""")

		syncOk()

		patchRequest().queryParameter("updatePersonFields").firstValue() shouldBe "organizations"
		val renamed = contacts.findById(company.id).get()
		renamed.displayName shouldBe "Other GmbH"
		linkOf(renamed).syncedVersion shouldBe renamed.version
		linkOf(renamed).etag shouldBe "%renamed"

		// Second edit: only the note. Google now has the new organization and still no name.
		service.update(company.id, ContactChanges(notesMd = Change("Invoices quarterly."))) {}
		wireMock.resetAll()
		stubPerson(
			"people/c1004",
			"""{"resourceName": "people/c1004", "etag": "%renamed", "organizations": [{"metadata": {"primary": true}, "name": "Other GmbH"}]}""",
		)
		stub(patch(urlPathEqualTo("/v1/people/c1004:updateContact")), """{"resourceName": "people/c1004", "etag": "%noted"}""")
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-3")), """{"nextSyncToken": "CONTACTS-sync-4"}""")

		syncOk()

		patchRequest().queryParameter("updatePersonFields").firstValue() shouldBe "biographies"
		jsonMapper.readTree(patchRequest().bodyAsString).has("names") shouldBe false
		contacts.findById(company.id).get().displayName shouldBe "Other GmbH"
	}

	/** Syncs twice so the organization-only person c1004 is linked, then changes its organization to "Other GmbH". */
	private fun companyWithNewOrganization(): Contact {
		stubFullListing()
		syncOk()
		stubIncremental(recording("connections-incremental.json"))
		syncOk()
		val company = contact("people/c1004")
		company.displayName shouldBe "Fictional GmbH"
		val changed = service.update(company.id, ContactChanges(organization = Change("Other GmbH"))) {}
		wireMock.resetAll()
		stubPerson("people/c1004", listed("people/c1004", "connections-incremental.json"))
		stub(patch(urlPathEqualTo("/v1/people/c1004:updateContact")), """{"resourceName": "people/c1004", "etag": "%renamed"}""")
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-2")), """{"nextSyncToken": "CONTACTS-sync-3"}""")
		return changed
	}

	/** Runs [edit] while the push of the run below is between reading the person (call 1) and writing it (call 2). */
	private fun duringWrite(edit: () -> Unit) {
		(tokens as FixedTokens).apply {
			calls = 0
			beforeCall = { if (it == 2) edit() }
		}
	}

	@Test
	fun `an organization-only contact whose note is edited while its new organization is pushed never gets the old one as a name`() {
		val company = companyWithNewOrganization()
		duringWrite { service.update(company.id, ContactChanges(notesMd = Change("Invoices quarterly."))) {} }

		syncOk()

		patchRequest().queryParameter("updatePersonFields").firstValue() shouldBe "organizations"
		// The note edited meanwhile is not synced yet, so the next run pushes it.
		val edited = contacts.findById(company.id).get()
		edited.notesMd shouldBe "Invoices quarterly."
		linkOf(edited).syncedVersion shouldBeLessThan edited.version

		(tokens as FixedTokens).beforeCall = null
		wireMock.resetAll()
		stubPerson(
			"people/c1004",
			"""{"resourceName": "people/c1004", "etag": "%renamed", "organizations": [{"metadata": {"primary": true}, "name": "Other GmbH"}]}""",
		)
		stub(patch(urlPathEqualTo("/v1/people/c1004:updateContact")), """{"resourceName": "people/c1004", "etag": "%noted"}""")
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-3")), """{"nextSyncToken": "CONTACTS-sync-4"}""")

		syncOk()

		// Google has no name for the person: the old derived display name must not be written as one.
		patchRequest().queryParameter("updatePersonFields").firstValue() shouldBe "biographies"
		jsonMapper.readTree(patchRequest().bodyAsString).has("names") shouldBe false
		contacts.findById(company.id).get().displayName shouldBe "Other GmbH"
	}

	@Test
	fun `a contact moved to the Trash during the push of its change stays there and is deleted at Google by the next run`() {
		val company = companyWithNewOrganization()
		duringWrite { service.delete(company.id) {} }

		syncOk()

		patchRequest().queryParameter("updatePersonFields").firstValue() shouldBe "organizations"
		val trashed = contacts.findById(company.id).get()
		trashed.isDeleted shouldBe true
		linkOf(trashed).resourceName shouldBe "people/c1004"

		(tokens as FixedTokens).beforeCall = null
		wireMock.resetAll()
		stub(delete(urlPathEqualTo("/v1/people/c1004:deleteContact")), "{}")
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-3")), """{"nextSyncToken": "CONTACTS-sync-4"}""")

		syncOk()

		writes() shouldContainExactly listOf("DELETE /v1/people/c1004:deleteContact")
		contacts.findById(company.id).get().isDeleted shouldBe true
		linkOf(trashed).resourceName.shouldBeNull()
	}

	@Test
	fun `a local change Google has no field for is marked synced without a write`() {
		stubFullListing()
		syncOk()
		// The display name of a contact with given and family name is derived at Google.
		val changed = service.update(contact("people/c1001").id, ContactChanges(displayName = Change("Alex (Example AG)"))) {}
		stubPerson("people/c1001")
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")

		syncOk()

		writes().shouldBeEmpty()
		linkOf(changed).syncedVersion shouldBe changed.version
		linkOf(changed).etag shouldBe "%EgcBAgkLLjc9GgQBAgUHIgxQaEx1bWJ1dGZ2Zz0="
	}

	@Test
	fun `a change of several field groups is written in one update that names each group`() {
		stubFullListing()
		syncOk()
		val alex = contact("people/c1001")
		val changed =
			service.update(
				alex.id,
				ContactChanges(
					emails = Change(alex.emails + ContactEmail("alex.private@example.test", "home")),
					phones = Change(listOf(ContactPhone("+41 44 000 00 09", "mobile"))),
				),
			) {}
		stubPerson("people/c1001")
		stub(patch(urlPathEqualTo("/v1/people/c1001:updateContact")), """{"resourceName": "people/c1001", "etag": "%two-groups"}""")
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")

		syncOk()

		val request = patchRequest()
		request.queryParameter("updatePersonFields").firstValue() shouldBe "emailAddresses,phoneNumbers"
		request.queryParameter("personFields").firstValue() shouldBe GooglePersonMapping.PERSON_FIELDS
		val body = jsonMapper.readTree(request.bodyAsString)
		body.propertyNames().asSequence().toList() shouldContainExactlyInAnyOrder listOf("etag", "emailAddresses", "phoneNumbers")
		body.get("etag").asString() shouldBe "%EgcBAgkLLjc9GgQBAgUHIgxQaEx1bWJ1dGZ2Zz0="
		// The unchanged address stays Google's entry, the new one is added, the entry the cockpit cannot read is kept.
		body.get("emailAddresses").values().map { it.get("value").asString() } shouldContainExactly
			listOf("alex.muster@example.test", "alex.private@example.test", "not-an-address")
		body
			.get("emailAddresses")
			.get(1)
			.get("type")
			.asString() shouldBe "home"
		val phone = body.get("phoneNumbers").single()
		phone.get("value").asString() shouldBe "+41 44 000 00 09"
		phone.get("type").asString() shouldBe "mobile"
		phone.has("canonicalForm") shouldBe false
		linkOf(changed).etag shouldBe "%two-groups"
		linkOf(changed).syncedVersion shouldBe changed.version
	}

	@Test
	fun `a rate limit on a write fails the run before the pull, keeps the sync token, and the change is pushed later`() {
		stubFullListing()
		syncOk()
		val changed = rename(contact("people/c1001"), "Muster-Neu")
		stubPerson("people/c1001")
		stub(patch(urlPathEqualTo("/v1/people/c1001:updateContact")), recording("error-429-rate-limit.json"), 429)
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")
		wireMock.resetRequests()

		val state = sync.sync(CONNECTION)

		state.lastError shouldBe "Google Contacts rate limit reached (HTTP 429), retrying later"
		state.syncToken shouldBe "CONTACTS-sync-1"
		wireMock.allServeEvents.none { it.request.url.startsWith(CONNECTIONS) } shouldBe true
		linkOf(changed).syncedVersion shouldBeLessThan changed.version

		wireMock.resetAll()
		stubPerson("people/c1001")
		stub(patch(urlPathEqualTo("/v1/people/c1001:updateContact")), """{"resourceName": "people/c1001", "etag": "%after-limit"}""")
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")
		syncOk().syncToken shouldBe "CONTACTS-sync-2"
		writes() shouldContainExactly listOf("PATCH /v1/people/c1001:updateContact")
		linkOf(changed).etag shouldBe "%after-limit"
	}

	@Test
	fun `when the person changed at Google since the last sync, reading it first shows it and Google's version wins`() {
		stubFullListing()
		syncOk()
		rename(contact("people/c1001"), "Changed-Here")
		stubPerson("people/c1001", recording("person-c1001-current.json"))
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")

		syncOk()

		writes().shouldBeEmpty()
		val alex = contact("people/c1001")
		alex.displayName shouldBe "Alex Changed-At-Google"
		link("people/c1001").etag shouldBe "%EgcBAgkLLjc9GgQBAgUHIgxHb29nbGVXaW5zPQ=="
		link("people/c1001").syncedVersion shouldBe alex.version
	}

	@Test
	fun `when the person changes at Google between the read and the write, Google's version wins`() {
		stubFullListing()
		syncOk()
		rename(contact("people/c1001"), "Changed-Here")
		stubChangedBeforeWrite(recording("error-400-etag-mismatch.json"), 400, recording("person-c1001-current.json"), 200)
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")

		syncOk()

		writes() shouldContainExactly listOf("PATCH /v1/people/c1001:updateContact")
		val alex = contact("people/c1001")
		alex.displayName shouldBe "Alex Changed-At-Google"
		link("people/c1001").etag shouldBe "%EgcBAgkLLjc9GgQBAgUHIgxHb29nbGVXaW5zPQ=="
		link("people/c1001").syncedVersion shouldBe alex.version
	}

	@Test
	fun `412 for the etag also means Google's version wins`() {
		stubFullListing()
		syncOk()
		rename(contact("people/c1001"), "Changed-Here")
		stubChangedBeforeWrite("""{"error": {"code": 412}}""", 412, recording("person-c1001-current.json"), 200)
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")

		syncOk()

		writes() shouldContainExactly listOf("PATCH /v1/people/c1001:updateContact")
		contact("people/c1001").displayName shouldBe "Alex Changed-At-Google"
	}

	@Test
	fun `a contact deleted at Google before the push goes to the Trash without a write`() {
		stubFullListing()
		syncOk()
		val changed = rename(contact("people/c1001"), "Changed-Here")
		stubPerson("people/c1001", """{"error": {"code": 404, "status": "NOT_FOUND"}}""", 404)
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")

		syncOk()

		writes().shouldBeEmpty()
		contacts.findById(changed.id).get().isDeleted shouldBe true
		linkOf(changed).resourceName.shouldBeNull()
	}

	@Test
	fun `a contact deleted at Google between the read and the write goes to the Trash`() {
		stubFullListing()
		syncOk()
		val changed = rename(contact("people/c1001"), "Changed-Here")
		stubPerson("people/c1001")
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
	fun `a contact purged from the Trash before the push is still deleted at Google`() {
		stubFullListing()
		syncOk()
		val sam = contact("people/c1003")
		service.delete(sam.id) {}
		tx.execute { trash.purge(sam.id) }
		stub(delete(urlPathEqualTo("/v1/people/c1003:deleteContact")), "{}")
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")

		syncOk()

		writes() shouldContainExactly listOf("DELETE /v1/people/c1003:deleteContact")
		links.findByConnectionIdAndResourceName(CONNECTION, "people/c1003").shouldBeNull()
		links.findAll().size shouldBe 2
		contacts.findAll().none { it.displayName == "Sam Beispiel" } shouldBe true
	}

	@Test
	fun `a purged contact whose delete Google refuses is not imported again, and the delete is retried`() {
		stubFullListing()
		syncOk()
		val sam = contact("people/c1003")
		service.delete(sam.id) {}
		tx.execute { trash.purge(sam.id) }
		stub(delete(urlPathEqualTo("/v1/people/c1003:deleteContact")), """{"error": {"code": 400, "status": "INVALID_ARGUMENT"}}""", 400)
		// Google still lists the person (e.g. it changed there meanwhile).
		stubIncremental(
			recording("connections-full-page2.json")
				.replace("CONTACTS-sync-1", "CONTACTS-sync-2")
				.replace("%EgcBAgkLLjc9GgQBAgUHIgxkNGNvTWZ2c0RvPQ==", "%changed-at-google"),
		)

		sync.sync(CONNECTION).lastError shouldBe "1 contacts could not be synced with Google"

		contacts.findAll().none { it.displayName == "Sam Beispiel" } shouldBe true
		link("people/c1003").contactId.shouldBeNull()

		wireMock.resetAll()
		stub(delete(urlPathEqualTo("/v1/people/c1003:deleteContact")), """{"error": {"code": 404, "status": "NOT_FOUND"}}""", 404)
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-2")), """{"nextSyncToken": "CONTACTS-sync-3"}""")
		syncOk()
		writes() shouldContainExactly listOf("DELETE /v1/people/c1003:deleteContact")
		links.findByConnectionIdAndResourceName(CONNECTION, "people/c1003").shouldBeNull()
	}

	@Test
	fun `a purged contact whose delete Google refuses is forgotten once Google lists it as deleted, no delete is sent again`() {
		stubFullListing()
		syncOk()
		val sam = contact("people/c1003")
		service.delete(sam.id) {}
		tx.execute { trash.purge(sam.id) }
		stub(delete(urlPathEqualTo("/v1/people/c1003:deleteContact")), """{"error": {"code": 400, "status": "INVALID_ARGUMENT"}}""", 400)
		// The incremental listing reports people/c1003 as deleted at Google (and changes c1001, adds c1004).
		stubIncremental(recording("connections-incremental.json"))

		sync.sync(CONNECTION).lastError shouldBe "1 contacts could not be synced with Google"

		links.findByConnectionIdAndResourceName(CONNECTION, "people/c1003").shouldBeNull()
		links.findAll().none { it.contactId == null } shouldBe true
		contacts.findAll().none { it.displayName == "Sam Beispiel" } shouldBe true

		wireMock.resetAll()
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-2")), """{"nextSyncToken": "CONTACTS-sync-3"}""")
		syncOk()
		writes().shouldBeEmpty()
	}

	@Test
	fun `a contact purged after its person was deleted at Google leaves no link behind`() {
		stubFullListing()
		syncOk()
		stubIncremental(recording("connections-incremental.json"))
		syncOk()
		val sam = contacts.findAll().single { it.displayName == "Sam Beispiel" }
		tx.execute { trash.purge(sam.id) }
		wireMock.resetRequests()
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-2")), """{"nextSyncToken": "CONTACTS-sync-3"}""")

		syncOk()

		writes().shouldBeEmpty()
		links.findAll().none { it.contactId == null } shouldBe true
	}

	@Test
	fun `a write Google refuses is skipped and reported, the rest of the run goes on`() {
		stubFullListing()
		syncOk()
		rename(contact("people/c1001"), "Changed-Here")
		stubPerson("people/c1001")
		stub(patch(urlPathEqualTo("/v1/people/c1001:updateContact")), """{"error": {"code": 400, "status": "INVALID_ARGUMENT"}}""", 400)
		stubIncremental(recording("connections-incremental.json"))

		val state = sync.sync(CONNECTION)

		state.lastError shouldBe "1 contacts could not be synced with Google"
		state.syncToken shouldBe "CONTACTS-sync-2"
		contact("people/c1004").organization shouldBe "Fictional GmbH"
	}

	@Test
	fun `a person deleted at Google after a stale etag goes to the Trash`() {
		stubFullListing()
		syncOk()
		val changed = rename(contact("people/c1001"), "Changed-Here")
		stubChangedBeforeWrite(recording("error-400-etag-mismatch.json"), 400, """{"error": {"code": 404, "status": "NOT_FOUND"}}""", 404)
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")

		syncOk()

		writes() shouldContainExactly listOf("PATCH /v1/people/c1001:updateContact")
		contacts.findById(changed.id).get().isDeleted shouldBe true
		linkOf(changed).resourceName.shouldBeNull()
	}

	@Test
	fun `a person Google does not return after a stale etag is reported, the local change is pushed again later`() {
		stubFullListing()
		syncOk()
		val changed = rename(contact("people/c1001"), "Changed-Here")
		stubChangedBeforeWrite(
			recording("error-400-etag-mismatch.json"),
			400,
			"""{"error": {"code": 400, "status": "INVALID_ARGUMENT"}}""",
			400,
		)
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")

		sync.sync(CONNECTION).lastError shouldBe "1 contacts could not be synced with Google"

		contacts.findById(changed.id).get().familyName shouldBe "Changed-Here"
		linkOf(changed).syncedVersion shouldBeLessThan changed.version

		wireMock.resetAll()
		stubPerson("people/c1001")
		stub(patch(urlPathEqualTo("/v1/people/c1001:updateContact")), """{"resourceName": "people/c1001", "etag": "%later"}""")
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-2")), """{"nextSyncToken": "CONTACTS-sync-3"}""")
		syncOk()
		writes() shouldContainExactly listOf("PATCH /v1/people/c1001:updateContact")
		linkOf(changed).etag shouldBe "%later"
	}

	@Test
	fun `a person Google refuses to return before the write is reported, nothing is written`() {
		stubFullListing()
		syncOk()
		val changed = rename(contact("people/c1001"), "Changed-Here")
		stubPerson("people/c1001", """{"error": {"code": 400, "status": "INVALID_ARGUMENT"}}""", 400)
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")

		sync.sync(CONNECTION).lastError shouldBe "1 contacts could not be synced with Google"

		writes().shouldBeEmpty()
		linkOf(changed).syncedVersion shouldBeLessThan changed.version
	}

	@Test
	fun `a stored resource name that is not people slash id fails only that contact, not the run`() {
		stubFullListing()
		syncOk()
		val changed = rename(contact("people/c1001"), "Changed-Here")
		jdbc.update("update contacts_google_link set resource_name = ? where contact_id = ?", "people/../me", changed.id)
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")

		val state = sync.sync(CONNECTION)

		state.lastError shouldBe "1 contacts could not be synced with Google"
		state.syncToken shouldBe "CONTACTS-sync-2"
		writes().shouldBeEmpty()
		wireMock.allServeEvents.none { it.request.url.contains("people/..") } shouldBe true
	}

	@Test
	fun `a delete Google refuses is retried by the next run, 404 counts as deleted`() {
		stubFullListing()
		syncOk()
		val sam = contact("people/c1003")
		service.delete(sam.id) {}
		stub(delete(urlPathEqualTo("/v1/people/c1003:deleteContact")), """{"error": {"code": 400, "status": "INVALID_ARGUMENT"}}""", 400)
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")

		sync.sync(CONNECTION).lastError shouldBe "1 contacts could not be synced with Google"
		linkOf(sam).resourceName shouldBe "people/c1003"

		wireMock.resetAll()
		stub(delete(urlPathEqualTo("/v1/people/c1003:deleteContact")), """{"error": {"code": 404, "status": "NOT_FOUND"}}""", 404)
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-2")), """{"nextSyncToken": "CONTACTS-sync-3"}""")
		syncOk()
		writes() shouldContainExactly listOf("DELETE /v1/people/c1003:deleteContact")
		linkOf(sam).resourceName.shouldBeNull()
	}

	@Test
	fun `a contact in the Trash here stays there when Google changes it before the delete was pushed`() {
		stubFullListing()
		syncOk()
		val alex = contact("people/c1001")
		service.delete(alex.id) {}
		stub(delete(urlPathEqualTo("/v1/people/c1001:deleteContact")), """{"error": {"code": 400, "status": "INVALID_ARGUMENT"}}""", 400)
		stubIncremental(recording("connections-incremental.json"))

		sync.sync(CONNECTION)

		contacts.findById(alex.id).get().isDeleted shouldBe true
		contacts.findById(alex.id).get().familyName shouldBe "Muster"
		linkOf(alex).resourceName shouldBe "people/c1001"
	}

	@Test
	fun `a create Google answers without a resource name is reported and tried again by the next run`() {
		val sam = trashedAndRestoredSam()
		stub(post(urlPathEqualTo("/v1/people:createContact")), "{}")

		sync.sync(CONNECTION).lastError shouldBe "1 contacts could not be synced with Google"

		linkOf(sam).resourceName.shouldBeNull()
		wireMock.resetAll()
		stub(post(urlPathEqualTo("/v1/people:createContact")), """{"resourceName": "people/c2002", "etag": "%c"}""")
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-3")), """{"nextSyncToken": "CONTACTS-sync-4"}""")
		syncOk()
		linkOf(sam).resourceName shouldBe "people/c2002"
	}

	@Test
	fun `a created person with a resource name that is not people slash id is reported and not linked, the run goes on`() {
		val sam = trashedAndRestoredSam()
		stub(post(urlPathEqualTo("/v1/people:createContact")), """{"resourceName": "people/../me", "etag": "%c"}""")

		val state = sync.sync(CONNECTION)

		state.lastError shouldBe "1 contacts could not be synced with Google"
		state.syncToken shouldBe "CONTACTS-sync-3"
		linkOf(sam).resourceName.shouldBeNull()
		contacts.findById(sam.id).get().externalId shouldBe "people/c1003"
	}

	/** Sam (people/c1003) synced, trashed and deleted at Google, then restored here; the next listing has token sync-2. */
	private fun trashedAndRestoredSam(): Contact {
		stubFullListing()
		syncOk()
		val sam = contact("people/c1003")
		service.delete(sam.id) {}
		stub(delete(urlPathEqualTo("/v1/people/c1003:deleteContact")), "{}")
		stubIncremental("""{"nextSyncToken": "CONTACTS-sync-2"}""")
		syncOk()
		tx.execute { trash.restore(sam.id) }
		wireMock.resetAll()
		stub(listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-2")), """{"nextSyncToken": "CONTACTS-sync-3"}""")
		return sam
	}

	// --- pull: failures and odd listings

	@Test
	fun `a listing Google refuses fails the run and keeps the sync token`() {
		stubFullListing()
		val before = syncOk()
		stub(
			listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-1")),
			"""{"error": {"code": 400, "status": "INVALID_ARGUMENT"}}""",
			400,
		)

		val state = sync.sync(CONNECTION)

		state.lastError shouldBe "Listing the Google contacts failed (HTTP 400)"
		state.syncToken shouldBe "CONTACTS-sync-1"
		state.lastSuccessAt shouldBe before.lastSuccessAt
	}

	@Test
	fun `an expired token answer to a full listing fails the run instead of listing again and again`() {
		stub(listing(), recording("error-400-expired-sync-token.json"), 400)

		sync.sync(CONNECTION).lastError shouldBe "Listing the Google contacts failed (HTTP 400)"

		wireMock.allServeEvents.size shouldBe 1
	}

	@Test
	fun `a 5xx answer of the listing fails the run with a safe message`() {
		stub(listing(), """{"error": {"code": 503, "message": "backend down for Alex Muster"}}""", 503)

		val state = sync.sync(CONNECTION)

		state.lastError shouldBe "Google Contacts failed (HTTP 503)"
		state.lastSuccessAt.shouldBeNull()
		active().shouldBeEmpty()
	}

	@Test
	fun `persons without a resource name and deletions of unknown persons are ignored`() {
		stubFullListing()
		syncOk()
		stubIncremental(
			"""
			{"connections": [
			  {"etag": "%no-name", "names": [{"displayName": "Without Resource Name"}]},
			  {"resourceName": "people/c9999", "etag": "%gone", "metadata": {"deleted": true}}
			], "nextSyncToken": "CONTACTS-sync-2"}
			""",
		)

		syncOk().syncToken shouldBe "CONTACTS-sync-2"

		active() shouldContainExactlyInAnyOrder listOf("Alex Muster", "+41 44 000 00 02", "Sam Beispiel")
	}

	@Test
	fun `a pulled person with a resource name that is not people slash id is skipped and reported`() {
		stubFullListing()
		syncOk()
		stubIncremental(
			"""
			{"connections": [
			  {"resourceName": "people/../me", "etag": "%odd", "names": [{"displayName": "Odd Name"}]},
			  {"resourceName": "people/c1005", "etag": "%new", "names": [{"displayName": "Noa Fiktiv", "givenName": "Noa"}]}
			], "nextSyncToken": "CONTACTS-sync-2"}
			""",
		)

		val state = sync.sync(CONNECTION)

		state.lastError shouldBe "1 contacts could not be synced with Google"
		state.syncToken shouldBe "CONTACTS-sync-2"
		active() shouldContain "Noa Fiktiv"
		contacts.findAll().none { it.displayName == "Odd Name" } shouldBe true
	}

	@Test
	fun `a person that cannot be stored is skipped and reported, the others are synced`() {
		// Another Google connection already has a contact with the id people/c1002 (the id is unique per source).
		val other = UUID.fromString("0192a5f0-0000-7000-8000-0000000000c2")
		val taken = service.createSynced(ContactSource.GOOGLE, "people/c1002", ContactChanges(displayName = Change("Other account")))
		jdbc.update("insert into contacts_google_account (connection_id) values (?)", other)
		jdbc.update(
			"insert into contacts_google_link (id, contact_id, connection_id, resource_name, synced_version) values (?, ?, ?, ?, ?)",
			UuidV7.generate(),
			taken.id,
			other,
			"people/c1002",
			taken.version,
		)
		stubFullListing()

		val state = sync.sync(CONNECTION)

		state.lastError shouldBe "1 contacts could not be synced with Google"
		state.syncToken shouldBe "CONTACTS-sync-1"
		contact("people/c1001").displayName shouldBe "Alex Muster"
		contact("people/c1003").displayName shouldBe "Sam Beispiel"
		links.findByConnectionIdAndResourceName(CONNECTION, "people/c1002").shouldBeNull()
		contacts.findById(taken.id).get().displayName shouldBe "Other account"
	}

	@Test
	fun `a person restored at Google after it was deleted there comes back`() {
		stubFullListing()
		syncOk()
		stubIncremental(recording("connections-incremental.json"))
		syncOk()
		contacts.findAll().single { it.displayName == "Sam Beispiel" }.isDeleted shouldBe true
		// Google lists the person again under its old resource name (e.g. restored from Google's Trash).
		stub(
			listing().withQueryParam("syncToken", equalTo("CONTACTS-sync-2")),
			recording("connections-full-page2.json").replace("CONTACTS-sync-1", "CONTACTS-sync-3"),
		)

		val state = sync.sync(CONNECTION)

		state.lastError.shouldBeNull()
		active() shouldContain "Sam Beispiel"
		link("people/c1003")
	}

	@Test
	fun `a contact restored from a backup that is in the Trash gives up its id, the person gets a new contact`() {
		val restored = service.createSynced(ContactSource.GOOGLE, "people/c1003", ContactChanges(displayName = Change("Sam (from backup)")))
		service.delete(restored.id) {}
		stubFullListing()

		syncOk()

		val old = contacts.findById(restored.id).get()
		old.isDeleted shouldBe true
		old.externalId.shouldBeNull()
		val sam = contact("people/c1003")
		sam.id shouldNotBe restored.id
		sam.externalId shouldBe "people/c1003"
	}

	// --- concurrency

	@Test
	fun `a run already going on for the connection is not started a second time`() {
		stubFullListing()
		val fixed = tokens as FixedTokens
		val entered = CountDownLatch(1).also { fixed.entered = it }
		val gate = CountDownLatch(1).also { fixed.gate = it }
		val first = AtomicReference<GoogleContactsAccount>()
		val running = thread { first.set(sync.sync(CONNECTION)) }
		try {
			entered.await(10, TimeUnit.SECONDS) shouldBe true

			val second = sync.sync(CONNECTION)

			second.lastAttemptAt.shouldNotBeNull()
			second.syncToken.shouldBeNull()
			wireMock.allServeEvents.shouldBeEmpty()
		} finally {
			gate.countDown()
			running.join(10_000)
		}
		first.get().shouldNotBeNull().syncToken shouldBe "CONTACTS-sync-1"
		wireMock.allServeEvents.size shouldBe 2
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

	@Test
	fun `disconnecting a Microsoft connection leaves the Google sync state alone`() {
		stubFullListing()
		syncOk()

		tx.executeWithoutResult { events.publishEvent(OAuthConnectionDisconnected(CONNECTION, OAuthProvider.MICROSOFT)) }

		accounts.findById(CONNECTION).isPresent shouldBe true
		links.findAll().size shouldBe 3
	}

	@Test
	fun `createSynced refuses the LOCAL source and keeps the external id of a provider contact`() {
		shouldThrow<IllegalArgumentException> {
			service.createSynced(ContactSource.LOCAL, "people/c1", ContactChanges(displayName = Change("Local")))
		}

		val created = service.createSynced(ContactSource.GOOGLE, "people/c3001", ContactChanges(givenName = Change("Noa")))

		created.source shouldBe ContactSource.GOOGLE
		created.externalId shouldBe "people/c3001"
		created.displayName shouldBe "Noa"
		contacts.findAll().map { it.displayName } shouldContainExactly listOf("Noa")
	}

	@Test
	fun `the scheduler is off in the test configuration, so tests sync explicitly`() {
		context.getBeanNamesForType(GoogleContactsSyncScheduler::class.java).toList().shouldBeEmpty()
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
