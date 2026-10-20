package ch.sonensei.cockpit.files.infra

import ch.sonensei.cockpit.core.persistence.SoftDeletableRepository
import ch.sonensei.cockpit.files.domain.FileItem
import ch.sonensei.cockpit.files.domain.Folder
import ch.sonensei.cockpit.files.domain.SpecialFolder
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface FolderRepository : SoftDeletableRepository<Folder> {
	fun findBySpecial(special: SpecialFolder): Folder?

	/** The folder at the root, not in the Trash, with [name] regardless of case. */
	@Query("select f from Folder f where f.parentId is null and lower(f.name) = lower(:name) and f.deletedAt is null")
	fun findActiveRootByName(name: String): Folder?

	/** The child of [parentId], not in the Trash, with [name] regardless of case. */
	@Query("select f from Folder f where f.parentId = :parentId and lower(f.name) = lower(:name) and f.deletedAt is null")
	fun findActiveChildByName(
		parentId: UUID,
		name: String,
	): Folder?

	/**
	 * The folder [id] when it is not in the Trash, locked `FOR SHARE` until the transaction ends, so a concurrent
	 * [lockForUpdate] (folder delete) waits for the caller to commit, and the caller waits for a running delete (and
	 * then gets `null`). Used before inserting a file or folder into it.
	 */
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select f from Folder f where f.id = :id and f.deletedAt is null")
	fun lockActiveForShare(id: UUID): Folder?

	/** Locks the folders [ids] `FOR UPDATE` until the transaction ends (in id order, to avoid deadlocks). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select f from Folder f where f.id in :ids order by f.id")
	fun lockForUpdate(ids: Collection<UUID>): List<Folder>

	/** [id] and the ids of all folders below it, in or out of the Trash. */
	@Query(
		value = """
			with recursive tree(id) as (
			    select id from folder where id = :id
			    union all
			    select f.id from folder f join tree t on f.parent_id = t.id
			)
			select id from tree
		""",
		nativeQuery = true,
	)
	fun findSubtreeIds(id: UUID): List<UUID>

	/** Folders in the Trash that were deleted themselves, not only as part of a deleted parent folder. */
	@Query(
		"""
		select f from Folder f where f.deletedAt is not null
		and (f.parentId is null or exists (select p.id from Folder p where p.id = f.parentId and p.deletedAt is null))
		""",
	)
	fun findTrashRoots(): List<Folder>

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Folder f where f.id in :ids")
	fun deleteAllByIds(ids: Collection<UUID>)
}

interface FileRepository : SoftDeletableRepository<FileItem> {
	/** The files of the given folders, in or out of the Trash. */
	fun findByFolderIdIn(folderIds: Collection<UUID>): List<FileItem>

	/** Files in the Trash that were deleted themselves, not only as part of a deleted folder. */
	@Query(
		"""
		select f from FileItem f where f.deletedAt is not null
		and (f.folderId is null or exists (select p.id from Folder p where p.id = f.folderId and p.deletedAt is null))
		""",
	)
	fun findTrashRoots(): List<FileItem>

	/**
	 * The file [id] when it is in the Trash, locked `FOR UPDATE` until the transaction ends. A purge locks the file with
	 * this before deleting it: a restore running at the same time either commits first (the condition is re-evaluated
	 * on the new row version after the wait, so this returns `null`) or waits for the purge and then fails.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select f from FileItem f where f.id = :id and f.deletedAt is not null")
	fun lockTrashed(id: UUID): FileItem?

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from FileItem f where f.id in :ids")
	fun deleteAllByIds(ids: Collection<UUID>)
}
