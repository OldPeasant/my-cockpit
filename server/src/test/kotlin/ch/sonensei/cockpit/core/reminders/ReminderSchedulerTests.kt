package ch.sonensei.cockpit.core.reminders

import ch.sonensei.cockpit.core.items.FakeItemResolvers
import ch.sonensei.cockpit.core.items.FakeItemResolversConfiguration
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.reminders.domain.ReminderService
import ch.sonensei.cockpit.core.reminders.infra.ReminderRepository
import ch.sonensei.cockpit.core.reminders.infra.ReminderScheduler
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import java.time.Duration

/** [ReminderScheduler.run] on its own: it drains full batches and survives a failing run. */
class ReminderSchedulerRunTests {
	@Test
	fun `run repeats while a whole batch fired and stops at the first partial batch`() {
		val service = mockk<ReminderService>()
		every { service.fireDue() } returnsMany listOf(ReminderService.FIRE_BATCH, ReminderService.FIRE_BATCH, 3)

		ReminderScheduler(service).run()

		verify(exactly = 3) { service.fireDue() }
	}

	@Test
	fun `run with nothing due fires once and stops`() {
		val service = mockk<ReminderService>()
		every { service.fireDue() } returns 0

		ReminderScheduler(service).run()

		verify(exactly = 1) { service.fireDue() }
	}

	@Test
	fun `a failing run is logged instead of thrown, so the next scheduled run retries`() {
		val service = mockk<ReminderService>()
		every { service.fireDue() } throws IllegalStateException("database unavailable") andThen 0

		val scheduler = ReminderScheduler(service)
		scheduler.run()
		scheduler.run()

		verify(exactly = 2) { service.fireDue() }
	}
}

/**
 * With the scheduler enabled (the test configuration disables it) and a short poll interval, due reminders fire
 * without anyone calling [ReminderService.fireDue]. The context is closed afterwards, so its scheduler does not keep
 * firing the reminders of later test classes that share the database.
 */
@SpringBootTest(
	properties = [
		"cockpit.reminders.scheduler.enabled=true",
		"cockpit.reminders.poll-interval=PT0.1S",
	],
)
@Import(FakeItemResolversConfiguration::class, MutableClockTestConfiguration::class)
@DirtiesContext
class ReminderSchedulerEnabledTests(
	@Autowired private val context: ApplicationContext,
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

	@Test
	fun `the background scheduler fires a due reminder once and leaves a future one pending`() {
		context.getBeansOfType(ReminderScheduler::class.java).values shouldHaveSize 1
		val task = fakes.tasks.add("Feed the fictitious cat")
		val due = service.create(task, start.minus(Duration.ofMinutes(1))).reminder
		val future = service.create(task, start.plus(Duration.ofHours(1))).reminder

		awaitUntil { repository.findById(due.id).get().firedAt != null }

		repository.findById(due.id).get().firedAt shouldBe start
		// Several more polls pass; the future reminder stays pending until the clock reaches it.
		Thread.sleep(300)
		repository.findById(future.id).get().firedAt shouldBe null
		clock.advance(Duration.ofHours(1))
		awaitUntil { repository.findById(future.id).get().firedAt != null }
		repository.findById(future.id).get().firedAt shouldBe start.plus(Duration.ofHours(1))
		// The first one kept its original firing time: it did not fire again.
		repository.findById(due.id).get().firedAt shouldBe start
	}

	private fun awaitUntil(condition: () -> Boolean) {
		val deadline = System.nanoTime() + 5_000_000_000
		while (!condition()) {
			check(System.nanoTime() < deadline) { "Condition not met in time" }
			Thread.sleep(20)
		}
	}
}

/** The test configuration turns the background scheduler off; [ReminderScheduler.run] still drains a backlog. */
@SpringBootTest
@Import(FakeItemResolversConfiguration::class, MutableClockTestConfiguration::class)
class ReminderSchedulerDisabledTests(
	@Autowired private val context: ApplicationContext,
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

	@Test
	fun `without the scheduler enabled no scheduler bean exists`() {
		context.getBeansOfType(ReminderScheduler::class.java).values shouldHaveSize 0
	}

	@Test
	fun `a backlog larger than one batch fires completely in one run`() {
		val task = fakes.tasks.add("Backlog item")
		repeat(ReminderService.FIRE_BATCH + 5) { service.create(task, start.minus(Duration.ofMinutes(it.toLong()))) }

		ReminderScheduler(service).run()

		repository.findAll().forEach { it.firedAt shouldNotBe null }
		service.fireDue() shouldBe 0
	}
}
