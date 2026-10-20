package ch.sonensei.cockpit.core.reminders

import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.events.domain.ClientNotification
import ch.sonensei.cockpit.core.items.FakeItemResolvers
import ch.sonensei.cockpit.core.items.FakeItemResolversConfiguration
import ch.sonensei.cockpit.core.items.domain.ItemDeletedEvent
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.reminders.api.ReminderResponse
import ch.sonensei.cockpit.core.reminders.domain.Reminder
import ch.sonensei.cockpit.core.reminders.domain.ReminderChannel
import ch.sonensei.cockpit.core.reminders.domain.ReminderService
import ch.sonensei.cockpit.core.reminders.infra.ReminderRepository
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@SpringBootTest
@Import(FakeItemResolversConfiguration::class, MutableClockTestConfiguration::class)
@RecordApplicationEvents
class ReminderServiceEdgeCaseTests(
	@Autowired private val service: ReminderService,
	@Autowired private val repository: ReminderRepository,
	@Autowired private val fakes: FakeItemResolvers,
	@Autowired private val clock: MutableClock,
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

	private fun reminderChanges() =
		events
			.stream(ChangeEvent::class.java)
			.filter { it.type == ReminderService.TYPE }
			.map { it.op to it.id }
			.toList()

	@Test
	fun `a reminder fires exactly at its time, not a microsecond before`() {
		val task = fakes.tasks.add("Exact time")
		service.create(task, start.plus(Duration.ofMinutes(10)))

		clock.set(start.plus(Duration.ofMinutes(10)).minusNanos(1_000))
		service.fireDue() shouldBe 0
		clock.set(start.plus(Duration.ofMinutes(10)))
		service.fireDue() shouldBe 1
	}

	@Test
	fun `remindAt is stored at microsecond precision`() {
		val task = fakes.tasks.add("Precise")
		val created = service.create(task, Instant.parse("2026-03-01T09:00:00.123456789Z")).reminder

		repository.findById(created.id).get().remindAt shouldBe Instant.parse("2026-03-01T09:00:00.123456Z")
	}

	@Test
	fun `an overdue reminder from while the server was down fires once on the next run`() {
		val task = fakes.tasks.add("Missed while offline")
		val created = service.create(task, start.plus(Duration.ofMinutes(5))).reminder

		clock.advance(Duration.ofDays(2))
		service.fireDue() shouldBe 1
		clock.advance(Duration.ofDays(2))
		service.fireDue() shouldBe 0

		reminderNotifications().map { it.id } shouldContainExactly listOf(created.id)
		repository.findById(created.id).get().firedAt shouldBe start.plus(Duration.ofDays(2))
	}

	@Test
	fun `a failing resolver still sends the notification, without a title`() {
		val task = fakes.tasks.add("Resolver breaks")
		val created = service.create(task, start).reminder
		fakes.tasks.failing = true

		service.fireDue() shouldBe 1

		val fired = reminderNotifications().single()
		fired.id shouldBe created.id
		fired.item.available shouldBe false
		fired.item.title shouldBe null
		fired.item.id shouldBe task.id
	}

	@Test
	fun `a reminder of an item type without a resolver fires with a notification without a title`() {
		val contact = ItemRef(ItemType.CONTACT, UuidV7.generate())
		val created = service.create(contact, start).reminder

		service.fireDue() shouldBe 1

		val fired = reminderNotifications().single()
		fired.id shouldBe created.id
		fired.item.type shouldBe ItemType.CONTACT
		fired.item.available shouldBe false
	}

	@Test
	fun `creating a reminder for an item its resolver does not know is rejected`() {
		shouldThrow<InvalidRequestException> { service.create(ItemRef(ItemType.TASK, UuidV7.generate()), start) }
		repository.findAll().shouldBeEmpty()
		reminderChanges().shouldBeEmpty()
	}

	@Test
	fun `get and delete of an unknown reminder are not found`() {
		shouldThrow<NotFoundException> { service.get(UuidV7.generate()) }
		shouldThrow<NotFoundException> { service.delete(UuidV7.generate()) }
	}

	@Test
	fun `create, fire and delete publish CREATED, UPDATED and DELETED change events`() {
		val task = fakes.tasks.add("Lifecycle")
		val created = service.create(task, start).reminder
		service.fireDue()
		service.delete(created.id)

		reminderChanges() shouldContainExactly
			listOf(ChangeOp.CREATED to created.id, ChangeOp.UPDATED to created.id, ChangeOp.DELETED to created.id)
	}

	@Test
	fun `a deleted reminder never fires`() {
		val task = fakes.tasks.add("Cancelled")
		val created = service.create(task, start.plus(Duration.ofMinutes(1))).reminder
		service.delete(created.id)

		clock.advance(Duration.ofHours(1))
		service.fireDue() shouldBe 0
		reminderNotifications().shouldBeEmpty()
	}

	@Test
	fun `deleting an item removes only its reminders and an item without reminders publishes nothing`() {
		val task = fakes.tasks.add("Deleted for good")
		val other = fakes.tasks.add("Stays")
		val first = service.create(task, start.plus(Duration.ofDays(1))).reminder
		val second = service.create(task, start.plus(Duration.ofDays(2))).reminder
		val kept = service.create(other, start.plus(Duration.ofDays(1))).reminder

		transactions.execute { publisher.publishEvent(ItemDeletedEvent(task)) }
		transactions.execute { publisher.publishEvent(ItemDeletedEvent(fakes.notes.add("No reminders"))) }

		repository.findAll().map { it.id } shouldContainExactly listOf(kept.id)
		reminderChanges().filter { it.first == ChangeOp.DELETED }.map { it.second }.toSet() shouldBe setOf(first.id, second.id)
		reminderChanges() shouldHaveSize 5
	}

	@Test
	fun `upcoming returns at most MAX_UPCOMING reminders, earliest first`() {
		val task = fakes.tasks.add("Many reminders")
		repository.saveAll(
			(0..ReminderService.MAX_UPCOMING).map {
				Reminder(task, start.plus(Duration.ofMinutes(it.toLong())), ReminderChannel.NOTIFICATION)
			},
		)

		val upcoming = service.upcoming(start.plus(Duration.ofDays(30)))

		upcoming shouldHaveSize ReminderService.MAX_UPCOMING
		upcoming.first().reminder.remindAt shouldBe start
		upcoming.last().reminder.remindAt shouldBe start.plus(Duration.ofMinutes(ReminderService.MAX_UPCOMING - 1L))
	}

	@Test
	fun `fireDue fires at most one batch per call, earliest first`() {
		val task = fakes.tasks.add("Big backlog")
		repository.saveAll(
			(1..ReminderService.FIRE_BATCH + 1).map {
				Reminder(task, start.minus(Duration.ofMinutes(it.toLong())), ReminderChannel.NOTIFICATION)
			},
		)

		service.fireDue() shouldBe ReminderService.FIRE_BATCH
		// The latest one is left for the next call.
		service
			.upcoming(start)
			.single()
			.reminder.remindAt shouldBe start.minus(Duration.ofMinutes(1))
		service.fireDue() shouldBe 1
		service.fireDue() shouldBe 0
	}

	@Test
	fun `a reminder another transaction is firing is skipped instead of waited for or fired twice`() {
		val task = fakes.tasks.add("Two schedulers")
		service.create(task, start)
		val locked = CountDownLatch(1)
		val release = CountDownLatch(1)

		// A first "scheduler" fires the reminder and keeps its transaction open.
		val first =
			CompletableFuture.supplyAsync {
				transactions.execute {
					val fired = service.fireDue()
					locked.countDown()
					release.await(10, TimeUnit.SECONDS)
					fired
				}
			}
		locked.await(10, TimeUnit.SECONDS) shouldBe true

		// A second one runs meanwhile: SKIP LOCKED makes it return at once without the locked row.
		val second = CompletableFuture.supplyAsync { service.fireDue() }
		try {
			second.get(5, TimeUnit.SECONDS) shouldBe 0
		} finally {
			release.countDown()
		}
		first.get(10, TimeUnit.SECONDS) shouldBe 1
		service.fireDue() shouldBe 0
	}

	@Test
	fun `a reminder cannot be marked fired twice`() {
		val reminder = Reminder(ItemRef(ItemType.TASK, UuidV7.generate()), start, ReminderChannel.NOTIFICATION)
		reminder.markFired(start)

		shouldThrow<IllegalStateException> { reminder.markFired(start.plusSeconds(1)) }
		reminder.firedAt shouldBe start
	}
}
