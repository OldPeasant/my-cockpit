package ch.sonensei.cockpit.calendar.edit

import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.calendar.agenda.CalendarResult
import ch.sonensei.cockpit.calendar.agenda.Occurrence
import ch.sonensei.cockpit.core.settings.UserSettings
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** New and edited events default to the synced time zone, the device's only while the settings are unknown. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventEditorViewModelSettingsTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<EventEditRepository>()
    private val zurich = ZoneId.of("Europe/Zurich")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    /** Wednesday 21 October 2026, 10:20 in Zurich (the device), 17:20 in Tokyo. */
    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:20:00Z"), zurich)
    private val today = LocalDate.parse("2026-10-21")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(90), "Work", null, true, false, 3)

    private val dentist =
        Occurrence(
            id(1),
            work.id,
            "Dentist",
            null,
            false,
            Instant.parse("2026-10-22T08:00:00Z"),
            Instant.parse("2026-10-22T09:00:00Z"),
            null,
            null,
            version = 4,
        )

    private fun details(timeZone: String?) =
        EventDetails(
            id = dentist.eventId,
            calendarId = dentist.calendarId,
            title = dentist.title,
            location = null,
            description = null,
            allDay = false,
            start = dentist.start,
            end = dentist.end,
            startDate = null,
            endDate = null,
            timeZone = timeZone,
            rrule = null,
            seriesId = null,
            version = 4,
        )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a new event starts at the next full hour of the synced zone`() {
        val vm = EventEditorViewModel(repo, clock, MutableStateFlow(UserSettings(timeZone = tokyo)))

        vm.create(today, listOf(work))

        val form = vm.uiState.value.form!!
        assertEquals(tokyo, form.zone)
        assertEquals(today to LocalTime.of(18, 0), form.startDate to form.startTime)
        assertEquals(LocalTime.of(19, 0), form.endTime)
    }

    @Test
    fun `without settings a new event uses the device zone`() {
        val vm = EventEditorViewModel(repo, clock, MutableStateFlow(null))

        vm.create(today, listOf(work))

        val form = vm.uiState.value.form!!
        assertEquals(zurich, form.zone)
        assertEquals(LocalTime.of(11, 0), form.startTime)
    }

    @Test
    fun `settings loaded after the editor was created apply to the next new event`() {
        val settings = MutableStateFlow<UserSettings?>(null)
        val vm = EventEditorViewModel(repo, clock, settings)

        settings.value = UserSettings(timeZone = tokyo)
        vm.create(today, listOf(work))

        assertEquals(
            tokyo,
            vm.uiState.value.form
                ?.zone,
        )
    }

    @Test
    fun `an event without its own zone is edited in the synced zone`() =
        runTest(dispatcher) {
            coEvery { repo.event(dentist.eventId) } returns CalendarResult.Ok(details(timeZone = null))
            val vm = EventEditorViewModel(repo, clock, MutableStateFlow(UserSettings(timeZone = tokyo)))

            vm.edit(dentist, listOf(work))
            advanceUntilIdle()

            val form = vm.uiState.value.form!!
            assertEquals(tokyo, form.zone)
            assertEquals(LocalDate.parse("2026-10-22") to LocalTime.of(17, 0), form.startDate to form.startTime)
        }

    @Test
    fun `an event's own zone wins over the synced zone`() =
        runTest(dispatcher) {
            coEvery { repo.event(dentist.eventId) } returns CalendarResult.Ok(details(timeZone = "Europe/Zurich"))
            val vm = EventEditorViewModel(repo, clock, MutableStateFlow(UserSettings(timeZone = tokyo)))

            vm.edit(dentist, listOf(work))
            advanceUntilIdle()

            val form = vm.uiState.value.form!!
            assertEquals(zurich, form.zone)
            assertEquals(LocalTime.of(10, 0), form.startTime)
        }
}
