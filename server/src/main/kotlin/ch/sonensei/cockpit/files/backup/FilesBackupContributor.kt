package ch.sonensei.cockpit.files.backup

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.json
import ch.sonensei.cockpit.backup.domain.jsonLines
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.files.domain.FileNames
import ch.sonensei.cockpit.files.infra.BlobStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * The drive in backups (SPEC.md §8.2, §10.2): `data/folders.json` and `data/files.jsonl` with every folder and file,
 * those in the Trash included, and each file's contents as `files/<path>`, in the original folder tree with the
 * original names.
 *
 * Paths are unique regardless of case (so the tree extracts on every file system) and among folders and files of a
 * folder together. On a clash the folder wins over the file, the item not in the Trash over the one in it, and the
 * older one (smaller id) over the newer; the others get ` (2)`, ` (3)`, ... before their extension. Each record keeps
 * its `path`, which is how restore finds the blob. Blobs are streamed one at a time, so memory use does not depend
 * on file sizes.
 *
 * Restore keeps ids, times and versions, stores every blob under a new storage key (checking its size and SHA-256
 * against the record) and indexes the items not in the Trash for search again. Thumbnails are a cache and rebuilt
 * on demand; unfinished uploads are not backed up.
 *
 * Rows are read and written with JDBC rather than JPA: a restore must keep ids, `created_at`, `updated_at` and
 * `version` exactly as backed up, which JPA auditing and versioning would overwrite.
 */
