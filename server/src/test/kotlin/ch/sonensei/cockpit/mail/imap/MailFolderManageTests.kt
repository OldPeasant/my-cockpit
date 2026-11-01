package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.mail.search.MailSearchIndex
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.mail.Folder
import jakarta.mail.FolderNotFoundException
import jakarta.mail.MessagingException
import jakarta.persistence.EntityManager
import org.eclipse.angus.mail.iap.BadCommandException
import org.eclipse.angus.mail.iap.CommandFailedException
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Edge cases of folder create/rename/delete against a scripted IMAP store with the real database: results the server
 * refuses (`false` or a thrown `NO`/`BAD`) become SERVER_REJECTED and leave the cache alone, flat namespaces, failing
 * (UN)SUBSCRIBE, subtree matching that does not confuse `Projects` with `Projectsx`, and the per-account serialization
 * of sync and rename. `/` is the delimiter.
 */
@SpringBootTest
@WithCockpitUser
@RecordApplicationEvents
class MailFolderManageTests(
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val changes: ChangeEventPublisher,
	@Autowired private val transactions: TransactionTemplate,
	@Autowired private val entityManager: EntityManager,
	@Autowired private val searchIndex: MailSearchIndex,
) : PostgresTestBase() {
	private val pool = mockk<ImapConnectionPool>()
	private val store = mockk<IMAPStore>()
	private val root = mockk<Folder>()
	private val remote = mutableMapOf<String, IMAPFolder>()
	private lateinit var service: MailFolderService
	private lateinit var account: MailAccount

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from mail_account")
		account = accounts.saveAndFlush(account("jane.doe@example.test"))
		service = MailFolderService(accounts, folders, pool, changes, transactions, entityManager, searchIndex)
		every { pool.withStore(any(), any<(IMAPStore) -> Any?>()) } answers { secondArg<(IMAPStore) -> Any?>().invoke(store) }
		every { store.defaultFolder } returns root
		every { root.separator } returns '/'
		scriptRemoteFolders()
		every { store.getFolder(any<String>()) } answers { remoteFolder(firstArg()) }
	}

	private fun account(email: String) =
		MailAccount(
			type = MailAccountType.IMAP_SMTP,
			displayName = "Work",
			emailAddress = email,
			imapHost = "imap.example.test",
			imapPort = 993,
			imapSecurity = MailSecurity.SSL_TLS,
			smtpHost = "smtp.example.test",
			smtpPort = 465,
			smtpSecurity = MailSecurity.SSL_TLS,
			username = "jane",
		).apply { password = EncryptedSecret("fictitious-secret-1") }

	/**
	 * The scripted server folders (every path a test touches; others fail loudly). Each does not exist until a test
	 * says so, and CREATE/RENAME/DELETE succeed by default. Created up front: MockK cannot stub inside an answer.
	 */
	private fun scriptRemoteFolders() {
		remote.clear()
		REMOTE_PATHS.forEach { path ->
			remote[path] =
				mockk<IMAPFolder>(relaxed = true).also {
					every { it.fullName } returns path
					every { it.separator } returns '/'
					every { it.exists() } returns false
					every { it.create(any()) } returns true
					every { it.renameTo(any()) } returns true
					every { it.delete(any()) } returns true
				}
		}
	}

	private fun remoteFolder(path: String): IMAPFolder = remote[path] ?: error("No scripted server folder $path")

	private fun existsRemotely(vararg paths: String) =
		paths.forEach {
			val folder = remoteFolder(it)
			every { folder.exists() } returns true
		}

	private fun cache(
		path: String,
		delimiter: String? = "/",
		role: MailFolderRole? = null,
		accountId: UUID = account.id,
	): MailFolder =
		folders.saveAndFlush(
			MailFolder(accountId, path, if (delimiter == null) path else path.substringAfterLast(delimiter)).apply {
				this.delimiter = delimiter
				this.role = role
			},
		)

	private fun cachedPaths() = folders.findByAccountIdOrderByPathAsc(account.id).map { it.path }

	private fun reload(folder: MailFolder) = folders.findById(folder.id).orElseThrow()

	private fun etag(folder: MailFolder) = "\"${reload(folder).version}\""

	private fun ApplicationEvents.folderEvents() =
		stream(ChangeEvent::class.java).toList().filter { it.type == MailFolderService.FOLDER_EVENT }.map { it.id to it.op }

	private fun refused(): MessagingException = MessagingException("NO fictitious refusal", CommandFailedException("NO [CANNOT] refused"))

	private fun rejected(block: () -> Unit) = shouldThrow<MailConnectionException>(block).error shouldBe MailConnectionError.SERVER_REJECTED

	// --- create ---

	@Test
	fun `create trims the name, creates and subscribes the folder under its parent and publishes CREATED`(events: ApplicationEvents) {
		val parent = cache("Projects")
		events.clear()

		val created = service.create(account.id, "  Q3 plans ", parent.id)

		created.path shouldBe "Projects/Q3 plans"
		created.name shouldBe "Q3 plans"
		created.delimiter shouldBe "/"
		remoteFolder("Projects/Q3 plans").let { f -> verify { f.create(Folder.HOLDS_MESSAGES or Folder.HOLDS_FOLDERS) } }
		remoteFolder("Projects/Q3 plans").let { f -> verify { f.isSubscribed = true } }
		events.folderEvents() shouldBe listOf(created.id to ChangeOp.CREATED)
	}

	@Test
	fun `a CREATE that returns false is SERVER_REJECTED and caches nothing`(events: ApplicationEvents) {
		remoteFolder("Projects").let { f -> every { f.create(any()) } returns false }

		rejected { service.create(account.id, "Projects", null) }

		cachedPaths() shouldBe emptyList()
		events.folderEvents() shouldBe emptyList()
	}

	@Test
	fun `a thrown NO or BAD and a missing parent are SERVER_REJECTED, other failures keep their connection error`() {
		remoteFolder("No").let { f -> every { f.create(any()) } throws refused() }
		remoteFolder("Bad").let { f -> every { f.create(any()) } throws MessagingException("BAD", BadCommandException("BAD syntax")) }
		val parent = cache("Vanished")
		remoteFolder("Vanished").let { f -> every { f.separator } throws FolderNotFoundException() }
		remoteFolder("Dropped").let { f -> every { f.create(any()) } throws MessagingException("fictitious connection reset") }

		rejected { service.create(account.id, "No", null) }
		rejected { service.create(account.id, "Bad", null) }
		rejected { service.create(account.id, "Child", parent.id) }
		shouldThrow<MailConnectionException> { service.create(account.id, "Dropped", null) }.error shouldBe
			MailConnectionError.CONNECTION_FAILED

		cachedPaths() shouldBe listOf("Vanished")
	}

	@Test
	fun `on a flat namespace create works at the top level and is 400 under a parent`() {
		every { root.separator } returns '\u0000'
		val parent = cache("Flat", delimiter = null)
		remoteFolder("Flat").let { f -> every { f.separator } returns '\u0000' }

		service.create(account.id, "a/b", null).let {
			it.path shouldBe "a/b"
			it.delimiter.shouldBeNull()
		}
		shouldThrow<InvalidRequestException> { service.create(account.id, "Child", parent.id) }

		remote.filterKeys { it != "a/b" }.values.forEach { verify(exactly = 0) { it.create(any()) } }
		cachedPaths() shouldBe listOf("Flat", "a/b")
	}

	@Test
	fun `create refuses blank names and the delimiter with 400 and INBOX in any case with 409`() {
		cache("INBOX", delimiter = null, role = MailFolderRole.INBOX)

		shouldThrow<InvalidRequestException> { service.create(account.id, "   ", null) }
		// A blank name needs no server; the delimiter and taken names are checked once the server's delimiter is known.
		verify(exactly = 0) { pool.withStore(any(), any<(IMAPStore) -> Any?>()) }
		shouldThrow<InvalidRequestException> { service.create(account.id, "a/b", null) }
		shouldThrow<ConflictException> { service.create(account.id, "inbox", null) }
		shouldThrow<ConflictException> { service.create(account.id, " Inbox ", null) }

		remote.values.forEach { verify(exactly = 0) { it.create(any()) } }
		cachedPaths() shouldBe listOf("INBOX")
	}

	@Test
	fun `create checks the parent and account before contacting the server`() {
		val other = accounts.saveAndFlush(account("other.person@example.test"))
		val foreignParent = cache("Theirs", accountId = other.id)

		shouldThrow<NotFoundException> { service.create(account.id, "Child", foreignParent.id) }
		shouldThrow<NotFoundException> { service.create(UUID.randomUUID(), "Child", null) }

		verify(exactly = 0) { pool.withStore(any(), any<(IMAPStore) -> Any?>()) }
	}

	@Test
	fun `a failing SUBSCRIBE after CREATE still creates the folder`() {
		remoteFolder("Receipts").let { f -> every { f.isSubscribed = true } throws MessagingException("NO subscribe refused") }

		service.create(account.id, "Receipts", null).path shouldBe "Receipts"

		cachedPaths() shouldBe listOf("Receipts")
	}

	@Test
	fun `create returns the folder a parallel sync cached meanwhile without a second CREATED event`(events: ApplicationEvents) {
		var synced: MailFolder? = null
		val receipts = remoteFolder("Receipts")
		every { receipts.create(any()) } answers {
			synced = cache("Receipts")
			true
		}
		events.clear()

		val created = service.create(account.id, "Receipts", null)

		created.id shouldBe synced.shouldNotBeNull().id
		cachedPaths() shouldBe listOf("Receipts")
		events.folderEvents() shouldBe emptyList()
	}

	// --- rename ---

	@Test
	fun `rename moves the folder and its subfolders but not siblings sharing its prefix`(events: ApplicationEvents) {
		val projects = cache("Projects")
		val sub = cache("Projects/Sub")
		val deep = cache("Projects/Sub/Deep")
		val longer = cache("Projectsx")
		val dashed = cache("Projects-old")
		existsRemotely("Projects")
		events.clear()

		val renamed = service.rename(account.id, projects.id, " Work ", etag(projects))

		renamed.path shouldBe "Work"
		renamed.name shouldBe "Work"
		val work = remoteFolder("Work")
		remoteFolder("Projects").let { f -> verify { f.renameTo(work) } }
		remoteFolder("Work").let { f -> verify { f.isSubscribed = true } }
		reload(sub).path shouldBe "Work/Sub"
		reload(sub).name shouldBe "Sub"
		reload(deep).path shouldBe "Work/Sub/Deep"
		reload(longer).path shouldBe "Projectsx"
		reload(dashed).path shouldBe "Projects-old"
		events.folderEvents() shouldContainExactlyInAnyOrder
			listOf(projects.id to ChangeOp.UPDATED, sub.id to ChangeOp.UPDATED, deep.id to ChangeOp.UPDATED)
	}

	@Test
	fun `rename to the same trimmed name changes nothing and does not contact the server`(events: ApplicationEvents) {
		val projects = cache("Clients/Projects")
		events.clear()

		service.rename(account.id, projects.id, " Projects ", etag(projects)).path shouldBe "Clients/Projects"

		verify(exactly = 0) { pool.withStore(any(), any<(IMAPStore) -> Any?>()) }
		reload(projects).version shouldBe projects.version
		events.folderEvents() shouldBe emptyList()
	}

	@Test
	fun `rename in a flat namespace uses the new name as the path and moves no lookalikes`() {
		val old = cache("Old", delimiter = null)
		val lookalike = cache("Old/Sub", delimiter = null)
		existsRemotely("Old")

		service.rename(account.id, old.id, "New/Name", etag(old)).path shouldBe "New/Name"

		reload(lookalike).path shouldBe "Old/Sub"
	}

	@Test
	fun `a RENAME that returns false, throws NO, or finds the source gone is SERVER_REJECTED and leaves the cache alone`(
		events: ApplicationEvents,
	) {
		val refusedFalse = cache("A")
		val refusedThrown = cache("B")
		val gone = cache("C")
		existsRemotely("A", "B")
		remoteFolder("A").let { f -> every { f.renameTo(any()) } returns false }
		remoteFolder("B").let { f -> every { f.renameTo(any()) } throws refused() }
		events.clear()

		rejected { service.rename(account.id, refusedFalse.id, "A2", etag(refusedFalse)) }
		rejected { service.rename(account.id, refusedThrown.id, "B2", etag(refusedThrown)) }
		rejected { service.rename(account.id, gone.id, "C2", etag(gone)) }

		remoteFolder("C").let { f -> verify(exactly = 0) { f.renameTo(any()) } }
		cachedPaths() shouldBe listOf("A", "B", "C")
		events.folderEvents() shouldBe emptyList()
	}

	@Test
	fun `rename to a name that exists only on the server is 409`() {
		val projects = cache("Projects")
		existsRemotely("Projects", "Done")

		shouldThrow<ConflictException> { service.rename(account.id, projects.id, "Done", etag(projects)) }

		remoteFolder("Projects").let { f -> verify(exactly = 0) { f.renameTo(any()) } }
		cachedPaths() shouldBe listOf("Projects")
	}

	@Test
	fun `a failing SUBSCRIBE after RENAME does not fail the rename`() {
		val projects = cache("Projects")
		existsRemotely("Projects")
		remoteFolder("Work").let { f -> every { f.isSubscribed = true } throws MessagingException("NO subscribe refused") }

		service.rename(account.id, projects.id, "Work", etag(projects)).path shouldBe "Work"
	}

	@Test
	fun `INBOX is recognised by path in any case and a top-level rename to inbox is taken`() {
		val lowerInbox = cache("Inbox", delimiter = null)
		val projects = cache("Projects")

		shouldThrow<ConflictException> { service.rename(account.id, lowerInbox.id, "Mail", etag(lowerInbox)) }
		shouldThrow<ConflictException> { service.rename(account.id, projects.id, "INBOX", etag(projects)) }

		verify(exactly = 0) { pool.withStore(any(), any<(IMAPStore) -> Any?>()) }
	}

	@Test
	fun `rename refuses blank names and the delimiter with 400 before contacting the server`() {
		val projects = cache("Clients/Projects")

		shouldThrow<InvalidRequestException> { service.rename(account.id, projects.id, "  ", etag(projects)) }
		shouldThrow<InvalidRequestException> { service.rename(account.id, projects.id, "Q3/plans", etag(projects)) }

		verify(exactly = 0) { pool.withStore(any(), any<(IMAPStore) -> Any?>()) }
		reload(projects).path shouldBe "Clients/Projects"
	}

	// --- delete ---

	@Test
	fun `delete removes the folder and its subfolders but not siblings sharing its prefix`(events: ApplicationEvents) {
		val projects = cache("Projects")
		val sub = cache("Projects/Sub")
		cache("Projectsx")
		cache("Projects-old")
		existsRemotely("Projects")
		events.clear()

		service.delete(account.id, projects.id, etag(projects))

		remoteFolder("Projects").let { f -> verify { f.delete(true) } }
		remoteFolder("Projects").let { f -> verify { f.isSubscribed = false } }
		cachedPaths() shouldBe listOf("Projects-old", "Projectsx")
		events.folderEvents() shouldContainExactlyInAnyOrder listOf(projects.id to ChangeOp.DELETED, sub.id to ChangeOp.DELETED)
	}

	@Test
	fun `a DELETE that returns false or throws NO is SERVER_REJECTED and keeps the cache`(events: ApplicationEvents) {
		val refusedFalse = cache("A")
		val refusedThrown = cache("B")
		existsRemotely("A", "B")
		remoteFolder("A").let { f -> every { f.delete(any()) } returns false }
		remoteFolder("B").let { f -> every { f.delete(any()) } throws refused() }
		events.clear()

		rejected { service.delete(account.id, refusedFalse.id, etag(refusedFalse)) }
		rejected { service.delete(account.id, refusedThrown.id, etag(refusedThrown)) }

		cachedPaths() shouldBe listOf("A", "B")
		events.folderEvents() shouldBe emptyList()
	}

	@Test
	fun `a failing UNSUBSCRIBE after DELETE still deletes`() {
		val old = cache("Old")
		existsRemotely("Old")
		remoteFolder("Old").let { f -> every { f.isSubscribed = false } throws MessagingException("NO unsubscribe refused") }

		service.delete(account.id, old.id, etag(old))

		cachedPaths() shouldBe emptyList()
	}

	@Test
	fun `delete refuses an inbox without role by path and a folder of another account`() {
		val lowerInbox = cache("inbox", delimiter = null)
		val other = accounts.saveAndFlush(account("other.person@example.test"))
		val theirs = cache("Theirs", accountId = other.id)

		shouldThrow<ConflictException> { service.delete(account.id, lowerInbox.id, etag(lowerInbox)) }
		shouldThrow<NotFoundException> { service.delete(account.id, theirs.id, etag(theirs)) }

		verify(exactly = 0) { pool.withStore(any(), any<(IMAPStore) -> Any?>()) }
		folders.findById(theirs.id).isPresent shouldBe true
	}

	// --- serialization of sync and changes (per account) ---

	/** A folder as `LIST "" "*"` returns it. */
	private fun listed(path: String): IMAPFolder =
		mockk<IMAPFolder>(relaxed = true).also {
			every { it.fullName } returns path
			every { it.name } returns path.substringAfterLast('/')
			every { it.separator } returns '/'
			every { it.attributes } returns emptyArray()
		}

	/**
	 * Scripts `Projects` (with `Projects/Sub`) to be renamed to `Work` on the server: `LIST` shows the old or the new
	 * paths depending on whether the RENAME has run. Returns the cached folders.
	 */
	private fun projectsToBeRenamed(): Pair<MailFolder, MailFolder> {
		val projects = cache("Projects")
		val sub = cache("Projects/Sub")
		existsRemotely("Projects")
		val before = arrayOf<Folder>(listed("INBOX"), listed("Projects"), listed("Projects/Sub"))
		val after = arrayOf<Folder>(listed("INBOX"), listed("Work"), listed("Work/Sub"))
		every { root.list("*") } answers { if (renamedOnServer.get()) after else before }
		remoteFolder("Projects").let { f ->
			every { f.renameTo(any()) } answers {
				renamedOnServer.set(true)
				true
			}
		}
		return projects to sub
	}

	private val renamedOnServer = AtomicBoolean(false)

	private fun CountDownLatch.awaitBriefly() = await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true

	private fun <T> Future<T>.result(): T = get(WAIT_SECONDS, TimeUnit.SECONDS)

	/** Waits (bounded) until [thread] is parked, i.e. blocked on the account lock rather than running. */
	private fun awaitParked(thread: AtomicReference<Thread>) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
		while (thread.get()?.state !in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)) {
			check(System.nanoTime() < deadline) { "The second call never blocked" }
			Thread.onSpinWait()
		}
	}

	@Test
	fun `a sync started while a RENAME runs waits for the rename and then keeps the folder and subfolder ids`() {
		val (projects, sub) = projectsToBeRenamed()
		val renaming = CountDownLatch(1)
		val release = CountDownLatch(1)
		remoteFolder("Projects").let { f ->
			every { f.renameTo(any()) } answers {
				renamedOnServer.set(true)
				renaming.countDown()
				release.awaitBriefly()
				true
			}
		}
		val ifMatch = etag(projects)
		val syncThread = AtomicReference<Thread>()
		val executor = Executors.newFixedThreadPool(2)
		try {
			val rename = executor.submit<MailFolder> { service.rename(account.id, projects.id, "Work", ifMatch) }
			renaming.awaitBriefly()
			val sync =
				executor.submit<List<MailFolder>> {
					syncThread.set(Thread.currentThread())
					service.sync(account.id)
				}

			awaitParked(syncThread)
			// The server already has the new name, but the sync must not list it before the rename updated the cache.
			sync.isDone shouldBe false
			verify(exactly = 0) { root.list(any()) }
			release.countDown()

			rename.result().path shouldBe "Work"
			val synced = sync.result().associate { it.path to it.id }
			synced["Work"] shouldBe projects.id
			synced["Work/Sub"] shouldBe sub.id
		} finally {
			release.countDown()
			executor.shutdownNow()
		}
		cachedPaths() shouldBe listOf("INBOX", "Work", "Work/Sub")
		reload(projects).name shouldBe "Work"
	}

	@Test
	fun `a rename requested while a sync lists the server waits until the sync has stored its result`() {
		val (projects, sub) = projectsToBeRenamed()
		val listing = CountDownLatch(1)
		val release = CountDownLatch(1)
		val before = arrayOf<Folder>(listed("INBOX"), listed("Projects"), listed("Projects/Sub"))
		every { root.list("*") } answers {
			listing.countDown()
			release.awaitBriefly()
			before
		}
		val ifMatch = etag(projects)
		val renameThread = AtomicReference<Thread>()
		val executor = Executors.newFixedThreadPool(2)
		try {
			val sync = executor.submit<List<MailFolder>> { service.sync(account.id) }
			listing.awaitBriefly()
			val rename =
				executor.submit<MailFolder> {
					renameThread.set(Thread.currentThread())
					service.rename(account.id, projects.id, "Work", ifMatch)
				}

			awaitParked(renameThread)
			rename.isDone shouldBe false
			remoteFolder("Projects").let { f -> verify(exactly = 0) { f.renameTo(any()) } }
			release.countDown()

			sync.result().map { it.path } shouldBe listOf("INBOX", "Projects", "Projects/Sub")
			rename.result().id shouldBe projects.id
		} finally {
			release.countDown()
			executor.shutdownNow()
		}
		reload(projects).path shouldBe "Work"
		reload(sub).path shouldBe "Work/Sub"
		cachedPaths() shouldBe listOf("INBOX", "Work", "Work/Sub")
	}

	@Test
	fun `a RENAME in progress does not block a sync of another account`() {
		val (projects, _) = projectsToBeRenamed()
		val other = accounts.saveAndFlush(account("other.person@example.test"))
		val renaming = CountDownLatch(1)
		val release = CountDownLatch(1)
		remoteFolder("Projects").let { f ->
			every { f.renameTo(any()) } answers {
				renaming.countDown()
				release.awaitBriefly()
				true
			}
		}
		val ifMatch = etag(projects)
		val executor = Executors.newSingleThreadExecutor()
		try {
			val rename = executor.submit<MailFolder> { service.rename(account.id, projects.id, "Work", ifMatch) }
			renaming.awaitBriefly()

			// Runs on the test thread: it would hang (and fail the bounded wait in the rename) if the lock were global.
			service.sync(other.id).map { it.path } shouldBe listOf("INBOX", "Projects", "Projects/Sub")
			rename.isDone shouldBe false
			release.countDown()

			rename.result().path shouldBe "Work"
		} finally {
			release.countDown()
			executor.shutdownNow()
		}
	}

	companion object {
		private const val WAIT_SECONDS = 10L

		private val REMOTE_PATHS =
			listOf(
				"Projects",
				"Projects/Q3 plans",
				"Work",
				"Done",
				"No",
				"Bad",
				"Vanished",
				"Dropped",
				"Flat",
				"a/b",
				"Receipts",
				"Old",
				"New/Name",
				"A",
				"A2",
				"B",
				"B2",
				"C",
				"C2",
			)
	}
}
