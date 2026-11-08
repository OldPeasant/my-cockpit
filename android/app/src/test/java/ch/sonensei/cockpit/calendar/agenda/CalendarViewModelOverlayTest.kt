package ch.sonensei.cockpit.calendar.agenda

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.util.concurrent.Executor

/** The month view's ranges, and how the task overlay is switched, remembered, loaded and kept consistent. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelOverlayTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<CalendarRepository>()
    private val zone = ZoneId.of("Europe/Zurich")

    // Wednesday 21 October 2026, 10:00 in Zurich.
    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), zone)
    private val today = LocalDate.parse("2026-10-21")
    private val monday = LocalDate.parse("2026-10-19")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(90), "Work", null, true, false, 3)

    private fun task(
        n: Int,
        due: LocalDate,
    ) = OverlayTask(id(n), "Task $n", dueDate = due)

    /** Answers with the tasks due in the asked range; a range with a gate waits until the test opens it. */
    private inner class FakeTasks : TaskOverlaySource {
        val calls = mutableListOf<Pair<LocalDate, LocalDate>>()
        val todays = mutableListOf<LocalDate>()
        var all: List<OverlayTask> = emptyList()
        var releasedToday: List<OverlayTask> = emptyList()
        var failure: CalendarResult<List<OverlayTask>>? = null
        val gates = mutableMapOf<Pair<LocalDate, LocalDate>, CompletableDeferred<Unit>>()

        override suspend fun tasks(
            from: LocalDate,
            to: LocalDate,
            today: LocalDate,
        ): CalendarResult<List<OverlayTask>> {
            calls += from to to
            todays += today
            gates[from to to]?.await()
            val released = if (today >= from && today < to) releasedToday else emptyList()
            return failure ?: CalendarResult.Ok(all.filter { it.dueDate!! >= from && it.dueDate!! < to } + released)
        }
    }

    private val tasks = FakeTasks()

    private fun viewModel(setting: TaskOverlaySetting = TaskOverlaySetting.InMemory()) =
        CalendarViewModel(repo, clock, Locale.GERMANY, taskSource = tasks, overlaySetting = setting)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.calendars() } returns CalendarResult.Ok(listOf(work))
        coEvery { repo.occurrences(any(), any(), any()) } returns CalendarResult.Ok(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the month view loads its whole weeks and moves by a month`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.select(CalendarView.MONTH)
            advanceUntilIdle()
            var state = vm.uiState.value
            assertEquals(CalendarView.MONTH, state.view)
            // October 2026 with weeks starting on Monday (Germany): 28 September to 1 November.
            assertEquals(LocalDate.parse("2026-09-28") to LocalDate.parse("2026-11-02"), state.from to state.to)
            assertEquals(35, state.days.size)
            assertEquals(state.from, state.days.first())
            assertEquals(state.to, state.days.last().plusDays(1))

            vm.move(1)
            advanceUntilIdle()
            state = vm.uiState.value
            // November 2026 starts on a Sunday and ends on a Monday: 6 weeks.
            assertEquals(LocalDate.parse("2026-11-21"), state.anchor)
            assertEquals(LocalDate.parse("2026-10-26") to LocalDate.parse("2026-12-07"), state.from to state.to)
            assertEquals(42, state.days.size)

            vm.move(-1)
            vm.move(-1)
            advanceUntilIdle()
            state = vm.uiState.value
            assertEquals(LocalDate.parse("2026-09-21"), state.anchor)
            assertEquals(LocalDate.parse("2026-08-31") to LocalDate.parse("2026-10-05"), state.from to state.to)
            coVerify { repo.occurrences(LocalDate.parse("2026-10-26"), LocalDate.parse("2026-12-07"), zone) }

            // A tapped day opens the day view.
            vm.openDay(LocalDate.parse("2026-09-15"))
            advanceUntilIdle()
            assertEquals(CalendarView.DAY, vm.uiState.value.view)
            assertEquals(LocalDate.parse("2026-09-15"), vm.uiState.value.from)
        }

    @Test
    fun `moving the month from the 31st lands in the next month, not two months on`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.openDay(LocalDate.parse("2027-01-31"))
            vm.select(CalendarView.MONTH)
            vm.move(1)
            advanceUntilIdle()

            assertEquals(LocalDate.parse("2027-02-28"), vm.uiState.value.anchor)
            // February 2027 is exactly 4 weeks from Monday 1 to Sunday 28.
            assertEquals(LocalDate.parse("2027-02-01") to LocalDate.parse("2027-03-01"), vm.uiState.value.let { it.from to it.to })
        }

    @Test
    fun `the overlay is off by default and loads nothing`() =
        runTest(dispatcher) {
            tasks.all = listOf(task(1, today))
            val vm = viewModel()
            vm.refresh()
            vm.select(CalendarView.WEEK)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.overlay)
            assertTrue(tasks.calls.isEmpty())
            assertTrue(
                vm.uiState.value.taskMarks
                    .isEmpty(),
            )
        }

    @Test
    fun `switching the overlay on loads the tasks of the shown days, off clears them, and both are remembered`() =
        runTest(dispatcher) {
            tasks.all = listOf(task(1, today), task(2, today.plusDays(20)))
            val setting = TaskOverlaySetting.InMemory()
            val vm = viewModel(setting)
            vm.select(CalendarView.WEEK)
            advanceUntilIdle()

            vm.setOverlay(true)
            assertTrue(vm.uiState.value.overlay)
            assertTrue(setting.enabled)
            advanceUntilIdle()
            assertEquals(listOf(monday to monday.plusDays(7)), tasks.calls)
            assertEquals(
                listOf(id(1)),
                vm.uiState.value.tasks
                    .map { it.id },
            )
            assertEquals(
                listOf(today),
                vm.uiState.value.taskMarks.keys
                    .toList(),
            )

            // Switching it on again does nothing.
            vm.setOverlay(true)
            advanceUntilIdle()
            assertEquals(1, tasks.calls.size)

            // Moving to the next week loads that week's tasks.
            vm.move(1)
            advanceUntilIdle()
            assertEquals(monday.plusDays(7) to monday.plusDays(14), tasks.calls.last())

            vm.setOverlay(false)
            assertFalse(setting.enabled)
            val state = vm.uiState.value
            assertFalse(state.overlay)
            assertTrue(state.tasks.isEmpty())
            assertTrue(state.taskMarks.isEmpty())

            val callsBefore = tasks.calls.size
            vm.refresh()
            advanceUntilIdle()
            assertEquals(callsBefore, tasks.calls.size)
        }

    @Test
    fun `a remembered overlay is on from the start and loads with the first range`() =
        runTest(dispatcher) {
            tasks.all = listOf(task(1, today.plusDays(3)))
            val vm = viewModel(TaskOverlaySetting.InMemory(enabled = true))
            assertTrue(vm.uiState.value.overlay)

            vm.refresh()
            advanceUntilIdle()

            assertEquals(listOf(today to today.plusDays(30)), tasks.calls)
            assertEquals(
                listOf(today.plusDays(3)),
                vm.uiState.value.taskMarks.keys
                    .toList(),
            )
        }

    @Test
    fun `tasks that cannot be loaded set a flag without hiding the events, a later load clears it`() =
        runTest(dispatcher) {
            tasks.all = listOf(task(1, today))
            val vm = viewModel(TaskOverlaySetting.InMemory(enabled = true))
            vm.refresh()
            advanceUntilIdle()
            assertEquals(1, vm.uiState.value.tasks.size)

            tasks.failure = CalendarResult.Offline
            vm.refresh()
            advanceUntilIdle()
            var state = vm.uiState.value
            assertTrue(state.tasksFailed)
            assertTrue(state.loaded)
            assertEquals(null, state.message)
            // The tasks shown before stay.
            assertEquals(1, state.tasks.size)

            tasks.failure = CalendarResult.Failed(403)
            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.tasksFailed)

            tasks.failure = null
            vm.refresh()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.tasksFailed)

            tasks.failure = CalendarResult.Offline
            vm.refresh()
            advanceUntilIdle()
            vm.setOverlay(false)
            state = vm.uiState.value
            assertFalse(state.tasksFailed)
        }

    @Test
    fun `another range shown while tasks load drops the old range's tasks`() =
        runTest(dispatcher) {
            tasks.all = listOf(task(1, today), task(2, today.plusDays(7)))
            val vm = viewModel(TaskOverlaySetting.InMemory(enabled = true))
            val firstWeek = monday to monday.plusDays(7)
            val gate = CompletableDeferred<Unit>()
            tasks.gates[firstWeek] = gate
            vm.refresh()
            vm.select(CalendarView.WEEK)
            advanceUntilIdle()
            assertEquals(firstWeek, tasks.calls.last())

            vm.move(1)
            // The old range's tasks are not shown on the new range while its own load runs.
            assertTrue(
                vm.uiState.value.tasks
                    .isEmpty(),
            )
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                listOf(id(2)),
                vm.uiState.value.tasks
                    .map { it.id },
            )
        }

    @Test
    fun `tasks still loading when the overlay is switched off are not shown`() =
        runTest(dispatcher) {
            tasks.all = listOf(task(1, today))
            val vm = viewModel()
            vm.select(CalendarView.DAY)
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            tasks.gates[today to today.plusDays(1)] = gate

            vm.setOverlay(true)
            advanceUntilIdle()
            vm.setOverlay(false)
            gate.complete(Unit)
            advanceUntilIdle()

            assertTrue(
                vm.uiState.value.tasks
                    .isEmpty(),
            )
            assertFalse(vm.uiState.value.tasksFailed)
        }

    @Test
    fun `the agenda appends the tasks of the days it loads more of`() =
        runTest(dispatcher) {
            tasks.all = listOf(task(1, today.plusDays(2)), task(2, today.plusDays(40)))
            val vm = viewModel(TaskOverlaySetting.InMemory(enabled = true))
            vm.refresh()
            advanceUntilIdle()
            assertEquals(
                listOf(id(1)),
                vm.uiState.value.tasks
                    .map { it.id },
            )

            vm.loadMore()
            advanceUntilIdle()

            assertEquals(today.plusDays(30) to today.plusDays(60), tasks.calls.last())
            val state = vm.uiState.value
            assertEquals(listOf(id(1), id(2)), state.tasks.map { it.id })
            assertEquals(listOf(today.plusDays(2), today.plusDays(40)), state.taskMarks.keys.toList())
        }

    @Test
    fun `appended tasks that answer after another view is shown are dropped`() =
        runTest(dispatcher) {
            tasks.all = listOf(task(1, today), task(2, today.plusDays(40)))
            val vm = viewModel(TaskOverlaySetting.InMemory(enabled = true))
            vm.refresh()
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            tasks.gates[today.plusDays(30) to today.plusDays(60)] = gate

            vm.loadMore()
            advanceUntilIdle()
            vm.select(CalendarView.DAY)
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(today to today.plusDays(1), state.from to state.to)
            assertEquals(listOf(id(1)), state.tasks.map { it.id })
        }

    @Test
    fun `appended tasks that answer after the overlay is switched off are not shown`() =
        runTest(dispatcher) {
            tasks.all = listOf(task(1, today), task(2, today.plusDays(40)))
            val vm = viewModel(TaskOverlaySetting.InMemory(enabled = true))
            vm.refresh()
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            tasks.gates[today.plusDays(30) to today.plusDays(60)] = gate

            vm.loadMore()
            advanceUntilIdle()
            vm.setOverlay(false)
            gate.complete(Unit)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.overlay)
            assertTrue(state.tasks.isEmpty())
            assertTrue(state.taskMarks.isEmpty())
            // The agenda still covers the appended days.
            assertEquals(today.plusDays(60), state.to)
        }

    @Test
    fun `appended tasks that cannot be loaded keep the tasks shown so far and set the flag`() =
        runTest(dispatcher) {
            tasks.all = listOf(task(1, today), task(2, today.plusDays(40)))
            val vm = viewModel(TaskOverlaySetting.InMemory(enabled = true))
            vm.refresh()
            advanceUntilIdle()

            tasks.failure = CalendarResult.Offline
            vm.loadMore()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(today.plusDays(60), state.to)
            assertTrue(state.tasksFailed)
            assertEquals(listOf(id(1)), state.tasks.map { it.id })
        }

    @Test
    fun `the month view loads the overlay's tasks of its whole weeks, and each moved month its own`() =
        runTest(dispatcher) {
            val september30 = LocalDate.parse("2026-09-30")
            val november1 = LocalDate.parse("2026-11-01")
            val november20 = LocalDate.parse("2026-11-20")
            tasks.all = listOf(task(1, september30), task(2, november1), task(3, november20))
            val vm = viewModel(TaskOverlaySetting.InMemory(enabled = true))
            vm.refresh()
            advanceUntilIdle()

            vm.select(CalendarView.MONTH)
            advanceUntilIdle()
            val october = LocalDate.parse("2026-09-28") to LocalDate.parse("2026-11-02")
            assertEquals(october, tasks.calls.last())
            // The days of September and November shown in October's grid get their marks too.
            assertEquals(
                listOf(september30, november1),
                vm.uiState.value.taskMarks.keys
                    .toList(),
            )

            vm.move(1)
            advanceUntilIdle()
            assertEquals(LocalDate.parse("2026-10-26") to LocalDate.parse("2026-12-07"), tasks.calls.last())
            assertEquals(
                listOf(november1, november20),
                vm.uiState.value.taskMarks.keys
                    .toList(),
            )

            vm.openDay(november20)
            advanceUntilIdle()
            assertEquals(november20 to november20.plusDays(1), tasks.calls.last())
            val shown = vm.uiState.value.tasks
            assertEquals(listOf(id(3)), shown.map { it.id })
        }

    @Test
    fun `switching the overlay on in the agenda after loading more covers all loaded days`() =
        runTest(dispatcher) {
            tasks.all = listOf(task(1, today.plusDays(2)), task(2, today.plusDays(45)))
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.loadMore()
            advanceUntilIdle()
            assertTrue(tasks.calls.isEmpty())

            vm.setOverlay(true)
            advanceUntilIdle()

            assertEquals(listOf(today to today.plusDays(60)), tasks.calls)
            val shown = vm.uiState.value.tasks
            assertEquals(listOf(id(1), id(2)), shown.map { it.id })
        }

    @Test
    fun `a failed agenda page loads no tasks for it`() =
        runTest(dispatcher) {
            val vm = viewModel(TaskOverlaySetting.InMemory(enabled = true))
            vm.refresh()
            advanceUntilIdle()
            coEvery { repo.occurrences(today.plusDays(30), today.plusDays(60), zone) } returns CalendarResult.Offline

            vm.loadMore()
            advanceUntilIdle()

            assertEquals(listOf(today to today.plusDays(30)), tasks.calls)
        }

    @Test
    fun `a task released from Scheduled to Next today is marked scheduled on today`() =
        runTest(dispatcher) {
            // The server's tickler cleared its scheduled date and set releasedOn when the list was read.
            val released = OverlayTask(id(1), "Call the plumber", releasedOn = today)
            tasks.releasedToday = listOf(released)
            val vm = viewModel(TaskOverlaySetting.InMemory(enabled = true))
            vm.select(CalendarView.WEEK)
            advanceUntilIdle()

            assertEquals(today, tasks.todays.last())
            val marks = vm.uiState.value.taskMarks
            assertEquals(listOf(today), marks.keys.toList())
            assertEquals(TaskMarkKind.SCHEDULED, marks.getValue(today).single().kind)

            // A week without today has no such mark.
            vm.move(1)
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.taskMarks
                    .isEmpty(),
            )
        }

    /** A clock the test can move past midnight. */
    private class MovableClock(
        var now: Instant,
        private val zoneId: ZoneId,
    ) : Clock() {
        override fun getZone(): ZoneId = zoneId

        override fun withZone(zone: ZoneId): Clock = MovableClock(now, zone)

        override fun instant(): Instant = now
    }

    @Test
    fun `a refresh on the next day in the same week asks for the new today's tasks and drops yesterday's released mark`() =
        runTest(dispatcher) {
            val movable = MovableClock(Instant.parse("2026-10-21T08:00:00Z"), zone)
            val released = OverlayTask(id(1), "Call the fictitious plumber", releasedOn = today)
            tasks.releasedToday = listOf(released)
            val setting = TaskOverlaySetting.InMemory(enabled = true)
            val vm = CalendarViewModel(repo, movable, Locale.GERMANY, taskSource = tasks, overlaySetting = setting)
            vm.select(CalendarView.WEEK)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(
                listOf(today),
                vm.uiState.value.taskMarks.keys
                    .toList(),
            )

            // Thursday 22 October, 10:00 in Zurich: the app comes back to the still open week view.
            movable.now = Instant.parse("2026-10-22T08:00:00Z")
            vm.refresh()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(today.plusDays(1), state.today)
            assertEquals(monday to monday.plusDays(7), state.from to state.to)
            assertEquals(today.plusDays(1), tasks.todays.last())
            // The task released yesterday is no longer marked scheduled on yesterday, nor on the new today.
            assertTrue(state.taskMarks.isEmpty())
        }

    @Test
    fun `Today in the month view returns to today's month with its tasks and the released-today mark`() =
        runTest(dispatcher) {
            val released = OverlayTask(id(1), "Water the fictitious plants", releasedOn = today)
            tasks.releasedToday = listOf(released)
            tasks.all = listOf(task(2, LocalDate.parse("2026-10-05")))
            val vm = viewModel(TaskOverlaySetting.InMemory(enabled = true))
            vm.select(CalendarView.MONTH)
            vm.move(2)
            advanceUntilIdle()
            assertEquals(LocalDate.parse("2026-12-21"), vm.uiState.value.anchor)
            assertTrue(
                vm.uiState.value.taskMarks
                    .isEmpty(),
            )

            vm.goToToday()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(CalendarView.MONTH, state.view)
            assertEquals(today, state.anchor)
            val october = LocalDate.parse("2026-09-28") to LocalDate.parse("2026-11-02")
            assertEquals(october, state.from to state.to)
            assertEquals(october, tasks.calls.last())
            assertEquals(today, tasks.todays.last())
            assertEquals(listOf(LocalDate.parse("2026-10-05"), today), state.taskMarks.keys.toList())
            assertEquals(
                TaskMarkKind.SCHEDULED,
                state.taskMarks
                    .getValue(today)
                    .single()
                    .kind,
            )
        }

    @Test
    fun `moving does nothing in the agenda, also with the overlay on`() =
        runTest(dispatcher) {
            val vm = viewModel(TaskOverlaySetting.InMemory(enabled = true))
            vm.refresh()
            advanceUntilIdle()
            val before = vm.uiState.value

            vm.move(1)
            advanceUntilIdle()

            assertEquals(before.from to before.to, vm.uiState.value.let { it.from to it.to })
            assertEquals(listOf(today to today.plusDays(30)), tasks.calls)
        }

    @Test
    fun `appended tasks are kept when the source answers without suspending`() =
        runTest(dispatcher) {
            // Every coroutine runs at once, inside the call that launches it: a task load started by loadMore answers
            // before loadMore's own state update, as it would on the main thread with a source that does not suspend.
            Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
            tasks.all = listOf(task(1, today.plusDays(2)), task(2, today.plusDays(40)))
            val vm = viewModel(TaskOverlaySetting.InMemory(enabled = true))
            vm.refresh()

            vm.loadMore()

            assertEquals(today.plusDays(60), vm.uiState.value.to)
            assertEquals(
                listOf(id(1), id(2)),
                vm.uiState.value.tasks
                    .map { it.id },
            )
        }

    @Test
    fun `another range does not keep the old range's tasks failure while its own tasks load`() =
        runTest(dispatcher) {
            tasks.all = listOf(task(1, today), task(2, today.plusDays(7)))
            val vm = viewModel(TaskOverlaySetting.InMemory(enabled = true))
            vm.select(CalendarView.WEEK)
            tasks.failure = CalendarResult.Offline
            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.tasksFailed)

            tasks.failure = null
            val nextWeek = monday.plusDays(7) to monday.plusDays(14)
            val gate = CompletableDeferred<Unit>()
            tasks.gates[nextWeek] = gate
            vm.move(1)
            // Cleared as soon as the range changes, not only when the new answer arrives.
            assertFalse(vm.uiState.value.tasksFailed)
            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.tasksFailed)
            assertEquals(
                listOf(id(2)),
                vm.uiState.value.tasks
                    .map { it.id },
            )
        }

    @Test
    fun `the same range shown again keeps its tasks failure`() =
        runTest(dispatcher) {
            val vm = viewModel(TaskOverlaySetting.InMemory(enabled = true))
            vm.select(CalendarView.DAY)
            tasks.failure = CalendarResult.Failed(500)
            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.tasksFailed)

            vm.openDay(today)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.tasksFailed)
        }

    @Test
    fun `retrying the tasks reloads only the shown range's tasks and clears the failure`() =
        runTest(dispatcher) {
            tasks.all = listOf(task(1, today))
            val vm = viewModel(TaskOverlaySetting.InMemory(enabled = true))
            vm.select(CalendarView.DAY)
            tasks.failure = CalendarResult.Offline
            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.tasksFailed)
            val callsBefore = tasks.calls.size

            tasks.failure = null
            vm.retryTasks()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.tasksFailed)
            assertEquals(listOf(id(1)), state.tasks.map { it.id })
            assertEquals(listOf(today to today.plusDays(1)), tasks.calls.drop(callsBefore))
        }

    @Test
    fun `retrying the tasks does nothing while the overlay is off`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.select(CalendarView.DAY)
            advanceUntilIdle()
            val callsBefore = tasks.calls.size

            vm.retryTasks()
            advanceUntilIdle()

            assertEquals(callsBefore, tasks.calls.size)
        }

    @Test
    fun `retrying after the agenda's appended tasks failed reloads the tasks of all its days`() =
        runTest(dispatcher) {
            tasks.all = listOf(task(1, today), task(2, today.plusDays(40)))
            val vm = viewModel(TaskOverlaySetting.InMemory(enabled = true))
            vm.refresh()
            advanceUntilIdle()
            tasks.failure = CalendarResult.Offline
            vm.loadMore()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.tasksFailed)
            val callsBefore = tasks.calls.size

            tasks.failure = null
            vm.retryTasks()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.tasksFailed)
            // One load over the whole agenda, the appended days included: their task shows up now.
            assertEquals(listOf(today to today.plusDays(60)), tasks.calls.drop(callsBefore))
            assertEquals(listOf(id(1), id(2)), state.tasks.map { it.id })
        }

    @Test
    fun `a retry that fails again keeps the failure and the tasks shown so far`() =
        runTest(dispatcher) {
            tasks.all = listOf(task(1, today))
            val vm = viewModel(TaskOverlaySetting.InMemory(enabled = true))
            vm.refresh()
            advanceUntilIdle()
            tasks.failure = CalendarResult.Offline
            vm.loadMore()
            advanceUntilIdle()

            vm.retryTasks()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state.tasksFailed)
            assertEquals(listOf(id(1)), state.tasks.map { it.id })
        }
}
