package ch.sonensei.cockpit.notes

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * Edge cases of the notes API (task notes-api): list filter combinations, tag spelling on PATCH, the revision rules
 * (title-only changes, the 5-minute boundary, restores), If-Match on restore, image uploads without a name or content
 * type, NUL characters and change events of restores.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class NoteEdgeCaseWebTests(
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

	private fun newNote(body: String): JsonNode =
		mockMvc
			.post(NOTES) {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = body
			}.andExpect { status { isCreated() } }
			.json()

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

	private fun change(
		note: JsonNode,
		body: String,
	): JsonNode = patch(note.id(), body, note.etag()).andExpect { status { isOk() } }.json()

	private fun restore(
		id: String,
		number: Int,
		ifMatch: String?,
	) = mockMvc.post("$NOTES/$id/revisions/$number/restore") {
		with(csrf())
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun fetch(id: String): JsonNode = mockMvc.get("$NOTES/$id").andExpect { status { isOk() } }.json()

	private fun list(query: String = ""): JsonNode = mockMvc.get("$NOTES$query").andExpect { status { isOk() } }.json()

	private fun revisions(id: String): List<Pair<Int, String>> =
		mockMvc
			.get("$NOTES/$id/revisions")
			.andExpect { status { isOk() } }
			.json()
			.get("items")
			.values()
			.map { it.get("number").asInt() to "${it.get("title").asString(null)}|${it.get("bodyMd").asString()}" }

	private fun upload(
		id: String,
		file: MockMultipartFile,
	) = mockMvc.multipart("$NOTES/$id/images") {
		file(file)
		with(csrf())
	}

	private fun JsonNode.id(): String = get("id").asString()

	private fun JsonNode.etag(): String = "\"${get("version").asLong()}\""

	private fun JsonNode.titles(): List<String> = get("items").values().map { it.get("displayTitle").asString() }

	private fun JsonNode.tagList(): List<String> = get("tags").values().map { it.asString() }

	private fun fileCount(): Long = jdbc.queryForObject("select count(*) from file", Long::class.java) ?: 0

	// --- list filters

	@Test
	fun `list filters combine pinned, archived and tag`() {
		newNote("""{"title":"Plain"}""")
		clock.advance(Duration.ofMinutes(1))
		newNote("""{"title":"Pinned","pinned":true,"tags":["home"]}""")
		clock.advance(Duration.ofMinutes(1))
		newNote("""{"title":"Archived","archived":true}""")
		clock.advance(Duration.ofMinutes(1))
		newNote("""{"title":"Archived pinned","archived":true,"pinned":true,"tags":["Home"]}""")

		list("?archived=true&pinned=true").titles() shouldContainExactly listOf("Archived pinned")
		list("?archived=true&pinned=false").titles() shouldContainExactly listOf("Archived")
		list("?archived=all&pinned=true").titles() shouldContainExactly listOf("Archived pinned", "Pinned")
		list("?archived=all&pinned=false").titles() shouldContainExactly listOf("Archived", "Plain")
		list("?archived=false&pinned=true").titles() shouldContainExactly listOf("Pinned")
		list("?archived=all").titles() shouldContainExactly listOf("Archived pinned", "Pinned", "Archived", "Plain")
		list("?archived=TRUE&tag=home").titles() shouldContainExactly listOf("Archived pinned")
		list("?archived=all&pinned=true&tag=HOME").titles() shouldContainExactly listOf("Archived pinned", "Pinned")
		list("?archived=all&pinned=false&tag=home").titles().shouldBeEmpty()
	}

	@Test
	fun `the tag filter ignores a leading hash, surrounding blanks and a blank tag`() {
		newNote("""{"title":"Tagged","tags":["Garden"]}""")
		clock.advance(Duration.ofMinutes(1))
		newNote("""{"title":"Untagged"}""")

		fun byTag(tag: String) =
			mockMvc
				.get(NOTES) { param("tag", tag) }
				.andExpect { status { isOk() } }
				.json()
				.titles()

		byTag("#garden") shouldContainExactly listOf("Tagged")
		byTag(" #GARDEN ") shouldContainExactly listOf("Tagged")
		byTag(" ") shouldContainExactly listOf("Untagged", "Tagged")
		byTag("gardening").shouldBeEmpty()
	}

	@Test
	fun `a tag-filtered list pages with a cursor`() {
		repeat(5) {
			newNote("""{"title":"Recipe $it","tags":["${if (it % 2 == 0) "food" else "FOOD"}"]}""")
			clock.advance(Duration.ofSeconds(1))
		}
		newNote("""{"title":"Other","tags":["misc"]}""")

		val first = list("?tag=food&limit=2")
		val second = list("?tag=food&limit=2&cursor=${first.get("nextCursor").asString()}")
		val third = list("?tag=food&limit=2&cursor=${second.get("nextCursor").asString()}")

		(first.titles() + second.titles() + third.titles()) shouldContainExactly
			listOf("Recipe 4", "Recipe 3", "Recipe 2", "Recipe 1", "Recipe 0")
		third.get("nextCursor").isNull shouldBe true
	}

	@Test
	fun `malformed cursors and page sizes are rejected with 400`() {
		listOf("maybe|2026-01-01T00:00:00Z|0192f0c0-0000-7000-8000-000000000001", "true|yesterday|x", "true|x").forEach {
			// Well-formed cursor encoding around a position that is not one of a note.
			mockMvc.get(NOTES) { param("cursor", CursorRequest.encodeCursor(it)) }.andExpect { status { isBadRequest() } }
		}
		mockMvc.get("$NOTES?limit=0").andExpect { status { isBadRequest() } }
		mockMvc.get("$NOTES?limit=201").andExpect { status { isBadRequest() } }
	}

	// --- tags

	@Test
	fun `PATCH tags keep the note's own spelling and reuse other notes' spelling for new tags`() {
		newNote("""{"tags":["Recipes"]}""")
		val note = newNote("""{"tags":["work"]}""")

		val changed = change(note, """{"tags":["WORK"," #recipes ","New"]}""")

		changed.tagList() shouldContainExactly listOf("New", "Recipes", "work")
		val cleared = change(changed, """{"tags":[]}""")
		cleared.tagList().shouldBeEmpty()
		fetch(note.id()).tagList().shouldBeEmpty()
	}

	@Test
	fun `the tag list counts only notes outside the Trash`() {
		newNote("""{"tags":["shared"]}""")
		val trashed = newNote("""{"tags":["shared","gone"]}""")
		mockMvc
			.delete("$NOTES/${trashed.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, trashed.etag())
			}.andExpect { status { isNoContent() } }

		val tags =
			mockMvc
				.get("$NOTES/tags")
				.andExpect { status { isOk() } }
				.json()
				.get("items")
				.values()
				.map { it.get("tag").asString() to it.get("count").asLong() }

		tags shouldContainExactly listOf("shared" to 1L)
	}

	@Test
	fun `a tag-only change moves the note up as most recently changed`() {
		val older = newNote("""{"title":"Older"}""")
		clock.advance(Duration.ofMinutes(1))
		newNote("""{"title":"Newer"}""")
		clock.advance(Duration.ofMinutes(1))

		val changed = change(older, """{"tags":["moved"]}""")

		Instant.parse(changed.get("updatedAt").asString()) shouldBe MutableClockTestConfiguration.START.plus(Duration.ofMinutes(2))
		(changed.get("version").asLong() > older.get("version").asLong()) shouldBe true
		fetch(older.id()).get("version") shouldBe changed.get("version")
		list().titles() shouldContainExactly listOf("Older", "Newer")
	}

	// --- title and body

	@Test
	fun `a blank title is stored as no title, NUL characters are rejected`() {
		val note = newNote("""{"title":"Plan","bodyMd":"Body line"}""")

		val changed = change(note, """{"title":"   "}""")

		changed.get("title").isNull shouldBe true
		changed.get("displayTitle").asString() shouldBe "Body line"
		patch(note.id(), """{"title":"a\u0000b"}""", changed.etag()).andExpect { status { isBadRequest() } }
		patch(note.id(), """{"bodyMd":"a\u0000b"}""", changed.etag()).andExpect { status { isBadRequest() } }
		fetch(note.id()).get("version") shouldBe changed.get("version")
	}

	@Test
	fun `values exactly at their maximum are accepted, duplicates do not count towards the tag limit`() {
		val tags = (1..50).map { "t$it".padEnd(50, 'x') }
		val note =
			newNote(
				"""{"title":"${"x".repeat(500)}","bodyMd":"${"b".repeat(100_000)}","tags":[${tags.joinToString { "\"$it\"" }}]}""",
			)

		note.get("title").asString().length shouldBe 500
		note.get("bodyMd").asString().length shouldBe 100_000
		note.tagList().size shouldBe 50
		val withDuplicates = tags + tags.first().uppercase() + "#${tags.last()}"
		change(note, """{"tags":[${withDuplicates.joinToString { "\"$it\"" }}]}""").tagList().size shouldBe 50
	}

	@Test
	fun `an invalid PATCH changes nothing, not even the valid fields sent with it`() {
		val note = newNote("""{"title":"Plan","bodyMd":"Body","tags":["Home"]}""")
		clock.advance(Duration.ofMinutes(10))

		patch(note.id(), """{"pinned":true,"archived":true,"tags":["Fresh"],"bodyMd":"New","title":"${"x".repeat(501)}"}""", note.etag())
			.andExpect { status { isBadRequest() } }
		patch(note.id(), """{"pinned":true,"tags":["Fresh"],"bodyMd":"a\u0000b"}""", note.etag())
			.andExpect { status { isBadRequest() } }

		val current = fetch(note.id())
		current.get("version") shouldBe note.get("version")
		current.get("pinned").asBoolean() shouldBe false
		current.get("archived").asBoolean() shouldBe false
		current.get("bodyMd").asString() shouldBe "Body"
		current.tagList() shouldContainExactly listOf("Home")
		revisions(note.id()).shouldBeEmpty()
		mockMvc
			.get("$NOTES/tags")
			.andExpect { status { isOk() } }
			.json()
			.get("items")
			.values()
			.map { it.get("tag").asString() } shouldContainExactly listOf("Home")
	}

	// --- revisions

	@Test
	fun `a title-only change keeps the previous content as a revision`() {
		val note = newNote("""{"title":"First","bodyMd":"same body"}""")
		clock.advance(Duration.ofMinutes(10))

		change(note, """{"title":"Second"}""")

		revisions(note.id()) shouldContainExactly listOf(1 to "First|same body")
	}

	@Test
	fun `changes that leave title and body as they are keep no revision`() {
		var note = newNote("""{"title":"Same","bodyMd":"text"}""")
		clock.advance(Duration.ofMinutes(10))
		note = change(note, """{"title":"  Same  ","bodyMd":"text"}""")
		note = change(note, """{"tags":["x"],"pinned":true,"archived":true}""")

		revisions(note.id()).shouldBeEmpty()
	}

	@Test
	fun `a new revision is kept exactly five minutes after the newest one, not earlier`() {
		var note = newNote("""{"bodyMd":"v1"}""")
		clock.advance(Duration.ofMinutes(1))
		note = change(note, """{"bodyMd":"v2"}""") // revision 1 (v1), replaced at START + 1 min
		clock.advance(Duration.ofMinutes(5).minusSeconds(1))
		note = change(note, """{"bodyMd":"v3"}""") // 4:59 later: no revision
		revisions(note.id()).map { it.first } shouldContainExactly listOf(1)

		clock.advance(Duration.ofSeconds(1))
		change(note, """{"bodyMd":"v4"}""") // exactly 5:00 later: revision 2 (v3)

		revisions(note.id()) shouldContainExactly listOf(2 to "null|v3", 1 to "null|v1")
	}

	@Test
	fun `a restore keeps the replaced content even within five minutes and can be undone`() {
		var note = newNote("""{"bodyMd":"v1"}""")
		clock.advance(Duration.ofMinutes(10))
		note = change(note, """{"bodyMd":"v2"}""") // revision 1 (v1)
		clock.advance(Duration.ofMinutes(1))
		note = change(note, """{"bodyMd":"v3"}""") // within 5 minutes: no revision

		note = restore(note.id(), 1, note.etag()).andExpect { status { isOk() } }.json()

		note.get("bodyMd").asString() shouldBe "v1"
		revisions(note.id()) shouldContainExactly listOf(2 to "null|v3", 1 to "null|v1")

		val undone = restore(note.id(), 2, note.etag()).andExpect { status { isOk() } }.json()

		undone.get("bodyMd").asString() shouldBe "v3"
		// The replaced content (v1) equals revision 1 but not the newest one, so it is kept again.
		revisions(note.id()) shouldContainExactly listOf(3 to "null|v1", 2 to "null|v3", 1 to "null|v1")
	}

	@Test
	fun `a restore does not keep the replaced content again when it equals the newest revision`() {
		var note = newNote("""{"bodyMd":"A"}""")
		clock.advance(Duration.ofMinutes(10))
		note = change(note, """{"bodyMd":"B"}""") // revision 1 (A)
		clock.advance(Duration.ofMinutes(10))
		note = change(note, """{"bodyMd":"C"}""") // revision 2 (B)
		clock.advance(Duration.ofMinutes(1))
		note = change(note, """{"bodyMd":"B"}""") // within 5 minutes: no revision; current equals revision 2

		val restored = restore(note.id(), 1, note.etag()).andExpect { status { isOk() } }.json()

		restored.get("bodyMd").asString() shouldBe "A"
		revisions(note.id()) shouldContainExactly listOf(2 to "null|B", 1 to "null|A")
	}

	@Test
	fun `restoring a revision that differs only in the title keeps the body and its links`() {
		val task = "0192f0c0-0000-7000-8000-00000000000a"
		var note = newNote("""{"title":"Original","bodyMd":"see [[task:$task]]"}""")
		clock.advance(Duration.ofMinutes(10))
		note = change(note, """{"title":"Renamed"}""")

		val restored = restore(note.id(), 1, note.etag()).andExpect { status { isOk() } }.json()

		restored.get("title").asString() shouldBe "Original"
		restored.get("bodyMd").asString() shouldBe "see [[task:$task]]"
		jdbc.queryForList(
			"select target_type || ':' || target_id from link where source_type = 'NOTE' and source_id = ?::uuid",
			String::class.java,
			note.id(),
		) shouldContainExactly listOf("TASK:$task")
		revisions(note.id()) shouldContainExactly listOf(2 to "Renamed|see [[task:$task]]", 1 to "Original|see [[task:$task]]")
	}

	@Test
	fun `restoring the content the note already has changes nothing`(events: ApplicationEvents) {
		var note = newNote("""{"bodyMd":"v1"}""")
		clock.advance(Duration.ofMinutes(10))
		note = change(note, """{"bodyMd":"v2"}""")
		clock.advance(Duration.ofMinutes(10))
		note = change(note, """{"bodyMd":"v1"}""") // revision 2 (v2)
		val eventsBefore = events.stream(ChangeEvent::class.java).count()

		val same = restore(note.id(), 1, note.etag()).andExpect { status { isOk() } }.json()

		same.get("version") shouldBe note.get("version")
		same.get("bodyMd").asString() shouldBe "v1"
		revisions(note.id()) shouldContainExactly listOf(2 to "null|v2", 1 to "null|v1")
		events.stream(ChangeEvent::class.java).count() shouldBe eventsBefore
	}

	@Test
	fun `restore of an unknown revision is 404, a stale or missing If-Match changes nothing`() {
		var note = newNote("""{"title":"Draft","bodyMd":"v1"}""")
		val stale = note.etag()
		clock.advance(Duration.ofMinutes(10))
		note = change(note, """{"bodyMd":"v2"}""")

		restore(note.id(), 99, note.etag()).andExpect {
			status { isNotFound() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
		}
		restore(note.id(), 0, note.etag()).andExpect { status { isNotFound() } }
		restore(note.id(), -1, note.etag()).andExpect { status { isNotFound() } }
		restore(note.id(), 1, stale).andExpect {
			status { isPreconditionFailed() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
		}
		restore(note.id(), 1, "W/${note.etag()}").andExpect { status { isPreconditionFailed() } }
		restore(note.id(), 1, null).andExpect { status { isPreconditionRequired() } }
		mockMvc
			.post("$NOTES/${note.id()}/revisions/abc/restore") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, note.etag())
			}.andExpect { status { isBadRequest() } }

		fetch(note.id()).get("bodyMd").asString() shouldBe "v2"
		fetch(note.id()).get("version") shouldBe note.get("version")
		revisions(note.id()) shouldContainExactly listOf(1 to "Draft|v1")
	}

	@Test
	fun `If-Match star and a list containing the current ETag are accepted`() {
		val note = newNote("""{"bodyMd":"v1"}""")

		val starred = patch(note.id(), """{"pinned":true}""", "*").andExpect { status { isOk() } }.json()
		patch(note.id(), """{"pinned":false}""", "\"999\", ${starred.etag()}").andExpect { status { isOk() } }
	}

	@Test
	fun `revisions and restore of a note in the Trash are 404`() {
		var note = newNote("""{"bodyMd":"v1"}""")
		clock.advance(Duration.ofMinutes(10))
		note = change(note, """{"bodyMd":"v2"}""")
		mockMvc
			.delete("$NOTES/${note.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, note.etag())
			}.andExpect { status { isNoContent() } }

		mockMvc.get("$NOTES/${note.id()}/revisions").andExpect { status { isNotFound() } }
		restore(note.id(), 1, note.etag()).andExpect { status { isNotFound() } }
		patch(note.id(), """{"pinned":true}""", note.etag()).andExpect { status { isNotFound() } }
		mockMvc
			.delete("$NOTES/${note.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, note.etag())
			}.andExpect { status { isNotFound() } }
	}

	@Test
	fun `a stale DELETE is rejected with 412 and leaves the note`() {
		val note = newNote("""{"bodyMd":"keep me"}""")
		val changed = change(note, """{"pinned":true}""")

		mockMvc
			.delete("$NOTES/${note.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, note.etag())
			}.andExpect { status { isPreconditionFailed() } }

		fetch(note.id()).get("version") shouldBe changed.get("version")
	}

	// --- events

	@Test
	fun `restoring a revision and restoring from the Trash publish UPDATED events`(events: ApplicationEvents) {
		var note = newNote("""{"bodyMd":"v1"}""")
		clock.advance(Duration.ofMinutes(10))
		note = change(note, """{"bodyMd":"v2"}""")
		note = restore(note.id(), 1, note.etag()).andExpect { status { isOk() } }.json()
		mockMvc
			.delete("$NOTES/${note.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, note.etag())
			}.andExpect { status { isNoContent() } }
		mockMvc.post("/api/v1/trash/NOTE/${note.id()}/restore") { with(csrf()) }.andExpect { status { is2xxSuccessful() } }

		events
			.stream(ChangeEvent::class.java)
			.filter { it.type == ItemType.NOTE.name }
			.map { it.op }
			.toList() shouldContainExactly
			listOf(ChangeOp.CREATED, ChangeOp.UPDATED, ChangeOp.UPDATED, ChangeOp.DELETED, ChangeOp.UPDATED)
	}

	// --- images

	@Test
	fun `an image without a content type gets its mime type from the filename`() {
		val note = newNote("{}")

		val image =
			upload(note.id(), MockMultipartFile("file", "holiday.JPG", null, byteArrayOf(1, 2, 3)))
				.andExpect { status { isCreated() } }
				.json()

		image.get("mime").asString() shouldBe "image/jpeg"
		image.get("name").asString() shouldBe "holiday.JPG"
	}

	@Test
	fun `an octet-stream part with an image filename is stored as that image type`() {
		val note = newNote("{}")

		val result =
			upload(note.id(), MockMultipartFile("file", "scan.png", "application/octet-stream", byteArrayOf(1)))
				.andExpect { status { isCreated() } }
				.andReturn()

		jsonMapper.readTree(result.response.contentAsString).get("mime").asString() shouldBe "image/png"
		result.response.getHeader(HttpHeaders.LOCATION)!!.endsWith(
			"/api/v1/files/${jsonMapper.readTree(result.response.contentAsString).get("fileId").asString()}",
		) shouldBe true
	}

	@Test
	fun `an image without a filename is named image, an upper-case image type is accepted`() {
		val note = newNote("{}")

		val image =
			upload(note.id(), MockMultipartFile("file", "", "IMAGE/PNG", byteArrayOf(1)))
				.andExpect { status { isCreated() } }
				.json()

		image.get("name").asString() shouldBe "image"
	}

	@Test
	fun `a part without filename and content type is not an image`() {
		val note = newNote("{}")

		upload(note.id(), MockMultipartFile("file", "", null, byteArrayOf(1))).andExpect {
			status { isBadRequest() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
		}
		upload(note.id(), MockMultipartFile("file", "archive.zip", null, byteArrayOf(1))).andExpect { status { isBadRequest() } }
		fileCount() shouldBe 0
	}

	@Test
	fun `an upload without the file part is rejected, as is one for a note in the Trash`() {
		val note = newNote("{}")

		mockMvc
			.multipart("$NOTES/${note.id()}/images") {
				file(MockMultipartFile("other", "a.png", "image/png", byteArrayOf(1)))
				with(csrf())
			}.andExpect { status { isBadRequest() } }
		mockMvc
			.delete("$NOTES/${note.id()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, note.etag())
			}.andExpect { status { isNoContent() } }
		upload(note.id(), MockMultipartFile("file", "a.png", "image/png", byteArrayOf(1))).andExpect { status { isNotFound() } }
		fileCount() shouldBe 0
	}

	@Test
	fun `a second image reuses the Attachments-Notes folder`() {
		val note = newNote("{}")

		upload(note.id(), MockMultipartFile("file", "one.png", "image/png", byteArrayOf(1))).andExpect { status { isCreated() } }
		upload(note.id(), MockMultipartFile("file", "two.png", "image/png", byteArrayOf(2))).andExpect { status { isCreated() } }

		jdbc.queryForObject("select count(distinct folder_id) from file", Long::class.java) shouldBe 1
		jdbc.queryForObject("select count(*) from folder where name = 'Notes'", Long::class.java) shouldBe 1
	}

	private companion object {
		const val NOTES = "/api/v1/notes"
	}
}
