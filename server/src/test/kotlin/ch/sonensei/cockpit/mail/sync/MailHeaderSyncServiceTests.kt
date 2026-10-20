package ch.sonensei.cockpit.mail.sync

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailSecurity
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class MailHeaderSyncServiceTests {
	private val now = Instant.parse("2026-10-20T10:00:00Z")
	private val account =
		MailAccount(
			MailAccountType.IMAP_SMTP,
			"Test",
			"jane@example.test",
			"imap.example.test",
			993,
			MailSecurity.SSL_TLS,
			"smtp.example.test",
			465,
			MailSecurity.SSL_TLS,
			"jane",
		).apply { syncWindowMonths = 6 }
	private val folder = MailFolder(account.id, "INBOX", "INBOX")
	private val accounts = mockk<MailAccountRepository>()
	private val folders = mockk<MailFolderRepository>()
	private val store = mockk<MailHeaderStore>(relaxed = true)
	private val changes = mockk<ChangeEventPublisher>(relaxed = true)
	private val remote = FakeRemoteFolder()
	private var openedWith: KnownFolderState? = null
	private var opened = 0
	private val mailbox =
		object : RemoteMailbox {
			override fun <T> withFolder(
				account: MailAccount,
				path: String,
				known: KnownFolderState?,
				block: (RemoteFolder) -> T,
			): T {
				opened++
				openedWith = known
				return block(remote)
			}
		}
	private val service = MailHeaderSyncService(accounts, folders, mailbox, store, changes, Clock.fixed(now, ZoneOffset.UTC))

	/** A server folder whose answers the test sets; it records which reads the sync made. */
	private class FakeRemoteFolder : RemoteFolder {
		override var status = RemoteFolderStatus(7, 12, null, condstore = false, qresync = false)
		override var qresyncChanges: QresyncChanges? = null
		var uids: List<Long> = emptyList()
		var flags: List<RemoteFlagState> = emptyList()
		var changedSince: List<RemoteFlagState> = emptyList()
		val calls = mutableListOf<String>()

		override fun uidsSince(since: Instant): List<Long> {
			calls += "uidsSince $since"
			return uids
		}

		override fun uidsFrom(fromUid: Long): List<Long> {
			calls += "uidsFrom $fromUid"
			return uids.filter { it >= fromUid }
		}

		override fun uidsBelow(uid: Long): List<Long> {
			calls += "uidsBelow $uid"
			return uids.filter { it < uid }
		}

		override fun headers(uids: List<Long>): List<RemoteMessage> {
			calls += "headers $uids"
			return uids.map(::message)
		}

		override fun flagsFrom(fromUid: Long): List<RemoteFlagState> {
			calls += "flagsFrom $fromUid"
			return flags
		}

		override fun flagsChangedSince(
			fromUid: Long,
			modSeq: Long,
		): List<RemoteFlagState> {
			calls += "flagsChangedSince $fromUid $modSeq"
			return changedSince
		}

		fun message(uid: Long) =
			RemoteMessage(
				uid,
				"<$uid@example.test>",
				null,
				null,
				"Subject $uid",
				emptyList(),
				emptyList(),
				emptyList(),
				emptyList(),
				emptyList(),
				null,
				null,
				RemoteFlags(),
				10,
				false,
				null,
			)
	}

	@BeforeEach
	fun setUp() {
		every { folders.findById(folder.id) } returns Optional.of(folder)
		every { accounts.findById(account.id) } returns Optional.of(account)
		every { store.upsert(any(), any(), any()) } answers { StoreCounts(created = thirdArg<List<RemoteMessage>>().size) }
		every { store.reconcile(any(), any(), any()) } answers {
			StoreCounts(updated = secondArg<List<RemoteFlagState>>().size, removed = thirdArg<Set<Long>>().size)
		}
	}

	private fun cachedState(
		uidNext: Long = 10,
		highestModSeq: Long? = null,
	) {
		folder.uidValidity = 7
		folder.uidNext = uidNext
		folder.highestModSeq = highestModSeq
	}

	private fun upserted(): List<Long> {
		val batches = mutableListOf<List<RemoteMessage>>()
		verify { store.upsert(account.id, folder.id, capture(batches)) }
		return batches.flatten().map { it.uid }
	}

	@Test
	fun `the first sync fetches the account's window and records the folder state`() {
		remote.uids = (1L..450L).toList()

		val result = service.syncFolder(folder.id)

		result shouldBe FolderSyncResult(folder.id, full = true, created = 450, updated = 0, removed = 0)
		remote.calls.first() shouldBe "uidsSince 2026-04-20T10:00:00Z"
		// In batches of 200.
		remote.calls.filter { it.startsWith("headers") }.size shouldBe 3
		upserted() shouldBe remote.uids
		verify { store.finish(folder.id, remote.status, now) }
		verify(exactly = 0) { store.detachFolder(any()) }
		verify(exactly = 0) { store.pruneDetached(any()) }
		verify { changes.publish("MAIL_FOLDER", folder.id, ChangeOp.UPDATED) }
	}

	@Test
	fun `a changed UIDVALIDITY detaches, resyncs and prunes the folder`() {
		cachedState()
		remote.status = remote.status.copy(uidValidity = 8)
		remote.uids = listOf(1, 2)
		every { store.pruneDetached(folder.id) } returns 3

		val result = service.syncFolder(folder.id)

		result shouldBe FolderSyncResult(folder.id, full = true, created = 2, updated = 0, removed = 3)
		verify { store.detachFolder(folder.id) }
		verify { store.pruneDetached(folder.id) }
		remote.calls.none { it.startsWith("flags") } shouldBe true
	}

	@Test
	fun `without CONDSTORE the flags of every cached UID are fetched and missing UIDs removed`() {
		cachedState(uidNext = 10)
		every { store.locatedUids(folder.id) } returns listOf(3, 5, 8)
		remote.flags = listOf(RemoteFlagState(3, RemoteFlags(seen = true)), RemoteFlagState(8, RemoteFlags()))
		remote.uids = listOf(3, 8, 10, 11)

		val result = service.syncFolder(folder.id)

		verify { store.reconcile(folder.id, remote.flags, setOf(5L)) }
		upserted() shouldContainExactly listOf(10, 11)
		remote.calls shouldContainExactly listOf("flagsFrom 3", "uidsFrom 10", "headers [10, 11]")
		result shouldBe FolderSyncResult(folder.id, full = false, created = 2, updated = 2, removed = 1)
		openedWith shouldBe null
	}

	@Test
	fun `with CONDSTORE only flags changed since the cached HIGHESTMODSEQ are fetched`() {
		cachedState(uidNext = 12, highestModSeq = 100)
		remote.status = RemoteFolderStatus(7, 12, 120, condstore = true, qresync = false)
		every { store.locatedUids(folder.id) } returns listOf(3, 5, 8)
		remote.changedSince = listOf(RemoteFlagState(8, RemoteFlags(flagged = true), 119))
		remote.uids = listOf(3, 8)

		service.syncFolder(folder.id)

		verify { store.reconcile(folder.id, remote.changedSince, setOf(5L)) }
		remote.calls shouldContainExactly listOf("flagsChangedSince 3 100", "uidsFrom 3")
		verify { store.finish(folder.id, remote.status, now) }
		// Without QRESYNC support the state is offered, but the fake answers like a CONDSTORE-only server.
		openedWith shouldBe KnownFolderState(7, 100)
	}

	@Test
	fun `with CONDSTORE and an unchanged HIGHESTMODSEQ no flags are fetched`() {
		cachedState(uidNext = 12, highestModSeq = 100)
		remote.status = RemoteFolderStatus(7, 12, 100, condstore = true, qresync = false)
		every { store.locatedUids(folder.id) } returns listOf(3, 5)
		remote.uids = listOf(3, 5)

		val result = service.syncFolder(folder.id)

		remote.calls shouldContainExactly listOf("uidsFrom 3")
		verify { store.reconcile(folder.id, emptyList(), emptySet()) }
		result shouldBe FolderSyncResult(folder.id, full = false, created = 0, updated = 0, removed = 0)
		verify(exactly = 0) { changes.publish(any<String>(), any(), any()) }
	}

	@Test
	fun `with QRESYNC the changes reported on open are applied without further reads`() {
		cachedState(uidNext = 12, highestModSeq = 100)
		remote.status = RemoteFolderStatus(7, 13, 130, condstore = true, qresync = true)
		remote.qresyncChanges = QresyncChanges(setOf(5), listOf(RemoteFlagState(8, RemoteFlags(answered = true), 125)))
		every { store.locatedUids(folder.id) } returns listOf(3, 5, 8)
		remote.uids = listOf(3, 8, 12)

		service.syncFolder(folder.id)

		openedWith shouldBe KnownFolderState(7, 100)
		verify { store.reconcile(folder.id, remote.qresyncChanges!!.changed, setOf(5L)) }
		remote.calls shouldContainExactly listOf("uidsFrom 12", "headers [12]")
	}

	@Test
	fun `a folder without cached messages only looks for new ones`() {
		cachedState(uidNext = 4)
		every { store.locatedUids(folder.id) } returns emptyList()
		remote.status = remote.status.copy(uidNext = 4)

		service.syncFolder(folder.id)

		remote.calls.shouldBeEmpty()
		verify { store.reconcile(folder.id, emptyList(), emptySet()) }
	}

	@Test
	fun `new UIDs already cached are not fetched again`() {
		cachedState(uidNext = 10)
		every { store.locatedUids(folder.id) } returns listOf(10)
		remote.flags = listOf(RemoteFlagState(10, RemoteFlags()))
		remote.uids = listOf(10, 11)

		service.syncFolder(folder.id)

		upserted() shouldContainExactly listOf(11)
	}

	@Test
	fun `a folder that cannot hold messages is not opened`() {
		folder.selectable = false

		service.syncFolder(folder.id) shouldBe FolderSyncResult(folder.id, full = false, 0, 0, 0)

		opened shouldBe 0
	}

	@Test
	fun `loadOlder fetches the newest messages below the oldest cached UID`() {
		cachedState(uidNext = 20)
		every { store.locatedUids(folder.id) } returns listOf(10, 15)
		remote.status = remote.status.copy(uidNext = 20)
		remote.uids = (1L..19L).toList()

		service.loadOlder(folder.id, limit = 4) shouldBe OlderMessagesResult(loaded = 4, hasMore = true)

		upserted() shouldContainExactly listOf(6, 7, 8, 9)
		remote.calls shouldContainExactly listOf("uidsBelow 10", "headers [6, 7, 8, 9]")
	}

	@Test
	fun `loadOlder in an empty cache starts below UIDNEXT`() {
		cachedState(uidNext = 4)
		every { store.locatedUids(folder.id) } returns emptyList()
		remote.status = remote.status.copy(uidNext = 4)
		remote.uids = listOf(1, 2, 3)

		service.loadOlder(folder.id) shouldBe OlderMessagesResult(loaded = 3, hasMore = false)
		remote.calls.first() shouldBe "uidsBelow 4"
	}

	@Test
	fun `loadOlder of a never synced folder syncs it first`() {
		every { store.locatedUids(folder.id) } returns emptyList()
		remote.uids = emptyList()

		service.loadOlder(folder.id) shouldBe OlderMessagesResult(loaded = 0, hasMore = false)

		remote.calls.first().startsWith("uidsSince") shouldBe true
		verify { store.finish(folder.id, remote.status, now) }
		verify(exactly = 0) { changes.publish(any<String>(), any(), any()) }
	}

	@Test
	fun `syncAccount skips folders that are gone from the server and folders that hold no messages`() {
		val gone = MailFolder(account.id, "Gone", "Gone")
		val group = MailFolder(account.id, "Group", "Group").apply { selectable = false }
		every { folders.findByAccountIdOrderByPathAsc(account.id) } returns listOf(gone, group, folder)
		every { folders.findById(gone.id) } returns Optional.of(gone)
		val failing =
			object : RemoteMailbox {
				override fun <T> withFolder(
					account: MailAccount,
					path: String,
					known: KnownFolderState?,
					block: (RemoteFolder) -> T,
				): T = if (path == "Gone") throw RemoteFolderNotFoundException(path) else block(remote)
			}
		val results = MailHeaderSyncService(accounts, folders, failing, store, changes, Clock.fixed(now, ZoneOffset.UTC)).syncAccount(account.id)

		results.map { it.folderId } shouldContainExactly listOf(folder.id)
	}
}
