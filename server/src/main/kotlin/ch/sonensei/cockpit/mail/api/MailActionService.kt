package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.imap.ImapConnectionPool
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import ch.sonensei.cockpit.mail.imap.MailFolderService
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageLocator
import ch.sonensei.cockpit.mail.model.MailMessageRepository
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.FolderNotFoundException
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.ReadOnlyFolderException
import org.eclipse.angus.mail.iap.BadCommandException
import org.eclipse.angus.mail.iap.CommandFailedException
import org.eclipse.angus.mail.imap.AppendUID
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** Triage actions on messages (SPEC.md §7.3). */
enum class MailAction {
	READ,
	UNREAD,
	FLAG,
	UNFLAG,

	/** To `targetFolderId` (same account). */
	MOVE,

	/** To the account's Archive folder. */
	ARCHIVE,

	/** To the account's Trash folder; permanently deleted when already in Trash. */
	DELETE,

	/** To the account's Spam folder. */
	SPAM,
}

/** Why an action failed for a message, besides mail server failures ([ch.sonensei.cockpit.mail.imap.MailConnectionError]). */
enum class MailActionError(
	val message: String,
) {
	NOT_FOUND("The message does not exist or is no longer on the server"),
	NO_ROLE_FOLDER("The account has no folder with the role this action needs (Archive, Trash or Spam)"),
	OTHER_ACCOUNT("The target folder belongs to another account"),
	REJECTED("The mail server rejected the action"),
}

/** The result of an action for one requested id; [errorCode] and [detail] are `null` when it succeeded. */
data class MailActionOutcome(
	val id: UUID,
	val errorCode: String?,
	val detail: String?,
) {
	val ok: Boolean get() = errorCode == null

	companion object {
		fun ok(id: UUID) = MailActionOutcome(id, null, null)

		fun failed(
			id: UUID,
			code: String,
			detail: String,
		) = MailActionOutcome(id, code, detail)
	}
}

/**
 * Applies triage actions (SPEC.md §7.3) on the mail server and then on the header cache, one IMAP command per source
 * folder. Each id succeeds or fails on its own: a missing message, a missing role folder or a failing server only
 * fails the ids concerned.
 *
 * Flags are stored with `UID STORE`. Moves use `UID MOVE` (RFC 6851), else `UID COPY` + `\Deleted` + expunge; the
 * cached row keeps its id and gets the new location from `COPYUID` (UIDPLUS), so links, the body cache and clients'
 * ids stay valid. Without `COPYUID` the row is detached (kept for links) or removed, and the next sync of the target
 * folder brings it back. A permanent delete expunges only the deleted messages with `UID EXPUNGE` when the server
 * supports UIDPLUS, else with a plain `EXPUNGE`. Folders whose content changed publish a `MAIL_FOLDER` change event.
 */
