package ch.sonensei.cockpit.calendar.model

import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.search.domain.SearchService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Edge cases of the EVENT resolver (SPEC.md §4.3) and the event search hooks (§4.4). */
@SpringBootTest
@Import(MutableClockTestConfiguration::class)
class CalendarItemsAndSearchEdgeCaseTests(
	@Autowired private val registry: ItemResolverRegistry,
	@Autowired private val sources: CalendarSourceRepository,
	@Autowired private val calendars: CalendarRepository,
	@Autowired private val events: CalendarEventRepository,
	@Autowired private val eventIndexer: CalendarEventIndexer,
	@Autowired private val search: SearchService,
	@Autowired private val clock: MutableClock,
	@Autowired private val tx: TransactionTemplate,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private lateinit var calendar: Calendar
	private val resolver get() = registry.find(ItemType.EVENT)!!

	@BeforeEach
	fun reset() {
		jdbc.execute("delete from calendar_source")
		jdbc.execute("delete from search_document")
		clock.set(MutableClockTestConfiguration.START)
		val source = sources.save(CalendarSource(CalendarSourceType.LOCAL, "Cockpit"))
		calendar = calendars.save(Calendar(source.id, "Personal"))
	}

	private fun save(event: CalendarEvent): CalendarEvent =
		tx.execute {
			events.save(event).also { eventIndexer.index(it) }
		}

	private fun found(query: String) = search.search(query, emptySet(), 10).flatMap { group -> group.results.map { it.hit.item.id } }

	private fun indexedIds() = jdbc.queryForList("select item_id from search_document where item_type = 'EVENT'", UUID::class.java)

	private fun timed(
		title: String,
		start: String,
		zone: String,
	) = CalendarEvent(calendar.id, title).apply { setTimed(Instant.parse(start), Instant.parse(start).plusSeconds(3600), ZoneId.of(zone)) }

	private fun weeklySeries(title: String = "Choir rehearsal") =
		save(
			timed(title, "2026-10-21T17:00:00Z", "Europe/Zurich").apply { rrule = "FREQ=WEEKLY" },
		)

	private fun overrideOf(
		master: CalendarEvent,
		title: String,
		occurrence: String,
		cancelled: Boolean = false,
	) = save(
		timed(title, occurrence, "Europe/Zurich").apply {
			overrideOf(master, OccurrenceId.of(Instant.parse(occurrence)))
			if (cancelled) status = EventStatus.CANCELLED
		},
	)

	@Test
	fun `the subtitle uses the event's own zone and its daylight saving offset`() {
		val summer = save(timed("Summer call", "2026-07-14T12:00:00Z", "Europe/Zurich"))
		val winter = save(timed("Winter call", "2026-12-15T12:00:00Z", "Europe/Zurich"))
		val newYork = save(timed("New York call", "2026-10-20T12:00:00Z", "America/New_York"))

		resolver.resolve(summer.id)!!.subtitle shouldBe "Tue 14 Jul 2026, 14:00"
		resolver.resolve(winter.id)!!.subtitle shouldBe "Tue 15 Dec 2026, 13:00"
		resolver.resolve(newYork.id)!!.subtitle shouldBe "Tue 20 Oct 2026, 08:00"
	}

	@Test
	fun `the subtitle shows the local date when the zone moves the start past midnight`() {
		val late = save(timed("Night train", "2026-10-20T23:30:00Z", "Europe/Zurich"))

		resolver.resolve(late.id)!!.subtitle shouldBe "Wed 21 Oct 2026, 01:30"
	}

	@Test
	fun `a timed series is marked as repeating and an override of an active series resolves without it`() {
		val master = weeklySeries()
		val moved = overrideOf(master, "Choir rehearsal (church)", "2026-10-28T17:00:00Z")

		resolver.resolve(master.id)!!.subtitle shouldBe "Wed 21 Oct 2026, 19:00 (repeats)"
		val resolved = resolver.resolve(moved.id)!!
		resolved.title shouldBe "Choir rehearsal (church)"
		resolved.subtitle shouldBe "Wed 28 Oct 2026, 18:00"
		resolved.deepLink shouldBe "/calendar/events/${moved.id}"
		resolved.icon shouldBe "event"
	}

	@Test
	fun `a cancelled override does not resolve`() {
		// Recorded assumption (tasks/cal-model.md): cancelled overrides are neither indexed nor resolved.
		val master = weeklySeries()
		val cancelled = overrideOf(master, "Choir rehearsal", "2026-11-04T17:00:00Z", cancelled = true)

		resolver.resolve(cancelled.id).shouldBeNull()
		resolver.exists(cancelled.id) shouldBe false
	}

	@Test
	fun `a cancelled single event still resolves and is indexed`() {
		val event = save(timed("Concert", "2026-10-30T19:00:00Z", "Europe/Zurich").apply { status = EventStatus.CANCELLED })

		resolver.exists(event.id) shouldBe true
		indexedIds() shouldContainExactlyInAnyOrder listOf(event.id)
	}

	@Test
	fun `a soft-deleted event resolves again after it is restored`() {
		var event = save(CalendarEvent(calendar.id, "Dentist").apply { setAllDay(LocalDate.of(2026, 11, 5)) })
		event.softDelete(clock)
		event = save(event)
		resolver.exists(event.id) shouldBe false

		event.restore()
		event = save(event)
		resolver.exists(event.id) shouldBe true
		found("dentist") shouldBe listOf(event.id)
	}

	@Test
	fun `indexing an override on its own while its series is deleted removes it from search`() {
		val master = weeklySeries()
		val moved = overrideOf(master, "Choir rehearsal (church)", "2026-10-28T17:00:00Z")
		// The master is deleted without reindexing its overrides (e.g. a direct repository write).
		tx.execute {
			val loaded = events.findActiveById(master.id)!!
			loaded.softDelete(clock)
			events.save(loaded)
		}
		indexedIds() shouldContainExactlyInAnyOrder listOf(master.id, moved.id)

		tx.execute { eventIndexer.index(events.findById(moved.id).get()) }

		indexedIds() shouldContainExactlyInAnyOrder listOf(master.id)
	}

	@Test
	fun `un-cancelling an override puts it back into search`() {
		val master = weeklySeries()
		val cancelled = overrideOf(master, "Choir rehearsal (cancelled)", "2026-11-04T17:00:00Z", cancelled = true)
		indexedIds() shouldContainExactlyInAnyOrder listOf(master.id)

		cancelled.status = EventStatus.CONFIRMED
		save(cancelled)

		indexedIds() shouldContainExactlyInAnyOrder listOf(master.id, cancelled.id)
	}

	@Test
	fun `cancelling an indexed override removes it from search`() {
		val master = weeklySeries()
		val moved = overrideOf(master, "Choir rehearsal (church)", "2026-10-28T17:00:00Z")

		moved.status = EventStatus.CANCELLED
		save(moved)

		indexedIds() shouldContainExactlyInAnyOrder listOf(master.id)
	}

	@Test
	fun `an event with only a location or no details is indexed by what it has`() {
		val withLocation =
			save(CalendarEvent(calendar.id, "Lunch").apply { location = "Bellevue" }.apply { setAllDay(LocalDate.of(2026, 11, 2)) })
		val bare = save(CalendarEvent(calendar.id, "Haircut").apply { setAllDay(LocalDate.of(2026, 11, 3)) })

		found("Bellevue") shouldBe listOf(withLocation.id)
		found("haircut") shouldBe listOf(bare.id)
		found("nonexistentword").shouldBeEmpty()
	}

	@Test
	fun `unindexing a calendar also removes series overrides, and an unknown calendar is a no-op`() {
		val master = weeklySeries()
		val moved = overrideOf(master, "Choir rehearsal (church)", "2026-10-28T17:00:00Z")
		indexedIds() shouldContainExactlyInAnyOrder listOf(master.id, moved.id)

		eventIndexer.unindexCalendar(calendar.id)
		eventIndexer.unindexCalendar(UUID.randomUUID())

		indexedIds().shouldBeEmpty()
	}
}
