package ch.sonensei.cockpit.calendar.sync

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.id
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.isProblem
import ch.sonensei.cockpit.calendar.api.CalendarEventService
import ch.sonensei.cockpit.calendar.api.EventChanges
import ch.sonensei.cockpit.calendar.api.FieldChange
import ch.sonensei.cockpit.calendar.api.TimingInput
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.model.EventSyncState
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.sync.FakeCalendarAdapter.Companion.remote
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.hibernate.Hibernate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Edge cases of the sync engine against [FakeCalendarAdapter]: paging, removed items with links, failures, ordering and
 * deduplication of write-through, and concurrent runs. Same context configuration as [CalendarSyncTests].
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class, FakeCalendarAdapterConfiguration::class)
@WithCockpitUser
class CalendarSyncEdgeCaseTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val sources: CalendarSourceRepository,
	@Autowired private val calendars: CalendarRepository,
	@Autowired private val events: CalendarEventRepository,
	@Autowired private val sync: CalendarSyncService,
	@Autowired private val writeThrough: CalendarWriteThrough,
	@Autowired private val eventService: CalendarEventService,
	@Autowired private val links: LinkService,
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
		fake.addCalendar("work", "Work")
		source = sources.saveAndFlush(CalendarSource(CalendarSourceType.CALDAV, "Nextcloud"))
	}

	private fun calendarId(externalId: String = "work"): UUID = calendars.findBySourceIdAndExternalId(source.id, externalId)!!.id

	private fun local(uid: String): CalendarEvent? = events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(calendarId(), uid)

	private fun localTitles(): List<String> =
		events
			.findAllById(events.findIdsByCalendarId(calendarId()))
			.filter {
				!it.isOverride
			}.map { it.title }

	private fun overridesOf(event: CalendarEvent) = events.findByParentEventIdOrderByIdAsc(event.id)

	private fun lastError(): String? = sync.get(source.id).status?.lastError

	private fun linkCount(id: UUID): Int =
		jdbc.queryForObject("select count(*) from link where source_id = ? or target_id = ?", Int::class.java, id, id)!!

	private fun searchCount(id: UUID): Int =
		jdbc.queryForObject("select count(*) from search_document where item_id = ?", Int::class.java, id)!!

	private fun storedCursor(): String? =
		jdbc
			.queryForList("select cursor from calendar_sync_cursor where calendar_id = ?", String::class.java, calendarId())
			.singleOrNull()

	private fun at(instant: String) = OccurrenceId.of(Instant.parse(instant))

	// --- paging

	@Test
	fun `pages with hasMore are fetched in one run with the returned cursors, and the last cursor is stored`() {
		(1..5).forEach { fake.put("work", remote("e$it", "Event $it")) }
		fake.pageSize = 2

		sync.sync(source.id)

		fake.fetchCursors.map { it.second } shouldContainExactly listOf(null, "full:2", "full:4")
		localTitles() shouldContainExactlyInAnyOrder (1..5).map { "Event $it" }
		storedCursor() shouldBe "5"
		lastError().shouldBeNull()
	}

	@Test
	fun `a complete listing over several pages keeps the events of every page and removes only the missing ones`() {
		(1..4).forEach { fake.put("work", remote("e$it", "Event $it")) }
		sync.sync(source.id)
		fake.remove("work", RemoteEventKey("e2"))
		fake.rejectCursor = true
		fake.pageSize = 1

		sync.sync(source.id)

		localTitles() shouldContainExactlyInAnyOrder listOf("Event 1", "Event 3", "Event 4")
	}

	@Test
	fun `a provider that never stops paging fails the calendar after MAX_PAGES pages without storing anything`() {
		var calls = 0
		fake.fetchHook = { _, _ ->
			calls++
			RemoteEventChanges(events = listOf(remote("e$calls", "Event $calls")), cursor = "page-$calls", hasMore = true)
		}

		sync.sync(source.id)

		calls shouldBe CalendarSyncService.MAX_PAGES
		lastError() shouldBe "Work: The provider sent more than ${CalendarSyncService.MAX_PAGES} pages of changes"
		localTitles().shouldBeEmpty()
		storedCursor().shouldBeNull()
	}

	@Test
	fun `a page without cursor drops the stored one, so the next fetch is a full one`() {
		fake.put("work", remote("a", "Standup"))
		sync.sync(source.id)
		storedCursor() shouldBe "1"
		fake.fetchHook = { _, _ -> RemoteEventChanges(cursor = null) }

		sync.sync(source.id)
		storedCursor().shouldBeNull()
		fake.fetchHook = null
		sync.sync(source.id)

		fake.fetchCursors.map { it.second } shouldContainExactly listOf(null, "1", null)
	}

	// --- removed remote items

	@Test
	fun `an event deleted at the provider loses its links and its search entry`() {
		fake.put("work", remote("a", "Standup"))
		fake.put("work", remote("b", "Review"))
		sync.sync(source.id)
		val a = local("a")!!.id
		val b = local("b")!!.id
		links.create(ItemRef(ItemType.EVENT, a), ItemRef(ItemType.EVENT, b), "prepares")
		linkCount(b) shouldBe 1
		searchCount(b) shouldBe 1

		fake.remove("work", RemoteEventKey("b"))
		sync.sync(source.id)

		events.findById(b).isPresent shouldBe false
		linkCount(b) shouldBe 0
		linkCount(a) shouldBe 0
		searchCount(b) shouldBe 0
		searchCount(a) shouldBe 1
	}

	@Test
	fun `a series deleted at the provider goes with its overrides and their links`() {
		fake.put("work", remote("s", "Weekly", rrule = "FREQ=WEEKLY;COUNT=5"))
		fake.put("work", remote("s", "Weekly (late)", start = "2026-03-09T11:00:00Z").copy(key = RemoteEventKey("s", at("2026-03-09T09:00:00Z"))))
		fake.put("work", remote("x", "Other"))
		sync.sync(source.id)
		val series = local("s")!!
		val override = overridesOf(series).single()
		links.create(ItemRef(ItemType.EVENT, override.id), ItemRef(ItemType.EVENT, local("x")!!.id), null)

		fake.remove("work", RemoteEventKey("s"))
		sync.sync(source.id)

		events.findById(series.id).isPresent shouldBe false
		events.findById(override.id).isPresent shouldBe false
		linkCount(override.id) shouldBe 0
		localTitles() shouldContainExactly listOf("Other")
	}

	@Test
	fun `a deletion of an event the cockpit never had is ignored`() {
		fake.put("work", remote("a", "Standup"))
		sync.sync(source.id)
		fake.fetchHook =
			{
				_,
				_,
				->
				RemoteEventChanges(deleted = listOf(RemoteEventKey("unknown"), RemoteEventKey("a", at("2026-03-09T09:00:00Z"))), cursor = "9")
			}

		sync.sync(source.id)

		localTitles() shouldContainExactly listOf("Standup")
		lastError().shouldBeNull()
	}

	@Test
	fun `a calendar removed at the provider takes the links of its events with it`() {
		fake.addCalendar("home", "Home")
		fake.put("work", remote("a", "Standup"))
		fake.put("home", remote("h", "Dentist"))
		sync.sync(source.id)
		val a = local("a")!!.id
		val h = events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(calendarId("home"), "h")!!.id
		links.create(ItemRef(ItemType.EVENT, a), ItemRef(ItemType.EVENT, h), null)

		fake.calendars.removeIf { it.externalId == "home" }
		sync.sync(source.id)

		calendars.findBySourceIdAndExternalId(source.id, "home").shouldBeNull()
		events.findById(h).isPresent shouldBe false
		linkCount(a) shouldBe 0
	}

	// --- remote data the engine normalizes or skips

	@Test
	fun `blank titles and names become placeholders, invalid colors and duplicate calendars are ignored`() {
		fake.calendars.clear()
		fake.addCalendar("work", " ", color = "red")
		fake.calendars += RemoteCalendar("work", "Duplicate")
		fake.put("work", remote("a", "  "))

		sync.sync(source.id)

		val calendar = calendars.findBySourceIdAndExternalId(source.id, "work").shouldNotBeNull()
		calendar.name shouldBe "(no title)"
		calendar.color.shouldBeNull()
		calendars.findBySourceIdOrderByIdAsc(source.id) shouldHaveSize 1
		local("a")!!.title shouldBe "(no title)"
	}

	@Test
	fun `an override without its series is skipped and reported, the rest of the calendar is stored`() {
		fake.put("work", remote("a", "Standup"))
		fake.put("work", remote("ghost", "Orphan").copy(key = RemoteEventKey("ghost", at("2026-03-09T09:00:00Z"))))

		sync.sync(source.id)

		localTitles() shouldContainExactly listOf("Standup")
		lastError() shouldBe "Work: 1 events could not be stored"
		storedCursor() shouldBe "2"
	}

	// --- failures

	@Test
	fun `an unexpected adapter error is reported without its message`() {
		fake.addCalendar("home", "Home")
		fake.failFetch["work"] = IllegalStateException("token=fictitious-secret-123 body=Private meeting")

		sync.sync(source.id)

		val error = lastError().shouldNotBeNull()
		error shouldBe "Work: Unexpected error (see the server log)"
		error shouldNotContain "secret"
		error shouldNotContain "Private"
	}

	@Test
	fun `an unexpected error listing calendars is reported generically, a blank provider message gets a default`() {
		fake.put("work", remote("a", "Standup"))
		sync.sync(source.id)
		fake.failListing = IllegalStateException("fictitious-secret-123")
		fake.failFetch["work"] = CalendarProviderException(" ")

		sync.sync(source.id)

		lastError() shouldBe "Calendars: Unexpected error (see the server log); Work: The provider failed"
	}

	@Test
	fun `a very long error is cut to MAX_ERROR_LENGTH`() {
		fake.failFetch["work"] = CalendarProviderException("x".repeat(5000))

		sync.sync(source.id)

		lastError()!!.length shouldBe CalendarSyncService.MAX_ERROR_LENGTH
	}

	// --- removed or unsyncable sources

	@Test
	fun `a source removed while it syncs gets no status, and the sync reports it as not found`() {
		fake.put("work", remote("a", "Standup"))
		fake.fetchHook = { _, _ ->
			jdbc.update("delete from calendar_source where id = ?", source.id)
			null
		}

		assertThrows<NotFoundException> { sync.sync(source.id) }

		jdbc.queryForObject("select count(*) from calendar_source_sync", Int::class.java) shouldBe 0
		jdbc.queryForObject("select count(*) from calendar_event", Int::class.java) shouldBe 0
	}

	@Test
	fun `removing a source removes its sync status and cursors`() {
		fake.put("work", remote("a", "Standup"))
		sync.sync(source.id)
		jdbc.queryForObject("select count(*) from calendar_source_sync", Int::class.java) shouldBe 1

		jdbc.update("delete from calendar_source where id = ?", source.id)

		jdbc.queryForObject("select count(*) from calendar_source_sync", Int::class.java) shouldBe 0
		jdbc.queryForObject("select count(*) from calendar_sync_cursor", Int::class.java) shouldBe 0
		assertThrows<NotFoundException> { sync.get(source.id) }
	}

	@Test
	fun `GET sources shows a source without adapter as not syncable, without status`() {
		val google = sources.saveAndFlush(CalendarSource(CalendarSourceType.GOOGLE, "Google"))

		val listed =
			api
				.read(mockMvc.get("/api/v1/calendar/sources").andExpect { status { isOk() } })
				.get("items")
				.values()
				.toList()

		val node = listed.single { it.get("id").asString() == google.id.toString() }
		node.get("syncable").asBoolean() shouldBe false
		(node.get("sync")?.isNull ?: true) shouldBe true
	}

	@Test
	fun `syncDue never syncs local sources or types without adapter`() {
		sources.saveAndFlush(CalendarSource(CalendarSourceType.GOOGLE, "Google"))
		api.createCalendar() // the LOCAL source

		sync.syncDue() shouldBe 1
		fake.fetchCursors.map { it.first } shouldContainExactly listOf("work")
	}

	@Test
	fun `syncDue skips a source whose run is in progress`() {
		val executor = Executors.newSingleThreadExecutor()
		try {
			var concurrentDue = -1
			fake.fetchHook = { _, _ ->
				fake.fetchHook = null
				// Another thread checks while this run holds the source's lock.
				concurrentDue = executor.submit<Int> { sync.syncDue() }.get(30, TimeUnit.SECONDS)
				null
			}

			sync.sync(source.id)

			concurrentDue shouldBe 1 // due, but not run again
			fake.fetchCursors shouldHaveSize 1
		} finally {
			executor.shutdownNow()
		}
	}

	// --- write-through

	@Test
	fun `splitting a synced series (FOLLOWING) ends the old series at the provider and creates the new one after it`() {
		fake.put("work", remote("s", "Weekly", rrule = "FREQ=WEEKLY;COUNT=5"))
		sync.sync(source.id)
		val series = local("s")!!
		val etag = series.externalEtag

		val created = api.patchEventOk(series.id.toString(), """{"title":"Weekly (new)"}""", "?scope=FOLLOWING&occurrence=2026-03-16T09:00:00Z")

		val newId = created.id()
		fake.writes shouldContainExactly listOf("update s if-match=$etag", "create $newId")
		fake.event("work", RemoteEventKey("s"))!!.rrule!! shouldContain "UNTIL="
		val remoteNew = fake.event("work", RemoteEventKey(newId)).shouldNotBeNull()
		remoteNew.title shouldBe "Weekly (new)"
		remoteNew.rrule.shouldNotBeNull()
		val localNew = events.findById(UUID.fromString(newId)).get()
		localNew.externalId shouldBe newId
		localNew.externalEtag shouldBe remoteNew.etag
		localNew.syncState shouldBe EventSyncState.SYNCED
		local("s")!!.externalEtag shouldBe fake.event("work", RemoteEventKey("s"))!!.etag
	}

	@Test
	fun `splitting a synced series with a later override deletes it remotely first and recreates it under the new series`() {
		fake.put("work", remote("s", "Weekly", rrule = "FREQ=WEEKLY;COUNT=5"))
		val fourth = at("2026-03-23T09:00:00Z")
		fake.put("work", remote("s", "Weekly (late)", start = "2026-03-23T11:00:00Z").copy(key = RemoteEventKey("s", fourth)))
		sync.sync(source.id)
		val series = local("s")!!
		val overrideEtag = overridesOf(series).single().externalEtag

		val created = api.patchEventOk(series.id.toString(), """{"title":"Weekly (new)"}""", "?scope=FOLLOWING&occurrence=2026-03-16T09:00:00Z")

		val newId = created.id()
		fake.writes shouldContainExactly
			listOf(
				"delete s@2026-03-23T09:00:00Z if-match=$overrideEtag",
				"update s if-match=${series.externalEtag}",
				"create $newId",
				"create $newId@2026-03-23T09:00:00Z",
			)
		fake.event("work", RemoteEventKey("s", fourth)).shouldBeNull()
		val moved = fake.event("work", RemoteEventKey(newId, fourth)).shouldNotBeNull()
		moved.title shouldBe "Weekly (late)"
		val localOverride = overridesOf(events.findById(UUID.fromString(newId)).get()).single()
		localOverride.externalId shouldBe newId
		localOverride.externalEtag shouldBe moved.etag
	}

	@Test
	fun `a failing write in the middle of a split rolls the local edit back, the next sync brings the provider's state`() {
		fake.put("work", remote("s", "Weekly", rrule = "FREQ=WEEKLY;COUNT=5"))
		sync.sync(source.id)
		val series = local("s")!!
		fake.failWriteOps["create"] = CalendarProviderException("Server answered 500")

		api
			.patchEvent(series.id.toString(), """{"title":"Weekly (new)"}""", "?scope=FOLLOWING&occurrence=2026-03-16T09:00:00Z")
			.isProblem(502)
			.andExpect { jsonPath("$.detail") { value("The calendar provider failed: Server answered 500") } }

		localTitles() shouldContainExactly listOf("Weekly")
		local("s")!!.rrule shouldBe "FREQ=WEEKLY;COUNT=5"
		// The provider took the first write (ADR 0002 "Consequences"): the next sync converges on it.
		fake.failWriteOps.clear()
		sync.sync(source.id)
		local("s")!!.rrule!! shouldContain "UNTIL="
	}

	@Test
	fun `deleting the following occurrences of a synced series ends it remotely and deletes later overrides first`() {
		fake.put("work", remote("s", "Weekly", rrule = "FREQ=WEEKLY;COUNT=5"))
		fake.put("work", remote("s", "Weekly (late)", start = "2026-03-23T11:00:00Z").copy(key = RemoteEventKey("s", at("2026-03-23T09:00:00Z"))))
		sync.sync(source.id)
		val series = local("s")!!
		val overrideEtag = overridesOf(series).single().externalEtag

		api.deleteEvent(series.id.toString(), "?scope=FOLLOWING&occurrence=2026-03-16T09:00:00Z").andExpect { status { isNoContent() } }

		fake.writes shouldContainExactly
			listOf("delete s@2026-03-23T09:00:00Z if-match=$overrideEtag", "update s if-match=${series.externalEtag}")
		fake.event("work", RemoteEventKey("s"))!!.rrule!! shouldContain "UNTIL="
		overridesOf(series).shouldBeEmpty()
	}

	@Test
	fun `deleting one occurrence of a synced series updates the series with an EXDATE`() {
		fake.put("work", remote("s", "Weekly", rrule = "FREQ=WEEKLY;COUNT=5"))
		sync.sync(source.id)
		val series = local("s")!!
		val second = at("2026-03-09T09:00:00Z")

		api.deleteEvent(series.id.toString(), "?scope=THIS&occurrence=2026-03-09T09:00:00Z").andExpect { status { isNoContent() } }

		fake.writes shouldContainExactly listOf("update s if-match=${series.externalEtag}")
		fake.event("work", RemoteEventKey("s"))!!.exdates shouldContainExactly setOf(second)
	}

	@Test
	fun `an event created and deleted in one transaction never reaches the provider`() {
		sync.sync(source.id)

		tx.execute {
			val created = eventService.create(null, changes("Scratch"))
			eventService.delete(created.event.id, null, null) {}
		}

		fake.writes.shouldBeEmpty()
		fake.events("work").keys.shouldBeEmpty()
		localTitles().shouldBeEmpty()
	}

	@Test
	fun `an event created and changed in one transaction is created once, with the last state`() {
		sync.sync(source.id)

		val id =
			tx.execute {
				val created = eventService.create(null, changes("Draft"))
				eventService.update(created.event.id, null, null, EventChanges(title = FieldChange("Final"))) {}
				created.event.id
			}!!

		fake.writes shouldContainExactly listOf("create $id")
		fake.event("work", RemoteEventKey(id.toString()))!!.title shouldBe "Final"
		events.findById(id).get().externalEtag shouldBe fake.event("work", RemoteEventKey(id.toString()))!!.etag
	}

	@Test
	fun `an event changed and then deleted in one transaction is only deleted at the provider`() {
		fake.put("work", remote("a", "Standup"))
		sync.sync(source.id)
		val a = local("a")!!

		tx.execute {
			eventService.update(a.id, null, null, EventChanges(title = FieldChange("Changed"))) {}
			eventService.delete(a.id, null, null) {}
		}

		fake.writes shouldContainExactly listOf("delete a if-match=${a.externalEtag}")
		fake.event("work", RemoteEventKey("a")).shouldBeNull()
	}

	@Test
	fun `a recorded change outside a transaction is written at once, events of local calendars are ignored`() {
		fake.put("work", remote("a", "Standup"))
		sync.sync(source.id)
		// Loaded with its EXDATEs: the entity is detached when recorded.
		val a = tx.execute { local("a")!!.also { Hibernate.initialize(it.exdates) } }!!.apply { title = "Pushed" }
		val etag = a.externalEtag

		writeThrough.record(a, ChangeOp.UPDATED)

		fake.writes shouldContainExactly listOf("update a if-match=$etag")
		a.externalEtag shouldBe fake.event("work", RemoteEventKey("a"))!!.etag
		fake.event("work", RemoteEventKey("a"))!!.title shouldBe "Pushed"

		val localCalendar = UUID.fromString(api.createCalendar().id())
		writeThrough.isSynced(localCalendar) shouldBe false
		writeThrough.record(CalendarEvent(localCalendar, "Local"), ChangeOp.CREATED)
		fake.writes shouldHaveSize 1
	}

	@Test
	fun `a remote change makes a local delete fail with 409, the event stays and shows the new version`() {
		fake.put("work", remote("a", "Standup"))
		sync.sync(source.id)
		val id = local("a")!!.id.toString()
		fake.put("work", remote("a", "Standup (changed remotely)"))

		api.deleteEvent(id).isProblem(409)

		local("a")!!.title shouldBe "Standup (changed remotely)"
		fake.event("work", RemoteEventKey("a")).shouldNotBeNull()
	}

	@Test
	fun `the 409 for a remote change is returned even when the refetch fails`() {
		fake.put("work", remote("a", "Standup"))
		sync.sync(source.id)
		val id = local("a")!!.id.toString()
		fake.put("work", remote("a", "Standup (changed remotely)"))
		fake.failFetch["work"] = CalendarProviderException("Server answered 503")

		api.patchEvent(id, """{"title":"Mine"}""").isProblem(409)

		local("a")!!.title shouldBe "Standup"
	}

	@Test
	fun `an event of a local calendar cannot move into a synced calendar`() {
		sync.sync(source.id)
		val localCalendar = api.createCalendar().id()
		val event =
			api.createEvent(
				"""{"calendarId":"$localCalendar","title":"Mine","start":"2026-03-04T09:00:00Z","end":"2026-03-04T10:00:00Z"}""",
			)

		api.patchEvent(event.id(), """{"calendarId":"${calendarId()}"}""").isProblem(409).andExpect {
			jsonPath("$.detail") { value("Events cannot be moved to or from a synced calendar") }
		}
		fake.writes.shouldBeEmpty()
	}

	@Test
	fun `creating an override of a series the provider created gets the series UID`() {
		sync.sync(source.id)
		val created =
			api.createEvent(
				"""{"calendarId":"${calendarId()}","title":"Weekly","start":"2026-03-02T09:00:00Z","end":"2026-03-02T10:00:00Z","rrule":"FREQ=WEEKLY;COUNT=3"}""",
			)
		val id = created.id()

		api.patchEventOk(id, """{"title":"Moved"}""", "?scope=THIS&occurrence=2026-03-09T09:00:00Z")

		fake.writes shouldContainExactly listOf("create $id", "create $id@2026-03-09T09:00:00Z")
		overridesOf(events.findById(UUID.fromString(id)).get()).single().externalId shouldBe id
	}

	private fun changes(title: String) =
		EventChanges(
			calendarId = FieldChange(calendarId()),
			title = FieldChange(title),
			timing = TimingInput(start = Instant.parse("2026-03-04T09:00:00Z"), end = Instant.parse("2026-03-04T10:00:00Z")),
		)
}
