package ch.sonensei.cockpit.journal.entries

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.journal.entries.domain.JournalEntry
import ch.sonensei.cockpit.journal.entries.domain.JournalEntryContent
import ch.sonensei.cockpit.journal.entries.domain.JournalEntryService
import ch.sonensei.cockpit.journal.entries.infra.JournalEntryRepository
import ch.sonensei.cockpit.journal.entries.infra.JournalEntryTrashContributor
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
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
import java.time.LocalDate
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Edge cases of the journal API (task journal-api, SPEC.md §8.5, §4.2-4.4): concurrent creates and replaces, tag
 * normalization and spelling, size and NUL limits, cursors, heatmap range limits, "on this day" around 29 February,
 * link sync on body changes, change events of delete/restore/purge, search after restore, the Trash purge and the
 * constraints of the migration.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class JournalEntryEdgeCaseWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val settings: SettingsService,
	@Autowired private val resolvers: ItemResolverRegistry,
	@Autowired private val journal: JournalEntryService,
	@Autowired private val repository: JournalEntryRepository,
	@Autowired private val indexer: SearchIndexer,
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

	private fun replace(
		entry: JsonNode,
		body: String,
	): JsonNode = put(entry.get("date").asString(), body, entry.etag()).andExpect { status { isOk() } }.json()

	private fun delete(entry: JsonNode) =
		mockMvc
			.delete("$ENTRIES/${entry.get("date").asString()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, entry.etag())
			}.andExpect { status { isNoContent() } }

	private fun fetch(date: String): JsonNode = mockMvc.get("$ENTRIES/$date").andExpect { status { isOk() } }.json()

	private fun JsonNode.etag(): String = "\"${get("version").asLong()}\""

	private fun JsonNode.id(): String = get("id").asString()

	private fun JsonNode.tags(): List<String> = get("tags").values().map { it.asString() }

	private fun JsonNode.dates(): List<String> = get("items").values().map { it.get("date").asString() }

	private fun count(sql: String): Long = jdbc.queryForObject(sql, Long::class.java) ?: 0

	private fun linkTargets(id: String): List<String> =
		jdbc.queryForList("select target_id::text from link where source_id = ?::uuid", String::class.java, id).filterNotNull()

	private fun searchBody(id: String): String? =
		jdbc
			.queryForList("select body from search_document where item_type = 'JOURNAL_ENTRY' and item_id = ?::uuid", String::class.java, id)
			.singleOrNull()

	private fun journalEvents(events: ApplicationEvents): List<Pair<String, ChangeOp>> =
		events
			.stream(ChangeEvent::class.java)
			.filter { it.type == ItemType.JOURNAL_ENTRY.name }
			.map { it.id.toString() to it.op }
			.toList()

	/** Inserts an active entry of [date] from another connection, committed at once, like a concurrent request. */
	private fun insertConcurrently(date: LocalDate) {
		thread {
			jdbc.update(
				"insert into journal_entry (id, created_at, updated_at, version, entry_date) values (?, now(), now(), 0, ?)",
				UUID.randomUUID(),
				date,
			)
		}.join()
	}

	// --- concurrency

	@Test
	fun `a concurrent create of the same date is a conflict and stores nothing of the loser`(events: ApplicationEvents) {
		val date = LocalDate.parse("2026-03-04")
		val error =
			shouldThrow<ConflictException> {
				journal.put(date, JournalEntryContent(bodyMd = "lost race")) { version ->
					version.shouldBeNull()
					insertConcurrently(date)
				}
			}
		error.status.value() shouldBe 409

		count("select count(*) from journal_entry") shouldBe 1
		count("select count(*) from journal_entry where body_md = 'lost race'") shouldBe 0
		count("select count(*) from search_document where item_type = 'JOURNAL_ENTRY'") shouldBe 0
		journalEvents(events).shouldBeEmpty()
		// The winner is the entry of the date now; a PUT without If-Match no longer creates.
		put("2026-03-04", "{}").andExpect { status { isPreconditionRequired() } }
	}

	@Test
	fun `a Trash restore that loses against a concurrent create of its date is a 409, the winner stays the only active entry`() {
		val deleted = create("2026-03-04", """{"bodyMd":"Restore me"}""")
		delete(deleted)
		// The repository answers the restore's "is the date free?" check, then another request creates the date's entry
		// before the restore is flushed (CR-2).
		val racing =
			object : JournalEntryRepository by repository {
				override fun findActiveByDate(date: LocalDate): JournalEntry? = repository.findActiveByDate(date).also { insertConcurrently(date) }
			}

		val error = shouldThrow<ConflictException> { JournalEntryTrashContributor(racing, indexer).restore(UUID.fromString(deleted.id())) }
		error.status.value() shouldBe 409

		count("select count(*) from journal_entry where deleted_at is null") shouldBe 1
		count("select count(*) from journal_entry where id = '${deleted.id()}'::uuid and deleted_at is not null") shouldBe 1
		searchBody(deleted.id()).shouldBeNull()
	}

	@Test
	fun `a replace that loses against a concurrent change fails with optimistic locking, a stale ETag is 412`() {
		create("2026-03-04")
		// Mapped to 409 by ProblemDetailsAdvice.
		shouldThrow<OptimisticLockingFailureException> {
			journal.put(LocalDate.parse("2026-03-04"), JournalEntryContent(bodyMd = "mine")) { version ->
				version shouldBe 0
				thread { jdbc.update("update journal_entry set version = version + 1") }.join()
			}
		}
		val current = fetch("2026-03-04")
		current.get("bodyMd").asString() shouldBe "Walked by the lake"
		current.get("version").asLong() shouldBe 1

		// Two clients replacing with the same ETag: the second one is refused.
		put("2026-03-04", """{"bodyMd":"second"}""", current.etag()).andExpect { status { isOk() } }
		put("2026-03-04", """{"bodyMd":"third"}""", current.etag()).andExpect { status { isPreconditionFailed() } }
		fetch("2026-03-04").get("bodyMd").asString() shouldBe "second"
	}

	// --- content rules

	@Test
	fun `tags keep their stored spelling on replace, duplicates collapse to the first spelling`() {
		val entry = create("2026-03-04", """{"tags":["Walk","#Tea"," tea ","TEA"]}""")
		entry.tags() shouldContainExactly listOf("Tea", "Walk")

		val replaced = replace(entry, """{"tags":["walk","new"]}""")
		replaced.tags() shouldContainExactly listOf("new", "Walk")
		jdbc.queryForList("select tag from journal_entry_tag", String::class.java) shouldContainExactlyInAnyOrder listOf("Walk", "new")
	}

	@Test
	fun `tag limits - 50 tags of 50 characters are accepted, more or longer are rejected`() {
		val fifty = (1..50).joinToString(",") { "\"t$it\"" }
		val fiftyOne = (1..51).joinToString(",") { "\"t$it\"" }
		put("2026-03-01", """{"tags":[$fiftyOne]}""").andExpect { status { isBadRequest() } }
		put("2026-03-01", """{"tags":["${"x".repeat(51)}"]}""").andExpect { status { isBadRequest() } }
		put("2026-03-01", """{"tags":["a\u0000b"]}""").andExpect { status { isBadRequest() } }
		put("2026-03-01", """{"tags":["#"]}""").andExpect { status { isBadRequest() } }
		count("select count(*) from journal_entry") shouldBe 0

		create("2026-03-01", """{"tags":[$fifty]}""").tags().size shouldBe 50
		// 51 spellings of 50 distinct tags count as 50.
		create("2026-03-02", """{"tags":[$fifty,"T1"]}""").tags().size shouldBe 50
		create("2026-03-03", """{"tags":["${"x".repeat(50)}"]}""").tags().single().length shouldBe 50
	}

	@Test
	fun `the body may be 100000 characters without NUL, mood 1 to 5 or null`() {
		put("2026-03-01", """{"bodyMd":"${"a".repeat(100_001)}"}""").andExpect {
			status { isBadRequest() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
		}
		put("2026-03-01", """{"bodyMd":"before\u0000after"}""").andExpect { status { isBadRequest() } }
		put("2026-03-01", """{"mood":0}""").andExpect { status { isBadRequest() } }
		put("2026-03-01", """{"tags":"walk"}""").andExpect { status { isBadRequest() } }
		count("select count(*) from journal_entry") shouldBe 0

		create("2026-03-01", """{"bodyMd":"${"a".repeat(100_000)}","mood":1}""").get("bodyMd").asString().length shouldBe 100_000
		create("2026-03-02", """{"mood":5}""").get("mood").asInt() shouldBe 5
		val defaults = create("2026-03-03", """{"bodyMd":null,"mood":null,"tags":null}""")
		defaults.get("bodyMd").asString() shouldBe ""
		defaults.get("mood").isNull shouldBe true
		defaults.tags().shouldBeEmpty()
	}

	@Test
	fun `today's date is accepted, a replace keeps id and createdAt and sends the new ETag`() {
		val entry = create("2026-03-04")
		clock.set(NOW.plusSeconds(60))
		val replaced =
			put("2026-03-04", """{"bodyMd":"later"}""", entry.etag())
				.andExpect {
					status { isOk() }
					header { string(HttpHeaders.ETAG, "\"1\"") }
				}.json()
		replaced.id() shouldBe entry.id()
		replaced.get("createdAt").asString() shouldBe entry.get("createdAt").asString()
		Instant.parse(replaced.get("updatedAt").asString()) shouldBe NOW.plusSeconds(60)
	}

	// --- list and cursor

	@Test
	fun `malformed cursors, positions and page sizes are rejected with 400`() {
		create("2026-03-04")
		mockMvc.get(ENTRIES) { param("cursor", "%%%not-base64") }.andExpect { status { isBadRequest() } }
		mockMvc.get(ENTRIES) { param("cursor", CursorRequest.encodeCursor("not-a-date")) }.andExpect { status { isBadRequest() } }
		mockMvc.get(ENTRIES) { param("limit", "0") }.andExpect { status { isBadRequest() } }
		mockMvc.get(ENTRIES) { param("limit", "201") }.andExpect { status { isBadRequest() } }
		mockMvc.get(ENTRIES) { param("tag", "a\u0000b") }.andExpect { status { isBadRequest() } }
		mockMvc.get(ENTRIES) { param("from", "2026-02-30") }.andExpect { status { isBadRequest() } }
	}

	@Test
	fun `a tag-filtered list pages with a cursor and leaves out deleted entries`() {
		create("2026-03-01", """{"tags":["food"]}""")
		val deleted = create("2026-03-02", """{"tags":["food"]}""")
		create("2026-03-03", """{"tags":["Food"]}""")
		create("2026-03-04", """{"tags":["other"]}""")
		delete(deleted)

		val first = mockMvc.get("$ENTRIES?tag=food&limit=1").andExpect { status { isOk() } }.json()
		first.dates() shouldContainExactly listOf("2026-03-03")
		val second =
			mockMvc
				.get("$ENTRIES?tag=food&limit=1&cursor=${first.get("nextCursor").asString()}")
				.andExpect { status { isOk() } }
				.json()
		second.dates() shouldContainExactly listOf("2026-03-01")
		second.get("nextCursor").isNull shouldBe true
		// A blank tag means no filter.
		val unfiltered = mockMvc.get(ENTRIES) { param("tag", "  ") }.andExpect { status { isOk() } }.json()
		unfiltered.dates() shouldContainExactly listOf("2026-03-04", "2026-03-03", "2026-03-01")
	}

	// --- heatmap

	@Test
	fun `the heatmap accepts exactly 3660 days and rejects 3661 or a reversed range`() {
		val to = LocalDate.parse("2026-03-04")
		val oldest = to.minusDays(3659)
		create(oldest.toString())
		create(oldest.minusDays(1).toString())

		val max = mockMvc.get("$JOURNAL/heatmap?from=$oldest&to=$to").andExpect { status { isOk() } }.json()
		max.dates() shouldContainExactly listOf(oldest.toString())
		mockMvc.get("$JOURNAL/heatmap?from=${oldest.minusDays(1)}&to=$to").andExpect { status { isBadRequest() } }
		mockMvc.get("$JOURNAL/heatmap?from=2026-03-04&to=2026-03-03").andExpect { status { isBadRequest() } }
		mockMvc
			.get("$JOURNAL/heatmap?from=2026-03-04&to=2026-03-04")
			.andExpect { status { isOk() } }
			.json()
			.get("items")
			.isEmpty shouldBe true
	}

	@Test
	fun `the heatmap defaults the start to 364 days before the given end and leaves out deleted entries`() {
		create("2025-06-01", """{"mood":3}""")
		val deleted = create("2025-06-02")
		create("2025-06-03")
		delete(deleted)

		val heatmap = mockMvc.get("$JOURNAL/heatmap?to=2025-06-02").andExpect { status { isOk() } }.json()
		heatmap.get("from").asString() shouldBe "2024-06-03"
		heatmap.get("to").asString() shouldBe "2025-06-02"
		heatmap.dates() shouldContainExactly listOf("2025-06-01")
		heatmap
			.get("items")
			.values()
			.single()
			.get("mood")
			.asInt() shouldBe 3
		// Only a start: the end is today.
		mockMvc.get("$JOURNAL/heatmap?from=2025-06-03").andExpect { status { isOk() } }.json().also {
			it.get("to").asString() shouldBe "2026-03-04"
			it.dates() shouldContainExactly listOf("2025-06-03")
		}
	}

	// --- on this day

	@Test
	fun `on 29 February only 29 February counts, the asked year and deleted entries are left out`() {
		create("2020-02-29")
		create("2024-02-29")
		create("2024-02-28")
		create("2025-02-28")
		create("2026-03-01")
		val deleted = create("2023-03-01")
		delete(deleted)

		mockMvc
			.get("$JOURNAL/on-this-day?date=2028-02-29")
			.andExpect { status { isOk() } }
			.json()
			.also { it.get("date").asString() shouldBe "2028-02-29" }
			.dates() shouldContainExactly
			listOf("2024-02-29", "2020-02-29")
		// In a leap year, 28 February does not pull in the 29th.
		mockMvc
			.get("$JOURNAL/on-this-day?date=2024-02-28")
			.andExpect { status { isOk() } }
			.json()
			.dates()
			.shouldBeEmpty()
		// Entries of the asked year itself (even earlier in the year) are not "on this day".
		mockMvc
			.get("$JOURNAL/on-this-day?date=2026-03-01")
			.andExpect { status { isOk() } }
			.json()
			.dates()
			.shouldBeEmpty()
		mockMvc.get("$JOURNAL/on-this-day?date=2026-13-01").andExpect { status { isBadRequest() } }
	}

	// --- links, search, events, trash

	@Test
	fun `links follow body changes and stay when only mood or tags change`() {
		val a = UUID.randomUUID()
		val b = UUID.randomUUID()
		val entry = create("2026-03-04", """{"bodyMd":"see [[task:$a]]"}""")
		linkTargets(entry.id()) shouldContainExactly listOf(a.toString())

		val moodOnly = replace(entry, """{"bodyMd":"see [[task:$a]]","mood":3}""")
		linkTargets(entry.id()) shouldContainExactly listOf(a.toString())

		val changed = replace(moodOnly, """{"bodyMd":"now [[note:$b]]"}""")
		linkTargets(entry.id()) shouldContainExactly listOf(b.toString())
		searchBody(entry.id()) shouldBe "now"

		replace(changed, """{"bodyMd":"no links"}""")
		linkTargets(entry.id()).shouldBeEmpty()
		searchBody(entry.id()) shouldBe "no links"
	}

	@Test
	fun `delete, restore and purge announce change events, a restored entry is searchable by its body again`(events: ApplicationEvents) {
		val target = UUID.randomUUID()
		val entry = create("2026-03-04", """{"bodyMd":"Picked **cherries** [[task:$target]]","tags":["fruit"]}""")
		val id = entry.id()
		delete(entry)
		linkTargets(id) shouldContainExactly listOf(target.toString())

		mockMvc.post("/api/v1/trash/JOURNAL_ENTRY/$id/restore") { with(csrf()) }.andExpect { status { is2xxSuccessful() } }
		searchBody(id) shouldBe "Picked cherries"
		mockMvc
			.get("/api/v1/search?q=cherries&types=JOURNAL_ENTRY")
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.contains(id) shouldBe true
		resolvers.find(ItemType.JOURNAL_ENTRY)!!.resolve(UUID.fromString(id))!!.title shouldBe "Journal 2026-03-04"

		delete(fetch("2026-03-04"))
		mockMvc.delete("/api/v1/trash/JOURNAL_ENTRY/$id") { with(csrf()) }.andExpect { status { is2xxSuccessful() } }

		journalEvents(events) shouldContainExactly
			listOf(id to ChangeOp.CREATED, id to ChangeOp.DELETED, id to ChangeOp.UPDATED, id to ChangeOp.DELETED, id to ChangeOp.DELETED)
	}

	@Test
	fun `a purge removes the entry with its tags and links, its images stay in the files`() {
		val image =
			mockMvc
				.multipart("$JOURNAL/images") {
					file(MockMultipartFile("file", "cherries.png", "image/png", byteArrayOf(9, 8, 7)))
					with(csrf())
				}.andExpect { status { isCreated() } }
				.json()
		val target = UUID.randomUUID()
		val entry = create("2026-03-04", """{"bodyMd":"${image.get("markdown").asString()} [[task:$target]]","tags":["fruit"]}""")
		val id = entry.id()
		delete(entry)
		mockMvc.delete("/api/v1/trash/JOURNAL_ENTRY/$id") { with(csrf()) }.andExpect { status { is2xxSuccessful() } }

		count("select count(*) from journal_entry") shouldBe 0
		count("select count(*) from journal_entry_tag") shouldBe 0
		linkTargets(id).shouldBeEmpty()
		searchBody(id).shouldBeNull()
		count("select count(*) from file") shouldBe 1
		val trash = mockMvc.get("/api/v1/trash").andExpect { status { isOk() } }.json()
		trash.get("items").isEmpty shouldBe true
		// Purging or restoring again finds nothing.
		mockMvc.delete("/api/v1/trash/JOURNAL_ENTRY/$id") { with(csrf()) }.andExpect { status { isNotFound() } }
		mockMvc.post("/api/v1/trash/JOURNAL_ENTRY/$id/restore") { with(csrf()) }.andExpect { status { isNotFound() } }
	}

	@Test
	fun `an active entry cannot be purged or restored, deleting checks If-Match and existence`() {
		val entry = create("2026-03-04")
		mockMvc.delete("/api/v1/trash/JOURNAL_ENTRY/${entry.id()}") { with(csrf()) }.andExpect { status { isNotFound() } }
		mockMvc.post("/api/v1/trash/JOURNAL_ENTRY/${entry.id()}/restore") { with(csrf()) }.andExpect { status { isNotFound() } }

		mockMvc
			.delete("$ENTRIES/2026-03-04") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "\"9\"")
			}.andExpect { status { isPreconditionFailed() } }
		mockMvc
			.delete("$ENTRIES/2026-03-03") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "\"0\"")
			}.andExpect { status { isNotFound() } }
		fetch("2026-03-04")
		resolvers.find(ItemType.JOURNAL_ENTRY)!!.resolve(UUID.randomUUID()).shouldBeNull()
	}

	// --- images

	@Test
	fun `an image without a usable content type gets its mime from the name, one without a name is called image`() {
		val guessed =
			mockMvc
				.multipart("$JOURNAL/images") {
					file(MockMultipartFile("file", "lake.jpg", MediaType.APPLICATION_OCTET_STREAM_VALUE, byteArrayOf(1)))
					with(csrf())
				}.andExpect { status { isCreated() } }
				.json()
		guessed.get("mime").asString() shouldBe "image/jpeg"
		guessed.get("name").asString() shouldBe "lake.jpg"
		guessed.get("size").asLong() shouldBe 1
		guessed.get("reference").asString() shouldBe "cockpit-file:${guessed.get("fileId").asString()}"

		mockMvc
			.multipart("$JOURNAL/images") {
				file(MockMultipartFile("file", "", "image/png", byteArrayOf(1, 2)))
				with(csrf())
			}.andExpect {
				status { isCreated() }
				jsonPath("$.name") { value("image") }
			}

		mockMvc
			.multipart("$JOURNAL/images") {
				file(MockMultipartFile("file", "data.bin", null, byteArrayOf(1)))
				with(csrf())
			}.andExpect { status { isBadRequest() } }
		count("select count(*) from file") shouldBe 2
	}

	// --- migration constraints

	@Test
	fun `the schema enforces the mood range and one active entry per date`() {
		val insert =
			"insert into journal_entry (id, created_at, updated_at, version, entry_date, mood, deleted_at) " +
				"values (?, now(), now(), 0, ?, ?, ?)"
		val date = LocalDate.parse("2026-01-01")
		shouldThrow<DataIntegrityViolationException> { jdbc.update(insert, UUID.randomUUID(), date, 0, null) }
		shouldThrow<DataIntegrityViolationException> { jdbc.update(insert, UUID.randomUUID(), date, 6, null) }

		jdbc.update(insert, UUID.randomUUID(), date, 5, null)
		shouldThrow<DataIntegrityViolationException> { jdbc.update(insert, UUID.randomUUID(), date, 1, null) }
		// Entries in the Trash do not count.
		jdbc.update(insert, UUID.randomUUID(), date, 1, java.sql.Timestamp.from(NOW))
		jdbc.update(insert, UUID.randomUUID(), date, null, java.sql.Timestamp.from(NOW))
		count("select count(*) from journal_entry") shouldBe 3
		count("select count(*) from journal_entry where body_md = ''") shouldBe 3
	}

	private companion object {
		const val JOURNAL = "/api/v1/journal"
		const val ENTRIES = "$JOURNAL/entries"

		// Wednesday 2026-03-04, 09:00 in Zurich (UTC+1).
		val NOW: Instant = Instant.parse("2026-03-04T08:00:00Z")
	}
}
