package ch.sonensei.cockpit.gtd.domain

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.storableInstant
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.core.web.CursorPage
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.gtd.infra.AreaRepository
import ch.sonensei.cockpit.gtd.infra.ProjectRepository
import ch.sonensei.cockpit.gtd.infra.TaskRepository
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.CriteriaQuery
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.hibernate.Hibernate
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Projects (SPEC.md §5.2, §5.4): CRUD, the designated next step, the order of their tasks and soft delete. Every change
 * re-indexes the project for search and publishes a [ch.sonensei.cockpit.core.events.domain.ChangeEvent]; a deleted
 * project goes to the Trash ([ProjectTrashContributor][ch.sonensei.cockpit.gtd.infra.ProjectTrashContributor]) and
 * leaves the search index. Its tasks stay where they are.
 *
 * A project is **stalled** when it is ACTIVE and its next step is not an open task of the project: none is set, or it
 * is done, in the Trash or was moved to another project. Methods that change a project take a `checkVersion` callback
 * that sees the current version before anything changes (If-Match).
 */
@Service
@Transactional
class ProjectService(
	private val projects: ProjectRepository,
	private val tasks: TaskRepository,
	private val areas: AreaRepository,
	private val indexer: SearchIndexer,
	private val changes: ChangeEventPublisher,
	private val clock: Clock,
) {
	/** One page of projects matching [filter], ordered by title, then id. */
	@Transactional(readOnly = true)
	fun list(
		filter: ProjectFilter,
		request: CursorRequest,
	): CursorPage<ProjectView> {
		val after = request.position?.let(::parsePosition)
		val fetched =
			projects.findBy<Project, List<Project>>(specification(filter, after)) { query ->
				query.sortBy(Sort.by(Sort.Order.asc("title"), Sort.Order.asc("id"))).limit(request.limit + 1).all()
			}
		val views = views(fetched)
		return request.page(views) { "${it.project.id}|${it.project.title}" }
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): ProjectView = view(load(id))

	/**
	 * Creates a project, ACTIVE unless [ProjectChanges.status] says otherwise.
	 *
	 * @throws InvalidRequestException (400) for invalid fields or an unknown area
	 */
	fun create(fields: ProjectChanges): ProjectView {
		val title = fields.title?.value ?: throw InvalidRequestException("title", "is required")
		val project = Project(title)
		apply(project, fields)
		validate(project)
		val saved = projects.saveAndFlush(project)
		changed(saved, ChangeOp.CREATED)
		return view(saved)
	}

	/**
	 * Changes the given fields. Setting the status to COMPLETED records the completion time, any other status clears it.
	 *
	 * @throws InvalidRequestException (400) for invalid fields or an unknown area
	 */
	fun update(
		id: UUID,
		fields: ProjectChanges,
		checkVersion: (Long) -> Unit,
	): ProjectView =
		modify(id, checkVersion) { project ->
			apply(project, fields)
		}

	/**
	 * Designates [taskId] as the project's next step, or clears it (`null`).
	 *
	 * @throws InvalidRequestException (400) when the task is not an open task of this project
	 */
	fun setNextStep(
		id: UUID,
		taskId: UUID?,
		checkVersion: (Long) -> Unit,
	): ProjectView =
		modify(id, checkVersion) { project ->
			if (taskId != null) {
				val task = tasks.findActiveById(taskId)
				if (task == null || task.projectId != project.id) throw InvalidRequestException("taskId", "is not a task of this project")
				if (task.isCompleted) throw InvalidRequestException("taskId", "is done")
			}
			project.nextTaskId = taskId
		}

	/**
	 * Orders the project's tasks: the [taskIds] first, in the given order, then its other tasks (open and done) in their
	 * previous order. Returns all its tasks (not in the Trash) in the new order; each task whose position changed is
	 * announced as `UPDATED`.
	 *
	 * @throws InvalidRequestException (400) for duplicates or ids that are not tasks of this project
	 */
	fun orderTasks(
		id: UUID,
		taskIds: List<UUID>,
	): List<Task> {
		val project = load(id)
		val current = tasks.findByProjectIdAndDeletedAtIsNullOrderByPositionAscIdAsc(project.id)
		val byId = current.associateBy { it.id }
		val errors = mutableListOf<FieldError>()
		if (taskIds.toSet().size != taskIds.size) errors += FieldError("taskIds", "contains duplicates")
		if (taskIds.any { it !in byId }) errors += FieldError("taskIds", "contains a task that is not in this project")
		if (errors.isNotEmpty()) throw InvalidRequestException(errors)

		val listed = taskIds.map(byId::getValue)
		val ordered = listed + current.filter { it.id !in taskIds.toSet() }
		ordered.forEachIndexed { position, task ->
			if (task.position != position) {
				task.position = position
				tasks.save(task)
				changes.publish(ItemType.TASK, task.id, ChangeOp.UPDATED)
			}
		}
		tasks.flush()
		ordered.forEach { Hibernate.initialize(it.contextIds) }
		return ordered
	}

	/** Moves the project to the Trash; its tasks keep their project. */
	fun delete(
		id: UUID,
		checkVersion: (Long) -> Unit,
	) {
		val project = load(id)
		checkVersion(project.version)
		project.softDelete(clock)
		projects.saveAndFlush(project)
		indexer.delete(ItemType.PROJECT, project.id)
		changes.publish(ItemType.PROJECT, project.id, ChangeOp.DELETED)
	}

	/** Re-indexes a project and announces it as changed, e.g. after its area was deleted or a restore. */
	fun changed(
		project: Project,
		op: ChangeOp = ChangeOp.UPDATED,
	) {
		indexer.upsert(ItemType.PROJECT, project.id, project.title, project.descriptionMd)
		changes.publish(ItemType.PROJECT, project.id, op)
	}

	private fun modify(
		id: UUID,
		checkVersion: (Long) -> Unit,
		change: (Project) -> Unit,
	): ProjectView {
		val project = load(id)
		checkVersion(project.version)
		change(project)
		validate(project)
		val saved = projects.saveAndFlush(project)
		changed(saved)
		return view(saved)
	}

	private fun load(id: UUID): Project = projects.findActiveById(id) ?: throw NotFoundException("Project not found")

	private fun apply(
		project: Project,
		fields: ProjectChanges,
	) {
		fields.title?.let { project.title = it.value }
		fields.descriptionMd?.let { project.descriptionMd = it.value }
		fields.areaId?.let { project.areaId = it.value }
		fields.status?.let { change ->
			if (change.value == ProjectStatus.COMPLETED && project.status != ProjectStatus.COMPLETED) {
				project.completedAt = clock.storableInstant()
			}
			if (change.value != ProjectStatus.COMPLETED) project.completedAt = null
			project.status = change.value
		}
		fields.dueDate?.let { project.dueDate = it.value }
		fields.reviewDate?.let { project.reviewDate = it.value }
	}

	private fun validate(project: Project) {
		val errors = mutableListOf<FieldError>()
		if (project.title.isBlank()) errors += FieldError("title", "must not be blank")
		if (project.title.length > MAX_TITLE_LENGTH) errors += FieldError("title", "must be at most $MAX_TITLE_LENGTH characters")
		if ((project.descriptionMd?.length ?: 0) > MAX_DESCRIPTION_LENGTH) {
			errors += FieldError("descriptionMd", "must be at most $MAX_DESCRIPTION_LENGTH characters")
		}
		project.areaId?.let { if (!areas.existsById(it)) errors += FieldError("areaId", "does not exist") }
		if (errors.isNotEmpty()) throw InvalidRequestException(errors)
	}

	private fun view(project: Project): ProjectView = views(listOf(project)).single()

	/** The projects with their stalled flag; the next steps of all of them are loaded in one query. */
	private fun views(list: List<Project>): List<ProjectView> {
		val nextIds = list.filter { it.status == ProjectStatus.ACTIVE }.mapNotNull { it.nextTaskId }
		val nextTasks = if (nextIds.isEmpty()) emptyMap() else tasks.findAllById(nextIds).associateBy { it.id }
		return list.map { project ->
			val next = project.nextTaskId?.let(nextTasks::get)
			val open = next != null && !next.isDeleted && !next.isCompleted && next.projectId == project.id
			ProjectView(project, stalled = project.status == ProjectStatus.ACTIVE && !open)
		}
	}

	private fun specification(
		filter: ProjectFilter,
		after: Pair<String, UUID>?,
	): Specification<Project> =
		Specification { root, query, cb ->
			val predicates = mutableListOf<Predicate>(cb.isNull(root.get<Instant>("deletedAt")))
			filter.status?.let { predicates += cb.equal(root.get<ProjectStatus>("status"), it) }
			filter.areaId?.let { predicates += cb.equal(root.get<UUID>("areaId"), it) }
			filter.stalled?.let { stalled ->
				val isStalled = stalled(root, checkNotNull(query), cb)
				predicates += if (stalled) isStalled else cb.not(isStalled)
			}
			if (after != null) {
				val title = root.get<String>("title")
				val id = root.get<UUID>("id")
				predicates += cb.or(cb.greaterThan(title, after.first), cb.and(cb.equal(title, after.first), cb.greaterThan(id, after.second)))
			}
			cb.and(*predicates.toTypedArray())
		}

	/** SQL form of the stalled rule: ACTIVE, and no open task of the project is its next step. */
	private fun stalled(
		root: Root<Project>,
		query: CriteriaQuery<*>,
		cb: CriteriaBuilder,
	): Predicate {
		val sub = query.subquery(UUID::class.java)
		val task = sub.from(Task::class.java)
		val taskId: Expression<UUID> = task.get("id")
		sub.select(taskId).where(
			cb.equal(taskId, root.get<UUID>("nextTaskId")),
			cb.equal(task.get<UUID>("projectId"), root.get<UUID>("id")),
			cb.isNull(task.get<Instant>("completedAt")),
			cb.isNull(task.get<Instant>("deletedAt")),
		)
		return cb.and(cb.equal(root.get<ProjectStatus>("status"), ProjectStatus.ACTIVE), cb.not(cb.exists(sub)))
	}

	/** The cursor is `<id>|<title>`: the id first, as the title may contain `|`. */
	private fun parsePosition(position: String): Pair<String, UUID> {
		val parsed =
			runCatching {
				val separator = position.indexOf('|').also { require(it > 0) }
				position.substring(separator + 1) to UUID.fromString(position.substring(0, separator))
			}.getOrNull()
		return parsed ?: throw InvalidRequestException("cursor", "is not a valid cursor")
	}

	companion object {
		const val MAX_TITLE_LENGTH = 500
		const val MAX_DESCRIPTION_LENGTH = 100_000
	}
}
