package ch.sonensei.cockpit.calendar.agenda

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** Which ranges the views load, how the agenda grows, and the visibility toggle. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<CalendarRepository>()
    private val zone = ZoneId.of("Europe/Zurich")

    // Wednesday 21 October 2026, 10:00 in Zurich.
    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), zone)
    private val today = LocalDate.parse("2026-10-21")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(90), "Work", null, true, false, 3)

    private fun event(
        n: Int,
        date: LocalDate,
    ) = Occurrence(id(n), work.id, "Event $n", null, true, null, null, date, date.plusDays(1))

    private fun viewModel() = CalendarViewModel(repo, clock, Locale.GERMANY)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.calendars() } returns CalendarResult.Ok(listOf(work))
        coEvery { repo.occurrences(any(), any(), any()) } returns CalendarResult.Ok(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the agenda loads 30 days from today and grows by range`() =
        runTest(dispatcher) {
            val first = today.plusDays(2)
            val later = today.plusDays(40)
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } returns CalendarResult.Ok(listOf(event(1, first)))
            coEvery { repo.occurrences(today.plusDays(30), today.plusDays(60), zone) } returns CalendarResult.Ok(listOf(event(2, later)))
            val vm = viewModel()

            vm.refresh()
            advanceUntilIdle()
            var state = vm.uiState.value
            assertEquals(CalendarView.AGENDA, state.view)
            assertEquals(today to today.plusDays(30), state.from to state.to)
            assertEquals(listOf(first), state.agendaDays.map { it.date })
            assertEquals(listOf(work), state.calendars)
            assertTrue(state.loaded)

            vm.loadMore()
            assertTrue(vm.uiState.value.loadingMore)
            advanceUntilIdle()
            state = vm.uiState.value
            assertFalse(state.loadingMore)
            assertEquals(today.plusDays(60), state.to)
            assertEquals(listOf(first, later), state.agendaDays.map { it.date })
        }

    @Test
    fun `day and week views load their days and move by a day or a week`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.select(CalendarView.WEEK)
            advanceUntilIdle()
            // Germany: weeks start on Monday.
            val monday = LocalDate.parse("2026-10-19")
            assertEquals(monday to monday.plusDays(7), vm.uiState.value.let { it.from to it.to })
            assertEquals((0L until 7L).map { monday.plusDays(it) }, vm.uiState.value.days)

            vm.move(1)
            advanceUntilIdle()
            assertEquals(monday.plusDays(7), vm.uiState.value.from)

            vm.openDay(LocalDate.parse("2026-10-30"))
            advanceUntilIdle()
            assertEquals(CalendarView.DAY, vm.uiState.value.view)
            assertEquals(LocalDate.parse("2026-10-30") to LocalDate.parse("2026-10-31"), vm.uiState.value.let { it.from to it.to })

            vm.move(-1)
            advanceUntilIdle()
            assertEquals(LocalDate.parse("2026-10-29"), vm.uiState.value.from)

            vm.goToToday()
            advanceUntilIdle()
            assertEquals(today to today.plusDays(1), vm.uiState.value.let { it.from to it.to })
            coVerify { repo.occurrences(monday, monday.plusDays(7), zone) }
        }

    @Test
    fun `a failed load keeps the shown events and says why`() =
        runTest(dispatcher) {
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } returns CalendarResult.Ok(listOf(event(1, today)))
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            coEvery { repo.occurrences(today, today.plusDays(30), zone) } returns CalendarResult.Offline
            vm.refresh()
            advanceUntilIdle()
            assertEquals(CalendarMessage.OFFLINE, vm.uiState.value.message)
            assertEquals(1, vm.uiState.value.occurrences.size)

            coEvery { repo.occurrences(today, today.plusDays(30), zone) } returns CalendarResult.Failed(400)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(CalendarMessage.FAILED, vm.uiState.value.message)
        }

    @Test
    fun `hiding a calendar stores it on the server and reloads the range`() =
        runTest(dispatcher) {
            val hidden = work.copy(visible = false, version = 4)
            coEvery { repo.setVisible(work, false) } returns CalendarResult.Ok(hidden)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            coEvery { repo.calendars() } returns CalendarResult.Ok(listOf(hidden))
            vm.setVisible(work.id, false)
            assertFalse(
                vm.uiState.value.calendars
                    .single()
                    .visible,
            )
            advanceUntilIdle()
            assertEquals(listOf(hidden), vm.uiState.value.calendars)
            coVerify(exactly = 2) { repo.occurrences(today, today.plusDays(30), zone) }
        }

    @Test
    fun `a refused visibility change is undone`() =
        runTest(dispatcher) {
            coEvery { repo.setVisible(work, false) } returns CalendarResult.Failed(412)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.setVisible(work.id, false)
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.calendars
                    .single()
                    .visible,
            )
            assertEquals(CalendarMessage.VISIBILITY_FAILED, vm.uiState.value.message)
        }
}
