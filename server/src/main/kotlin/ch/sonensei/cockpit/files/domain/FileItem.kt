package ch.sonensei.cockpit.files.domain

import ch.sonensei.cockpit.core.persistence.SoftDeletableEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A file of the drive (SPEC.md §8.2): metadata in the `file` table, contents in the blob at [storageKey]
 * (`ab/cd/<uuid>` below `${COCKPIT_DATA_DIR}/blobs`). [folderId] `null` is the drive root. [size] is in bytes,
 * [sha256] the lowercase hex digest of the contents.
 */
@Entity
@Table(name = "file")
class FileItem(
	@Column(name = "name", nullable = false)
	var name: String,
	@Column(name = "folder_id")
	var folderId: UUID?,
	@Column(name = "mime", nullable = false)
	var mime: String,
	@Column(name = "size", nullable = false, updatable = false)
	val size: Long,
	@Column(name = "sha256", nullable = false, updatable = false)
	val sha256: String,
	@Column(name = "storage_key", nullable = false, updatable = false)
	val storageKey: String,
	id: UUID? = null,
) : SoftDeletableEntity(id) {
	/** Moves the file to the Trash at [at], so a whole subtree shares one deletion time. */
	internal fun trashAt(at: Instant) {
		if (deletedAt == null) deletedAt = at
	}
}
