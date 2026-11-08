package ch.sonensei.cockpit.calendar.agenda

import ch.sonensei.cockpit.core.settings.UserSettings
import io.mockk.coEvery
import io.mockk.coVerify
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
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** The calendar takes week start and time zone from the synced settings, the device locale only while they are unknown. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelSettingsTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<CalendarRepository>()
    private val zurich = ZoneId.of("Europe/Zurich")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    /** Wednesday 2026-10-21 23:30 in Zurich, already Thursday 06:30 in Tokyo. */
    private val clock = Clock.fixed(Instant.parse("2026-10-21T21:30:00Z"), zurich)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.calendars() } returns CalendarResult.Ok(emptyList())
        coEvery { repo.occurrences(any(), any(), any()) } returns CalendarResult.Ok(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `without settings the locale decides the week start and the device the zone`() =
        runTest(dispatcher) {
            val vm = CalendarViewModel(repo, clock, Locale.US, settings = MutableStateFlow(null))

            assertEquals(DayOfWeek.SUNDAY, vm.uiState.value.firstDayOfWeek)
            assertEquals(zurich, vm.uiState.value.zone)
        }

    @Test
    fun `known settings decide from the start`() =
        runTest(dispatcher) {
            val settings = MutableStateFlow<UserSettings?>(UserSettings(weekStart = DayOfWeek.SATURDAY, timeZone = tokyo))
            val vm = CalendarViewModel(repo, clock, Locale.GERMANY, settings = settings)

            assertEquals(DayOfWeek.SATURDAY, vm.uiState.value.firstDayOfWeek)
            assertEquals(tokyo, vm.uiState.value.zone)
            assertEquals(LocalDate.of(2026, 10, 22), vm.uiState.value.today)

            vm.select(CalendarView.WEEK)
            advanceUntilIdle()
            val saturday = LocalDate.of(2026, 10, 17)
            assertEquals(
                saturday,
                vm.uiState.value.days
                    .first(),
            )
            coVerify { repo.occurrences(saturday, saturday.plusDays(7), tokyo) }
        }

    @Test
    fun `settings loaded later recompute and reload the shown week`() =
        runTest(dispatcher) {
            val settings = MutableStateFlow<UserSettings?>(null)
            val vm = CalendarViewModel(repo, clock, Locale.GERMANY, settings = settings)
            vm.refresh()
            vm.select(CalendarView.WEEK)
            advanceUntilIdle()
            val monday = LocalDate.of(2026, 10, 19)
            assertEquals(
                monday,
                vm.uiState.value.days
                    .first(),
            )

            settings.value = UserSettings(weekStart = DayOfWeek.SUNDAY, timeZone = zurich)
            advanceUntilIdle()

            val sunday = LocalDate.of(2026, 10, 18)
            assertEquals(DayOfWeek.SUNDAY, vm.uiState.value.firstDayOfWeek)
            assertEquals(
                sunday,
                vm.uiState.value.days
                    .first(),
            )
            coVerify { repo.occurrences(sunday, sunday.plusDays(7), zurich) }
        }

    @Test
    fun `a new time zone moves the agenda to that zone's today`() =
        runTest(dispatcher) {
            val settings = MutableStateFlow<UserSettings?>(null)
            val vm = CalendarViewModel(repo, clock, Locale.GERMANY, settings = settings)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(LocalDate.of(2026, 10, 21), vm.uiState.value.from)

            settings.value = UserSettings(timeZone = tokyo)
            advanceUntilIdle()

            val tokyoToday = LocalDate.of(2026, 10, 22)
            assertEquals(tokyoToday, vm.uiState.value.today)
            assertEquals(tokyoToday, vm.uiState.value.from)
            coVerify { repo.occurrences(tokyoToday, tokyoToday.plusDays(30), tokyo) }
        }
}
