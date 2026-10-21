package ch.sonensei.cockpit.gtd.convert

import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.Link
import ch.sonensei.cockpit.core.items.domain.LinkOrigin
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.items.infra.LinkRepository
import ch.sonensei.cockpit.gtd.convert.domain.TaskToNoteService
import ch.sonensei.cockpit.gtd.domain.Change
import ch.sonensei.cockpit.gtd.domain.TaskChanges
import ch.sonensei.cockpit.gtd.domain.TaskList
import ch.sonensei.cockpit.gtd.domain.TaskService
import ch.sonensei.cockpit.journal.habits.domain.HabitChanges
import ch.sonensei.cockpit.journal.habits.domain.HabitSchedule
import ch.sonensei.cockpit.journal.habits.domain.HabitService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.util.UUID
import ch.sonensei.cockpit.journal.habits.domain.Change as HabitField

/** The conversion is one transaction: a failure after the task was trashed and the note created undoes both. */
@SpringBootTest
class TaskToNoteRollbackTests(
	@Autowired private val converter: TaskToNoteService,
	@Autowired private val tasks: TaskService,
	@Autowired private val linkRepository: LinkRepository,
	@Autowired private val habitService: HabitService,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@MockitoSpyBean
	private lateinit var links: LinkService

	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("delete from task")
		jdbc.execute("delete from note")
		jdbc.execute("delete from link")
		jdbc.execute("delete from habit_check")
		jdbc.execute("delete from habit")
		jdbc.execute("delete from search_document")
	}

	@Test
	fun `a failing link copy rolls back the note and the task deletion`() {
		val task = tasks.create(null, TaskList.INBOX, TaskChanges(title = Change("File the receipt"))).task.id
		linkRepository.save(Link(ItemRef(ItemType.TASK, task), ItemRef(ItemType.HABIT, newHabit()), null, LinkOrigin.MANUAL))
		doThrow(IllegalStateException("link store down")).`when`(links).create(anyRef(), anyRef(), any())

		shouldThrow<IllegalStateException> { converter.convert(task) {} }

		jdbc.queryForObject("select deleted_at from task where id = ?", Any::class.java, task) shouldBe null
		jdbc.queryForObject("select count(*) from note", Long::class.java) shouldBe 0L
	}

	/** A habit that exists: HABIT has a resolver, so a link to a missing habit would be dropped. */
	private fun newHabit(): UUID =
		habitService.create(HabitChanges(name = HabitField("Stretch"), schedule = HabitField(HabitSchedule.DAILY))).habit.id
}

/** Mockito's `any()` for a parameter that must not be null in Kotlin: registers the matcher, returns a placeholder. */
private fun anyRef(): ItemRef = any(ItemRef::class.java) ?: ItemRef(ItemType.NOTE, UUID.randomUUID())
