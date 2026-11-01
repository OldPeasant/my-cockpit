package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.search.MailSearchIndex
import jakarta.mail.Folder
import jakarta.mail.FolderNotFoundException
import jakarta.mail.MessagingException
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.eclipse.angus.mail.iap.BadCommandException
import org.eclipse.angus.mail.iap.CommandFailedException
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * The folder list of an account (SPEC.md §7.2): [sync] reads it from the server and updates the cached `mail_folder`
 * rows (new folders added, vanished ones removed, roles detected by [FolderRoleDetector]); [overrideRole] lets the
 * owner choose a role by hand; [create], [rename] and [delete] change folders on the server and in the cache at once,
 * so the owner does not wait for the next sync. Every changed folder publishes a `MAIL_FOLDER` change event.
 */
@Service
class MailFolderService(
	private val accounts: MailAccountRepository,
	private val folders: MailFolderRepository,
	private val pool: ImapConnectionPool,
	private val changes: ChangeEventPublisher,
	private val transactions: TransactionTemplate,
	private val entityManager: EntityManager,
	private val searchIndex: MailSearchIndex,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** The cached folders of an account, by path. */
	fun list(accountId: UUID): List<MailFolder> {
		account(accountId)
		return folders.findByAccountIdOrderByPathAsc(accountId)
	}

	/**
	 * Reads the folder list from the server (outside any transaction, through the pool) and stores it. Syncs of the
	 * same account store one after the other (row lock on the account), so parallel syncs never insert a folder twice.
	 *
	 * @throws MailConnectionException when the server cannot be reached or rejects the login
	 */
	fun sync(accountId: UUID): List<MailFolder> {
		val account = account(accountId)
		val remote = pool.withStore(account) { store -> listRemote(store) }
		return checkNotNull(
			transactions.execute {
				// Waits until a parallel sync of this account has committed, so the cached rows read next include its folders.
				lockAccount(account.id)
				store(account.id, remote)
			},
		)
	}

	/**
	 * Sets a folder's role by hand ([role] `null`: the folder has no role), or with [automatic] hands it back to
	 * detection (the role is then recomputed on the next [sync]). A folder that had [role] before loses it.
	 */
	fun overrideRole(
		accountId: UUID,
		folderId: UUID,
		role: MailFolderRole?,
		automatic: Boolean,
		ifMatch: String?,
	): MailFolder =
		checkNotNull(
			transactions.execute {
				val folder = folder(accountId, folderId)
				ETags.checkIfMatch(ifMatch, folder.version)
				if (automatic) {
					folder.roleOverridden = false
				} else {
					if (role != null && folder.role != role) {
						folders.findByAccountIdAndRole(accountId, role)?.let { previous ->
							previous.role = null
							previous.roleOverridden = false
							folders.saveAndFlush(previous)
							changes.publish(FOLDER_EVENT, previous.id, ChangeOp.UPDATED)
						}
					}
					folder.role = role
					folder.roleOverridden = true
				}
				folders.saveAndFlush(folder).also { changes.publish(FOLDER_EVENT, it.id, ChangeOp.UPDATED) }
			},
		)

	/**
	 * Creates folder [name] under [parentId] (or at the top level) on the server (`CREATE`, then `SUBSCRIBE`), with the
	 * server's hierarchy delimiter, and caches it.
	 *
	 * @throws InvalidRequestException when [name] is blank or contains the delimiter
	 * @throws ConflictException when the name is already taken under that parent
	 * @throws MailConnectionException when the server cannot be reached or refuses the command
	 */
	fun create(
		accountId: UUID,
		name: String,
		parentId: UUID?,
	): MailFolder {
		val account = account(accountId)
		val parent = parentId?.let { folder(accountId, it) }
		val trimmed = validName(name)
		val created =
			pool.withStore(account) { store ->
				remote {
					val separator = parent?.let { store.getFolder(it.path).separator } ?: store.defaultFolder.separator
					val delimiter = separator.takeUnless { it == NO_DELIMITER }
					when {
						delimiter != null && trimmed.contains(delimiter) -> Created.InvalidName(delimiter)
						parent != null && delimiter == null -> Created.InvalidName(null)
						else -> createRemote(store, accountId, parent?.let { it.path + delimiter + trimmed } ?: trimmed, delimiter)
					}
				}
			}
		val done =
			when (created) {
				is Created.Done -> created
				is Created.InvalidName ->
					throw InvalidRequestException(
						"name",
						created.delimiter?.let { "must not contain the hierarchy delimiter $it" } ?: "the server has no folder hierarchy",
					)
				Created.Taken -> throw ConflictException(NAME_TAKEN)
				Created.Rejected -> throw MailConnectionException(MailConnectionError.SERVER_REJECTED)
			}
		return checkNotNull(
			transactions.execute {
				lockAccount(accountId)
				// A parallel sync may have cached it already.
				folders.findByAccountIdAndPath(accountId, done.path)
					?: folders
						.saveAndFlush(MailFolder(accountId, done.path, trimmed).apply { delimiter = done.delimiter?.toString() })
						.also { changes.publish(FOLDER_EVENT, it.id, ChangeOp.CREATED) }
			},
		)
	}

	private fun createRemote(
		store: IMAPStore,
		accountId: UUID,
		path: String,
		delimiter: Char?,
	): Created {
		if (taken(accountId, path)) return Created.Taken
		val folder = store.getFolder(path)
		if (folder.exists()) return Created.Taken
		if (!folder.create(Folder.HOLDS_MESSAGES or Folder.HOLDS_FOLDERS)) return Created.Rejected
		// A failed SUBSCRIBE leaves a folder that exists but is hidden in some clients: it is created all the same.
		runCatching { folder.isSubscribed = true }.onFailure { log.debug("SUBSCRIBE failed after CREATE", it) }
		return Created.Done(path, delimiter)
	}

	/**
	 * Renames a folder on the server (`RENAME`, under the same parent) and moves the cached folder and its subfolders
	 * to the new paths. Their ids and the ids of their cached messages stay, so links stay valid; when the server gives
	 * the folder a new UIDVALIDITY, the next sync resyncs it.
	 *
	 * @throws InvalidRequestException when [name] is blank or contains the delimiter
	 * @throws ConflictException for INBOX, or when the name is already taken under the same parent
	 * @throws MailConnectionException when the server cannot be reached or refuses the command
	 */
	fun rename(
		accountId: UUID,
		folderId: UUID,
		name: String,
		ifMatch: String?,
	): MailFolder {
		val account = account(accountId)
		val folder = folder(accountId, folderId)
		ETags.checkIfMatch(ifMatch, folder.version)
		if (isInbox(folder)) throw ConflictException("INBOX cannot be renamed")
		val trimmed = validName(name)
		val delimiter = folder.delimiter?.singleOrNull()
		if (delimiter != null && trimmed.contains(delimiter)) {
			throw InvalidRequestException("name", "must not contain the hierarchy delimiter $delimiter")
		}
		val oldPath = folder.path
		val newPath =
			if (delimiter != null && oldPath.contains(delimiter)) oldPath.substringBeforeLast(delimiter) + delimiter + trimmed else trimmed
		if (newPath == oldPath) return folder
		if (taken(accountId, newPath)) throw ConflictException(NAME_TAKEN)
		val outcome =
			pool.withStore(account) { store ->
				remote {
					val source = store.getFolder(oldPath)
					val target = store.getFolder(newPath)
					when {
						target.exists() -> Outcome.TAKEN
						// Gone on the server (the next sync uncaches it): nothing to rename.
						!source.exists() || !source.renameTo(target) -> Outcome.REJECTED
						else -> {
							// Not every server moves the subscription along with the folder.
							runCatching { target.isSubscribed = true }.onFailure { log.debug("SUBSCRIBE failed after RENAME", it) }
							Outcome.DONE
						}
					}
				}
			}
		when (outcome) {
			Outcome.TAKEN -> throw ConflictException(NAME_TAKEN)
			Outcome.REJECTED -> throw MailConnectionException(MailConnectionError.SERVER_REJECTED)
			Outcome.DONE -> Unit
		}
		return checkNotNull(
			transactions.execute {
				lockAccount(accountId)
				val moved = subtree(accountId, oldPath, delimiter)
				val renamed = moved.firstOrNull { it.id == folderId } ?: throw NotFoundException("Folder not found")
				moved.forEach { it.path = newPath + it.path.removePrefix(oldPath) }
				renamed.name = trimmed
				folders.flush()
				moved.forEach { changes.publish(FOLDER_EVENT, it.id, ChangeOp.UPDATED) }
				renamed
			},
		)
	}

	/**
	 * Deletes a folder and its subfolders on the server and from the cache. Their cached messages stay as link targets
	 * without folder, as for folders that vanish on the server. A folder already gone on the server is only uncached.
	 *
	 * @throws ConflictException for INBOX and folders with a role, or a folder holding one of them
	 * @throws MailConnectionException when the server cannot be reached or refuses the command
	 */
	fun delete(
		accountId: UUID,
		folderId: UUID,
		ifMatch: String?,
	) {
		val account = account(accountId)
		val folder = folder(accountId, folderId)
		ETags.checkIfMatch(ifMatch, folder.version)
		val delimiter = folder.delimiter?.singleOrNull()
		if (subtree(accountId, folder.path, delimiter).any { isInbox(it) || it.role != null }) {
			throw ConflictException("INBOX and folders with a role (or holding one) cannot be deleted")
		}
		val outcome =
			pool.withStore(account) { store ->
				remote {
					val remoteFolder = store.getFolder(folder.path)
					when {
						!remoteFolder.exists() -> Outcome.DONE
						!remoteFolder.delete(true) -> Outcome.REJECTED
						else -> {
							runCatching { remoteFolder.isSubscribed = false }.onFailure { log.debug("UNSUBSCRIBE failed after DELETE", it) }
							Outcome.DONE
						}
					}
				}
			}
		if (outcome != Outcome.DONE) throw MailConnectionException(MailConnectionError.SERVER_REJECTED)
		transactions.executeWithoutResult {
			lockAccount(accountId)
			subtree(accountId, folder.path, delimiter).forEach {
				// Its messages stay cached but without folder (the database sets folder_id to null): no longer searchable.
				searchIndex.unindexFolder(it.id)
				folders.delete(it)
				changes.publish(FOLDER_EVENT, it.id, ChangeOp.DELETED)
			}
			folders.flush()
		}
	}

	private fun folder(
		accountId: UUID,
		folderId: UUID,
	): MailFolder =
		folders.findById(folderId).orElse(null)?.takeIf { it.accountId == accountId } ?: throw NotFoundException("Folder not found")

	/** The cached folder at [path] and every cached folder below it. */
	private fun subtree(
		accountId: UUID,
		path: String,
		delimiter: Char?,
	): List<MailFolder> =
		folders.findByAccountIdOrderByPathAsc(accountId).filter {
			it.path == path || (delimiter != null && it.path.startsWith(path + delimiter))
		}

	/** Whether a cached folder already has [path] (INBOX in any case, RFC 9051 §5.1). */
	private fun taken(
		accountId: UUID,
		path: String,
	): Boolean =
		folders.findByAccountIdOrderByPathAsc(accountId).any {
			it.path == path || (it.path.equals(INBOX, ignoreCase = true) && path.equals(INBOX, ignoreCase = true))
		}

	private fun isInbox(folder: MailFolder) = folder.role == MailFolderRole.INBOX || folder.path.equals(INBOX, ignoreCase = true)

	private fun validName(name: String): String = name.trim().ifEmpty { throw InvalidRequestException("name", "must not be blank") }

	/** Waits until a parallel change of this account's folders has committed. */
	private fun lockAccount(accountId: UUID) {
		entityManager.find(MailAccount::class.java, accountId, LockModeType.PESSIMISTIC_WRITE)
			?: throw NotFoundException("Mail account not found")
	}

	/**
	 * Runs IMAP commands. Angus reports most refused commands (`NO`) as a `false` result of [block]; a `NO` or `BAD`
	 * it throws instead becomes [MailConnectionError.SERVER_REJECTED], any other failure a [MailConnectionException].
	 */
	private fun <T> remote(block: () -> T): T =
		try {
			block()
		} catch (e: MessagingException) {
			if (e is FolderNotFoundException ||
				generateSequence<Throwable>(e) { it.cause }.any { it is CommandFailedException || it is BadCommandException }
			) {
				log.debug("The mail server refused a folder command", e)
				throw MailConnectionException(MailConnectionError.SERVER_REJECTED, e)
			}
			throw MailConnectionException.of(e)
		}

	private sealed interface Created {
		data class Done(
			val path: String,
			val delimiter: Char?,
		) : Created

		data class InvalidName(
			val delimiter: Char?,
		) : Created

		data object Taken : Created

		data object Rejected : Created
	}

	private enum class Outcome { DONE, TAKEN, REJECTED }

	private fun account(accountId: UUID): MailAccount =
		accounts.findById(accountId).orElse(null) ?: throw NotFoundException("Mail account not found")

	private fun listRemote(store: IMAPStore): List<RemoteFolder> =
		try {
			store.defaultFolder.list("*").map { folder ->
				val imap = folder as IMAPFolder
				val delimiter = imap.separator.takeUnless { it == NO_DELIMITER }
				RemoteFolder(imap.fullName, imap.name, delimiter, imap.attributes.orEmpty().toSet())
			}
		} catch (e: MessagingException) {
			throw MailConnectionException.of(e)
		}.let(::withInbox)

	/** INBOX always exists (RFC 9051 §5.1); some servers leave it out of `LIST "" "*"`. */
	private fun withInbox(listed: List<RemoteFolder>): List<RemoteFolder> =
		if (listed.any { it.path.equals(INBOX, ignoreCase = true) }) listed else listed + RemoteFolder(INBOX, INBOX, null, emptySet())

	private fun store(
		accountId: UUID,
		remote: List<RemoteFolder>,
	): List<MailFolder> {
		val cached = folders.findByAccountIdOrderByPathAsc(accountId).associateBy { it.path }
		val remoteByPath = remote.associateBy { it.path }
		cached.values.filter { it.path !in remoteByPath }.forEach {
			// Its messages stay cached but without folder: no longer searchable.
			searchIndex.unindexFolder(it.id)
			folders.delete(it)
			changes.publish(FOLDER_EVENT, it.id, ChangeOp.DELETED)
		}
		val overrides = cached.values.filter { it.roleOverridden && it.path in remoteByPath }.associate { it.path to it.role }
		val roles = FolderRoleDetector.assign(remote, overrides)
		val changed = mutableSetOf<UUID>()
		// Free the roles that move first: (account_id, role) is unique and checked per statement.
		cached.values.filter { it.path in remoteByPath && it.role != null && it.role != roles[it.path] }.forEach {
			it.role = null
			changed += it.id
		}
		folders.flush()
		val result =
			remote.map { r ->
				val folder = cached[r.path]
				if (folder == null) {
					val created =
						MailFolder(accountId, r.path, r.name).apply {
							delimiter = r.delimiter?.toString()
							role = roles[r.path]
							selectable = r.selectable
						}
					folders.save(created).also { changes.publish(FOLDER_EVENT, it.id, ChangeOp.CREATED) }
				} else {
					val delimiter = r.delimiter?.toString()
					if (folder.name != r.name ||
						folder.delimiter != delimiter ||
						folder.selectable != r.selectable ||
						folder.role != roles[r.path]
					) {
						folder.name = r.name
						folder.delimiter = delimiter
						folder.selectable = r.selectable
						folder.role = roles[r.path]
						changed += folder.id
					}
					folder
				}
			}
		folders.flush()
		changed.forEach { changes.publish(FOLDER_EVENT, it, ChangeOp.UPDATED) }
		return result.sortedBy { it.path }
	}

	companion object {
		/** The `type` of folder change events. */
		const val FOLDER_EVENT = "MAIL_FOLDER"

		private const val INBOX = "INBOX"
		private const val NAME_TAKEN = "A folder with this name already exists"
		private const val NO_DELIMITER = '\u0000'
	}
}
