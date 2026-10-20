package ch.sonensei.cockpit.gtd.domain

import java.time.LocalDate
import java.util.UUID

/** Field changes of a project (create or PATCH); each `null` property leaves its field unchanged. */
data class ProjectChanges(
	val title: Change<String>? = null,
	val descriptionMd: Change<String?>? = null,
	val areaId: Change<UUID?>? = null,
	val status: Change<ProjectStatus>? = null,
	val dueDate: Change<LocalDate?>? = null,
	val reviewDate: Change<LocalDate?>? = null,
)

/** Filters of the project list; `null` properties do not filter. */
data class ProjectFilter(
	val status: ProjectStatus? = null,
	val areaId: UUID? = null,
	/** `true`: only stalled projects, `false`: only projects that are not stalled. */
	val stalled: Boolean? = null,
)

/** A project with its derived [stalled] flag (SPEC.md §5.2 rules). */
data class ProjectView(
	val project: Project,
	val stalled: Boolean,
)

/** A completed task, and whether its project now needs a new next step (it was the next step of an active project). */
data class CompletedTask(
	val task: Task,
	val needsNextStep: Boolean,
)
