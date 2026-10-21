package ch.sonensei.cockpit.gtd.domain

import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** A new value for one field, which may be `null` (clears the field). A `null` [Change] means "leave unchanged". */
data class Change<out T>(
	val value: T,
)

/** Field changes of a task (create or PATCH); each `null` property leaves its field unchanged. */
data class TaskChanges(
	val title: Change<String>? = null,
	val notesMd: Change<String?>? = null,
	val projectId: Change<UUID?>? = null,
	val position: Change<Int>? = null,
	val energy: Change<Energy?>? = null,
	val creativity: Change<Creativity?>? = null,
	val timeEstimateMin: Change<Int?>? = null,
	val contextIds: Change<Set<UUID>>? = null,
	val dueDate: Change<LocalDate?>? = null,
	val dueTime: Change<LocalTime?>? = null,
	val scheduledDate: Change<LocalDate?>? = null,
	val waitingForContactId: Change<UUID?>? = null,
	val waitingForText: Change<String?>? = null,
	val waitingSince: Change<LocalDate?>? = null,
	val recurrenceRule: Change<String?>? = null,
	val recurrenceMode: Change<RecurrenceMode?>? = null,
)

/**
 * Moves a task to [list] (GTD processing). [projectId] `null` keeps the task's project. [scheduledDate] is required
 * for and only allowed with [TaskList.SCHEDULED]; the waiting-for fields are only allowed with [TaskList.WAITING].
 * [ticklerDate] hides the task until that (future) date; `null` clears it.
 */
data class TaskMove(
	val list: TaskList,
	val projectId: UUID? = null,
	val scheduledDate: LocalDate? = null,
	val ticklerDate: LocalDate? = null,
	val waitingForContactId: UUID? = null,
	val waitingForText: String? = null,
)

/** Due-date windows of the task list filter, relative to today in the user's time zone. */
enum class DueFilter {
	/** Due before today. */
	OVERDUE,

	/** Due today. */
	TODAY,

	/** Due today or in the next 6 days. */
	WEEK,
}

/** Filters of the task list; `null` properties do not filter. [completed] selects done (Logbook) or open tasks. */
data class TaskFilter(
	val list: TaskList? = null,
	val projectId: UUID? = null,
	val contextId: UUID? = null,
	val energy: Energy? = null,
	val creativity: Creativity? = null,
	val maxMinutes: Int? = null,
	val due: DueFilter? = null,
	val completed: Boolean = false,
	/** Only tasks with a due or tickler date in this range (both inclusive); includes tasks still in a tickler. */
	val dates: ClosedRange<LocalDate>? = null,
)

/** Changes of a checklist item; `null` leaves a field unchanged. */
data class ChecklistItemChanges(
	val text: String? = null,
	val done: Boolean? = null,
	val position: Int? = null,
)
