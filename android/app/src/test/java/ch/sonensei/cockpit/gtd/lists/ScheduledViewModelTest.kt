package ch.sonensei.cockpit.gtd.lists

import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.next.Completion
import ch.sonensei.cockpit.gtd.next.CompletionResult
import ch.sonensei.cockpit.gtd.next.CompletionSnackbar
import ch.sonensei.cockpit.gtd.next.TaskCompletionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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

/** The Scheduled view model: the Scheduled list from the repository, earliest date first, with completion and reload. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledViewModelTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 10, 20)
    private val later = GtdTask(id(1), "Renew library card", TaskList.SCHEDULED, 0, 1, scheduledDate = today.plusDays(5))
    private val undated = GtdTask(id(2), "Sort old photos", TaskList.SCHEDULED, 1, 1)
    private val soon = GtdTask(id(3), "Book bike service", TaskList.SCHEDULED, 2, 1, scheduledDate = today)
    private val sameDayFirst = GtdTask(id(4), "Buy stamps", TaskList.SCHEDULED, 3, 1, scheduledDate = today.plusDays(5))

    private val scheduledFlow = MutableStateFlow(Live(listOf(later, undated, soon, sameDayFirst)))
    private val reloads = mutableListOf<Flow<Unit>>()
    private val repository =
        mockk<TaskListsRepository> {
            every { observe(TaskList.SCHEDULED, capture(reloads)) } returns scheduledFlow
        }
    private val contexts = mockk<ContextRepository> { every { observe() } returns MutableStateFlow(Cached(emptyList(), false, null)) }
    private val completion = mockk<TaskCompletionRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { completion.complete(any()) } answers {
            val task = firstArg<GtdTask>()
            CompletionResult.Completed(Completion(task.id, task.title, task.version + 1))
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.(ScheduledViewModel) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = ScheduledViewModel(repository, contexts, completion, clock)
            backgroundScope.launch { viewModel.uiState.collect {} }
            block(viewModel)
        }

    /** Counts what the view model asks the repository to reload, from the reload flow it passed to `observe`. */
    private fun TestScope.countReloads(): () -> Int {
        var count = 0
        backgroundScope.launch { merge(*reloads.toTypedArray()).collect { count++ } }
        return { count }
    }

    @Test
    fun `observes the Scheduled list, earliest date first, ties in list order, undated last`() =
        test { vm ->
            verify { repository.observe(TaskList.SCHEDULED, any()) }
            val state = vm.uiState.value
            assertEquals(listOf(soon, later, sameDayFirst, undated), state.tasks)
            assertEquals(today, state.today)
            assertEquals(true, state.loaded)
        }

    @Test
    fun `ties on the same date keep list position, not arrival order`() {
        val first = GtdTask(id(5), "Water plants", TaskList.SCHEDULED, 0, 1, scheduledDate = today)
        val second = GtdTask(id(6), "Pay rent", TaskList.SCHEDULED, 1, 1, scheduledDate = today)
        assertEquals(listOf(first, second), byScheduledDate(listOf(second, first)))
        assertEquals(emptyList<GtdTask>(), byScheduledDate(emptyList()))
    }

    @Test
    fun `completing hides the task at once and offers undo`() =
        test { vm ->
            vm.complete(soon)
            assertEquals(listOf(later, sameDayFirst, undated), vm.uiState.value.tasks)
            val offer = vm.uiState.value.completion as CompletionSnackbar.Undo
            assertEquals(soon.id, offer.completion.taskId)
            coVerify(exactly = 1) { completion.complete(soon) }
        }

    @Test
    fun `undoing a completion shows the task again in its date order`() =
        test { vm ->
            coEvery { completion.undo(any()) } returns CompletionResult.Reopened
            vm.complete(soon)
            val offer = vm.uiState.value.completion as CompletionSnackbar.Undo
            vm.undo(offer)
            coVerify { completion.undo(offer.completion) }
            assertEquals(listOf(soon, later, sameDayFirst, undated), vm.uiState.value.tasks)
            assertNull(vm.uiState.value.completion)
        }

    @Test
    fun `reloads once a completion got its answer`() =
        test { vm ->
            val reloaded = countReloads()
            vm.complete(later)
            assertTrue(reloaded() >= 1)
            assertEquals(listOf(soon, sameDayFirst, undated), vm.uiState.value.tasks)
        }

    @Test
    fun `retry asks for a reload`() =
        test { vm ->
            val reloaded = countReloads()
            vm.retry()
            assertEquals(1, reloaded())
        }

    @Test
    fun `a reload with new data is shown in date order`() =
        test { vm ->
            val moved = later.copy(scheduledDate = today.minusDays(1), version = 2)
            scheduledFlow.value = Live(listOf(moved, soon))
            assertEquals(listOf(moved, soon), vm.uiState.value.tasks)
        }

    @Test
    fun `nothing is completed while the list is read-only`() =
        test { vm ->
            scheduledFlow.value = Live(listOf(soon), LoadError.OFFLINE)
            assertEquals(true, vm.uiState.value.readOnly)
            vm.complete(soon)
            coVerify(exactly = 0) { completion.complete(any()) }
            assertEquals(listOf(soon), vm.uiState.value.tasks)
        }

    @Test
    fun `a first load error is shown, not loading`() =
        test { vm ->
            scheduledFlow.value = Live(error = LoadError.FAILED)
            val state = vm.uiState.value
            assertEquals(false, state.loading)
            assertEquals(false, state.loaded)
            assertEquals(LoadError.FAILED, state.error)
            assertTrue(state.tasks.isEmpty())
        }
}
