package ch.sonensei.cockpit.journal.entries.domain

import ch.sonensei.cockpit.core.persistence.SoftDeletableEntity
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import org.hibernate.annotations.BatchSize
import java.time.LocalDate
import java.util.UUID

/**
 * The journal entry of one local [date] (SPEC.md §8.5): Markdown like a note (inline links `[[type:id]]`, images
 * `![](cockpit-file:{fileId})` of files in `/Attachments/Journal`), an optional [mood] from 1 to 5, and tags.
 */
@Entity
@Table(name = "journal_entry")
class JournalEntry(
	@Column(name = "entry_date", nullable = false, updatable = false)
	val date: LocalDate,
	id: UUID? = null,
) : SoftDeletableEntity(id) {
	@Column(name = "body_md", nullable = false)
	var bodyMd: String = ""

	@Column(name = "mood")
	var mood: Int? = null

	// Batched so a page of entries loads its tags in one query, not one per entry.
	@ElementCollection
	@BatchSize(size = 100)
	@CollectionTable(name = "journal_entry_tag", joinColumns = [JoinColumn(name = "entry_id")])
	@Column(name = "tag", nullable = false)
	var tags: MutableSet<String> = mutableSetOf()

	/** The title in generic views and search, e.g. `Journal 2026-03-04`. */
	val displayTitle: String get() = "Journal $date"
}

/** The content of an entry as a PUT sets it: every field, omitted ones with their defaults. */
data class JournalEntryContent(
	val bodyMd: String = "",
	val mood: Int? = null,
	val tags: List<String> = emptyList(),
)

/** A date with an entry, for the calendar heatmap. */
data class JournalDay(
	val date: LocalDate,
	val mood: Int?,
)
