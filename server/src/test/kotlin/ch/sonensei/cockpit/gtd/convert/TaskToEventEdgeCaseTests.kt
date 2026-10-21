package ch.sonensei.cockpit.gtd.convert

import ch.sonensei.cockpit.calendar.api.CalendarEventService
import ch.sonensei.cockpit.calendar.api.CalendarService
import ch.sonensei.cockpit.calendar.model.Calendar
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
import ch.sonensei.cockpit.calendar.sync.CalendarSyncService
import ch.sonensei.cockpit.calendar.sync.FakeCalendarAdapter
import ch.sonensei.cockpit.calendar.sync.FakeCalendarAdapterConfiguration
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.Link
import ch.sonensei.cockpit.core.items.domain.LinkOrigin
import ch.sonensei.cockpit.core.items.infra.LinkRepository
import ch.sonensei.cockpit.gtd.domain.TaskChanges
import ch.sonensei.cockpit.gtd.domain.TaskList
import ch.sonensei.cockpit.gtd.domain.TaskService
import ch.sonensei.cockpit.journal.habits.domain.HabitChanges
import ch.sonensei.cockpit.journal.habits.domain.HabitSchedule
import ch.sonensei.cockpit.journal.habits.domain.HabitService
import ch.sonensei.cockpit.notes.domain.FieldChange
import ch.sonensei.cockpit.notes.domain.NoteChanges
import ch.sonensei.cockpit.notes.domain.NoteService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import ch.sonensei.cockpit.gtd.domain.Change as TaskField
import ch.sonensei.cockpit.journal.habits.domain.Change as HabitField