@Service
class MailActionService(
	private val locator: MailMessageLocator,
	private val messages: MailMessageRepository,
	private val accounts: MailAccountRepository,
	private val folders: MailFolderRepository,
	private val pool: ImapConnectionPool,
	private val links: LinkService,
	private val transactions: TransactionTemplate,
	private val changes: ChangeEventPublisher,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * Applies [action] to the messages [ids] (duplicates count once); returns one outcome per distinct id, in request
	 * order. A message that moved is acted on at its current location.
	 *
	 * @throws InvalidRequestException when `targetFolderId` is missing for MOVE, given for another action, or the
	 *   folder cannot hold messages
	 * @throws NotFoundException for an unknown target folder
	 */
	fun apply(
		ids: List<UUID>,
		action: MailAction,
		targetFolderId: UUID?,
	): List<MailActionOutcome> {
		val target = target(action, targetFolderId)
		val outcomes = HashMap<UUID, MailActionOutcome>()
		val located = mutableListOf<Pair<UUID, MailMessage>>()
		for (id in ids.distinct()) {
			val message = locator.current(id)
			if (message == null) outcomes[id] = failure(id, MailActionError.NOT_FOUND) else located += id to message
		}
		located.groupBy { checkNotNull(it.second.folderId) }.forEach { (folderId, group) ->
			applyInFolder(folderId, group, action, target).forEach { outcomes[it.id] = it }
		}
		return ids.distinct().map { outcomes.getValue(it) }
	}

	private fun target(
		action: MailAction,
		targetFolderId: UUID?,
	): MailFolder? {
		if (action != MailAction.MOVE) {
			if (targetFolderId != null) throw InvalidRequestException("targetFolderId", "is only allowed for MOVE")
			return null
		}
		if (targetFolderId == null) throw InvalidRequestException("targetFolderId", "is required for MOVE")
		val folder = folders.findById(targetFolderId).orElse(null) ?: throw NotFoundException("Mail folder not found")
		if (!folder.selectable) throw InvalidRequestException("targetFolderId", "is a folder that cannot hold messages")
		return folder
	}

	/** The action for the messages of one source folder ([group]: requested id to its located row). */
	private fun applyInFolder(
		folderId: UUID,
		group: List<Pair<UUID, MailMessage>>,
		action: MailAction,
		target: MailFolder?,
	): List<MailActionOutcome> {
		val requested = group.map { it.first }
		val source = folders.findById(folderId).orElse(null)
		val account = source?.let { accounts.findById(it.accountId).orElse(null) }
		if (source == null || account == null) return requested.map { failure(it, MailActionError.NOT_FOUND) }
		val operation =
			when (val resolved = operation(action, source, target)) {
				is Resolved.Failed -> return requested.map { failure(it, resolved.error) }
				Resolved.Nothing -> return requested.map { MailActionOutcome.ok(it) }
				is Resolved.Do -> resolved.operation
			}
		val uids = group.map { checkNotNull(it.second.uid) }.distinct()
		val remote =
			try {
				pool.withStore(account) { store -> remote(store, source, uids, operation) }
			} catch (e: MailConnectionException) {
				// The cause may hold server responses; keep it at DEBUG.
				log.info("Mail action {} failed on the server: {}", action, e.error)
				log.debug("Mail action failure", e.cause)
				return requested.map { MailActionOutcome.failed(it, e.error.name, e.error.message) }
			}
		val done =
			when (remote) {
				Remote.Gone -> return requested.map { failure(it, MailActionError.NOT_FOUND) }
				Remote.Rejected -> return requested.map { failure(it, MailActionError.REJECTED) }
				is Remote.Done -> remote
			}
		updateCache(source, group.map { it.second }.filter { it.uid !in done.missing }, operation, done.newUids)
		return group.map { (id, message) ->
			if (message.uid in done.missing) failure(id, MailActionError.NOT_FOUND) else MailActionOutcome.ok(id)
		}
	}

	private fun operation(
		action: MailAction,
		source: MailFolder,
		target: MailFolder?,
	): Resolved {
		fun moveTo(destination: MailFolder?): Resolved =
			when {
				destination == null -> Resolved.Failed(MailActionError.NO_ROLE_FOLDER)
				destination.accountId != source.accountId -> Resolved.Failed(MailActionError.OTHER_ACCOUNT)
				destination.id == source.id -> Resolved.Nothing
				else -> Resolved.Do(Operation.Move(destination))
			}

		fun roleFolder(role: MailFolderRole) = folders.findByAccountIdAndRole(source.accountId, role)?.takeIf { it.selectable }
		return when (action) {
			MailAction.READ -> Resolved.Do(Operation.SetFlag(Flags.Flag.SEEN, true))
			MailAction.UNREAD -> Resolved.Do(Operation.SetFlag(Flags.Flag.SEEN, false))
			MailAction.FLAG -> Resolved.Do(Operation.SetFlag(Flags.Flag.FLAGGED, true))
			MailAction.UNFLAG -> Resolved.Do(Operation.SetFlag(Flags.Flag.FLAGGED, false))
			MailAction.MOVE -> moveTo(checkNotNull(target))
			MailAction.ARCHIVE -> moveTo(roleFolder(MailFolderRole.ARCHIVE))
			MailAction.SPAM -> moveTo(roleFolder(MailFolderRole.SPAM))
			MailAction.DELETE ->
				if (source.role == MailFolderRole.TRASH) Resolved.Do(Operation.Expunge) else moveTo(roleFolder(MailFolderRole.TRASH))
		}
	}

	/**
	 * Runs [operation] on [uids] of [source] on the server. A missing folder or message and a rejected command are
	 * returned, not thrown: a throw would count as a connection failure and drop a working pooled connection.
	 */
	private fun remote(
		store: IMAPStore,
		source: MailFolder,
		uids: List<Long>,
		operation: Operation,
	): Remote {
		val folder = store.getFolder(source.path) as IMAPFolder
		try {
			folder.open(Folder.READ_WRITE)
		} catch (e: FolderNotFoundException) {
			return Remote.Gone
		} catch (e: ReadOnlyFolderException) {
			return Remote.Rejected
		} catch (e: MessagingException) {
			throw MailConnectionException.of(e)
		}
		try {
			if (source.uidValidity != null && folder.uidValidity != source.uidValidity) return Remote.Gone
			val found = folder.getMessagesByUID(uids.toLongArray())
			val missing = uids.filterIndexed { i, _ -> found[i] == null }.toSet()
			val present = uids.filterIndexed { i, _ -> found[i] != null }
			val selected = found.filterNotNull().toTypedArray<Message>()
			if (selected.isEmpty()) return Remote.Done(missing, emptyMap())
			val newUids =
				when (operation) {
					is Operation.SetFlag -> {
						folder.setFlags(selected, Flags(operation.flag), operation.value)
						emptyMap()
					}
					is Operation.Move -> move(store, folder, selected, operation.destination, present)
					Operation.Expunge -> {
						folder.setFlags(selected, Flags(Flags.Flag.DELETED), true)
						expunge(store, folder, selected)
						emptyMap()
					}
				}
			return Remote.Done(missing, newUids)
		} catch (e: MessagingException) {
			if (generateSequence<Throwable>(e) { it.cause }.any { it is CommandFailedException || it is BadCommandException }) {
				log.debug("The mail server rejected an action", e)
				return Remote.Rejected
			}
			throw MailConnectionException.of(e)
		} finally {
			runCatching { if (folder.isOpen) folder.close(false) }
		}
	}

	/** Moves [selected] (UIDs [uids], same order) and returns their new UIDs where the server reported them. */
	private fun move(
		store: IMAPStore,
		folder: IMAPFolder,
		selected: Array<Message>,
		destination: MailFolder,
		uids: List<Long>,
	): Map<Long, AppendUID> {
		val target = store.getFolder(destination.path)
		val copied: Array<AppendUID?>? =
			if (store.hasCapability("MOVE")) {
				folder.moveUIDMessages(selected, target)
			} else {
				folder.copyUIDMessages(selected, target).also {
					folder.setFlags(selected, Flags(Flags.Flag.DELETED), true)
					expunge(store, folder, selected)
				}
			}
		if (copied == null) return emptyMap()
		return uids.zip(copied.toList()).mapNotNull { (uid, appended) -> appended?.let { uid to it } }.toMap()
	}

	private fun expunge(
		store: IMAPStore,
		folder: IMAPFolder,
		selected: Array<Message>,
	) {
		if (store.hasCapability("UIDPLUS")) folder.expunge(selected) else folder.expunge()
	}

	/** Applies what the server did to the cached rows [moved] (re-read in one transaction), then announces it. */
	private fun updateCache(
		source: MailFolder,
		moved: List<MailMessage>,
		operation: Operation,
		newUids: Map<Long, AppendUID>,
	) {
		if (moved.isEmpty()) return
		transactions.executeWithoutResult {
			val rows = messages.findAllById(moved.map { it.id }).filter { it.folderId == source.id && it.uid != null }
			for (row in rows) {
				when (operation) {
					is Operation.SetFlag ->
						when (operation.flag) {
							Flags.Flag.SEEN -> row.seen = operation.value
							else -> row.flagged = operation.value
						}
					is Operation.Move -> relocate(row, operation.destination, newUids[row.uid])
					Operation.Expunge -> remove(row)
				}
			}
			messages.flush()
			changes.publish(MailFolderService.FOLDER_EVENT, source.id, ChangeOp.UPDATED)
			if (operation is Operation.Move) changes.publish(MailFolderService.FOLDER_EVENT, operation.destination.id, ChangeOp.UPDATED)
		}
	}

	/**
	 * Points [row] at its new UID in [destination]; without one (or with another UIDVALIDITY than the cached one, or
	 * a row already cached there) it is removed and the next sync of [destination] brings the message back.
	 */
	private fun relocate(
		row: MailMessage,
		destination: MailFolder,
		appended: AppendUID?,
	) {
		val current = folders.findById(destination.id).orElse(null)
		val usable =
			appended != null &&
				appended.uid > 0 &&
				current != null &&
				(current.uidValidity == null || current.uidValidity == appended.uidvalidity) &&
				messages.findByFolderIdAndUid(destination.id, appended.uid) == null
		if (usable) row.locate(destination.id, checkNotNull(appended).uid) else remove(row)
	}

	/** A message gone from its location: kept detached while an item links to it, else deleted (with its body). */
	private fun remove(row: MailMessage) {
		if (links.linksOf(ItemRef(ItemType.EMAIL, row.id)).isNotEmpty()) row.detach() else messages.delete(row)
	}

	private fun failure(
		id: UUID,
		error: MailActionError,
	) = MailActionOutcome.failed(id, error.name, error.message)

	private sealed interface Operation {
		data class SetFlag(
			val flag: Flags.Flag,
			val value: Boolean,
		) : Operation

		data class Move(
			val destination: MailFolder,
		) : Operation

		data object Expunge : Operation
	}

	private sealed interface Resolved {
		data class Do(
			val operation: Operation,
		) : Resolved

		data class Failed(
			val error: MailActionError,
		) : Resolved

		/** Already done (e.g. archiving a message in Archive). */
		data object Nothing : Resolved
	}

	private sealed interface Remote {
		data class Done(
			val missing: Set<Long>,
			val newUids: Map<Long, AppendUID>,
		) : Remote

		data object Gone : Remote

		data object Rejected : Remote
	}
}
