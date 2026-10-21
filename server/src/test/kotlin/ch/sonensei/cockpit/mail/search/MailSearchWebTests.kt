package ch.sonensei.cockpit.mail.search

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchService
import ch.sonensei.cockpit.mail.imap.MailConnectionError
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.mail.sync.KnownFolderState
import ch.sonensei.cockpit.mail.sync.MailHeaderStore
import ch.sonensei.cockpit.mail.sync.MailSyncRepository
import ch.sonensei.cockpit.mail.sync.QresyncChanges
import ch.sonensei.cockpit.mail.sync.RemoteFlagState
import ch.sonensei.cockpit.mail.sync.RemoteFlags
import ch.sonensei.cockpit.mail.sync.RemoteFolder
import ch.sonensei.cockpit.mail.sync.RemoteFolderNotFoundException
import ch.sonensei.cockpit.mail.sync.RemoteFolderStatus
import ch.sonensei.cockpit.mail.sync.RemoteMailbox
import ch.sonensei.cockpit.mail.sync.RemoteMessage
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * `GET /api/v1/mail/search` against Postgres: cached headers and bodies are found through `search_document`, scopes
 * and validation, and `server=true` with a scripted mail server ([FakeMailbox]).
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@Import(MailSearchWebTests.FakeMailboxConfiguration::class)
class MailSearchWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailSyncRepository,
	@Autowired private val store: MailHeaderStore,
	@Autowired private val index: MailSearchIndex,
	@Autowired private val globalSearch: SearchService,
	@Autowired private val mailbox: FakeMailbox,
) : PostgresTestBase() {
	private lateinit var work: MailAccount
	private lateinit var home: MailAccount
	private lateinit var workInbox: MailFolder
	private lateinit var workArchive: MailFolder
	private lateinit var homeInbox: MailFolder

	@TestConfiguration(proxyBeanMethods = false)
	class FakeMailboxConfiguration {
		@Bean
		@Primary
		fun fakeMailbox() = FakeMailbox()
	}

	/** A mail server whose folders the test scripts by path; an account in [failing] cannot be reached. */
	class FakeMailbox : RemoteMailbox {
		val folders = mutableMapOf<String, FakeFolder>()
		val failing = mutableSetOf<UUID>()
		val searched = mutableListOf<Pair<String, List<String>>>()

		/** "accountId/path" of folders deleted on the server. */
		val gone = mutableSetOf<String>()

		override fun <T> withFolder(
			account: MailAccount,
			path: String,
			known: KnownFolderState?,
			block: (RemoteFolder) -> T,
		): T {
			if (account.id in failing) throw MailConnectionException(MailConnectionError.CONNECTION_FAILED, RuntimeException("refused"))
			if ("${account.id}/$path" in gone) throw RemoteFolderNotFoundException(path)
			val folder = folders.getValue("${account.id}/$path")
			return block(folder)
		}

		inner class FakeFolder(
			private val path: String,
			private val uidValidity: Long,
			val messages: MutableMap<Long, RemoteMessage> = mutableMapOf(),
		) : RemoteFolder {
			override val status get() = RemoteFolderStatus(uidValidity, (messages.keys.maxOrNull() ?: 0) + 1, null, false, false)
			override val qresyncChanges: QresyncChanges? = null

			override fun uidsSince(since: Instant) = messages.keys.sorted()

			override fun uidsFrom(fromUid: Long) = messages.keys.filter { it >= fromUid }.sorted()

			override fun receivedDates(uids: List<Long>) = uids.mapNotNull { uid -> messages[uid]?.receivedAt?.let { uid to it } }.toMap()

			override fun uidsWithMessageIds(messageIds: Collection<String>) = emptyList<Long>()

			override fun search(words: List<String>): List<Long> {
				searched += path to words
				return messages.values
					.filter { m -> words.all { w -> listOfNotNull(m.subject, m.snippet).any { it.contains(w, ignoreCase = true) } } }
					.map { it.uid }
					.sorted()
			}

			override fun headers(uids: List<Long>) = uids.mapNotNull { messages[it] }

			override fun flagsFrom(fromUid: Long) = emptyList<RemoteFlagState>()

			override fun flagsChangedSince(
				fromUid: Long,
				modSeq: Long,
			) = emptyList<RemoteFlagState>()
		}
	}

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from link")
		jdbc.update("delete from mail_account")
		jdbc.update("delete from search_document where item_type = 'EMAIL'")
		mailbox.folders.clear()
		mailbox.failing.clear()
		mailbox.searched.clear()
		mailbox.gone.clear()
		work = accounts.save(account("Work", "jane.doe@work.example"))
		home = accounts.save(account("Home", "jane@home.example"))
		workInbox = folder(work, "INBOX")
		workArchive = folder(work, "Archive")
		homeInbox = folder(home, "INBOX")
	}

	private fun account(
		name: String,
		email: String,
	) = MailAccount(
		MailAccountType.IMAP_SMTP,
		name,
		email,
		"imap.example.test",
		993,
		MailSecurity.SSL_TLS,
		"smtp.example.test",
		465,
		MailSecurity.SSL_TLS,
		"jane",
	)

	private fun folder(
		account: MailAccount,
		path: String,
	): MailFolder {
		val folder = MailFolder(account.id, path, path)
		folder.uidValidity = UID_VALIDITY
		folder.uidNext = 1
		return folders.save(folder)
	}

	private fun remote(
		uid: Long,
		subject: String,
		from: MailAddress = MailAddress("ann.example@example.test", "Ann Example"),
		received: Instant = Instant.parse("2026-10-19T08:00:00Z").plusSeconds(uid),
		snippet: String? = null,
	) = RemoteMessage(
		uid = uid,
		messageId = "<$uid.$subject@example.test>".replace(" ", "-"),
		inReplyTo = null,
		references = null,
		subject = subject,
		from = listOf(from),
		to = listOf(MailAddress("jane.doe@work.example", "Jane Doe")),
		cc = emptyList(),
		bcc = emptyList(),
		replyTo = emptyList(),
		sentAt = received,
		receivedAt = received,
		flags = RemoteFlags(),
		sizeBytes = 100,
		hasAttachments = false,
		snippet = snippet,
		modSeq = null,
	)

	private fun cache(
		folder: MailFolder,
		vararg remote: RemoteMessage,
	): List<MailMessage> {
		store.upsert(folder.accountId, folder.id, remote.toList())
		return remote.map { r -> messages.findByFolderIdAndUidIn(folder.id, listOf(r.uid)).single() }
	}

	private fun search(params: String): JsonNode =
		mockMvc
			.get(URI("/api/v1/mail/search?$params"))
			.andExpect { status { isOk() } }
			.andReturn()
			.response
			.contentAsString
			.let(json::readTree)

	private fun subjects(params: String): List<String> = search(params)["items"].strings("subject")

	private fun JsonNode.strings(field: String) = (0 until size()).map { this[it][field].asString() }

	@Test
	fun `finds cached headers by subject, sender and recipient words, newest first`() {
		cache(
			workInbox,
			remote(1, "Quarterly budget review"),
			remote(2, "Lunch on Friday", from = MailAddress("bob.sample@example.test", "Bob Sample")),
			remote(3, "Budget draft"),
		)

		subjects("q=budget") shouldContainExactly listOf("Budget draft", "Quarterly budget review")
		subjects("q=bob") shouldContainExactly listOf("Lunch on Friday")
		subjects("q=sample.example") shouldContainExactly listOf("Lunch on Friday")
		subjects("q=bob.sample@example.test") shouldContainExactly listOf("Lunch on Friday")
		subjects("q=Budg%20quart") shouldContainExactly listOf("Quarterly budget review")
		subjects("q=jane") shouldBe listOf("Budget draft", "Lunch on Friday", "Quarterly budget review")
	}

	@Test
	fun `finds cached bodies, plain text or the text of HTML`() {
		val (plain, html) = cache(workInbox, remote(1, "Notes"), remote(2, "Newsletter"))
		index.index(plain, "The meeting moves to Zurich.", null)
		index.index(html, null, "<p>Grüezi <b>Bern</b></p><script>ignored()</script>")

		subjects("q=zurich") shouldContainExactly listOf("Notes")
		subjects("q=meetings") shouldContainExactly listOf("Notes")
		subjects("q=gr%C3%BCezi%20bern") shouldContainExactly listOf("Newsletter")
	}

	@Test
	fun `a body indexed when it was cached is found, and stays when headers are synced again`() {
		val (message) = cache(workInbox, remote(5, "Report"))
		index.index(message, "Kangaroo statistics", null)

		store.upsert(work.id, workInbox.id, listOf(remote(5, "Report")))

		subjects("q=kangaroo") shouldContainExactly listOf("Report")
	}

	@Test
	fun `scopes by folder and account, all folders of all accounts by default`() {
		cache(workInbox, remote(1, "Invoice work inbox"))
		cache(workArchive, remote(1, "Invoice work archive"))
		cache(homeInbox, remote(1, "Invoice home"))

		subjects("q=invoice").toSet() shouldBe setOf("Invoice work inbox", "Invoice work archive", "Invoice home")
		subjects("q=invoice&accountId=${work.id}") shouldContainExactlyInAnyOrder listOf("Invoice work inbox", "Invoice work archive")
		subjects("q=invoice&folderId=${workArchive.id}") shouldContainExactly listOf("Invoice work archive")
		subjects("q=invoice&accountId=${work.id}&folderId=${workArchive.id}") shouldContainExactly listOf("Invoice work archive")
	}

	@Test
	fun `limit cuts newest first and reports more`() {
		cache(workInbox, remote(1, "Report one"), remote(2, "Report two"), remote(3, "Report three"))

		val page = search("q=report&limit=2")

		page["items"].strings("subject") shouldContainExactly listOf("Report three", "Report two")
		page["hasMore"].asBoolean() shouldBe true
		page["server"].isNull shouldBe true
		search("q=report&limit=3")["hasMore"].asBoolean() shouldBe false
	}

	@Test
	fun `removed and detached messages are not found`() {
		cache(workInbox, remote(1, "Vanishing act"), remote(2, "Detached act"))
		store.reconcile(workInbox.id, emptyList(), setOf(1L))
		store.detachFolder(workInbox.id)

		subjects("q=act").shouldBeEmpty()
		jdbc.queryForObject("select count(*) from search_document where item_type = 'EMAIL'", Int::class.java) shouldBe 0
	}

	@Test
	fun `cached mail is found by the global search too, with arrival as recency`() {
		val (message) = cache(workInbox, remote(1, "Pelican sighting", received = Instant.parse("2020-01-01T00:00:00Z")))

		val hits = globalSearch.search("pelican", setOf(ItemType.EMAIL), 10).single().results

		hits.map { it.hit.item.id } shouldContainExactly listOf(message.id)
		hits.single().hit.updatedAt shouldBe Instant.parse("2020-01-01T00:00:00Z")
		hits.single().resolved?.title shouldBe "Pelican sighting"
	}

	@Test
	fun `reconcile indexes cached mail without document and drops documents of gone mail`() {
		val (kept, gone) = cache(workInbox, remote(1, "Walrus kept"), remote(2, "Walrus gone"))
		jdbc.update("delete from search_document where item_type = 'EMAIL'")
		jdbc.update("delete from mail_message where id = ?", gone.id)
		val orphan = UUID.randomUUID()
		jdbc.update(
			"insert into search_document (item_type, item_id, title, body, updated_at) values ('EMAIL', ?, 'Walrus orphan', '', now())",
			orphan,
		)

		index.reconcile(100) shouldBe (1 to 1)

		subjects("q=walrus") shouldContainExactly listOf("Walrus kept")
		jdbc.queryForList("select item_id from search_document where item_type = 'EMAIL'", UUID::class.java) shouldContainExactly
			listOf(kept.id)
		index.reconcile(100) shouldBe (0 to 0)
	}

	@Test
	fun `server search caches found messages that were not cached and returns them with the local hits`() {
		cache(workInbox, remote(1, "Contract signed"))
		mailbox.folders["${work.id}/INBOX"] =
			mailbox.FakeFolder("INBOX", UID_VALIDITY, mutableMapOf(1L to remote(1, "Contract signed")))
		mailbox.folders["${work.id}/Archive"] =
			mailbox.FakeFolder(
				"Archive",
				UID_VALIDITY,
				mutableMapOf(
					7L to remote(7, "Old paperwork", snippet = "the contract from 2019", received = Instant.parse("2019-03-01T00:00:00Z")),
					8L to remote(8, "Unrelated"),
				),
			)

		val result = search("q=contract&accountId=${work.id}&server=true")

		result["items"].strings("subject") shouldContainExactly listOf("Contract signed", "Old paperwork")
		result["server"]["searchedFolders"].asInt() shouldBe 2
		result["server"]["loaded"].asInt() shouldBe 1
		result["server"]["failures"].shouldBeEmpty()
		mailbox.searched.map { it.second }.distinct() shouldContainExactly listOf(listOf("contract"))
		messages.findByFolderIdAndUidIn(workArchive.id, listOf(7L, 8L)).map { it.uid } shouldContainExactly listOf(7L)
		// Without server=true the old message is found too now, by its cached headers (subject/snippet are indexed).
		subjects("q=paperwork") shouldContainExactly listOf("Old paperwork")
	}

	@Test
	fun `server search reports an unreachable account and still answers with the other hits`() {
		cache(homeInbox, remote(1, "Holiday plans"))
		mailbox.folders["${home.id}/INBOX"] = mailbox.FakeFolder("INBOX", UID_VALIDITY, mutableMapOf(1L to remote(1, "Holiday plans")))
		mailbox.failing += work.id

		val result = search("q=holiday&server=true")

		result["items"].strings("subject") shouldContainExactly listOf("Holiday plans")
		result["server"]["searchedFolders"].asInt() shouldBe 1
		val failure = result["server"]["failures"].single()
		failure["accountId"].asString() shouldBe work.id.toString()
		failure["errorCode"].asString() shouldBe "CONNECTION_FAILED"
	}

	@Test
	fun `server search keeps the most recent matches per folder and skips folders gone from the server or without messages`() {
		mailbox.folders["${work.id}/INBOX"] =
			mailbox.FakeFolder(
				"INBOX",
				UID_VALIDITY,
				mutableMapOf(1L to remote(1, "Receipt one"), 2L to remote(2, "Receipt two"), 3L to remote(3, "Receipt three")),
			)
		mailbox.gone += "${work.id}/Archive"
		// Not registered in the fake: opening it would fail the request.
		folders.save(MailFolder(work.id, "Projects", "Projects").apply { selectable = false })

		val result = search("q=receipt&accountId=${work.id}&server=true&limit=2")

		result["items"].strings("subject") shouldContainExactly listOf("Receipt three", "Receipt two")
		result["hasMore"].asBoolean() shouldBe false
		result["server"]["searchedFolders"].asInt() shouldBe 1
		result["server"]["loaded"].asInt() shouldBe 2
		result["server"]["truncated"].asBoolean() shouldBe true
		result["server"]["failures"].shouldBeEmpty()
		mailbox.searched.map { it.first } shouldContainExactly listOf("INBOX")
		messages.findByFolderIdAndUidIn(workInbox.id, listOf(1L, 2L, 3L)).map { it.uid }.toSet() shouldBe setOf(2L, 3L)
	}

	@Test
	fun `server search scoped to a folder that cannot hold messages searches nothing on the server`() {
		val group = folders.save(MailFolder(work.id, "Group", "Group").apply { selectable = false })

		val result = search("q=anything&folderId=${group.id}&server=true")

		result["items"].shouldBeEmpty()
		result["server"]["searchedFolders"].asInt() shouldBe 0
		mailbox.searched.shouldBeEmpty()
	}

	@Test
	fun `messages of a folder deleted from the cache are not found`() {
		cache(workArchive, remote(1, "Obsolete minutes"))
		cache(workInbox, remote(1, "Obsolete agenda"))

		// What the folder list sync does when a folder is gone: the rows stay (folder_id set null, uid left behind).
		jdbc.update("delete from mail_folder where id = ?", workArchive.id)

		subjects("q=obsolete") shouldContainExactly listOf("Obsolete agenda")
		subjects("q=obsolete&accountId=${work.id}") shouldContainExactly listOf("Obsolete agenda")
	}

	@Test
	fun `reconcile drops documents of messages whose folder was deleted and does not count them as added`() {
		val (_, unindexed) = cache(workArchive, remote(1, "Stale indexed"), remote(2, "Stale unindexed"))
		jdbc.update("delete from search_document where item_type = 'EMAIL' and item_id = ?", unindexed.id)
		jdbc.update("delete from mail_folder where id = ?", workArchive.id)

		index.reconcile(100) shouldBe (1 to 0)

		jdbc.queryForList("select item_id from search_document where item_type = 'EMAIL'", UUID::class.java).shouldBeEmpty()
		// Nothing left to repair: a later run finds no work.
		index.reconcile(100) shouldBe (0 to 0)
	}

	@Test
	fun `a query without words finds nothing and does not ask the server`() {
		cache(workInbox, remote(1, "Anything"))

		val result = search("q=%2B%2B%2B&server=true")

		result["items"].shouldBeEmpty()
		result["server"]["searchedFolders"].asInt() shouldBe 0
		mailbox.searched.shouldBeEmpty()
	}

	@Test
	fun `invalid requests are 400 or 404`() {
		mockMvc.get(URI("/api/v1/mail/search?q=%20")).andExpect { status { isBadRequest() } }
		mockMvc.get("/api/v1/mail/search?q=${"a".repeat(201)}").andExpect { status { isBadRequest() } }
		mockMvc.get("/api/v1/mail/search?q=a&limit=0").andExpect { status { isBadRequest() } }
		mockMvc.get("/api/v1/mail/search?q=a&limit=201").andExpect { status { isBadRequest() } }
		mockMvc.get("/api/v1/mail/search").andExpect { status { isBadRequest() } }
		mockMvc.get("/api/v1/mail/search?q=a&accountId=${home.id}&folderId=${workInbox.id}").andExpect { status { isBadRequest() } }
		mockMvc.get("/api/v1/mail/search?q=a&folderId=${UUID.randomUUID()}").andExpect { status { isNotFound() } }
		mockMvc.get("/api/v1/mail/search?q=a&accountId=${UUID.randomUUID()}").andExpect { status { isNotFound() } }
	}

	private companion object {
		const val UID_VALIDITY = 42L
	}
}
