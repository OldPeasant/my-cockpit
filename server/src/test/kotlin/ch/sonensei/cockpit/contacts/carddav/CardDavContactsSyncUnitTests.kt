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
import io.kotest.matchers.shouldNotBe
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
 * listing with a token (the token stays), a 412 on a write (the server's version wins), discovery from an address-book
 * URL or through principal and home set, a sync-collection answer truncated with 507 (continued with its token), and a
 * contact restored from a backup (adopted) or in the Trash (gives up its id). Data is fictitious.
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

	// --- discovery (CR-1: the configured URL is the address book; principal and home set)

	private fun propfindAt(
		path: String,
		vararg responses: String,
	) {
		val body =
			"""<D:multistatus xmlns:D="DAV:" xmlns:CR="urn:ietf:params:xml:ns:carddav">""" +
				responses.joinToString("") + "</D:multistatus>"
		wireMock.stubFor(request("PROPFIND", urlPathEqualTo(path)).willReturn(aResponse().withStatus(207).withBody(body)))
	}

	private fun found(
		href: String,
		vararg props: String,
	): String =
		"<D:response><D:href>$href</D:href><D:propstat><D:prop>${props.joinToString("")}</D:prop>" +
			"<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"

	/** A sync-collection answer with [entries] and the new [token]. */
	private fun syncAnswer(
		entries: String,
		token: String,
	): String = """<D:multistatus xmlns:D="DAV:">$entries<D:sync-token>$token</D:sync-token></D:multistatus>"""

	private fun emptySyncAt(path: String) {
		wireMock.stubFor(
			request("REPORT", urlPathEqualTo(path))
				.withRequestBody(containing("sync-collection"))
				.willReturn(aResponse().withStatus(207).withBody(syncAnswer("", "tok-1"))),
		)
	}

	@Test
	fun `a configured URL that is an address book is used as it is`() {
		propfindAt("/dav/", found("/dav/", ADDRESSBOOK))
		emptySyncAt("/dav/")

		val state = sync.sync(account.id)

		state.lastError.shouldBeNull()
		state.addressbookUrl shouldBe "${wireMock.baseUrl()}/dav/"
		state.syncToken shouldBe "tok-1"
		requests().filter { it.method.value() == "PROPFIND" }.map { it.url } shouldContainExactly listOf("/dav/")
		requests().single { it.method.value() == "REPORT" }.url shouldBe "/dav/"
	}

	@Test
	fun `discovery follows the principal to the home set and takes the first address book of this origin by path`() {
		val principal = "<D:current-user-principal><D:href>/dav/alice/</D:href></D:current-user-principal>"
		val home = "<CR:addressbook-home-set><D:href>/dav/alice/books/</D:href></CR:addressbook-home-set>"
		propfindAt("/dav/", found("/dav/", COLLECTION, principal))
		propfindAt("/dav/alice/", found("/dav/alice/", home))
		propfindAt(
			"/dav/alice/books/",
			found("/dav/alice/books/", COLLECTION),
			found("/dav/alice/books/zeta/", ADDRESSBOOK),
			found("http://evil.example.test/dav/alice/books/aaa/", ADDRESSBOOK),
			found("/dav/alice/books/alpha/", ADDRESSBOOK),
			found("/dav/alice/books/abc-calendar/", COLLECTION),
		)
		emptySyncAt("/dav/alice/books/alpha/")

		val state = sync.sync(account.id)

		state.lastError.shouldBeNull()
		state.addressbookUrl shouldBe "${wireMock.baseUrl()}/dav/alice/books/alpha/"
		val propfinds = requests().filter { it.method.value() == "PROPFIND" }
		propfinds.map { it.url } shouldContainExactly listOf("/dav/", "/dav/alice/", "/dav/alice/books/")
		propfinds.map { it.getHeader("Depth") } shouldContainExactly listOf("0", "0", "1")
		requests().single { it.method.value() == "REPORT" }.url shouldBe "/dav/alice/books/alpha/"
	}

	@Test
	fun `a server that announces no address book home fails the run with a message`() {
		propfindAt("/dav/", found("/dav/", COLLECTION))

		val state = sync.sync(account.id)

		state.lastError shouldBe "Finding the address book: the CardDAV server announced no address book home"
		state.addressbookUrl.shouldBeNull()
		requests().filter { it.method.value() == "REPORT" }.shouldBeEmpty()
	}

	// --- pull branches (CR-1: 507 truncation paging, adoption of a restored contact)

	@Test
	fun `a sync-collection answer truncated with 507 is continued with its token until complete`() {
		account.addressbookUrl = book()
		account.syncToken = "tok-1"
		val truncated = "<D:response><D:href>$BOOK</D:href><D:status>HTTP/1.1 507 Insufficient Storage</D:status></D:response>"
		stubReport("<D:sync-token>tok-1</D:sync-token>", 207, syncAnswer(etagEntry("${BOOK}ada.vcf", "e-ada.vcf") + truncated, "tok-2"))
		stubReport("<D:sync-token>tok-2</D:sync-token>", 207, syncAnswer(etagEntry("${BOOK}bob.vcf", "e-bob.vcf"), "tok-3"))
		stubReport("addressbook-multiget", 207, multiget("ada.vcf" to "Ada Example", "bob.vcf" to "Bob Example"))

		val state = sync.sync(account.id)

		state.lastError.shouldBeNull()
		state.syncToken shouldBe "tok-3"
		requests().count { it.bodyAsString.contains("sync-collection") } shouldBe 2
		verify(exactly = 1) { service.createSynced(ContactSource.CARDDAV, book("ada.vcf"), any()) }
		verify(exactly = 1) { service.createSynced(ContactSource.CARDDAV, book("bob.vcf"), any()) }
		saved.map { it.href } shouldContainExactly listOf("${BOOK}ada.vcf", "${BOOK}bob.vcf")
	}

	private fun listAda() {
		account.addressbookUrl = book()
		account.syncToken = null
		stubReport("sync-collection", 207, syncAnswer(etagEntry("${BOOK}ada.vcf", "e-ada.vcf"), "tok-1"))
		stubReport("addressbook-multiget", 207, multiget("ada.vcf" to "Ada Restored"))
	}

	@Test
	fun `an active contact restored from a backup with the card's id and no link is adopted, not duplicated`() {
		listAda()
		val restored = Contact(displayName = "Ada Example", source = ContactSource.CARDDAV)
		every { lookup.findBySourceAndExternalId(ContactSource.CARDDAV, book("ada.vcf")) } returns restored
		every { links.findByContactId(restored.id) } returns null
		val applied = slot<ContactChanges>()
		every { service.update(restored.id, capture(applied), any()) } returns restored

		val state = sync.sync(account.id)

		state.lastError.shouldBeNull()
		applied.captured.displayName?.value shouldBe "Ada Restored"
		verify(exactly = 0) { service.createSynced(any(), any(), any()) }
		saved.single().contactId shouldBe restored.id
		saved.single().etag shouldBe "\"e-ada.vcf\""
	}

	@Test
	fun `a contact in the Trash with the card's id gives the id up, and the card gets a new contact`() {
		listAda()
		val trashed =
			Contact(displayName = "Ada Example", source = ContactSource.CARDDAV).apply { softDelete(Clock.fixed(NOW, ZoneOffset.UTC)) }
		every { lookup.findBySourceAndExternalId(ContactSource.CARDDAV, book("ada.vcf")) } returns trashed
		every { links.findByContactId(trashed.id) } returns null
		every { lookup.updateExternalId(trashed.id, null) } returns 1

		val state = sync.sync(account.id)

		state.lastError.shouldBeNull()
		verify(exactly = 1) { lookup.updateExternalId(trashed.id, null) }
		verify(exactly = 1) { service.createSynced(ContactSource.CARDDAV, book("ada.vcf"), any()) }
		verify(exactly = 0) { service.update(any(), any(), any()) }
		saved.single().contactId shouldNotBe trashed.id
	}

	// --- CR-2: no repeated sync-collection without a token; CR-4/CR-5: a card back under the href of a trashed contact

	@Test
	fun `without a token a server that refuses sync-collection gets one REPORT, then PROPFIND`() {
		account.addressbookUrl = book()
		account.syncToken = null
		stubReport("sync-collection", 403, """<D:error xmlns:D="DAV:"><D:supported-report/></D:error>""")
		propfindAt(BOOK, found(BOOK, ADDRESSBOOK))

		val state = sync.sync(account.id)

		state.lastError.shouldBeNull()
		val reports = requests().filter { it.bodyAsString.contains("sync-collection") }
		reports.size shouldBe 1
		reports.single().bodyAsString shouldContain "<D:sync-token></D:sync-token>"
		requests().count { it.method.value() == "PROPFIND" } shouldBe 1
	}

	@Test
	fun `a refused token is retried once without a token`() {
		account.addressbookUrl = book()
		account.syncToken = "tok-old"
		stubReport("<D:sync-token>tok-old</D:sync-token>", 409, "")
		stubReport("<D:sync-token></D:sync-token>", 207, syncAnswer("", "tok-1"))

		val state = sync.sync(account.id)

		state.lastError.shouldBeNull()
		state.syncToken shouldBe "tok-1"
		val reports = requests().filter { it.bodyAsString.contains("sync-collection") }
		reports.map { it.bodyAsString.contains("tok-old") } shouldContainExactly listOf(true, false)
		requests().filter { it.method.value() == "PROPFIND" }.shouldBeEmpty()
	}

	@Test
	fun `a card back under the href of a contact the sync trashed restores the contact with the card, and nothing is deleted`() {
		account.addressbookUrl = book()
		account.syncToken = "tok-1"
		val trashed =
			Contact(displayName = "Ada Example", source = ContactSource.CARDDAV).apply { softDelete(Clock.fixed(NOW, ZoneOffset.UTC)) }
		// Removed at the server earlier: the link stays without an ETag, the contact is in the Trash.
		saved += CardDavLink(trashed.id, account.id, "${BOOK}ada.vcf", etag = null, syncedVersion = -1)
		every { contacts.findById(trashed.id) } returns Optional.of(trashed)
		every { contacts.saveAndFlush(any<Contact>()) } answers { firstArg() }
		every { links.findById(any()) } answers { Optional.ofNullable(saved.firstOrNull { it.id == firstArg<UUID>() }) }
		val applied = slot<ContactChanges>()
		every { service.update(trashed.id, capture(applied), any()) } returns trashed
		stubReport("sync-collection", 207, syncAnswer(etagEntry("${BOOK}ada.vcf", "e-ada.vcf"), "tok-2"))
		stubReport("addressbook-multiget", 207, multiget("ada.vcf" to "Ada Back"))

		val first = sync.sync(account.id)

		first.lastError.shouldBeNull()
		trashed.isDeleted shouldBe false
		verify(exactly = 1) { contacts.saveAndFlush(trashed) }
		applied.captured.displayName?.value shouldBe "Ada Back"
		verify(exactly = 0) { service.createSynced(any(), any(), any()) }
		saved.single().contactId shouldBe trashed.id
		saved.single().etag shouldBe "\"e-ada.vcf\""
		saved.single().syncedVersion shouldBe trashed.version

		// The next run has nothing to push: the card stays at the server.
		stubReport("sync-collection", 207, syncAnswer("", "tok-3"))

		val second = sync.sync(account.id)

		second.lastError.shouldBeNull()
		requests().filter { it.method.value() == "DELETE" || it.method.value() == "PUT" }.shouldBeEmpty()
		saved.single().etag shouldBe "\"e-ada.vcf\""
	}

	@Test
	fun `a card that changes at the server while the user has its contact in the Trash is left for the push to delete`() {
		account.addressbookUrl = book()
		account.syncToken = "tok-1"
		val trashed =
			Contact(displayName = "Ada Example", source = ContactSource.CARDDAV).apply { softDelete(Clock.fixed(NOW, ZoneOffset.UTC)) }
		// Trashed here while the card exists at the server (ETag stored); the delete at the server failed so far.
		saved += CardDavLink(trashed.id, account.id, "${BOOK}ada.vcf", etag = "\"e-old\"", syncedVersion = trashed.version)
		every { contacts.findById(trashed.id) } returns Optional.of(trashed)
		every { links.findById(any()) } answers { Optional.ofNullable(saved.firstOrNull { it.id == firstArg<UUID>() }) }
		// A refused write (not a 5xx, which fails the whole run), so the run goes on to the pull.
		wireMock.stubFor(request("DELETE", anyUrl()).willReturn(aResponse().withStatus(403)))
		stubReport("sync-collection", 207, syncAnswer(etagEntry("${BOOK}ada.vcf", "e-ada.vcf"), "tok-2"))
		stubReport("addressbook-multiget", 207, multiget("ada.vcf" to "Ada Changed"))

		val state = sync.sync(account.id)

		// The pull ran and fetched the changed card; only the refused DELETE counts as not synced.
		state.lastError shouldBe "1 contacts could not be synced with the CardDAV server"
		state.syncToken shouldBe "tok-2"
		requests().count { it.bodyAsString.contains("sync-collection") } shouldBe 1
		requests().count { it.bodyAsString.contains("addressbook-multiget") } shouldBe 1
		trashed.isDeleted shouldBe true
		verify(exactly = 0) { contacts.saveAndFlush(any<Contact>()) }
		verify(exactly = 0) { service.update(any(), any(), any()) }
		verify(exactly = 0) { service.createSynced(any(), any(), any()) }
		saved.single().etag shouldBe "\"e-old\""
		requests().single { it.method.value() == "DELETE" }.url shouldBe "${BOOK}ada.vcf"
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
		const val COLLECTION = "<D:resourcetype><D:collection/></D:resourcetype>"
		const val ADDRESSBOOK = "<D:resourcetype><D:collection/><CR:addressbook/></D:resourcetype>"
		val NOW: Instant = Instant.parse("2026-10-26T10:00:00Z")

		val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

		@JvmStatic
		@AfterAll
		fun stop() = wireMock.stop()
	}
}
