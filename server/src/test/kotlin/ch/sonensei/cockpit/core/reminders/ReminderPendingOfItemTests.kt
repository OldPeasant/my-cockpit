package ch.sonensei.cockpit.core.reminders

import ch.sonensei.cockpit.core.items.FakeItemResolvers
import ch.sonensei.cockpit.core.items.FakeItemResolversConfiguration
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.reminders.domain.ReminderService
import ch.sonensei.cockpit.core.reminders.infra.ReminderRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Duration

/** [ReminderService.pendingOfItem]: the not yet fired reminders of exactly one item, for modules managing their own. */
@SpringBootTest
@Import(FakeItemResolversConfiguration::class, MutableClockTestConfiguration::class)
class ReminderPendingOfItemTests(
	@Autowired private val service: ReminderService,
	@Autowired private val repository: ReminderRepository,
	@Autowired private val fakes: FakeItemResolvers,
	@Autowired private val clock: MutableClock,
) : PostgresTestBase() {
	private val start = MutableClockTestConfiguration.START

	@BeforeEach
	fun setUp() {
		repository.deleteAll()
		fakes.reset()
		clock.set(start)
	}

	/** Leaves no pending reminders behind that other test classes' firing runs would pick up. */
	@AfterEach
	fun cleanUp() {
		repository.deleteAll()
	}

	@Test
	fun `only reminders of the item that have not fired, earliest first`() {
		val task = fakes.tasks.add("Water the plants")
		val otherTask = fakes.tasks.add("Buy stamps")
		// A note sharing the task's id is a different item.
		val sameIdNote = fakes.notes.add("Plant notes", task.id)
		val late = service.create(task, start.plus(Duration.ofHours(5))).reminder
		val early = service.create(task, start.plus(Duration.ofHours(2))).reminder
		val fired = service.create(task, start.plus(Duration.ofMinutes(30))).reminder
		service.create(otherTask, start.plus(Duration.ofHours(1)))
		service.create(sameIdNote, start.plus(Duration.ofHours(1)))

		clock.advance(Duration.ofHours(1))
		service.fireDue() shouldBe 3

		service.pendingOfItem(task).map { it.id } shouldContainExactly listOf(early.id, late.id)
		service.pendingOfItem(task).map { it.id }.contains(fired.id) shouldBe false
		service.pendingOfItem(otherTask).shouldBeEmpty()
		service.pendingOfItem(sameIdNote).shouldBeEmpty()
	}

	@Test
	fun `reminders at the same time come in id order, an item without reminders has none`() {
		val task = fakes.tasks.add("Call the dentist")
		val at = start.plus(Duration.ofHours(3))
		val first = service.create(task, at).reminder
		val second = service.create(task, at).reminder

		service.pendingOfItem(task).map { it.id } shouldContainExactly listOf(first.id, second.id).sorted()
		service.pendingOfItem(fakes.tasks.add("Nothing planned")).shouldBeEmpty()
	}
}
