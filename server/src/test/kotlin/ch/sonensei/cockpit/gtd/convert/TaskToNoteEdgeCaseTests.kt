package ch.sonensei.cockpit.gtd.convert

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.Link
import ch.sonensei.cockpit.core.items.domain.LinkOrigin
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.items.infra.LinkRepository
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.gtd.domain.TaskChanges
import ch.sonensei.cockpit.gtd.domain.TaskList
import ch.sonensei.cockpit.gtd.domain.TaskService
import ch.sonensei.cockpit.notes.domain.FieldChange
import ch.sonensei.cockpit.notes.domain.NoteChanges
import ch.sonensei.cockpit.notes.domain.NoteService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import ch.sonensei.cockpit.gtd.domain.Change as TaskField

/** Edge cases of "Move to reference": events, search, If-Match variants, inline links, linked tasks, completed tasks. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class TaskToNoteEdgeCaseTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val tasks: TaskService,
	@Autowired private val notes: NoteService,
	@Autowired private val links: LinkRepository,
	@Autowired private val linkService: LinkService,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("update project set next_task_id = null")
		jdbc.execute("delete from checklist_item")
		jdbc.execute("delete from task_context")
		jdbc.execute("delete from task")
		jdbc.execute("delete from note")
		jdbc.execute("delete from link")
		jdbc.execute("delete from search_document")
		clock.set(MutableClockTestConfiguration.START)
	}

	private fun newTask(
		title: String = "Archive the warranty card",
		notesMd: String? = null,
	): UUID =
		tasks
			.create(
				null,
				TaskList.INBOX,
				TaskChanges(title = TaskField(title), notesMd = notesMd?.let { TaskField(it) }),
			).task.id

	private fun newNote(title: String) = notes.create(NoteChanges(title = FieldChange(title))).id

	private fun convert(
		id: UUID,
		ifMatch: String? = null,
	) = mockMvc.post("/api/v1/tasks/$id/convert-to-note") {
		with(csrf())
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun ResultActionsDsl.createdNoteId(): UUID {
		val body =
			andExpect { status { isCreated() } }
				.andReturn()
				.response.contentAsString
		return UUID.fromString(jsonMapper.readTree(body).get("id").asString())
	}

	private fun taskDeletedAt(id: UUID): Any? = jdbc.queryForObject("select deleted_at from task where id = ?", Any::class.java, id)

	private fun noteCount(): Long = jdbc.queryForObject("select count(*) from note", Long::class.java) ?: 0

	private fun searchDocument(
		type: ItemType,
		id: UUID,
	): Pair<String?, String?>? =
		jdbc
			.query(
				"select title, body from search_document where item_type = ? and item_id = ?",
				{ rs, _ -> rs.getString(1) to rs.getString(2) },
				type.name,
				id,
			).singleOrNull()

	private fun linksOfNote(noteId: UUID): List<Triple<ItemRef, ItemRef, Pair<String?, LinkOrigin>>> =
		links.findTouching(ItemType.NOTE, noteId).map { Triple(it.source, it.target, it.label to it.origin) }

	@Test
	fun `announces the new note as CREATED and the task as DELETED`(events: ApplicationEvents) {
		val task = newTask()
		events.clear()

		val note = convert(task).createdNoteId()

		events
			.stream(ChangeEvent::class.java)
			.toList()
			.map { Triple(it.type, it.id, it.op) } shouldContainExactlyInAnyOrder
			listOf(
				Triple("TASK", task, ChangeOp.DELETED),
				Triple("NOTE", note, ChangeOp.CREATED),
			)
	}

	@Test
	fun `the task leaves the search index and the note enters it with its body as plain text`() {
		val task = newTask("Warranty of the fridge", notesMd = "Valid until **2028**.")
		searchDocument(ItemType.TASK, task) shouldBe ("Warranty of the fridge" to "Valid until **2028**.")

		val note = convert(task).createdNoteId()

		searchDocument(ItemType.TASK, task) shouldBe null
		searchDocument(ItemType.NOTE, note) shouldBe ("Warranty of the fridge" to "Valid until 2028.")
	}

	@Test
	fun `the ETag and the body describe the new note as GET returns it`() {
		val task = newTask(notesMd = "Kept in the blue folder.")
		val response =
			convert(task)
				.andExpect { status { isCreated() } }
				.andReturn()
				.response
		val created = jsonMapper.readTree(response.contentAsString)
		val noteId = created.get("id").asString()

		val fetched =
			mockMvc
				.get("/api/v1/notes/$noteId")
				.andExpect { status { isOk() } }
				.andReturn()
				.response
		response.getHeader(HttpHeaders.ETAG) shouldBe fetched.getHeader(HttpHeaders.ETAG)
		response.getHeader(HttpHeaders.ETAG) shouldBe "\"${created.get("version").asLong()}\""
		jsonMapper.readTree(fetched.contentAsString).get("bodyMd").asString() shouldBe "Kept in the blue folder."
	}

	@Test
	fun `If-Match star, a blank header and a list containing the current tag convert`() {
		convert(newTask(), ifMatch = "*").andExpect { status { isCreated() } }
		convert(newTask(), ifMatch = "").andExpect { status { isCreated() } }
		val task = newTask()
		convert(task, ifMatch = "\"99\", \"${tasks.get(task).version}\"").andExpect { status { isCreated() } }
		noteCount() shouldBe 3
	}

	@Test
	fun `a weak If-Match tag never matches`() {
		val task = newTask()
		convert(task, ifMatch = "W/\"${tasks.get(task).version}\"").andExpect { status { isPreconditionFailed() } }
		taskDeletedAt(task) shouldBe null
		noteCount() shouldBe 0
	}

	@Test
	fun `a stale If-Match leaves links, events and the search index untouched`(events: ApplicationEvents) {
		val task = newTask()
		val other = newNote("Manuals")
		links.save(Link(ItemRef(ItemType.TASK, task), ItemRef(ItemType.NOTE, other), null, LinkOrigin.MANUAL))
		events.clear()

		convert(task, ifMatch = "\"99\"").andExpect { status { isPreconditionFailed() } }

		events.stream(ChangeEvent::class.java).count() shouldBe 0
		searchDocument(ItemType.TASK, task) shouldBe ("Archive the warranty card" to "")
		links.findTouching(ItemType.NOTE, other).size shouldBe 1
		noteCount() shouldBe 1
	}

	@Test
	fun `converting twice gives one note, the second call is not found`() {
		val task = newTask()
		convert(task).andExpect { status { isCreated() } }
		convert(task).andExpect { status { isNotFound() } }
		noteCount() shouldBe 1
	}

	@Test
	fun `a completed task converts like an open one`() {
		val task = newTask("Renew the passport", notesMd = "Done at the town hall.")
		tasks.complete(task) {}

		val note = convert(task).createdNoteId()

		notes.get(note).title shouldBe "Renew the passport"
		notes.get(note).bodyMd shouldBe "Done at the town hall."
		(taskDeletedAt(task) != null) shouldBe true
	}

	@Test
	fun `inline links in the task notes become synced links of the note`() {
		val manual = newNote("Insurance policy")
		val task = newTask(notesMd = "See [[note:$manual]] for the details.")
		val noteRef = ItemRef(ItemType.NOTE, convert(task).createdNoteId())

		linksOfNote(noteRef.id) shouldContainExactlyInAnyOrder
			listOf(Triple(noteRef, ItemRef(ItemType.NOTE, manual), null to LinkOrigin.SYNC))
	}

	@Test
	fun `an inline link and a manual link to the same item both survive without a conflict`() {
		val manual = newNote("Insurance policy")
		val task = newTask(notesMd = "See [[note:$manual]].")
		val manualRef = ItemRef(ItemType.NOTE, manual)
		links.save(Link(ItemRef(ItemType.TASK, task), manualRef, "policy", LinkOrigin.MANUAL))

		val noteRef = ItemRef(ItemType.NOTE, convert(task).createdNoteId())

		linksOfNote(noteRef.id) shouldContainExactlyInAnyOrder
			listOf(
				Triple(noteRef, manualRef, null to LinkOrigin.SYNC),
				Triple(noteRef, manualRef, "policy" to LinkOrigin.MANUAL),
			)
	}

	@Test
	fun `an item linked to the task in both directions is linked to the note once, by its manual link`() {
		val task = newTask()
		val taskRef = ItemRef(ItemType.TASK, task)
		val other = ItemRef(ItemType.NOTE, newNote("Receipts"))
		links.save(Link(other, taskRef, null, LinkOrigin.SYNC))
		links.save(Link(taskRef, other, "receipt", LinkOrigin.MANUAL))

		val noteRef = ItemRef(ItemType.NOTE, convert(task).createdNoteId())

		linksOfNote(noteRef.id) shouldContainExactlyInAnyOrder
			listOf(Triple(noteRef, other, "receipt" to LinkOrigin.MANUAL))
	}

	@Test
	fun `an item with an inline backlink, a manual link and an inline link from the task notes converts without a conflict`() {
		// CR-1, through the real services: X's body links the task (SYNC X -> task), the task links X manually
		// (MANUAL task -> X, newer than the sync link) and the task notes link X inline.
		val other = ItemRef(ItemType.NOTE, newNote("Receipts"))
		val task = newTask(notesMd = "See [[note:${other.id}]].")
		val taskRef = ItemRef(ItemType.TASK, task)
		notes.update(other.id, NoteChanges(bodyMd = FieldChange("Belongs to [[task:$task]].")), {})
		linkService.create(taskRef, other, "receipt")
		links.findTouching(ItemType.TASK, task).map { it.origin } shouldContainExactlyInAnyOrder
			listOf(LinkOrigin.SYNC, LinkOrigin.MANUAL)

		val noteRef = ItemRef(ItemType.NOTE, convert(task).createdNoteId())

		linksOfNote(noteRef.id) shouldContainExactlyInAnyOrder
			listOf(
				Triple(noteRef, other, null to LinkOrigin.SYNC),
				Triple(noteRef, other, "receipt" to LinkOrigin.MANUAL),
			)
		// X's own inline link still points at the trashed task; only its owner's edit removes it.
		links.findTouching(ItemType.TASK, task).map { Triple(it.source, it.target, it.origin) } shouldContainExactlyInAnyOrder
			listOf(Triple(other, taskRef, LinkOrigin.SYNC), Triple(taskRef, other, LinkOrigin.MANUAL))
	}

	@Test
	fun `links to other tasks are carried over, links to trashed tasks are skipped`() {
		val task = newTask()
		val taskRef = ItemRef(ItemType.TASK, task)
		val blocker = newTask("Find the invoice")
		val trashed = newTask("Old reminder")
		tasks.delete(trashed) {}
		links.save(Link(ItemRef(ItemType.TASK, blocker), taskRef, "blocks", LinkOrigin.MANUAL))
		links.save(Link(taskRef, ItemRef(ItemType.TASK, trashed), null, LinkOrigin.MANUAL))

		val noteRef = ItemRef(ItemType.NOTE, convert(task).createdNoteId())

		linksOfNote(noteRef.id) shouldContainExactlyInAnyOrder
			listOf(Triple(ItemRef(ItemType.TASK, blocker), noteRef, "blocks" to LinkOrigin.MANUAL))
	}

	@Test
	fun `restoring the converted task from the Trash brings it back with its links and keeps the note`() {
		val task = newTask("Keep the lease contract")
		val other = ItemRef(ItemType.NOTE, newNote("Flat"))
		links.save(Link(ItemRef(ItemType.TASK, task), other, null, LinkOrigin.MANUAL))
		val note = convert(task).createdNoteId()

		mockMvc.post("/api/v1/trash/TASK/$task/restore") { with(csrf()) }.andExpect { status { isNoContent() } }

		tasks.get(task).title shouldBe "Keep the lease contract"
		links.findTouching(ItemType.TASK, task).size shouldBe 1
		notes.get(note).title shouldBe "Keep the lease contract"
		links.findTouching(ItemType.NOTE, note).size shouldBe 1
	}

	@Test
	fun `a malformed task id is a bad request`() {
		mockMvc
			.post("/api/v1/tasks/not-a-uuid/convert-to-note") { with(csrf()) }
			.andExpect { status { isBadRequest() } }
	}

	@Test
	fun `without a CSRF token nothing is converted`() {
		val task = newTask()
		mockMvc.post("/api/v1/tasks/$task/convert-to-note").andExpect { status { isForbidden() } }
		taskDeletedAt(task) shouldBe null
		noteCount() shouldBe 0
	}

	@Test
	@WithAnonymousUser
	fun `an anonymous request converts nothing`() {
		val task = newTask()
		convert(task).andExpect { status { isUnauthorized() } }
		taskDeletedAt(task) shouldBe null
		noteCount() shouldBe 0
	}
}
