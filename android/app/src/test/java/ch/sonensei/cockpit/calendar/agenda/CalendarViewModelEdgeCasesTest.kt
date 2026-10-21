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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** Guards, failures, the order of answers and a change of day in the calendar view model. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelEdgeCasesTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<CalendarRepository>()
    private val zone = ZoneId.of("Europe/Zurich")

    /** A clock the test can move to another day. */
    private class MovableClock(
        var now: Instant,
        private val zone: ZoneId,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MovableClock(now, zone)

        override fun instant(): Instant = now
    }

    // Wednesday 21 October 2026, 10:00 in Zurich.
    private val clock = MovableClock(Instant.parse("2026-10-21T08:00:00Z"), zone)
    private val today = LocalDate.parse("2026-10-21")
    private val monday = LocalDate.parse("2026-10-19")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(90), "Work", null, true, false, 3)
    private val home = CalendarInfo(id(91), "Home", null, true, false, 7)

    private fun event(
        n: Int,
        date: LocalDate,
    ) = Occurrence(id(n), work.id, "Event $n", null, true, null, null, date, date.plusDays(1))

    private fun viewModel(locale: Locale = Locale.GERMANY) = CalendarViewModel(repo, clock, locale)

    private val agendaRange get() = today to today.plusDays(CalendarViewModel.AGENDA_CHUNK_DAYS)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.calendars() } returns CalendarResult.Ok(listOf(work, home))
        coEvery { repo.occurrences(any(), any(), any()) } returns CalendarResult.Ok(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an older week answering after a newer one was asked for is dropped`() =
        runTest(dispatcher) {
            val slow = CompletableDeferred<CalendarResult<List<Occurrence>>>()
            coEvery { repo.occurrences(monday, monday.plusDays(7), zone) } coAnswers { slow.await() }
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.select(CalendarView.WEEK)
            runCurrent()
            assertTrue(vm.uiState.value.loading)
            vm.move(1)
            advanceUntilIdle()
            slow.complete(CalendarResult.Ok(listOf(event(1, today))))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(monday.plusDays(7) to monday.plusDays(14), state.from to state.to)
            assertEquals(emptyList<Occurrence>(), state.occurrences)
            assertFalse(state.loading)
            assertTrue(state.loaded)
        }

    @Test
    fun `an older failing answer does not overwrite the newer result`() =
        runTest(dispatcher) {
            val slow = CompletableDeferred<CalendarResult<List<Occurrence>>>()
            val friday = LocalDate.parse("2026-10-23")
            coEvery { repo.occurrences(today, today.plusDays(1), zone) } coAnswers { slow.await() }
            coEvery { repo.occurrences(friday.minusDays(1), friday, zone) } returns
                CalendarResult.Ok(listOf(event(2, friday.minusDays(1))))
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.select(CalendarView.DAY)
            runCurrent()
            vm.move(1)
            advanceUntilIdle()
            slow.complete(CalendarResult.Offline)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertNull(state.message)
            assertEquals(listOf("Event 2"), state.occurrences.map { it.title })
        }

    @Test
    fun `switching to another range clears the old events until the new ones arrive`() =
        runTest(dispatcher) {
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } returns CalendarResult.Ok(listOf(event(1, today)))
            val pending = CompletableDeferred<CalendarResult<List<Occurrence>>>()
            coEvery { repo.occurrences(today, today.plusDays(1), zone) } coAnswers { pending.await() }
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            assertEquals(1, vm.uiState.value.occurrences.size)

            vm.select(CalendarView.DAY)
            runCurrent()
            assertEquals(emptyList<Occurrence>(), vm.uiState.value.occurrences)
            assertFalse(vm.uiState.value.loaded)
            assertTrue(vm.uiState.value.loading)

            pending.complete(CalendarResult.Ok(listOf(event(3, today))))
            advanceUntilIdle()
            assertEquals(
                listOf("Event 3"),
                vm.uiState.value.occurrences
                    .map { it.title },
            )
        }

    @Test
    fun `a running load of later days is dropped when the agenda is refreshed`() =
        runTest(dispatcher) {
            val more = CompletableDeferred<CalendarResult<List<Occurrence>>>()
            coEvery { repo.occurrences(today.plusDays(30), today.plusDays(60), zone) } coAnswers { more.await() }
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.loadMore()
            runCurrent()
            assertTrue(vm.uiState.value.loadingMore)
            vm.refresh()
            advanceUntilIdle()
            more.complete(CalendarResult.Ok(listOf(event(2, today.plusDays(40)))))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(agendaRange, state.from to state.to)
            assertFalse(state.loadingMore)
            assertEquals(emptyList<Occurrence>(), state.occurrences)
        }

    @Test
    fun `later days are not asked for before the first load, while loading, twice at once, or outside the agenda`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.loadMore() // nothing loaded yet
            advanceUntilIdle()

            val first = CompletableDeferred<CalendarResult<List<Occurrence>>>()
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } coAnswers { first.await() }
            vm.refresh()
            runCurrent()
            vm.loadMore() // the range is still loading
            first.complete(CalendarResult.Ok(emptyList()))
            advanceUntilIdle()

            vm.loadMore()
            vm.loadMore() // the first one is still running
            advanceUntilIdle()
            assertEquals(today.plusDays(60), vm.uiState.value.to)

            vm.select(CalendarView.DAY)
            advanceUntilIdle()
            vm.loadMore() // not the agenda
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.occurrences(today.plusDays(30), today.plusDays(60), zone) }
            coVerify(exactly = 0) { repo.occurrences(today.plusDays(60), today.plusDays(90), zone) }
            coVerify(exactly = 0) { repo.occurrences(today.plusDays(1), any(), zone) }
        }

    @Test
    fun `a failed load of later days keeps the range and the events, and a later success clears the message`() =
        runTest(dispatcher) {
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } returns CalendarResult.Ok(listOf(event(1, today)))
            coEvery { repo.occurrences(today.plusDays(30), today.plusDays(60), zone) } returnsMany
                listOf(CalendarResult.Offline, CalendarResult.Failed(400), CalendarResult.Ok(listOf(event(2, today.plusDays(31)))))
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.loadMore()
            advanceUntilIdle()
            assertEquals(CalendarMessage.OFFLINE, vm.uiState.value.message)
            assertEquals(today.plusDays(30), vm.uiState.value.to)
            assertFalse(vm.uiState.value.loadingMore)
            assertEquals(1, vm.uiState.value.occurrences.size)

            vm.loadMore()
            advanceUntilIdle()
            assertEquals(CalendarMessage.FAILED, vm.uiState.value.message)
            assertEquals(today.plusDays(30), vm.uiState.value.to)

            vm.loadMore()
            advanceUntilIdle()
            assertNull(vm.uiState.value.message)
            assertEquals(today.plusDays(60), vm.uiState.value.to)
            assertEquals(
                listOf(today, today.plusDays(31)),
                vm.uiState.value.agendaDays
                    .map { it.date },
            )
        }

    @Test
    fun `an occurrence returned by two ranges is listed once`() =
        runTest(dispatcher) {
            val longTrip = Occurrence(id(5), work.id, "Trip", null, true, null, null, today.plusDays(28), today.plusDays(33))
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } returns CalendarResult.Ok(listOf(longTrip))
            coEvery { repo.occurrences(today.plusDays(30), today.plusDays(60), zone) } returns CalendarResult.Ok(listOf(longTrip))
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.loadMore()
            advanceUntilIdle()

            assertEquals(1, vm.uiState.value.occurrences.size)
            assertEquals(
                (28L until 33L).map { today.plusDays(it) },
                vm.uiState.value.agendaDays
                    .map { it.date },
            )
        }

    @Test
    fun `refreshing on a new day starts the agenda at the new today`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.loadMore()
            advanceUntilIdle()
            assertEquals(today.plusDays(60), vm.uiState.value.to)

            clock.now = Instant.parse("2026-10-22T06:00:00Z")
            vm.refresh()
            advanceUntilIdle()

            val tomorrow = today.plusDays(1)
            val state = vm.uiState.value
            assertEquals(tomorrow, state.today)
            assertEquals(tomorrow to tomorrow.plusDays(30), state.from to state.to)
            coVerify { repo.occurrences(tomorrow, tomorrow.plusDays(30), zone) }
        }

    @Test
    fun `refreshing the agenda on the same day reloads everything scrolled to so far`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.loadMore()
            advanceUntilIdle()

            vm.refresh()
            advanceUntilIdle()

            assertEquals(today to today.plusDays(60), vm.uiState.value.let { it.from to it.to })
            coVerify(exactly = 1) { repo.occurrences(today, today.plusDays(60), zone) }
        }

    @Test
    fun `refreshing the week view on a new day keeps the shown week`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.select(CalendarView.WEEK)
            advanceUntilIdle()

            clock.now = Instant.parse("2026-10-22T06:00:00Z")
            vm.refresh()
            advanceUntilIdle()

            assertEquals(today.plusDays(1), vm.uiState.value.today)
            assertEquals(monday to monday.plusDays(7), vm.uiState.value.let { it.from to it.to })
        }

    @Test
    fun `back to the agenda starts at today, moving in the agenda and selecting the shown view do nothing`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.openDay(today.plusDays(10))
            advanceUntilIdle()

            vm.select(CalendarView.DAY) // already shown
            vm.select(CalendarView.AGENDA)
            advanceUntilIdle()
            assertEquals(CalendarView.AGENDA, vm.uiState.value.view)
            assertEquals(agendaRange, vm.uiState.value.let { it.from to it.to })

            vm.move(1)
            advanceUntilIdle()
            assertEquals(agendaRange, vm.uiState.value.let { it.from to it.to })
            coVerify(exactly = 1) { repo.occurrences(today.plusDays(10), today.plusDays(11), zone) }
            coVerify(exactly = 2) { repo.occurrences(today, today.plusDays(30), zone) }
        }

    @Test
    fun `the week view shows the week of the day it came from, and the day view returns to that day`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.openDay(LocalDate.parse("2026-10-30"))
            advanceUntilIdle()

            vm.select(CalendarView.WEEK)
            advanceUntilIdle()
            val nextMonday = LocalDate.parse("2026-10-26")
            assertEquals(nextMonday to nextMonday.plusDays(7), vm.uiState.value.let { it.from to it.to })

            vm.select(CalendarView.DAY)
            advanceUntilIdle()
            assertEquals(LocalDate.parse("2026-10-30"), vm.uiState.value.anchor)
            coVerify(exactly = 1) { repo.occurrences(nextMonday, nextMonday.plusDays(7), zone) }
        }

    @Test
    fun `today reloads even when today is already shown`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.select(CalendarView.DAY)
            advanceUntilIdle()

            vm.openDay(today) // same day, loaded: only the anchor
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.occurrences(today, today.plusDays(1), zone) }

            vm.goToToday()
            advanceUntilIdle()
            coVerify(exactly = 2) { repo.occurrences(today, today.plusDays(1), zone) }
        }

    @Test
    fun `weeks start on Sunday where the locale says so`() =
        runTest(dispatcher) {
            val vm = viewModel(Locale.US)
            vm.refresh()
            advanceUntilIdle()
            vm.select(CalendarView.WEEK)
            advanceUntilIdle()

            val sunday = LocalDate.parse("2026-10-18")
            assertEquals(sunday to sunday.plusDays(7), vm.uiState.value.let { it.from to it.to })
            assertEquals(
                sunday,
                vm.uiState.value.days
                    .first(),
            )
        }

    @Test
    fun `a first load that fails ends the spinner, says why, and can be retried`() =
        runTest(dispatcher) {
            coEvery { repo.calendars() } returns CalendarResult.Offline
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } returnsMany
                listOf(CalendarResult.Offline, CalendarResult.Ok(listOf(event(1, today))))
            val vm = viewModel()

            vm.refresh()
            advanceUntilIdle()
            var state = vm.uiState.value
            assertFalse(state.loading)
            assertFalse(state.loaded)
            assertEquals(CalendarMessage.OFFLINE, state.message)
            assertEquals(emptyList<CalendarInfo>(), state.calendars)

            coEvery { repo.calendars() } returns CalendarResult.Ok(listOf(work))
            vm.refresh()
            advanceUntilIdle()
            state = vm.uiState.value
            assertTrue(state.loaded)
            assertNull(state.message)
            assertEquals(listOf(work), state.calendars)
            assertEquals(listOf(today), state.agendaDays.map { it.date })
        }

    @Test
    fun `calendars that fail to load keep the ones shown`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            coEvery { repo.calendars() } returns CalendarResult.Failed(500)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(listOf(work, home), vm.uiState.value.calendars)
            assertNull(vm.uiState.value.message)
        }

    @Test
    fun `an offline visibility change is undone without reloading the calendars`() =
        runTest(dispatcher) {
            coEvery { repo.setVisible(work, false) } returns CalendarResult.Offline
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.setVisible(work.id, false)
            advanceUntilIdle()

            assertEquals(listOf(work, home), vm.uiState.value.calendars)
            assertEquals(CalendarMessage.OFFLINE, vm.uiState.value.message)
            coVerify(exactly = 1) { repo.calendars() }
            coVerify(exactly = 1) { repo.occurrences(any(), any(), any()) }
        }

    @Test
    fun `a refused visibility change reloads the calendars from the server`() =
        runTest(dispatcher) {
            coEvery { repo.setVisible(work, false) } returns CalendarResult.Failed(412)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            val changedElsewhere = work.copy(visible = false, version = 5)
            coEvery { repo.calendars() } returns CalendarResult.Ok(listOf(changedElsewhere, home))
            vm.setVisible(work.id, false)
            advanceUntilIdle()

            assertEquals(listOf(changedElsewhere, home), vm.uiState.value.calendars)
            assertEquals(CalendarMessage.VISIBILITY_FAILED, vm.uiState.value.message)
            coVerify(exactly = 2) { repo.calendars() }
        }

    @Test
    fun `a refused visibility change keeps the old calendars when reloading them fails too`() =
        runTest(dispatcher) {
            coEvery { repo.setVisible(work, false) } returns CalendarResult.Failed(412)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            coEvery { repo.calendars() } returns CalendarResult.Offline
            vm.setVisible(work.id, false)
            advanceUntilIdle()

            assertEquals(listOf(work, home), vm.uiState.value.calendars)
            assertEquals(CalendarMessage.VISIBILITY_FAILED, vm.uiState.value.message)
        }

    @Test
    fun `an unknown calendar or an unchanged visibility sends nothing`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.setVisible(id(999), false)
            vm.setVisible(work.id, true)
            advanceUntilIdle()

            coVerify(exactly = 0) { repo.setVisible(any(), any()) }
            assertEquals(listOf(work, home), vm.uiState.value.calendars)
        }

    @Test
    fun `toggling one calendar leaves the others alone`() =
        runTest(dispatcher) {
            val hiddenHome = home.copy(visible = false, version = 8)
            coEvery { repo.setVisible(home, false) } returns CalendarResult.Ok(hiddenHome)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            coEvery { repo.calendars() } returns CalendarResult.Ok(listOf(work, hiddenHome))
            vm.setVisible(home.id, false)
            advanceUntilIdle()

            assertEquals(listOf(work, hiddenHome), vm.uiState.value.calendars)
        }

    @Test
    fun `a second toggle of a calendar while its change is written is ignored`() =
        runTest(dispatcher) {
            val hidden = work.copy(visible = false, version = 4)
            val firstWrite = CompletableDeferred<CalendarResult<CalendarInfo>>()
            coEvery { repo.setVisible(work, false) } coAnswers { firstWrite.await() }
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.setVisible(work.id, false)
            runCurrent()
            assertEquals(mapOf(work.id to false), vm.uiState.value.pendingVisibility)
            vm.setVisible(work.id, true) // would be sent with version 3, which the first change replaces
            runCurrent()
            assertFalse(
                vm.uiState.value
                    .calendar(work.id)!!
                    .visible,
            )

            coEvery { repo.calendars() } returns CalendarResult.Ok(listOf(hidden, home))
            firstWrite.complete(CalendarResult.Ok(hidden))
            advanceUntilIdle()

            assertEquals(listOf(hidden, home), vm.uiState.value.calendars)
            assertEquals(emptyMap<UUID, Boolean>(), vm.uiState.value.pendingVisibility)
            assertNull(vm.uiState.value.message)
            coVerify(exactly = 1) { repo.setVisible(any(), any()) }

            // Once the answer is there, the calendar can be toggled again, with the new version.
            coEvery { repo.setVisible(hidden, true) } returns CalendarResult.Ok(work.copy(version = 5))
            vm.setVisible(work.id, true)
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.setVisible(hidden, true) }
        }

    @Test
    fun `a calendar list loaded while a change is written keeps the chosen visibility`() =
        runTest(dispatcher) {
            val write = CompletableDeferred<CalendarResult<CalendarInfo>>()
            coEvery { repo.setVisible(work, false) } coAnswers { write.await() }
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.setVisible(work.id, false)
            runCurrent()
            // The list was read by the server before the change: Work is still visible there.
            val renamedHome = home.copy(name = "Family", version = 8)
            coEvery { repo.calendars() } returns CalendarResult.Ok(listOf(work, renamedHome))
            vm.refresh()
            advanceUntilIdle()

            assertEquals(listOf(work.copy(visible = false), renamedHome), vm.uiState.value.calendars)

            val hidden = work.copy(visible = false, version = 4)
            write.complete(CalendarResult.Ok(hidden))
            advanceUntilIdle()
            assertEquals(listOf(hidden, renamedHome), vm.uiState.value.calendars)
        }

    @Test
    fun `a calendar list read before a change answered does not undo it`() =
        runTest(dispatcher) {
            val hidden = work.copy(visible = false, version = 4)
            coEvery { repo.setVisible(work, false) } returns CalendarResult.Ok(hidden)
            coEvery { repo.setVisible(home, false) } returns CalendarResult.Failed(412)
            val stale = CompletableDeferred<CalendarResult<List<CalendarInfo>>>()
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            // Hiding Home is refused, and the calendars it reloads are read before Work is hidden.
            coEvery { repo.calendars() } coAnswers { stale.await() }
            vm.setVisible(home.id, false)
            advanceUntilIdle()
            coEvery { repo.calendars() } returns CalendarResult.Ok(listOf(hidden, home))
            vm.setVisible(work.id, false)
            advanceUntilIdle()
            stale.complete(CalendarResult.Ok(listOf(work, home)))
            advanceUntilIdle()

            assertEquals(listOf(hidden, home), vm.uiState.value.calendars)
        }

    @Test
    fun `a failed change after a newer calendar list arrived goes back to that list`() =
        runTest(dispatcher) {
            val write = CompletableDeferred<CalendarResult<CalendarInfo>>()
            coEvery { repo.setVisible(work, false) } coAnswers { write.await() }
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.setVisible(work.id, false)
            runCurrent()
            // Meanwhile another device renamed Work.
            val renamed = work.copy(name = "Office", version = 4)
            coEvery { repo.calendars() } returns CalendarResult.Ok(listOf(renamed, home))
            vm.refresh()
            advanceUntilIdle()
            assertEquals(renamed.copy(visible = false), vm.uiState.value.calendar(work.id))

            write.complete(CalendarResult.Offline)
            advanceUntilIdle()

            assertEquals(listOf(renamed, home), vm.uiState.value.calendars)
            assertEquals(CalendarMessage.OFFLINE, vm.uiState.value.message)
            assertEquals(emptyMap<UUID, Boolean>(), vm.uiState.value.pendingVisibility)
        }

    @Test
    fun `a slow agenda load superseded by a view switch does not overwrite the new range`() =
        runTest(dispatcher) {
            val slow = CompletableDeferred<CalendarResult<List<Occurrence>>>()
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } coAnswers { slow.await() }
            coEvery { repo.occurrences(today, today.plusDays(1), zone) } returns CalendarResult.Ok(listOf(event(4, today)))
            val vm = viewModel()
            vm.refresh()
            runCurrent()
            assertTrue(vm.uiState.value.loading)

            vm.select(CalendarView.DAY)
            advanceUntilIdle()
            slow.complete(CalendarResult.Ok(listOf(event(1, today), event(2, today.plusDays(5)))))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(CalendarView.DAY, state.view)
            assertEquals(today to today.plusDays(1), state.from to state.to)
            assertEquals(listOf("Event 4"), state.occurrences.map { it.title })
            assertFalse(state.loading)
        }
}
