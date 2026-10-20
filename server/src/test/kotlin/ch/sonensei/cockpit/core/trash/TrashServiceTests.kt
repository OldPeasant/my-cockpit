package ch.sonensei.cockpit.core.trash

import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
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
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.scheduling.config.CronTask
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import java.time.Duration

@SpringBootTest
@Import(FakeTrashContributorsConfiguration::class, MutableClockTestConfiguration::class)
@RecordApplicationEvents
class TrashServiceTests(
	@Autowired private val trash: TrashService,
	@Autowired private val job: TrashPurgeJob,
	@Autowired private val fakes: FakeTrashContributors,
	@Autowired private val links: LinkRepository,
	@Autowired private val clock: MutableClock,
) : PostgresTestBase() {
	private val start = MutableClockTestConfiguration.START

	@BeforeEach
	fun setUp() {
		links.deleteAll()
		fakes.reset()
		clock.set(start)
	}

	private fun page(
		cursor: String? = null,
		limit: Int? = null,
	) = trash.list(CursorRequest.of(cursor, limit))

	@Test
	fun `lists deleted items of every contributor, most recently deleted first, with purge time`() {
		val oldTask = fakes.tasks.addDeleted("Old task", start.minus(Duration.ofDays(3)))
		val note = fakes.notes.addDeleted("Note", start.minus(Duration.ofDays(1)))
		val task = fakes.tasks.addDeleted("Task", start.minus(Duration.ofDays(2)))
		fakes.tasks.addActive("Still active")

		val result = page()

		result.items.map { it.ref } shouldContainExactly listOf(note, task, oldTask)
		result.nextCursor.shouldBeNull()
		result.items.first().purgeAt shouldBe start.minus(Duration.ofDays(1)).plus(Duration.ofDays(30))
		result.items
			.first()
			.item.title shouldBe "Note"
	}

	@Test
	fun `pages through the Trash with a cursor, ties broken by type and id`() {
		val at = start.minus(Duration.ofHours(1))
		val all =
			List(3) { fakes.tasks.addDeleted("Task $it", at) } + List(2) { fakes.notes.addDeleted("Note $it", at) } +
				fakes.notes.addDeleted("Newest", start)
		val seen = mutableListOf<ItemRef>()
		var cursor: String? = null
		do {
			val result = page(cursor, 2)
			result.items.size shouldBe (if (result.nextCursor == null) result.items.size else 2)
			seen += result.items.map { it.ref }
			cursor = result.nextCursor
		} while (cursor != null)

		seen shouldContainExactlyInAnyOrder all
		seen.distinct().size shouldBe all.size
		seen.first().type shouldBe ItemType.NOTE
		seen.first() shouldBe all.last()
		// Same deletedAt: by type in ItemType order (TASK before NOTE), then id descending.
		seen.subList(1, 4) shouldContainExactly all.subList(0, 3).sortedByDescending { it.id }
		seen.subList(4, 6) shouldContainExactly all.subList(3, 5).sortedByDescending { it.id }
	}

	@Test
	fun `a malformed cursor is rejected`() {
		shouldThrow<InvalidRequestException> { page(CursorRequest.encodeCursor("nonsense")) }
		shouldThrow<InvalidRequestException> { page(CursorRequest.encodeCursor("2026-01-01T00:00:00Z|NOPE|${UuidV7.generate()}")) }
	}

	@Test
	fun `a failing contributor is left out of the list`() {
		val note = fakes.notes.addDeleted("Note", start)
		fakes.tasks.addDeleted("Task", start)
		fakes.tasks.failing = true

		page().items.map { it.ref } shouldContainExactly listOf(note)
	}

	@Test
	fun `restore takes the item out of the Trash, keeps its links and announces an update`(events: ApplicationEvents) {
		val task = fakes.tasks.addDeleted("Task", start)
		val other = ItemRef(ItemType.NOTE, UuidV7.generate())
		links.save(Link(task, other, null, LinkOrigin.MANUAL))

		trash.restore(task)

		fakes.tasks.isActive(task.id) shouldBe true
		page().items.shouldBeEmpty()
		links.findTouching(task.type, task.id).size shouldBe 1
		events.stream(ChangeEvent::class.java).toList().single().let {
			it.type shouldBe "TASK"
			it.id shouldBe task.id
			it.op shouldBe ChangeOp.UPDATED
		}
	}

	@Test
	fun `restore and purge of an item that is not in the Trash give 404`() {
		val active = fakes.tasks.addActive("Active")
		val unknown = ItemRef(ItemType.TASK, UuidV7.generate())
		val noContributor = ItemRef(ItemType.EVENT, UuidV7.generate())

		for (ref in listOf(active, unknown, noContributor)) {
			shouldThrow<NotFoundException> { trash.restore(ref) }
			shouldThrow<NotFoundException> { trash.purge(ref) }
		}
		fakes.tasks.isActive(active.id) shouldBe true
		fakes.tasks.purged.shouldBeEmpty()
	}

	@Test
	fun `purge deletes the item and its links in both directions and announces the deletion`(events: ApplicationEvents) {
		val task = fakes.tasks.addDeleted("Task", start)
		val note = ItemRef(ItemType.NOTE, UuidV7.generate())
		val unrelated = ItemRef(ItemType.NOTE, UuidV7.generate())
		links.save(Link(task, note, null, LinkOrigin.MANUAL))
		links.save(Link(note, task, null, LinkOrigin.SYNC))
		links.save(Link(note, unrelated, null, LinkOrigin.MANUAL))

		trash.purge(task)

		fakes.tasks.purged shouldContainExactly listOf(task.id)
		fakes.tasks.isDeleted(task.id) shouldBe false
		links.findTouching(task.type, task.id).shouldBeEmpty()
		links.findTouching(unrelated.type, unrelated.id).size shouldBe 1
		events
			.stream(ChangeEvent::class.java)
			.toList()
			.single()
			.op shouldBe ChangeOp.DELETED
	}

	@Test
	fun `a failing purge keeps the item's links`() {
		val task = fakes.tasks.addDeleted("Task", start)
		links.save(Link(task, ItemRef(ItemType.NOTE, UuidV7.generate()), null, LinkOrigin.MANUAL))
		fakes.tasks.failingPurge = task.id

		shouldThrow<IllegalStateException> { trash.purge(task) }

		links.findTouching(task.type, task.id).size shouldBe 1
	}

	@Test
	fun `the job purges items deleted more than 30 days ago, by the test clock`() {
		val task = fakes.tasks.addDeleted("Task", start)
		val note = fakes.notes.addDeleted("Note", start.plus(Duration.ofDays(1)))

		clock.set(start.plus(Duration.ofDays(30)))
		job.purgeExpired() shouldBe 0
		page().items.size shouldBe 2

		clock.advance(Duration.ofNanos(1000))
		job.purgeExpired() shouldBe 1
		fakes.tasks.purged shouldContainExactly listOf(task.id)
		page().items.map { it.ref } shouldContainExactly listOf(note)

		clock.advance(Duration.ofDays(2))
		job.purgeExpired() shouldBe 1
		page().items.shouldBeEmpty()
	}

	@Test
	fun `the job removes the links of purged items`() {
		val task = fakes.tasks.addDeleted("Task", start)
		links.save(Link(task, ItemRef(ItemType.NOTE, UuidV7.generate()), null, LinkOrigin.MANUAL))

		clock.advance(Duration.ofDays(31))
		job.purgeExpired() shouldBe 1

		links.findTouching(task.type, task.id).shouldBeEmpty()
	}

	@Test
	fun `a failing item does not stop the job, it is retried on the next run`() {
		val broken = fakes.tasks.addDeleted("Broken", start)
		val fine = fakes.tasks.addDeleted("Fine", start)
		val note = fakes.notes.addDeleted("Note", start)
		fakes.tasks.failingPurge = broken.id
		clock.advance(Duration.ofDays(31))

		job.purgeExpired() shouldBe 2
		fakes.tasks.purged shouldContainExactly listOf(fine.id)
		fakes.notes.purged shouldContainExactly listOf(note.id)
		page().items.map { it.ref } shouldContainExactly listOf(broken)

		fakes.tasks.failingPurge = null
		job.purgeExpired() shouldBe 1
		page().items.shouldBeEmpty()
	}

	@Test
	fun `the job is scheduled daily`(
		@Autowired holders: List<ScheduledTaskHolder>,
	) {
		val task =
			holders
				.flatMap { it.scheduledTasks }
				.map { it.task }
				.filterIsInstance<CronTask>()
				.singleOrNull { it.toString().endsWith("TrashPurgeJob.purgeExpired") }
		task.shouldNotBeNull().expression shouldBe "0 30 3 * * *"
	}
}
