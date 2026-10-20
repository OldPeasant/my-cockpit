package ch.sonensei.cockpit.calendar.sync

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.id
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.isProblem
import ch.sonensei.cockpit.calendar.api.CalendarEventService
import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.Calendar
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.sync.FakeCalendarAdapter.Companion.ZURICH
import ch.sonensei.cockpit.calendar.sync.FakeCalendarAdapter.Companion.remote
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Series overrides and EXDATEs through the sync engine against [FakeCalendarAdapter]: incremental changes and complete
 * listings of overrides, remote EXDATEs, invalid remote data, write-through of known overrides (with their etag and the
 * 412 path), and providers whose series share one etag (CalDAV). Same context configuration as [CalendarSyncTests].
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class, FakeCalendarAdapterConfiguration::class)
@WithCockpitUser
class CalendarSyncOverrideTests(
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
		fake.addCalendar("work", "Work")
		source = sources.saveAndFlush(CalendarSource(CalendarSourceType.CALDAV, "Nextcloud"))
	}

	private fun calendarId(): UUID = calendars.findBySourceIdAndExternalId(source.id, "work")!!.id

	private fun local(uid: String): CalendarEvent? = events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(calendarId(), uid)

	private fun overridesOf(event: CalendarEvent) = events.findByParentEventIdOrderByIdAsc(event.id)

	private fun exdatesOf(id: UUID): Set<OccurrenceId> =
		tx.execute {
			events
				.findById(id)
				.get()
				.exdates
				.toSet()
		}

	private fun lastError(): String? = sync.get(source.id).status?.lastError

	private fun at(instant: String) = OccurrenceId.of(Instant.parse(instant))

	private fun marchTitles() = api.occurrences("2026-03-01T00:00:00Z", "2026-04-01T00:00:00Z").map { it.get("title").asString() }

	private fun putSeries(
		count: Int = 5,
		exdates: Set<OccurrenceId> = emptySet(),
	) = fake.put("work", remote("s", "Weekly", rrule = "FREQ=WEEKLY;COUNT=$count").copy(exdates = exdates))

	private fun putOverride(
		occurrence: String,
		title: String,
	) = fake.put("work", remote("s", title, start = occurrence).copy(key = RemoteEventKey("s", at(occurrence))))

	// --- pull: overrides and EXDATEs

	@Test
	fun `a remote change of a known override updates it in place, an unchanged override is not rewritten`() {
		putSeries()
		putOverride(SECOND, "Weekly (late)")
		putOverride(THIRD, "Weekly (early)")
		sync.sync(source.id)
		val series = local("s")!!
		val (second, third) = overridesOf(series).sortedBy { it.recurrenceId!!.at }
		val thirdVersion = third.version

		putOverride(SECOND, "Weekly (later)")
		sync.sync(source.id)

		val overrides = overridesOf(series)
		overrides.map { it.id } shouldContainExactlyInAnyOrder listOf(second.id, third.id)
		val changed = overrides.single { it.id == second.id }
		changed.title shouldBe "Weekly (later)"
		changed.externalEtag shouldBe fake.event("work", RemoteEventKey("s", at(SECOND)))!!.etag
		overrides.single { it.id == third.id }.version shouldBe thirdVersion
		lastError().shouldBeNull()
	}

	@Test
	fun `a complete listing removes an override the provider no longer has and keeps its series`() {
		putSeries()
		putOverride(SECOND, "Weekly (late)")
		putOverride(THIRD, "Weekly (early)")
		sync.sync(source.id)
		val series = local("s")!!
		// The provider dropped the exception (the occurrence is a plain one again) and no longer accepts the cursor.
		fake.remove("work", RemoteEventKey("s", at(SECOND)))
		fake.rejectCursor = true

		sync.sync(source.id)

		events.findById(series.id).isPresent shouldBe true
		overridesOf(series).map { it.recurrenceId } shouldContainExactly listOf(at(THIRD))
		exdatesOf(series.id).shouldBeEmpty()
		marchTitles() shouldContainExactly listOf("Weekly", "Weekly", "Weekly (early)", "Weekly", "Weekly")
	}

	@Test
	fun `remote EXDATEs of a series are taken over and follow later changes`() {
		putSeries(exdates = setOf(at(SECOND)))
		sync.sync(source.id)
		val series = local("s")!!

		exdatesOf(series.id) shouldContainExactly setOf(at(SECOND))
		marchTitles().size shouldBe 4

		// The provider restored the occurrence and removed another one.
		putSeries(exdates = setOf(at(THIRD)))
		sync.sync(source.id)

		exdatesOf(series.id) shouldContainExactly setOf(at(THIRD))
		api.occurrences("2026-03-09T00:00:00Z", "2026-03-10T00:00:00Z").size shouldBe 1
		api.occurrences("2026-03-16T00:00:00Z", "2026-03-17T00:00:00Z").shouldBeEmpty()
	}

	@Test
	fun `remote overrides of a single event or of an occurrence of the wrong kind are skipped and reported`() {
		putSeries()
		fake.put("work", remote("single", "Standup"))
		// An all-day recurrence id for a timed series, and an override of an event that is no series.
		fake.put("work", remote("s", "Wrong kind").copy(key = RemoteEventKey("s", OccurrenceId.of(LocalDate.parse("2026-03-09")))))
		fake.put("work", remote("single", "Not a series").copy(key = RemoteEventKey("single", at(SECOND))))

		sync.sync(source.id)

		overridesOf(local("s")!!).shouldBeEmpty()
		overridesOf(local("single")!!).shouldBeEmpty()
		lastError() shouldBe "Work: 2 events could not be stored"
	}

	@Test
	fun `a new remote event with invalid times is skipped and reported, the others are stored`() {
		fake.put("work", remote("ok", "Standup"))
		val start = Instant.parse("2026-03-03T10:00:00Z")
		fake.put("work", remote("bad", "Broken").copy(timing = Timing.Timed(start, start.minusSeconds(3600), ZURICH)))

		sync.sync(source.id)

		local("ok").shouldNotBeNull()
		local("bad").shouldBeNull()
		lastError() shouldBe "Work: 1 events could not be stored"
	}

	@Test
	fun `an invalid remote change of a known event is skipped and leaves the local copy unchanged`() {
		fake.put("work", remote("a", "Standup"))
		sync.sync(source.id)
		val before = local("a")!!
		val start = Instant.parse("2026-03-03T10:00:00Z")
		fake.put("work", remote("a", "Broken").copy(timing = Timing.Timed(start, start.minusSeconds(3600), ZURICH)))

		sync.sync(source.id)

		lastError() shouldBe "Work: 1 events could not be stored"
		// "Could not be stored" means nothing of it: no half-applied remote change under the old etag.
		val after = local("a")!!
		after.title shouldBe "Standup"
		after.externalEtag shouldBe before.externalEtag
		after.version shouldBe before.version
	}

	// --- paging order (CR-1)

	@Test
	fun `an override on an earlier page than its series is stored with it`() {
		sync.sync(source.id)
		val override = remote("s", "Weekly (late)", start = SECOND).copy(key = RemoteEventKey("s", at(SECOND)), etag = "e2")
		val series = remote("s", "Weekly", rrule = "FREQ=WEEKLY;COUNT=5").copy(etag = "e1")
		fake.fetchHook = { _, cursor ->
			when (cursor) {
				"page-2" -> RemoteEventChanges(events = listOf(series), cursor = "page-3")
				else -> RemoteEventChanges(events = listOf(override), cursor = "page-2", hasMore = true)
			}
		}

		sync.sync(source.id)

		overridesOf(local("s")!!).map { it.title } shouldContainExactly listOf("Weekly (late)")
		lastError().shouldBeNull()
	}

	@Test
	fun `an incremental override without its series is reported and makes the next sync a full one`() {
		sync.sync(source.id)
		putSeries()
		putOverride(SECOND, "Weekly (late)")
		val override = fake.event("work", RemoteEventKey("s", at(SECOND)))!!
		// The provider's changes hold the override but (wrongly or on a later cursor) not its series.
		fake.fetchHook = { _, cursor -> if (cursor == null) null else RemoteEventChanges(events = listOf(override), cursor = "99") }

		sync.sync(source.id)

		lastError() shouldBe "Work: 1 events could not be stored"
		local("s").shouldBeNull()

		fake.fetchHook = null
		fake.fetchCursors.clear()
		sync.sync(source.id)

		fake.fetchCursors.first() shouldBe ("work" to null)
		overridesOf(local("s")!!).map { it.title } shouldContainExactly listOf("Weekly (late)")
		lastError().shouldBeNull()
	}

	@Test
	fun `a deletion on an earlier page than a re-creation of the same event keeps the event`() {
		fake.put("work", remote("a", "Standup"))
		sync.sync(source.id)
		val again = remote("a", "Standup (again)").copy(etag = "e9")
		fake.fetchHook = { _, cursor ->
			when (cursor) {
				"page-2" -> RemoteEventChanges(events = listOf(again), cursor = "page-3")
				else -> RemoteEventChanges(deleted = listOf(RemoteEventKey("a")), cursor = "page-2", hasMore = true)
			}
		}

		sync.sync(source.id)

		local("a")!!.title shouldBe "Standup (again)"
	}

	// --- untrusted remote content (CR-2)

	@Test
	fun `remote events are kept within the API's limits - non-web conference URLs, invalid attendees and reminders are dropped`() {
		fake.put(
			"work",
			remote("a", "x".repeat(600)).copy(
				description = "d".repeat(CalendarEventService.MAX_DESCRIPTION_LENGTH + 1),
				location = "l".repeat(CalendarEventService.MAX_TEXT_LENGTH + 1),
				conferenceUrl = "javascript:alert(1)",
				attendees = listOf(Attendee("ann@example.org", "n".repeat(300)), Attendee("not-an-address")),
				reminderMinutes = listOf(10, -5, CalendarEventService.MAX_REMINDER_MINUTES + 1),
			),
		)
		fake.put("work", remote("b", "Call").copy(conferenceUrl = "https://meet.example.org/abc"))

		sync.sync(source.id)

		lastError().shouldBeNull()
		val a = local("a")!!
		a.title.length shouldBe CalendarEventService.MAX_TITLE_LENGTH
		a.description!!.length shouldBe CalendarEventService.MAX_DESCRIPTION_LENGTH
		a.location!!.length shouldBe CalendarEventService.MAX_TEXT_LENGTH
		a.conferenceUrl.shouldBeNull()
		a.attendees shouldContainExactly listOf(Attendee("ann@example.org", "n".repeat(CalendarEventService.MAX_NAME_LENGTH)))
		a.reminderMinutes shouldContainExactly listOf(10)
		local("b")!!.conferenceUrl shouldBe "https://meet.example.org/abc"
		// The pulled event stays editable: the API's validation of the whole event passes.
		api.patchEventOk(a.id.toString(), """{"location":"Room 4"}""")
	}

	@Test
	fun `a remote series with an invalid RRULE is skipped and reported, a prefixed one is stored without prefix`() {
		fake.put("work", remote("bad", "Broken", rrule = "FREQ=SOMETIMES"))
		fake.put("work", remote("ok", "Weekly", rrule = "RRULE:FREQ=WEEKLY;COUNT=2"))

		sync.sync(source.id)

		local("bad").shouldBeNull()
		local("ok")!!.rrule shouldBe "FREQ=WEEKLY;COUNT=2"
		lastError() shouldBe "Work: 1 events could not be stored"
	}

	// --- write-through: search and conflicts (CR-3, CR-5)

	@Test
	fun `after a write-through, search shows the provider's normalized answer`() {
		fake.put("work", remote("a", "Standup"))
		sync.sync(source.id)
		val id = local("a")!!.id
		fake.normalizeWrites = { it.copy(title = it.title.uppercase()) }

		api.patchEventOk(id.toString(), """{"title":"Retro"}""")

		local("a")!!.title shouldBe "RETRO"
		jdbc
			.query("select title from search_document where item_type = 'EVENT' and item_id = ?", { rs, _ -> rs.getString(1) }, id)
			.single() shouldBe "RETRO"
	}

	@Test
	fun `a refetch does not wait for a sync of the same source that is in progress`() {
		sync.sync(source.id)
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		fake.fetchHook = { _, _ ->
			entered.countDown()
			release.await(10, TimeUnit.SECONDS)
			null
		}
		val running = thread { sync.sync(source.id) }
		try {
			entered.await(10, TimeUnit.SECONDS) shouldBe true
			val fetches = fake.fetchCursors.size

			val took = measureTime { sync.refetch(calendarId()) }

			took shouldBeLessThan 5.seconds
			fake.fetchCursors.size shouldBe fetches
		} finally {
			release.countDown()
			running.join(10_000)
		}
	}

	// --- write-through of known overrides

	@Test
	fun `changing a synced override again updates it at the provider with its etag`() {
		putSeries()
		putOverride(SECOND, "Weekly (late)")
		sync.sync(source.id)
		val series = local("s")!!
		val etag = overridesOf(series).single().externalEtag.shouldNotBeNull()

		api.patchEventOk(series.id.toString(), """{"title":"Weekly (later)"}""", "?scope=THIS&occurrence=$SECOND")

		fake.writes shouldContainExactly listOf("update s@$SECOND if-match=$etag")
		val stored = fake.event("work", RemoteEventKey("s", at(SECOND)))!!
		stored.title shouldBe "Weekly (later)"
		overridesOf(series).single().externalEtag shouldBe stored.etag
	}

	@Test
	fun `a remote change of an override makes a local edit of it fail with 409 and fetches the new version`() {
		putSeries()
		putOverride(SECOND, "Weekly (late)")
		sync.sync(source.id)
		val series = local("s")!!
		val override = overridesOf(series).single()
		putOverride(SECOND, "Weekly (changed remotely)")

		api.patchEvent(override.id.toString(), """{"title":"Mine"}""").isProblem(409).andExpect {
			jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("changed remotely")) }
		}

		val refreshed = overridesOf(series).single()
		refreshed.id shouldBe override.id
		refreshed.title shouldBe "Weekly (changed remotely)"
		refreshed.externalEtag shouldBe fake.event("work", RemoteEventKey("s", at(SECOND)))!!.etag
		fake.event("work", RemoteEventKey("s", at(SECOND)))!!.title shouldBe "Weekly (changed remotely)"
	}

	// --- one etag per series (CalDAV)

	@Test
	fun `with a series-wide etag, changing the series after one of its occurrences is not refused`() {
		fake.seriesShareEtag = true
		putSeries()
		sync.sync(source.id)
		val series = local("s")!!
		api.patchEventOk(series.id.toString(), """{"title":"Weekly (late)"}""", "?scope=THIS&occurrence=$SECOND")

		api.patchEventOk(series.id.toString(), """{"location":"Room 4"}""", "?scope=ALL")

		val remoteSeries = fake.event("work", RemoteEventKey("s"))!!
		remoteSeries.location shouldBe "Room 4"
		local("s")!!.externalEtag shouldBe remoteSeries.etag
		overridesOf(series).single().externalEtag shouldBe remoteSeries.etag
	}

	@Test
	fun `with a series-wide etag, removing an override gives the series the provider's new etag before it is written`() {
		fake.seriesShareEtag = true
		putSeries()
		putOverride(FOURTH, "Weekly (late)")
		sync.sync(source.id)
		val series = local("s")!!
		val etag = series.externalEtag.shouldNotBeNull()

		api.deleteEvent(series.id.toString(), "?scope=FOLLOWING&occurrence=$THIRD").andExpect { status { isNoContent() } }

		fake.writes.first() shouldBe "delete s@$FOURTH if-match=$etag"
		// The removal changed the series resource: the series update must send its new etag, not the old one.
		fake.writes.last() shouldContain "update s if-match="
		fake.writes.last() shouldNotBe "update s if-match=$etag"
		fake.event("work", RemoteEventKey("s"))!!.rrule!! shouldContain "UNTIL="
		local("s")!!.externalEtag shouldBe fake.event("work", RemoteEventKey("s"))!!.etag
	}

	// --- calendars and refetch

	@Test
	fun `a calendar that becomes read-only at the provider becomes read-only here`() {
		sync.sync(source.id)
		calendars.findById(calendarId()).get().readOnly shouldBe false

		fake.calendars[0] = RemoteCalendar("work", "Work", readOnly = true)
		sync.sync(source.id)

		calendars.findById(calendarId()).get().readOnly shouldBe true
	}

	@Test
	fun `a refetch of an unknown, a local or a not yet synced calendar fetches nothing`() {
		sync.sync(source.id)
		fake.fetchCursors.clear()
		val localCalendar = UUID.fromString(api.createCalendar().id())
		val unsynced = calendars.saveAndFlush(Calendar(source.id, "Not synced yet")).id

		sync.refetch(UUID.randomUUID())
		sync.refetch(localCalendar)
		sync.refetch(unsynced)

		fake.fetchCursors.shouldBeEmpty()
	}

	private companion object {
		const val SECOND = "2026-03-09T09:00:00Z"
		const val THIRD = "2026-03-16T09:00:00Z"
		const val FOURTH = "2026-03-23T09:00:00Z"
	}
}