/**
 * Edge cases of "To calendar": read-only and synced calendars (write-through, provider failure), events, search,
 * If-Match variants, defaults of the time fields, links of other types, restoring the task, malformed requests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeCalendarAdapterConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class TaskToEventEdgeCaseTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val tasks: TaskService,
	@Autowired private val notes: NoteService,
	@Autowired private val calendarService: CalendarService,
	@Autowired private val events: CalendarEventService,
	@Autowired private val calendars: CalendarRepository,
	@Autowired private val sources: CalendarSourceRepository,
	@Autowired private val sync: CalendarSyncService,
	@Autowired private val fake: FakeCalendarAdapter,
	@Autowired private val links: LinkRepository,
	@Autowired private val habitService: HabitService,
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
		jdbc.execute("delete from habit_check")
		jdbc.execute("delete from habit")
		jdbc.execute("delete from search_document")
		jdbc.execute("delete from user_settings")
		fake.reset()
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

	private fun newCalendar(name: String = "Personal") = calendarService.create(name, null, visible = true, isDefault = false).calendar.id

	/** A synced (CalDAV, [FakeCalendarAdapter]) calendar "work", and a read-only synced one "shared". */
	private fun syncedCalendars(): Pair<UUID, UUID> {
		fake.addCalendar("work", "Work")
		fake.addCalendar("shared", "Shared", readOnly = true)
		val source = sources.saveAndFlush(CalendarSource(CalendarSourceType.CALDAV, "Nextcloud"))
		sync.sync(source.id)
		return calendars.findBySourceIdAndExternalId(source.id, "work")!!.id to
			calendars.findBySourceIdAndExternalId(source.id, "shared")!!.id
	}

	private fun at(
		calendar: UUID,
		start: String = "2026-11-03T08:30:00Z",
	) = """{"calendarId":"$calendar","start":"$start"}"""

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

	private fun ResultActionsDsl.createdEventId(): UUID {
		val body =
			andExpect { status { isCreated() } }
				.andReturn()
				.response.contentAsString
		return UUID.fromString(jsonMapper.readTree(body).get("id").asString())
	}

	private fun taskDeletedAt(id: UUID): Any? = jdbc.queryForObject("select deleted_at from task where id = ?", Any::class.java, id)

	private fun eventCount(): Long = jdbc.queryForObject("select count(*) from calendar_event", Long::class.java) ?: 0

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

	private fun assertUnchanged(task: UUID) {
		taskDeletedAt(task) shouldBe null
		eventCount() shouldBe 0
	}

	// --- calendars

	@Test
	fun `a read-only local calendar is a conflict and changes nothing`() {
		newCalendar()
		val localSource = sources.findAll().single().id
		val readOnly = calendars.saveAndFlush(Calendar(localSource, "Holidays CH").apply { readOnly = true })
		val task = newTask()

		convert(task, at(readOnly.id)).andExpect { status { isConflict() } }

		assertUnchanged(task)
	}

	@Test
	fun `without a calendar and without a default calendar it is a bad request and changes nothing`() {
		val task = newTask()

		convert(task, """{"start":"2026-11-03T08:30:00Z"}""").andExpect {
			status { isBadRequest() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
		}

		assertUnchanged(task)
	}

	@Test
	fun `a synced calendar gets the event through its provider, the task goes to the Trash`() {
		val (work, _) = syncedCalendars()
		val task = newTask(notesMd = "Room 3.")

		val event = convert(task, at(work)).createdEventId()

		fake.writes shouldContainExactly listOf("create $event")
		val remote = fake.event("work", RemoteEventKey(event.toString())).shouldNotBeNull()
		remote.title shouldBe "Dentist appointment"
		remote.description shouldBe "Room 3."
		events.get(event).externalEtag shouldBe remote.etag
		(taskDeletedAt(task) != null) shouldBe true
	}

	@Test
	fun `a failing provider is a bad gateway and rolls back the event, the task deletion and the copied links`() {
		val (work, _) = syncedCalendars()
		val task = newTask()
		val other = notes.create(NoteChanges(title = FieldChange("Insurance"))).id
		links.save(Link(ItemRef(ItemType.TASK, task), ItemRef(ItemType.NOTE, other), "about", LinkOrigin.MANUAL))
		fake.failWrites = CalendarProviderException("Server answered 500")

		convert(task, at(work)).andExpect { status { isBadGateway() } }

		assertUnchanged(task)
		fake.events("work").keys.shouldBeEmpty()
		links.findTouching(ItemType.NOTE, other).map { it.source } shouldContainExactly listOf(ItemRef(ItemType.TASK, task))
		searchDocument(ItemType.TASK, task) shouldBe ("Dentist appointment" to "")
	}

	@Test
	fun `a read-only synced calendar is a conflict and nothing reaches the provider`() {
		val (_, shared) = syncedCalendars()
		val task = newTask()

		convert(task, at(shared)).andExpect { status { isConflict() } }

		assertUnchanged(task)
		fake.writes.shouldBeEmpty()
	}

	@Test
	fun `a calendar of an external source without sync support is a conflict`() {
		newCalendar()
		val google = sources.saveAndFlush(CalendarSource(CalendarSourceType.GOOGLE, "Work account"))
		val external = calendars.saveAndFlush(Calendar(google.id, "Work"))
		val task = newTask()

		convert(task, at(external.id)).andExpect { status { isConflict() } }

		assertUnchanged(task)
	}

	// --- events and search

	@Test
	fun `announces the new event as CREATED and the task as DELETED`(appEvents: ApplicationEvents) {
		val calendar = newCalendar()
		val task = newTask()
		appEvents.clear()

		val event = convert(task, at(calendar)).createdEventId()

		appEvents
			.stream(ChangeEvent::class.java)
			.toList()
			.map { Triple(it.type, it.id, it.op) } shouldContainExactlyInAnyOrder
			listOf(
				Triple("TASK", task, ChangeOp.DELETED),
				Triple("EVENT", event, ChangeOp.CREATED),
			)
	}

	@Test
	fun `the task leaves the search index and the event enters it with the notes as body`() {
		val calendar = newCalendar()
		val task = newTask("Annual check-up", notesMd = "Fasting from 8 pm.")
		searchDocument(ItemType.TASK, task) shouldBe ("Annual check-up" to "Fasting from 8 pm.")

		val event = convert(task, at(calendar)).createdEventId()

		searchDocument(ItemType.TASK, task) shouldBe null
		searchDocument(ItemType.EVENT, event) shouldBe ("Annual check-up" to "Fasting from 8 pm.")
	}

	@Test
	fun `a conversion that fails after the task was trashed leaves the search index untouched`() {
		// The task is trashed before the event is validated; the rollback must bring its search document back.
		// (Change events published before the failure never reach clients: the SSE hub forwards only after commit.)
		val calendar = newCalendar()
		val task = newTask()

		convert(task, """{"calendarId":"$calendar","start":"2026-11-03T08:30:00Z","end":"2026-11-03T08:00:00Z"}""")
			.andExpect { status { isBadRequest() } }

		searchDocument(ItemType.TASK, task) shouldBe ("Dentist appointment" to "")
		assertUnchanged(task)
	}

	@Test
	fun `the ETag and the body describe the new event as GET returns it`() {
		val calendar = newCalendar()
		val response =
			convert(newTask(), at(calendar))
				.andExpect { status { isCreated() } }
				.andReturn()
				.response
		val created = jsonMapper.readTree(response.contentAsString)

		val fetched =
			mockMvc
				.get("/api/v1/calendar/events/${created.get("id").asString()}")
				.andExpect { status { isOk() } }
				.andReturn()
				.response
		response.getHeader(HttpHeaders.ETAG) shouldBe fetched.getHeader(HttpHeaders.ETAG)
		response.getHeader(HttpHeaders.ETAG) shouldBe "\"${created.get("version").asLong()}\""
		jsonMapper.readTree(fetched.contentAsString).get("title").asString() shouldBe "Dentist appointment"
	}

	// --- content and times

	@Test
	fun `whitespace-only notes give no description`() {
		val calendar = newCalendar()
		val event = convert(newTask(notesMd = "   \n  "), at(calendar)).createdEventId()
		events.get(event).description shouldBe null
	}

	@Test
	fun `a timed event without end lasts one hour, a given time zone is kept`() {
		val calendar = newCalendar()
		val event =
			convert(newTask(), """{"calendarId":"$calendar","start":"2026-11-03T08:30:00Z","timeZone":"America/New_York"}""")
				.createdEventId()
		val stored = events.get(event)
		stored.startAt shouldBe Instant.parse("2026-11-03T08:30:00Z")
		stored.endAt shouldBe Instant.parse("2026-11-03T09:30:00Z")
		stored.timeZone shouldBe "America/New_York"
	}

	@Test
	fun `an unknown time zone or an all-day end before its start changes nothing`() {
		val calendar = newCalendar()
		val task = newTask()
		convert(task, """{"calendarId":"$calendar","start":"2026-11-03T08:30:00Z","timeZone":"Mars/Olympus"}""")
			.andExpect { status { isBadRequest() } }
		convert(task, """{"calendarId":"$calendar","allDay":true,"startDate":"2026-11-03","endDate":"2026-11-02"}""")
			.andExpect { status { isBadRequest() } }
		assertUnchanged(task)
	}

	@Test
	fun `a completed task converts like an open one`() {
		val calendar = newCalendar()
		val task = newTask("Renew the passport")
		tasks.complete(task) {}

		val event = convert(task, at(calendar)).createdEventId()

		events.get(event).title shouldBe "Renew the passport"
		(taskDeletedAt(task) != null) shouldBe true
	}

	// --- If-Match

	@Test
	fun `If-Match star, a blank header and a list containing the current tag convert`() {
		val calendar = newCalendar()
		convert(newTask(), at(calendar), ifMatch = "*").andExpect { status { isCreated() } }
		convert(newTask(), at(calendar), ifMatch = "").andExpect { status { isCreated() } }
		val task = newTask()
		convert(task, at(calendar), ifMatch = "\"99\", \"${tasks.get(task).version}\"").andExpect { status { isCreated() } }
		eventCount() shouldBe 3
	}

	@Test
	fun `a weak If-Match tag never matches`() {
		val calendar = newCalendar()
		val task = newTask()
		convert(task, at(calendar), ifMatch = "W/\"${tasks.get(task).version}\"").andExpect { status { isPreconditionFailed() } }
		assertUnchanged(task)
	}

	@Test
	fun `converting twice gives one event, the second call is not found`() {
		val calendar = newCalendar()
		val task = newTask()
		convert(task, at(calendar)).andExpect { status { isCreated() } }
		convert(task, at(calendar)).andExpect { status { isNotFound() } }
		eventCount() shouldBe 1
	}

	// --- links

	@Test
	fun `links to other tasks are carried over, links to trashed tasks are skipped, links to other modules are kept`() {
		val calendar = newCalendar()
		val task = newTask()
		val taskRef = ItemRef(ItemType.TASK, task)
		val blocker = newTask("Find the insurance card")
		val trashed = newTask("Old reminder")
		tasks.delete(trashed) {}
		val habit = ItemRef(ItemType.HABIT, newHabit())
		links.save(Link(ItemRef(ItemType.TASK, blocker), taskRef, "blocks", LinkOrigin.MANUAL))
		links.save(Link(taskRef, ItemRef(ItemType.TASK, trashed), null, LinkOrigin.MANUAL))
		links.save(Link(taskRef, habit, null, LinkOrigin.MANUAL))

		val eventRef = ItemRef(ItemType.EVENT, convert(task, at(calendar)).createdEventId())

		links.findTouching(ItemType.EVENT, eventRef.id).map { Triple(it.source, it.target, it.label to it.origin) } shouldContainExactlyInAnyOrder
			listOf(
				Triple(ItemRef(ItemType.TASK, blocker), eventRef, "blocks" to LinkOrigin.MANUAL),
				Triple(eventRef, habit, null to LinkOrigin.MANUAL),
			)
	}

	@Test
	fun `restoring the converted task from the Trash brings it back with its links and keeps the event`() {
		val calendar = newCalendar()
		val task = newTask("Keep the appointment card")
		val other = ItemRef(ItemType.NOTE, notes.create(NoteChanges(title = FieldChange("Health"))).id)
		links.save(Link(ItemRef(ItemType.TASK, task), other, null, LinkOrigin.MANUAL))
		val event = convert(task, at(calendar)).createdEventId()

		mockMvc.post("/api/v1/trash/TASK/$task/restore") { with(csrf()) }.andExpect { status { isNoContent() } }

		tasks.get(task).title shouldBe "Keep the appointment card"
		links.findTouching(ItemType.TASK, task).size shouldBe 1
		events.get(event).title shouldBe "Keep the appointment card"
		links.findTouching(ItemType.EVENT, event).size shouldBe 1
	}

	// --- malformed requests

	@Test
	fun `a malformed task id is a bad request`() {
		mockMvc
			.post("/api/v1/tasks/not-a-uuid/convert-to-event") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = "{}"
			}.andExpect { status { isBadRequest() } }
	}

	@Test
	fun `a missing or malformed body changes nothing`() {
		val calendar = newCalendar()
		val task = newTask()
		mockMvc
			.post("/api/v1/tasks/$task/convert-to-event") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
			}.andExpect { status { isBadRequest() } }
		convert(task, """{"calendarId":"$calendar","start":"tomorrow"}""").andExpect { status { isBadRequest() } }
		convert(task, """{"calendarId":"not-a-uuid","start":"2026-11-03T08:30:00Z"}""").andExpect { status { isBadRequest() } }
		assertUnchanged(task)
	}

	@Test
	fun `without a CSRF token nothing is converted`() {
		val calendar = newCalendar()
		val task = newTask()
		mockMvc
			.post("/api/v1/tasks/$task/convert-to-event") {
				contentType = MediaType.APPLICATION_JSON
				content = at(calendar)
			}.andExpect { status { isForbidden() } }
		assertUnchanged(task)
	}

	/** A habit that exists: HABIT has a resolver, so a link to a missing habit would be dropped. */
	private fun newHabit(): UUID =
		habitService.create(HabitChanges(name = HabitField("Stretch"), schedule = HabitField(HabitSchedule.DAILY))).habit.id
}
