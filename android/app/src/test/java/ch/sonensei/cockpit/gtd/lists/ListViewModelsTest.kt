package ch.sonensei.cockpit.gtd.lists

import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.next.Completion
import ch.sonensei.cockpit.gtd.next.CompletionResult
import ch.sonensei.cockpit.gtd.next.TaskCompletionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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

@OptIn(ExperimentalCoroutinesApi::class)
class ListViewModelsTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 10, 20)
    private val recent = GtdTask(id(1), "Quote", TaskList.WAITING, 0, 1, waitingSince = today.minusDays(1))
    private val undated = GtdTask(id(2), "Parcel", TaskList.WAITING, 1, 1)
    private val old = GtdTask(id(3), "Contract", TaskList.WAITING, 2, 1, waitingSince = today.minusDays(9))
    private val italian = GtdTask(id(4), "Learn Italian", TaskList.SOMEDAY, 0, 1)

    private val waitingFlow = MutableStateFlow(Live(listOf(recent, undated, old)))
    private val somedayFlow = MutableStateFlow(Live(listOf(italian)))
    private val reloads = mutableListOf<Flow<Unit>>()
    private val repository =
        mockk<TaskListsRepository> {
            every { observe(TaskList.WAITING, capture(reloads)) } returns waitingFlow
            every { observe(TaskList.SOMEDAY, capture(reloads)) } returns somedayFlow
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

    private fun <V : LiveTaskListViewModel> test(
        create: () -> V,
        block: suspend TestScope.(V) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val viewModel = create()
        backgroundScope.launch { viewModel.uiState.collect {} }
        block(viewModel)
    }

    private fun waiting() = WaitingViewModel(repository, contexts, completion, clock)

    private fun someday() = SomedayViewModel(repository, contexts, completion, clock)

    @Test
    fun `waiting is sorted longest waiting first, undated last`() =
        test(::waiting) { vm ->
            assertEquals(listOf(old, recent, undated), vm.uiState.value.tasks)
            assertEquals(today, vm.uiState.value.today)
        }

    @Test
    fun `follow up creates the task and reports it`() =
        test(::waiting) { vm ->
            coEvery { repository.followUp(old, "Follow up with Anna: Contract") } returns ActionOutcome.DONE
            vm.followUp(old, "Follow up with Anna: Contract")
            coVerify { repository.followUp(old, "Follow up with Anna: Contract") }
            assertEquals(ActionSnackbar(ActionSnackbar.Kind.FOLLOW_UP, "Contract", ActionOutcome.DONE), vm.uiState.value.action)
            vm.dismissAction(vm.uiState.value.action!!)
            assertEquals(null, vm.uiState.value.action)
        }

    @Test
    fun `activate moves the task and reports a refusal`() =
        test(::someday) { vm ->
            coEvery { repository.activate(italian) } returns ActionOutcome.CONFLICT
            vm.activate(italian)
            assertEquals(
                ActionOutcome.CONFLICT,
                vm.uiState.value.action!!
                    .outcome,
            )
            assertTrue(
                vm.uiState.value.busy
                    .isEmpty(),
            )
        }

    @Test
    fun `completing hides the task at once`() =
        test(::someday) { vm ->
            vm.complete(italian)
            assertTrue(
                vm.uiState.value.tasks
                    .isEmpty(),
            )
        }

    @Test
    fun `nothing changes while the list is read-only`() =
        test(::someday) { vm ->
            somedayFlow.value = Live(listOf(italian), LoadError.OFFLINE)
            vm.activate(italian)
            vm.complete(italian)
            coVerify(exactly = 0) { repository.activate(any()) }
            coVerify(exactly = 0) { completion.complete(any()) }
            assertEquals(listOf(italian), vm.uiState.value.tasks)
        }

    @Test
    fun `a first load error is shown, not loading`() =
        test(::someday) { vm ->
            somedayFlow.value = Live(error = LoadError.FAILED)
            val state = vm.uiState.value
            assertEquals(false, state.loading)
            assertEquals(false, state.loaded)
            assertEquals(LoadError.FAILED, state.error)
        }

    /** Counts what the view model asks the repository to reload, from the reload flow it passed to `observe`. */
    private fun TestScope.countReloads(): () -> Int {
        var count = 0
        backgroundScope.launch { merge(*reloads.toTypedArray()).collect { count++ } }
        return { count }
    }

    @Test
    fun `waiting reloads once a completion got its answer`() =
        test(::waiting) { vm ->
            val reloaded = countReloads()
            vm.complete(old)
            assertTrue(reloaded() >= 1)
            assertEquals(listOf(recent, undated), vm.uiState.value.tasks)
        }

    @Test
    fun `a follow up runs once at a time, its row is busy meanwhile, then the list reloads`() =
        test(::waiting) { vm ->
            val answer = CompletableDeferred<ActionOutcome>()
            coEvery { repository.followUp(old, any()) } coAnswers { answer.await() }
            val reloaded = countReloads()
            vm.followUp(old, "Follow up with Anna: Contract")
            vm.followUp(old, "Follow up with Anna: Contract")
            assertEquals(setOf(old.id), vm.uiState.value.busy)
            assertNull(vm.uiState.value.action)
            assertEquals(0, reloaded())
            answer.complete(ActionOutcome.OFFLINE)
            coVerify(exactly = 1) { repository.followUp(old, any()) }
            assertTrue(
                vm.uiState.value.busy
                    .isEmpty(),
            )
            assertEquals(ActionSnackbar(ActionSnackbar.Kind.FOLLOW_UP, "Contract", ActionOutcome.OFFLINE), vm.uiState.value.action)
            assertEquals(1, reloaded())
        }

    @Test
    fun `retry asks for a reload`() =
        test(::someday) { vm ->
            val reloaded = countReloads()
            vm.retry()
            assertEquals(1, reloaded())
        }

    @Test
    fun `a failed reload keeps the shown list read-only`() =
        test(::waiting) { vm ->
            waitingFlow.value = Live(listOf(recent), LoadError.FAILED)
            val state = vm.uiState.value
            assertEquals(listOf(recent), state.tasks)
            assertEquals(true, state.loaded)
            assertEquals(true, state.readOnly)
            vm.followUp(recent, "Follow up: Quote")
            coVerify(exactly = 0) { repository.followUp(any(), any()) }
        }
}
