package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.gtd.domain.Project
import ch.sonensei.cockpit.gtd.domain.ProjectStatus
import ch.sonensei.cockpit.gtd.domain.Task
import ch.sonensei.cockpit.gtd.domain.TaskList
import ch.sonensei.cockpit.gtd.infra.ProjectItemResolver
import ch.sonensei.cockpit.gtd.infra.ProjectRepository
import ch.sonensei.cockpit.gtd.infra.TaskItemResolver
import ch.sonensei.cockpit.gtd.infra.TaskRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest
@Import(MutableClockTestConfiguration::class)
class GtdItemResolverTests(
	@Autowired private val registry: ItemResolverRegistry,
	@Autowired private val tasks: TaskRepository,
	@Autowired private val projects: ProjectRepository,
	@Autowired private val clock: MutableClock,
	@Autowired private val tx: TransactionTemplate,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@BeforeEach
	fun reset() {
		jdbc.execute("update project set next_task_id = null")
		jdbc.execute("delete from checklist_item")
		jdbc.execute("delete from task_context")
		jdbc.execute("delete from task")
		jdbc.execute("delete from project")
	}

	@Test
	fun `TASK and PROJECT resolvers are registered`() {
		registry.find(ItemType.TASK).shouldBeInstanceOf<TaskItemResolver>()
		registry.find(ItemType.PROJECT).shouldBeInstanceOf<ProjectItemResolver>()
	}

	@Test
	fun `a task resolves to its title, list and deep link`() {
		val task = tasks.save(Task("Water the plants", TaskList.NEXT))
		val resolver = registry.find(ItemType.TASK)!!

		resolver.resolve(task.id) shouldBe ResolvedItem("Water the plants", "Next", "radio_button_unchecked", "/tasks/${task.id}")
		resolver.exists(task.id) shouldBe true
	}

	@Test
	fun `a done task resolves as done`() {
		val task = tasks.save(Task("Pay invoice", TaskList.NEXT).apply { completedAt = clock.instant() })

		registry.find(ItemType.TASK)!!.resolve(task.id) shouldBe ResolvedItem("Pay invoice", "Done", "task_alt", "/tasks/${task.id}")
	}

	@Test
	fun `a project resolves to its title, status and deep link`() {
		val project = projects.save(Project("Plan holiday", ProjectStatus.ON_HOLD))

		registry.find(ItemType.PROJECT)!!.resolve(project.id) shouldBe
			ResolvedItem("Plan holiday", "On hold", "folder_open", "/projects/${project.id}")
	}

	@Test
	fun `every open task list has a readable subtitle`() {
		val resolver = registry.find(ItemType.TASK)!!
		val expected =
			mapOf(
				TaskList.INBOX to "Inbox",
				TaskList.NEXT to "Next",
				TaskList.WAITING to "Waiting For",
				TaskList.SOMEDAY to "Someday/Maybe",
				TaskList.SCHEDULED to "Scheduled",
			)
		expected.keys shouldBe TaskList.entries.toSet()

		expected.forEach { (list, label) ->
			val task = tasks.save(Task("Task on $list", list))
			resolver.resolve(task.id) shouldBe ResolvedItem("Task on $list", label, "radio_button_unchecked", "/tasks/${task.id}")
		}
	}

	@Test
	fun `every project status has a readable subtitle`() {
		val resolver = registry.find(ItemType.PROJECT)!!
		val expected =
			mapOf(
				ProjectStatus.ACTIVE to "Active",
				ProjectStatus.ON_HOLD to "On hold",
				ProjectStatus.SOMEDAY to "Someday/Maybe",
				ProjectStatus.COMPLETED to "Completed",
				ProjectStatus.DROPPED to "Dropped",
			)
		expected.keys shouldBe ProjectStatus.entries.toSet()

		expected.forEach { (status, label) ->
			val project = projects.save(Project("Project $status", status))
			resolver.resolve(project.id) shouldBe ResolvedItem("Project $status", label, "folder_open", "/projects/${project.id}")
			resolver.exists(project.id) shouldBe true
		}
	}

	@Test
	fun `a restored task and project resolve again`() {
		val task = tasks.save(Task("Restored task", TaskList.SOMEDAY))
		val project = projects.save(Project("Restored project"))
		tx.executeWithoutResult {
			tasks.findActiveById(task.id)!!.softDelete(clock)
			projects.findActiveById(project.id)!!.softDelete(clock)
		}
		tx.executeWithoutResult {
			tasks.findById(task.id).orElseThrow().restore()
			projects.findById(project.id).orElseThrow().restore()
		}

		registry.find(ItemType.TASK)!!.resolve(task.id)?.deepLink shouldBe "/tasks/${task.id}"
		registry.find(ItemType.PROJECT)!!.resolve(project.id)?.deepLink shouldBe "/projects/${project.id}"
	}

	@Test
	fun `missing and soft-deleted items do not resolve`() {
		val task = tasks.save(Task("Trashed task"))
		val project = projects.save(Project("Trashed project"))
		tx.executeWithoutResult {
			tasks.findActiveById(task.id)!!.softDelete(clock)
			projects.findActiveById(project.id)!!.softDelete(clock)
		}

		registry.find(ItemType.TASK)!!.resolve(task.id).shouldBeNull()
		registry.find(ItemType.TASK)!!.exists(task.id) shouldBe false
		registry.find(ItemType.PROJECT)!!.resolve(project.id).shouldBeNull()
		registry.find(ItemType.PROJECT)!!.exists(project.id) shouldBe false
		registry.find(ItemType.TASK)!!.resolve(UuidV7.generate()).shouldBeNull()
		registry.find(ItemType.PROJECT)!!.resolve(UuidV7.generate()).shouldBeNull()
	}
}
