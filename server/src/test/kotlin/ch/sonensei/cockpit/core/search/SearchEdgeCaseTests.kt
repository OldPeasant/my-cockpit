package ch.sonensei.cockpit.core.search

import ch.sonensei.cockpit.core.auth.WithCockpitUser
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
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * Edge cases of the global search beyond [SearchServiceTests] and [SearchWebTests]: ranking and grouping order, the
 * trigram fill-up, resolver failures, events outside a transaction, query normalisation and the schema. Same context
 * configuration as [SearchWebTests], so both share one cached Spring context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeItemResolversConfiguration::class)
@WithCockpitUser
class SearchEdgeCaseTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val indexer: SearchIndexer,
	@Autowired private val search: SearchService,
	@Autowired private val fakes: FakeItemResolvers,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val events: ApplicationEventPublisher,
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

	private fun ageDays(
		item: ItemRef,
		days: Int,
	) {
		jdbc.update("update search_document set updated_at = updated_at - make_interval(days => ?) where item_id = ?", days, item.id)
	}

	// --- ranking and grouping -------------------------------------------------------------------------------------

	@Test
	fun `groups are ordered by their best hit, not by a fixed type order`() {
		note("Garden plan")
		task("Shopping", "garden gloves")
		task("Kitchen renovation")
		note("Recipes", "kitchen scale")

		search.search("garden", emptySet(), 20).map { it.type } shouldContainExactly listOf(ItemType.NOTE, ItemType.TASK)
		search.search("kitchen", emptySet(), 20).map { it.type } shouldContainExactly listOf(ItemType.TASK, ItemType.NOTE)
	}

	@Test
	fun `within a group items are best first`() {
		val bodyHit = task("Weekend", "repaint the fence")
		val titleHit = task("Fence repair")

		val group = search.search("fence", emptySet(), 20).single()
		group.results.map { it.hit.item } shouldContainExactly listOf(titleHit, bodyHit)
	}

	@Test
	fun `a full-text hit ranks before a fuzzy title hit even when the fuzzy one is newer`() {
		val fullText = task("Shopping", "plumbing supplies")
		ageDays(fullText, 365)
		val fuzzy = task("Plumm tree")

		found("plumb") shouldContainExactly listOf(fullText, fuzzy)
	}

	@Test
	fun `recency does not let an old title hit fall behind a fresh body hit`() {
		val oldTitle = task("Passport renewal")
		ageDays(oldTitle, 365)
		val freshBody = task("Travel", "bring the passport")

		found("passport") shouldContainExactly listOf(oldTitle, freshBody)
	}

	// --- trigram fill-up ------------------------------------------------------------------------------------------

	@Test
	fun `fuzzy hits fill up to the limit without duplicating full-text hits`() {
		val exact = task("Call plumber")
		val typo1 = task("Pay plumbr")
		val typo2 = task("Ask plumbar")

		val all = found("plumber")
		all.first() shouldBe exact
		all shouldContainExactlyInAnyOrder listOf(exact, typo1, typo2)

		val two = found("plumber", limit = 2)
		two shouldHaveSize 2
		two.first() shouldBe exact
		two.toSet() shouldHaveSize 2
	}

	@Test
	fun `fuzzy matching applies the type filter`() {
		task("Call plumber")

		found("plumbr", setOf(ItemType.NOTE)).shouldBeEmpty()
		found("plumbr", setOf(ItemType.TASK)) shouldHaveSize 1
	}

	@Test
	fun `fuzzy matching is on titles only, not on bodies`() {
		note("Shopping", "call the plumber")

		found("plumbr").shouldBeEmpty()
	}

	@Test
	fun `a fuzzy hit whose resolver no longer knows the item is left out`() {
		val trashed = task("Call plumber")
		fakes.tasks.remove(trashed.id)

		found("plumbr").shouldBeEmpty()
	}

	// --- resolvers and events ---------------------------------------------------------------------------------------

	@Test
	fun `a failing resolver does not break the search, the hit keeps its indexed title`() {
		val broken = task("Call plumber")
		val fine = note("Plumber invoice")
		fakes.tasks.failing = true

		val results = search.search("plumber", emptySet(), 20).flatMap { it.results }
		results.map { it.hit.item } shouldContainExactlyInAnyOrder listOf(broken, fine)
		val brokenResult = results.single { it.hit.item == broken }
		brokenResult.resolved shouldBe null
		brokenResult.hit.title shouldBe "Call plumber"
		results.single { it.hit.item == fine }.resolved?.deepLink shouldBe "/note/${fine.id}"
	}

	@Test
	fun `a failing resolver gives the indexed title without display data over HTTP`() {
		val broken = task("Call plumber")
		fakes.tasks.failing = true

		mockMvc.get(BASE) { param("q", "plumber") }.andExpect {
			status { isOk() }
			jsonPath("$.groups[0].type") { value("TASK") }
			jsonPath("$.groups[0].items[0].id") { value(broken.id.toString()) }
			jsonPath("$.groups[0].items[0].title") { value("Call plumber") }
			jsonPath("$.groups[0].items[0].deepLink") { doesNotExist() }
			jsonPath("$.groups[0].items[0].icon") { doesNotExist() }
		}
	}

	@Test
	fun `ItemDeletedEvent published outside a transaction removes the document`() {
		val purged = task("Call plumber")
		val kept = task("Call electrician")

		events.publishEvent(ItemDeletedEvent(purged))

		found("call") shouldContainExactly listOf(kept)
		jdbc.queryForObject("select count(*) from search_document where item_id = ?", Long::class.java, purged.id) shouldBe 0L
	}

	@Test
	fun `delete removes only the document of that type`() {
		val id = UuidV7.generate()
		fakes.tasks.add("Quarterly report", id)
		fakes.notes.add("Quarterly report", id)
		indexer.upsert(ItemType.TASK, id, "Quarterly report", null)
		indexer.upsert(ItemType.NOTE, id, "Quarterly report", null)

		indexer.delete(ItemType.TASK, id)

		found("quarterly") shouldContainExactly listOf(ItemRef(ItemType.NOTE, id))
	}

	@Test
	fun `deleting an item that was never indexed is harmless`() {
		indexer.delete(ItemType.TASK, UuidV7.generate())
		events.publishEvent(ItemDeletedEvent(ItemRef(ItemType.NOTE, UuidV7.generate())))
	}

	@Test
	fun `upsert refreshes recency`() {
		val first = task("Renew passport")
		val second = task("Renew passport")
		ageDays(first, 60)
		ageDays(second, 30)

		found("passport") shouldContainExactly listOf(second, first)
		indexer.upsert(first.type, first.id, "Renew passport", "")
		found("passport") shouldContainExactly listOf(first, second)
	}

	// --- query normalisation ------------------------------------------------------------------------------------------

	@Test
	fun `queries are case-insensitive`() {
		val plumber = task("Call Plumber")

		found("PLUMBER") shouldContainExactly listOf(plumber)
		found("pLuMb") shouldContainExactly listOf(plumber)
	}

	@Test
	fun `every query word must match`() {
		val both = task("Call plumber tomorrow")
		task("Call electrician")

		found("call tomorrow") shouldContainExactly listOf(both)
	}

	@Test
	fun `an English stop word alone or with other words does not fail`() {
		val theatre = note("Theatre tickets")
		val plumber = task("Call plumber")

		found("the") shouldContainExactly listOf(theatre)
		found("the plumber") shouldContainExactly listOf(plumber)
	}

	@Test
	fun `non-ASCII letters are words`() {
		val trip = note("Reise nach Zürich")

		found("zür") shouldContainExactly listOf(trip)
		found("zürich") shouldContainExactly listOf(trip)
	}

	@Test
	fun `an empty type set searches all types`() {
		val task = task("Plan holiday")
		val note = note("Holiday ideas")

		found("holiday", emptySet()) shouldContainExactlyInAnyOrder listOf(task, note)
	}

	// --- indexer input --------------------------------------------------------------------------------------------------

	@Test
	fun `titles are truncated, null bodies stored as empty, NUL dropped from titles`() {
		val longTitle = "a".repeat(SearchIndexer.MAX_TITLE_LENGTH) + " overflowword"
		val item = task(longTitle)
		val nul = note("Shop\u0000ping list")
		indexer.upsert(nul.type, nul.id, "Shop\u0000ping list", null)

		jdbc.queryForObject("select length(title) from search_document where item_id = ?", Int::class.java, item.id) shouldBe
			SearchIndexer.MAX_TITLE_LENGTH
		found("overflowword").shouldBeEmpty()
		jdbc.queryForObject("select body from search_document where item_id = ?", String::class.java, nul.id) shouldBe ""
		found("shopping") shouldContainExactly listOf(nul)
	}

	// --- HTTP parameters ------------------------------------------------------------------------------------------------

	@Test
	fun `q is trimmed before validation and search`() {
		task("Plan holiday")

		mockMvc.get(BASE) { param("q", "   holiday   ") }.andExpect {
			status { isOk() }
			jsonPath("$.groups[0].items.length()") { value(1) }
		}
		mockMvc.get(BASE) { param("q", "  " + "x".repeat(SearchService.MAX_QUERY_LENGTH) + "  ") }.andExpect {
			status { isOk() }
		}
	}

	@Test
	fun `limit boundaries 1 and 50 are accepted`() {
		task("Plan holiday")
		listOf("1", "${SearchService.MAX_LIMIT}").forEach { limit ->
			mockMvc
				.get(BASE) {
					param("q", "holiday")
					param("limit", limit)
				}.andExpect { status { isOk() } }
		}
	}

	@Test
	fun `an empty types parameter means all types`() {
		task("Plan holiday")
		note("Holiday ideas")

		mockMvc
			.get(BASE) {
				param("q", "holiday")
				param("types", "")
			}.andExpect {
				status { isOk() }
				jsonPath("$.groups.length()") { value(2) }
			}
	}

	@Test
	fun `a non-numeric limit is a 400 problem detail`() {
		mockMvc
			.get(BASE) {
				param("q", "holiday")
				param("limit", "many")
			}.andExpect {
				status { isBadRequest() }
				content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			}
	}

	@Test
	fun `a query without letters or digits finds nothing over HTTP`() {
		task("Plan holiday")

		mockMvc.get(BASE) { param("q", "&|!():*'") }.andExpect {
			status { isOk() }
			jsonPath("$.groups.length()") { value(0) }
		}
	}

	// --- schema -----------------------------------------------------------------------------------------------------------

	@Test
	fun `the migration creates a GIN index on tsv and a trigram index on title`() {
		val indexes =
			jdbc
				.queryForList("select indexdef from pg_indexes where tablename = 'search_document'", String::class.java)
				.filterNotNull()
		indexes.single { it.contains("(tsv)") } shouldContain "USING gin"
		indexes.single { it.contains("gin_trgm_ops") } shouldContain "USING gin (title gin_trgm_ops)"
	}

	@Test
	fun `tsv holds both the exact and the English-stemmed words of title and body`() {
		val item = note("Running shoes", "planning trips")

		val lexemes =
			jdbc
				.queryForObject("select tsv::text from search_document where item_id = ?", String::class.java, item.id)!!
		listOf("'running'", "'run'", "'planning'", "'plan'").forEach { lexemes shouldContain it }
	}

	private companion object {
		const val BASE = "/api/v1/search"
	}
}
