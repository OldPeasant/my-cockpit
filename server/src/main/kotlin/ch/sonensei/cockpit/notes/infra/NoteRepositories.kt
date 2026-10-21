package ch.sonensei.cockpit.notes.infra

import ch.sonensei.cockpit.core.persistence.SoftDeletableRepository
import ch.sonensei.cockpit.notes.domain.Note
import ch.sonensei.cockpit.notes.domain.NoteRevision
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface NoteRepository :
	SoftDeletableRepository<Note>,
	JpaSpecificationExecutor<Note> {
	/** How a tag is already spelled on any note (also one in the Trash), compared regardless of case; `null` when unused. */
	@Query(value = "select tag from note_tag where lower(tag) = lower(:tag) order by tag limit 1", nativeQuery = true)
	fun findTagSpelling(tag: String): String?

	/** Every tag of the notes not in the Trash with its number of notes, ordered by tag regardless of case. */
	@Query(
		value =
			"select t.tag as tag, count(*) as count from note_tag t join note n on n.id = t.note_id " +
				"where n.deleted_at is null group by t.tag order by lower(t.tag), t.tag",
		nativeQuery = true,
	)
	fun countTags(): List<TagCount>
}

/** A tag and its number of notes. */
interface TagCount {
	val tag: String
	val count: Long
}

interface NoteRevisionRepository : JpaRepository<NoteRevision, UUID> {
	/** The kept revisions of a note, newest first. */
	fun findByNoteIdOrderByNumberDesc(noteId: UUID): List<NoteRevision>

	fun findFirstByNoteIdOrderByNumberDesc(noteId: UUID): NoteRevision?

	fun findByNoteIdAndNumber(
		noteId: UUID,
		number: Int,
	): NoteRevision?

	/** Deletes the revisions of a note numbered [number] or lower. */
	@Modifying(flushAutomatically = true, clearAutomatically = false)
	@Query("delete from NoteRevision r where r.noteId = :noteId and r.number <= :number")
	fun deleteUpTo(
		noteId: UUID,
		number: Int,
	): Int
}
