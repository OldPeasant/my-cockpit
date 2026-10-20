package ch.sonensei.cockpit.calendar.sync

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.etag
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.id
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.isProblem
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.EventSyncState
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.sync.FakeCalendarAdapter.Companion.remote
import ch.sonensei.cockpit.calendar.sync.FakeCalendarAdapter.Companion.timed
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The sync engine against [FakeCalendarAdapter]: pulling (incremental merge, deletions), write-through and conflicts. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class, FakeCalendarAdapterConfiguration::class)
@WithCockpitUser
class CalendarSyncTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val sources: CalendarSourceRepository,
	@Autowired private val calendars: CalendarRepository,
	@Autowired private val events: CalendarEventRepository,
	@Autowired private val sync: CalendarSyncService,
	@Autowired private val fake: FakeCalendarAdapter,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val tx: TransactionTemplate,
) : PostgresTestBase() {
	private val api = CalendarApi(mockMvc, jsonMapper)
	private lateinit var source: CalendarSource

	@BeforeEach
	fun reset() {
		CalendarApi.resetCalendarTables(jdbc)
		clock.set(MutableClockTestConfiguration.START)
		fake.reset()
		fake.addCalendar("work", "Work", color = "#112233")
		source = sources.saveAndFlush(CalendarSource(CalendarSourceType.CALDAV, "Nextcloud"))
	}

	private fun calendarId(externalId: String = "work"): UUID = calendars.findBySourceIdAndExternalId(source.id, externalId)!!.id

	private fun local(
		uid: String,
		calendar: String = "work",
	): CalendarEvent? = events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(calendarId(calendar), uid)

	private fun localTitles(calendar: String = "work"): List<String> =
		events.findAllById(events.findIdsByCalendarId(calendarId(calendar))).filter { !it.isOverride }.map { it.title }

	private fun overridesOf(event: CalendarEvent) = events.findByParentEventIdOrderByIdAsc(event.id)

	private fun exdatesOf(id: UUID): Set<OccurrenceId> =
		tx.execute {
			events
				.findById(id)
				.get()
				.exdates
				.toSet()
		}!!

	// --- pull

	@Test
	fun `the first sync creates the calendars and events, stores the cursor and records success`() {
		fake.put("work", remote("a", "Standup"))
		fake.put("work", remote("b", "Review", start = "2026-03-03T13:00:00Z"))

		sync.sync(source.id)

		val calendar = calendars.findBySourceIdAndExternalId(source.id, "work").shouldNotBeNull()
		calendar.name shouldBe "Work"
		calendar.color shouldBe "#112233"
		localTitles() shouldContainExactlyInAnyOrder listOf("Standup", "Review")
		val a = local("a").shouldNotBeNull()
		a.syncState shouldBe EventSyncState.SYNCED
		a.externalEtag shouldBe fake.event("work", RemoteEventKey("a"))!!.etag
		jdbc.queryForObject("select cursor from calendar_sync_cursor where calendar_id = ?", String::class.java, calendar.id) shouldBe "2"
		val status = sync.get(source.id).status.shouldNotBeNull()
		status.lastAttemptAt shouldBe MutableClockTestConfiguration.START
		status.lastSuccessAt shouldBe MutableClockTestConfiguration.START
		status.lastError.shouldBeNull()
		// The events are in the calendar views like local ones.
		api.occurrences("2026-03-02T00:00:00Z", "2026-03-04T00:00:00Z").map { it.get("title").asString() } shouldContainExactly
			listOf("Standup", "Review")
	}

	@Test
	fun `a later sync fetches only the changes since the cursor and merges changes, additions and deletions`() {
		fake.put("work", remote("a", "Standup"))
		fake.put("work", remote("b", "Review"))
		fake.put("work", remote("c", "Lunch"))
		sync.sync(source.id)
		val idA = local("a")!!.id
		val versionC = local("c")!!.version

		fake.put("work", remote("a", "Standup (moved)", start = "2026-03-02T10:00:00Z"))
		fake.remove("work", RemoteEventKey("b"))
		fake.put("work", remote("d", "Retro"))
		sync.sync(source.id)

		fake.fetchCursors.map { it.second } shouldContainExactly listOf(null, "3")
		localTitles() shouldContainExactlyInAnyOrder listOf("Standup (moved)", "Lunch", "Retro")
		val a = local("a")!!
		a.id shouldBe idA
		a.startAt shouldBe Instant.parse("2026-03-02T10:00:00Z")
		a.externalEtag shouldBe fake.event("work", RemoteEventKey("a"))!!.etag
		// Unchanged events are not rewritten; deleted ones are gone for good (not in the Trash).
		local("c")!!.version shouldBe versionC
		local("b").shouldBeNull()
		events.findAllDeleted().shouldBeEmpty()
	}

	@Test
	fun `series overrides are merged after their series, a removed occurrence becomes an EXDATE`() {
		fake.put("work", remote("s", "Weekly", rrule = "FREQ=WEEKLY;COUNT=5"))
		val second = OccurrenceId.of(Instant.parse("2026-03-09T09:00:00Z"))
		val third = OccurrenceId.of(Instant.parse("2026-03-16T09:00:00Z"))
		// The override arrives in the same page as (and listed before) its series.
		fake.put("work", remote("s", "Weekly (late)", start = "2026-03-09T11:00:00Z").copy(key = RemoteEventKey("s", second)))
		fake.pageSize = 1
		sync.sync(source.id)

		val series = local("s").shouldNotBeNull()
		val override = overridesOf(series).single()
		override.recurrenceId shouldBe second
		override.title shouldBe "Weekly (late)"
		override.externalId shouldBe "s"

		fake.pageSize = Int.MAX_VALUE
		fake.remove("work", RemoteEventKey("s", third))
		fake.remove("work", RemoteEventKey("s", second))
		sync.sync(source.id)

		overridesOf(series).shouldBeEmpty()
		exdatesOf(series.id) shouldContainExactlyInAnyOrder setOf(second, third)
	}

	@Test
	fun `a complete listing removes local events the provider no longer has`() {
		fake.put("work", remote("a", "Keep"))
		fake.put("work", remote("b", "Gone"))
		sync.sync(source.id)
		// The provider dropped "b" and no longer accepts the cursor (e.g. Google 410): the adapter lists everything.
		fake.remove("work", RemoteEventKey("b"))
		fake.rejectCursor = true
		sync.sync(source.id)

		localTitles() shouldContainExactly listOf("Keep")
	}

	@Test
	fun `calendars follow the provider - renamed, added and removed with their events`() {
		fake.put("work", remote("a", "Standup"))
		sync.sync(source.id)
		val workId = calendarId()
		calendars
			.findById(workId)
			.get()
			.apply { color = "#abcdef" }
			.let(calendars::saveAndFlush)

		fake.calendars.clear()
		fake.addCalendar("home", "Home", readOnly = true)
		fake.put("home", remote("h", "Dentist"))
		sync.sync(source.id)

		calendars.findById(workId).isPresent shouldBe false
		events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(workId, "a").shouldBeNull()
		val home = calendars.findBySourceIdAndExternalId(source.id, "home").shouldNotBeNull()
		home.readOnly shouldBe true
		localTitles("home") shouldContainExactly listOf("Dentist")

		fake.calendars[0] = RemoteCalendar("home", "Family", color = "#000000")
		sync.sync(source.id)
		calendars.findById(home.id).get().name shouldBe "Family"
		// Color is the user's after creation.
		calendars
			.findById(home.id)
			.get()
			.color
			.shouldBeNull()
	}

	@Test
	fun `a failing calendar is recorded as error, the others still sync, the next success clears it`() {
		fake.addCalendar("home", "Home")
		fake.put("home", remote("h", "Dentist"))
		fake.failFetch["work"] = CalendarProviderException("Server answered 503")

		sync.sync(source.id)

		localTitles("home") shouldContainExactly listOf("Dentist")
		val failed = sync.get(source.id).status!!
		failed.lastError shouldBe "Work: Server answered 503"
		failed.lastSuccessAt.shouldBeNull()

		fake.failFetch.clear()
		clock.advance(Duration.ofMinutes(1))
		sync.sync(source.id)
		val ok = sync.get(source.id).status!!
		ok.lastError.shouldBeNull()
		ok.lastSuccessAt shouldBe clock.instant()
	}

	@Test
	fun `a failing calendar listing still syncs the known calendars`() {
		fake.put("work", remote("a", "Standup"))
		sync.sync(source.id)
		fake.put("work", remote("b", "Review"))
		fake.failListing = CalendarProviderException("Unauthorized")

		sync.sync(source.id)

		localTitles() shouldContainExactlyInAnyOrder listOf("Standup", "Review")
		sync.get(source.id).status!!.lastError shouldBe "Calendars: Unauthorized"
	}

	@Test
	fun `syncDue syncs sources whose poll interval passed since the last attempt`() {
		sync.syncDue() shouldBe 1
		sync.syncDue() shouldBe 0
		clock.advance(Duration.ofMinutes(9))
		sync.syncDue() shouldBe 0
		clock.advance(Duration.ofMinutes(1))
		sync.syncDue() shouldBe 1
	}

	// --- API

	@Test
	fun `GET sources lists every source with its sync status, POST sync syncs one now`() {
		api.createCalendar() // creates the LOCAL source
		val listed =
			api
				.read(mockMvc.get(SOURCES).andExpect { status { isOk() } })
				.get("items")
				.values()
				.toList()
		listed.map { it.get("type").asString() } shouldContainExactly listOf("CALDAV", "LOCAL")
		val (caldav, localSource) = listed
		caldav.get("syncable").asBoolean() shouldBe true
		caldav.get("sync").get("state").asString() shouldBe "NEVER"
		caldav.get("sync").get("nextSyncAt").asString() shouldBe "2026-03-01T08:00:00Z"
		localSource.get("syncable").asBoolean() shouldBe false
		(localSource.get("sync")?.isNull ?: true) shouldBe true
		listed.forEach { it.has("config") shouldBe false }
		listed.forEach { it.has("credentials") shouldBe false }

		fake.put("work", remote("a", "Standup"))
		mockMvc.post("$SOURCES/${source.id}/sync") { with(csrf()) }.andExpect {
			status { isOk() }
			jsonPath("$.id") { value(source.id.toString()) }
			jsonPath("$.sync.state") { value("OK") }
			jsonPath("$.sync.lastSuccessAt") { value("2026-03-01T08:00:00Z") }
			jsonPath("$.sync.nextSyncAt") { value("2026-03-01T08:10:00Z") }
			jsonPath("$.sync.pollIntervalSeconds") { value(600) }
		}
		local("a").shouldNotBeNull()

		fake.failFetch["work"] = CalendarProviderException("Server answered 503")
		mockMvc.post("$SOURCES/${source.id}/sync") { with(csrf()) }.andExpect {
			status { isOk() }
			jsonPath("$.sync.state") { value("ERROR") }
			jsonPath("$.sync.lastError") { value("Work: Server answered 503") }
		}
	}

	@Test
	fun `POST sync of a local, unsupported or unknown source fails`() {
		val local = api.createCalendar().get("sourceId").asString()
		mockMvc.post("$SOURCES/$local/sync") { with(csrf()) }.isProblem(409)
		val google = sources.saveAndFlush(CalendarSource(CalendarSourceType.GOOGLE, "Google"))
		mockMvc.post("$SOURCES/${google.id}/sync") { with(csrf()) }.isProblem(409)
		mockMvc.post("$SOURCES/${UUID.randomUUID()}/sync") { with(csrf()) }.isProblem(404)
	}

	// --- write-through

	@Test
	fun `creating, changing and deleting an event of a synced calendar goes to the provider first`() {
		sync.sync(source.id)
		val created =
			api.createEvent(
				"""{"calendarId":"${calendarId()}","title":"Planning","start":"2026-03-04T09:00:00Z","end":"2026-03-04T10:00:00Z"}""",
			)
		val id = created.id()
		fake.writes shouldContainExactly listOf("create $id")
		val stored = fake.event("work", RemoteEventKey(id)).shouldNotBeNull()
		created.get("syncState").asString() shouldBe "SYNCED"
		events.findById(UUID.fromString(id)).get().externalEtag shouldBe stored.etag

		val changed = api.patchEventOk(id, """{"title":"Planning Q2"}""")
		fake.writes.last() shouldBe "update $id if-match=${stored.etag}"
		fake.event("work", RemoteEventKey(id))!!.title shouldBe "Planning Q2"
		changed.get("title").asString() shouldBe "Planning Q2"
		events.findById(UUID.fromString(id)).get().externalEtag shouldBe fake.event("work", RemoteEventKey(id))!!.etag

		val current = fake.event("work", RemoteEventKey(id))!!.etag
		api.deleteEvent(id).andExpect { status { isNoContent() } }
		fake.writes.last() shouldBe "delete $id if-match=$current"
		fake.event("work", RemoteEventKey(id)).shouldBeNull()
		// Gone for good: the provider has its own trash.
		events.findById(UUID.fromString(id)).isPresent shouldBe false
	}

	@Test
	fun `changing one occurrence of a synced series creates the override at the provider`() {
		fake.put("work", remote("s", "Weekly", rrule = "FREQ=WEEKLY;COUNT=5"))
		sync.sync(source.id)
		val series = local("s")!!

		api.patchEventOk(series.id.toString(), """{"title":"Weekly (moved)"}""", "?scope=THIS&occurrence=2026-03-09T09:00:00Z")

		fake.writes shouldContainExactly listOf("create s@2026-03-09T09:00:00Z")
		val key = RemoteEventKey("s", OccurrenceId.of(Instant.parse("2026-03-09T09:00:00Z")))
		fake.event("work", key)!!.title shouldBe "Weekly (moved)"
		val override = overridesOf(series).single()
		override.externalId shouldBe "s"
		override.externalEtag shouldBe fake.event("work", key)!!.etag

		// Deleting the series removes it with its overrides in one call.
		api.deleteEvent(series.id.toString()).andExpect { status { isNoContent() } }
		fake.writes.last() shouldContain "delete s if-match="
		fake.events("work").keys.shouldBeEmpty()
	}

	@Test
	fun `a remote change since the last sync makes a local edit fail with 409 and fetches the new version`() {
		fake.put("work", remote("a", "Standup"))
		sync.sync(source.id)
		val id = local("a")!!.id.toString()
		val etag = etag(api.event(id))
		fake.put("work", remote("a", "Standup (changed remotely)"))

		api.patchEvent(id, """{"title":"Mine"}""", ifMatch = etag).isProblem(409).andExpect {
			jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("changed remotely")) }
		}

		fake.event("work", RemoteEventKey("a"))!!.title shouldBe "Standup (changed remotely)"
		val refreshed = local("a")!!
		refreshed.title shouldBe "Standup (changed remotely)"
		refreshed.externalEtag shouldBe fake.event("work", RemoteEventKey("a"))!!.etag

		// The retry with the new version goes through.
		api.patchEventOk(id, """{"title":"Mine"}""")
		fake.event("work", RemoteEventKey("a"))!!.title shouldBe "Mine"
	}

	@Test
	fun `a failing provider makes the edit fail with 502 and leaves the local copy unchanged`() {
		fake.put("work", remote("a", "Standup"))
		sync.sync(source.id)
		val id = local("a")!!.id.toString()
		fake.failWrites = CalendarProviderException("Server answered 500")

		api.patchEvent(id, """{"title":"Mine"}""").isProblem(502)
		api
			.postJson(
				CalendarApi.EVENTS,
				"""{"calendarId":"${calendarId()}","title":"New","start":"2026-03-04T09:00:00Z","end":"2026-03-04T10:00:00Z"}""",
			).isProblem(502)

		local("a")!!.title shouldBe "Standup"
		localTitles() shouldContainExactly listOf("Standup")
	}

	@Test
	fun `events cannot be moved between a synced and another calendar, read-only synced calendars are not writable`() {
		fake.addCalendar("shared", "Shared", readOnly = true)
		fake.put("work", remote("a", "Standup"))
		fake.put("shared", remote("x", "Holiday"))
		sync.sync(source.id)
		val localCalendar = api.createCalendar().id()

		api.patchEvent(local("a")!!.id.toString(), """{"calendarId":"$localCalendar"}""").isProblem(409)
		api.patchEvent(local("x", "shared")!!.id.toString(), """{"title":"Mine"}""").isProblem(409)
		fake.writes.shouldBeEmpty()
	}

	@Test
	fun `with a series-wide etag, a write gives the new etag to the whole series`() {
		fake.seriesShareEtag = true
		fake.put("work", remote("s", "Weekly", rrule = "FREQ=WEEKLY;COUNT=5"))
		sync.sync(source.id)
		val series = local("s")!!

		api.patchEventOk(series.id.toString(), """{"title":"A"}""", "?scope=THIS&occurrence=2026-03-09T09:00:00Z")

		val etag = fake.event("work", RemoteEventKey("s", OccurrenceId.of(Instant.parse("2026-03-09T09:00:00Z"))))!!.etag
		local("s")!!.externalEtag shouldBe etag
		overridesOf(series).single().externalEtag shouldBe etag
	}

	@Test
	fun `a cancelled remote override is kept as a cancelled override`() {
		val second = OccurrenceId.of(Instant.parse("2026-03-09T09:00:00Z"))
		fake.put("work", remote("s", "Weekly", rrule = "FREQ=WEEKLY;COUNT=3"))
		fake.put(
			"work",
			remote("s", "Weekly").copy(key = RemoteEventKey("s", second), timing = timed("2026-03-09T09:00:00Z"), status = EventStatus.CANCELLED),
		)
		sync.sync(source.id)

		overridesOf(local("s")!!).single().status shouldBe EventStatus.CANCELLED
		api.occurrences("2026-03-01T00:00:00Z", "2026-03-31T00:00:00Z").size shouldBe 2
	}

	private companion object {
		const val SOURCES = "/api/v1/calendar/sources"
	}
}
