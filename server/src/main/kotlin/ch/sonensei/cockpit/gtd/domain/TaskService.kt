package ch.sonensei.cockpit.gtd.domain

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.persistence.storableInstant
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.CursorPage
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.gtd.infra.ChecklistItemRepository
import ch.sonensei.cockpit.gtd.infra.ContextRepository
import ch.sonensei.cockpit.gtd.infra.ProjectRepository
import ch.sonensei.cockpit.gtd.infra.TaskRepository
import jakarta.persistence.criteria.Predicate
import org.hibernate.Hibernate
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A created task, and whether it is new (`false`: an idempotent repeat of a create with the same id). */
data class CreatedTask(
	val task: Task,
	val created: Boolean,
)

/**
 * Tasks (SPEC.md §5.2, §5.4): create, change, GTD workflow (complete, move) and soft delete. Every change re-indexes
 * the task for search and publishes a [ch.sonensei.cockpit.core.events.domain.ChangeEvent]; a deleted task goes to the
 * Trash ([TaskTrashContributor][ch.sonensei.cockpit.gtd.infra.TaskTrashContributor]) and leaves the search index.
 *
 * Returned tasks have their contexts loaded, so callers can read them outside the transaction. Methods that change an
 * existing task take a `checkVersion` callback that sees the current version before anything changes (If-Match).
 */
