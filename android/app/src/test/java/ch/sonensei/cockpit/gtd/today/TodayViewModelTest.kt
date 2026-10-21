package ch.sonensei.cockpit.gtd.today

import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.gtd.next.Completion
import ch.sonensei.cockpit.gtd.next.CompletionResult
import ch.sonensei.cockpit.gtd.next.CompletionSnackbar
import ch.sonensei.cockpit.gtd.next.TaskCompletionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 10, 20)
    private val overdue = GtdTask(id(1), "Pay the electricity bill", TaskList.NEXT, 0, 1, dueDate = today.minusDays(2))
    private val due = GtdTask(id(2), "Return library books", TaskList.WAITING, 1, 1, dueDate = today, dueTime = "17:00:00")
    private val scheduled = GtdTask(id(3), "Book the train", TaskList.NEXT, 2, 1, scheduledDate = today)
    private val scheduledEarlier = GtdTask(id(4), "Renew the parking permit", TaskList.NEXT, 3, 1, scheduledDate = today.minusDays(1))
    private val undated = GtdTask(id(5), "Sort the photos", TaskList.NEXT, 4, 1)
    private val later = GtdTask(id(6), "Plan the trip", TaskList.NEXT, 5, 1, dueDate = today.plusDays(3), scheduledDate = today)
    private val loadedAt = Instant.parse("2026-10-20T09:00:00Z")

    private val todayFlow = MutableStateFlow(Cached(listOf(overdue, due), offline = false, refreshedAt = loadedAt))
    private val nextFlow =
        MutableStateFlow(Cached(listOf(overdue, scheduled, scheduledEarlier, undated, later), offline = false, refreshedAt = loadedAt))
    private val tasks =
        mockk<TaskRepository> {
            every { observe(TaskView.TODAY) } returns todayFlow
            every { observe(TaskView.NEXT) } returns nextFlow
        }
    private val contexts = mockk<ContextRepository> { every { observe() } returns MutableStateFlow(Cached(emptyList(), false, loadedAt)) }
    private val completion = mockk<TaskCompletionRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { completion.complete(any()) } answers {
            val task = firstArg<GtdTask>()
            CompletionResult.Completed(Completion(task.id, task.title, task.version + 1))
        }
        coEvery { completion.undo(any()) } returns CompletionResult.Reopened
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend (TodayViewModel) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = TodayViewModel(tasks, contexts, completion, clock)
            backgroundScope.launch { viewModel.uiState.collect {} }
            block(viewModel)
        }

    private fun TodayViewModel.sections() = uiState.value.sections.associate { it.kind to it.tasks }

    @Test
    fun `groups overdue, due today and scheduled tasks, each task once`() =
        test { vm ->
            assertEquals(
                mapOf(
                    TodaySectionKind.OVERDUE to listOf(overdue),
                    TodaySectionKind.DUE to listOf(due),
                    TodaySectionKind.SCHEDULED to listOf(scheduled, scheduledEarlier, later),
                ),
                vm.sections(),
            )
            assertEquals(
                TodaySectionKind.entries,
                vm.uiState.value.sections
                    .map { it.kind },
            )
            assertEquals(today, vm.uiState.value.today)
        }

    @Test
    fun `leaves out empty sections`() =
        test { vm ->
            todayFlow.value = todayFlow.value.copy(data = emptyList())
            nextFlow.value = nextFlow.value.copy(data = listOf(undated))
            assertEquals(emptyList<TodaySection>(), vm.uiState.value.sections)
            assertTrue(!vm.uiState.value.loading)
        }

    @Test
    fun `is loading until both views were loaded`() =
        test { vm ->
            nextFlow.value = Cached(emptyList(), offline = false, refreshedAt = null)
            todayFlow.value = Cached(emptyList(), offline = false, refreshedAt = null)
            assertTrue(vm.uiState.value.loading)
            assertNull(vm.uiState.value.refreshedAt)
        }

    @Test
    fun `completing hides the task and offers undo`() =
        test { vm ->
            vm.complete(overdue)
            coVerify { completion.complete(overdue) }
            assertNull(vm.sections()[TodaySectionKind.OVERDUE])
            val offer = vm.uiState.value.snackbar as CompletionSnackbar.Undo
            vm.undo(offer)
            coVerify { completion.undo(offer.completion) }
            assertEquals(listOf(overdue), vm.sections()[TodaySectionKind.OVERDUE])
        }

    @Test
    fun `offline in either view the lists are read-only`() =
        test { vm ->
            todayFlow.value = todayFlow.value.copy(offline = true)
            assertTrue(vm.uiState.value.offline)
            vm.complete(due)
            coVerify(exactly = 0) { completion.complete(any()) }
        }

    @Test
    fun `sections follow the date rules`() {
        assertEquals(TodaySectionKind.OVERDUE, sectionOf(overdue.copy(scheduledDate = today), today))
        assertEquals(TodaySectionKind.DUE, sectionOf(due, today))
        assertEquals(TodaySectionKind.SCHEDULED, sectionOf(scheduledEarlier, today))
        assertNull(sectionOf(undated, today))
        assertNull(sectionOf(undated.copy(scheduledDate = today.plusDays(1)), today))
        assertNull(sectionOf(undated.copy(dueDate = today.plusDays(1)), today))
    }
}
