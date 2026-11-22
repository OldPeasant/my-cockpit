package ch.sonensei.cockpit.calendar.edit

import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** A new event from a long press: from the pressed time or all day, in the calendar the add button would pick. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventEditorViewModelCreateAtTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<EventEditRepository>()
    private val zone = ZoneId.of("Europe/Zurich")

    // Wednesday 21 October 2026, 10:20 in Zurich.
    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:20:00Z"), zone)
    private val now = clock.instant().atZone(zone)
    private val today = LocalDate.parse("2026-10-21")
    private val friday = LocalDate.parse("2026-10-23")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val feed = CalendarInfo(id(89), "Holidays", null, true, true, 1)
    private val hidden = CalendarInfo(id(88), "Private", null, false, false, 1)
    private val work = CalendarInfo(id(90), "Work", null, true, false, 3)
    private val home = CalendarInfo(id(91), "Home", null, true, false, 2)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `newForm without a start time or all day is unchanged`() {
        val todays = newForm(today, now, work.id)
        assertFalse(todays.allDay)
        assertEquals(today to LocalTime.of(11, 0), todays.startDate to todays.startTime)
        assertEquals(today to LocalTime.of(12, 0), todays.endDate to todays.endTime)
        val fridays = newForm(friday, now, work.id)
        assertEquals(friday to LocalTime.of(9, 0), fridays.startDate to fridays.startTime)
        assertEquals(friday to LocalTime.of(10, 0), fridays.endDate to fridays.endTime)
        assertEquals(fridays, newForm(friday, now, work.id, null, false))
    }

    @Test
    fun `newForm with a start time starts there for an hour, also today and also before now`() {
        val pressed = newForm(friday, now, work.id, LocalTime.of(14, 45))
        assertFalse(pressed.allDay)
        assertEquals(friday to LocalTime.of(14, 45), pressed.startDate to pressed.startTime)
        assertEquals(friday to LocalTime.of(15, 45), pressed.endDate to pressed.endTime)
        assertEquals(work.id, pressed.calendarId)
        assertEquals(zone, pressed.zone)

        // Today the pressed time wins over the next full hour, even when it has passed.
        val earlier = newForm(today, now, work.id, LocalTime.of(7, 15))
        assertEquals(today to LocalTime.of(7, 15), earlier.startDate to earlier.startTime)
        assertEquals(LocalTime.of(8, 15), earlier.endTime)
    }

    @Test
    fun `newForm from the last quarter of the day ends the next morning`() {
        val late = newForm(friday, now, work.id, LocalTime.of(23, 45))
        assertEquals(friday to LocalTime.of(23, 45), late.startDate to late.startTime)
        assertEquals(friday.plusDays(1) to LocalTime.of(0, 45), late.endDate to late.endTime)
        assertTrue(late.endsAfterStart)
    }

    @Test
    fun `newForm all day is an all-day event on that one day, a start time is ignored`() {
        val allDay = newForm(friday, now, work.id, allDay = true)
        assertTrue(allDay.allDay)
        assertEquals(friday, allDay.startDate)
        assertEquals(friday, allDay.endDate)
        assertEquals(work.id, allDay.calendarId)
        assertTrue(allDay.endsAfterStart)
        assertEquals(allDay, newForm(friday, now, work.id, LocalTime.of(14, 45), allDay = true))

        // Today as well: no next-full-hour shift into another day.
        val todays = newForm(today, now, work.id, allDay = true)
        assertEquals(today to today, todays.startDate to todays.endDate)
        assertTrue(todays.allDay)
    }

    @Test
    fun `create from a pressed time opens a new event there in the first visible writable calendar`() {
        val vm = EventEditorViewModel(repo, clock)
        vm.create(friday, listOf(feed, hidden, work, home), LocalTime.of(14, 45))
        val state = vm.uiState.value
        assertTrue(state.open && state.creating)
        val form = state.form!!
        assertEquals(work.id, form.calendarId)
        assertFalse(form.allDay)
        assertEquals(friday to LocalTime.of(14, 45), form.startDate to form.startTime)
        assertEquals(LocalTime.of(15, 45), form.endTime)
    }

    @Test
    fun `create all day opens an all-day event on the day in the first visible writable calendar`() {
        val vm = EventEditorViewModel(repo, clock)
        vm.create(friday, listOf(feed, hidden, home, work), allDay = true)
        val form = vm.uiState.value.form!!
        assertTrue(form.allDay)
        assertEquals(friday to friday, form.startDate to form.endDate)
        assertEquals(home.id, form.calendarId)
    }

    @Test
    fun `create with only hidden writable calendars falls back to the first writable one, like the add button`() {
        val vm = EventEditorViewModel(repo, clock)
        vm.create(friday, listOf(feed, hidden), LocalTime.of(8, 0))
        assertEquals(
            hidden.id,
            vm.uiState.value.form!!
                .calendarId,
        )
    }

    @Test
    fun `create without a start time or all day behaves like the add button`() {
        val vm = EventEditorViewModel(repo, clock)
        vm.create(today, listOf(feed, work))
        val form = vm.uiState.value.form!!
        assertEquals(newForm(today, now, work.id), form)
        assertEquals(today to LocalTime.of(11, 0), form.startDate to form.startTime)

        // A long press after the add button replaces the form.
        vm.create(friday, listOf(feed, work), LocalTime.of(16, 30))
        assertEquals(
            friday to LocalTime.of(16, 30),
            vm.uiState.value.form!!
                .startDate to
                vm.uiState.value.form!!
                    .startTime,
        )
    }
}
