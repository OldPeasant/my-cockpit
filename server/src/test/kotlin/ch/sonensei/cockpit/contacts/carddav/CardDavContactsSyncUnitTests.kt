package ch.sonensei.cockpit.contacts.carddav

import ch.sonensei.cockpit.contacts.domain.Contact
import ch.sonensei.cockpit.contacts.domain.ContactChanges
import ch.sonensei.cockpit.contacts.domain.ContactService
import ch.sonensei.cockpit.contacts.domain.ContactSource
import ch.sonensei.cockpit.contacts.domain.VCardService
import ch.sonensei.cockpit.contacts.infra.ContactRepository
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.any
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.request
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.stubbing.Scenario
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * [CardDavContactsSync] without Spring or a database (repositories and the contact service mocked) against WireMock,
 * for the paths a Radicale server does not take: a server without sync-collection (listed with PROPFIND), hrefs of
 * another host or outside the address book (ignored), a card that cannot be stored (skipped and counted), a 5xx while
 * listing with a token (the token stays), and a 412 on a write (the server's version wins). Data is fictitious.
 */
class CardDavContactsSyncUnitTests {
	private val accounts = mockk<CardDavAccountRepository>()
	private val links = mockk<CardDavLinkRepository>()
	private val lookup = mockk<CardDavContactLookup>()
	private val contacts = mockk<ContactRepository>()
	private val service = mockk<ContactService>()
	private val sync =
		CardDavContactsSync(
			accounts = accounts,
			links = links,
			lookup = lookup,
			contacts = contacts,
			service = service,
			vcards = VCardService(mockk(), mockk(), mockk()),
			transactionManager = mockk<PlatformTransactionManager>(relaxed = true),
			clock = Clock.fixed(NOW, ZoneOffset.UTC),
		)
	private val account = CardDavAccount("Server", "${wireMock.baseUrl()}/dav/", USER, EncryptedSecret(PASSWORD))

	/** The links "stored" by the sync. */
	private val saved = mutableListOf<CardDavLink>()

	init {
		every { accounts.findById(account.id) } returns Optional.of(account)
		every { accounts.findAll() } returns listOf(account)
		every { accounts.saveAndFlush(any<CardDavAccount>()) } answers { firstArg() }
		every { links.findByAccountId(account.id) } answers { saved.toList() }
		every { links.findByAccountIdAndHref(account.id, any()) } answers { saved.firstOrNull { it.href == secondArg<String>() } }
		every { links.saveAndFlush(any<CardDavLink>()) } answers { firstArg<CardDavLink>().also { saved += it } }
		every { lookup.findBySourceAndExternalId(any(), any()) } returns null
		every { service.createSynced(any(), any(), any()) } answers {
			Contact(displayName = thirdArg<ContactChanges>().displayName?.value ?: "?", source = firstArg())
		}
	}

	@BeforeEach
	fun reset() {
		wireMock.resetAll()
	}

	private fun requests(): List<LoggedRequest> = wireMock.allServeEvents.map { it.request }.reversed()

	private fun book(path: String = ""): String = "${wireMock.baseUrl()}$BOOK$path"

	private fun stubReport(
		bodyPart: String,
		status: Int,
		body: String = "",
	) {
		wireMock.stubFor(
			request("REPORT", urlPathEqualTo(BOOK))
				.withRequestBody(containing(bodyPart))
				.willReturn(aResponse().withStatus(status).withBody(body)),
		)
	}

	/** A multiget answer with one card per (file name, FN). */
	private fun multiget(vararg cards: Pair<String, String>): String {
		val entries =
			cards.joinToString("") { (name, fn) ->
				"<D:response><D:href>$BOOK$name</D:href><D:propstat><D:prop><D:getetag>\"e-$name\"</D:getetag>" +
					"<CR:address-data>BEGIN:VCARD\r\nVERSION:3.0\r\nUID:$name\r\nFN:$fn\r\nEND:VCARD\r\n</CR:address-data>" +
					"</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
			}
		return """<D:multistatus xmlns:D="DAV:" xmlns:CR="urn:ietf:params:xml:ns:carddav">$entries</D:multistatus>"""
	}

	private fun etagEntry(
		href: String,
		etag: String,
	): String =
		"<D:response><D:href>$href</D:href><D:propstat><D:prop><D:getetag>\"$etag\"</D:getetag></D:prop>" +
			"<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"

	@Test
	fun `a wrong password at discovery is stored as the run's error, without the password`() {
		wireMock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(401).withBody("secret-response-body")))

		val state = sync.sync(account.id)

		state.lastError shouldBe "Finding the address book: the CardDAV server rejected the user name or password"
		state.lastError.orEmpty() shouldNotContain PASSWORD
		state.lastAttemptAt shouldBe NOW
		state.lastSuccessAt.shouldBeNull()
		state.addressbookUrl.shouldBeNull()
		verify(exactly = 0) { service.createSynced(any(), any(), any()) }
	}

	@Test
	fun `a server without sync-collection is listed with PROPFIND, and hrefs elsewhere are ignored`() {
		account.addressbookUrl = book()
		stubReport("sync-collection", 403, """<D:error xmlns:D="DAV:"><D:supported-report/></D:error>""")
		val listing =
			etagEntry(BOOK, "collection") +
				etagEntry("${BOOK}ada.vcf", "e-ada.vcf") +
				etagEntry("http://evil.example.test${BOOK}eve.vcf", "e-eve") +
				etagEntry("/other/zed.vcf", "e-zed") +
				etagEntry("$BOOK../secret/ida.vcf", "e-ida")
		wireMock.stubFor(
			request("PROPFIND", urlPathEqualTo(BOOK))
				.willReturn(aResponse().withStatus(207).withBody("""<D:multistatus xmlns:D="DAV:">$listing</D:multistatus>""")),
		)
		stubReport("addressbook-multiget", 207, multiget("ada.vcf" to "Ada Example"))
		val changes = slot<ContactChanges>()

		val state = sync.sync(account.id)

		state.lastError.shouldBeNull()
		state.lastSuccessAt shouldBe NOW
		state.syncToken.shouldBeNull()
		verify(exactly = 1) { service.createSynced(ContactSource.CARDDAV, book("ada.vcf"), capture(changes)) }
		changes.captured.displayName?.value shouldBe "Ada Example"
		saved.map { it.href } shouldContainExactly listOf("${BOOK}ada.vcf")
		saved.single().etag shouldBe "\"e-ada.vcf\""
		saved.single().accountId shouldBe account.id
		val multiget = requests().single { it.bodyAsString.contains("addressbook-multiget") }.bodyAsString
		multiget shouldContain "<D:href>${BOOK}ada.vcf</D:href>"
		multiget shouldNotContain "eve.vcf"
		multiget shouldNotContain "zed.vcf"
		multiget shouldNotContain "ida.vcf"
		requests().filter { !it.url.startsWith(BOOK) }.shouldBeEmpty()
	}

	@Test
	fun `a card that cannot be stored is skipped and counted, the others are stored`() {
		account.addressbookUrl = book()
		account.syncToken = null
		val listing = etagEntry("${BOOK}ada.vcf", "e-ada.vcf") + etagEntry("${BOOK}bad.vcf", "e-bad.vcf")
		stubReport("sync-collection", 207, """<D:multistatus xmlns:D="DAV:">$listing<D:sync-token>tok-1</D:sync-token></D:multistatus>""")
		stubReport("addressbook-multiget", 207, multiget("ada.vcf" to "Ada Example", "bad.vcf" to "Bad Example"))
		every { service.createSynced(any(), book("bad.vcf"), any()) } throws InvalidRequestException("emails", "fictitious refusal")

		val state = sync.sync(account.id)

		state.lastError shouldBe "1 contacts could not be synced with the CardDAV server"
		state.lastSuccessAt.shouldBeNull()
		state.syncToken shouldBe "tok-1"
		saved.map { it.href } shouldContainExactly listOf("${BOOK}ada.vcf")
	}

	@Test
	fun `a server error while listing with a token fails the run and keeps the token`() {
		account.addressbookUrl = book()
		account.syncToken = "tok-1"
		stubReport("sync-collection", 500, "secret-response-body")

		val state = sync.sync(account.id)

		state.lastError shouldBe "Listing the changed contacts failed at the CardDAV server (HTTP 500)"
		state.syncToken shouldBe "tok-1"
		state.lastSuccessAt.shouldBeNull()
		requests().single().bodyAsString shouldContain "<D:sync-token>tok-1</D:sync-token>"
	}

	@Test
	fun `a write the server refuses with 412 lets the server's version win, then only changes are listed`() {
		account.addressbookUrl = book()
		account.syncToken = "tok-1"
		val contact = Contact(displayName = "Ada Example", source = ContactSource.CARDDAV).apply { title = "Changed here" }
		// The contact's version (0) is above the one last synced: a local change to push.
		saved += CardDavLink(contact.id, account.id, "${BOOK}ada.vcf", etag = "\"e1\"", syncedVersion = -1)
		every { contacts.findById(contact.id) } returns Optional.of(contact)
		val applied = slot<ContactChanges>()
		every { service.update(contact.id, capture(applied), any()) } returns contact
		val card = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:ada\r\nFN:%s\r\nEND:VCARD\r\n"
		wireMock.stubFor(
			get(urlPathEqualTo("${BOOK}ada.vcf"))
				.inScenario("conflict")
				.whenScenarioStateIs(Scenario.STARTED)
				.willReturn(aResponse().withStatus(200).withHeader("ETag", "\"e1\"").withBody(card.format("Ada Example")))
				.willSetStateTo("changed"),
		)
		wireMock.stubFor(put(urlPathEqualTo("${BOOK}ada.vcf")).willReturn(aResponse().withStatus(412)))
		wireMock.stubFor(
			get(urlPathEqualTo("${BOOK}ada.vcf"))
				.inScenario("conflict")
				.whenScenarioStateIs("changed")
				.willReturn(aResponse().withStatus(200).withHeader("ETag", "\"e2\"").withBody(card.format("Ada Server"))),
		)
		val listing = etagEntry("${BOOK}ada.vcf", "e2")
		stubReport("sync-collection", 207, """<D:multistatus xmlns:D="DAV:">$listing<D:sync-token>tok-2</D:sync-token></D:multistatus>""")

		val state = sync.sync(account.id)

		state.lastError.shouldBeNull()
		state.syncToken shouldBe "tok-2"
		val write = requests().single { it.method.value() == "PUT" }
		write.getHeader("If-Match") shouldBe "\"e1\""
		write.bodyAsString shouldContain "Changed here"
		applied.captured.displayName?.value shouldBe "Ada Server"
		saved.single().etag shouldBe "\"e2\""
		// The echo of the server's version is not fetched again.
		requests().filter { it.bodyAsString.contains("addressbook-multiget") }.shouldBeEmpty()
		verify(exactly = 1) { service.update(contact.id, any(), any()) }
	}

	@Test
	fun `an unknown account is not found`() {
		val unknown = UUID.fromString("0192a5f0-0000-7000-8000-0000000000c2")
		every { accounts.findById(unknown) } returns Optional.empty()

		shouldThrow<NotFoundException> { sync.sync(unknown) }
		shouldThrow<NotFoundException> { sync.createContact(unknown, ContactChanges()) }
	}

	@Test
	fun `the scheduler syncs every account and survives an unexpected failure`() {
		val failing = mockk<CardDavContactsSync>()
		every { failing.syncAll() } throws IllegalStateException("fictitious database failure")

		CardDavContactsSyncScheduler(failing).run()

		verify(exactly = 1) { failing.syncAll() }
	}

	companion object {
		const val USER = "alice-fictitious"
		const val PASSWORD = "fictitious-password-456"
		const val BOOK = "/dav/book/"
		val NOW: Instant = Instant.parse("2026-10-26T10:00:00Z")

		val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

		@JvmStatic
		@AfterAll
		fun stop() = wireMock.stop()
	}
}
