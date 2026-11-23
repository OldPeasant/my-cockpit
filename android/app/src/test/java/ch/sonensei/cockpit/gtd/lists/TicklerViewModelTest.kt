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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The Tickler view model: tasks in a tickler from the repository, earliest return date first, with completion and reload. */
@OptIn(ExperimentalCoroutinesApi::class)
class TicklerViewModelTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 11, 23)
    private val passport = GtdTask(id(1), "Renew passport", TaskList.INBOX, 0, 1, ticklerDate = today.plusDays(21))
    private val trip = GtdTask(id(2), "Plan summer trip", TaskList.SOMEDAY, 1, 1, ticklerDate = today.plusDays(1))
    private val tyres = GtdTask(id(3), "Change winter tyres", TaskList.NEXT, 2, 1, ticklerDate = today.plusDays(21))

    private val ticklerFlow = MutableStateFlow(Live(listOf(passport, tyres, trip)))
    private val reloads = mutableListOf<Flow<Unit>>()
    private val todays = mutableListOf<() -> LocalDate>()
    private val repository =
        mockk<TaskListsRepository> {
            every { observeTickler(capture(todays), capture(reloads)) } returns ticklerFlow
        }
    private val contexts = mockk<ContextRepository> { every { observe() } returns MutableStateFlow(Cached(emptyList(), false, null)) }
    private val completion = mockk<TaskCompletionRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneOffset.UTC)

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

    private fun test(block: suspend TestScope.(TicklerViewModel) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = TicklerViewModel(repository, contexts, completion, clock)
            backgroundScope.launch { viewModel.uiState.collect {} }
            block(viewModel)
        }

    private fun TestScope.countReloads(): () -> Int {
        var count = 0
        backgroundScope.launch { merge(*reloads.toTypedArray()).collect { count++ } }
        return { count }
    }

    @Test
    fun `observes the tickler with the clock's today, earliest return date first, ties in list order`() =
        test { vm ->
            verify { repository.observeTickler(any(), any()) }
            assertEquals(today, todays.single()())
            val state = vm.uiState.value
            assertEquals(listOf(trip, passport, tyres), state.tasks)
            assertEquals(today, state.today)
            assertEquals(true, state.loaded)
        }

    @Test
    fun `tickler order sorts by date, then position, undated last`() {
        val undated = GtdTask(id(4), "No date", TaskList.INBOX, 0, 1)
        assertEquals(listOf(trip, passport, tyres, undated), byTicklerDate(listOf(undated, tyres, passport, trip)))
        assertEquals(emptyList<GtdTask>(), byTicklerDate(emptyList()))
    }

    @Test
    fun `completing hides the task at once and offers undo`() =
        test { vm ->
            vm.complete(trip)
            assertEquals(listOf(passport, tyres), vm.uiState.value.tasks)
            val offer = vm.uiState.value.completion as CompletionSnackbar.Undo
            assertEquals(trip.id, offer.completion.taskId)
            coVerify(exactly = 1) { completion.complete(trip) }
        }

    @Test
    fun `reloads once a completion got its answer, and retry reloads`() =
        test { vm ->
            val reloaded = countReloads()
            vm.complete(passport)
            val afterComplete = reloaded()
            assertTrue(afterComplete >= 1)
            vm.retry()
            assertEquals(afterComplete + 1, reloaded())
        }

    @Test
    fun `a reload after the date changed in the detail shows the new order`() =
        test { vm ->
            val moved = tyres.copy(ticklerDate = today.plusDays(2), version = 2)
            ticklerFlow.value = Live(listOf(passport, moved, trip))
            assertEquals(listOf(trip, moved, passport), vm.uiState.value.tasks)
            ticklerFlow.value = Live(listOf(passport, trip))
            assertEquals(listOf(trip, passport), vm.uiState.value.tasks)
        }

    @Test
    fun `offline after a load keeps the list read-only, nothing is completed`() =
        test { vm ->
            ticklerFlow.value = Live(listOf(trip), LoadError.OFFLINE)
            assertEquals(true, vm.uiState.value.readOnly)
            vm.complete(trip)
            coVerify(exactly = 0) { completion.complete(any()) }
            assertEquals(listOf(trip), vm.uiState.value.tasks)
        }

    @Test
    fun `a first load error is shown, not loading`() =
        test { vm ->
            ticklerFlow.value = Live(error = LoadError.FAILED)
            val state = vm.uiState.value
            assertEquals(false, state.loading)
            assertEquals(LoadError.FAILED, state.error)
            assertTrue(state.tasks.isEmpty())
        }
}
