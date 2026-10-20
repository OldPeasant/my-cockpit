package ch.sonensei.cockpit.core.items

import ch.sonensei.cockpit.core.items.domain.ItemDeletedEvent
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.Link
import ch.sonensei.cockpit.core.items.domain.LinkOrigin
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.items.infra.LinkRepository
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.support.TransactionTemplate

// Same context configuration as LinkWebTests, so the link tests share one cached Spring context (and its
// connection pool) instead of starting another one against the shared Postgres container.
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeItemResolversConfiguration::class)
class LinkServiceTests(
	@Autowired private val service: LinkService,
	@Autowired private val repository: LinkRepository,
	@Autowired private val fakes: FakeItemResolvers,
	@Autowired private val events: ApplicationEventPublisher,
	@Autowired private val tx: TransactionTemplate,
) : PostgresTestBase() {
	private lateinit var task: ItemRef
	private lateinit var otherTask: ItemRef
	private lateinit var note: ItemRef

	@BeforeEach
	fun setUp() {
		repository.deleteAll()
		fakes.reset()
		task = fakes.tasks.add("Call plumber")
		otherTask = fakes.tasks.add("Plan holiday")
		note = fakes.notes.add("Holiday ideas")
	}

	private fun targetsOf(source: ItemRef) = service.linksOf(source).filter { it.link.source == source }.map { it.link.target }

	@Test
	fun `create stores a manual link with both ends resolved`() {
		val created = service.create(task, note, "  origin  ")

		created.link.source shouldBe task
		created.link.target shouldBe note
		created.link.label shouldBe "origin"
		created.link.origin shouldBe LinkOrigin.MANUAL
		created.source.item?.title shouldBe "Call plumber"
		created.target.item?.deepLink shouldBe "/note/${note.id}"
		repository.findAll() shouldHaveSize 1
	}

	@Test
	fun `a blank label is stored as none`() {
		service
			.create(task, note, "   ")
			.link.label
			.shouldBeNull()
	}

	@Test
	fun `linksOf returns links in both directions, oldest first`() {
		val outgoing = service.create(task, note, null)
		val incoming = service.create(otherTask, task, null)
		service.create(otherTask, note, null)

		service.linksOf(task).map { it.link.id } shouldBe listOf(outgoing.link.id, incoming.link.id)
		service.linksOf(note).map { it.link.id } shouldHaveSize 2
		service.linksOf(ItemRef(ItemType.TASK, UuidV7.generate())).shouldBeEmpty()
	}

	@Test
	fun `an end without a resolver or whose item is gone is unavailable, not an error`() {
		val habit = ItemRef(ItemType.HABIT, UuidV7.generate())
		service.create(task, habit, null).target.available shouldBe false
		service.create(task, note, null)
		fakes.notes.remove(note.id)

		val ends = service.linksOf(task).associate { it.link.target to it.target }
		ends.getValue(habit).available shouldBe false
		ends.getValue(note).available shouldBe false
		ends.getValue(note).item.shouldBeNull()
		service.linksOf(task).forEach { it.source.available shouldBe true }
	}

	@Test
	fun `a failing resolver marks its ends unavailable`() {
		service.create(task, note, null)
		fakes.notes.failing = true

		val link = service.linksOf(task).single()
		link.target.available shouldBe false
		link.source.available shouldBe true
	}

	@Test
	fun `self links are rejected`() {
		val error = shouldThrow<InvalidRequestException> { service.create(task, task, null) }
		error.fieldErrors.single().field shouldBe "target"
	}

	@Test
	fun `an end its resolver does not know is rejected`() {
		val missing = ItemRef(ItemType.NOTE, UuidV7.generate())
		shouldThrow<InvalidRequestException> { service.create(task, missing, null) }.fieldErrors.single().field shouldBe "target"
		shouldThrow<InvalidRequestException> { service.create(missing, task, null) }.fieldErrors.single().field shouldBe "source"
		repository.findAll().shouldBeEmpty()
	}

	@Test
	fun `a too long label is rejected`() {
		shouldThrow<InvalidRequestException> { service.create(task, note, "x".repeat(LinkService.MAX_LABEL_LENGTH + 1)) }
		service
			.create(task, note, "x".repeat(LinkService.MAX_LABEL_LENGTH))
			.link.label
			?.length shouldBe 200
	}

	@Test
	fun `a pair is linked at most once, in either direction`() {
		service.create(task, note, null)

		shouldThrow<ConflictException> { service.create(task, note, null) }
		shouldThrow<ConflictException> { service.create(note, task, null) }
		repository.findAll() shouldHaveSize 1
	}

	@Test
	fun `the database rejects a reversed manual duplicate`() {
		service.create(task, note, null)

		shouldThrow<DataIntegrityViolationException> {
			repository.saveAndFlush(
				Link(note, task, null, LinkOrigin.MANUAL),
			)
		}
	}

	@Test
	fun `the database rejects a duplicate sync link in the same direction only`() {
		service.sync(note, listOf(task))

		shouldThrow<DataIntegrityViolationException> {
			repository.saveAndFlush(Link(note, task, null, LinkOrigin.SYNC))
		}
	}

	@Test
	fun `the database allows sync links in both directions next to a manual link`() {
		service.create(task, note, null)

		repository.saveAndFlush(Link(note, task, null, LinkOrigin.SYNC))
		repository.saveAndFlush(Link(task, note, null, LinkOrigin.SYNC))

		repository.findAll() shouldHaveSize 3
	}

	@Test
	fun `delete removes the link from both ends`() {
		val link = service.create(task, note, null)

		service.delete(link.link.id)

		service.linksOf(task).shouldBeEmpty()
		service.linksOf(note).shouldBeEmpty()
		shouldThrow<NotFoundException> { service.delete(link.link.id) }
	}

	@Test
	fun `delete rejects a sync link and keeps it`() {
		service.sync(note, listOf(task))
		val inline = repository.findAll().single()

		shouldThrow<ConflictException> { service.delete(inline.id) }

		service.linksOf(task).map { it.link.id } shouldBe listOf(inline.id)
	}

	@Test
	fun `sync replaces the links a source owns and keeps manual ones`() {
		val manual = service.create(note, task, "manual")
		val third = fakes.tasks.add("Buy milk")

		service.sync(note, listOf(otherTask, third, otherTask, note))
		targetsOf(note) shouldContainExactlyInAnyOrder listOf(task, otherTask, third)
		repository.findBySourceTypeAndSourceIdAndOrigin(note.type, note.id, LinkOrigin.SYNC) shouldHaveSize 2

		val keptId = repository.findBySourceTypeAndSourceIdAndOrigin(note.type, note.id, LinkOrigin.SYNC).single { it.target == third }.id
		service.sync(note, listOf(third, task))
		targetsOf(note) shouldContainExactlyInAnyOrder listOf(task, third)
		// The manual link does not stop note from owning its own sync link to task.
		val owned = repository.findBySourceTypeAndSourceIdAndOrigin(note.type, note.id, LinkOrigin.SYNC)
		owned.map { it.target } shouldContainExactlyInAnyOrder listOf(third, task)
		owned.single { it.target == third }.id shouldBe keptId
		service
			.linksOf(note)
			.first()
			.link.id shouldBe manual.link.id

		service.sync(note, emptyList())
		targetsOf(note) shouldBe listOf(task)
		repository.findAll().single().id shouldBe manual.link.id
	}

	@Test
	fun `sync adds its own link next to a manual link in the other direction, listed once`() {
		val manual = service.create(task, note, null)

		service.sync(note, listOf(task))

		repository.findAll() shouldHaveSize 2
		service.linksOf(task).map { it.link.id } shouldBe listOf(manual.link.id)
		service.linksOf(note).map { it.link.id } shouldBe listOf(manual.link.id)
	}

	@Test
	fun `sync does not touch links other sources own`() {
		service.sync(otherTask, listOf(note))
		service.sync(task, listOf(note))

		service.sync(task, emptyList())

		service.linksOf(note).map { it.link.source } shouldBe listOf(otherTask)
	}

	@Test
	fun `ItemDeletedEvent removes the item's links in both directions in the publisher's transaction`() {
		service.create(task, note, null)
		service.create(otherTask, task, null)
		service.create(otherTask, note, null)

		tx.executeWithoutResult { events.publishEvent(ItemDeletedEvent(task)) }

		service.linksOf(task).shouldBeEmpty()
		service.linksOf(otherTask).map { it.link.target } shouldBe listOf(note)
	}

	@Test
	fun `a rolled back delete keeps the links`() {
		service.create(task, note, null)

		tx.executeWithoutResult {
			events.publishEvent(ItemDeletedEvent(task))
			it.setRollbackOnly()
		}

		service.linksOf(task) shouldHaveSize 1
	}
}
