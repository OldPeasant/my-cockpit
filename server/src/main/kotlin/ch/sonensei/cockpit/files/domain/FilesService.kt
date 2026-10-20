package ch.sonensei.cockpit.files.domain

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemDeletedEvent
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.storableInstant
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.files.infra.BlobStore
import ch.sonensei.cockpit.files.infra.FileRepository
import ch.sonensei.cockpit.files.infra.FolderRepository
import ch.sonensei.cockpit.files.infra.ThumbnailStore
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.InputStream
import java.time.Clock
import java.util.UUID

/**
 * Folders and files of the drive (SPEC.md §8.2): creating, renaming and deleting (to the Trash) them, the special
 * folders, and the Trash operations behind the FILE and FOLDER trash contributors. Every change keeps the search index
 * (names) in sync and is announced as a change event.
 *
 * Deleting a folder moves its whole subtree to the Trash with one deletion time; only the folder itself is listed in
 * the Trash. Restoring it brings back what was deleted with it; purging it deletes the subtree and its blobs.
 * Blobs are written before the row and removed again when the transaction rolls back; a purged file's blob (and its
 * cached thumbnails) is deleted only after the transaction commits.
 *
 * Concurrency: inserting a file or folder locks its target folder `FOR SHARE` (and requires it to be active), and
 * deleting a folder locks every folder of its subtree `FOR UPDATE` before collecting the contents. So an insert racing
 * a delete either commits first (and is trashed with the folder) or waits and then fails with [NotFoundException].
 * Purging locks the item (a folder: its subtree) `FOR UPDATE` before it checks that it is in the Trash, so a restore
 * racing a purge either commits first (and the purge does nothing) or waits and then fails with a stale version.
 */
