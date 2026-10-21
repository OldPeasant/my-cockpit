package ch.sonensei.cockpit.gtd.convert

import ch.sonensei.cockpit.calendar.api.CalendarEventService
import ch.sonensei.cockpit.calendar.api.CalendarService
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.Link
import ch.sonensei.cockpit.core.items.domain.LinkOrigin
import ch.sonensei.cockpit.core.items.infra.LinkRepository
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
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import ch.sonensei.cockpit.gtd.domain.Change as TaskField

@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class TaskToEventWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val tasks: TaskService,
	@Autowired private val notes: NoteService,
	@Autowired private val calendars: CalendarService,
	@Autowired private val events: CalendarEventService,
	@Autowired private val links: LinkRepository,
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
		jdbc.execute("delete from calendar_source")
		jdbc.execute("delete from link")
		jdbc.execute("delete from search_document")
		jdbc.execute("delete from user_settings")
	}

	private fun newTask(
		title: String = "Dentist appointment",
		notesMd: String? = null,
	): UUID =
		tasks
			.create(
				null,
				TaskList.INBOX,
				TaskChanges(title = TaskField(title), notesMd = notesMd?.let { TaskField(it) }),
			).task.id

	private fun newCalendar(name: String = "Personal") = calendars.create(name, null, visible = true, isDefault = false).calendar.id

	private fun convert(
		id: UUID,
		body: String,
		ifMatch: String? = null,
	) = mockMvc.post("/api/v1/tasks/$id/convert-to-event") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun created(
		id: UUID,
		body: String,
	): JsonNode =
		jsonMapper.readTree(
			convert(id, body)
				.andExpect { status { isCreated() } }
				.andReturn()
				.response.contentAsString,
		)

	private fun taskDeletedAt(id: UUID): Any? = jdbc.queryForObject("select deleted_at from task where id = ?", Any::class.java, id)

	private fun eventCount(): Long = jdbc.queryForObject("select count(*) from calendar_event", Long::class.java) ?: 0

	@Test
	fun `creates a timed event from title and notes and moves the task to the Trash`() {
		val calendar = newCalendar()
		val task = newTask(notesMd = "Bring the insurance card.")
		val response =
			convert(task, """{"calendarId":"$calendar","start":"2026-11-03T08:30:00Z","end":"2026-11-03T09:15:00Z"}""")
				.andExpect {
					status { isCreated() }
					header { exists(HttpHeaders.ETAG) }
				}.andReturn()
				.response
		val body = jsonMapper.readTree(response.contentAsString)
		val eventId = UUID.fromString(body.get("id").asString())
		body.get("title").asString() shouldBe "Dentist appointment"
		body.get("description").asString() shouldBe "Bring the insurance card."
		body.get("calendarId").asString() shouldBe calendar.toString()
		body.get("allDay").asBoolean() shouldBe false
		response.getHeader(HttpHeaders.LOCATION)!! shouldEndWith "/api/v1/calendar/events/$eventId"
		val event = events.get(eventId)
		event.startAt shouldBe Instant.parse("2026-11-03T08:30:00Z")
		event.endAt shouldBe Instant.parse("2026-11-03T09:15:00Z")
		event.timeZone shouldBe "Europe/Zurich"
		(taskDeletedAt(task) != null) shouldBe true
	}

	@Test
	fun `creates an all-day event`() {
		val calendar = newCalendar()
		val task = newTask()
		val body = created(task, """{"calendarId":"$calendar","allDay":true,"startDate":"2026-11-03"}""")
		val event = events.get(UUID.fromString(body.get("id").asString()))
		event.allDay shouldBe true
		event.startDate shouldBe LocalDate.parse("2026-11-03")
		event.endDate shouldBe LocalDate.parse("2026-11-04")
		body.get("description").isNull shouldBe true
	}

	@Test
	fun `without a calendar it goes to the default calendar`() {
		val calendar = newCalendar()
		val task = newTask()
		val body = created(task, """{"start":"2026-11-03T08:30:00Z"}""")
		body.get("calendarId").asString() shouldBe calendar.toString()
	}

	@Test
	fun `carries links over in their direction with their label`() {
		val calendar = newCalendar()
		val task = newTask()
		val taskRef = ItemRef(ItemType.TASK, task)
		val outgoing = ItemRef(ItemType.NOTE, notes.create(NoteChanges(title = FieldChange("Insurance"))).id)
		val incoming = ItemRef(ItemType.NOTE, notes.create(NoteChanges(title = FieldChange("Health log"))).id)
		val gone = notes.create(NoteChanges(title = FieldChange("Old note"))).id
		notes.delete(gone) {}
		links.save(Link(taskRef, outgoing, "about", LinkOrigin.MANUAL))
		links.save(Link(incoming, taskRef, null, LinkOrigin.SYNC))
		links.save(Link(taskRef, ItemRef(ItemType.NOTE, gone), null, LinkOrigin.MANUAL))

		val body = created(task, """{"calendarId":"$calendar","start":"2026-11-03T08:30:00Z"}""")
		val eventRef = ItemRef(ItemType.EVENT, UUID.fromString(body.get("id").asString()))

		links.findTouching(eventRef.type, eventRef.id).map { Triple(it.source, it.target, it.label to it.origin) } shouldContainExactlyInAnyOrder
			listOf(
				Triple(eventRef, outgoing, "about" to LinkOrigin.MANUAL),
				Triple(incoming, eventRef, null to LinkOrigin.MANUAL),
			)
		// The task keeps its own links in the Trash, so a restore brings them back.
		links.findTouching(ItemType.TASK, task).size shouldBe 3
	}

	@Test
	fun `invalid times change nothing`() {
		val calendar = newCalendar()
		val task = newTask()
		convert(task, """{"calendarId":"$calendar"}""").andExpect { status { isBadRequest() } }
		convert(task, """{"calendarId":"$calendar","start":"2026-11-03T08:30:00Z","end":"2026-11-03T08:00:00Z"}""")
			.andExpect { status { isBadRequest() } }
		convert(task, """{"calendarId":"$calendar","allDay":true,"start":"2026-11-03T08:30:00Z"}""")
			.andExpect { status { isBadRequest() } }
		taskDeletedAt(task) shouldBe null
		eventCount() shouldBe 0
	}

	@Test
	fun `an unknown calendar changes nothing`() {
		newCalendar()
		val task = newTask()
		convert(task, """{"calendarId":"${UUID.randomUUID()}","start":"2026-11-03T08:30:00Z"}""").andExpect { status { isBadRequest() } }
		taskDeletedAt(task) shouldBe null
		eventCount() shouldBe 0
	}

	@Test
	fun `a stale If-Match changes nothing, a matching one converts`() {
		val calendar = newCalendar()
		val task = newTask()
		val body = """{"calendarId":"$calendar","start":"2026-11-03T08:30:00Z"}"""
		convert(task, body, ifMatch = "\"99\"").andExpect { status { isPreconditionFailed() } }
		taskDeletedAt(task) shouldBe null
		eventCount() shouldBe 0
		convert(task, body, ifMatch = "\"${tasks.get(task).version}\"").andExpect { status { isCreated() } }
	}

	@Test
	fun `unknown and trashed tasks are not found`() {
		val calendar = newCalendar()
		val body = """{"calendarId":"$calendar","start":"2026-11-03T08:30:00Z"}"""
		convert(UUID.randomUUID(), body).andExpect { status { isNotFound() } }
		val task = newTask()
		tasks.delete(task) {}
		convert(task, body).andExpect { status { isNotFound() } }
		eventCount() shouldBe 0
	}

	@Test
	@WithAnonymousUser
	fun `requires authentication`() {
		convert(UUID.randomUUID(), "{}").andExpect { status { isUnauthorized() } }
	}
}
