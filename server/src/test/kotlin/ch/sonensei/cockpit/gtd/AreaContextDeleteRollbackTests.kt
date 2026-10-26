package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.gtd.domain.AreaService
import ch.sonensei.cockpit.gtd.domain.Change
import ch.sonensei.cockpit.gtd.domain.ContextService
import ch.sonensei.cockpit.gtd.domain.ProjectChanges
import ch.sonensei.cockpit.gtd.domain.ProjectService
import ch.sonensei.cockpit.gtd.domain.TaskChanges
import ch.sonensei.cockpit.gtd.domain.TaskList
import ch.sonensei.cockpit.gtd.domain.TaskService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.util.UUID

/**
 * Deleting a context with `replaceWith` or an area with `moveTo` is one transaction: a failure after the tasks or
 * projects were reassigned (here: announcing the deletion) undoes the reassignment and keeps the context or area.
 */
@SpringBootTest
class AreaContextDeleteRollbackTests(
	@Autowired private val contexts: ContextService,
	@Autowired private val areas: AreaService,
	@Autowired private val tasks: TaskService,
	@Autowired private val projects: ProjectService,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@MockitoSpyBean
	private lateinit var publisher: ChangeEventPublisher

	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("update project set next_task_id = null")
		jdbc.execute("delete from checklist_item")
		jdbc.execute("delete from task_context")
		jdbc.execute("delete from task")
		jdbc.execute("delete from project")
		jdbc.execute("delete from area")
		jdbc.execute("delete from context")
		jdbc.execute("delete from search_document")
	}

	@Test
	fun `a failure after replacing the context rolls back the replacement in every task`() {
		val home = contexts.create("@home", null, null).id
		val phone = contexts.create("@phone", null, null).id
		val visible = newTask("Call the plumber", home)
		val trashed = newTask("Water the plants", home)
		tasks.delete(trashed) {}
		val visibleVersion = versionOf("task", visible)
		val trashedVersion = versionOf("task", trashed)
		failOnDeleted("CONTEXT")

		shouldThrow<IllegalStateException> { contexts.delete(home, phone) {} }

		contextsOf(visible) shouldContainExactly listOf(home.toString())
		contextsOf(trashed) shouldContainExactly listOf(home.toString())
		versionOf("task", visible) shouldBe visibleVersion
		versionOf("task", trashed) shouldBe trashedVersion
		jdbc.queryForObject("select count(*) from context where id = ?", Long::class.java, home) shouldBe 1L
	}

	@Test
	fun `a failure after moving the projects rolls back the move`() {
		val home = areas.create("Home", null).id
		val family = areas.create("Family", null).id
		val visible = newProject("Renovate kitchen", home)
		val trashed = newProject("Paint the fence", home)
		projects.delete(trashed) {}
		val visibleVersion = versionOf("project", visible)
		val trashedVersion = versionOf("project", trashed)
		failOnDeleted("AREA")

		shouldThrow<IllegalStateException> { areas.delete(home, family) {} }

		areaOf(visible) shouldBe home.toString()
		areaOf(trashed) shouldBe home.toString()
		versionOf("project", visible) shouldBe visibleVersion
		versionOf("project", trashed) shouldBe trashedVersion
		jdbc.queryForObject("select count(*) from area where id = ?", Long::class.java, home) shouldBe 1L
	}

	/** Makes announcing the deletion of a context or area ([type]) fail, after the reassignment ran. */
	private fun failOnDeleted(type: String) {
		doThrow(IllegalStateException("fictitious event bus failure"))
			.`when`(publisher)
			.publish(eq(type) ?: type, anyId(), eq(ChangeOp.DELETED) ?: ChangeOp.DELETED)
	}

	private fun newTask(
		title: String,
		context: UUID,
	): UUID =
		tasks
			.create(
				null,
				TaskList.INBOX,
				TaskChanges(title = Change(title), contextIds = Change(setOf(context))),
			).task.id

	private fun newProject(
		title: String,
		area: UUID,
	): UUID = projects.create(ProjectChanges(title = Change(title), areaId = Change(area))).project.id

	private fun versionOf(
		table: String,
		id: UUID,
	): Long = jdbc.queryForObject("select version from $table where id = ?", Long::class.java, id)!!

	private fun contextsOf(task: UUID): List<String?> =
		jdbc.queryForList("select context_id::text from task_context where task_id = ?", String::class.java, task)

	private fun areaOf(project: UUID): String? =
		jdbc.queryForObject("select area_id::text from project where id = ?", String::class.java, project)
}

/** Mockito's `any()` for a parameter that must not be null in Kotlin: registers the matcher, returns a placeholder. */
private fun anyId(): UUID = any(UUID::class.java) ?: UUID.randomUUID()
