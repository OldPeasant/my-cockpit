package ch.sonensei.cockpit.notes

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.trash.domain.TrashContributorRegistry
import ch.sonensei.cockpit.notes.domain.FieldChange
import ch.sonensei.cockpit.notes.domain.NoteChanges
import ch.sonensei.cockpit.notes.domain.NoteService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Revision restore under concurrency and the bookkeeping of kept revisions (task notes-api, criterion 3), after the fix
 * that flushes the changed note before the revision insert (a concurrent change of the same version gives an
 * optimistic locking error, 409, instead of a duplicate revision number, 500). Also the search document a Trash
 * restore writes (criterion 4).
 */
@SpringBootTest
@Import(MutableClockTestConfiguration::class)
class NoteRevisionInfraTests(
	@Autowired private val service: NoteService,
	@Autowired private val trashContributors: TrashContributorRegistry,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("delete from note")
		jdbc.execute("delete from link")
		jdbc.execute("delete from search_document")
		clock.set(MutableClockTestConfiguration.START)
	}

	private fun create(body: String) = service.create(NoteChanges(bodyMd = FieldChange(body)))

	private fun changeBody(
		id: UUID,
		body: String,
	) = service.update(id, NoteChanges(bodyMd = FieldChange(body))) {}

	private fun revisionBodies(id: UUID) = service.revisions(id).map { it.number to it.bodyMd }

	/** Runs [changes] in parallel threads that all passed the version check before either writes. */
	private fun concurrently(vararg changes: (CyclicBarrier) -> Unit): List<Throwable?> {
		val barrier = CyclicBarrier(changes.size)
		val pool = Executors.newFixedThreadPool(changes.size)
		try {
			val futures = changes.map { change -> pool.submit { change(barrier) } }
			return futures.map {
				try {
					it.get(30, TimeUnit.SECONDS)
					null
				} catch (e: ExecutionException) {
					e.cause
				}
			}
		} finally {
			pool.shutdownNow()
		}
	}

	private fun restoreAfterBarrier(
		id: UUID,
		number: Int,
		version: Long,
	): (CyclicBarrier) -> Unit =
		{ barrier ->
			service.restoreRevision(id, number) { current ->
				current shouldBe version
				barrier.await(10, TimeUnit.SECONDS)
			}
		}

	@Test
	fun `two concurrent restores of the same version - one wins, the other fails with an optimistic locking error`() {
		val note = create("original")
		clock.advance(Duration.ofMinutes(10))
		val changed = changeBody(note.id, "second")

		val restore = restoreAfterBarrier(note.id, 1, changed.version)
		val outcomes = concurrently(restore, restore)

		outcomes.count { it == null } shouldBe 1
		outcomes.filterNotNull().single().shouldBeInstanceOf<OptimisticLockingFailureException>()
		val current = service.get(note.id)
		current.bodyMd shouldBe "original"
		current.version shouldBe changed.version + 1
		revisionBodies(note.id) shouldContainExactly listOf(2 to "second", 1 to "original")
	}

	@Test
	fun `a restore and a body change of the same version - one wins, the other fails with an optimistic locking error`() {
		val note = create("original")
		clock.advance(Duration.ofMinutes(10))
		val changed = changeBody(note.id, "second")
		// Both writers would keep a revision (number 2): the newest one is older than five minutes.
		clock.advance(Duration.ofMinutes(10))

		val outcomes =
			concurrently(
				restoreAfterBarrier(note.id, 1, changed.version),
				{ barrier ->
					service.update(note.id, NoteChanges(bodyMd = FieldChange("third"))) {
						barrier.await(10, TimeUnit.SECONDS)
					}
				},
			)

		outcomes.count { it == null } shouldBe 1
		outcomes.filterNotNull().single().shouldBeInstanceOf<OptimisticLockingFailureException>()
		val current = service.get(note.id)
		current.version shouldBe changed.version + 1
		current.bodyMd shouldBeIn listOf("original", "third")
		revisionBodies(note.id) shouldContainExactly listOf(2 to "second", 1 to "original")
	}

	@Test
	fun `a kept revision records when the replaced content was saved, not when it was replaced`() {
		val note = create("original")
		clock.advance(Duration.ofMinutes(10))
		val changed = changeBody(note.id, "second")
		val secondSavedAt = changed.updatedAt
		clock.advance(Duration.ofMinutes(1))

		service.restoreRevision(note.id, 1) {}

		val kept = service.revisions(note.id)
		kept.map { it.number } shouldContainExactly listOf(2, 1)
		kept[0].savedAt shouldBe secondSavedAt
		kept[0].savedAt shouldBe MutableClockTestConfiguration.START.plus(Duration.ofMinutes(10))
		kept[1].savedAt shouldBe MutableClockTestConfiguration.START
		service.get(note.id).updatedAt shouldBe MutableClockTestConfiguration.START.plus(Duration.ofMinutes(11))
	}

	@Test
	fun `restoring the oldest of 20 kept revisions puts it back and still keeps the newest 20`() {
		val note = create("v0")
		repeat(20) {
			clock.advance(Duration.ofMinutes(10))
			changeBody(note.id, "v${it + 1}")
		}
		service.revisions(note.id).map { it.number } shouldBe (20 downTo 1).toList()

		val restored = service.restoreRevision(note.id, 1) {}

		restored.bodyMd shouldBe "v0"
		val kept = service.revisions(note.id)
		kept.size shouldBe 20
		kept.first().number to kept.first().bodyMd shouldBe (21 to "v20")
		kept.last().number shouldBe 2
	}

	@Test
	fun `a note restored from the Trash is indexed with its body as plain text`() {
		val note = create("# Garden\nPlant **tulips** in [autumn](https://example.org)")
		service.delete(note.id) {}
		jdbc.queryForObject("select count(*) from search_document where item_id = ?", Long::class.java, note.id) shouldBe 0

		trashContributors.find(ItemType.NOTE)!!.restore(note.id) shouldBe true

		val document =
			jdbc.queryForMap(
				"select title, body from search_document where item_type = 'NOTE' and item_id = ?",
				note.id,
			)
		document["title"] shouldBe "Garden"
		document["body"] shouldBe "Garden\nPlant tulips in autumn"
	}
}