@Service
@Transactional
class TaskService(
	private val tasks: TaskRepository,
	private val projects: ProjectRepository,
	private val contexts: ContextRepository,
	private val checklistItems: ChecklistItemRepository,
	private val indexer: SearchIndexer,
	private val changes: ChangeEventPublisher,
	private val settings: SettingsService,
	private val clock: Clock,
) {
	/**
	 * One page of tasks matching [filter], without tasks whose tickler date is still to come (callers run [releaseDue]
	 * first, so due ticklers and scheduled tasks are on their new lists). Open tasks are ordered by position, then id;
	 * done tasks (the Logbook) by completion time, newest first.
	 */
	@Transactional(readOnly = true)
	fun list(
		filter: TaskFilter,
		request: CursorRequest,
	): CursorPage<Task> {
		val after = request.position?.let { parsePosition(it, filter.completed) }
		val sort =
			if (filter.completed) {
				Sort.by(Sort.Order.desc("completedAt"), Sort.Order.desc("id"))
			} else {
				Sort.by(Sort.Order.asc("position"), Sort.Order.asc("id"))
			}
		val fetched =
			tasks.findBy<Task, List<Task>>(specification(filter, after)) { query ->
				query.sortBy(sort).limit(request.limit + 1).all()
			}
		fetched.forEach { Hibernate.initialize(it.contextIds) }
		return request.page(fetched) { positionOf(it, filter.completed) }
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): Task = load(id)

	/**
	 * Creates a task, by default in the Inbox at the end of its list (or project). With a client-chosen [id] (UUIDv7,
	 * offline capture) the create is idempotent: repeating it returns the existing task unchanged.
	 *
	 * @throws ConflictException (409) when the id belongs to a task in the Trash
	 * @throws InvalidRequestException (400) for invalid fields, see [move] for the list rules
	 */
	fun create(
		id: UUID?,
		list: TaskList,
		fields: TaskChanges,
	): CreatedTask {
		if (id != null) {
			if (!UuidV7.isUuidV7(id)) throw InvalidRequestException("id", "must be a UUIDv7")
			existingCreate(id)?.let { return it }
		}
		val title = fields.title?.value ?: throw InvalidRequestException("title", "is required")
		val task = Task(title, list, id)
		apply(task, fields)
		if (list == TaskList.WAITING && task.waitingSince == null) task.waitingSince = today()
		if (fields.position == null) task.position = nextPosition(task)
		validate(task, checkProject = true)
		val saved = tasks.saveAndFlush(task)
		changed(saved, ChangeOp.CREATED)
		return CreatedTask(initialized(saved), created = true)
	}

	/**
	 * The answer to a repeated create with the client-chosen [id]: the existing task unchanged, or `null` when there is
	 * none. Callers also use it after a concurrent create with the same id won the insert.
	 *
	 * @throws ConflictException (409) when the id belongs to a task in the Trash
	 */
	@Transactional(readOnly = true)
	fun existingCreate(id: UUID): CreatedTask? =
		tasks.findById(id).orElse(null)?.let { existing ->
			if (existing.isDeleted) throw ConflictException("A task with this id is in the Trash")
			CreatedTask(initialized(existing), created = false)
		}

	/**
	 * Changes the given fields. The list is changed with [move], completion with [complete]/[uncomplete].
	 *
	 * @throws InvalidRequestException (400) when the result is invalid (e.g. a SCHEDULED task without a date)
	 */
	fun update(
		id: UUID,
		fields: TaskChanges,
		checkVersion: (Long) -> Unit,
	): Task = modify(id, checkVersion, keepPosition = fields.position != null) { apply(it, fields) }

	/**
	 * Marks the task done (keeps the first completion time when it is already done). When it is the next step of an
	 * active project, that project is now stalled: [CompletedTask.needsNextStep] tells the client to ask for a new one.
	 * A recurring task gets its next occurrence ([createNextOccurrence]) the first time it is completed only, so
	 * uncompleting and completing it again creates no duplicate.
	 */
	fun complete(
		id: UUID,
		checkVersion: (Long) -> Unit,
	): CompletedTask {
		val task =
			modify(id, checkVersion) { task ->
				if (task.completedAt == null) {
					val now = clock.storableInstant()
					// Created before the task changes, so the position query's auto-flush does not update it twice.
					if (task.nextOccurrenceId == null) task.nextOccurrenceId = createNextOccurrence(task, now)?.id
					task.completedAt = now
				}
			}
		val project = task.projectId?.let(projects::findActiveById)
		val needsNextStep = project != null && project.status == ProjectStatus.ACTIVE && project.nextTaskId == task.id
		return CompletedTask(task, needsNextStep)
	}

	/** Makes a done task open again, on the list it was on. */
	fun uncomplete(
		id: UUID,
		checkVersion: (Long) -> Unit,
	): Task = modify(id, checkVersion) { it.completedAt = null }

	/**
	 * Moves the task to another list (processing). Moving to WAITING sets `waitingSince` to today unless it was already
	 * waiting; leaving WAITING clears the waiting-for fields, leaving SCHEDULED clears the scheduled date. A task that
	 * changes project goes to the end of its new project; a task without a project goes to the end of its new list; a
	 * project task that only changes list keeps its position within the project.
	 *
	 * @throws InvalidRequestException (400) for SCHEDULED without a date, a date or waiting-for on another list, a
	 *   tickler date that is not in the future, or an unknown project
	 */
	fun move(
		id: UUID,
		move: TaskMove,
		checkVersion: (Long) -> Unit,
	): Task {
		val errors = mutableListOf<FieldError>()
		if (move.list == TaskList.SCHEDULED && move.scheduledDate == null) {
			errors += FieldError("scheduledDate", "is required for the SCHEDULED list")
		}
		if (move.list != TaskList.SCHEDULED && move.scheduledDate != null) {
			errors += FieldError("scheduledDate", "is only allowed for the SCHEDULED list")
		}
		if (move.list != TaskList.WAITING) {
			if (move.waitingForContactId != null) errors += FieldError("waitingForContactId", "is only allowed for the WAITING list")
			if (move.waitingForText != null) errors += FieldError("waitingForText", "is only allowed for the WAITING list")
		}
		if (move.ticklerDate != null && !move.ticklerDate.isAfter(today())) {
			errors += FieldError("ticklerDate", "must be in the future")
		}
		if (errors.isNotEmpty()) throw InvalidRequestException(errors)

		return modify(id, checkVersion) { task ->
			val oldList = task.list
			task.list = move.list
			move.projectId?.let { task.projectId = it }
			task.scheduledDate = move.scheduledDate
			task.ticklerDate = move.ticklerDate
			if (move.list == TaskList.WAITING) {
				if (oldList != TaskList.WAITING || task.waitingSince == null) task.waitingSince = today()
				move.waitingForContactId?.let { task.waitingForContactId = it }
				move.waitingForText?.let { task.waitingForText = it }
			} else {
				task.waitingSince = null
				task.waitingForContactId = null
				task.waitingForText = null
			}
		}
	}

	/**
	 * Releases what is due today in the user's time zone (SPEC.md §5.1): open tasks whose tickler date has come go back
	 * to the INBOX (their tickler, scheduled date and waiting-for fields are cleared), then open SCHEDULED tasks whose
	 * date has come become NEXT actions (their scheduled date is cleared). A scheduled task in a tickler waits for the
	 * tickler. Each moved task is announced as `UPDATED`. Runs from [ch.sonensei.cockpit.gtd.infra.TicklerJob] and
	 * before tasks are read, so nothing depends on the job having run.
	 *
	 * @return the number of tasks moved
	 */
	fun releaseDue(): Int {
		val today = today()
		val ticklers = tasks.findDueTicklers(today)
		ticklers.forEach { task ->
			task.ticklerDate = null
			task.scheduledDate = null
			task.waitingSince = null
			task.waitingForContactId = null
			task.waitingForText = null
			moveTo(task, TaskList.INBOX)
		}
		val scheduled = tasks.findDueScheduled(today)
		scheduled.forEach { task ->
			task.scheduledDate = null
			moveTo(task, TaskList.NEXT)
		}
		return ticklers.size + scheduled.size
	}

	/** Moves the task to the Trash. */
	fun delete(
		id: UUID,
		checkVersion: (Long) -> Unit,
	) {
		val task = load(id)
		checkVersion(task.version)
		task.softDelete(clock)
		tasks.saveAndFlush(task)
		indexer.delete(ItemType.TASK, task.id)
		changes.publish(ItemType.TASK, task.id, ChangeOp.DELETED)
	}

	/**
	 * An active task, for other parts of the module (e.g. the checklist).
	 *
	 * @throws NotFoundException (404) when there is no such task or it is in the Trash
	 */
	@Transactional(readOnly = true)
	fun requireActive(id: UUID): Task = tasks.findActiveById(id) ?: throw NotFoundException("Task not found")

	/** Re-indexes a task and announces it as changed, e.g. after a change of its checklist or a restore. */
	fun changed(
		task: Task,
		op: ChangeOp = ChangeOp.UPDATED,
	) {
		indexer.upsert(ItemType.TASK, task.id, task.title, task.notesMd)
		changes.publish(ItemType.TASK, task.id, op)
	}

	/**
	 * Loads, changes, validates and saves a task. A task that changed project goes to the end of its new project, a task
	 * without a project that changed list to the end of its new list, unless [keepPosition]. A project task that only
	 * changed list keeps its position within the project. Validation runs before the position query, whose auto-flush would otherwise
	 * write an invalid task (e.g. an unknown project) to the database.
	 */
	private fun modify(
		id: UUID,
		checkVersion: (Long) -> Unit,
		keepPosition: Boolean = false,
		change: (Task) -> Unit,
	): Task {
		val task = load(id)
		checkVersion(task.version)
		val oldList = task.list
		val oldProject = task.projectId
		change(task)
		validate(task, checkProject = task.projectId != oldProject)
		// A project task is ordered within its project, so only a new project moves it; a task without one moves with its list.
		val moved = task.projectId != oldProject || (task.projectId == null && task.list != oldList)
		if (!keepPosition && moved) task.position = nextPosition(task)
		val saved = tasks.saveAndFlush(task)
		changed(saved)
		return saved
	}

	/** Puts a task on [list] (at the end of it, unless it is ordered within a project), saves and announces it. */
	private fun moveTo(
		task: Task,
		list: TaskList,
	) {
		val moved = task.list != list
		task.list = list
		if (moved && task.projectId == null) task.position = nextPosition(task)
		changed(tasks.saveAndFlush(task))
	}

	/**
	 * The next occurrence of a recurring [task] completed at [completedAt], or `null` when it does not recur or its
	 * series has ended. FIXED: the next day of the rule after the task's due date (or scheduled date, or the completion
	 * day without either), even if that day has passed. AFTER_COMPLETION: the first day of the rule after the completion
	 * day in the user's zone, the rule starting on that day.
	 *
	 * The new task is a copy on the same list (with an unchecked checklist), dated on that day: its due and scheduled
	 * dates move by the same number of days (the due date is set when the task had neither). It is not in a tickler and
	 * waits since today.
	 */
	private fun createNextOccurrence(
		task: Task,
		completedAt: Instant,
	): Task? {
		val rule = task.recurrenceRule ?: return null
		val mode = task.recurrenceMode ?: return null
		val nextRule = TaskRecurrence.remainingRule(rule) ?: return null
		val completedOn = completedAt.atZone(settings.timeZone()).toLocalDate()
		val anchor = task.dueDate ?: task.scheduledDate
		val nextDate =
			when (mode) {
				RecurrenceMode.FIXED -> (anchor ?: completedOn).let { TaskRecurrence.nextDate(rule, it, it) }
				RecurrenceMode.AFTER_COMPLETION -> TaskRecurrence.nextDate(rule, completedOn, completedOn)
			} ?: return null
		val shift = anchor?.let { ChronoUnit.DAYS.between(it, nextDate) }
		val next =
			Task(task.title, task.list).apply {
				notesMd = task.notesMd
				projectId = task.projectId?.takeIf { projects.findActiveById(it) != null }
				energy = task.energy
				creativity = task.creativity
				timeEstimateMin = task.timeEstimateMin
				contextIds.addAll(task.contextIds)
				dueDate = if (shift == null) nextDate else task.dueDate?.plusDays(shift)
				dueTime = task.dueTime
				scheduledDate = shift?.let { task.scheduledDate?.plusDays(it) }
				waitingForContactId = task.waitingForContactId
				waitingForText = task.waitingForText
				if (list == TaskList.WAITING) waitingSince = today()
				recurrenceRule = nextRule
				recurrenceMode = mode
			}
		next.position = nextPosition(next)
		validate(next, checkProject = false)
		val saved = tasks.saveAndFlush(next)
		checklistItems.findByTaskIdOrderByPositionAscIdAsc(task.id).forEach {
			checklistItems.save(ChecklistItem(saved.id, it.text, done = false, position = it.position))
		}
		changed(saved, ChangeOp.CREATED)
		return saved
	}

	private fun load(id: UUID): Task = initialized(requireActive(id))

	private fun initialized(task: Task): Task = task.also { Hibernate.initialize(it.contextIds) }

	private fun apply(
		task: Task,
		fields: TaskChanges,
	) {
		fields.title?.let { task.title = it.value }
		fields.notesMd?.let { task.notesMd = it.value }
		fields.projectId?.let { task.projectId = it.value }
		fields.position?.let { task.position = it.value }
		fields.energy?.let { task.energy = it.value }
		fields.creativity?.let { task.creativity = it.value }
		fields.timeEstimateMin?.let { task.timeEstimateMin = it.value }
		fields.contextIds?.let {
			task.contextIds.clear()
			task.contextIds.addAll(it.value)
		}
		fields.dueDate?.let { task.dueDate = it.value }
		fields.dueTime?.let { task.dueTime = it.value }
		fields.scheduledDate?.let { task.scheduledDate = it.value }
		fields.waitingForContactId?.let { task.waitingForContactId = it.value }
		fields.waitingForText?.let { task.waitingForText = it.value }
		fields.waitingSince?.let { task.waitingSince = it.value }
		fields.recurrenceRule?.let { task.recurrenceRule = it.value }
		fields.recurrenceMode?.let { task.recurrenceMode = it.value }
	}

	/**
	 * The invariants of a task, checked after every change; all violations are reported at once. The project must be
	 * active only when [checkProject] (it is new or changed): a task whose project went to the Trash stays changeable.
	 */
	private fun validate(
		task: Task,
		checkProject: Boolean,
	) {
		val errors = mutableListOf<FieldError>()
		if (task.title.isBlank()) errors += FieldError("title", "must not be blank")
		if (task.title.length > MAX_TITLE_LENGTH) errors += FieldError("title", "must be at most $MAX_TITLE_LENGTH characters")
		if ((task.notesMd?.length ?: 0) > MAX_NOTES_LENGTH) errors += FieldError("notesMd", "must be at most $MAX_NOTES_LENGTH characters")
		if ((task.waitingForText?.length ?: 0) > MAX_TITLE_LENGTH) {
			errors += FieldError("waitingForText", "must be at most $MAX_TITLE_LENGTH characters")
		}
		task.timeEstimateMin?.let {
			if (it !in 0..MAX_ESTIMATE_MINUTES) errors += FieldError("timeEstimateMin", "must be between 0 and $MAX_ESTIMATE_MINUTES")
		}
		if (task.dueTime != null && task.dueDate == null) errors += FieldError("dueTime", "requires dueDate")
		if (task.list == TaskList.SCHEDULED && task.scheduledDate == null) {
			errors += FieldError("scheduledDate", "is required for the SCHEDULED list")
		}
		if (task.list != TaskList.SCHEDULED && task.scheduledDate != null) {
			errors += FieldError("scheduledDate", "is only allowed for the SCHEDULED list")
		}
		if (task.list != TaskList.WAITING) {
			if (task.waitingForContactId != null) errors += FieldError("waitingForContactId", "is only allowed for the WAITING list")
			if (task.waitingForText != null) errors += FieldError("waitingForText", "is only allowed for the WAITING list")
			if (task.waitingSince != null) errors += FieldError("waitingSince", "is only allowed for the WAITING list")
		}
		val rule = task.recurrenceRule
		if ((rule?.length ?: 0) > MAX_TITLE_LENGTH) {
			errors += FieldError("recurrenceRule", "must be at most $MAX_TITLE_LENGTH characters")
		} else if (rule != null) {
			TaskRecurrence.problem(rule)?.let { errors += FieldError("recurrenceRule", it) }
		}
		if (rule != null && task.recurrenceMode == null) errors += FieldError("recurrenceMode", "is required with recurrenceRule")
		if (rule == null && task.recurrenceMode != null) errors += FieldError("recurrenceMode", "requires recurrenceRule")
		task.projectId?.let { if (checkProject && projects.findActiveById(it) == null) errors += FieldError("projectId", "does not exist") }
		if (task.contextIds.isNotEmpty() && contexts.findAllById(task.contextIds).size != task.contextIds.size) {
			errors += FieldError("contextIds", "contains an unknown context")
		}
		if (errors.isNotEmpty()) throw InvalidRequestException(errors)
	}

	private fun nextPosition(task: Task): Int {
		val projectId = task.projectId
		val max = if (projectId != null) tasks.maxPositionInProject(projectId) else tasks.maxPositionInList(task.list)
		return (max ?: -1) + 1
	}

	private fun today(): LocalDate = LocalDate.now(clock.withZone(settings.timeZone()))

	private fun specification(
		filter: TaskFilter,
		after: Pair<Any, UUID>?,
	): Specification<Task> =
		Specification { root, _, cb ->
			val predicates = mutableListOf<Predicate>(cb.isNull(root.get<Instant>("deletedAt")))
			predicates +=
				if (filter.completed) {
					cb.isNotNull(root.get<Instant>("completedAt"))
				} else {
					cb.isNull(root.get<Instant>("completedAt"))
				}
			// A task in a tickler is hidden until its date (SPEC.md §5.1).
			val ticklerDate = root.get<LocalDate>("ticklerDate")
			predicates += cb.or(cb.isNull(ticklerDate), cb.lessThanOrEqualTo(ticklerDate, today()))
			filter.list?.let { predicates += cb.equal(root.get<TaskList>("list"), it) }
			filter.projectId?.let { predicates += cb.equal(root.get<UUID>("projectId"), it) }
			filter.contextId?.let { predicates += cb.isMember(it, root.get<Collection<UUID>>("contextIds")) }
			filter.energy?.let { predicates += cb.equal(root.get<Energy>("energy"), it) }
			filter.creativity?.let { predicates += cb.equal(root.get<Creativity>("creativity"), it) }
			filter.maxMinutes?.let { predicates += cb.lessThanOrEqualTo(root.get("timeEstimateMin"), it) }
			filter.due?.let { due ->
				val today = today()
				val dueDate = root.get<LocalDate>("dueDate")
				predicates +=
					when (due) {
						DueFilter.OVERDUE -> cb.lessThan(dueDate, today)
						DueFilter.TODAY -> cb.equal(dueDate, today)
						DueFilter.WEEK -> cb.between(dueDate, today, today.plusDays(6))
					}
			}
			if (after != null) {
				val id = root.get<UUID>("id")
				predicates +=
					if (filter.completed) {
						val completedAt = root.get<Instant>("completedAt")
						val at = after.first as Instant
						cb.or(cb.lessThan(completedAt, at), cb.and(cb.equal(completedAt, at), cb.lessThan(id, after.second)))
					} else {
						val position = root.get<Int>("position")
						val at = after.first as Int
						cb.or(cb.greaterThan(position, at), cb.and(cb.equal(position, at), cb.greaterThan(id, after.second)))
					}
			}
			cb.and(*predicates.toTypedArray())
		}

	private fun positionOf(
		task: Task,
		completed: Boolean,
	): String = "${if (completed) task.completedAt else task.position}|${task.id}"

	/** The cursor as (sort key, id): the position for open tasks, the completion time for done ones. */
	private fun parsePosition(
		position: String,
		completed: Boolean,
	): Pair<Any, UUID> {
		val parsed =
			runCatching {
				val (key, id) = position.split('|').also { require(it.size == 2) }
				val sortKey: Any = if (completed) Instant.parse(key) else key.toInt()
				sortKey to UUID.fromString(id)
			}.getOrNull()
		return parsed ?: throw InvalidRequestException("cursor", "is not a valid cursor")
	}

	companion object {
		const val MAX_TITLE_LENGTH = 500
		const val MAX_NOTES_LENGTH = 100_000
		const val MAX_ESTIMATE_MINUTES = 10_000
	}
}
