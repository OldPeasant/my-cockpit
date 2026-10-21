package ch.sonensei.cockpit.notes

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class NoteWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("delete from note")
		jdbc.execute("delete from link")
		jdbc.execute("delete from search_document")
		jdbc.execute("delete from file")
		while ((jdbc.queryForObject("select count(*) from folder", Long::class.java) ?: 0) > 0) {
			jdbc.execute("delete from folder f where not exists (select 1 from folder c where c.parent_id = f.id)")
		}
		clock.set(MutableClockTestConfiguration.START)
	}

	// --- helpers

	private fun ResultActionsDsl.json(): JsonNode = andReturn().response.contentAsString.let(jsonMapper::readTree)

	private fun post(
		path: String,
		body: String,
		ifMatch: String? = null,
	) = mockMvc.post(path) {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun patch(
		id: String,
		body: String,
		ifMatch: String?,
	) = mockMvc.patch("$NOTES/$id") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun newNote(body: String = """{"bodyMd":"Groceries\n- milk"}"""): JsonNode =
		post(NOTES, body).andExpect { status { isCreated() } }.json()

	private fun fetch(id: String): JsonNode = mockMvc.get("$NOTES/$id").andExpect { status { isOk() } }.json()

	private fun change(
		note: JsonNode,
		body: String,
	): JsonNode = patch(note.id(), body, note.etag()).andExpect { status { isOk() } }.json()

	private fun list(query: String = ""): JsonNode = mockMvc.get("$NOTES$query").andExpect { status { isOk() } }.json()

	private fun JsonNode.id(): String = get("id").asString()

	private fun JsonNode.etag(): String = "\"${get("version").asLong()}\""

	private fun JsonNode.titles(): List<String> = get("items").values().map { it.get("displayTitle").asString() }

	private fun revisions(id: String): JsonNode =
		mockMvc
			.get("$NOTES/$id/revisions")
			.andExpect { status { isOk() } }
			.json()
			.get("items")

	private fun searchDocument(id: String): Map<String, Any?>? =
		jdbc
			.queryForList("select title, body from search_document where item_type = 'NOTE' and item_id = ?::uuid", id)
			.singleOrNull()

	private fun syncTargets(id: String): List<String?> =
		jdbc.queryForList(
			"select target_type || ':' || target_id from link where source_type = 'NOTE' and source_id = ?::uuid and origin = 'SYNC' " +
				"order by target_id",
			String::class.java,
			id,
		)

	private fun noteEvents(events: ApplicationEvents): List<Pair<String, ChangeOp>> =
		events
			.stream(ChangeEvent::class.java)
			.filter { it.type == ItemType.NOTE.name }
			.map { it.id.toString() to it.op }
			.toList()

	// --- CRUD

	@Test
	fun `create returns the note with ETag and Location, the title derived from the body`() {
		val result =
			post(NOTES, """{"bodyMd":"# Packing list\n- [ ] passport","tags":["Travel"," #summer "],"pinned":true}""")
				.andExpect { status { isCreated() } }
				.andReturn()
		val note = jsonMapper.readTree(result.response.contentAsString)

		note.get("title").isNull shouldBe true
		note.get("displayTitle").asString() shouldBe "Packing list"
		note.get("bodyMd").asString() shouldBe "# Packing list\n- [ ] passport"
		note.get("tags").values().map { it.asString() } shouldContainExactly listOf("summer", "Travel")
		note.get("pinned").asBoolean() shouldBe true
		note.get("archived").asBoolean() shouldBe false
		result.response.getHeader(HttpHeaders.ETAG) shouldBe note.etag()
		result.response.getHeader(HttpHeaders.LOCATION)!!.endsWith("$NOTES/${note.id()}") shouldBe true
		fetch(note.id()) shouldBe note
	}

	@Test
	fun `an empty create makes an untitled note, a given title wins over the body`() {
		newNote("{}").get("displayTitle").asString() shouldBe "Untitled note"

		val titled = newNote("""{"title":"  Ideas  ","bodyMd":"first line"}""")

		titled.get("title").asString() shouldBe "Ideas"
		titled.get("displayTitle").asString() shouldBe "Ideas"
	}

	@Test
	fun `PATCH changes only the sent fields and needs a current If-Match`() {
		val note = newNote("""{"title":"Ideas","bodyMd":"one","tags":["a"]}""")

		patch(note.id(), """{"pinned":true}""", null).andExpect { status { isPreconditionRequired() } }
		patch(note.id(), """{"pinned":true}""", "\"99\"").andExpect { status { isPreconditionFailed() } }
		val changed = change(note, """{"pinned":true,"archived":true,"title":null}""")

		changed.get("pinned").asBoolean() shouldBe true
		changed.get("archived").asBoolean() shouldBe true
		changed.get("title").isNull shouldBe true
		changed.get("displayTitle").asString() shouldBe "one"
		changed.get("bodyMd").asString() shouldBe "one"
		changed.get("tags").values().map { it.asString() } shouldContainExactly listOf("a")
		(changed.get("version").asLong() > note.get("version").asLong()) shouldBe true
	}

	@Test
	fun `PATCH rejects unknown fields, nulls and wrong types`() {
		val note = newNote()

		patch(note.id(), """{"color":"red"}""", note.etag()).andExpect { status { isBadRequest() } }
		patch(note.id(), """{"bodyMd":null}""", note.etag()).andExpect { status { isBadRequest() } }
		patch(note.id(), """{"tags":null}""", note.etag()).andExpect { status { isBadRequest() } }
		patch(note.id(), """{"pinned":"maybe"}""", note.etag()).andExpect { status { isBadRequest() } }
		fetch(note.id()).get("version") shouldBe note.get("version")
	}

	@Test
	fun `invalid fields are rejected with 400`() {
		post(NOTES, """{"title":"${"x".repeat(501)}"}""").andExpect { status { isBadRequest() } }
		post(NOTES, """{"tags":["   "]}""").andExpect { status { isBadRequest() } }
		post(NOTES, """{"tags":["a",null]}""").andExpect { status { isBadRequest() } }
		post(NOTES, """{"tags":["${"t".repeat(51)}"]}""").andExpect { status { isBadRequest() } }
		post(NOTES, """{"tags":[${(1..51).joinToString { "\"t$it\"" }}]}""").andExpect { status { isBadRequest() } }
		post(NOTES, """{"bodyMd":"${"b".repeat(100_001)}"}""").andExpect { status { isBadRequest() } }
		jdbc.queryForObject("select count(*) from note", Long::class.java) shouldBe 0
	}

	@Test
	fun `tags are deduplicated regardless of case and reuse the spelling of other notes`() {
		newNote("""{"tags":["Recipes"]}""")

		val note = newNote("""{"tags":["recipes","Pasta","PASTA","#pasta"]}""")

		note.get("tags").values().map { it.asString() } shouldContainExactly listOf("Pasta", "Recipes")
		val tags =
			mockMvc
				.get("$NOTES/tags")
				.andExpect { status { isOk() } }
				.json()
				.get("items")
		tags.values().map { it.get("tag").asString() to it.get("count").asLong() } shouldContainExactly listOf("Pasta" to 1L, "Recipes" to 2L)
	}

	@Test
	fun `unknown notes give 404`() {
		val id = UUID.randomUUID()
		mockMvc.get("$NOTES/$id").andExpect { status { isNotFound() } }
		patch(id.toString(), "{}", "\"0\"").andExpect { status { isNotFound() } }
		mockMvc.get("$NOTES/$id/revisions").andExpect { status { isNotFound() } }
	}

	// --- list

	@Test
	fun `list shows pinned notes first, then the most recently changed, and filters by tag, pinned and archived`() {
		val old = newNote("""{"title":"Old","tags":["work"]}""")
		clock.advance(Duration.ofMinutes(1))
		newNote("""{"title":"Pinned","pinned":true}""")
		clock.advance(Duration.ofMinutes(1))
		newNote("""{"title":"New","tags":["Work"]}""")
		clock.advance(Duration.ofMinutes(1))
		newNote("""{"title":"Archived","archived":true,"tags":["work"]}""")

		list().titles() shouldContainExactly listOf("Pinned", "New", "Old")
		list("?tag=WORK").titles() shouldContainExactly listOf("New", "Old")
		list("?pinned=false").titles() shouldContainExactly listOf("New", "Old")
		list("?archived=true").titles() shouldContainExactly listOf("Archived")
		list("?archived=all&tag=work").titles() shouldContainExactly listOf("Archived", "New", "Old")
		mockMvc.get("$NOTES?archived=maybe").andExpect { status { isBadRequest() } }

		clock.advance(Duration.ofMinutes(1))
		change(old, """{"bodyMd":"edited"}""")
		list().titles() shouldContainExactly listOf("Pinned", "Old", "New")
	}

	@Test
	fun `list pages with a cursor`() {
		repeat(5) {
			newNote("""{"title":"Note $it","pinned":${it % 2 == 0}}""")
			clock.advance(Duration.ofSeconds(1))
		}

		val first = list("?limit=2")
		val second = list("?limit=2&cursor=${first.get("nextCursor").asString()}")
		val third = list("?limit=2&cursor=${second.get("nextCursor").asString()}")

		(first.titles() + second.titles() + third.titles()) shouldContainExactly
			listOf("Note 4", "Note 2", "Note 0", "Note 3", "Note 1")
		third.get("nextCursor").isNull shouldBe true
		mockMvc.get("$NOTES?cursor=bm9wZQ").andExpect { status { isBadRequest() } }
	}

	// --- links

	@Test
	fun `inline links in the body are synced to the link table on save`() {
		val other = newNote("""{"title":"Other"}""")
		val task = "0192f0c0-0000-7000-8000-00000000000a"
		val note = newNote("""{"bodyMd":"See [[note:${other.id()}]] and [[task:$task]]"}""")

		syncTargets(note.id()) shouldContainExactlyInAnyOrder listOf("NOTE:${other.id()}", "TASK:$task")

		val changed = change(note, """{"bodyMd":"Only [[task:$task]] now"}""")
		syncTargets(note.id()) shouldContainExactly listOf("TASK:$task")

		change(changed, """{"bodyMd":"no links"}""")
		syncTargets(note.id()).shouldBeEmpty()
	}

	@Test
	fun `linked notes are resolved in the links panel`() {
		val other = newNote("""{"bodyMd":"# Meeting notes\ntext"}""")
		val note = newNote("""{"bodyMd":"[[note:${other.id()}]]"}""")

		val links =
			mockMvc
				.get("/api/v1/links?type=NOTE&id=${note.id()}")
				.andExpect { status { isOk() } }
				.json()
				.get("items")

		links
			.values()
			.single()
			.get("target")
			.get("title")
			.asString() shouldBe "Meeting notes"
		links
			.values()
			.single()
			.get("target")
			.get("deepLink")
			.asString() shouldBe "/notes/${other.id()}"
	}

	// --- images

	@Test
	fun `an image is stored in Attachments-Notes and its reference returned`() {
		val note = newNote()
		val bytes = byteArrayOf(-119, 80, 78, 71, 1, 2, 3)

		val image =
			mockMvc
				.multipart("$NOTES/${note.id()}/images") {
					file(MockMultipartFile("file", "paste.png", "image/png", bytes))
					with(csrf())
				}.andExpect { status { isCreated() } }
				.json()

		val fileId = image.get("fileId").asString()
		image.get("reference").asString() shouldBe "cockpit-file:$fileId"
		image.get("markdown").asString() shouldBe "![](cockpit-file:$fileId)"
		image.get("mime").asString() shouldBe "image/png"
		image.get("size").asLong() shouldBe bytes.size.toLong()
		val path =
			jdbc.queryForObject(
				"select p.name || '/' || f.name from file x join folder f on f.id = x.folder_id join folder p on p.id = f.parent_id " +
					"where x.id = ?::uuid",
				String::class.java,
				fileId,
			)
		path shouldBe "Attachments/Notes"
		mockMvc
			.get("/api/v1/files/$fileId/content")
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsByteArray shouldBe bytes
		fetch(note.id()).get("version") shouldBe note.get("version")
	}

	@Test
	fun `only images are accepted, and only for existing notes`() {
		val note = newNote()

		mockMvc
			.multipart("$NOTES/${note.id()}/images") {
				file(MockMultipartFile("file", "notes.txt", "text/plain", "hello".toByteArray()))
				with(csrf())
			}.andExpect { status { isBadRequest() } }
		mockMvc
			.multipart("$NOTES/${UUID.randomUUID()}/images") {
				file(MockMultipartFile("file", "a.png", "image/png", byteArrayOf(1)))
				with(csrf())
			}.andExpect { status { isNotFound() } }
		jdbc.queryForObject("select count(*) from file", Long::class.java) shouldBe 0
	}

	// --- revisions

	@Test
	fun `a content change keeps the previous version, at most one per five minutes`() {
		var note = newNote("""{"bodyMd":"v1"}""")
		revisions(note.id()).isEmpty shouldBe true

		clock.advance(Duration.ofMinutes(1))
		note = change(note, """{"bodyMd":"v2"}""")
		clock.advance(Duration.ofMinutes(1))
		note = change(note, """{"bodyMd":"v3"}""")
		note = change(note, """{"pinned":true}""")

		revisions(note.id()).values().map { it.get("number").asInt() to it.get("bodyMd").asString() } shouldContainExactly listOf(1 to "v1")

		clock.advance(Duration.ofMinutes(5))
		change(note, """{"bodyMd":"v4","title":"T"}""")

		val kept = revisions(note.id())
		kept.values().map { it.get("number").asInt() to it.get("bodyMd").asString() } shouldContainExactly listOf(2 to "v3", 1 to "v1")
		kept
			.values()
			.first()
			.get("title")
			.isNull shouldBe true
		kept
			.values()
			.last()
			.get("savedAt")
			.asString() shouldBe MutableClockTestConfiguration.START.toString()
	}

	@Test
	fun `only the newest 20 revisions are kept`() {
		var note = newNote("""{"bodyMd":"v0"}""")
		repeat(25) {
			clock.advance(Duration.ofMinutes(10))
			note = change(note, """{"bodyMd":"v${it + 1}"}""")
		}

		val kept = revisions(note.id())

		kept.size() shouldBe 20
		kept
			.values()
			.first()
			.get("number")
			.asInt() shouldBe 25
		kept
			.values()
			.first()
			.get("bodyMd")
			.asString() shouldBe "v24"
		kept
			.values()
			.last()
			.get("number")
			.asInt() shouldBe 6
		post("$NOTES/${note.id()}/revisions/5/restore", "", note.etag()).andExpect { status { isNotFound() } }
	}

	@Test
	fun `restoring a revision puts its content back and keeps the replaced one`() {
		val other = newNote("""{"title":"Other"}""")
		var note = newNote("""{"title":"Draft","bodyMd":"see [[note:${other.id()}]]","tags":["x"]}""")
		clock.advance(Duration.ofMinutes(10))
		note = change(note, """{"title":null,"bodyMd":"oops, all gone"}""")
		syncTargets(note.id()).shouldBeEmpty()

		post("$NOTES/${note.id()}/revisions/1/restore", "", null).andExpect { status { isPreconditionRequired() } }
		val restored = post("$NOTES/${note.id()}/revisions/1/restore", "", note.etag()).andExpect { status { isOk() } }.json()

		restored.get("title").asString() shouldBe "Draft"
		restored.get("bodyMd").asString() shouldBe "see [[note:${other.id()}]]"
		restored.get("tags").values().map { it.asString() } shouldContainExactly listOf("x")
		syncTargets(note.id()) shouldContainExactly listOf("NOTE:${other.id()}")
		revisions(note.id()).values().map { it.get("number").asInt() to it.get("bodyMd").asString() } shouldContainExactly
			listOf(2 to "oops, all gone", 1 to "see [[note:${other.id()}]]")
		searchDocument(note.id())!!["title"] shouldBe "Draft"
	}

	// --- search, trash, events

	@Test
	fun `notes are indexed for search with Markdown stripped`() {
		val note = newNote("""{"bodyMd":"# Garden\nPlant **tulips** in [autumn](https://example.org)"}""")

		val document = searchDocument(note.id())!!
		document["title"] shouldBe "Garden"
		document["body"] shouldBe "Garden\nPlant tulips in autumn"

		val hits =
			mockMvc
				.get("/api/v1/search?q=tulips&types=NOTE")
				.andExpect { status { isOk() } }
				.andReturn()
				.response.contentAsString
		(note.id() in hits) shouldBe true
		hits shouldNotContain "**"
	}

	@Test
	fun `a deleted note goes to the Trash and leaves the search, a restore brings it back`() {
		val note = newNote("""{"title":"Secret plan","tags":["x"]}""")

		mockMvc.delete("$NOTES/${note.id()}") { with(csrf()) }.andExpect { status { isPreconditionRequired() } }
		mockMvc
			.delete("$NOTES/${note.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, note.etag())
			}.andExpect { status { isNoContent() } }

		mockMvc.get("$NOTES/${note.id()}").andExpect { status { isNotFound() } }
		list("?archived=all").get("items").isEmpty shouldBe true
		searchDocument(note.id()) shouldBe null
		val trash =
			mockMvc
				.get("/api/v1/trash")
				.andExpect { status { isOk() } }
				.json()
				.get("items")
		trash
			.values()
			.single()
			.get("title")
			.asString() shouldBe "Secret plan"

		mockMvc.post("/api/v1/trash/NOTE/${note.id()}/restore") { with(csrf()) }.andExpect { status { is2xxSuccessful() } }

		fetch(note.id()).get("title").asString() shouldBe "Secret plan"
		searchDocument(note.id())!!["title"] shouldBe "Secret plan"
	}

	@Test
	fun `purging a note deletes it with its tags, revisions and links`() {
		var note = newNote("""{"bodyMd":"[[task:0192f0c0-0000-7000-8000-00000000000a]]","tags":["x"]}""")
		clock.advance(Duration.ofMinutes(10))
		note = change(note, """{"bodyMd":"[[task:0192f0c0-0000-7000-8000-00000000000b]]"}""")
		mockMvc
			.delete("$NOTES/${note.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, note.etag())
			}.andExpect { status { isNoContent() } }

		mockMvc.delete("/api/v1/trash/NOTE/${note.id()}") { with(csrf()) }.andExpect { status { is2xxSuccessful() } }

		jdbc.queryForObject("select count(*) from note", Long::class.java) shouldBe 0
		jdbc.queryForObject("select count(*) from note_tag", Long::class.java) shouldBe 0
		jdbc.queryForObject("select count(*) from note_revision", Long::class.java) shouldBe 0
		jdbc.queryForObject("select count(*) from link", Long::class.java) shouldBe 0
	}

	@Test
	fun `every change publishes a change event`(events: ApplicationEvents) {
		val note = newNote()
		val changed = change(note, """{"pinned":true}""")
		mockMvc
			.delete("$NOTES/${note.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, changed.etag())
			}.andExpect { status { isNoContent() } }

		noteEvents(events) shouldContainExactly
			listOf(note.id() to ChangeOp.CREATED, note.id() to ChangeOp.UPDATED, note.id() to ChangeOp.DELETED)
	}

	private companion object {
		const val NOTES = "/api/v1/notes"
	}
}
