package ch.sonensei.cockpit.notes.domain

import ch.sonensei.cockpit.core.persistence.BaseEntity
import ch.sonensei.cockpit.core.persistence.SoftDeletableEntity
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import org.hibernate.annotations.BatchSize
import java.time.Instant
import java.util.UUID

/**
 * A Markdown note (SPEC.md §8.1). [title] is optional: [displayTitle] falls back to the first line of [bodyMd]. Inline
 * links `[[type:id]]` in the body are SYNC links of the note; images are `![](cockpit-file:{fileId})` references to
 * files in `/Attachments/Notes`.
 */
@Entity
@Table(name = "note")
class Note(
	@Column(name = "title")
	var title: String? = null,
	@Column(name = "body_md", nullable = false)
	var bodyMd: String = "",
	id: UUID? = null,
) : SoftDeletableEntity(id) {
	@Column(name = "pinned", nullable = false)
	var pinned: Boolean = false

	@Column(name = "archived", nullable = false)
	var archived: Boolean = false

	// Batched so a page of notes loads its tags in one query, not one per note.
	@ElementCollection
	@BatchSize(size = 100)
	@CollectionTable(name = "note_tag", joinColumns = [JoinColumn(name = "note_id")])
	@Column(name = "tag", nullable = false)
	var tags: MutableSet<String> = mutableSetOf()

	/** The title, or else the first line of the body as plain text. */
	val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: NoteMarkdown.titleOf(bodyMd)
}

/**
 * An earlier version of a note's content (SPEC.md §8.1 "Note history"): [title] and [bodyMd] as they were saved at
 * [savedAt]. [number] counts up per note; the newest [NoteService.MAX_REVISIONS] are kept.
 */
@Entity
@Table(name = "note_revision")
class NoteRevision(
	@Column(name = "note_id", nullable = false, updatable = false)
	val noteId: UUID,
	@Column(name = "number", nullable = false, updatable = false)
	val number: Int,
	@Column(name = "title", updatable = false)
	val title: String?,
	@Column(name = "body_md", nullable = false, updatable = false)
	val bodyMd: String,
	@Column(name = "saved_at", nullable = false, updatable = false)
	val savedAt: Instant,
	id: UUID? = null,
) : BaseEntity(id)
