package ch.sonensei.cockpit.calendar.model

import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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

@SpringBootTest
@Import(MutableClockTestConfiguration::class)
class CalendarModelTests(
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

	@Test
	fun `a source keeps its config and encrypts its credentials`() {
		val saved =
			sources.save(
				CalendarSource(CalendarSourceType.CALDAV, "Nextcloud").apply {
					config = """{"url": "https://cloud.example.test/remote.php/dav"}"""
					credentials = EncryptedSecret("fictitious-app-password")
				},
			)

		val loaded = sources.findById(saved.id).get()
		loaded.type shouldBe CalendarSourceType.CALDAV
		loaded.config shouldBe """{"url": "https://cloud.example.test/remote.php/dav"}"""
		loaded.credentials!!.reveal() shouldBe "fictitious-app-password"
		jdbc.queryForObject("select credentials from calendar_source", String::class.java)!! shouldNotContain "fictitious"
	}

	@Test
	fun `the database rejects an unknown source type and a non-object config`() {
		shouldThrow<DataIntegrityViolationException> {
			jdbc.update(
				"insert into calendar_source (id, created_at, updated_at, version, type, name) values (gen_random_uuid(), now(), now(), 0, 'EXCHANGE', 'x')",
			)
		}
		shouldThrow<DataIntegrityViolationException> {
			jdbc.update(
				"insert into calendar_source (id, created_at, updated_at, version, type, name, config) values (gen_random_uuid(), now(), now(), 0, 'LOCAL', 'x', '[]')",
			)
		}
	}

	@Test
	fun `a calendar keeps its flags and only one calendar is the default`() {
		val source = sources.save(CalendarSource(CalendarSourceType.GOOGLE, "Work account"))
		val work =
			calendars.save(
				Calendar(source.id, "Work").apply {
					color = "#3366ff"
					visible = false
					readOnly = true
					isDefault = true
					externalId = "work@group.calendar.example.test"
					externalEtag = "\"42\""
				},
			)

		val loaded = calendars.findById(work.id).get()
		loaded.color shouldBe "#3366ff"
		loaded.visible shouldBe false
		loaded.readOnly shouldBe true
		loaded.isDefault shouldBe true
		calendars.findBySourceIdAndExternalId(source.id, "work@group.calendar.example.test")!!.id shouldBe work.id
		calendars.findByIsDefaultTrue()!!.id shouldBe work.id

		shouldThrow<DataIntegrityViolationException> { calendars.saveAndFlush(Calendar(source.id, "Other").apply { isDefault = true }) }
	}

	@Test
	fun `a timed event with every field set is saved and loaded unchanged`() {
		val calendar = localCalendar()
		val start = Instant.parse("2026-10-20T12:00:00Z")
		val saved =
			events.save(
				CalendarEvent(calendar.id, "Team sync").apply {
					description = "Weekly planning"
					location = "Room 3"
					setTimed(start, start.plusSeconds(1800), zurich)
					rrule = "FREQ=WEEKLY;BYDAY=TU"
					exdates = mutableSetOf(OccurrenceId.of(start.plusSeconds(7 * 86400)))
					attendees = listOf(Attendee("ada@example.test", "Ada", AttendeeStatus.ACCEPTED), Attendee("bob@example.test"))
					reminderMinutes = listOf(10, 60)
					status = EventStatus.TENTATIVE
					transparency = EventTransparency.TRANSPARENT
					conferenceUrl = "https://meet.example.test/abc"
					externalId = "evt-1"
					externalEtag = "\"7\""
					syncState = EventSyncState.SYNCED
				},
			)

		tx.execute {
			val loaded = events.findActiveById(saved.id)!!
			loaded.title shouldBe "Team sync"
			loaded.description shouldBe "Weekly planning"
			loaded.location shouldBe "Room 3"
			loaded.allDay shouldBe false
			loaded.startAt shouldBe start
			loaded.endAt shouldBe start.plusSeconds(1800)
			loaded.timeZone shouldBe "Europe/Zurich"
			loaded.startDate.shouldBeNull()
			loaded.rrule shouldBe "FREQ=WEEKLY;BYDAY=TU"
			loaded.isRecurring shouldBe true
			loaded.exdates shouldContainExactly setOf(OccurrenceId.of(start.plusSeconds(7 * 86400)))
			loaded.attendees shouldContainExactly
				listOf(Attendee("ada@example.test", "Ada", AttendeeStatus.ACCEPTED), Attendee("bob@example.test", null, AttendeeStatus.NEEDS_ACTION))
			loaded.reminderMinutes shouldContainExactly listOf(10, 60)
			loaded.status shouldBe EventStatus.TENTATIVE
			loaded.transparency shouldBe EventTransparency.TRANSPARENT
			loaded.conferenceUrl shouldBe "https://meet.example.test/abc"
			loaded.externalId shouldBe "evt-1"
			loaded.externalEtag shouldBe "\"7\""
			loaded.syncState shouldBe EventSyncState.SYNCED
			loaded.recurrenceId.shouldBeNull()
			loaded.createdAt shouldBe MutableClockTestConfiguration.START
		}
		events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(calendar.id, "evt-1")!!.id shouldBe saved.id
	}

	@Test
	fun `an all-day event stores dates without instants or zone`() {
		val calendar = localCalendar()
		val saved =
			events.save(
				CalendarEvent(calendar.id, "Holiday").apply {
					setTimed(Instant.parse("2026-12-24T08:00:00Z"), Instant.parse("2026-12-24T09:00:00Z"), zurich)
					setAllDay(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 27))
				},
			)

		val loaded = events.findActiveById(saved.id)!!
		loaded.allDay shouldBe true
		loaded.startDate shouldBe LocalDate.of(2026, 12, 24)
		loaded.endDate shouldBe LocalDate.of(2026, 12, 27)
		loaded.startAt.shouldBeNull()
		loaded.timeZone.shouldBeNull()
		loaded.syncState shouldBe EventSyncState.LOCAL
		loaded.attendees shouldBe emptyList()
		loaded.reminderMinutes shouldBe emptyList()
	}

	@Test
	fun `invalid times are rejected by the entity and the database`() {
		val event = CalendarEvent(localCalendar().id, "Broken")
		val start = Instant.parse("2026-10-20T12:00:00Z")
		shouldThrow<IllegalArgumentException> { event.setTimed(start, start.minusSeconds(1), zurich) }
		shouldThrow<IllegalArgumentException> { event.setAllDay(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 2)) }
		// No times at all.
		shouldThrow<DataIntegrityViolationException> { events.saveAndFlush(event) }
	}

	@Test
	fun `an override references its series and the occurrence it replaces, once per occurrence`() {
		val calendar = localCalendar()
		val start = Instant.parse("2026-10-20T12:00:00Z")
		val master =
			events.save(
				CalendarEvent(calendar.id, "Standup").apply {
					setTimed(start, start.plusSeconds(900), zurich)
					rrule = "FREQ=DAILY;COUNT=5"
				},
			)
		val occurrence = OccurrenceId.of(start.plusSeconds(86400))
		val moved =
			events.save(
				CalendarEvent(calendar.id, "Standup (moved)").apply {
					setTimed(start.plusSeconds(86400 + 3600), start.plusSeconds(86400 + 4500), zurich)
					overrideOf(master, occurrence)
				},
			)

		val loaded = events.findActiveById(moved.id)!!
		loaded.parentEventId shouldBe master.id
		loaded.recurrenceId shouldBe occurrence
		loaded.isOverride shouldBe true
		events.findByParentEventIdOrderByIdAsc(master.id).map { it.id } shouldContainExactly listOf(moved.id)

		shouldThrow<DataIntegrityViolationException> {
			events.saveAndFlush(
				CalendarEvent(calendar.id, "Duplicate").apply {
					setTimed(start, start, zurich)
					overrideOf(master, occurrence)
				},
			)
		}
	}

	@Test
	fun `only occurrences of a series master can be overridden`() {
		val calendar = localCalendar()
		val single = CalendarEvent(calendar.id, "Once").apply { setAllDay(LocalDate.of(2026, 1, 1)) }
		shouldThrow<IllegalArgumentException> { CalendarEvent(calendar.id, "x").overrideOf(single, OccurrenceId.of(LocalDate.of(2026, 1, 1))) }
		shouldThrow<IllegalArgumentException> { OccurrenceId(null, null) }
		shouldThrow<IllegalArgumentException> { OccurrenceId(Instant.EPOCH, LocalDate.EPOCH) }
	}

	@Test
	fun `deleting a source removes its calendars, events, overrides and exdates`() {
		val calendar = localCalendar()
		val master =
			events.save(
				CalendarEvent(calendar.id, "Gym").apply {
					setAllDay(LocalDate.of(2026, 10, 19))
					rrule = "FREQ=WEEKLY"
					exdates = mutableSetOf(OccurrenceId.of(LocalDate.of(2026, 10, 26)))
				},
			)
		events.save(
			CalendarEvent(calendar.id, "Gym").apply {
				setAllDay(LocalDate.of(2026, 11, 3))
				overrideOf(master, OccurrenceId.of(LocalDate.of(2026, 11, 2)))
			},
		)

		sources.deleteById(calendar.sourceId)

		jdbc.queryForObject("select count(*) from calendar", Int::class.java) shouldBe 0
		jdbc.queryForObject("select count(*) from calendar_event", Int::class.java) shouldBe 0
		jdbc.queryForObject("select count(*) from calendar_event_exdate", Int::class.java) shouldBe 0
	}

	@Test
	fun `range candidates are the overlapping single events, earlier series and overrides`() {
		val calendar = localCalendar()
		val other = localCalendar("Other")
		val from = Instant.parse("2026-10-19T00:00:00Z")
		val to = Instant.parse("2026-10-26T00:00:00Z")

		fun timed(
			title: String,
			start: String,
			end: String,
			calendarId: java.util.UUID = calendar.id,
		) = events.save(CalendarEvent(calendarId, title).apply { setTimed(Instant.parse(start), Instant.parse(end), zurich) })

		val inside = timed("inside", "2026-10-20T10:00:00Z", "2026-10-20T11:00:00Z")
		val overlapsStart = timed("overlaps start", "2026-10-18T23:00:00Z", "2026-10-19T01:00:00Z")
		val zeroLength = timed("zero length", "2026-10-19T00:00:00Z", "2026-10-19T00:00:00Z")
		timed("ends at from", "2026-10-18T23:00:00Z", "2026-10-19T00:00:00Z")
		timed("starts at to", "2026-10-26T00:00:00Z", "2026-10-26T01:00:00Z")
		timed("other calendar", "2026-10-20T10:00:00Z", "2026-10-20T11:00:00Z", other.id)
		val series =
			events.save(
				CalendarEvent(calendar.id, "old series").apply {
					setTimed(Instant.parse("2025-01-01T10:00:00Z"), Instant.parse("2025-01-01T11:00:00Z"), zurich)
					rrule = "FREQ=WEEKLY"
				},
			)
		val override =
			events.save(
				CalendarEvent(calendar.id, "moved far away").apply {
					setTimed(Instant.parse("2027-01-01T10:00:00Z"), Instant.parse("2027-01-01T11:00:00Z"), zurich)
					overrideOf(series, OccurrenceId.of(Instant.parse("2025-01-08T10:00:00Z")))
				},
			)
		val allDay = events.save(CalendarEvent(calendar.id, "all day").apply { setAllDay(LocalDate.of(2026, 10, 25)) })
		events.save(CalendarEvent(calendar.id, "all day after").apply { setAllDay(LocalDate.of(2026, 10, 26)) })
		events.save(CalendarEvent(calendar.id, "all day before").apply { setAllDay(LocalDate.of(2026, 10, 18)) })
		events.save(
			CalendarEvent(calendar.id, "deleted").apply {
				setTimed(Instant.parse("2026-10-20T10:00:00Z"), Instant.parse("2026-10-20T11:00:00Z"), zurich)
				softDelete(clock)
			},
		)

		events
			.findCandidatesInRange(listOf(calendar.id), from, to, LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26))
			.map { it.title } shouldContainExactlyInAnyOrder
			listOf(inside, overlapsStart, zeroLength, series, override, allDay).map { it.title }
	}

	@Test
	fun `soft-deleted events stay out of active reads`() {
		val calendar = localCalendar()
		val event = events.save(CalendarEvent(calendar.id, "Dentist").apply { setAllDay(LocalDate.of(2026, 11, 5)) })
		event.softDelete(clock)
		events.save(event)

		events.findActiveById(event.id).shouldBeNull()
		events.findAllDeleted().map { it.id } shouldContainExactly listOf(event.id)
		events
			.findById(event.id)
			.get()
			.deletedAt
			.shouldNotBeNull()
		events.findIdsByCalendarId(calendar.id) shouldContainExactly listOf(event.id)
	}
}
