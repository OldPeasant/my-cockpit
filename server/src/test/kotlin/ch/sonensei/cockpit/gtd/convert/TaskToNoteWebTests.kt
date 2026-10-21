package ch.sonensei.cockpit.gtd.convert

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.Link
import ch.sonensei.cockpit.core.items.domain.LinkOrigin
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
import io.kotest.matchers.string.shouldEndWith
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import ch.sonensei.cockpit.gtd.domain.Change as TaskField

@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@WithCockpitUser
class TaskToNoteWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val tasks: TaskService,
	@Autowired private val notes: NoteService,
	@Autowired private val links: LinkRepository,
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
		title: String = "Read the tax guide",
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

	private fun taskDeletedAt(id: UUID): Any? = jdbc.queryForObject("select deleted_at from task where id = ?", Any::class.java, id)

	private fun noteCount(): Long = jdbc.queryForObject("select count(*) from note", Long::class.java) ?: 0

	@Test
	fun `creates a note from title and notes and moves the task to the Trash`() {
		val task = newTask(notesMd = "Chapter 3 covers *deductions*.")
		val response =
			convert(task)
				.andExpect {
					status { isCreated() }
					header { exists(HttpHeaders.ETAG) }
				}.andReturn()
				.response
		val body = jsonMapper.readTree(response.contentAsString)
		val noteId = UUID.fromString(body.get("id").asString())
		body.get("title").asString() shouldBe "Read the tax guide"
		body.get("bodyMd").asString() shouldBe "Chapter 3 covers *deductions*."
		response.getHeader(HttpHeaders.LOCATION)!! shouldEndWith "/api/v1/notes/$noteId"
		notes.get(noteId).title shouldBe "Read the tax guide"
		(taskDeletedAt(task) != null) shouldBe true
	}

	@Test
	fun `a task without notes gives an empty body`() {
		val task = newTask()
		val body =
			jsonMapper.readTree(
				convert(task)
					.andExpect { status { isCreated() } }
					.andReturn()
					.response.contentAsString,
			)
		body.get("bodyMd").asString() shouldBe ""
	}

	@Test
	fun `carries links over in their direction with their label`() {
		val task = newTask()
		val taskRef = ItemRef(ItemType.TASK, task)
		val outgoing = ItemRef(ItemType.NOTE, newNote("Tax folder"))
		val incoming = ItemRef(ItemType.NOTE, newNote("Inline linker"))
		val unknownType = ItemRef(ItemType.HABIT, UUID.randomUUID())
		links.save(Link(taskRef, outgoing, "source", LinkOrigin.MANUAL))
		links.save(Link(incoming, taskRef, null, LinkOrigin.SYNC))
		links.save(Link(taskRef, unknownType, null, LinkOrigin.MANUAL))

		val body =
			jsonMapper.readTree(
				convert(task)
					.andExpect { status { isCreated() } }
					.andReturn()
					.response.contentAsString,
			)
		val noteRef = ItemRef(ItemType.NOTE, UUID.fromString(body.get("id").asString()))

		val noteLinks = links.findTouching(noteRef.type, noteRef.id).map { Triple(it.source, it.target, it.label to it.origin) }
		noteLinks shouldContainExactlyInAnyOrder
			listOf(
				Triple(noteRef, outgoing, "source" to LinkOrigin.MANUAL),
				Triple(incoming, noteRef, null to LinkOrigin.MANUAL),
				Triple(noteRef, unknownType, null to LinkOrigin.MANUAL),
			)
		// The task keeps its own links in the Trash, so a restore brings them back.
		links.findTouching(ItemType.TASK, task).size shouldBe 3
	}

	@Test
	fun `skips links to items that no longer exist`() {
		val task = newTask()
		val gone = newNote("Old note")
		notes.delete(gone) {}
		links.save(Link(ItemRef(ItemType.TASK, task), ItemRef(ItemType.NOTE, gone), null, LinkOrigin.MANUAL))

		val body =
			jsonMapper.readTree(
				convert(task)
					.andExpect { status { isCreated() } }
					.andReturn()
					.response.contentAsString,
			)

		links.findTouching(ItemType.NOTE, UUID.fromString(body.get("id").asString())) shouldBe emptyList()
	}

	@Test
	fun `a stale If-Match changes nothing`() {
		val task = newTask()
		convert(task, ifMatch = "\"99\"").andExpect { status { isPreconditionFailed() } }
		taskDeletedAt(task) shouldBe null
		noteCount() shouldBe 0
	}

	@Test
	fun `a matching If-Match converts`() {
		val task = newTask()
		convert(task, ifMatch = "\"${tasks.get(task).version}\"").andExpect { status { isCreated() } }
	}

	@Test
	fun `unknown and trashed tasks are not found`() {
		convert(UUID.randomUUID()).andExpect { status { isNotFound() } }
		val task = newTask()
		tasks.delete(task) {}
		convert(task).andExpect { status { isNotFound() } }
		noteCount() shouldBe 0
	}

	@Test
	@WithAnonymousUser
	fun `requires authentication`() {
		convert(UUID.randomUUID()).andExpect { status { isUnauthorized() } }
	}
}
