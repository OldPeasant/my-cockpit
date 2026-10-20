package ch.sonensei.cockpit.mail.sync

import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** How many cached messages one step of a sync created, changed and removed. */
data class StoreCounts(
	val created: Int = 0,
	val updated: Int = 0,
	val removed: Int = 0,
) {
	operator fun plus(other: StoreCounts) = StoreCounts(created + other.created, updated + other.updated, removed + other.removed)
}

/**
 * Writes what [MailHeaderSyncService] read from the server into `mail_message`, one transaction per call (a batch).
 *
 * A message no longer on the server is deleted, unless an item links to it: then it is only detached
 * ([MailMessage.detach]) so the link survives, and a message with the same Message-ID that shows up later (e.g. after
 * a move to another folder or a UIDVALIDITY change) takes over that row again.
 */
@Component
class MailHeaderStore(
	private val messages: MailSyncRepository,
	private val folders: MailFolderRepository,
	private val links: LinkService,
) {
	@Transactional(readOnly = true)
	fun locatedUids(folderId: UUID): List<Long> = messages.locatedUids(folderId)

	/** Stores new headers and refreshes the flags of messages already cached at these UIDs. */
	@Transactional
	fun upsert(
		accountId: UUID,
		folderId: UUID,
		remote: List<RemoteMessage>,
	): StoreCounts {
		if (remote.isEmpty()) return StoreCounts()
		val cached = messages.findByFolderIdAndUidIn(folderId, remote.map { it.uid }).associateBy { it.uid }
		val index = RepositoryThreadIndex(accountId)
		var created = 0
		var updated = 0
		for (r in remote) {
			val existing = cached[r.uid]
			if (existing != null) {
				if (applyFlags(existing, r.flags, r.modSeq)) updated++
				continue
			}
			val reattached = r.messageId?.let { reattachable(accountId, folderId, it) }
			val message = reattached ?: MailMessage(accountId, folderId, r.uid)
			message.locate(folderId, r.uid)
			applyHeaders(message, r)
			applyFlags(message, r.flags, r.modSeq)
			if (message.threadKey == null) assignThread(message, index)
			// Saved before the next message's thread lookup (queries flush first), so replies in one batch find it.
			messages.save(message)
			if (reattached != null) updated++ else created++
		}
		messages.flush()
		return StoreCounts(created, updated)
	}

	/** Applies flag changes to cached messages ([changes] of UIDs not cached are ignored) and removes [vanished] UIDs. */
	@Transactional
	fun reconcile(
		folderId: UUID,
		changes: List<RemoteFlagState>,
		vanished: Set<Long>,
	): StoreCounts {
		var updated = 0
		changes.chunked(QUERY_CHUNK).forEach { chunk ->
			val byUid = chunk.associateBy { it.uid }
			messages.findByFolderIdAndUidIn(folderId, byUid.keys).forEach { message ->
				val state = byUid.getValue(checkNotNull(message.uid))
				if (applyFlags(message, state.flags, state.modSeq)) updated++
			}
		}
		var removed = 0
		vanished.chunked(QUERY_CHUNK).forEach { chunk ->
			messages.findByFolderIdAndUidIn(folderId, chunk).forEach {
				remove(it)
				removed++
			}
		}
		messages.flush()
		return StoreCounts(updated = updated, removed = removed)
	}

	/** Before a resync after a UIDVALIDITY change: every cached UID of the folder is invalid. */
	@Transactional
	fun detachFolder(folderId: UUID) {
		messages.findByFolderIdAndUidIsNotNull(folderId).forEach(MailMessage::detach)
		messages.flush()
	}

	/** After a resync: rows of the folder that were not found again are removed (or stay detached when linked). */
	@Transactional
	fun pruneDetached(folderId: UUID): Int {
		val stale = messages.findByFolderIdAndUidIsNull(folderId).filter { !isLinked(it) }
		messages.deleteAll(stale)
		messages.flush()
		return stale.size
	}

	/** Records the folder state the cache now reflects. */
	@Transactional
	fun finish(
		folderId: UUID,
		status: RemoteFolderStatus,
		at: Instant,
	) {
		val folder = folders.findById(folderId).orElseThrow { NotFoundException("Folder not found") }
		folder.uidValidity = status.uidValidity
		folder.uidNext = status.uidNext
		folder.highestModSeq = status.highestModSeq
		folder.lastSyncedAt = at
		folders.saveAndFlush(folder)
	}

	private fun reattachable(
		accountId: UUID,
		folderId: UUID,
		messageId: String,
	): MailMessage? {
		val detached = messages.findByAccountIdAndMessageIdHeaderAndUidIsNullOrderByIdAsc(accountId, messageId)
		return detached.firstOrNull { it.folderId == folderId } ?: detached.firstOrNull()
	}

	private fun remove(message: MailMessage) {
		if (isLinked(message)) message.detach() else messages.delete(message)
	}

	private fun isLinked(message: MailMessage): Boolean = links.linksOf(ItemRef(ItemType.EMAIL, message.id)).isNotEmpty()

	private fun assignThread(
		message: MailMessage,
		index: ThreadIndex,
	) {
		val assignment =
			MailThreading.assign(
				messageId = message.messageIdHeader,
				inReplyTo = message.inReplyTo,
				references = message.referencesHeader,
				subject = message.subject,
				fallbackId = message.id.toString(),
				index = index,
			)
		message.threadKey = assignment.key
		if (assignment.merged.isNotEmpty()) {
			messages.findByAccountIdAndThreadKeyIn(message.accountId, assignment.merged).forEach { it.threadKey = assignment.key }
		}
	}

	private inner class RepositoryThreadIndex(
		private val accountId: UUID,
	) : ThreadIndex {
		override fun keysByMessageId(messageIds: Collection<String>): Map<String, String> =
			messages
				.threadKeys(accountId, messageIds)
				.groupBy { it.messageIdHeader }
				.mapValues { (_, rows) -> rows.first().threadKey }

		override fun existingKeys(keys: Collection<String>): Set<String> = messages.existingThreadKeys(accountId, keys).toSet()
	}

	private companion object {
		const val QUERY_CHUNK = 1000

		fun applyHeaders(
			message: MailMessage,
			r: RemoteMessage,
		) {
			message.messageIdHeader = r.messageId
			message.inReplyTo = r.inReplyTo
			message.referencesHeader = r.references
			message.subject = r.subject
			message.from = r.from
			message.to = r.to
			message.cc = r.cc
			message.bcc = r.bcc
			message.replyTo = r.replyTo
			message.sentAt = r.sentAt
			message.receivedAt = r.receivedAt
			message.sizeBytes = r.sizeBytes
			message.hasAttachments = r.hasAttachments
			message.snippet = r.snippet
		}

		/** Returns whether anything changed. */
		fun applyFlags(
			message: MailMessage,
			flags: RemoteFlags,
			modSeq: Long?,
		): Boolean {
			val keywords = flags.keywords.toTypedArray()
			val changed =
				message.seen != flags.seen ||
					message.flagged != flags.flagged ||
					message.answered != flags.answered ||
					message.draft != flags.draft ||
					!message.keywords.contentEquals(keywords)
			if (changed) {
				message.seen = flags.seen
				message.flagged = flags.flagged
				message.answered = flags.answered
				message.draft = flags.draft
				message.keywords = keywords
			}
			if (modSeq != null && message.modSeq != modSeq) message.modSeq = modSeq
			return changed
		}
	}
}
