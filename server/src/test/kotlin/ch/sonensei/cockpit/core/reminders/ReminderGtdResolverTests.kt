package ch.sonensei.cockpit.core.reminders

import ch.sonensei.cockpit.core.events.domain.ClientNotification
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.reminders.api.ReminderResponse
import ch.sonensei.cockpit.core.reminders.domain.ReminderService
import ch.sonensei.cockpit.core.reminders.infra.ReminderRepository
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.gtd.domain.Project
import ch.sonensei.cockpit.gtd.domain.Task
import ch.sonensei.cockpit.gtd.domain.TaskList
import ch.sonensei.cockpit.gtd.infra.ProjectRepository
import ch.sonensei.cockpit.gtd.infra.TaskRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

/**
 * Reminders with the real `TASK` resolver of the gtd module (a `@Transactional` proxy), no fakes: the resolvers run in
 * their own transactions ([ch.sonensei.cockpit.core.reminders.domain.ReminderItems]), so these check that they still
 * see committed items, the Trash and items created in the caller's transaction.
 */
@SpringBootTest
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
class ReminderGtdResolverTests(
	@Autowired private val service: ReminderService,
	@Autowired private val repository: ReminderRepository,
	@Autowired private val tasks: TaskRepository,
	@Autowired private val projects: ProjectRepository,
	@Autowired private val clock: MutableClock,
	@Autowired private val transactions: TransactionTemplate,
) : PostgresTestBase() {
	private val start = MutableClockTestConfiguration.START

	@Autowired
	private lateinit var events: ApplicationEvents

	@BeforeEach
	fun setUp() {
		repository.deleteAll()
		clock.set(start)
	}

	private fun reminderNotifications() =
		events
			.stream(ClientNotification::class.java)
			.filter { it.name == "reminder" }
			.map { it.data as ReminderResponse }
			.toList()

	private fun ref(task: Task) = ItemRef(ItemType.TASK, task.id)

	@Test
	fun `a reminder of a real task fires once with the task title and deep link`() {
		val task = tasks.save(Task("Water the fictitious plants", TaskList.NEXT))
		val created = service.create(ref(task), start.plus(Duration.ofMinutes(30)))
		created.item?.title shouldBe "Water the fictitious plants"

		clock.advance(Duration.ofMinutes(30))
		service.fireDue() shouldBe 1
		service.fireDue() shouldBe 0

		val fired = reminderNotifications().single()
		fired.id shouldBe created.reminder.id
		fired.item.available shouldBe true
		fired.item.title shouldBe "Water the fictitious plants"
		fired.item.deepLink shouldBe "/tasks/${task.id}"
		repository.findById(created.reminder.id).get().firedAt shouldBe start.plus(Duration.ofMinutes(30))
	}

	@Test
	fun `a reminder of a task moved to the Trash is marked fired without notification`() {
		val task = tasks.save(Task("Cancelled errand", TaskList.NEXT))
		val created = service.create(ref(task), start).reminder
		transactions.execute {
			tasks.findById(task.id).get().softDelete(clock)
		}

		service.get(created.id).item shouldBe null
		service.fireDue() shouldBe 1

		repository.findById(created.id).get().firedAt shouldNotBe null
		reminderNotifications().shouldBeEmpty()
		service.upcoming(start.plus(Duration.ofDays(1))).shouldBeEmpty()
	}

	@Test
	fun `a reminder can be created in the transaction that creates its task and resolves after the commit`() {
		val (task, reminderId) =
			transactions.execute {
				val task = tasks.save(Task("Created together", TaskList.INBOX))
				task to service.create(ref(task), start.plus(Duration.ofHours(1))).reminder.id
			}

		service.get(reminderId).item?.title shouldBe "Created together"
		service.ofItem(ref(task)).map { it.reminder.id } shouldBe listOf(reminderId)
	}

	@Test
	fun `a firing batch resolves each real task in its own transaction while the reminders stay locked`() {
		val titles = (1..3).map { "Fictitious chore $it" }
		titles.forEach { service.create(ref(tasks.save(Task(it, TaskList.NEXT))), start) }

		service.fireDue() shouldBe 3

		reminderNotifications().map { it.item.title }.toSet() shouldBe titles.toSet()
		repository.findAll().all { it.firedAt == start } shouldBe true
	}

	@Test
	fun `creating a reminder for a missing or trashed real task is rejected`() {
		val trashed = tasks.save(Task("Already in the Trash", TaskList.NEXT))
		transactions.execute {
			tasks.findById(trashed.id).get().softDelete(clock)
		}

		shouldThrow<InvalidRequestException> { service.create(ItemRef(ItemType.TASK, UuidV7.generate()), start) }
		shouldThrow<InvalidRequestException> { service.create(ref(trashed), start) }

		repository.findAll().shouldBeEmpty()
	}

	@Test
	fun `a reminder of a real project fires with the project title and deep link`() {
		val project = projects.save(Project("Plan the fictitious offsite"))
		val created = service.create(ItemRef(ItemType.PROJECT, project.id), start)
		created.item?.deepLink shouldBe "/projects/${project.id}"

		service.fireDue() shouldBe 1

		val fired = reminderNotifications().single()
		fired.item.title shouldBe "Plan the fictitious offsite"
		fired.item.deepLink shouldBe "/projects/${project.id}"
	}

	@Test
	fun `listings resolve tasks and projects together and keep reminders of a trashed task unavailable`() {
		val task = tasks.save(Task("Fictitious listing task", TaskList.NEXT))
		val project = projects.save(Project("Fictitious listing project"))
		val gone = tasks.save(Task("Trashed listing task", TaskList.NEXT))
		service.create(ref(task), start.plus(Duration.ofMinutes(1)))
		service.create(ItemRef(ItemType.PROJECT, project.id), start.plus(Duration.ofMinutes(2)))
		service.create(ref(gone), start.plus(Duration.ofMinutes(3)))
		service.create(ref(task), start.plus(Duration.ofMinutes(4)))
		transactions.execute {
			tasks.findById(gone.id).get().softDelete(clock)
		}

		service.upcoming(start.plus(Duration.ofHours(1))).map { it.item?.title } shouldBe
			listOf("Fictitious listing task", "Fictitious listing project", null, "Fictitious listing task")
	}
}
