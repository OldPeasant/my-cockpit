package ch.sonensei.cockpit.mail.sync

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailSecurity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

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
	private val announcer = mockk<NewMailAnnouncer>(relaxed = true)
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
	private val service = MailHeaderSyncService(accounts, folders, mailbox, store, changes, announcer, Clock.fixed(now, ZoneOffset.UTC))

	/** A server folder whose answers the test sets; it records which reads the sync made. */
	private class FakeRemoteFolder : RemoteFolder {
		override var status = RemoteFolderStatus(7, 12, null, condstore = false, qresync = false)
		override var qresyncChanges: QresyncChanges? = null
		var uids: List<Long> = emptyList()
		var flags: List<RemoteFlagState> = emptyList()
		var changedSince: List<RemoteFlagState> = emptyList()
		var received: Map<Long, Instant> = emptyMap()
		var messageIdMatches: List<Long> = emptyList()

		/**
		 * When set, [uidsSince] answers like an IMAP server (`SEARCH SINCE` compares the INTERNALDATE by day, here
		 * in UTC): only UIDs in [received] arrived on or after the day of `since`. Otherwise it answers all [uids].
		 */
		var datedSearch = false
		val calls = mutableListOf<String>()

		override fun uidsSince(since: Instant): List<Long> {
			calls += "uidsSince $since"
			if (!datedSearch) return uids
			val day = since.atOffset(ZoneOffset.UTC).toLocalDate()
			return uids.filter { uid -> received[uid]?.let { !it.atOffset(ZoneOffset.UTC).toLocalDate().isBefore(day) } ?: false }
		}

		override fun uidsFrom(fromUid: Long): List<Long> {
			calls += "uidsFrom $fromUid"
			return uids.filter { it >= fromUid }
		}

		override fun receivedDates(uids: List<Long>): Map<Long, Instant> {
			calls += "receivedDates $uids"
			return uids.mapNotNull { uid -> received[uid]?.let { uid to it } }.toMap()
		}

		override fun uidsWithMessageIds(messageIds: Collection<String>): List<Long> {
			calls += "uidsWithMessageIds ${messageIds.sorted()}"
			return messageIdMatches
		}

		var searchMatches: List<Long> = emptyList()

		override fun search(words: List<String>): List<Long> {
			calls += "search $words"
			return searchMatches
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
	fun `with CONDSTORE but no cached HIGHESTMODSEQ the flags of every cached UID are fetched`() {
		cachedState(uidNext = 12, highestModSeq = null)
		remote.status = RemoteFolderStatus(7, 12, 120, condstore = true, qresync = false)
		every { store.locatedUids(folder.id) } returns listOf(3, 5)
		remote.flags = listOf(RemoteFlagState(3, RemoteFlags(seen = true), 110))

		service.syncFolder(folder.id)

		remote.calls shouldContainExactly listOf("flagsFrom 3")
		verify { store.reconcile(folder.id, remote.flags, setOf(5L)) }
		// Nothing to resynchronize from: the folder is opened without a QRESYNC state.
		openedWith shouldBe null
	}

	@Test
	fun `with CONDSTORE on a folder without MODSEQ support (NOMODSEQ) the flags of every cached UID are fetched`() {
		cachedState(uidNext = 12, highestModSeq = 100)
		remote.status = RemoteFolderStatus(7, 12, null, condstore = true, qresync = false)
		every { store.locatedUids(folder.id) } returns listOf(3, 5)
		remote.flags = listOf(RemoteFlagState(3, RemoteFlags()), RemoteFlagState(5, RemoteFlags(flagged = true)))

		service.syncFolder(folder.id)

		remote.calls shouldContainExactly listOf("flagsFrom 3")
		verify { store.reconcile(folder.id, remote.flags, emptySet()) }
		verify { store.finish(folder.id, remote.status, now) }
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

	/** Store answers that create a row (id derived from the UID) for every fetched message. */
	private fun storeCreatesRows() {
		every { store.upsert(any(), any(), any()) } answers {
			val batch = thirdArg<List<RemoteMessage>>()
			StoreCounts(created = batch.size, createdIds = batch.map { rowId(it.uid) })
		}
	}

	private fun rowId(uid: Long) = UUID(0x0190_0000_0000_7000L, uid)

	@Test
	fun `an incremental sync reports the rows created for new arrivals and hands them to the announcer`() {
		cachedState(uidNext = 10)
		every { store.locatedUids(folder.id) } returns listOf(3)
		remote.flags = listOf(RemoteFlagState(3, RemoteFlags()))
		remote.uids = listOf(3, 10, 11)
		storeCreatesRows()

		val result = service.syncFolder(folder.id)

		result.arrived shouldContainExactly listOf(rowId(10), rowId(11))
		result.created shouldBe 2
		verify(exactly = 1) { announcer.announce(account, folder, listOf(rowId(10), rowId(11))) }
	}

	@Test
	fun `an incremental sync whose new UIDs only reattached known rows announces nothing`() {
		cachedState(uidNext = 10)
		every { store.locatedUids(folder.id) } returns emptyList()
		remote.uids = listOf(10)
		// A moved-in message whose row already existed: updated, not created.
		every { store.upsert(any(), any(), any()) } returns StoreCounts(updated = 1)

		val result = service.syncFolder(folder.id)

		result.arrived.shouldBeEmpty()
		result.updated shouldBe 1
		verify(exactly = 0) { announcer.announce(any(), any(), any()) }
	}

	@Test
	fun `an incremental sync without new UIDs announces nothing`() {
		cachedState(uidNext = 12)
		every { store.locatedUids(folder.id) } returns listOf(3)
		remote.flags = listOf(RemoteFlagState(3, RemoteFlags(seen = true)))

		service.syncFolder(folder.id).arrived.shouldBeEmpty()

		verify(exactly = 0) { announcer.announce(any(), any(), any()) }
	}

	@Test
	fun `a first or full sync never announces its rows as new mail`() {
		remote.uids = listOf(1, 2, 3)
		storeCreatesRows()

		val first = service.syncFolder(folder.id)

		first.full shouldBe true
		first.created shouldBe 3
		first.arrived.shouldBeEmpty()

		// A changed UIDVALIDITY resyncs in full, too.
		cachedState()
		remote.status = remote.status.copy(uidValidity = 8)
		service.syncFolder(folder.id).arrived.shouldBeEmpty()

		verify(exactly = 0) { announcer.announce(any(), any(), any()) }
	}

	@Test
	fun `a folder that cannot hold messages is not opened`() {
		folder.selectable = false

		service.syncFolder(folder.id) shouldBe FolderSyncResult(folder.id, full = false, 0, 0, 0)

		opened shouldBe 0
	}

	@Test
	fun `loadOlder fetches the most recently arrived messages missing from the cache, whatever their UID`() {
		cachedState(uidNext = 20)
		every { store.locatedUids(folder.id) } returns listOf(10, 15)
		remote.status = remote.status.copy(uidNext = 20)
		remote.uids = (1L..19L).toList()
		// UID order is not arrival order: 18 and 19 were moved in late but arrived long ago, 4..7 arrived most recently.
		remote.received = remote.uids.associateWith { now.minus(Duration.ofDays(400 + 19 - it)) } +
			listOf(4L, 5L, 6L, 7L).associateWith { now.minus(Duration.ofDays(300 + it)) }

		service.loadOlder(folder.id, limit = 4) shouldBe OlderMessagesResult(loaded = 4, hasMore = true)

		upserted() shouldContainExactly listOf(4, 5, 6, 7)
		// The fake's SINCE search answers every UID here, so all missing UIDs are candidates (narrowing: tests below).
		remote.calls shouldContainExactly
			listOf(
				"uidsFrom 1",
				"uidsSince 2026-10-13T10:00:00Z",
				"receivedDates ${(1L..19L).filter { it != 10L && it != 15L }}",
				"headers [4, 5, 6, 7]",
			)
	}

	@Test
	fun `loadOlder keeps the candidates narrow when one far older message is cached`() {
		cachedState(uidNext = 61)
		every { store.locatedUids(folder.id) } returns listOf(50, 60)
		remote.status = remote.status.copy(uidNext = 61)
		remote.datedSearch = true
		remote.uids = (1L..30L).toList() + listOf(50L, 60L)
		// 60 was moved in and arrived three years ago; 1..20 are uncached mail of the years since, 21..30 arrived
		// 10..1 days ago. The cached outlier must not widen the window to the 1..20.
		remote.received =
			(1L..20L).associateWith { now.minus(Duration.ofDays(1000L - it)) } +
			(21L..30L).associateWith { now.minus(Duration.ofDays(31 - it)) } +
			mapOf(50L to now, 60L to now.minus(Duration.ofDays(3 * 365)))

		service.loadOlder(folder.id, limit = 3) shouldBe OlderMessagesResult(loaded = 3, hasMore = true)

		upserted() shouldContainExactly listOf(28, 29, 30)
		remote.calls shouldContainExactly
			listOf(
				"uidsFrom 1",
				"uidsSince 2026-10-13T10:00:00Z",
				"receivedDates [24, 25, 26, 27, 28, 29, 30]",
				"headers [28, 29, 30]",
			)
	}

	@Test
	fun `loadOlder doubles the window until it holds at least the limit of missing messages`() {
		cachedState(uidNext = 100)
		every { store.locatedUids(folder.id) } returns listOf(99)
		remote.status = remote.status.copy(uidNext = 100)
		remote.datedSearch = true
		remote.uids = (1L..10L).toList() + 99L
		// UID u arrived 10 * u days ago.
		remote.received = (1L..10L).associateWith { now.minus(Duration.ofDays(10 * it)) } + (99L to now)

		service.loadOlder(folder.id, limit = 3) shouldBe OlderMessagesResult(loaded = 3, hasMore = true)

		upserted() shouldContainExactly listOf(1, 2, 3)
		// 7 days: none, 14: {1}, 28: {1, 2}, 56: {1..5} >= 3.
		remote.calls.filter { it.startsWith("uidsSince") } shouldContainExactly
			listOf(7L, 14L, 28L, 56L).map { "uidsSince ${now.minus(Duration.ofDays(it))}" }
		remote.calls.single { it.startsWith("receivedDates") } shouldBe "receivedDates [1, 2, 3, 4, 5]"
	}

	@Test
	fun `loadOlder takes every missing message as candidate once the window reaches the epoch`() {
		cachedState(uidNext = 4)
		every { store.locatedUids(folder.id) } returns emptyList()
		remote.status = remote.status.copy(uidNext = 4)
		remote.datedSearch = true
		remote.uids = listOf(1, 2, 3)
		// Only one message arrived after 1970: no window before the epoch holds the limit of two.
		remote.received =
			mapOf(1L to now.minus(Duration.ofDays(1)), 2L to Instant.parse("1969-07-20T20:17:00Z"), 3L to Instant.parse("1968-01-01T00:00:00Z"))

		service.loadOlder(folder.id, limit = 2) shouldBe OlderMessagesResult(loaded = 2, hasMore = true)

		upserted() shouldContainExactly listOf(1, 2)
		val spans = generateSequence(Duration.ofDays(7)) { it.multipliedBy(2) }.takeWhile { now.minus(it).isAfter(Instant.EPOCH) }.toList()
		spans.size shouldBe 12
		remote.calls.filter { it.startsWith("uidsSince") } shouldContainExactly spans.map { "uidsSince ${now.minus(it)}" }
		remote.calls.single { it.startsWith("receivedDates") } shouldBe "receivedDates [1, 2, 3]"
	}

	@Test
	fun `loadOlder of a folder without cached messages starts the window a week before now`() {
		cachedState(uidNext = 7)
		every { store.locatedUids(folder.id) } returns emptyList()
		remote.status = remote.status.copy(uidNext = 7)
		remote.datedSearch = true
		remote.uids = (1L..6L).toList()
		// 1..5 arrived within the last week, 6 a month ago: its date is never read.
		remote.received = (1L..5L).associateWith { now.minus(Duration.ofDays(it)) } + (6L to now.minus(Duration.ofDays(30)))

		service.loadOlder(folder.id, limit = 2) shouldBe OlderMessagesResult(loaded = 2, hasMore = true)

		upserted() shouldContainExactly listOf(1, 2)
		remote.calls shouldContainExactly
			listOf("uidsFrom 1", "uidsSince 2026-10-13T10:00:00Z", "receivedDates [1, 2, 3, 4, 5]", "headers [1, 2]")
	}

	@Test
	fun `loadOlder still picks missing messages that arrived after the oldest cached one first`() {
		cachedState(uidNext = 31)
		val oldestCached = Instant.parse("2026-01-10T09:00:00Z")
		every { store.locatedUids(folder.id) } returns listOf(20, 30)
		remote.status = remote.status.copy(uidNext = 31)
		remote.datedSearch = true
		remote.uids = (10L..14L).toList() + listOf(20L, 25L, 30L)
		// 25 is a gap between the cached messages; 10..14 arrived 1..5 days before the oldest cached one.
		remote.received =
			(10L..14L).associateWith { oldestCached.minus(Duration.ofDays(it - 9)) } +
			mapOf(20L to oldestCached, 25L to Instant.parse("2026-06-01T09:00:00Z"), 30L to Instant.parse("2026-10-01T09:00:00Z"))

		service.loadOlder(folder.id, limit = 2) shouldBe OlderMessagesResult(loaded = 2, hasMore = true)

		upserted() shouldContainExactly listOf(10, 25)
		// 7..112 days: nothing missing, 224: {25}, 448 (2025-07-29): {10..14, 25} >= 2.
		remote.calls.filter { it.startsWith("uidsSince") } shouldContainExactly
			listOf(7L, 14L, 28L, 56L, 112L, 224L, 448L).map { "uidsSince ${now.minus(Duration.ofDays(it))}" }
		remote.calls.single { it.startsWith("receivedDates") } shouldBe "receivedDates [10, 11, 12, 13, 14, 25]"
	}

	@Test
	fun `paging with loadOlder loads every missing message once, newest arrival first, with narrowed windows`() {
		cachedState(uidNext = 9)
		remote.status = remote.status.copy(uidNext = 9)
		remote.datedSearch = true
		remote.uids = (1L..8L).toList()
		// UID order is not arrival order; 7 has no INTERNALDATE, 8 is cached.
		val daysAgo = mapOf(1L to 3L, 2L to 40L, 3L to 10L, 4L to 200L, 5L to 1L, 6L to 90L, 8L to 0L)
		remote.received = daysAgo.mapValues { (_, days) -> now.minus(Duration.ofDays(days)) }
		every { store.locatedUids(folder.id) } returnsMany listOf(listOf(8), listOf(1, 3, 5, 8), listOf(1, 2, 3, 4, 5, 6, 8))

		service.loadOlder(folder.id, limit = 3) shouldBe OlderMessagesResult(loaded = 3, hasMore = true)
		// 7 days: {1, 5}, 14: {1, 3, 5}.
		remote.calls.drop(1) shouldContainExactly
			listOf(
				"uidsSince ${now.minus(Duration.ofDays(7))}",
				"uidsSince ${now.minus(Duration.ofDays(14))}",
				"receivedDates [1, 3, 5]",
				"headers [1, 3, 5]",
			)

		remote.calls.clear()
		service.loadOlder(folder.id, limit = 3) shouldBe OlderMessagesResult(loaded = 3, hasMore = true)
		// 7..112 days: at most {2, 6}, 224: {2, 4, 6}; the undated 7 is never in a SINCE window.
		remote.calls.filter { it.startsWith("uidsSince") } shouldContainExactly
			listOf(7L, 14L, 28L, 56L, 112L, 224L).map { "uidsSince ${now.minus(Duration.ofDays(it))}" }
		remote.calls.filter { it.startsWith("receivedDates") || it.startsWith("headers") } shouldContainExactly
			listOf("receivedDates [2, 4, 6]", "headers [2, 4, 6]")

		remote.calls.clear()
		service.loadOlder(folder.id, limit = 3) shouldBe OlderMessagesResult(loaded = 1, hasMore = false)
		// Only the undated message is left: it fits the limit, so no window and no dates.
		remote.calls shouldContainExactly listOf("uidsFrom 1", "headers [7]")

		upserted() shouldContainExactly listOf(1, 3, 5, 2, 4, 6, 7)
	}

	@Test
	fun `loadOlder ranks messages without a date last and ties by UID`() {
		cachedState(uidNext = 5)
		every { store.locatedUids(folder.id) } returns emptyList()
		remote.status = remote.status.copy(uidNext = 5)
		remote.uids = listOf(1, 2, 3, 4)
		val day = now.minus(Duration.ofDays(500))
		remote.received = mapOf(1L to day, 2L to day, 3L to now.minus(Duration.ofDays(600)))

		service.loadOlder(folder.id, limit = 1) shouldBe OlderMessagesResult(loaded = 1, hasMore = true)
		upserted() shouldContainExactly listOf(2)

		every { store.locatedUids(folder.id) } returns listOf(2)
		service.loadOlder(folder.id, limit = 2) shouldBe OlderMessagesResult(loaded = 2, hasMore = true)
		upserted() shouldContainExactly listOf(2, 1, 3)
	}

	@Test
	fun `loadOlder takes every missing message without reading dates when they fit into the limit`() {
		cachedState(uidNext = 4)
		every { store.locatedUids(folder.id) } returns emptyList()
		remote.status = remote.status.copy(uidNext = 4)
		remote.uids = listOf(1, 2, 3)

		service.loadOlder(folder.id) shouldBe OlderMessagesResult(loaded = 3, hasMore = false)
		remote.calls shouldContainExactly listOf("uidsFrom 1", "headers [1, 2, 3]")
		verify { changes.publish("MAIL_FOLDER", folder.id, ChangeOp.UPDATED) }
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
	fun `loadOlder announces the change when only its implicit full sync changed the cache`() {
		cachedState()
		remote.status = remote.status.copy(uidValidity = 8)
		remote.uids = listOf(1, 2)
		every { store.locatedUids(folder.id) } returns listOf(1L, 2L)

		service.loadOlder(folder.id) shouldBe OlderMessagesResult(loaded = 0, hasMore = false)

		verify(exactly = 1) { changes.publish("MAIL_FOLDER", folder.id, ChangeOp.UPDATED) }
	}

	@Test
	fun `loadOlder rejects a limit that is not positive without opening the folder`() {
		cachedState()

		shouldThrow<IllegalArgumentException> { service.loadOlder(folder.id, limit = 0) }

		opened shouldBe 0
	}

	@Test
	fun `syncing or loading older mail of an unknown folder is not found and contacts no server`() {
		val unknown = UUID.fromString("01900000-0000-7000-8000-00000000abcd")
		every { folders.findById(unknown) } returns Optional.empty()

		shouldThrow<NotFoundException> { service.syncFolder(unknown) }
		shouldThrow<NotFoundException> { service.loadOlder(unknown) }

		opened shouldBe 0
		verify(exactly = 0) { changes.publish(any<String>(), any(), any()) }
	}

	@Test
	fun `syncing a folder whose account is gone is not found and contacts no server`() {
		every { accounts.findById(account.id) } returns Optional.empty()

		shouldThrow<NotFoundException> { service.syncFolder(folder.id) }

		opened shouldBe 0
		verify(exactly = 0) { store.finish(any(), any(), any()) }
	}

	@Test
	fun `loadOlder of a folder that cannot hold messages loads nothing and opens nothing`() {
		folder.selectable = false

		service.loadOlder(folder.id) shouldBe OlderMessagesResult(loaded = 0, hasMore = false)

		opened shouldBe 0
		verify(exactly = 0) { changes.publish(any<String>(), any(), any()) }
	}

	@Test
	fun `a resync looks up linked rows the window missed by Message-ID before pruning`() {
		cachedState()
		remote.status = remote.status.copy(uidValidity = 8)
		remote.uids = listOf(5)
		every { store.linkedDetachedMessageIds(folder.id) } returns listOf("<2@example.test>", "<3@example.test>", "<9@example.test>")
		every { store.locatedUids(folder.id) } returns listOf(5)
		// The server matches substrings: 23 has another Message-ID (`<23@example.test>`) and must not be taken.
		remote.messageIdMatches = listOf(2, 3, 5, 23)
		every { store.pruneDetached(folder.id) } returns 1

		val result = service.syncFolder(folder.id)

		remote.calls shouldContainExactly
			listOf(
				"uidsSince 2026-04-20T10:00:00Z",
				"headers [5]",
				"uidsWithMessageIds [<2@example.test>, <3@example.test>, <9@example.test>]",
				"headers [2, 3, 23]",
			)
		upserted() shouldContainExactly listOf(5, 2, 3)
		verifyOrder {
			store.upsert(account.id, folder.id, match { batch -> batch.map { it.uid } == listOf(2L, 3L) })
			store.pruneDetached(folder.id)
		}
		result shouldBe FolderSyncResult(folder.id, full = true, created = 3, updated = 0, removed = 1)
	}

	@Test
	fun `a resync without linked detached rows does not search by Message-ID`() {
		cachedState()
		remote.status = remote.status.copy(uidValidity = 8)
		every { store.linkedDetachedMessageIds(folder.id) } returns emptyList()

		service.syncFolder(folder.id)

		remote.calls.none { it.startsWith("uidsWithMessageIds") } shouldBe true
	}

	@Test
	fun `searchServer caches the most recently arrived matches up to the limit and reports more`() {
		cachedState(uidNext = 12)
		remote.searchMatches = listOf(2, 5, 9, 11)
		// UID order is not arrival order; 11 has no date and ranks last.
		remote.received = mapOf(2L to now.minus(Duration.ofDays(1)), 5L to now.minus(Duration.ofDays(10)), 9L to now.minus(Duration.ofDays(5)))
		every { store.locatedUids(folder.id) } returns listOf(9)
		every { store.locatedIds(folder.id, listOf(2L, 9L)) } returns listOf(rowId(2), rowId(9))

		val result = service.searchServer(folder.id, listOf("budget", "draft"), limit = 2)

		result shouldBe ServerSearchResult(listOf(rowId(2), rowId(9)), loaded = 1, hasMore = true)
		remote.calls shouldContainExactly listOf("search [budget, draft]", "receivedDates [2, 5, 9, 11]", "headers [2]")
		upserted() shouldContainExactly listOf(2)
		verify(exactly = 1) { changes.publish("MAIL_FOLDER", folder.id, ChangeOp.UPDATED) }
		verify(exactly = 0) { store.detachFolder(any()) }
	}

	@Test
	fun `searchServer reads no dates when the matches fit and announces nothing when all were cached`() {
		cachedState(uidNext = 12)
		remote.searchMatches = listOf(3, 4)
		every { store.locatedUids(folder.id) } returns listOf(3, 4)
		every { store.locatedIds(folder.id, listOf(3L, 4L)) } returns listOf(rowId(3), rowId(4))

		val result = service.searchServer(folder.id, listOf("invoice"), limit = 2)

		result shouldBe ServerSearchResult(listOf(rowId(3), rowId(4)), loaded = 0, hasMore = false)
		remote.calls shouldContainExactly listOf("search [invoice]")
		verify(exactly = 0) { store.upsert(any(), any(), any()) }
		verify(exactly = 0) { changes.publish(any<String>(), any(), any()) }
	}

	@Test
	fun `searchServer without matches loads nothing`() {
		cachedState(uidNext = 12)
		every { store.locatedUids(folder.id) } returns listOf(3)
		every { store.locatedIds(folder.id, emptyList()) } returns emptyList()

		service.searchServer(folder.id, listOf("nothing"), limit = 5) shouldBe ServerSearchResult(emptyList(), 0, false)

		remote.calls shouldContainExactly listOf("search [nothing]")
	}

	@Test
	fun `searchServer resyncs a folder whose UIDVALIDITY changed before searching, and announces the change`() {
		cachedState()
		remote.status = remote.status.copy(uidValidity = 8)
		remote.uids = listOf(1)
		remote.searchMatches = listOf(1)
		every { store.locatedUids(folder.id) } returns listOf(1)
		every { store.locatedIds(folder.id, listOf(1L)) } returns listOf(rowId(1))

		val result = service.searchServer(folder.id, listOf("contract"), limit = 10)

		result shouldBe ServerSearchResult(listOf(rowId(1)), loaded = 0, hasMore = false)
		verify { store.detachFolder(folder.id) }
		remote.calls.first().startsWith("uidsSince") shouldBe true
		// The search runs after the resync, so the UIDs it finds match the cache.
		remote.calls.last() shouldBe "search [contract]"
		verify(exactly = 1) { changes.publish("MAIL_FOLDER", folder.id, ChangeOp.UPDATED) }
	}

	@Test
	fun `searchServer of a never synced folder syncs it first`() {
		remote.uids = emptyList()
		remote.searchMatches = emptyList()
		every { store.locatedUids(folder.id) } returns emptyList()
		every { store.locatedIds(folder.id, emptyList()) } returns emptyList()

		service.searchServer(folder.id, listOf("contract"), limit = 10) shouldBe ServerSearchResult(emptyList(), 0, false)

		remote.calls shouldContainExactly listOf("uidsSince 2026-04-20T10:00:00Z", "search [contract]")
		verify { store.finish(folder.id, remote.status, now) }
	}

	@Test
	fun `searchServer of a folder that cannot hold messages or without words opens nothing`() {
		cachedState()

		service.searchServer(folder.id, emptyList(), limit = 10) shouldBe ServerSearchResult(emptyList(), 0, false)
		folder.selectable = false
		service.searchServer(folder.id, listOf("contract"), limit = 10) shouldBe ServerSearchResult(emptyList(), 0, false)

		opened shouldBe 0
		verify(exactly = 0) { changes.publish(any<String>(), any(), any()) }
	}

	@Test
	fun `searchServer rejects a limit that is not positive and an unknown folder without contacting the server`() {
		cachedState()
		val unknown = UUID.fromString("01900000-0000-7000-8000-00000000abce")
		every { folders.findById(unknown) } returns Optional.empty()

		shouldThrow<IllegalArgumentException> { service.searchServer(folder.id, listOf("contract"), limit = 0) }
		shouldThrow<NotFoundException> { service.searchServer(unknown, listOf("contract"), limit = 10) }

		opened shouldBe 0
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
		val results =
			MailHeaderSyncService(
				accounts,
				folders,
				failing,
				store,
				changes,
				announcer,
				Clock.fixed(now, ZoneOffset.UTC),
			).syncAccount(account.id)

		results.map { it.folderId } shouldContainExactly listOf(folder.id)
	}
}
