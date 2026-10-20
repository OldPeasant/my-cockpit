package ch.sonensei.cockpit.core.search

import ch.sonensei.cockpit.core.items.FakeItemResolvers
import ch.sonensei.cockpit.core.items.FakeItemResolversConfiguration
import ch.sonensei.cockpit.core.items.domain.ItemDeletedEvent
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.core.search.domain.SearchService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest
@Import(FakeItemResolversConfiguration::class)
class SearchServiceTests(
	@Autowired private val indexer: SearchIndexer,
	@Autowired private val search: SearchService,
	@Autowired private val fakes: FakeItemResolvers,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val events: ApplicationEventPublisher,
	@Autowired private val transactions: TransactionTemplate,
) : PostgresTestBase() {
	@BeforeEach
	fun setUp() {
		jdbc.update("delete from search_document")
		fakes.reset()
	}

	private fun task(
		title: String,
		body: String = "",
	): ItemRef = fakes.tasks.add(title).also { indexer.upsert(it.type, it.id, title, body) }

	private fun note(
		title: String,
		body: String = "",
	): ItemRef = fakes.notes.add(title).also { indexer.upsert(it.type, it.id, title, body) }

	private fun found(
		query: String,
		types: Set<ItemType> = emptySet(),
		limit: Int = 20,
	): List<ItemRef> = search.search(query, types, limit).flatMap { group -> group.results.map { it.hit.item } }

	@Test
	fun `prefix of a title word finds the item`() {
		val plumber = task("Call plumber")
		task("Buy milk")

		found("plu") shouldContainExactly listOf(plumber)
		found("call plumb") shouldContainExactly listOf(plumber)
		found("ca") shouldContainExactly listOf(plumber)
	}

	@Test
	fun `body words are found and stemmed English forms match`() {
		val note = note("Holiday ideas", "We were planning a trip to the mountains")

		found("mountain") shouldContainExactly listOf(note)
		found("plans") shouldContainExactly listOf(note)
	}

	@Test
	fun `a typo in a title still finds the item`() {
		val plumber = task("Call plumber")
		task("Buy milk")

		found("plumbr") shouldContainExactly listOf(plumber)
		found("plumer") shouldContainExactly listOf(plumber)
		found("plumbar") shouldContainExactly listOf(plumber)
	}

	@Test
	fun `a title match ranks before a body match`() {
		val bodyMatch = note("Shopping list", "milk, bread, garden gloves")
		val titleMatch = task("Garden fence")

		found("garden") shouldContainExactly listOf(titleMatch, bodyMatch)
	}

	@Test
	fun `among equal matches the more recent ranks first`() {
		val old = task("Renew passport")
		val recent = task("Renew passport")
		jdbc.update("update search_document set updated_at = updated_at - interval '60 days' where item_id = ?", old.id)

		found("passport") shouldContainExactly listOf(recent, old)
	}

	@Test
	fun `type filter returns only those types and results are grouped by type`() {
		val task = task("Plan holiday")
		val note = note("Holiday ideas")

		found("holiday", setOf(ItemType.NOTE)) shouldContainExactly listOf(note)
		val groups = search.search("holiday", emptySet(), 20)
		groups.map { it.type }.toSet() shouldBe setOf(ItemType.TASK, ItemType.NOTE)
		groups.flatMap { g -> g.results.map { it.hit.item } }.toSet() shouldBe setOf(task, note)
	}

	@Test
	fun `limit counts items across all groups`() {
		repeat(5) { task("Report $it") }
		repeat(5) { note("Report note $it") }

		found("report", limit = 3).size shouldBe 3
	}

	@Test
	fun `deleted items are not found`() {
		val deleted = task("Call plumber")
		val purged = task("Call plumber again")
		val kept = task("Call electrician")

		indexer.delete(deleted.type, deleted.id)
		transactions.executeWithoutResult { events.publishEvent(ItemDeletedEvent(purged)) }

		found("call") shouldContainExactly listOf(kept)
		found("plumbr").shouldBeEmpty()
	}

	@Test
	fun `an item its resolver no longer knows is left out`() {
		val trashed = task("Call plumber")
		fakes.tasks.remove(trashed.id)

		found("plumber").shouldBeEmpty()
	}

	@Test
	fun `items of types without a resolver are found with the indexed title`() {
		val id = UuidV7.generate()
		indexer.upsert(ItemType.CONTACT, id, "Erika Mustermann", null)

		val result =
			search
				.search("erika", emptySet(), 20)
				.single()
				.results
				.single()
		result.hit.item shouldBe ItemRef(ItemType.CONTACT, id)
		result.hit.title shouldBe "Erika Mustermann"
		result.resolved shouldBe null
	}

	@Test
	fun `upsert replaces the indexed text`() {
		val task = task("Call plumber")
		indexer.upsert(task.type, task.id, "Call electrician", "")

		found("plumber").shouldBeEmpty()
		found("electrician") shouldContainExactly listOf(task)
	}

	@Test
	fun `queries with tsquery syntax or without words are safe`() {
		val task = task("Call plumber")

		found("plumber & | ! ( ' :*") shouldContainExactly listOf(task)
		found("!!! ???").shouldBeEmpty()
	}

	@Test
	fun `NUL characters are dropped and long bodies are truncated`() {
		val note = note("Long note", "start\u0000 " + "lorem ".repeat(30_000) + "needle")

		found("start") shouldContainExactly listOf(note)
		found("needle").shouldBeEmpty()
	}
}
