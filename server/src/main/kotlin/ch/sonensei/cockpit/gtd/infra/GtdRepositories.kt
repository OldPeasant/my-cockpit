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
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface TaskRepository :
	SoftDeletableRepository<Task>,
	JpaSpecificationExecutor<Task> {
	/** Open tasks of a list (not done, not deleted), in list order. */
	fun findByListAndCompletedAtIsNullAndDeletedAtIsNullOrderByPositionAscIdAsc(list: TaskList): List<Task>

	/** A project's tasks (open and done, not deleted), in project order. */
	fun findByProjectIdAndDeletedAtIsNullOrderByPositionAscIdAsc(projectId: UUID): List<Task>

	/** The highest position in a project (`null` when it has no tasks). */
	@Query("select max(t.position) from Task t where t.projectId = :projectId and t.deletedAt is null")
	fun maxPositionInProject(projectId: UUID): Int?

	/** The highest position of the tasks without a project in a list (`null` when there are none). */
	@Query("select max(t.position) from Task t where t.list = :list and t.projectId is null and t.deletedAt is null")
	fun maxPositionInList(list: TaskList): Int?
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

	fun findByIdAndTaskId(
		id: UUID,
		taskId: UUID,
	): ChecklistItem?

	@Query("select max(c.position) from ChecklistItem c where c.taskId = :taskId")
	fun maxPosition(taskId: UUID): Int?
}
