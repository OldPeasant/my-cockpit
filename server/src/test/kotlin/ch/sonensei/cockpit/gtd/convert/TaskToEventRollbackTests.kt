package ch.sonensei.cockpit.gtd.convert

import ch.sonensei.cockpit.calendar.api.CalendarService
import ch.sonensei.cockpit.calendar.api.TimingInput
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.Link
import ch.sonensei.cockpit.core.items.domain.LinkOrigin
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.items.infra.LinkRepository
import ch.sonensei.cockpit.gtd.convert.domain.TaskToEventService
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
import java.time.Instant
import java.util.UUID
import ch.sonensei.cockpit.journal.habits.domain.Change as HabitField

/** The conversion is one transaction: a failure after the task was trashed and the event created undoes both. */
@SpringBootTest
class TaskToEventRollbackTests(
	@Autowired private val converter: TaskToEventService,
	@Autowired private val tasks: TaskService,
	@Autowired private val calendars: CalendarService,
	@Autowired private val linkRepository: LinkRepository,
	@Autowired private val habitService: HabitService,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@MockitoSpyBean
	private lateinit var links: LinkService

	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("update project set next_task_id = null")
		jdbc.execute("delete from task")
		jdbc.execute("delete from calendar_source")
		jdbc.execute("delete from link")
		jdbc.execute("delete from habit_check")
		jdbc.execute("delete from habit")
		jdbc.execute("delete from search_document")
		jdbc.execute("delete from user_settings")
	}

	@Test
	fun `a failing link copy rolls back the event, the task deletion and the search index`() {
		val calendar = calendars.create("Personal", null, visible = true, isDefault = false).calendar.id
		val task = tasks.create(null, TaskList.INBOX, TaskChanges(title = Change("Call the plumber"))).task.id
		val taskRef = ItemRef(ItemType.TASK, task)
		linkRepository.save(Link(taskRef, ItemRef(ItemType.HABIT, newHabit()), null, LinkOrigin.MANUAL))
		doThrow(IllegalStateException("link store down")).`when`(links).create(anyItemRef(), anyItemRef(), any())

		shouldThrow<IllegalStateException> {
			converter.convert(task, calendar, TimingInput(start = Instant.parse("2026-11-03T08:30:00Z"))) {}
		}

		jdbc.queryForObject("select deleted_at from task where id = ?", Any::class.java, task) shouldBe null
		jdbc.queryForObject("select count(*) from calendar_event", Long::class.java) shouldBe 0L
		jdbc.queryForObject("select count(*) from search_document where item_type = 'EVENT'", Long::class.java) shouldBe 0L
		jdbc.queryForObject(
			"select count(*) from search_document where item_type = 'TASK' and item_id = ?",
			Long::class.java,
			task,
		) shouldBe 1L
		linkRepository.findTouching(ItemType.TASK, task).size shouldBe 1
	}

	/** A habit that exists: HABIT has a resolver, so a link to a missing habit would be dropped. */
	private fun newHabit(): UUID =
		habitService.create(HabitChanges(name = HabitField("Stretch"), schedule = HabitField(HabitSchedule.DAILY))).habit.id
}

/** Mockito's `any()` for a parameter that must not be null in Kotlin: registers the matcher, returns a placeholder. */
private fun anyItemRef(): ItemRef = any(ItemRef::class.java) ?: ItemRef(ItemType.EVENT, UUID.randomUUID())
