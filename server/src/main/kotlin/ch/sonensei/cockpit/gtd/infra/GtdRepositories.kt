package ch.sonensei.cockpit.gtd.infra

import ch.sonensei.cockpit.core.persistence.SoftDeletableRepository
import ch.sonensei.cockpit.gtd.domain.Area
import ch.sonensei.cockpit.gtd.domain.ChecklistItem
import ch.sonensei.cockpit.gtd.domain.Context
import ch.sonensei.cockpit.gtd.domain.Project
import ch.sonensei.cockpit.gtd.domain.ProjectStatus
import ch.sonensei.cockpit.gtd.domain.Task
import ch.sonensei.cockpit.gtd.domain.TaskList
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TaskRepository : SoftDeletableRepository<Task> {
	/** Open tasks of a list (not done, not deleted), in list order. */
	fun findByListAndCompletedAtIsNullAndDeletedAtIsNullOrderByPositionAscIdAsc(list: TaskList): List<Task>

	/** A project's tasks (open and done, not deleted), in project order. */
	fun findByProjectIdAndDeletedAtIsNullOrderByPositionAscIdAsc(projectId: UUID): List<Task>
}

interface ProjectRepository : SoftDeletableRepository<Project> {
	fun findByStatusAndDeletedAtIsNullOrderByIdAsc(status: ProjectStatus): List<Project>
}

interface AreaRepository : JpaRepository<Area, UUID> {
	fun findAllByOrderByPositionAscIdAsc(): List<Area>
}

interface ContextRepository : JpaRepository<Context, UUID> {
	fun findAllByOrderByPositionAscIdAsc(): List<Context>

	fun findByNameIgnoreCase(name: String): Context?
}

interface ChecklistItemRepository : JpaRepository<ChecklistItem, UUID> {
	fun findByTaskIdOrderByPositionAscIdAsc(taskId: UUID): List<ChecklistItem>
}
