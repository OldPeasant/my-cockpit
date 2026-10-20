package ch.sonensei.cockpit.core.items

import ch.sonensei.cockpit.core.items.domain.ItemDeletedEvent
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemResolver
import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.Link
import ch.sonensei.cockpit.core.items.domain.LinkOrigin
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import ch.sonensei.cockpit.core.items.infra.LinkRepository
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * Edge cases of [LinkService] and the `link` table beyond the main scenarios in [LinkServiceTests]. Same context
 * configuration as [LinkWebTests], so the link tests share one cached Spring context (and its connection pool).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeItemResolversConfiguration::class)
class LinkServiceEdgeCaseTests(
	@Autowired private val service: LinkService,
	@Autowired private val repository: LinkRepository,
	@Autowired private val fakes: FakeItemResolvers,
	@Autowired private val events: ApplicationEventPublisher,
	@Autowired private val tx: TransactionTemplate,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val entityManager: EntityManager,
) : PostgresTestBase() {
	private lateinit var task: ItemRef
	private lateinit var noteA: ItemRef
	private lateinit var noteB: ItemRef

	@BeforeEach
	fun setUp() {
		repository.deleteAll()
		fakes.reset()
		task = fakes.tasks.add("Water the plants")
		noteA = fakes.notes.add("Garden plan")
		noteB = fakes.notes.add("Seed list")
	}

	private fun linkedTo(item: ItemRef) = service.linksOf(item).map { if (it.link.source == item) it.link.target else it.link.source }

	@Test
	fun `sync links targets that are missing or have no resolver and lists them as unavailable`() {
		val goneNote = ItemRef(ItemType.NOTE, UuidV7.generate())
		val habit = ItemRef(ItemType.HABIT, UuidV7.generate())

		service.sync(noteA, listOf(goneNote, habit, task))

		service.linksOf(noteA).associate { it.link.target to it.target.available } shouldContainExactly
			mapOf(goneNote to false, habit to false, task to true)
	}

	@Test
	fun `sync with unchanged targets keeps the existing links`() {
		service.sync(noteA, listOf(task, noteB))
		val before = repository.findAll().map { it.id }.toSet()

		service.sync(noteA, listOf(noteB, task))

		repository.findAll().map { it.id }.toSet() shouldBe before
	}

	@Test
	fun `sync of a source without a resolver works`() {
		val bookmark = ItemRef(ItemType.BOOKMARK, UuidV7.generate())

		service.sync(bookmark, listOf(task))

		linkedTo(task) shouldBe listOf(bookmark)
		service
			.linksOf(task)
			.single()
			.source.available shouldBe false
	}

	@Test
	fun `a pair both ends sync stays linked while one end still wants it`() {
		// Two notes that mention each other inline: each owns its own SYNC link to the other.
		service.sync(noteA, listOf(noteB))
		service.sync(noteB, listOf(noteA))
		linkedTo(noteA) shouldBe listOf(noteB)
		linkedTo(noteB) shouldBe listOf(noteA)

		// A no longer mentions B, but B still does: its inline link must stay persisted (SPEC.md §4.3).
		service.sync(noteA, emptyList())

		linkedTo(noteB) shouldBe listOf(noteA)
		linkedTo(noteA) shouldBe listOf(noteB)
		service
			.linksOf(noteA)
			.single()
			.link.source shouldBe noteB

		service.sync(noteB, emptyList())
		service.linksOf(noteA).shouldBeEmpty()
	}

	@Test
	fun `a pair with a manual and a sync link is listed once, as the manual link`() {
		service.sync(noteA, listOf(task))
		val manual = service.create(task, noteA, "origin")

		service.linksOf(task).map { it.link.id } shouldBe listOf(manual.link.id)
		service.linksOf(noteA).map { it.link.id } shouldBe listOf(manual.link.id)

		// Deleting the manual link leaves the inline one.
		service.delete(manual.link.id)

		val remaining = service.linksOf(noteA).single()
		remaining.link.origin shouldBe LinkOrigin.SYNC
		remaining.link.target shouldBe task
	}

	@Test
	fun `a manual link next to an existing sync link is allowed, a second manual one is not`() {
		service.sync(noteA, listOf(task))
		service.sync(task, listOf(noteA))

		service.create(noteA, task, null).link.origin shouldBe LinkOrigin.MANUAL

		shouldThrow<ConflictException> { service.create(task, noteA, null) }
		repository.findAll() shouldHaveSize 3
	}

	@Test
	fun `linksOf orders by the shown link, oldest first`() {
		service.sync(task, listOf(noteA))
		service.sync(task, listOf(noteA, noteB))
		// The manual link to noteA is newer than the sync link to noteB, so noteA moves behind it.
		service.create(noteA, task, null)

		linkedTo(task) shouldBe listOf(noteB, noteA)
		service.linksOf(task).map { it.link.origin } shouldBe listOf(LinkOrigin.SYNC, LinkOrigin.MANUAL)
	}

	@Test
	fun `a pair with sync links in both directions and no manual link is listed once, as the oldest link`() {
		service.sync(noteA, listOf(noteB))
		val older = repository.findBySourceTypeAndSourceIdAndOrigin(noteA.type, noteA.id, LinkOrigin.SYNC).single()
		service.sync(noteB, listOf(noteA))

		repository.findAll() shouldHaveSize 2
		service.linksOf(noteA).map { it.link.id } shouldBe listOf(older.id)
		service.linksOf(noteB).map { it.link.id } shouldBe listOf(older.id)
	}

	@Test
	fun `ItemDeletedEvent removes manual and sync links of the item in both directions`() {
		service.create(task, noteA, null)
		service.sync(noteA, listOf(task, noteB))
		service.sync(task, listOf(noteA))
		service.sync(noteB, listOf(task))
		val unrelated = repository.findBySourceTypeAndSourceIdAndOrigin(noteA.type, noteA.id, LinkOrigin.SYNC).single { it.target == noteB }

		tx.executeWithoutResult { events.publishEvent(ItemDeletedEvent(task)) }

		repository.findAll().map { it.id } shouldBe listOf(unrelated.id)
		service.linksOf(task).shouldBeEmpty()
		linkedTo(noteA) shouldBe listOf(noteB)
	}

	@Test
	fun `ItemDeletedEvent keeps the entities the publisher loaded managed`() {
		val kept = service.create(task, noteA, null)

		tx.executeWithoutResult {
			val loaded = repository.findById(kept.link.id).orElseThrow()
			events.publishEvent(ItemDeletedEvent(noteB))
			entityManager.contains(loaded) shouldBe true
		}

		repository.findAll().map { it.id } shouldBe listOf(kept.link.id)
	}

	@Test
	fun `links of an item in the Trash stay and become available again on restore`() {
		val noteId = noteA.id
		service.create(task, noteA, null)
		fakes.notes.remove(noteId)

		val whileTrashed = service.linksOf(noteA).single()
		whileTrashed.source.available shouldBe true
		whileTrashed.target.available shouldBe false

		fakes.notes.add("Garden plan", noteId)
		service
			.linksOf(task)
			.single()
			.target.item
			?.title shouldBe "Garden plan"
	}

	@Test
	fun `ItemDeletedEvent of an item without links leaves other links alone`() {
		service.create(task, noteA, null)

		tx.executeWithoutResult { events.publishEvent(ItemDeletedEvent(noteB)) }

		repository.findAll() shouldHaveSize 1
	}

	@Test
	fun `ItemDeletedEvent matches type and id, not only the id`() {
		val sharedId = UuidV7.generate()
		val taskWithId = fakes.tasks.add("Repot cactus", sharedId)
		val noteWithId = fakes.notes.add("Cactus care", sharedId)
		service.create(taskWithId, noteA, null)
		service.create(noteWithId, noteB, null)

		tx.executeWithoutResult { events.publishEvent(ItemDeletedEvent(taskWithId)) }

		service.linksOf(taskWithId).shouldBeEmpty()
		linkedTo(noteWithId) shouldBe listOf(noteB)
	}

	@Test
	fun `linksOf distinguishes items of different types with the same id`() {
		val sharedId = UuidV7.generate()
		val taskWithId = fakes.tasks.add("Repot cactus", sharedId)
		val noteWithId = fakes.notes.add("Cactus care", sharedId)
		service.create(taskWithId, noteWithId, null)
		service.create(noteWithId, noteB, null)

		linkedTo(taskWithId) shouldBe listOf(noteWithId)
		linkedTo(noteWithId) shouldContainExactlyInAnyOrder listOf(taskWithId, noteB)
	}

	@Test
	fun `linksOf resolves each distinct end once`() {
		val counting = CountingResolver(ItemType.NOTE)
		val center = ItemRef(ItemType.NOTE, UuidV7.generate())
		val others = List(3) { ItemRef(ItemType.NOTE, UuidV7.generate()) }
		others.forEach { repository.saveAndFlush(Link(center, it, null, LinkOrigin.MANUAL)) }
		val countingService = LinkService(repository, ItemResolverRegistry(listOf(counting)))

		countingService.linksOf(center) shouldHaveSize 3

		counting.calls[center.id] shouldBe 1
		others.forEach { counting.calls[it.id] shouldBe 1 }
	}

	@Test
	fun `the database rejects a self link`() {
		shouldThrow<DataIntegrityViolationException> {
			repository.saveAndFlush(Link(task, task, null, LinkOrigin.SYNC))
		}
	}

	@Test
	fun `the database rejects an unknown origin`() {
		val now = Instant.parse("2026-10-20T09:00:00Z")
		shouldThrow<DataIntegrityViolationException> {
			jdbc.update(
				"insert into link (id, created_at, updated_at, version, source_type, source_id, target_type, target_id, origin) " +
					"values (?, ?::timestamptz, ?::timestamptz, 0, 'TASK', ?, 'NOTE', ?, 'IMPORTED')",
				UuidV7.generate(),
				now.toString(),
				now.toString(),
				task.id,
				noteA.id,
			)
		}
	}

	/** Resolves every id to an item and counts how often each id was asked for. */
	private class CountingResolver(
		override val type: ItemType,
	) : ItemResolver {
		val calls = HashMap<UUID, Int>()

		override fun resolve(id: UUID): ResolvedItem {
			calls.merge(id, 1, Int::plus)
			return ResolvedItem("Note $id", icon = "description", deepLink = "/notes/$id")
		}
	}
}
