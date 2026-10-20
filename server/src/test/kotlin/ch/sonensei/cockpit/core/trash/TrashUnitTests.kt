package ch.sonensei.cockpit.core.trash

import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemDeletedEvent
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.trash.domain.TrashContributor
import ch.sonensei.cockpit.core.trash.domain.TrashContributorRegistry
import ch.sonensei.cockpit.core.trash.domain.TrashPurgeJob
import ch.sonensei.cockpit.core.trash.domain.TrashService
import ch.sonensei.cockpit.core.trash.domain.TrashedItem
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.ApplicationEventPublisher
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Edge cases of the Trash without Spring or a database: registry, ordering, cursor robustness, events, purge job. */
class TrashUnitTests {
	private val start: Instant = Instant.parse("2026-03-01T08:00:00Z")
	private val clock = MutableClock(start)
	private val tasks = FakeTrashContributor(ItemType.TASK)
	private val notes = FakeTrashContributor(ItemType.NOTE)
	private val published = mutableListOf<Any>()
	private val publisher = ApplicationEventPublisher { published += it }

	private fun service(vararg contributors: TrashContributor = arrayOf(tasks, notes)) =
		TrashService(TrashContributorRegistry(contributors.toList()), publisher, ChangeEventPublisher(publisher, clock))

	private val trash = service()
	private val job = TrashPurgeJob(trash, clock)

	private fun page(
		cursor: String? = null,
		limit: Int? = null,
	) = trash.list(CursorRequest.of(cursor, limit))

	private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

	/** A contributor whose list is a stale snapshot: the item is listed but already gone when it is purged or restored. */
	private class StaleContributor(
		override val type: ItemType,
		private val listed: List<TrashedItem>,
	) : TrashContributor {
		override fun listDeleted() = listed

		override fun restore(id: UUID) = false

		override fun purge(id: UUID) = false
	}

	// Registry

	@Test
	fun `the registry refuses two contributors for the same type`() {
		val error =
			shouldThrow<IllegalStateException> {
				TrashContributorRegistry(listOf(tasks, notes, FakeTrashContributor(ItemType.TASK)))
			}
		error.message.shouldNotBeNull() shouldContain "More than one TrashContributor for TASK"
	}

	@Test
	fun `the registry finds the contributor of a type and nothing for types without one`() {
		val registry = TrashContributorRegistry(listOf(tasks, notes))

		registry.find(ItemType.TASK) shouldBe tasks
		registry.find(ItemType.NOTE) shouldBe notes
		registry.find(ItemType.EVENT).shouldBeNull()
		registry.all() shouldContainExactlyInAnyOrder listOf(tasks, notes)
		TrashContributorRegistry(emptyList()).all().shouldBeEmpty()
	}

	// Listing and cursor

	@Test
	fun `an empty Trash is one empty page`() {
		val result = page()

		result.items.shouldBeEmpty()
		result.nextCursor.shouldBeNull()
	}

	@Test
	fun `a page that holds exactly the remaining items has no next cursor`() {
		List(3) { tasks.addDeleted("Task $it", start.minusSeconds(it.toLong())) }

		page(limit = 3).nextCursor.shouldBeNull()
		page(limit = 2).nextCursor.shouldNotBeNull()
	}

	@Test
	fun `ties on deletedAt and type are ordered by id descending, across pages of one`() {
		val refs = listOf(1, 3, 2).map { tasks.addDeleted("Task $it", start, id(it)) }
		val seen = mutableListOf<UUID>()
		var cursor: String? = null
		do {
			val result = page(cursor, 1)
			seen += result.items.map { it.item.id }
			cursor = result.nextCursor
		} while (cursor != null)

		seen shouldContainExactly listOf(id(3), id(2), id(1))
		seen.size shouldBe refs.size
	}

	@Test
	fun `the cursor of the last item gives an empty page`() {
		tasks.addDeleted("Only", start)
		val last = page().items.single()
		val cursor = CursorRequest.encodeCursor("${last.item.deletedAt}|${last.type.name}|${last.item.id}")

		val result = page(cursor)

		result.items.shouldBeEmpty()
		result.nextCursor.shouldBeNull()
	}

	@Test
	fun `paging continues correctly when the cursor's item is restored between pages`() {
		val newest = tasks.addDeleted("Newest", start)
		val middle = notes.addDeleted("Middle", start.minusSeconds(60))
		val oldest = tasks.addDeleted("Oldest", start.minusSeconds(120))

		val first = page(limit = 2)
		first.items.map { it.ref } shouldContainExactly listOf(newest, middle)
		trash.restore(middle)

		val second = page(first.nextCursor, 2)

		second.items.map { it.ref } shouldContainExactly listOf(oldest)
		second.nextCursor.shouldBeNull()
	}

