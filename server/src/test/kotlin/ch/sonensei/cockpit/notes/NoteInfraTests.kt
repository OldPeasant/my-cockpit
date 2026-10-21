package ch.sonensei.cockpit.notes

import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.trash.domain.TrashContributorRegistry
import ch.sonensei.cockpit.notes.domain.FieldChange
import ch.sonensei.cockpit.notes.domain.NoteChanges
import ch.sonensei.cockpit.notes.domain.NoteService
import ch.sonensei.cockpit.notes.infra.NoteItemResolver
import ch.sonensei.cockpit.notes.infra.NoteTrashContributor
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
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
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The NOTE resolver and Trash contributor called directly (task notes-api, criterion 4), and concurrent changes of the
 * same version, which optimistic locking must reject instead of losing one of them.
 */
@SpringBootTest
@Import(MutableClockTestConfiguration::class)
class NoteInfraTests(
	@Autowired private val service: NoteService,
	@Autowired private val resolvers: ItemResolverRegistry,
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

	private val resolver get() = resolvers.find(ItemType.NOTE)!!
	private val trash get() = trashContributors.find(ItemType.NOTE)!!

	private fun create(
		title: String? = null,
		body: String = "",
		pinned: Boolean = false,
		archived: Boolean = false,
	) = service.create(
		NoteChanges(
			title = title?.let(::FieldChange),
			bodyMd = FieldChange(body),
			pinned = FieldChange(pinned),
			archived = FieldChange(archived),
		),
	)

	private fun searchTitle(id: UUID): String? =
		jdbc
			.queryForList("select title from search_document where item_type = 'NOTE' and item_id = ?", String::class.java, id)
			.singleOrNull()

	// --- resolver

	@Test
	fun `the NOTE resolver and Trash contributor are registered`() {
		resolver.shouldBeInstanceOf<NoteItemResolver>()
		trash.shouldBeInstanceOf<NoteTrashContributor>()
	}

	@Test
	fun `a note resolves to its display title, state subtitle, icon and deep link`() {
		val plain = create(body = "## Weekly review\nsteps")
		val pinned = create(title = "Pinned idea", pinned = true)
		val archived = create(title = "Old idea", archived = true)
		val empty = create()

		resolver.resolve(plain.id) shouldBe ResolvedItem("Weekly review", "Note", "description", "/notes/${plain.id}")
		resolver.resolve(pinned.id) shouldBe ResolvedItem("Pinned idea", "Note", "push_pin", "/notes/${pinned.id}")
		resolver.resolve(archived.id) shouldBe ResolvedItem("Old idea", "Archived note", "description", "/notes/${archived.id}")
		resolver.resolve(empty.id)?.title shouldBe "Untitled note"
		resolver.exists(plain.id) shouldBe true
	}

	@Test
	fun `missing and trashed notes do not resolve`() {
		val note = create(title = "Gone soon")
		service.delete(note.id) {}

		resolver.resolve(note.id).shouldBeNull()
		resolver.exists(note.id) shouldBe false
		resolver.resolve(UuidV7.generate()).shouldBeNull()
	}

	// --- Trash contributor

	@Test
	fun `the Trash lists deleted notes with their display title and deletion time`() {
		create(title = "Still active")
		val deleted = create(body = "# Derived title\nbody")
		service.delete(deleted.id) {}

		val listed = trash.listDeleted().single()

		listed.id shouldBe deleted.id
		listed.title shouldBe "Derived title"
		listed.deletedAt shouldBe MutableClockTestConfiguration.START
		listed.subtitle shouldBe "Note"
		listed.icon shouldBe "description"
	}

	@Test
	fun `restore and purge refuse notes that are not in the Trash`() {
		val active = create(title = "Active")

		trash.restore(active.id) shouldBe false
		trash.purge(active.id) shouldBe false
		trash.restore(UuidV7.generate()) shouldBe false
		trash.purge(UuidV7.generate()) shouldBe false
		service.get(active.id).title shouldBe "Active"
	}

	@Test
	fun `a restored note is active and indexed again, a purged one is gone`() {
		val kept = create(title = "Kept")
		val purged = create(title = "Purged")
		service.delete(kept.id) {}
		service.delete(purged.id) {}
		searchTitle(kept.id).shouldBeNull()

		trash.restore(kept.id) shouldBe true
		trash.purge(purged.id) shouldBe true

		service.get(kept.id).title shouldBe "Kept"
		searchTitle(kept.id) shouldBe "Kept"
		trash.listDeleted().shouldBeEmpty()
		jdbc.queryForObject("select count(*) from note where id = ?", Long::class.java, purged.id) shouldBe 0
		trash.restore(kept.id) shouldBe false
	}

	// --- optimistic locking

	/** Runs [change] in two threads that both passed the version check before either writes. */
	private fun concurrently(change: (CyclicBarrier) -> Unit): List<Throwable?> {
		val barrier = CyclicBarrier(2)
		val pool = Executors.newFixedThreadPool(2)
		try {
			val futures = (1..2).map { pool.submit { change(barrier) } }
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

	@Test
	fun `two concurrent changes of the same version - one wins, the other fails with an optimistic locking error`() {
		val note = create(title = "Shared")

		val outcomes =
			concurrently { barrier ->
				service.update(note.id, NoteChanges(pinned = FieldChange(true))) { version ->
					version shouldBe note.version
					barrier.await(10, TimeUnit.SECONDS)
				}
			}

		outcomes.count { it == null } shouldBe 1
		outcomes.filterNotNull().single().shouldBeInstanceOf<OptimisticLockingFailureException>()
		service.get(note.id).version shouldBe note.version + 1
	}

	@Test
	fun `two concurrent body changes of the same version keep one change and at most one revision`() {
		val note = create(body = "original")
		clock.advance(java.time.Duration.ofMinutes(10))

		val outcomes =
			concurrently { barrier ->
				service.update(note.id, NoteChanges(bodyMd = FieldChange("edit ${Thread.currentThread().name}"))) {
					barrier.await(10, TimeUnit.SECONDS)
				}
			}

		outcomes.count { it == null } shouldBe 1
		outcomes.filterNotNull().single().shouldBeInstanceOf<OptimisticLockingFailureException>()
		service.revisions(note.id).map { it.bodyMd } shouldBe listOf("original")
	}

	@Test
	fun `a failed version check changes nothing`() {
		val note = create(body = "original")
		clock.advance(java.time.Duration.ofMinutes(10))

		shouldThrow<IllegalStateException> {
			service.update(note.id, NoteChanges(bodyMd = FieldChange("changed"))) { error("stale") }
		}

		service.get(note.id).bodyMd shouldBe "original"
		service.revisions(note.id).shouldBeEmpty()
	}
}
