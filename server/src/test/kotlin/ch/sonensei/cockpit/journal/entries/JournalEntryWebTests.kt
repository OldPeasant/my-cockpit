package ch.sonensei.cockpit.journal.entries

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** `/api/v1/journal` over HTTP (SPEC.md §8.5), against Postgres, with the test clock in Europe/Zurich. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class JournalEntryWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val settings: SettingsService,
	@Autowired private val resolvers: ItemResolverRegistry,
) : PostgresTestBase() {
	@BeforeEach
	fun setUp() {
		reset()
		settings.update(mapOf("timeZone" to "Europe/Zurich"))
		clock.set(NOW)
	}

	@AfterEach
	fun reset() {
		jdbc.execute("delete from journal_entry")
		jdbc.execute("delete from link")
		jdbc.execute("delete from search_document")
		jdbc.execute("delete from file")
		while ((jdbc.queryForObject("select count(*) from folder", Long::class.java) ?: 0) > 0) {
			jdbc.execute("delete from folder f where not exists (select 1 from folder c where c.parent_id = f.id)")
		}
		settings.update(mapOf("timeZone" to null))
		clock.set(MutableClockTestConfiguration.START)
	}

	// --- helpers

	private fun ResultActionsDsl.json(): JsonNode = andReturn().response.contentAsString.let(jsonMapper::readTree)

	private fun put(
		date: String,
		body: String,
		ifMatch: String? = null,
	) = mockMvc.put("$ENTRIES/$date") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun create(
		date: String,
		body: String = """{"bodyMd":"Walked by the lake"}""",
	): JsonNode = put(date, body).andExpect { status { isCreated() } }.json()

	private fun fetch(date: String): JsonNode = mockMvc.get("$ENTRIES/$date").andExpect { status { isOk() } }.json()

	private fun JsonNode.etag(): String = "\"${get("version").asLong()}\""

	private fun JsonNode.dates(): List<String> = get("items").values().map { it.get("date").asString() }

	private fun searchDocument(id: String): Map<String, Any?>? =
		jdbc
			.queryForList("select title, body from search_document where item_type = 'JOURNAL_ENTRY' and item_id = ?::uuid", id)
			.singleOrNull()

	// --- upsert by date

	@Test
	fun `a PUT creates the entry of a date, a second one replaces it with If-Match`(events: ApplicationEvents) {
		val created =
			put("2026-03-04", """{"bodyMd":"# Spring\nFirst **crocus**","mood":4,"tags":["#walk"," Garden "]}""")
				.andExpect {
					status { isCreated() }
					header { string(HttpHeaders.LOCATION, "http://localhost$ENTRIES/2026-03-04") }
					header { string(HttpHeaders.ETAG, "\"0\"") }
				}.json()
		created.get("date").asString() shouldBe "2026-03-04"
		created.get("displayTitle").asString() shouldBe "Journal 2026-03-04"
		created.get("mood").asInt() shouldBe 4
		created.get("tags").values().map { it.asString() } shouldContainExactly listOf("Garden", "walk")

		put("2026-03-04", """{"bodyMd":"changed"}""").andExpect { status { isPreconditionRequired() } }
		put("2026-03-04", """{"bodyMd":"changed"}""", "\"7\"").andExpect { status { isPreconditionFailed() } }
		val replaced = put("2026-03-04", """{"bodyMd":"changed"}""", created.etag()).andExpect { status { isOk() } }.json()

		replaced.get("id").asString() shouldBe created.get("id").asString()
		replaced.get("bodyMd").asString() shouldBe "changed"
		replaced.get("mood").isNull shouldBe true
		replaced.get("tags").isEmpty shouldBe true
		fetch("2026-03-04").get("version").asLong() shouldBe 1
		events.stream(ChangeEvent::class.java).map { it.op }.toList() shouldContainExactly listOf(ChangeOp.CREATED, ChangeOp.UPDATED)
	}

	@Test
	fun `creating with an If-Match is refused, a missing date is 404`() {
		put("2026-03-04", "{}", "*").andExpect { status { isPreconditionFailed() } }
		mockMvc.get("$ENTRIES/2026-03-04").andExpect { status { isNotFound() } }
		create("2026-03-04", "{}").get("bodyMd").asString() shouldBe ""
	}

	@Test
	fun `invalid entries are rejected`() {
		put("2026-03-05", "{}").andExpect { status { isBadRequest() } }
		put("2026-03-04", """{"mood":6}""").andExpect { status { isBadRequest() } }
		put("2026-03-04", """{"mood":"good"}""").andExpect { status { isBadRequest() } }
		put("2026-03-04", """{"title":"x"}""").andExpect { status { isBadRequest() } }
		put("2026-03-04", """{"tags":[null]}""").andExpect { status { isBadRequest() } }
		put("2026-03-04", """{"tags":["  "]}""").andExpect { status { isBadRequest() } }
		put("not-a-date", "{}").andExpect { status { isBadRequest() } }
		jdbc.queryForObject("select count(*) from journal_entry", Long::class.java) shouldBe 0
	}

	@Test
	fun `today follows the time zone setting`() {
		// 2026-03-04 23:30 UTC is already 2026-03-05 in Zurich.
		clock.set(Instant.parse("2026-03-04T23:30:00Z"))
		create("2026-03-05")
		settings.update(mapOf("timeZone" to "UTC"))
		put("2026-03-06", "{}").andExpect { status { isBadRequest() } }
	}

	// --- list, heatmap, on this day

	@Test
	fun `the list is newest first, filtered by range and tag, and paged`() {
		create("2026-03-01", """{"tags":["Walk"]}""")
		create("2026-03-02")
		create("2026-03-03", """{"tags":["walk"]}""")

		mockMvc.get("$ENTRIES?limit=2").andExpect { status { isOk() } }.json().also { page ->
			page.dates() shouldContainExactly listOf("2026-03-03", "2026-03-02")
			val next = page.get("nextCursor").asString()
			mockMvc.get("$ENTRIES?limit=2&cursor=$next").andExpect { status { isOk() } }.json().also {
				it.dates() shouldContainExactly listOf("2026-03-01")
				it.get("nextCursor").isNull shouldBe true
			}
		}
		mockMvc
			.get("$ENTRIES?from=2026-03-02&to=2026-03-02")
			.andExpect { status { isOk() } }
			.json()
			.dates() shouldContainExactly
			listOf("2026-03-02")
		mockMvc
			.get(ENTRIES) { param("tag", "#WALK") }
			.andExpect { status { isOk() } }
			.json()
			.dates() shouldContainExactly
			listOf("2026-03-03", "2026-03-01")
		mockMvc.get("$ENTRIES?from=2026-03-03&to=2026-03-01").andExpect { status { isBadRequest() } }
	}

	@Test
	fun `the heatmap lists the dates with entries and their mood`() {
		create("2025-03-04", """{"mood":2}""")
		create("2026-01-10")
		create("2026-03-04", """{"mood":5}""")

		val heatmap = mockMvc.get("$JOURNAL/heatmap").andExpect { status { isOk() } }.json()
		heatmap.get("from").asString() shouldBe "2025-03-05"
		heatmap.get("to").asString() shouldBe "2026-03-04"
		heatmap.dates() shouldContainExactly listOf("2026-01-10", "2026-03-04")
		heatmap.get("items").values().map { it.get("mood").takeUnless(JsonNode::isNull)?.asInt() } shouldContainExactly listOf(null, 5)

		mockMvc
			.get("$JOURNAL/heatmap?from=2025-01-01&to=2025-12-31")
			.andExpect { status { isOk() } }
			.json()
			.dates() shouldContainExactly
			listOf("2025-03-04")
		mockMvc.get("$JOURNAL/heatmap?from=2000-01-01&to=2026-01-01").andExpect { status { isBadRequest() } }
	}

	@Test
	fun `on this day lists the same day of earlier years, newest first`() {
		create("2024-03-04", """{"bodyMd":"two years ago"}""")
		create("2025-03-04", """{"bodyMd":"last year"}""")
		create("2025-03-05")
		create("2026-03-04")

		val today = mockMvc.get("$JOURNAL/on-this-day").andExpect { status { isOk() } }.json()
		today.get("date").asString() shouldBe "2026-03-04"
		today.dates() shouldContainExactly listOf("2025-03-04", "2024-03-04")
	}

	@Test
	fun `on 28 February of a common year, entries of 29 February count`() {
		create("2024-02-29")
		create("2024-02-28")
		mockMvc
			.get("$JOURNAL/on-this-day?date=2025-02-28")
			.andExpect { status { isOk() } }
			.json()
			.dates() shouldContainExactly
			listOf("2024-02-29", "2024-02-28")
		mockMvc
			.get("$JOURNAL/on-this-day?date=2028-02-28")
			.andExpect { status { isOk() } }
			.json()
			.dates() shouldContainExactly
			listOf("2024-02-28")
	}

	// --- images

	@Test
	fun `an image is stored in Attachments-Journal`() {
		val bytes = byteArrayOf(1, 2, 3)
		val image =
			mockMvc
				.multipart("$JOURNAL/images") {
					file(MockMultipartFile("file", "lake.png", "image/png", bytes))
					with(csrf())
				}.andExpect { status { isCreated() } }
				.json()
		val fileId = image.get("fileId").asString()
		image.get("markdown").asString() shouldBe "![](cockpit-file:$fileId)"
		jdbc.queryForObject(
			"select fo.name from file f join folder fo on fo.id = f.folder_id where f.id = ?::uuid",
			String::class.java,
			fileId,
		) shouldBe "Journal"

		mockMvc
			.multipart("$JOURNAL/images") {
				file(MockMultipartFile("file", "notes.txt", "text/plain", "hello".toByteArray()))
				with(csrf())
			}.andExpect { status { isBadRequest() } }
	}

	// --- resolver, search, links, trash

	@Test
	fun `entries resolve, are searchable and sync their inline links`() {
		val target = UUID.randomUUID()
		val entry = create("2026-03-04", """{"bodyMd":"Planted **tulips** [[task:$target]]"}""")
		val id = entry.get("id").asString()

		val resolved = resolvers.find(ItemType.JOURNAL_ENTRY)!!.resolve(UUID.fromString(id)).shouldNotBeNull()
		resolved.title shouldBe "Journal 2026-03-04"
		resolved.deepLink shouldBe "/journal/2026-03-04"

		searchDocument(id)!!["body"] shouldBe "Planted tulips"
		val search = mockMvc.get("/api/v1/search?q=tulips&types=JOURNAL_ENTRY").andExpect { status { isOk() } }.andReturn()
		search.response.contentAsString.contains(id) shouldBe true

		jdbc.queryForList("select target_id::text from link where source_id = ?::uuid", String::class.java, id) shouldContainExactly
			listOf(target.toString())
	}

	@Test
	fun `a deleted entry goes to the Trash, its date can be written again, a restore then conflicts`() {
		val entry = create("2026-03-04")
		val id = entry.get("id").asString()

		mockMvc.delete("$ENTRIES/2026-03-04") { with(csrf()) }.andExpect { status { isPreconditionRequired() } }
		mockMvc
			.delete("$ENTRIES/2026-03-04") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, entry.etag())
			}.andExpect { status { isNoContent() } }

		mockMvc.get("$ENTRIES/2026-03-04").andExpect { status { isNotFound() } }
		searchDocument(id).shouldBeNull()
		resolvers.find(ItemType.JOURNAL_ENTRY)!!.resolve(UUID.fromString(id)).shouldBeNull()
		val trash =
			mockMvc
				.get("/api/v1/trash")
				.andExpect { status { isOk() } }
				.json()
				.get("items")
				.values()
		trash.single().get("title").asString() shouldBe "Journal 2026-03-04"

		val replacement = create("2026-03-04", """{"bodyMd":"new"}""")
		mockMvc.post("/api/v1/trash/JOURNAL_ENTRY/$id/restore") { with(csrf()) }.andExpect { status { isConflict() } }

		mockMvc
			.delete("$ENTRIES/2026-03-04") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, replacement.etag())
			}.andExpect { status { isNoContent() } }
		mockMvc.post("/api/v1/trash/JOURNAL_ENTRY/$id/restore") { with(csrf()) }.andExpect { status { is2xxSuccessful() } }
		fetch("2026-03-04").get("bodyMd").asString() shouldBe "Walked by the lake"
		searchDocument(id).shouldNotBeNull()

		mockMvc.delete("/api/v1/trash/JOURNAL_ENTRY/${replacement.get("id").asString()}") { with(csrf()) }.andExpect {
			status { is2xxSuccessful() }
		}
		jdbc.queryForObject("select count(*) from journal_entry", Long::class.java) shouldBe 1
	}

	private companion object {
		const val JOURNAL = "/api/v1/journal"
		const val ENTRIES = "$JOURNAL/entries"

		// Wednesday 2026-03-04, 09:00 in Zurich (UTC+1).
		val NOW: Instant = Instant.parse("2026-03-04T08:00:00Z")
	}
}