@Component
class FilesBackupContributor(
	private val jdbc: JdbcTemplate,
	private val blobs: BlobStore,
	private val search: SearchIndexer,
) : BackupContributor {
	override val id = ID

	override fun export(writer: BackupDataWriter) {
		val folders = folders()
		writer.json(FOLDERS, folders)
		val paths = FolderPaths(folders)
		files(paths) { all -> writer.jsonLines(FILES, all.map { it.record }) }
		files(paths) { all -> all.forEach { file -> blobs.open(file.storageKey).use { writer.file(file.record.path, it) } } }
	}

	override fun import(reader: BackupDataReader) {
		val folders = reader.json<List<FolderBackup>>(FOLDERS).orEmpty()
		// Parents first, for the foreign key.
		val byParent = folders.groupBy { it.parentId }
		val ordered =
			generateSequence(byParent[null].orEmpty()) { level -> level.flatMap { byParent[it.id].orEmpty() }.ifEmpty { null } }
				.flatten()
				.toList()
		check(ordered.size == folders.size) { "folders.json has folders whose parent is missing" }
		ordered.forEach(::insert)
		reader.jsonLines<FileBackup>(FILES) { file ->
			val content = checkNotNull(reader.file(file.path)) { "Blob of file ${file.id} is missing" }
			val stored = content.use { blobs.write(it) }
			try {
				check(stored.size == file.size && stored.sha256 == file.sha256) { "Blob of file ${file.id} does not match its size or SHA-256" }
				insert(file, stored.key)
			} catch (e: Exception) {
				blobs.delete(stored.key)
				throw e
			}
		}
	}

	private fun insert(folder: FolderBackup) {
		jdbc.update(
			"""insert into folder (id, created_at, updated_at, version, deleted_at, parent_id, name, special)
			values (?, ?, ?, ?, ?, ?, ?, ?)""",
			folder.id,
			Timestamp.from(folder.createdAt),
			Timestamp.from(folder.updatedAt),
			folder.version,
			folder.deletedAt?.let(Timestamp::from),
			folder.parentId,
			folder.name,
			folder.special,
		)
		if (folder.deletedAt == null) search.upsert(ItemType.FOLDER, folder.id, folder.name, null)
	}

	private fun insert(
		file: FileBackup,
		storageKey: String,
	) {
		jdbc.update(
			"""insert into file (id, created_at, updated_at, version, deleted_at, folder_id, name, mime, size, sha256, storage_key)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
			file.id,
			Timestamp.from(file.createdAt),
			Timestamp.from(file.updatedAt),
			file.version,
			file.deletedAt?.let(Timestamp::from),
			file.folderId,
			file.name,
			file.mime,
			file.size,
			file.sha256,
			storageKey,
		)
		if (file.deletedAt == null) search.upsert(ItemType.FILE, file.id, file.name, null)
	}

	/** Every folder with its path, in id order. */
	private fun folders(): List<FolderBackup> {
		val rows =
			jdbc.query("select * from folder order by id") { rs, _ ->
				FolderBackup(
					id = rs.uuid("id"),
					createdAt = rs.instant("created_at"),
					updatedAt = rs.instant("updated_at"),
					version = rs.getLong("version"),
					deletedAt = rs.instantOrNull("deleted_at"),
					parentId = rs.uuidOrNull("parent_id"),
					name = rs.getString("name"),
					special = rs.getString("special"),
					path = "",
				)
			}
		val byParent = rows.groupBy { it.parentId }
		val paths = mutableMapOf<UUID, String>()
		var level = byParent[null].orEmpty().map { it to "" }
		while (level.isNotEmpty()) {
			level =
				level
					.groupBy({ it.second }, { it.first })
					.flatMap { (prefix, siblings) ->
						val names = UniqueNames()
						siblings.sortedWith(PREFERRED).map { folder ->
							val path = prefix + names.claim(folder.name)
							paths[folder.id] = path
							byParent[folder.id].orEmpty().map { it to "$path/" }
						}
					}.flatten()
		}
		return rows.map { it.copy(path = paths.getValue(it.id)) }
	}

	/**
	 * Every file with its path, streamed from the database folder by folder, so only the names of one folder are kept
	 * in memory.
	 */
	private fun <R> files(
		folders: FolderPaths,
		block: (Sequence<ExportedFile>) -> R,
	): R {
		var folder: UUID? = null
		var names: UniqueNames? = null
		return jdbc
			.queryForStream(
				"select * from file order by folder_id nulls first, deleted_at is not null, id",
			) { rs, _ ->
				val record =
					FileBackup(
						id = rs.uuid("id"),
						createdAt = rs.instant("created_at"),
						updatedAt = rs.instant("updated_at"),
						version = rs.getLong("version"),
						deletedAt = rs.instantOrNull("deleted_at"),
						folderId = rs.uuidOrNull("folder_id"),
						name = rs.getString("name"),
						mime = rs.getString("mime"),
						size = rs.getLong("size"),
						sha256 = rs.getString("sha256"),
						path = "",
					)
				if (names == null || record.folderId != folder) {
					folder = record.folderId
					names = folders.namesIn(record.folderId)
				}
				ExportedFile(record.copy(path = folders.prefix(record.folderId) + names!!.claim(record.name)), rs.getString("storage_key"))
			}.use { block(it.iterator().asSequence()) }
	}

	private class ExportedFile(
		val record: FileBackup,
		val storageKey: String,
	)

	/** The paths of the folders, and the names their child folders already claim. */
	private class FolderPaths(
		folders: List<FolderBackup>,
	) {
		private val paths = folders.associate { it.id to it.path }
		private val children = folders.groupBy({ it.parentId }, { it.path.substringAfterLast('/') })

		fun prefix(folderId: UUID?): String = folderId?.let { "${paths.getValue(it)}/" }.orEmpty()

		fun namesIn(folderId: UUID?): UniqueNames = UniqueNames().apply { children[folderId].orEmpty().forEach(::claim) }
	}

	/** Names claimed in one folder, compared regardless of case. */
	private class UniqueNames {
		private val taken = mutableSetOf<String>()

		/** [name], or its first numbered variant (`a (2).txt`, `a (3).txt`, ...) that is not taken yet. */
		fun claim(name: String): String =
			generateSequence(1) { it + 1 }
				.map { n -> if (n == 1) name else FileNames.numbered(name, n) }
				.first { taken.add(it.lowercase()) }
	}

	companion object {
		const val ID = "files"
		const val FOLDERS = "folders"
		const val FILES = "files"

		/** Who keeps the original name on a clash: not in the Trash first, then the oldest. */
		private val PREFERRED = compareBy<FolderBackup>({ it.deletedAt != null }, { it.id })
	}
}

/** One entry of `data/folders.json`. [special] is a `SpecialFolder` name; [path] is the folder's path below `files/`. */
data class FolderBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val deletedAt: Instant?,
	val parentId: UUID?,
	val name: String,
	val special: String?,
	val path: String,
)

/** One line of `data/files.jsonl`. [path] is where the contents are, below `files/`. */
data class FileBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val deletedAt: Instant?,
	val folderId: UUID?,
	val name: String,
	val mime: String,
	val size: Long,
	val sha256: String,
	val path: String,
)

private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

private fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

private fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()

private fun ResultSet.instantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()
