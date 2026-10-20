package ch.sonensei.cockpit.gtd.domain

/** The GTD list a task is on (SPEC.md §5.1). Done is not a list: it is [Task.completedAt]. Stored by name. */
enum class TaskList {
	INBOX,
	NEXT,
	WAITING,
	SOMEDAY,

	/** Appears in Next on [Task.scheduledDate]. */
	SCHEDULED,
}

enum class Energy {
	LOW,
	MEDIUM,
	HIGH,
}

/** Creativity a task needs; the labels shown to the user are configurable (settings), the names are not. */
enum class Creativity {
	ROUTINE,
	MIXED,
	CREATIVE,
}

enum class ProjectStatus {
	ACTIVE,
	ON_HOLD,
	SOMEDAY,
	COMPLETED,
	DROPPED,
}

/** How the next occurrence of a recurring task is dated (SPEC.md §5.2 rules). */
enum class RecurrenceMode {
	/** The next date of the recurrence rule, whenever the task was completed. */
	FIXED,

	/** Relative to the completion date. */
	AFTER_COMPLETION,
}