@Service
@Transactional
class FilesService(
	private val folders: FolderRepository,
	private val files: FileRepository,
	private val blobs: BlobStore,
	private val thumbnails: ThumbnailStore,
	private val search: SearchIndexer,
	private val changes: ChangeEventPublisher,
	private val events: ApplicationEventPublisher,
	private val jdbc: JdbcTemplate,
	private val clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@Transactional(readOnly = true)
	fun folder(id: UUID): Folder = folders.findActiveById(id) ?: throw NotFoundException("Folder not found")

	@Transactional(readOnly = true)
	fun file(id: UUID): FileItem = files.findActiveById(id) ?: throw NotFoundException("File not found")

	/** The absolute path of a folder (`/Attachments/Tasks`); `/` for the root (`null`). */
	@Transactional(readOnly = true)
	fun pathOf(folderId: UUID?): String {
		val names = ArrayDeque<String>()
		var current = folderId
		while (current != null) {
			val folder = folders.findById(current).orElse(null) ?: break
			names.addFirst(folder.name)
			current = folder.parentId
		}
		return names.joinToString("/", prefix = "/")
	}

	/**
	 * Creates a folder in [parentId] (`null`: the root).
	 *
	 * @throws ch.sonensei.cockpit.core.web.InvalidRequestException for an invalid name ([FileNames.validate])
	 * @throws NotFoundException when the parent does not exist or is in the Trash
	 * @throws ConflictException when the parent already has a folder with this name (regardless of case)
	 */
	fun createFolder(
		name: String,
		parentId: UUID?,
		id: UUID? = null,
	): Folder {
		val valid = FileNames.validate(name)
		parentId?.let(::lockActiveFolder)
		if (sibling(parentId, valid) != null) throw nameTaken()
		val folder = flushedName { folders.saveAndFlush(Folder(valid, parentId, id)) }
		indexed(folder)
		changes.publish(ItemType.FOLDER, folder.id, ChangeOp.CREATED)
		return folder
	}

	/** Renames the folder, see [updateFolder]. @throws ConflictException for a special folder or a taken name */
	fun renameFolder(
		id: UUID,
		name: String,
	): Folder = updateFolder(id, name, null)

	/**
	 * Renames the folder to [name] (`null`: unchanged) and/or moves it to [target] (`null`: unchanged). [checkVersion]
	 * gets the current version first (it throws to reject a stale request). Folder moves are serialized, so two moves
	 * running at the same time cannot create a cycle.
	 *
	 * @throws ConflictException for a special folder, when the target is the folder itself or below it, or when the
	 *   target already has a folder with the name
	 * @throws NotFoundException when the folder or the target folder does not exist or is in the Trash
	 */
	fun updateFolder(
		id: UUID,
		name: String?,
		target: MoveTarget?,
		checkVersion: (Long) -> Unit = {},
	): Folder {
		val folder = folder(id)
		checkVersion(folder.version)
		if (folder.isSpecial) throw ConflictException("Special folders cannot be renamed or moved")
		val newName = name?.let { FileNames.validate(it) } ?: folder.name
		val newParent = if (target != null) target.folderId else folder.parentId
		if (newParent != folder.parentId) {
			jdbc.queryForList("select pg_advisory_xact_lock(?)", FOLDER_MOVE_LOCK)
			if (newParent != null) {
				if (newParent in folders.findSubtreeIds(id)) {
					throw ConflictException("A folder cannot be moved into itself or a folder below it")
				}
				lockActiveFolder(newParent)
			}
		}
		val other = sibling(newParent, newName)
		if (other != null && other.id != folder.id) throw nameTaken()
		folder.name = newName
		folder.parentId = newParent
		flushedName { folders.flush() }
		indexed(folder)
		changes.publish(ItemType.FOLDER, folder.id, ChangeOp.UPDATED)
		return folder
	}

	/**
	 * Moves the folder and everything below it to the Trash. The subtree's folders are locked `FOR UPDATE` first, so
	 * no file or folder can be inserted into them until the delete commits (see [lockActiveFolder]). [checkVersion] gets
	 * the folder's current version first.
	 *
	 * @throws ConflictException for a special folder
	 */
	fun deleteFolder(
		id: UUID,
		checkVersion: (Long) -> Unit = {},
	) {
		val folder = folder(id)
		checkVersion(folder.version)
		if (folder.isSpecial) throw ConflictException("Special folders cannot be deleted")
		val at = clock.storableInstant()
		val subtree = folders.findAllById(lockSubtree(id)).filter { !it.isDeleted }
		val contained = files.findByFolderIdIn(subtree.map { it.id }).filter { !it.isDeleted }
		subtree.forEach {
			it.trashAt(at)
			search.delete(ItemType.FOLDER, it.id)
			changes.publish(ItemType.FOLDER, it.id, ChangeOp.DELETED)
		}
		contained.forEach {
			it.trashAt(at)
			search.delete(ItemType.FILE, it.id)
			changes.publish(ItemType.FILE, it.id, ChangeOp.DELETED)
		}
	}

	/**
	 * Stores [content] as a new file in [folderId] (`null`: the root). The blob is removed again if the transaction
	 * rolls back. File names need not be unique.
	 *
	 * [content] is read inside this method's transaction, so a pooled database connection stays checked out while it
	 * streams. Callers should pass content that is already buffered locally (e.g. a multipart upload Spring has stored
	 * in memory or a temporary file), not a stream fed by a slow client, so a few slow uploads cannot exhaust the pool.
	 * The target folder is locked only after the blob is written (see [lockActiveFolder]), so no row lock is held
	 * during the transfer.
	 *
	 * @throws ch.sonensei.cockpit.core.web.InvalidRequestException for an invalid name ([FileNames.validate])
	 * @throws NotFoundException when the folder does not exist or is in the Trash
	 * @throws ch.sonensei.cockpit.files.infra.BlobTooLargeException when [content] exceeds [maxSize] bytes
	 */
	fun storeFile(
		folderId: UUID?,
		name: String,
		mime: String,
		content: InputStream,
		maxSize: Long = Long.MAX_VALUE,
		id: UUID? = null,
	): FileItem {
		val valid = FileNames.validate(name)
		folderId?.let(::folder)
		val blob = blobs.write(content, maxSize)
		afterCompletion { committed -> if (!committed) deleteBlob(blob.key) }
		// Writing the blob can take long: check again, under a lock, that the folder was not deleted meanwhile.
		folderId?.let(::lockActiveFolder)
		val file = files.save(FileItem(valid, folderId, mime.ifBlank { DEFAULT_MIME }, blob.size, blob.sha256, blob.key, id))
		indexed(file)
		changes.publish(ItemType.FILE, file.id, ChangeOp.CREATED)
		return file
	}

	/** Renames the file, see [updateFile]. */
	fun renameFile(
		id: UUID,
		name: String,
	): FileItem = updateFile(id, name, null)

	/**
	 * Renames the file to [name] (`null`: unchanged) and/or moves it to [target] (`null`: unchanged). [checkVersion]
	 * gets the current version first.
	 *
	 * @throws NotFoundException when the file or the target folder does not exist or is in the Trash
	 */
	fun updateFile(
		id: UUID,
		name: String?,
		target: MoveTarget?,
		checkVersion: (Long) -> Unit = {},
	): FileItem {
		val file = file(id)
		checkVersion(file.version)
		name?.let { file.name = FileNames.validate(it) }
		if (target != null && target.folderId != file.folderId) {
			target.folderId?.let(::lockActiveFolder)
			file.folderId = target.folderId
		}
		files.flush()
		indexed(file)
		changes.publish(ItemType.FILE, file.id, ChangeOp.UPDATED)
		return file
	}

	fun deleteFile(
		id: UUID,
		checkVersion: (Long) -> Unit = {},
	) {
		val file = file(id)
		checkVersion(file.version)
		file.trashAt(clock.storableInstant())
		search.delete(ItemType.FILE, file.id)
		changes.publish(ItemType.FILE, file.id, ChangeOp.DELETED)
	}

	/**
	 * The special folder [kind], created (with its parents) when it does not exist yet. A user folder that already has
	 * the special folder's name and place becomes the special folder. Safe to call concurrently.
	 */
	fun specialFolder(kind: SpecialFolder): Folder {
		folders.findBySpecial(kind)?.let { return it }
		// Serialize creation, so two first uses do not both create the folder.
		jdbc.queryForList("select pg_advisory_xact_lock(?)", SPECIAL_FOLDER_LOCK)
		folders.findBySpecial(kind)?.let { return it }
		val parentId = kind.parent?.let { specialFolder(it).id }
		val existing = sibling(parentId, kind.folderName)
		val folder =
			if (existing != null) {
				// Adopt the user folder, with the special folder's spelling (it cannot be renamed afterwards).
				existing.also {
					it.special = kind
					it.name = kind.folderName
					indexed(it)
					changes.publish(ItemType.FOLDER, it.id, ChangeOp.UPDATED)
				}
			} else {
				folders.save(Folder(kind.folderName, parentId).apply { special = kind }).also {
					indexed(it)
					changes.publish(ItemType.FOLDER, it.id, ChangeOp.CREATED)
				}
			}
		return folder
	}

	/** The Trash of folders: only folders deleted themselves, not those deleted with their parent. */
	@Transactional(readOnly = true)
	fun trashedFolders(): List<Folder> = folders.findTrashRoots()

	/** The Trash of files: only files deleted themselves, not those deleted with their folder. */
	@Transactional(readOnly = true)
	fun trashedFiles(): List<FileItem> = files.findTrashRoots()

	/**
	 * Restores a folder listed in the Trash and what was deleted together with it. A name now taken by another folder
	 * gets a number (`Reports (2)`). `false` when the folder is not listed in the Trash.
	 */
	fun restoreFolder(id: UUID): Boolean {
		val folder = trashedFolders().find { it.id == id } ?: return false
		val at = folder.deletedAt
		folder.name = freeName(folder.parentId, folder.name)
		val subtree = folders.findAllById(folders.findSubtreeIds(id)).filter { it.deletedAt == at }
		subtree.forEach {
			it.restore()
			indexed(it)
		}
		files.findByFolderIdIn(subtree.map { it.id }).filter { it.deletedAt == at }.forEach {
			it.restore()
			indexed(it)
			changes.publish(ItemType.FILE, it.id, ChangeOp.UPDATED)
		}
		subtree.filter { it.id != id }.forEach { changes.publish(ItemType.FOLDER, it.id, ChangeOp.UPDATED) }
		return true
	}

	/** Restores a file listed in the Trash. `false` when it is not listed in the Trash. */
	fun restoreFile(id: UUID): Boolean {
		val file = trashedFiles().find { it.id == id } ?: return false
		file.restore()
		indexed(file)
		return true
	}

	/**
	 * Deletes a folder listed in the Trash permanently, with every folder and file below it and their blobs (after
	 * commit). Links and search documents of the contents go too; those of the folder itself are removed by the
	 * caller's [ItemDeletedEvent]. `false` when the folder is not listed in the Trash.
	 *
	 * Only rows in the Trash are ever purged. An active folder or file found below the folder (which the locking in
	 * [deleteFolder] and [storeFile] prevents) is not deleted but moved to the drive root, with its own subtree, so it
	 * survives; an active folder gets a free name there ([freeName]).
	 */
	fun purgeFolder(id: UUID): Boolean {
		// Lock first, then decide from the current rows (the subtree is loaded only by the locking read): a restore
		// committed meanwhile makes this return false, and a restore started later waits for the purge and then fails
		// with a stale version.
		val subtreeIds = lockSubtree(id)
		if (trashedFolders().none { it.id == id }) return false
		val subtree = folders.findAllById(subtreeIds)
		val children = subtree.groupBy { it.parentId }
		val purged = mutableListOf<Folder>()
		val pending = ArrayDeque(subtree.filter { it.id == id })
		while (pending.isNotEmpty()) {
			val next = pending.removeFirst()
			if (next.isDeleted) {
				purged += next
				pending += children[next.id].orEmpty()
			} else {
				rescue(next)
			}
		}
		val purgedIds = purged.map { it.id }
		val (contained, strays) = files.findByFolderIdIn(purgedIds).partition { it.isDeleted }
		strays.forEach { rescue(it) }
		if (contained.isNotEmpty()) files.deleteAllByIds(contained.map { it.id })
		folders.deleteAllByIds(purgedIds)
		contained.forEach {
			events.publishEvent(ItemDeletedEvent(ItemRef(ItemType.FILE, it.id)))
			changes.publish(ItemType.FILE, it.id, ChangeOp.DELETED)
		}
		purgedIds.filter { it != id }.forEach {
			events.publishEvent(ItemDeletedEvent(ItemRef(ItemType.FOLDER, it)))
			changes.publish(ItemType.FOLDER, it, ChangeOp.DELETED)
		}
		val keys = contained.map { it.storageKey }
		afterCompletion { committed -> if (committed) keys.forEach(::deleteBlob) }
		return true
	}

	/**
	 * Deletes a file listed in the Trash permanently, and its blob after commit. `false` when it is not listed. The file
	 * is locked first ([FileRepository.lockTrashed]), so a racing restore is never overwritten.
	 */
	fun purgeFile(id: UUID): Boolean {
		val file = files.lockTrashed(id) ?: return false
		if (trashedFiles().none { it.id == id }) return false
		files.deleteAllByIds(listOf(file.id))
		val key = file.storageKey
		afterCompletion { committed -> if (committed) deleteBlob(key) }
		return true
	}

	/** Moves an active folder found below a purged folder to the root, so the purge does not delete it. */
	private fun rescue(folder: Folder) {
		log.warn("Active folder {} found below a purged folder, moved to the root", folder.id)
		folder.name = freeName(null, folder.name)
		folder.parentId = null
		indexed(folder)
		changes.publish(ItemType.FOLDER, folder.id, ChangeOp.UPDATED)
	}

	/** Moves an active file found in a purged folder to the root, so the purge does not delete it. */
	private fun rescue(file: FileItem) {
		log.warn("Active file {} found in a purged folder, moved to the root", file.id)
		file.folderId = null
		changes.publish(ItemType.FILE, file.id, ChangeOp.UPDATED)
	}

	/** The folder [id], locked `FOR SHARE` while the transaction runs; see [FolderRepository.lockActiveForShare]. */
	private fun lockActiveFolder(id: UUID): Folder = folders.lockActiveForShare(id) ?: throw NotFoundException("Folder not found")

	/**
	 * Locks [id] and every folder below it `FOR UPDATE` and returns their ids. Repeats until no new folder appears,
	 * since a folder committed between reading the tree and locking it would otherwise be missed.
	 */
	private fun lockSubtree(id: UUID): List<UUID> {
		var locked = emptySet<UUID>()
		while (true) {
			val ids = folders.findSubtreeIds(id)
			if (locked.containsAll(ids)) return ids
			folders.lockForUpdate(ids)
			locked = ids.toSet()
		}
	}

	/** Runs [write] (which flushes a folder name) and turns a violation of the unique name index into a 409. */
	private fun <T> flushedName(write: () -> T): T =
		try {
			write()
		} catch (_: DataIntegrityViolationException) {
			// A concurrent request took the name between the sibling check and the insert/update.
			throw nameTaken()
		}

	private fun sibling(
		parentId: UUID?,
		name: String,
	): Folder? = if (parentId == null) folders.findActiveRootByName(name) else folders.findActiveChildByName(parentId, name)

	private fun freeName(
		parentId: UUID?,
		name: String,
	): String =
		generateSequence(1) { it + 1 }
			.map { if (it == 1) name else FileNames.numbered(name, it) }
			.first { sibling(parentId, it) == null }

	private fun indexed(folder: Folder) = search.upsert(ItemType.FOLDER, folder.id, folder.name, null)

	private fun indexed(file: FileItem) = search.upsert(ItemType.FILE, file.id, file.name, null)

	private fun nameTaken() = ConflictException("A folder with this name already exists here")

	private fun deleteBlob(key: String) {
		try {
			blobs.delete(key)
			thumbnails.delete(key)
		} catch (e: java.io.IOException) {
			log.warn("Could not delete blob {}", key, e)
		}
	}

	private fun afterCompletion(action: (committed: Boolean) -> Unit) {
		TransactionSynchronizationManager.registerSynchronization(
			object : TransactionSynchronization {
				override fun afterCompletion(status: Int) = action(status == TransactionSynchronization.STATUS_COMMITTED)
			},
		)
	}

	companion object {
		const val DEFAULT_MIME = "application/octet-stream"

		/** `pg_advisory_xact_lock` key for creating special folders (any constant unique within the application). */
		private const val SPECIAL_FOLDER_LOCK = 0x46494C45_53504543L

		/** `pg_advisory_xact_lock` key that serializes folder moves, so concurrent moves cannot create a cycle. */
		private const val FOLDER_MOVE_LOCK = 0x46494C45_4D4F5645L
	}
}
