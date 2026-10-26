package ch.sonensei.cockpit.files.fulltext

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Outcome of the text extraction of one file, see the `file_text` migration. */
enum class FileTextStatus { EXTRACTED, SKIPPED, FAILED }

/** An active file whose text has not been extracted from its current contents yet. */
data class PendingFile(
	val id: UUID,
	val mime: String,
	val size: Long,
	val sha256: String,
	val storageKey: String,
)

/** The current state of a file, read under a row lock. */
data class LockedFile(
	val name: String,
	val sha256: String,
	val updatedAt: Instant,
	val active: Boolean,
	val storageKey: String,
)

/** SQL access to `file_text`. */
@Repository
class FileTextRepository(
	private val jdbc: JdbcTemplate,
) {
	/** Up to [limit] active files without a `file_text` row for their contents, newest first. */
	fun findPending(limit: Int): List<PendingFile> =
		jdbc.query(
			"""select f.id, f.mime, f.size, f.sha256, f.storage_key
			from file f left join file_text t on t.file_id = f.id
			where f.deleted_at is null and (t.file_id is null or t.sha256 <> f.sha256)
			order by f.created_at desc, f.id desc
			limit ?""",
			{ rs, _ ->
				PendingFile(
					rs.getObject("id", UUID::class.java),
					rs.getString("mime"),
					rs.getLong("size"),
					rs.getString("sha256"),
					rs.getString("storage_key"),
				)
			},
			limit,
		)

	/**
	 * File [id] locked `FOR UPDATE` (so a rename, move or delete waits until the caller's transaction ends), `null`
	 * when it does not exist (purged).
	 */
	fun lockFile(id: UUID): LockedFile? =
		jdbc
			.query(
				"select name, sha256, updated_at, deleted_at, storage_key from file where id = ? for update",
				{ rs, _ ->
					LockedFile(
						rs.getString("name"),
						rs.getString("sha256"),
						rs.getTimestamp("updated_at").toInstant(),
						rs.getTimestamp("deleted_at") == null,
						rs.getString("storage_key"),
					)
				},
				id,
			).firstOrNull()

	/** The extracted text of file [fileId], `null` when there is none (not extracted yet, skipped or failed). */
	fun textOf(fileId: UUID): String? =
		jdbc
			.query(
				"select text from file_text where file_id = ? and status = 'EXTRACTED'",
				{ rs, _ -> rs.getString("text") },
				fileId,
			).firstOrNull()

	fun save(
		fileId: UUID,
		sha256: String,
		status: FileTextStatus,
		text: String?,
		at: Instant,
	) {
		jdbc.update(
			"""insert into file_text (file_id, sha256, status, text, extracted_at) values (?, ?, ?, ?, ?)
			on conflict (file_id) do update
			set sha256 = excluded.sha256, status = excluded.status, text = excluded.text, extracted_at = excluded.extracted_at""",
			fileId,
			sha256,
			status.name,
			text,
			Timestamp.from(at),
		)
	}
}
