package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.gtd.domain.Area
import ch.sonensei.cockpit.gtd.domain.ChecklistItem
import ch.sonensei.cockpit.gtd.domain.Context
import ch.sonensei.cockpit.gtd.domain.Creativity
import ch.sonensei.cockpit.gtd.domain.Energy
import ch.sonensei.cockpit.gtd.domain.Project
import ch.sonensei.cockpit.gtd.domain.ProjectStatus
import ch.sonensei.cockpit.gtd.domain.RecurrenceMode
import ch.sonensei.cockpit.gtd.domain.Task
import ch.sonensei.cockpit.gtd.domain.TaskList
import ch.sonensei.cockpit.gtd.infra.AreaRepository
import ch.sonensei.cockpit.gtd.infra.ChecklistItemRepository
import ch.sonensei.cockpit.gtd.infra.ContextRepository
import ch.sonensei.cockpit.gtd.infra.ProjectRepository
import ch.sonensei.cockpit.gtd.infra.TaskRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@SpringBootTest
@Import(MutableClockTestConfiguration::class)
class GtdRepositoryTests(
	@Autowired private val tasks: TaskRepository,
	@Autowired private val projects: ProjectRepository,
	@Autowired private val areas: AreaRepository,
	@Autowired private val contexts: ContextRepository,
	@Autowired private val checklist: ChecklistItemRepository,
	@Autowired private val clock: MutableClock,
	@Autowired private val tx: TransactionTemplate,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@BeforeEach
	fun reset() {
		jdbc.execute("update project set next_task_id = null")
		checklist.deleteAllInBatch()
		jdbc.execute("delete from task_context")
		jdbc.execute("delete from task")
		projects.deleteAllInBatch()
		areas.deleteAllInBatch()
		contexts.deleteAllInBatch()
		clock.set(MutableClockTestConfiguration.START)
	}

	@Test
	fun `a task with every field set is saved and loaded unchanged`() {
		val area = areas.save(Area("Home", position = 2))
		val project = projects.save(Project("Renovate kitchen").apply { areaId = area.id })
		val home = contexts.save(Context("@home", "#3366ff", 1))
		val phone = contexts.save(Context("@phone"))
		val contactId = UuidV7.generate()
		val completed = Instant.parse("2026-03-02T10:15:30.123456Z")

		val saved =
			tasks.save(
				Task("Call the tiler", TaskList.WAITING).apply {
					notesMd = "Ask about **grout** colours"
					projectId = project.id
					position = 3
					energy = Energy.LOW
					creativity = Creativity.MIXED
					timeEstimateMin = 15
					contextIds = mutableSetOf(home.id, phone.id)
					dueDate = LocalDate.of(2026, 3, 10)
					dueTime = LocalTime.of(14, 30)
					scheduledDate = LocalDate.of(2026, 3, 5)
					ticklerDate = LocalDate.of(2026, 3, 4)
					waitingForContactId = contactId
					waitingForText = "Tiler Example Ltd"
					waitingSince = LocalDate.of(2026, 3, 1)
					recurrenceRule = "FREQ=WEEKLY;BYDAY=MO"
					recurrenceMode = RecurrenceMode.AFTER_COMPLETION
					completedAt = completed
				},
			)

		val loaded = tx.execute { tasks.findActiveById(saved.id).shouldNotBeNull().also { it.contextIds.size } }
		loaded.title shouldBe "Call the tiler"
		loaded.list shouldBe TaskList.WAITING
		loaded.notesMd shouldBe "Ask about **grout** colours"
		loaded.projectId shouldBe project.id
		loaded.position shouldBe 3
		loaded.energy shouldBe Energy.LOW
		loaded.creativity shouldBe Creativity.MIXED
		loaded.timeEstimateMin shouldBe 15
		loaded.contextIds shouldContainExactlyInAnyOrder listOf(home.id, phone.id)
		loaded.dueDate shouldBe LocalDate.of(2026, 3, 10)
		loaded.dueTime shouldBe LocalTime.of(14, 30)
		loaded.scheduledDate shouldBe LocalDate.of(2026, 3, 5)
		loaded.ticklerDate shouldBe LocalDate.of(2026, 3, 4)
		loaded.waitingForContactId shouldBe contactId
		loaded.waitingForText shouldBe "Tiler Example Ltd"
		loaded.waitingSince shouldBe LocalDate.of(2026, 3, 1)
		loaded.recurrenceRule shouldBe "FREQ=WEEKLY;BYDAY=MO"
		loaded.recurrenceMode shouldBe RecurrenceMode.AFTER_COMPLETION
		loaded.completedAt shouldBe completed
		loaded.isCompleted shouldBe true
		loaded.deletedAt.shouldBeNull()
		loaded.createdAt shouldBe MutableClockTestConfiguration.START
		loaded.version shouldBe 0
	}

	@Test
	fun `a new task defaults to the inbox with only a title`() {
		val loaded = tasks.findActiveById(tasks.save(Task("Inbox thought")).id).shouldNotBeNull()

		loaded.list shouldBe TaskList.INBOX
		loaded.projectId.shouldBeNull()
		loaded.energy.shouldBeNull()
		loaded.isCompleted shouldBe false
	}

	@Test
	fun `a project with every field set is saved and loaded unchanged`() {
		val area = areas.save(Area("Work"))
		val project = projects.save(Project("Launch website", ProjectStatus.ON_HOLD))
		val next = tasks.save(Task("Draft sitemap", TaskList.NEXT).apply { projectId = project.id })
		val completed = Instant.parse("2026-04-01T09:00:00Z")

		tx.executeWithoutResult {
			projects.findActiveById(project.id)!!.apply {
				descriptionMd = "Goal: *live* by spring"
				areaId = area.id
				nextTaskId = next.id
				dueDate = LocalDate.of(2026, 5, 1)
				reviewDate = LocalDate.of(2026, 3, 8)
				completedAt = completed
			}
		}

		val loaded = projects.findActiveById(project.id).shouldNotBeNull()
		loaded.title shouldBe "Launch website"
		loaded.status shouldBe ProjectStatus.ON_HOLD
		loaded.descriptionMd shouldBe "Goal: *live* by spring"
		loaded.areaId shouldBe area.id
		loaded.nextTaskId shouldBe next.id
		loaded.dueDate shouldBe LocalDate.of(2026, 5, 1)
		loaded.reviewDate shouldBe LocalDate.of(2026, 3, 8)
		loaded.completedAt shouldBe completed
		loaded.version shouldBe 1
	}

	@Test
	fun `areas, contexts and checklist items are saved, loaded and ordered by position`() {
		val work = areas.save(Area("Work", 1))
		val family = areas.save(Area("Family", 0))
		val errands = contexts.save(Context("@errands", "#aa0000", 1))
		val computer = contexts.save(Context("@computer", null, 0))
		val task = tasks.save(Task("Pack for the trip"))
		val second = checklist.save(ChecklistItem(task.id, "Passport", done = true, position = 1))
		val first = checklist.save(ChecklistItem(task.id, "Tickets", position = 0))

		areas.findAllByOrderByPositionAscIdAsc().map { it.title } shouldContainExactly listOf("Family", "Work")
		areas.findById(work.id).orElseThrow().position shouldBe 1
		contexts.findAllByOrderByPositionAscIdAsc().map { it.id } shouldContainExactly listOf(computer.id, errands.id)
		contexts.findById(errands.id).orElseThrow().color shouldBe "#aa0000"
		contexts.findByNameIgnoreCase("@ERRANDS")?.id shouldBe errands.id
		checklist.findByTaskIdOrderByPositionAscIdAsc(task.id).map { it.id } shouldContainExactly listOf(first.id, second.id)
		checklist.findById(second.id).orElseThrow().apply {
			text shouldBe "Passport"
			done shouldBe true
			taskId shouldBe task.id
		}
		family.position shouldBe 0
	}

	@Test
	fun `soft-deleted tasks and projects are excluded from normal reads`() {
		val project = projects.save(Project("Old project"))
		val kept = tasks.save(Task("Kept", TaskList.NEXT).apply { projectId = project.id })
		val deleted = tasks.save(Task("Deleted", TaskList.NEXT).apply { projectId = project.id })

		tx.executeWithoutResult {
			tasks.findActiveById(deleted.id)!!.softDelete(clock)
			projects.findActiveById(project.id)!!.softDelete(clock)
		}

		tasks.findActiveById(deleted.id).shouldBeNull()
		tasks.findAllActive().map { it.id } shouldContainExactly listOf(kept.id)
		tasks.findByListAndCompletedAtIsNullAndDeletedAtIsNullOrderByPositionAscIdAsc(TaskList.NEXT).map { it.id } shouldContainExactly
			listOf(kept.id)
		tasks.findByProjectIdAndDeletedAtIsNullOrderByPositionAscIdAsc(project.id).map { it.id } shouldContainExactly listOf(kept.id)
		tasks.findAllDeleted().map { it.id } shouldContainExactly listOf(deleted.id)
		projects.findActiveById(project.id).shouldBeNull()
		projects.findByStatusAndDeletedAtIsNullOrderByIdAsc(ProjectStatus.ACTIVE).shouldContainExactly(emptyList())
		projects.findAllDeleted().map { it.id } shouldContainExactly listOf(project.id)
	}

	@Test
	fun `list queries return open tasks in position order, project queries include done tasks`() {
		val project = projects.save(Project("Garden"))
		val b = tasks.save(Task("B", TaskList.NEXT).apply { position = 2 })
		val a = tasks.save(Task("A", TaskList.NEXT).apply { position = 1 })
		tasks.save(Task("Done", TaskList.NEXT).apply { completedAt = clock.instant() })
		tasks.save(Task("Elsewhere", TaskList.SOMEDAY))
		val p2 =
			tasks.save(
				Task("Plant", TaskList.NEXT).apply {
					projectId = project.id
					position = 1
					completedAt = clock.instant()
				},
			)
		val p1 =
			tasks.save(
				Task("Dig", TaskList.NEXT).apply {
					projectId = project.id
					position = 0
				},
			)

		tasks.findByListAndCompletedAtIsNullAndDeletedAtIsNullOrderByPositionAscIdAsc(TaskList.NEXT).map { it.id } shouldContainExactly
			listOf(p1.id, a.id, b.id)
		tasks.findByProjectIdAndDeletedAtIsNullOrderByPositionAscIdAsc(project.id).map { it.id } shouldContainExactly listOf(p1.id, p2.id)
		projects.findByStatusAndDeletedAtIsNullOrderByIdAsc(ProjectStatus.ACTIVE).map { it.id } shouldContainExactly listOf(project.id)
	}

	@Test
	fun `deleting a task removes its checklist and contexts, and clears it as a project's next step`() {
		val project = projects.save(Project("Move house"))
		val context = contexts.save(Context("@home"))
		val task =
			tasks.save(
				Task("Book movers").apply {
					projectId = project.id
					contextIds = mutableSetOf(context.id)
				},
			)
		checklist.save(ChecklistItem(task.id, "Get quotes"))
		tx.executeWithoutResult { projects.findActiveById(project.id)!!.nextTaskId = task.id }

		jdbc.update("delete from task where id = ?", task.id)

		checklist.findByTaskIdOrderByPositionAscIdAsc(task.id).shouldContainExactly(emptyList())
		jdbc.queryForObject("select count(*) from task_context", Int::class.java) shouldBe 0
		projects
			.findActiveById(project.id)
			.shouldNotBeNull()
			.nextTaskId
			.shouldBeNull()
		contexts.existsById(context.id) shouldBe true
	}

	@Test
	fun `deleting a project, area or context detaches what referenced it`() {
		val area = areas.save(Area("Health"))
		val project = projects.save(Project("Run a 10k").apply { areaId = area.id })
		val context = contexts.save(Context("@gym"))
		val task =
			tasks.save(
				Task("Buy shoes").apply {
					projectId = project.id
					contextIds = mutableSetOf(context.id)
				},
			)

		jdbc.update("delete from area where id = ?", area.id)
		jdbc.update("delete from context where id = ?", context.id)
		jdbc.update("delete from project where id = ?", project.id)

		val loaded = tx.execute { tasks.findActiveById(task.id).shouldNotBeNull().also { it.contextIds.size } }
		loaded.projectId.shouldBeNull()
		loaded.contextIds.shouldContainExactly(emptySet())
	}

	@Test
	fun `context names are unique regardless of case`() {
		contexts.save(Context("@Home"))

		shouldThrow<DataIntegrityViolationException> { contexts.save(Context("@home")) }
	}

	@Test
	fun `references to missing rows and values outside the enums are rejected`() {
		val missing = UuidV7.generate()

		shouldThrow<DataIntegrityViolationException> { tasks.save(Task("Orphan").apply { projectId = missing }) }
		shouldThrow<DataIntegrityViolationException> { checklist.save(ChecklistItem(missing, "Orphan step")) }
		shouldThrow<DataIntegrityViolationException> { tasks.save(Task("Unknown context").apply { contextIds = mutableSetOf(missing) }) }
		val task = tasks.save(Task("Valid"))
		shouldThrow<DataIntegrityViolationException> { jdbc.update("update task set list = 'DONE' where id = ?", task.id) }
		shouldThrow<DataIntegrityViolationException> { jdbc.update("update task set due_time = '10:00' where id = ?", task.id) }
		shouldThrow<DataIntegrityViolationException> { jdbc.update("update task set time_estimate_min = -5 where id = ?", task.id) }
	}

	@Test
	fun `values outside the project status, energy, creativity and recurrence mode enums are rejected`() {
		val project = projects.save(Project("Valid project"))
		val task = tasks.save(Task("Valid task"))

		shouldThrow<DataIntegrityViolationException> { jdbc.update("update project set status = 'ARCHIVED' where id = ?", project.id) }
		shouldThrow<DataIntegrityViolationException> { jdbc.update("update task set energy = 'EXTREME' where id = ?", task.id) }
		shouldThrow<DataIntegrityViolationException> { jdbc.update("update task set creativity = 'GENIUS' where id = ?", task.id) }
		shouldThrow<DataIntegrityViolationException> { jdbc.update("update task set recurrence_mode = 'DAILY' where id = ?", task.id) }
		shouldThrow<DataIntegrityViolationException> { jdbc.update("update task set list = null where id = ?", task.id) }
		shouldThrow<DataIntegrityViolationException> { jdbc.update("update task set list = 'inbox' where id = ?", task.id) }
	}

	@Test
	fun `every enum value round-trips through the database`() {
		val ids =
			TaskList.entries.map { list ->
				tasks.save(Task("On $list", list)).id
			}
		ids.zip(TaskList.entries).forEach { (id, list) -> tasks.findActiveById(id)!!.list shouldBe list }

		Energy.entries.forEach { energy ->
			tasks
				.findActiveById(
					tasks.save(Task("E").apply { this.energy = energy }).id,
				)!!
				.energy shouldBe energy
		}
		Creativity.entries.forEach { c -> tasks.findActiveById(tasks.save(Task("C").apply { creativity = c }).id)!!.creativity shouldBe c }
		RecurrenceMode.entries.forEach { mode ->
			tasks.findActiveById(tasks.save(Task("R").apply { recurrenceMode = mode }).id)!!.recurrenceMode shouldBe mode
		}
		ProjectStatus.entries.forEach { status ->
			val project = projects.save(Project("P $status", status))
			projects.findActiveById(project.id)!!.status shouldBe status
			projects.findByStatusAndDeletedAtIsNullOrderByIdAsc(status).map { it.id } shouldContainExactly listOf(project.id)
		}
	}

	@Test
	fun `a zero time estimate and a due date without a time are accepted`() {
		val saved =
			tasks.save(
				Task("Quick check").apply {
					timeEstimateMin = 0
					dueDate = LocalDate.of(2026, 6, 1)
				},
			)

		val loaded = tasks.findActiveById(saved.id).shouldNotBeNull()
		loaded.timeEstimateMin shouldBe 0
		loaded.dueDate shouldBe LocalDate.of(2026, 6, 1)
		loaded.dueTime.shouldBeNull()
	}

	@Test
	fun `a project's area and next step must exist`() {
		val missing = UuidV7.generate()

		shouldThrow<DataIntegrityViolationException> { projects.save(Project("No area").apply { areaId = missing }) }
		shouldThrow<DataIntegrityViolationException> { projects.save(Project("No next step").apply { nextTaskId = missing }) }
	}

	@Test
	fun `changing a task's contexts replaces the stored set`() {
		val home = contexts.save(Context("@home"))
		val phone = contexts.save(Context("@phone"))
		val errands = contexts.save(Context("@errands"))
		val task = tasks.save(Task("Order groceries").apply { contextIds = mutableSetOf(home.id, phone.id) })

		tx.executeWithoutResult {
			tasks.findActiveById(task.id)!!.contextIds.apply {
				remove(phone.id)
				add(errands.id)
			}
		}

		val loaded = tx.execute { tasks.findActiveById(task.id).shouldNotBeNull().also { it.contextIds.size } }
		loaded.contextIds shouldContainExactlyInAnyOrder listOf(home.id, errands.id)
		jdbc.queryForObject("select count(*) from task_context where task_id = ?", Int::class.java, task.id) shouldBe 2
	}

	@Test
	fun `an update bumps the version and updated time, and a stale update is rejected`() {
		val saved = tasks.save(Task("Write report", TaskList.NEXT))
		val stale = tasks.findActiveById(saved.id).shouldNotBeNull()
		clock.advance(Duration.ofMinutes(5))

		tx.executeWithoutResult { tasks.findActiveById(saved.id)!!.title = "Write the annual report" }

		val current = tasks.findActiveById(saved.id).shouldNotBeNull()
		current.version shouldBe 1
		current.updatedAt shouldBe MutableClockTestConfiguration.START.plus(Duration.ofMinutes(5))
		current.createdAt shouldBe MutableClockTestConfiguration.START
		stale.version shouldBe 0
		stale.title = "Concurrent edit"
		shouldThrow<OptimisticLockingFailureException> { tasks.save(stale) }
		tasks.findActiveById(saved.id)!!.title shouldBe "Write the annual report"
	}

	@Test
	fun `processing a task and editing areas, contexts and checklist items are persisted`() {
		val task = tasks.save(Task("Inbox idea"))
		val area = areas.save(Area("Hobby"))
		val context = contexts.save(Context("@desk"))
		val step = checklist.save(ChecklistItem(task.id, "Sketch"))

		tx.executeWithoutResult {
			tasks.findActiveById(task.id)!!.list = TaskList.NEXT
			areas.findById(area.id).orElseThrow().apply {
				title = "Hobbies"
				position = 4
			}
			contexts.findById(context.id).orElseThrow().apply {
				name = "@workbench"
				color = "#00aa55"
				position = 2
			}
			checklist.findById(step.id).orElseThrow().apply {
				text = "Sketch the layout"
				done = true
				position = 1
			}
		}

		tasks.findActiveById(task.id)!!.list shouldBe TaskList.NEXT
		tasks.findByListAndCompletedAtIsNullAndDeletedAtIsNullOrderByPositionAscIdAsc(TaskList.INBOX).shouldContainExactly(emptyList())
		areas.findById(area.id).orElseThrow().apply {
			title shouldBe "Hobbies"
			position shouldBe 4
			version shouldBe 1
		}
		contexts.findByNameIgnoreCase("@WORKBENCH").shouldNotBeNull().apply {
			id shouldBe context.id
			color shouldBe "#00aa55"
			position shouldBe 2
		}
		checklist.findById(step.id).orElseThrow().apply {
			text shouldBe "Sketch the layout"
			done shouldBe true
			position shouldBe 1
		}
	}

	@Test
	fun `a stale project update is rejected`() {
		val saved = projects.save(Project("Paint fence"))
		val stale = projects.findActiveById(saved.id).shouldNotBeNull()
		tx.executeWithoutResult { projects.findActiveById(saved.id)!!.status = ProjectStatus.COMPLETED }

		stale.title = "Paint the fence white"
		shouldThrow<OptimisticLockingFailureException> { projects.save(stale) }
		projects.findActiveById(saved.id)!!.status shouldBe ProjectStatus.COMPLETED
	}

	@Test
	fun `a restored task is back in normal reads and list queries`() {
		val task = tasks.save(Task("Back from trash", TaskList.NEXT))
		tx.executeWithoutResult { tasks.findActiveById(task.id)!!.softDelete(clock) }
		tasks.findByListAndCompletedAtIsNullAndDeletedAtIsNullOrderByPositionAscIdAsc(TaskList.NEXT).shouldContainExactly(emptyList())

		tx.executeWithoutResult { tasks.findById(task.id).orElseThrow().restore() }

		tasks
			.findActiveById(task.id)
			.shouldNotBeNull()
			.deletedAt
			.shouldBeNull()
		tasks.findByListAndCompletedAtIsNullAndDeletedAtIsNullOrderByPositionAscIdAsc(TaskList.NEXT).map { it.id } shouldContainExactly
			listOf(task.id)
		tasks.findAllDeleted().shouldContainExactly(emptyList())
	}

	@Test
	fun `tasks with equal positions keep creation order`() {
		val first = tasks.save(Task("First", TaskList.SOMEDAY))
		val second = tasks.save(Task("Second", TaskList.SOMEDAY))
		val third = tasks.save(Task("Third", TaskList.SOMEDAY))

		tasks.findByListAndCompletedAtIsNullAndDeletedAtIsNullOrderByPositionAscIdAsc(TaskList.SOMEDAY).map { it.id } shouldContainExactly
			listOf(first.id, second.id, third.id)
		tasks.findByListAndCompletedAtIsNullAndDeletedAtIsNullOrderByPositionAscIdAsc(TaskList.INBOX).shouldContainExactly(emptyList())
	}

	@Test
	fun `an unknown context name is not found`() {
		contexts.save(Context("@office"))

		contexts.findByNameIgnoreCase("@home").shouldBeNull()
	}

	@Test
	fun `list, project and due queries can use the indexes`() {
		val indexes =
			jdbc.queryForList(
				"select indexname from pg_indexes where tablename in ('task', 'project', 'checklist_item', 'task_context')",
				String::class.java,
			)

		indexes.containsAll(
			listOf(
				"task_open_list",
				"task_project",
				"task_due_date",
				"task_scheduled_date",
				"task_tickler_date",
				"task_completed_at",
				"project_status",
				"project_area",
				"project_due_date",
				"checklist_item_task",
				"task_context_context",
			),
		) shouldBe true
	}
}