	@Test
	fun `an item deleted while paging does not shift the following pages`() {
		val a = tasks.addDeleted("A", start.minusSeconds(10))
		val b = tasks.addDeleted("B", start.minusSeconds(20))
		val c = tasks.addDeleted("C", start.minusSeconds(30))

		val first = page(limit = 1)
		notes.addDeleted("Newly deleted", start)
		val rest = page(first.nextCursor, 10)

		first.items.map { it.ref } shouldContainExactly listOf(a)
		rest.items.map { it.ref } shouldContainExactly listOf(b, c)
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			"not-an-instant|TASK|00000000-0000-7000-8000-000000000001",
			"2026-03-01T08:00:00Z|TASK|not-a-uuid",
			"2026-03-01T08:00:00Z|task|00000000-0000-7000-8000-000000000001",
			"2026-03-01T08:00:00Z|TASK",
			"2026-03-01T08:00:00Z|TASK|00000000-0000-7000-8000-000000000001|extra",
			"||",
			"|",
		],
	)
	fun `a cursor with a well-encoded but invalid position is rejected as a bad request`(position: String) {
		tasks.addDeleted("Task", start)

		val error = shouldThrow<InvalidRequestException> { page(CursorRequest.encodeCursor(position)) }
		error.fieldErrors.map { it.field } shouldContainExactly listOf("cursor")
	}

	@Test
	fun `purgeAt is exactly 30 days after deletedAt`() {
		tasks.addDeleted("Task", start)

		page().items.single().purgeAt shouldBe Instant.parse("2026-03-31T08:00:00Z")
	}

	// Restore and purge events

	@Test
	fun `restore announces an update at the clock's time and no deletion`() {
		val task = tasks.addDeleted("Task", start.minus(Duration.ofDays(1)))

		trash.restore(task)

		published shouldContainExactly listOf(ChangeEvent(ItemType.TASK, task.id, ChangeOp.UPDATED, start))
	}

	@Test
	fun `purge removes links first, then announces the deletion`() {
		val note = notes.addDeleted("Note", start)

		trash.purge(note)

		published shouldContainExactly
			listOf(ItemDeletedEvent(note), ChangeEvent(ItemType.NOTE, note.id, ChangeOp.DELETED, start))
	}

	@Test
	fun `a restore or purge that finds nothing publishes no event`() {
		val stale = StaleContributor(ItemType.TASK, listOf(TrashedItem(id(1), "Gone", start, icon = "task_alt")))
		val trash = service(stale)
		val ref = ItemRef(ItemType.TASK, id(1))

		shouldThrow<NotFoundException> { trash.restore(ref) }
		shouldThrow<NotFoundException> { trash.purge(ref) }

		published.shouldBeEmpty()
	}

	@Test
	fun `a contributor failing on restore or purge propagates its error and publishes nothing`() {
		val task = tasks.addDeleted("Task", start)
		tasks.failing = true

		shouldThrow<IllegalStateException> { trash.restore(task) }
		shouldThrow<IllegalStateException> { trash.purge(task) }

		published.shouldBeEmpty()
		tasks.isDeleted(task.id) shouldBe true
	}

	// Purge job

	@Test
	fun `the job keeps an item deleted exactly 30 days ago and purges it one nanosecond later`() {
		val task = tasks.addDeleted("Task", start)
		clock.set(start.plus(TrashService.RETENTION))

		job.purgeExpired() shouldBe 0
		tasks.isDeleted(task.id) shouldBe true

		clock.advance(Duration.ofNanos(1))
		job.purgeExpired() shouldBe 1
		tasks.purged shouldContainExactly listOf(task.id)
	}

	@Test
	fun `the job does nothing on an empty Trash`() {
		clock.advance(Duration.ofDays(365))

		job.purgeExpired() shouldBe 0
		published.shouldBeEmpty()
	}

	@Test
	fun `the job still purges other modules when one contributor fails to list`() {
		tasks.addDeleted("Task", start)
		val note = notes.addDeleted("Note", start)
		tasks.failing = true
		clock.advance(Duration.ofDays(31))

		job.purgeExpired() shouldBe 1

		notes.purged shouldContainExactly listOf(note.id)
		tasks.purged.shouldBeEmpty()
	}

	@Test
	fun `the job skips an item that left the Trash between listing and purging`() {
		val stale = StaleContributor(ItemType.TASK, listOf(TrashedItem(id(1), "Restored meanwhile", start, icon = "task_alt")))
		val note = notes.addDeleted("Note", start)
		val trash = service(stale, notes)
		clock.advance(Duration.ofDays(31))

		TrashPurgeJob(trash, clock).purgeExpired() shouldBe 1

		notes.purged shouldContainExactly listOf(note.id)
		published.filterIsInstance<ItemDeletedEvent>() shouldContainExactly listOf(ItemDeletedEvent(note))
	}

	@Test
	fun `the job leaves recent items alone`() {
		tasks.addDeleted("Recent", start.minus(Duration.ofDays(29)))
		val old = notes.addDeleted("Old", start.minus(Duration.ofDays(45)))

		job.purgeExpired() shouldBe 1

		page().items.map { it.item.title } shouldContainExactly listOf("Recent")
		notes.purged shouldContainExactly listOf(old.id)
	}
}
