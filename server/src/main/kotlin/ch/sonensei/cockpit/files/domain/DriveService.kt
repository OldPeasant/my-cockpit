package ch.sonensei.cockpit.files.domain

import ch.sonensei.cockpit.core.web.CursorPage
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.files.infra.BlobStore
import ch.sonensei.cockpit.files.infra.DriveQueries
import ch.sonensei.cockpit.files.infra.FileRepository
import ch.sonensei.cockpit.files.infra.FolderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** The folder a file or folder is moved to; [folderId] `null` is the drive root. */
data class MoveTarget(
	val folderId: UUID?,
)

/** One entry of a folder listing: a folder or a file. */
sealed interface DriveEntry {
	data class OfFolder(
		val folder: Folder,
	) : DriveEntry

	data class OfFile(
		val file: FileItem,
	) : DriveEntry
}

/**
 * Disk usage of the drive: [usedBytes]/[fileCount] of the files not in the Trash, [trashBytes]/[trashFileCount] of
 * those in the Trash, and the [totalSpaceBytes]/[usableSpaceBytes] of the file system that holds the blobs.
 */
data class DriveUsage(
	val usedBytes: Long,
	val fileCount: Long,
	val folderCount: Long,
	val trashBytes: Long,
	val trashFileCount: Long,
	val totalSpaceBytes: Long,
	val usableSpaceBytes: Long,
)

/** One entry of a folder archive: a directory ([storageKey] `null`, [path] ends with `/`) or a file's blob. */
data class ArchiveEntry(
	val path: String,
	val storageKey: String?,
)

/**
 * Read views of the drive (SPEC.md §8.2): folder listings (folders first, then files, each by name regardless of case,
 * then id), disk usage, and the entries of a folder's zip download.
 */
@Service
@Transactional(readOnly = true)
class DriveService(
	private val folders: FolderRepository,
	private val files: FileRepository,
	private val queries: DriveQueries,
	private val blobs: BlobStore,
) {
	/**
	 * One page of the contents of [folderId] (`null`: the root), not in the Trash.
	 *
	 * @throws NotFoundException when the folder does not exist or is in the Trash
	 * @throws InvalidRequestException for a cursor that was not made by this listing
	 */
	fun list(
		folderId: UUID?,
		page: CursorRequest,
	): CursorPage<DriveEntry> {
		if (folderId != null) folders.findActiveById(folderId) ?: throw NotFoundException("Folder not found")
		val rows = queries.listing(folderId, page.position?.let(::parsePosition), page.limit + 1)
		val folderById = folders.findAllById(rows.filter { it.kind == DriveQueries.FOLDER }.map { it.id }).associateBy { it.id }
		val fileById = files.findAllById(rows.filter { it.kind == DriveQueries.FILE }.map { it.id }).associateBy { it.id }
		val entries =
			rows.mapNotNull { row ->
				if (row.kind == DriveQueries.FOLDER) {
					folderById[row.id]?.let { row to DriveEntry.OfFolder(it) }
				} else {
					fileById[row.id]?.let { row to DriveEntry.OfFile(it) }
				}
			}
		val result = page.page(entries) { (row, _) -> "${row.kind}|${row.id}|${row.sortName}" }
		return CursorPage(result.items.map { it.second }, result.nextCursor)
	}

	fun usage(): DriveUsage {
		val totals = queries.totals()
		val space = blobs.space()
		return DriveUsage(
			usedBytes = totals.usedBytes,
			fileCount = totals.fileCount,
			folderCount = totals.folderCount,
			trashBytes = totals.trashBytes,
			trashFileCount = totals.trashFileCount,
			totalSpaceBytes = space.total,
			usableSpaceBytes = space.usable,
		)
	}

	/**
	 * The entries of a zip of the folder [id] and everything below it that is not in the Trash, below a top directory
	 * named like the folder. Every directory gets an entry (so empty ones survive); paths are unique regardless of case,
	 * so a file whose name is taken in its folder gets a number (`report (2).pdf`).
	 *
	 * @throws NotFoundException when the folder does not exist or is in the Trash
	 */
	fun archiveEntries(id: UUID): List<ArchiveEntry> {
		val root = folders.findActiveById(id) ?: throw NotFoundException("Folder not found")
		val subtree = folders.findAllById(folders.findSubtreeIds(id)).filter { !it.isDeleted }
		val children = subtree.groupBy { it.parentId }
		val filesByFolder = files.findByFolderIdIn(subtree.map { it.id }).filter { !it.isDeleted }.groupBy { it.folderId }
		val entries = mutableListOf<ArchiveEntry>()
		val taken = mutableSetOf<String>()

		fun visit(
			folder: Folder,
			path: String,
		) {
			val dir = "$path/"
			entries += ArchiveEntry(dir, null)
			taken += dir.lowercase()
			// Folder names are unique per parent (regardless of case), so only files can collide.
			children[folder.id].orEmpty().sortedWith(compareBy({ it.name.lowercase() }, { it.id })).forEach {
				taken += "$dir${it.name}/".lowercase()
			}
			filesByFolder[folder.id].orEmpty().sortedWith(compareBy({ it.name.lowercase() }, { it.id })).forEach { file ->
				val name =
					generateSequence(1) { it + 1 }
						.map { if (it == 1) file.name else FileNames.numbered(file.name, it) }
						.first { "$dir$it".lowercase() !in taken && "$dir$it/".lowercase() !in taken }
				taken += "$dir$name".lowercase()
				entries += ArchiveEntry("$dir$name", file.storageKey)
			}
			children[folder.id].orEmpty().sortedWith(compareBy({ it.name.lowercase() }, { it.id })).forEach {
				visit(it, "$dir${it.name}")
			}
		}
		visit(root, root.name)
		return entries
	}

	private fun parsePosition(position: String): DriveQueries.Position {
		val parts = position.split('|', limit = 3)
		val kind = parts.getOrNull(0)?.toIntOrNull()
		val id = parts.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
		val sortName = parts.getOrNull(2)
		if (kind == null || kind !in setOf(DriveQueries.FOLDER, DriveQueries.FILE) || id == null || sortName == null) {
			throw InvalidRequestException("cursor", "is not a valid cursor")
		}
		return DriveQueries.Position(kind, id, sortName)
	}
}
