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
import java.time.LocalDate
import java.util.UUID

interface TaskRepository :
	SoftDeletableRepository<Task>,
	JpaSpecificationExecutor<Task> {
	/** Open tasks of a list (not done, not deleted), in list order. */
	fun findByListAndCompletedAtIsNullAndDeletedAtIsNullOrderByPositionAscIdAsc(list: TaskList): List<Task>

	/** A project's tasks (open and done, not deleted), in project order. */
	fun findByProjectIdAndDeletedAtIsNullOrderByPositionAscIdAsc(projectId: UUID): List<Task>

	/** Every task of a project, also deleted ones, in project order. */
	fun findByProjectIdOrderByPositionAscIdAsc(projectId: UUID): List<Task>

	/** The highest position in a project (`null` when it has no tasks). */
	@Query("select max(t.position) from Task t where t.projectId = :projectId and t.deletedAt is null")
	fun maxPositionInProject(projectId: UUID): Int?

	/** The highest position of the tasks without a project in a list (`null` when there are none). */
	@Query("select max(t.position) from Task t where t.list = :list and t.projectId is null and t.deletedAt is null")
	fun maxPositionInList(list: TaskList): Int?

	/** Open tasks whose tickler date is [today] or earlier, in the order they were put away. */
	@Query(
		"select t from Task t where t.ticklerDate <= :today and t.completedAt is null and t.deletedAt is null " +
			"order by t.ticklerDate, t.position, t.id",
	)
	fun findDueTicklers(today: LocalDate): List<Task>

	/** Open SCHEDULED tasks (not in a tickler) whose scheduled date is [today] or earlier, in date order. */
	@Query(
		"select t from Task t where t.list = ch.sonensei.cockpit.gtd.domain.TaskList.SCHEDULED " +
			"and t.scheduledDate <= :today and t.ticklerDate is null and t.completedAt is null and t.deletedAt is null " +
			"order by t.scheduledDate, t.position, t.id",
	)
	fun findDueScheduled(today: LocalDate): List<Task>

	/** Every task with the context, also done and deleted ones. */
	@Query("select t from Task t join t.contextIds c where c = :contextId")
	fun findAllWithContext(contextId: UUID): List<Task>
}

interface ProjectRepository :
	SoftDeletableRepository<Project>,
	JpaSpecificationExecutor<Project> {
	fun findByStatusAndDeletedAtIsNullOrderByIdAsc(status: ProjectStatus): List<Project>

	/** Projects (not deleted) with one of the statuses, oldest first. */
	fun findByStatusInAndDeletedAtIsNullOrderByIdAsc(statuses: Collection<ProjectStatus>): List<Project>

	/** The projects of an area, also deleted ones. */
	fun findByAreaId(areaId: UUID): List<Project>
}

interface AreaRepository : JpaRepository<Area, UUID> {
	fun findAllByOrderByPositionAscIdAsc(): List<Area>

	@Query("select max(a.position) from Area a")
	fun maxPosition(): Int?
}

interface ContextRepository : JpaRepository<Context, UUID> {
	fun findAllByOrderByPositionAscIdAsc(): List<Context>

	fun findByNameIgnoreCase(name: String): Context?

	@Query("select max(c.position) from Context c")
	fun maxPosition(): Int?
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
