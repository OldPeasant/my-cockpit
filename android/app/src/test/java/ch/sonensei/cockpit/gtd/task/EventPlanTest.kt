package ch.sonensei.cockpit.gtd.task

import ch.sonensei.cockpit.core.network.generated.models.ConvertToEventRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** "To calendar" plans (SPEC.md §5.3): the request they send, their default start and calendar, and when they are complete. */
class EventPlanTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val zurich = ZoneId.of("Europe/Zurich")
    private val work = TargetCalendar(id(90), "Work")
    private val personal = TargetCalendar(id(91), "Personal", isDefault = true)
    private val shared = TargetCalendar(id(92), "Shared")

    @Test
    fun `a timed plan starts at its day and time in the given zone and ends after its duration`() {
        val plan = EventPlan(work.id, LocalDate.of(2026, 10, 24), LocalTime.of(23, 30), durationMin = 90)

        assertEquals(
            ConvertToEventRequest(
                allDay = false,
                calendarId = work.id,
                start = OffsetDateTime.parse("2026-10-24T23:30:00+02:00"),
                end = OffsetDateTime.parse("2026-10-25T01:00:00+02:00"),
                timeZone = "Europe/Zurich",
            ),
            plan.request(zurich),
        )
    }

    @Test
    fun `the same plan in another zone starts at the same wall-clock time there`() {
        val plan = EventPlan(work.id, LocalDate.of(2026, 7, 1), LocalTime.of(9, 0))

        val request = plan.request(ZoneId.of("America/New_York"))

        assertEquals(OffsetDateTime.parse("2026-07-01T09:00:00-04:00"), request.start)
        assertEquals(OffsetDateTime.parse("2026-07-01T10:00:00-04:00"), request.end)
        assertEquals("America/New_York", request.timeZone)
        assertNull(request.startDate)
        assertNull(request.endDate)
    }

    @Test
    fun `a timed event across the end of summer time lasts its duration, not its wall-clock span`() {
        // 2026-10-25 03:00 CEST becomes 02:00 CET in Zurich.
        val plan = EventPlan(work.id, LocalDate.of(2026, 10, 25), LocalTime.of(1, 30), durationMin = 120)

        val request = plan.request(zurich)

        assertEquals(Duration.ofMinutes(120), Duration.between(request.start, request.end))
        assertEquals(OffsetDateTime.parse("2026-10-25T02:30:00+01:00"), request.end)
    }

    @Test
    fun `an all-day plan sends its day with the exclusive next day as end and no times`() {
        val plan = EventPlan(personal.id, LocalDate.of(2026, 12, 31), LocalTime.of(15, 0), durationMin = 30, allDay = true)

        assertEquals(
            ConvertToEventRequest(
                allDay = true,
                calendarId = personal.id,
                startDate = LocalDate.of(2026, 12, 31),
                endDate = LocalDate.of(2027, 1, 1),
            ),
            plan.request(zurich),
        )
    }

    @Test
    fun `a plan needs a calendar and, unless all-day, a positive duration`() {
        val day = LocalDate.of(2026, 10, 20)
        assertTrue(EventPlan(work.id, day, LocalTime.NOON).isValid)
        assertFalse(EventPlan(null, day, LocalTime.NOON).isValid)
        assertFalse(EventPlan(null, day, LocalTime.NOON, allDay = true).isValid)
        assertFalse(EventPlan(work.id, day, LocalTime.NOON, durationMin = 0).isValid)
        assertTrue(EventPlan(work.id, day, LocalTime.NOON, durationMin = 0, allDay = true).isValid)
    }

    @Test
    fun `the default plan starts at the next full hour for one hour in the default calendar`() {
        val now = ZonedDateTime.of(2026, 10, 20, 10, 15, 42, 0, zurich)

        val plan = EventPlan.startingAfter(now, listOf(work, personal))

        assertEquals(EventPlan(personal.id, LocalDate.of(2026, 10, 20), LocalTime.of(11, 0), durationMin = 60, allDay = false), plan)
    }

    @Test
    fun `exactly on the hour the default start is the following hour`() {
        val plan = EventPlan.startingAfter(ZonedDateTime.of(2026, 10, 20, 10, 0, 0, 0, zurich))

        assertEquals(LocalTime.of(11, 0), plan.time)
        assertNull(plan.calendarId)
    }

    @Test
    fun `late in the evening the default start is midnight of the next day`() {
        val plan = EventPlan.startingAfter(ZonedDateTime.of(2026, 12, 31, 23, 40, 0, 0, zurich))

        assertEquals(LocalDate.of(2027, 1, 1), plan.date)
        assertEquals(LocalTime.MIDNIGHT, plan.time)
    }

    @Test
    fun `the default calendar is the one marked default, else the first, else none`() {
        assertEquals(personal.id, EventPlan.defaultCalendar(listOf(work, personal, shared)))
        assertEquals(work.id, EventPlan.defaultCalendar(listOf(work, shared)))
        assertNull(EventPlan.defaultCalendar(emptyList()))
    }

    @Test
    fun `a chosen calendar that is still offered is kept, one that is gone is replaced by the default`() {
        val plan = EventPlan(shared.id, LocalDate.of(2026, 10, 20), LocalTime.NOON, durationMin = 30)

        assertEquals(plan, plan.withCalendarOf(listOf(work, personal, shared)))
        assertEquals(plan.copy(calendarId = personal.id), plan.withCalendarOf(listOf(work, personal)))
        assertEquals(plan.copy(calendarId = null), plan.withCalendarOf(emptyList()))
        assertEquals(plan.copy(calendarId = work.id), plan.copy(calendarId = null).withCalendarOf(listOf(work)))
    }

    @Test
    fun `the offered durations include the default one`() {
        assertEquals(listOf(15, 30, 60, 90, 120, 180), EventPlan.DURATIONS_MIN)
        assertTrue(EventPlan.DEFAULT_DURATION_MIN in EventPlan.DURATIONS_MIN)
    }
}
