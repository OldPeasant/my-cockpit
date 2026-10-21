package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.search.MailSearchIndex
import jakarta.mail.MessagingException
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * The folder list of an account (SPEC.md §7.2): [sync] reads it from the server and updates the cached `mail_folder`
 * rows (new folders added, vanished ones removed, roles detected by [FolderRoleDetector]); [overrideRole] lets the
 * owner choose a role by hand. Every changed folder publishes a `MAIL_FOLDER` change event.
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
				entityManager.find(MailAccount::class.java, account.id, LockModeType.PESSIMISTIC_WRITE)
					?: throw NotFoundException("Mail account not found")
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
				val folder =
					folders.findById(folderId).orElse(null)?.takeIf { it.accountId == accountId } ?: throw NotFoundException("Folder not found")
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
		private const val NO_DELIMITER = '\u0000'
	}
}
