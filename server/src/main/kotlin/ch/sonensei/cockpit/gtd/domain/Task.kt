package ch.sonensei.cockpit.gtd.domain

import ch.sonensei.cockpit.core.persistence.SoftDeletableEntity
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import org.hibernate.annotations.BatchSize
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * A GTD task (SPEC.md §5.2). Tasks may stay in the [TaskList.INBOX] with partial data. [projectId] and [contextIds]
 * reference [Project] and [Context] rows of this module; [waitingForContactId] references a contact (no foreign key).
 * Dates without a time ([dueDate], [scheduledDate], [ticklerDate], [waitingSince]) are local dates of the user's zone.
 */
@Entity
@Table(name = "task")
class Task(
	@Column(name = "title", nullable = false)
	var title: String,
	@Enumerated(EnumType.STRING)
	@Column(name = "list", nullable = false)
	var list: TaskList = TaskList.INBOX,
	id: UUID? = null,
) : SoftDeletableEntity(id) {
	@Column(name = "notes_md")
	var notesMd: String? = null

	@Column(name = "project_id")
	var projectId: UUID? = null

	/** Order within the project, or within the list for tasks without a project. */
	@Column(name = "position", nullable = false)
	var position: Int = 0

	@Enumerated(EnumType.STRING)
	@Column(name = "energy")
	var energy: Energy? = null

	@Enumerated(EnumType.STRING)
	@Column(name = "creativity")
	var creativity: Creativity? = null

	@Column(name = "time_estimate_min")
	var timeEstimateMin: Int? = null

	// Batched so a page of tasks loads its contexts in one query, not one per task.
	@ElementCollection
	@BatchSize(size = 100)
	@CollectionTable(name = "task_context", joinColumns = [JoinColumn(name = "task_id")])
	@Column(name = "context_id", nullable = false)
	var contextIds: MutableSet<UUID> = mutableSetOf()

	@Column(name = "due_date")
	var dueDate: LocalDate? = null

	/** Only together with [dueDate]. */
	@Column(name = "due_time")
	var dueTime: LocalTime? = null

	@Column(name = "scheduled_date")
	var scheduledDate: LocalDate? = null

	/** While set, the task is hidden; on that date it moves back to the Inbox. */
	@Column(name = "tickler_date")
	var ticklerDate: LocalDate? = null

	@Column(name = "waiting_for_contact_id")
	var waitingForContactId: UUID? = null

	@Column(name = "waiting_for_text")
	var waitingForText: String? = null

	@Column(name = "waiting_since")
	var waitingSince: LocalDate? = null

	/** An iCalendar RRULE without the `RRULE:` prefix, e.g. `FREQ=WEEKLY;BYDAY=MO`. */
	@Column(name = "recurrence_rule")
	var recurrenceRule: String? = null

	@Enumerated(EnumType.STRING)
	@Column(name = "recurrence_mode")
	var recurrenceMode: RecurrenceMode? = null

	/** The occurrence created when this recurring task was completed; completing it again creates no other one. */
	@Column(name = "next_occurrence_id")
	var nextOccurrenceId: UUID? = null

	@Column(name = "completed_at")
	var completedAt: Instant? = null

	val isCompleted: Boolean get() = completedAt != null
}
