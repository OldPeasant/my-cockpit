package ch.sonensei.cockpit.core.trash

import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.Link
import ch.sonensei.cockpit.core.items.domain.LinkOrigin
import ch.sonensei.cockpit.core.items.infra.LinkRepository
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.trash.domain.TrashPurgeJob
import ch.sonensei.cockpit.core.trash.domain.TrashService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import java.time.Duration

/**
 * Purge removes the item's links and announces the deletion in one transaction: when something after the link removal
 * fails, the links are rolled back (they are real rows in Postgres).
 */
@SpringBootTest
@Import(
	FakeTrashContributorsConfiguration::class,
	MutableClockTestConfiguration::class,
	TrashTransactionTests.SabotageConfiguration::class,
)
class TrashTransactionTests(
	@Autowired private val trash: TrashService,
	@Autowired private val job: TrashPurgeJob,
	@Autowired private val fakes: FakeTrashContributors,
	@Autowired private val links: LinkRepository,
	@Autowired private val clock: MutableClock,
	@Autowired private val sabotage: ChangeEventSabotage,
) : PostgresTestBase() {
	private val start = MutableClockTestConfiguration.START

	/** Fails the announcement of a change while [failing], i.e. after the links were already deleted. */
	class ChangeEventSabotage {
		@Volatile
		var failing = false

		@EventListener
		fun on(event: ChangeEvent) {
			check(!failing) { "announcement failed for ${event.type}" }
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	class SabotageConfiguration {
		@Bean
		fun changeEventSabotage() = ChangeEventSabotage()
	}

	@BeforeEach
	fun setUp() {
		links.deleteAll()
		fakes.reset()
		clock.set(start)
		sabotage.failing = false
	}

	private fun linkTo(ref: ItemRef) = links.save(Link(ref, ItemRef(ItemType.NOTE, UuidV7.generate()), null, LinkOrigin.MANUAL))

	@Test
	fun `a purge whose announcement fails rolls back the removal of the links`() {
		val task = fakes.tasks.addDeleted("Task", start)
		linkTo(task)
		sabotage.failing = true

		shouldThrow<IllegalStateException> { trash.purge(task) }

		links.findTouching(task.type, task.id).size shouldBe 1
	}

	@Test
	fun `in the job a failing purge rolls back only that item, the others commit`() {
		val first = fakes.tasks.addDeleted("First", start)
		val second = fakes.notes.addDeleted("Second", start)
		linkTo(first)
		linkTo(second)
		clock.advance(Duration.ofDays(31))
		fakes.tasks.failingPurge = first.id

		job.purgeExpired() shouldBe 1

		links.findTouching(first.type, first.id).size shouldBe 1
		links.findTouching(second.type, second.id).shouldBeEmpty()
	}
}
