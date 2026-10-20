package ch.sonensei.cockpit.calendar.model

import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.search.domain.SearchService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

@SpringBootTest
@Import(MutableClockTestConfiguration::class)
class CalendarItemsAndSearchTests(
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
		}!!

	private fun found(query: String) = search.search(query, emptySet(), 10).flatMap { group -> group.results.map { it.hit.item.id } }

	private fun indexedIds() = jdbc.queryForList("select item_id from search_document where item_type = 'EVENT'", java.util.UUID::class.java)

	@Test
	fun `the EVENT resolver is registered`() {
		registry.find(ItemType.EVENT).shouldBeInstanceOf<EventItemResolver>()
	}

	@Test
	fun `a timed event resolves to its title, start in its zone and deep link`() {
		val event =
			save(
				CalendarEvent(calendar.id, "Dentist").apply {
					setTimed(Instant.parse("2026-10-20T12:00:00Z"), Instant.parse("2026-10-20T13:00:00Z"), ZoneId.of("Europe/Zurich"))
				},
			)

		registry.find(ItemType.EVENT)!!.resolve(event.id) shouldBe
			ResolvedItem("Dentist", "Tue 20 Oct 2026, 14:00", "event", "/calendar/events/${event.id}")
	}

	@Test
	fun `an all-day series resolves with all day and repeats`() {
		val event =
			save(
				CalendarEvent(calendar.id, "Birthday").apply {
					setAllDay(LocalDate.of(2026, 3, 1))
					rrule = "FREQ=YEARLY"
				},
			)

		registry.find(ItemType.EVENT)!!.resolve(event.id)!!.subtitle shouldBe "Sun 1 Mar 2026, all day (repeats)"
	}

	@Test
	fun `missing and deleted events, and overrides of deleted series, do not resolve`() {
		val resolver = registry.find(ItemType.EVENT)!!
		val master =
			save(
				CalendarEvent(calendar.id, "Yoga").apply {
					setAllDay(LocalDate.of(2026, 10, 1))
					rrule = "FREQ=WEEKLY"
				},
			)
		val override =
			save(
				CalendarEvent(calendar.id, "Yoga (outdoor)").apply {
					setAllDay(LocalDate.of(2026, 10, 9))
					overrideOf(master, OccurrenceId.of(LocalDate.of(2026, 10, 8)))
				},
			)
		resolver.exists(override.id) shouldBe true

		master.softDelete(clock)
		save(master)

		resolver.resolve(UuidV7.generate()).shouldBeNull()
		resolver.resolve(master.id).shouldBeNull()
		resolver.exists(override.id) shouldBe false
	}

	@Test
	fun `indexed events are found by title, description and location`() {
		val event =
			save(
				CalendarEvent(calendar.id, "Quarterly planning").apply {
					description = "Budget for the greenhouse"
					location = "Winterthur"
					setAllDay(LocalDate.of(2026, 11, 2))
				},
			)

		found("quarterly") shouldBe listOf(event.id)
		found("greenhouse") shouldBe listOf(event.id)
		found("Winterthur") shouldBe listOf(event.id)

		event.title = "Annual planning"
		save(event)
		found("quarterly").shouldBeEmpty()
		found("annual") shouldBe listOf(event.id)
	}

	@Test
	fun `deleted events, cancelled overrides and overrides of a deleted series are not indexed, restore reindexes`() {
		var master =
			save(
				CalendarEvent(calendar.id, "Choir rehearsal").apply {
					setTimed(Instant.parse("2026-10-21T17:00:00Z"), Instant.parse("2026-10-21T19:00:00Z"), ZoneId.of("Europe/Zurich"))
					rrule = "FREQ=WEEKLY"
				},
			)
		val moved =
			save(
				CalendarEvent(calendar.id, "Choir rehearsal (church)").apply {
					setTimed(Instant.parse("2026-10-29T17:00:00Z"), Instant.parse("2026-10-29T19:00:00Z"), ZoneId.of("Europe/Zurich"))
					overrideOf(master, OccurrenceId.of(Instant.parse("2026-10-28T17:00:00Z")))
				},
			)
		val cancelled: CalendarEvent =
			save(
				CalendarEvent(calendar.id, "Choir rehearsal").apply {
					setTimed(Instant.parse("2026-11-04T17:00:00Z"), Instant.parse("2026-11-04T19:00:00Z"), ZoneId.of("Europe/Zurich"))
					overrideOf(master, OccurrenceId.of(Instant.parse("2026-11-04T17:00:00Z")))
					status = EventStatus.CANCELLED
				},
			)
		indexedIds() shouldContainExactlyInAnyOrder listOf(master.id, moved.id)

		master.softDelete(clock)
		master = save(master)
		indexedIds().shouldBeEmpty()

		master.restore()
		master = save(master)
		indexedIds() shouldContainExactlyInAnyOrder listOf(master.id, moved.id)

		save(moved.also { it.softDelete(clock) })
		indexedIds() shouldContainExactlyInAnyOrder listOf(master.id)
	}

	@Test
	fun `unindexing a calendar removes the documents of all its events`() {
		save(CalendarEvent(calendar.id, "Event A").apply { setAllDay(LocalDate.of(2026, 1, 1)) })
		save(CalendarEvent(calendar.id, "Event B").apply { setAllDay(LocalDate.of(2026, 1, 2)) })
		val otherCalendar = calendars.save(Calendar(calendar.sourceId, "Other"))
		val kept = save(CalendarEvent(otherCalendar.id, "Event C").apply { setAllDay(LocalDate.of(2026, 1, 3)) })

		eventIndexer.unindexCalendar(calendar.id)

		indexedIds() shouldContainExactlyInAnyOrder listOf(kept.id)
	}
}
