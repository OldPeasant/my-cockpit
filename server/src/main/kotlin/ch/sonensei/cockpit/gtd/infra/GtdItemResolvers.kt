package ch.sonensei.cockpit.gtd.infra

import ch.sonensei.cockpit.core.items.domain.ItemResolver
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import ch.sonensei.cockpit.gtd.domain.ProjectStatus
import ch.sonensei.cockpit.gtd.domain.TaskList
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Tasks in generic views (SPEC.md §4.3): subtitle is the list or "Done", deep link `/tasks/{id}`. */
@Component
class TaskItemResolver(
	private val tasks: TaskRepository,
) : ItemResolver {
	override val type = ItemType.TASK

	@Transactional(readOnly = true)
	override fun resolve(id: UUID): ResolvedItem? =
		tasks.findActiveById(id)?.let {
			ResolvedItem(
				title = it.title,
				subtitle = if (it.isCompleted) "Done" else it.list.label(),
				icon = if (it.isCompleted) "task_alt" else "radio_button_unchecked",
				deepLink = "/tasks/${it.id}",
			)
		}

	private fun TaskList.label(): String =
		when (this) {
			TaskList.INBOX -> "Inbox"
			TaskList.NEXT -> "Next"
			TaskList.WAITING -> "Waiting For"
			TaskList.SOMEDAY -> "Someday/Maybe"
			TaskList.SCHEDULED -> "Scheduled"
		}
}

/** Projects in generic views (SPEC.md §4.3): subtitle is the status, deep link `/projects/{id}`. */
@Component
class ProjectItemResolver(
	private val projects: ProjectRepository,
) : ItemResolver {
	override val type = ItemType.PROJECT

	@Transactional(readOnly = true)
	override fun resolve(id: UUID): ResolvedItem? =
		projects.findActiveById(id)?.let {
			ResolvedItem(
				title = it.title,
				subtitle = it.status.label(),
				icon = "folder_open",
				deepLink = "/projects/${it.id}",
			)
		}

	private fun ProjectStatus.label(): String =
		when (this) {
			ProjectStatus.ACTIVE -> "Active"
			ProjectStatus.ON_HOLD -> "On hold"
			ProjectStatus.SOMEDAY -> "Someday/Maybe"
			ProjectStatus.COMPLETED -> "Completed"
			ProjectStatus.DROPPED -> "Dropped"
		}
}
