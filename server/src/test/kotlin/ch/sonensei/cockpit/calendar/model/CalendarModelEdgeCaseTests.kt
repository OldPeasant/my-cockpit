package ch.sonensei.cockpit.calendar.model

import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Edge cases of the calendar schema constraints, entity invariants and the range-candidate query. */
@SpringBootTest
@Import(MutableClockTestConfiguration::class)
class CalendarModelEdgeCaseTests(
	@Autowired private val sources: CalendarSourceRepository,
	@Autowired private val calendars: CalendarRepository,
	@Autowired private val events: CalendarEventRepository,
	@Autowired private val tx: TransactionTemplate,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val clock: MutableClock,
) : PostgresTestBase() {
	private val zurich = ZoneId.of("Europe/Zurich")

	@BeforeEach
	fun reset() {
		jdbc.execute("delete from calendar_source")
		clock.set(MutableClockTestConfiguration.START)
	}

	private fun localCalendar(name: String = "Personal"): Calendar {
		val source = sources.save(CalendarSource(CalendarSourceType.LOCAL, "Cockpit"))
		return calendars.save(Calendar(source.id, name))
	}

	/** Inserts a valid timed single event row, with [overrides] replacing column values (SQL literals). */
	private fun insertEvent(
		calendarId: UUID,
		overrides: Map<String, String> = emptyMap(),
	): UUID {
		val id = UUID.randomUUID()
		val columns =
			linkedMapOf(
				"id" to "'$id'",
				"created_at" to "now()",
				"updated_at" to "now()",
				"version" to "0",
				"calendar_id" to "'$calendarId'",
				"title" to "'Raw event'",
				"all_day" to "false",
				"start_at" to "'2026-10-20T12:00:00Z'",
				"end_at" to "'2026-10-20T13:00:00Z'",
				"time_zone" to "'Europe/Zurich'",
				"status" to "'CONFIRMED'",
				"transparency" to "'OPAQUE'",
				"sync_state" to "'LOCAL'",
			)
		columns.putAll(overrides)
		jdbc.update("insert into calendar_event (${columns.keys.joinToString()}) values (${columns.values.joinToString()})")
		return id
	}

	@Test
	fun `every source type of SPEC 6_1 is accepted by the database`() {
		CalendarSourceType.entries.forEach { sources.saveAndFlush(CalendarSource(it, "Source ${it.name}")) }

		sources.findAllByOrderByIdAsc().map { it.type } shouldContainExactly CalendarSourceType.entries
		jdbc.queryForObject("select count(*) from calendar_source where credentials is null", Int::class.java) shouldBe 5
	}

	@Test
	fun `calendars are listed per source and external ids are unique per source only`() {
		val google = sources.save(CalendarSource(CalendarSourceType.GOOGLE, "Google account"))
		val other = sources.save(CalendarSource(CalendarSourceType.GOOGLE, "Second account"))
		val first = calendars.save(Calendar(google.id, "Work").apply { externalId = "shared-id" })
		val second = calendars.save(Calendar(google.id, "Family"))
		calendars.save(Calendar(other.id, "Work too").apply { externalId = "shared-id" })

		calendars.findBySourceIdOrderByIdAsc(google.id).map { it.id } shouldContainExactly listOf(first.id, second.id)
		calendars.findByIsDefaultTrue().shouldBeNull()
		shouldThrow<DataIntegrityViolationException> {
			calendars.saveAndFlush(Calendar(google.id, "Duplicate").apply { externalId = "shared-id" })
		}
	}

	@Test
	fun `a new calendar is visible, writable and not the default`() {
		val calendar = localCalendar()
		val loaded = calendars.findById(calendar.id).get()
		loaded.visible shouldBe true
		loaded.readOnly shouldBe false
		loaded.isDefault shouldBe false
		loaded.color.shouldBeNull()
	}

	@Test
	fun `the database rejects unknown status, transparency and sync state values and non-array attendees`() {
		val calendar = localCalendar()
		insertEvent(calendar.id) // the baseline row is valid

		listOf(
			mapOf("status" to "'MAYBE'"),
			mapOf("transparency" to "'BUSY'"),
			mapOf("sync_state" to "'CONFLICT'"),
			mapOf("attendees" to "'{\"email\": \"ada@example.test\"}'::jsonb"),
		).forEach { bad ->
			shouldThrow<DataIntegrityViolationException> { insertEvent(calendar.id, bad) }
		}
	}

	@Test
	fun `the database rejects mixed timed and all-day columns`() {
		val calendar = localCalendar()
		listOf(
			// Timed without a zone.
			mapOf("time_zone" to "null"),
			// Timed that ends before it starts.
			mapOf("end_at" to "'2026-10-20T11:00:00Z'"),
			// Timed with dates as well.
			mapOf("start_date" to "'2026-10-20'", "end_date" to "'2026-10-21'"),
			// All-day with instants as well.
			mapOf("all_day" to "true", "start_date" to "'2026-10-20'", "end_date" to "'2026-10-21'"),
			// All-day whose exclusive end is its start.
			mapOf(
				"all_day" to "true",
				"start_at" to "null",
				"end_at" to "null",
				"time_zone" to "null",
				"start_date" to "'2026-10-20'",
				"end_date" to "'2026-10-20'",
			),
		).forEach { bad ->
			shouldThrow<DataIntegrityViolationException> { insertEvent(calendar.id, bad) }
		}
		// A one-day all-day event is fine.
		insertEvent(
			calendar.id,
			mapOf(
				"all_day" to "true",
				"start_at" to "null",
				"end_at" to "null",
				"time_zone" to "null",
				"start_date" to "'2026-10-20'",
				"end_date" to "'2026-10-21'",
			),
		)
	}

	@Test
	fun `the database rejects inconsistent override columns`() {
		val calendar = localCalendar()
		val master = insertEvent(calendar.id, mapOf("rrule" to "'FREQ=DAILY'"))
		listOf(
			// Recurrence id without a parent.
			mapOf("recurrence_id_at" to "'2026-10-21T12:00:00Z'"),
			// Parent without a recurrence id.
			mapOf("parent_event_id" to "'$master'"),
			// Both recurrence id forms.
			mapOf("parent_event_id" to "'$master'", "recurrence_id_at" to "'2026-10-21T12:00:00Z'", "recurrence_id_date" to "'2026-10-21'"),
			// An override with its own rrule.
			mapOf("parent_event_id" to "'$master'", "recurrence_id_at" to "'2026-10-21T12:00:00Z'", "rrule" to "'FREQ=DAILY'"),
		).forEach { bad ->
			shouldThrow<DataIntegrityViolationException> { insertEvent(calendar.id, bad) }
		}
	}

	@Test
	fun `event external ids are unique per calendar only`() {
		val calendar = localCalendar()
		val other = localCalendar("Other")
		events.saveAndFlush(CalendarEvent(calendar.id, "A").apply { setAllDay(LocalDate.of(2026, 1, 1)) }.apply { externalId = "ext-1" })
		events.saveAndFlush(CalendarEvent(other.id, "B").apply { setAllDay(LocalDate.of(2026, 1, 1)) }.apply { externalId = "ext-1" })

		shouldThrow<DataIntegrityViolationException> {
			events.saveAndFlush(CalendarEvent(calendar.id, "C").apply { setAllDay(LocalDate.of(2026, 1, 2)) }.apply { externalId = "ext-1" })
		}
		events.findByCalendarIdAndExternalId(other.id, "ext-1")!!.title shouldBe "B"
		events.findByCalendarIdAndExternalId(calendar.id, "missing").shouldBeNull()
	}

	@Test
	fun `exdates hold exactly one form and are unique per event`() {
		val calendar = localCalendar()
		val master = insertEvent(calendar.id, mapOf("rrule" to "'FREQ=DAILY'"))
		jdbc.update("insert into calendar_event_exdate (event_id, occurrence_at) values (?::uuid, '2026-10-21T12:00:00Z')", master.toString())

		shouldThrow<DataIntegrityViolationException> {
			jdbc.update("insert into calendar_event_exdate (event_id, occurrence_at) values (?::uuid, '2026-10-21T12:00:00Z')", master.toString())
		}
		shouldThrow<DataIntegrityViolationException> {
			jdbc.update("insert into calendar_event_exdate (event_id) values (?::uuid)", master.toString())
		}
		shouldThrow<DataIntegrityViolationException> {
			jdbc.update(
				"insert into calendar_event_exdate (event_id, occurrence_at, occurrence_date) values (?::uuid, '2026-10-22T12:00:00Z', '2026-10-22')",
				master.toString(),
			)
		}
	}

	@Test
	fun `exdates can be added and removed on a saved series`() {
		val calendar = localCalendar()
		val first = LocalDate.of(2026, 10, 26)
		val second = LocalDate.of(2026, 11, 2)
		val master =
			events.save(
				CalendarEvent(calendar.id, "Gym").apply {
					setAllDay(LocalDate.of(2026, 10, 19))
					rrule = "FREQ=WEEKLY"
					exdates = mutableSetOf(OccurrenceId.of(first))
				},
			)

		tx.execute {
			val loaded = events.findActiveById(master.id)!!
			loaded.exdates.remove(OccurrenceId.of(first))
			loaded.exdates.add(OccurrenceId.of(second))
		}

		tx.execute { events.findActiveById(master.id)!!.exdates shouldContainExactly setOf(OccurrenceId.of(second)) }
	}

	@Test
	fun `an all-day override stores its recurrence id as a date`() {
		val calendar = localCalendar()
		val master =
			events.save(
				CalendarEvent(calendar.id, "Market").apply {
					setAllDay(LocalDate.of(2026, 10, 3))
					rrule = "FREQ=WEEKLY;BYDAY=SA"
				},
			)
		val override =
			events.save(
				CalendarEvent(calendar.id, "Market (Sunday)").apply {
					setAllDay(LocalDate.of(2026, 10, 11))
					overrideOf(master, OccurrenceId.of(LocalDate.of(2026, 10, 10)))
				},
			)

		val loaded = events.findActiveById(override.id)!!
		loaded.recurrenceId shouldBe OccurrenceId.of(LocalDate.of(2026, 10, 10))
		loaded.isRecurring shouldBe false
		jdbc.queryForObject("select recurrence_id_at is null from calendar_event where id = ?", Boolean::class.java, override.id) shouldBe true
	}

	@Test
	fun `an event overrides at most one occurrence and an override cannot be overridden`() {
		val calendar = localCalendar()
		val start = Instant.parse("2026-10-20T07:00:00Z")
		val master =
			events.save(
				CalendarEvent(calendar.id, "Standup").apply {
					setTimed(start, start.plusSeconds(900), zurich)
					rrule = "FREQ=DAILY"
				},
			)
		val override =
			CalendarEvent(calendar.id, "Standup (late)").apply {
				setTimed(start.plusSeconds(86400 + 600), start.plusSeconds(86400 + 1500), zurich)
				overrideOf(master, OccurrenceId.of(start.plusSeconds(86400)))
			}

		shouldThrow<IllegalStateException> { override.overrideOf(master, OccurrenceId.of(start.plusSeconds(2 * 86400))) }
		events.save(override)
		shouldThrow<IllegalArgumentException> {
			CalendarEvent(calendar.id, "x").overrideOf(override, OccurrenceId.of(start.plusSeconds(86400)))
		}
	}

	@Test
	fun `overrideOf clears an rrule set before`() {
		val calendar = localCalendar()
		val master =
			events.save(
				CalendarEvent(calendar.id, "Standup").apply {
					setAllDay(LocalDate.of(2026, 10, 20))
					rrule = "FREQ=DAILY"
				},
			)
		val override =
			CalendarEvent(calendar.id, "Copy").apply {
				setAllDay(LocalDate.of(2026, 10, 22))
				rrule = "FREQ=DAILY"
				overrideOf(master, OccurrenceId.of(LocalDate.of(2026, 10, 21)))
			}
		override.rrule.shouldBeNull()
		events.saveAndFlush(override)
	}

	@Test
	fun `switching an event between timed and all-day clears the other form, and setAllDay defaults to one day`() {
		val event = CalendarEvent(UUID.randomUUID(), "Switch")
		event.setAllDay(LocalDate.of(2026, 2, 28))
		event.endDate shouldBe LocalDate.of(2026, 3, 1)

		event.setTimed(Instant.parse("2026-02-28T09:00:00Z"), Instant.parse("2026-02-28T09:00:00Z"), zurich)
		event.allDay shouldBe false
		event.startDate.shouldBeNull()
		event.endDate.shouldBeNull()
		event.timeZone shouldBe "Europe/Zurich"
	}

	@Test
	fun `range candidates exclude series and zero-length events at or after the end, and include long spans`() {
		val calendar = localCalendar()
		val second = localCalendar("Second")
		val from = Instant.parse("2026-10-19T00:00:00Z")
		val to = Instant.parse("2026-10-26T00:00:00Z")
		val fromDate = LocalDate.of(2026, 10, 19)
		val toDate = LocalDate.of(2026, 10, 26)

		fun timed(
			title: String,
			start: String,
			end: String,
			calendarId: UUID = calendar.id,
			rrule: String? = null,
		) = events.save(
			CalendarEvent(calendarId, title).apply {
				setTimed(Instant.parse(start), Instant.parse(end), zurich)
				this.rrule = rrule
			},
		)

		fun allDay(
			title: String,
			start: LocalDate,
			end: LocalDate = start.plusDays(1),
			rrule: String? = null,
		) = events.save(
			CalendarEvent(calendar.id, title).apply {
				setAllDay(start, end)
				this.rrule = rrule
			},
		)

		val spanning = timed("spans the range", "2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z")
		val inSecondCalendar = timed("second calendar", "2026-10-21T10:00:00Z", "2026-10-21T11:00:00Z", second.id)
		timed("zero length at to", "2026-10-26T00:00:00Z", "2026-10-26T00:00:00Z")
		timed("zero length before from", "2026-10-18T23:59:59Z", "2026-10-18T23:59:59Z")
		timed("series starting at to", "2026-10-26T00:00:00Z", "2026-10-26T01:00:00Z", rrule = "FREQ=DAILY")
		val seriesInside = timed("series starting inside", "2026-10-25T23:00:00Z", "2026-10-26T01:00:00Z", rrule = "FREQ=DAILY")
		val allDaySpan = allDay("all-day span", LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 20))
		allDay("all-day ends at fromDate", LocalDate.of(2026, 10, 17), fromDate)
		allDay("all-day series starting at toDate", toDate, rrule = "FREQ=WEEKLY")
		val allDaySeries = allDay("all-day series from last year", LocalDate.of(2025, 5, 1), rrule = "FREQ=YEARLY")
		val master = timed("master", "2026-11-10T10:00:00Z", "2026-11-10T11:00:00Z", rrule = "FREQ=DAILY")
		val deletedOverride =
			events.save(
				CalendarEvent(calendar.id, "deleted override").apply {
					setTimed(Instant.parse("2026-10-20T10:00:00Z"), Instant.parse("2026-10-20T11:00:00Z"), zurich)
					overrideOf(master, OccurrenceId.of(Instant.parse("2026-11-11T10:00:00Z")))
					softDelete(clock)
				},
			)

		val found =
			events.findCandidatesInRange(listOf(calendar.id, second.id), from, to, fromDate, toDate).map { it.title }

		found shouldContainExactlyInAnyOrder
			listOf(spanning, inSecondCalendar, seriesInside, allDaySpan, allDaySeries).map { it.title }
		(deletedOverride.title in found) shouldBe false
		events.findCandidatesInRange(listOf(UUID.randomUUID()), from, to, fromDate, toDate) shouldBe emptyList()
	}
}
