package ch.sonensei.cockpit.mail.sync

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.imap.MailFolderService
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** How a folder sync went. */
data class FolderSyncResult(
	val folderId: UUID,
	/** The folder was synced from scratch: first sync, or the server changed its UIDVALIDITY. */
	val full: Boolean,
	val created: Int,
	val updated: Int,
	val removed: Int,
)

/** What [MailHeaderSyncService.loadOlder] added. */
data class OlderMessagesResult(
	val loaded: Int,
	/** The server has even older messages in the folder. */
	val hasMore: Boolean,
)

/**
 * Keeps the header cache of a folder in sync with the server (SPEC.md §7.2).
 *
 * - **Full sync** (first sync, or UIDVALIDITY changed): the headers of the messages that arrived within the
 *   account's sync window ([MailAccount.syncWindowMonths], default 12) are fetched. After a UIDVALIDITY change every
 *   cached UID is invalid: rows are detached first, found again by Message-ID, and the rest removed.
 * - **Incremental sync**: messages from the cached UIDNEXT on are new; flags and expunges are reconciled with QRESYNC
 *   (changes and VANISHED reported on open) when the server supports it, else with CONDSTORE (`CHANGEDSINCE` the cached
 *   HIGHESTMODSEQ, plus a UID search for expunges), else by fetching the flags of every cached UID.
 *   Linked rows the window pass did not find are then looked up by Message-ID before the rest is removed.
 * - **Older mail on demand** ([loadOlder]): the most recently arrived messages missing from the cache.
 *
 * Syncs of the same folder run one after another. A folder whose content changed publishes a `MAIL_FOLDER` change
 * event.
 */
