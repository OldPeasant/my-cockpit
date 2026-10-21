package ch.sonensei.cockpit.mail.sync

import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.mail.imap.MailAccountConnectionChanged
import ch.sonensei.cockpit.mail.imap.MailAccountService
import ch.sonensei.cockpit.mail.imap.MailConnectionError
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import ch.sonensei.cockpit.mail.imap.MailFolderService
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailSecurity
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class MailPushServiceTests {
	private val accounts = mockk<MailAccountRepository>()
	private val folders = mockk<MailFolderRepository>()
	private val folderService = mockk<MailFolderService>(relaxed = true)
	private val sync = mockk<MailHeaderSyncService>()
	private val properties =
		MailPushProperties(true, Duration.ofMinutes(29), Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(10))

	/** Accounts whose server has IDLE; the others throw [IdleNotSupportedException]. */
	private val idleCapable = ConcurrentHashMap.newKeySet<UUID>()
	private val sessions = ConcurrentHashMap<UUID, CopyOnWriteArrayList<FakeIdleSession>>()
	private val connector =
		IdleConnector { account, _ ->
			if (account.id !in idleCapable) throw IdleNotSupportedException()
			FakeIdleSession().also { sessions.getOrPut(account.id) { CopyOnWriteArrayList() } += it }
		}
	private val service = MailPushService(accounts, folders, folderService, sync, connector, properties)

	private val ann = account("ann")
	private val bob = account("bob")
	private val known = CopyOnWriteArrayList<MailAccount>()
	private val folderList = mutableMapOf<UUID, List<MailFolder>>()

	private fun account(name: String) =
		MailAccount(
			MailAccountType.IMAP_SMTP,
			name,
			"$name@example.test",
			"imap.example.test",
			993,
			MailSecurity.SSL_TLS,
			"smtp.example.test",
			465,
			MailSecurity.SSL_TLS,
			name,
		)

	/** Inbox, Archive and a `\Noselect` group folder for [account]. */
	private fun foldersOf(account: MailAccount): List<MailFolder> {
		val list =
			listOf(
				MailFolder(account.id, "Archive", "Archive").apply { role = MailFolderRole.ARCHIVE },
				MailFolder(account.id, "Group", "Group").apply { selectable = false },
				MailFolder(account.id, "INBOX", "INBOX").apply { role = MailFolderRole.INBOX },
			)
		folderList[account.id] = list
		return list
	}

	private fun inboxOf(account: MailAccount) = folderList.getValue(account.id).single { it.role == MailFolderRole.INBOX }

	private fun archiveOf(account: MailAccount) = folderList.getValue(account.id).single { it.role == MailFolderRole.ARCHIVE }

	private fun groupOf(account: MailAccount) = folderList.getValue(account.id).single { !it.selectable }

	@BeforeEach
	fun setUp() {
		foldersOf(ann)
		foldersOf(bob)
		every { accounts.findAll() } answers { known.toList() }
		every { accounts.findById(any()) } answers { Optional.ofNullable(known.find { it.id == firstArg() }) }
		every { accounts.existsById(any()) } answers { known.any { it.id == firstArg() } }
		every { folders.findByAccountIdOrderByPathAsc(any()) } answers { folderList[firstArg()].orEmpty() }
		every { folders.findByAccountIdAndRole(any(), MailFolderRole.INBOX) } answers {
			folderList[firstArg()]?.find { it.role == MailFolderRole.INBOX }
		}
		every { sync.syncFolder(any()) } answers { FolderSyncResult(firstArg(), full = false, 0, 0, 0) }
	}

	@AfterEach
	fun tearDown() {
		service.stop()
	}

	private fun idlingWatcher(account: MailAccount): InboxWatcher {
		waitUntil { service.watcher(account.id)?.idling == true }
		return service.watcher(account.id)!!
	}

	private fun unsupportedWatcher(account: MailAccount): InboxWatcher {
		waitUntil { service.watcher(account.id)?.unsupported == true && service.watcher(account.id)?.alive == false }
		return service.watcher(account.id)!!
	}

	@Test
	fun `start watches the Inbox of every account`() {
		idleCapable += listOf(ann.id, bob.id)
		known += listOf(ann, bob)

		service.start()

		idlingWatcher(ann)
		idlingWatcher(bob)
		service.isRunning shouldBe true
		// The catch-up sync of each watcher.
		verify(exactly = 1) { sync.syncFolder(inboxOf(ann).id) }
		verify(exactly = 1) { sync.syncFolder(inboxOf(bob).id) }
	}

	@Test
	fun `poll syncs the folder list and every selectable folder, the Inbox too while its IDLE runs`() {
		idleCapable += ann.id
		known += ann
		service.start()
		idlingWatcher(ann)

		service.poll()

		verify(exactly = 1) { folderService.sync(ann.id) }
		verify(exactly = 1) { sync.syncFolder(archiveOf(ann).id) }
		verify(exactly = 0) { sync.syncFolder(groupOf(ann).id) }
		// The watcher's catch-up sync plus the poll's safety-net sync (an IDLE may have died silently).
		verify(exactly = 2) { sync.syncFolder(inboxOf(ann).id) }
	}

	@Test
	fun `an exception outside the per-account handling does not escape the poll`() {
		known += ann
		service.start()
		unsupportedWatcher(ann)
		every { accounts.findAll() } throws IllegalStateException("Fictitious database outage")

		service.poll()

		verify(exactly = 0) { folderService.sync(any()) }
	}

	@Test
	fun `start schedules the poll every poll interval on its own thread`() {
		known += ann
		val polling =
			MailPushService(
				accounts,
				folders,
				folderService,
				sync,
				connector,
				MailPushProperties(true, Duration.ofMinutes(29), Duration.ofMillis(50), Duration.ofMinutes(10), Duration.ofMinutes(10)),
			)
		val polls = AtomicInteger()
		every { folderService.sync(ann.id) } answers {
			polls.incrementAndGet()
			emptyList()
		}
		try {
			polling.start()
			waitUntil { polls.get() >= 2 }
		} finally {
			polling.stop()
		}
		polling.isRunning shouldBe false
	}

	@Test
	fun `poll syncs the Inbox of an account whose server has no IDLE`() {
		known += ann
		service.start()
		unsupportedWatcher(ann)

		service.poll()

		verify(exactly = 1) { sync.syncFolder(inboxOf(ann).id) }
		verify(exactly = 1) { sync.syncFolder(archiveOf(ann).id) }
	}

	@Test
	fun `poll syncs the Inbox while the IDLE connection is down`() {
		known += ann
		val failing =
			MailPushService(
				accounts,
				folders,
				folderService,
				sync,
				{ _, _ -> throw MailConnectionException(MailConnectionError.TIMEOUT) },
				properties,
			)
		try {
			failing.start()
			waitUntil { (failing.watcher(ann.id)?.failures ?: 0) >= 1 }

			failing.poll()

			verify(exactly = 1) { sync.syncFolder(inboxOf(ann).id) }
		} finally {
			failing.stop()
		}
	}

	@Test
	fun `a failing account does not stop the poll of the others`() {
		known += listOf(ann, bob)
		service.start()
		unsupportedWatcher(ann)
		unsupportedWatcher(bob)
		every { folderService.sync(ann.id) } throws IllegalStateException("Fictitious folder list failure")

		service.poll()

		verify(exactly = 0) { sync.syncFolder(inboxOf(ann).id) }
		verify(exactly = 1) { sync.syncFolder(inboxOf(bob).id) }
		verify(exactly = 1) { sync.syncFolder(archiveOf(bob).id) }
	}

	@Test
	fun `a folder that is gone from the server does not stop the poll of the account's other folders`() {
		known += ann
		service.start()
		unsupportedWatcher(ann)
		every { sync.syncFolder(archiveOf(ann).id) } throws RemoteFolderNotFoundException("Archive")

		service.poll()

		verify(exactly = 1) { sync.syncFolder(inboxOf(ann).id) }
	}

	@Test
	fun `poll does nothing before start or after stop`() {
		known += ann

		service.poll()
		service.reconcile()

		service.watcher(ann.id).shouldBeNull()
		verify(exactly = 0) { accounts.findAll() }
	}

	@Test
	fun `reconcile stops the watchers of deleted accounts and keeps the others`() {
		idleCapable += listOf(ann.id, bob.id)
		known += listOf(ann, bob)
		service.start()
		val annWatcher = idlingWatcher(ann)
		val bobWatcher = idlingWatcher(bob)

		known -= bob
		service.reconcile()

		service.watcher(bob.id).shouldBeNull()
		bobWatcher.alive shouldBe false
		waitUntil { sessions.getValue(bob.id).single().closed }
		service.watcher(ann.id) shouldBeSameInstanceAs annWatcher
		annWatcher.alive shouldBe true
	}

	@Test
	fun `reconcile starts watchers for new accounts but leaves an account without IDLE alone`() {
		known += ann
		service.start()
		val annWatcher = unsupportedWatcher(ann)

		idleCapable += bob.id
		known += bob
		service.reconcile()

		service.watcher(ann.id) shouldBeSameInstanceAs annWatcher
		idlingWatcher(bob)
	}

	@Test
	fun `a created account gets its watcher right away`() {
		idleCapable += ann.id
		service.start()
		known += ann

		service.onChange(ChangeEvent(MailAccountService.ACCOUNT_EVENT, ann.id, ChangeOp.CREATED, Instant.parse("2026-10-21T08:00:00Z")))

		idlingWatcher(ann)
	}

	@Test
	fun `change events of other kinds or operations start no watcher`() {
		idleCapable += ann.id
		service.start()
		known += ann
		val at = Instant.parse("2026-10-21T08:00:00Z")

		service.onChange(ChangeEvent(MailAccountService.ACCOUNT_EVENT, ann.id, ChangeOp.UPDATED, at))
		service.onChange(ChangeEvent("MAIL_FOLDER", ann.id, ChangeOp.CREATED, at))

		service.watcher(ann.id).shouldBeNull()
	}

	@Test
	fun `changed connection settings restart the account's watcher`() {
		idleCapable += ann.id
		known += ann
		service.start()
		val old = idlingWatcher(ann)

		service.onAccountChanged(MailAccountConnectionChanged(ann.id))

		old.alive shouldBe false
		waitUntil { sessions.getValue(ann.id).first().closed }
		val restarted = idlingWatcher(ann)
		restarted shouldNotBeSameInstanceAs old
		sessions.getValue(ann.id).size shouldBe 2
	}

	@Test
	fun `a deleted account's watcher is stopped and not restarted`() {
		idleCapable += ann.id
		known += ann
		service.start()
		val old = idlingWatcher(ann)

		known -= ann
		service.onAccountChanged(MailAccountConnectionChanged(ann.id))

		old.alive shouldBe false
		service.watcher(ann.id).shouldBeNull()
	}

	@Test
	fun `stop ends every watcher and closes its connection`() {
		idleCapable += listOf(ann.id, bob.id)
		known += listOf(ann, bob)
		service.start()
		val watchers = listOf(idlingWatcher(ann), idlingWatcher(bob))

		service.stop()

		service.isRunning shouldBe false
		watchers.forEach { it.alive shouldBe false }
		waitUntil { sessions.values.flatten().all { it.closed } }
		service.watcher(ann.id).shouldBeNull()
	}

	@Test
	fun `a watcher connects with the cached Inbox path and syncs the folder list first when the Inbox is unknown`() {
		known += ann
		val paths = CopyOnWriteArrayList<String>()
		every { folders.findByAccountIdAndRole(ann.id, MailFolderRole.INBOX) } returnsMany
			listOf(null, inboxOf(ann))
		val recording =
			MailPushService(
				accounts,
				folders,
				folderService,
				sync,
				{ _, path ->
					paths += path
					FakeIdleSession()
				},
				properties,
			)
		try {
			recording.start()
			waitUntil { recording.watcher(ann.id)?.idling == true }

			paths.single() shouldBe "INBOX"
			verify(exactly = 1) { folderService.sync(ann.id) }
			recording.watcher(ann.id).shouldNotBeNull()
		} finally {
			recording.stop()
		}
	}
}
