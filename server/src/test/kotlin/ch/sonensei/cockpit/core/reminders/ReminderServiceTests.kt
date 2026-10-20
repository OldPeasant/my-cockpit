package ch.sonensei.cockpit.core.reminders

import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.events.domain.ClientNotification
import ch.sonensei.cockpit.core.items.FakeItemResolvers
import ch.sonensei.cockpit.core.items.FakeItemResolversConfiguration
import ch.sonensei.cockpit.core.items.domain.ItemDeletedEvent
import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.reminders.api.ReminderResponse
import ch.sonensei.cockpit.core.reminders.domain.ReminderService
import ch.sonensei.cockpit.core.reminders.infra.ReminderRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration

@SpringBootTest
@Import(FakeItemResolversConfiguration::class, MutableClockTestConfiguration::class)
@RecordApplicationEvents
class ReminderServiceTests(
	@Autowired private val service: ReminderService,
	@Autowired private val repository: ReminderRepository,
	@Autowired private val fakes: FakeItemResolvers,
	@Autowired private val clock: MutableClock,
	@Autowired private val resolvers: ItemResolverRegistry,
	@Autowired private val changes: ChangeEventPublisher,
	@Autowired private val publisher: ApplicationEventPublisher,
	@Autowired private val transactions: TransactionTemplate,
) : PostgresTestBase() {
	private val start = MutableClockTestConfiguration.START

	@Autowired
	private lateinit var events: ApplicationEvents

	@BeforeEach
	fun setUp() {
		repository.deleteAll()
		fakes.reset()
		clock.set(start)
	}

	private fun reminderNotifications() =
		events
			.stream(ClientNotification::class.java)
			.filter { it.name == "reminder" }
			.map { it.data as ReminderResponse }
			.toList()

	@Test
	fun `a due reminder fires once with the resolved item title`() {
		val task = fakes.tasks.add("Call plumber")
		val created = service.create(task, start.plus(Duration.ofHours(1))).reminder

		service.fireDue() shouldBe 0
		clock.advance(Duration.ofHours(1))
		service.fireDue() shouldBe 1
		service.fireDue() shouldBe 0

		val fired = reminderNotifications()
		fired shouldHaveSize 1
		fired.single().id shouldBe created.id
		fired.single().item.title shouldBe "Call plumber"
		fired.single().item.deepLink shouldBe "/task/${task.id}"
		fired.single().firedAt shouldBe start.plus(Duration.ofHours(1))
		repository.findById(created.id).get().firedAt shouldBe start.plus(Duration.ofHours(1))
	}

	@Test
	fun `a fired reminder does not fire again after a restart`() {
		val task = fakes.tasks.add("Renew passport")
		service.create(task, start)
		service.fireDue() shouldBe 1

		// A new service instance has no in-memory state, like the service after a server restart.
		val restarted = ReminderService(repository, resolvers, changes, publisher, clock as Clock)
		clock.advance(Duration.ofDays(1))
		transactions.execute { restarted.fireDue() } shouldBe 0
		reminderNotifications() shouldHaveSize 1
	}

	@Test
	fun `upcoming lists pending reminders up to the given time, overdue ones included`() {
		val task = fakes.tasks.add("Buy milk")
		val overdue = service.create(task, start.minus(Duration.ofHours(1))).reminder
		val soon = service.create(task, start.plus(Duration.ofHours(2))).reminder
		service.create(task, start.plus(Duration.ofDays(3)))

		service.upcoming(start.plus(Duration.ofDays(1))).map { it.reminder.id } shouldContainExactly listOf(overdue.id, soon.id)
		service.fireDue() shouldBe 1
		service.upcoming(start.plus(Duration.ofDays(1))).map { it.reminder.id } shouldContainExactly listOf(soon.id)
	}

	@Test
	fun `a reminder of an item that is gone is marked fired without notification`() {
		val task = fakes.tasks.add("Gone soon")
		val created = service.create(task, start).reminder
		fakes.tasks.remove(task.id)

		service.fireDue() shouldBe 1
		reminderNotifications().shouldBeEmpty()
		repository.findById(created.id).get().firedAt shouldBe start
	}

	@Test
	fun `changes publish REMINDER change events and a permanently deleted item loses its reminders`() {
		val task = fakes.tasks.add("Plan holiday")
		val created = service.create(task, start.plus(Duration.ofDays(1))).reminder
		transactions.execute { publisher.publishEvent(ItemDeletedEvent(task)) }

		repository.findAll().shouldBeEmpty()
		events
			.stream(ChangeEvent::class.java)
			.filter { it.type == ReminderService.TYPE }
			.map { it.op to it.id }
			.toList() shouldContainExactly listOf(ChangeOp.CREATED to created.id, ChangeOp.DELETED to created.id)
	}
}