@Service
class MailHeaderSyncService(
	private val accounts: MailAccountRepository,
	private val folders: MailFolderRepository,
	private val mailbox: RemoteMailbox,
	private val store: MailHeaderStore,
	private val changes: ChangeEventPublisher,
	private val clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val locks = ConcurrentHashMap<UUID, ReentrantLock>()

	/**
	 * Syncs every selectable folder of an account (the folder list as last synced by [MailFolderService]). A folder
	 * that disappeared from the server is skipped; the next folder list sync removes it.
	 *
	 * @throws ch.sonensei.cockpit.mail.imap.MailConnectionException when the server cannot be reached
	 */
	fun syncAccount(accountId: UUID): List<FolderSyncResult> {
		account(accountId)
		return folders.findByAccountIdOrderByPathAsc(accountId).filter { it.selectable }.mapNotNull { folder ->
			try {
				syncFolder(folder.id)
			} catch (e: RemoteFolderNotFoundException) {
				log.info("Mail folder {} of account {} is gone from the server, skipped", folder.id, accountId)
				log.debug("Missing folder", e)
				null
			}
		}
	}

	/**
	 * Brings the cached headers of a folder up to date.
	 *
	 * @throws NotFoundException when the folder is not cached (or the account is gone)
	 * @throws RemoteFolderNotFoundException when the folder no longer exists on the server
	 * @throws ch.sonensei.cockpit.mail.imap.MailConnectionException when the server cannot be reached
	 */
	fun syncFolder(folderId: UUID): FolderSyncResult =
		lock(folderId)
			.withLock {
				val folder = folder(folderId)
				val account = account(folder.accountId)
				// `\Noselect` folders only group others and cannot be opened.
				if (!folder.selectable) return@withLock FolderSyncResult(folder.id, full = false, 0, 0, 0)
				val known = folder.uidValidity?.let { validity -> folder.highestModSeq?.let { KnownFolderState(validity, it) } }
				mailbox.withFolder(account, folder.path, known) { remote ->
					if (folder.uidValidity != remote.status.uidValidity) full(account, folder, remote) else incremental(account, folder, remote)
				}
			}.also(::announce)

	/**
	 * Loads up to [limit] of the messages missing from the cache into it, the most recently arrived (INTERNALDATE)
	 * first, e.g. when the list is scrolled past the sync window. Messages are picked by what is missing, not by UID,
	 * because UID order is not arrival order (a message moved into an archive keeps its old date but gets a new UID).
	 * A folder that was never synced (or whose UIDVALIDITY changed) is synced first. A folder that cannot hold
	 * messages (`\Noselect`) has nothing to load.
	 */
	fun loadOlder(
		folderId: UUID,
		limit: Int = DEFAULT_OLDER_LIMIT,
	): OlderMessagesResult {
		require(limit > 0) { "limit must be positive" }
		var changed = false
		val result =
			lock(folderId).withLock {
				val folder = folder(folderId)
				val account = account(folder.accountId)
				if (!folder.selectable) return@withLock OlderMessagesResult(0, false)
				mailbox.withFolder(account, folder.path, null) { remote ->
					if (folder.uidValidity != remote.status.uidValidity) {
						changed = full(account, folder, remote).changedAnything
					}
					val cached = store.locatedUids(folder.id).toHashSet()
					val missing = remote.uidsFrom(1).filter { it !in cached }
					val batch =
						if (missing.size <= limit) {
							missing
						} else {
							val dates = remote.receivedDates(missing)
							missing
								.sortedWith(compareByDescending<Long> { dates[it] ?: Instant.EPOCH }.thenByDescending { it })
								.take(limit)
								.sorted()
						}
					val counts =
						batch.chunked(BATCH_SIZE).fold(StoreCounts()) { acc, uids ->
							acc + store.upsert(account.id, folder.id, remote.headers(uids))
						}
					OlderMessagesResult(counts.created + counts.updated, missing.size > batch.size)
				}
			}
		if (changed || result.loaded > 0) changes.publish(FOLDER_EVENT, folderId, ChangeOp.UPDATED)
		return result
	}

	private fun full(
		account: MailAccount,
		folder: MailFolder,
		remote: RemoteFolder,
	): FolderSyncResult {
		val resync = folder.uidValidity != null
		if (resync) {
			log.info("UIDVALIDITY of mail folder {} changed, resyncing it", folder.id)
			store.detachFolder(folder.id)
		}
		val since =
			clock
				.instant()
				.atZone(ZoneOffset.UTC)
				.minusMonths(account.syncWindowMonths.toLong())
				.toInstant()
		var counts =
			remote.uidsSince(since).chunked(BATCH_SIZE).fold(StoreCounts()) { acc, uids ->
				acc + store.upsert(account.id, folder.id, remote.headers(uids))
			}
		if (resync) {
			counts += retakeLinked(account, folder, remote)
			counts += StoreCounts(removed = store.pruneDetached(folder.id))
		}
		store.finish(folder.id, remote.status, clock.instant())
		return FolderSyncResult(folder.id, full = true, counts.created, counts.updated, counts.removed)
	}

	/**
	 * After the window pass of a resync: linked rows still detached may belong to messages older than the window that
	 * are still in the folder. They are looked up by Message-ID so the links keep pointing at a located message.
	 */
	private fun retakeLinked(
		account: MailAccount,
		folder: MailFolder,
		remote: RemoteFolder,
	): StoreCounts {
		val wanted = store.linkedDetachedMessageIds(folder.id).toSet()
		if (wanted.isEmpty()) return StoreCounts()
		val located = store.locatedUids(folder.id).toHashSet()
		return remote.uidsWithMessageIds(wanted).filter { it !in located }.chunked(BATCH_SIZE).fold(StoreCounts()) { acc, uids ->
			// The server matches substrings: only the exact Message-IDs are taken.
			acc + store.upsert(account.id, folder.id, remote.headers(uids).filter { it.messageId in wanted })
		}
	}

	private fun incremental(
		account: MailAccount,
		folder: MailFolder,
		remote: RemoteFolder,
	): FolderSyncResult {
		val status = remote.status
		val cached = store.locatedUids(folder.id)
		val lowest = cached.firstOrNull()
		val cachedModSeq = folder.highestModSeq
		val qresync = remote.qresyncChanges
		val (flagChanges, vanished) =
			when {
				lowest == null -> emptyList<RemoteFlagState>() to emptySet()
				qresync != null -> qresync.changed to qresync.vanished
				status.condstore && cachedModSeq != null && status.highestModSeq != null -> {
					val changed = if (status.highestModSeq == cachedModSeq) emptyList() else remote.flagsChangedSince(lowest, cachedModSeq)
					changed to (cached.toSet() - remote.uidsFrom(lowest).toSet())
				}
				else -> {
					val states = remote.flagsFrom(lowest)
					states to (cached.toSet() - states.map { it.uid }.toSet())
				}
			}
		var counts = store.reconcile(folder.id, flagChanges, vanished)
		val previousNext = folder.uidNext ?: 1
		if (status.uidNext > previousNext) {
			val known = cached.toSet()
			counts +=
				remote.uidsFrom(previousNext).filter { it !in known }.chunked(BATCH_SIZE).fold(StoreCounts()) { acc, uids ->
					acc + store.upsert(account.id, folder.id, remote.headers(uids))
				}
		}
		store.finish(folder.id, status, clock.instant())
		return FolderSyncResult(folder.id, full = false, counts.created, counts.updated, counts.removed)
	}

	private fun announce(result: FolderSyncResult) {
		if (result.changedAnything) changes.publish(FOLDER_EVENT, result.folderId, ChangeOp.UPDATED)
	}

	private val FolderSyncResult.changedAnything: Boolean get() = created + updated + removed > 0

	private fun lock(folderId: UUID) = locks.computeIfAbsent(folderId) { ReentrantLock() }

	private fun folder(folderId: UUID): MailFolder = folders.findById(folderId).orElse(null) ?: throw NotFoundException("Folder not found")

	private fun account(accountId: UUID): MailAccount =
		accounts.findById(accountId).orElse(null) ?: throw NotFoundException("Mail account not found")

	companion object {
		const val DEFAULT_OLDER_LIMIT = 100
		private const val BATCH_SIZE = 200
		private const val FOLDER_EVENT = MailFolderService.FOLDER_EVENT
	}
}
