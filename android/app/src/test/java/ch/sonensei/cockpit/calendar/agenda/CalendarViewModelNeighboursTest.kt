package ch.sonensei.cockpit.calendar.agenda

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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

/** The previous and next periods the day, week and month views preload for a swipe. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelNeighboursTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<CalendarRepository>()
    private val zone = ZoneId.of("Europe/Zurich")

    // Wednesday 21 October 2026, 10:00 in Zurich.
    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), zone)
    private val today = LocalDate.parse("2026-10-21")
    private val yesterday = today.minusDays(1)
    private val tomorrow = today.plusDays(1)

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(90), "Work", null, true, false, 3)

    private fun event(
        n: Int,
        date: LocalDate,
    ) = Occurrence(id(n), work.id, "Event $n", null, true, null, null, date, date.plusDays(1))

    private fun viewModel() = CalendarViewModel(repo, clock, Locale.GERMANY)

    private fun CalendarViewModel.ranges() = uiState.value.neighbours.map { it.from to it.to }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.calendars() } returns CalendarResult.Ok(listOf(work))
        coEvery { repo.occurrences(any(), any(), any()) } returns CalendarResult.Ok(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun kotlinx.coroutines.test.TestScope.dayView(): CalendarViewModel {
        val vm = viewModel()
        vm.refresh()
        advanceUntilIdle()
        vm.select(CalendarView.DAY)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `the agenda preloads no neighbours and ignores moves`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.neighbours
                    .isEmpty(),
            )
            val before = vm.uiState.value

            vm.move(1)
            advanceUntilIdle()
            assertEquals(before.from to before.to, vm.uiState.value.let { it.from to it.to })
            assertEquals(CalendarView.AGENDA, vm.uiState.value.view)
            coVerify(exactly = 1) { repo.occurrences(any(), any(), any()) }
        }

    @Test
    fun `a move by zero keeps the period and loads nothing`() =
        runTest(dispatcher) {
            val vm = dayView()
            coVerify(exactly = 4) { repo.occurrences(any(), any(), any()) }
            vm.move(0)
            advanceUntilIdle()
            assertEquals(today, vm.uiState.value.anchor)
            coVerify(exactly = 4) { repo.occurrences(any(), any(), any()) }
        }

    @Test
    fun `a pager swipe over several periods moves by as many days`() =
        runTest(dispatcher) {
            val vm = dayView()
            vm.move(3)
            advanceUntilIdle()
            assertEquals(today.plusDays(3) to today.plusDays(4), vm.uiState.value.let { it.from to it.to })
            assertEquals(
                listOf(today.plusDays(2) to today.plusDays(3), today.plusDays(4) to today.plusDays(5)),
                vm.ranges(),
            )
        }

    @Test
    fun `the week and month views preload the previous and next week or month grid`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.select(CalendarView.WEEK)
            advanceUntilIdle()
            val monday = LocalDate.parse("2026-10-19")
            assertEquals(
                listOf(monday.minusDays(7) to monday, monday.plusDays(7) to monday.plusDays(14)),
                vm.ranges(),
            )

            vm.select(CalendarView.MONTH)
            advanceUntilIdle()
            // Germany, weeks from Monday: September's grid starts on 31 August, November's on 26 October.
            val ranges = vm.ranges()
            assertEquals(2, ranges.size)
            assertEquals(LocalDate.parse("2026-08-31"), ranges[0].first)
            assertEquals(LocalDate.parse("2026-10-26"), ranges[1].first)
            val november = vm.uiState.value.pageState(LocalDate.parse("2026-11-15"))
            assertTrue(november.loaded)
            assertEquals(LocalDate.parse("2026-10-26"), november.days.first())
        }

    @Test
    fun `a neighbour stays an empty, not loaded page until its preload answers, then fills`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<CalendarResult<List<Occurrence>>>()
            coEvery { repo.occurrences(tomorrow, tomorrow.plusDays(1), zone) } coAnswers { answer.await() }
            val vm = dayView()

            // The day itself is shown; its neighbours wait for the slower of the two preloads.
            assertTrue(vm.uiState.value.loaded)
            val waiting = vm.uiState.value.pageState(tomorrow)
            assertFalse(waiting.loaded)
            assertTrue(waiting.occurrences.isEmpty())
            assertEquals(tomorrow to tomorrow.plusDays(1), waiting.from to waiting.to)

            answer.complete(CalendarResult.Ok(listOf(event(3, tomorrow))))
            advanceUntilIdle()
            val filled = vm.uiState.value.pageState(tomorrow)
            assertTrue(filled.loaded)
            assertEquals(listOf(event(3, tomorrow)), filled.occurrences)
            assertTrue(
                vm.uiState.value
                    .pageState(yesterday)
                    .loaded,
            )
        }

    @Test
    fun `a failed neighbour preload leaves only that neighbour empty`() =
        runTest(dispatcher) {
            coEvery { repo.occurrences(yesterday, today, zone) } returns CalendarResult.Offline
            coEvery { repo.occurrences(tomorrow, tomorrow.plusDays(1), zone) } returns CalendarResult.Ok(listOf(event(4, tomorrow)))
            val vm = dayView()
            assertEquals(listOf(tomorrow to tomorrow.plusDays(1)), vm.ranges())
            assertFalse(
                vm.uiState.value
                    .pageState(yesterday)
                    .loaded,
            )
            assertEquals(
                listOf(event(4, tomorrow)),
                vm.uiState.value
                    .pageState(tomorrow)
                    .occurrences,
            )
        }

    @Test
    fun `a failed load of the shown day preloads no neighbours`() =
        runTest(dispatcher) {
            coEvery { repo.occurrences(today, tomorrow, zone) } returns CalendarResult.Offline
            val vm = dayView()
            assertTrue(
                vm.uiState.value.neighbours
                    .isEmpty(),
            )
            coVerify(exactly = 0) { repo.occurrences(yesterday, today, zone) }
            coVerify(exactly = 0) { repo.occurrences(tomorrow, tomorrow.plusDays(1), zone) }
        }

    @Test
    fun `a preload that answers after a move is not shown for the new period`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<CalendarResult<List<Occurrence>>>()
            coEvery { repo.occurrences(yesterday, today, zone) } coAnswers { answer.await() }
            val vm = dayView()

            vm.move(1)
            answer.complete(CalendarResult.Ok(listOf(event(6, yesterday))))
            advanceUntilIdle()
            assertEquals(tomorrow, vm.uiState.value.anchor)
            // The new day's own neighbours: today and the day after tomorrow, not the late answer for yesterday.
            assertEquals(listOf(today to tomorrow, tomorrow.plusDays(1) to tomorrow.plusDays(2)), vm.ranges())
            assertTrue(
                vm.uiState.value.neighbours
                    .none { n -> n.occurrences.any { it == event(6, yesterday) } },
            )
        }

    @Test
    fun `swiping back shows the day left behind at once`() =
        runTest(dispatcher) {
            coEvery { repo.occurrences(today, tomorrow, zone) } returns CalendarResult.Ok(listOf(event(8, today)))
            val vm = dayView()
            // The next day's load never answers: the day left behind is still kept for a swipe back.
            coEvery { repo.occurrences(tomorrow, tomorrow.plusDays(1), zone) } coAnswers { CompletableDeferred<Nothing>().await() }

            vm.move(1)
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value
                    .pageState(today)
                    .loaded,
            )
            assertEquals(
                listOf(event(8, today)),
                vm.uiState.value
                    .pageState(today)
                    .occurrences,
            )

            vm.move(-1)
            // Before today's load answers again, the preloaded occurrences are shown.
            assertEquals(listOf(event(8, today)), vm.uiState.value.occurrences)
        }

    @Test
    fun `switching the view drops the neighbours of the previous view`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<CalendarResult<List<Occurrence>>>()
            val vm = dayView()
            assertEquals(2, vm.uiState.value.neighbours.size)
            val monday = LocalDate.parse("2026-10-19")
            coEvery { repo.occurrences(monday, monday.plusDays(7), zone) } coAnswers { answer.await() }

            vm.select(CalendarView.WEEK)
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.neighbours
                    .isEmpty(),
            )
            assertFalse(
                vm.uiState.value
                    .pageState(monday.plusDays(7))
                    .loaded,
            )
            answer.complete(CalendarResult.Ok(emptyList()))
            advanceUntilIdle()
            assertEquals(2, vm.uiState.value.neighbours.size)
        }
}
