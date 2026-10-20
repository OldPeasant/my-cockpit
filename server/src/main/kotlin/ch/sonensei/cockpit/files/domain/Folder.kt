package ch.sonensei.cockpit.files.domain

import ch.sonensei.cockpit.core.persistence.SoftDeletableEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A folder of the drive (SPEC.md §8.2). [parentId] `null` is the drive root. Names are unique per parent among folders
 * not in the Trash, regardless of case. A [special] folder is auto-managed: it cannot be deleted, renamed or moved.
 */
@Entity
@Table(name = "folder")
class Folder(
	@Column(name = "name", nullable = false)
	var name: String,
	@Column(name = "parent_id")
	var parentId: UUID? = null,
	id: UUID? = null,
) : SoftDeletableEntity(id) {
	@Enumerated(EnumType.STRING)
	@Column(name = "special")
	var special: SpecialFolder? = null

	val isSpecial: Boolean get() = special != null

	/** Moves the folder to the Trash at [at], so a whole subtree shares one deletion time. */
	internal fun trashAt(at: Instant) {
		if (deletedAt == null) deletedAt = at
	}
}

/**
 * The auto-managed folders `/Attachments/{Tasks,Notes,Mail,Journal}` (SPEC.md §8.2), created on demand by
 * [FilesService.specialFolder]. [parent] `null` means the folder sits at the drive root.
 */
enum class SpecialFolder(
	val folderName: String,
	val parent: SpecialFolder?,
) {
	ATTACHMENTS("Attachments", null),
	TASKS("Tasks", ATTACHMENTS),
	NOTES("Notes", ATTACHMENTS),
	MAIL("Mail", ATTACHMENTS),
	JOURNAL("Journal", ATTACHMENTS),
}
