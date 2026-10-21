package ch.sonensei.cockpit.gtd.next

import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class NextViewModelTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val home = GtdContext(id(80), "@home", 0, 1)
    private val phone = GtdContext(id(81), "@phone", 1, 1)
    private val call =
        GtdTask(
            id(1),
            "Call the dentist",
            TaskList.NEXT,
            0,
            1,
            contextIds = listOf(phone.id),
            energy = Energy.LOW,
            creativity = Creativity.ROUTINE,
            timeEstimateMin = 5,
        )
    private val sketch =
        GtdTask(
            id(2),
            "Sketch the garden plan",
            TaskList.NEXT,
            1,
            1,
            contextIds = listOf(home.id),
            energy = Energy.HIGH,
            creativity = Creativity.CREATIVE,
            timeEstimateMin = 60,
        )
    private val water = GtdTask(id(3), "Water the plants", TaskList.NEXT, 2, 1, contextIds = listOf(home.id), energy = Energy.LOW)
    private val loadedAt = Instant.parse("2026-10-20T09:00:00Z")

    private val nextFlow = MutableStateFlow(Cached(listOf(call, sketch, water), offline = false, refreshedAt = loadedAt))
    private val contextFlow = MutableStateFlow(Cached(listOf(home, phone), offline = false, refreshedAt = loadedAt))
    private val tasks = mockk<TaskRepository> { every { observe(TaskView.NEXT) } returns nextFlow }
    private val contexts = mockk<ContextRepository> { every { observe() } returns contextFlow }
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

    private fun test(block: suspend (NextViewModel) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = NextViewModel(tasks, contexts, completion, clock)
            backgroundScope.launch { viewModel.uiState.collect {} }
            block(viewModel)
        }

    private val NextViewModel.shown get() = uiState.value.tasks

    @Test
    fun `shows every next action without a filter`() =
        test { vm ->
            assertEquals(listOf(call, sketch, water), vm.shown)
            assertEquals(3, vm.uiState.value.total)
            assertEquals(listOf(home, phone), vm.uiState.value.contexts)
        }

    @Test
    fun `filters by context`() =
        test { vm ->
            vm.toggleContext(home.id)
            assertEquals(listOf(sketch, water), vm.shown)
            assertEquals(3, vm.uiState.value.total)
            vm.toggleContext(phone.id)
            assertEquals(listOf(call), vm.shown)
            vm.toggleContext(phone.id)
            assertEquals(3, vm.shown.size)
        }

    @Test
    fun `filters by energy and creativity`() =
        test { vm ->
            vm.toggleEnergy(Energy.LOW)
            assertEquals(listOf(call, water), vm.shown)
            vm.toggleCreativity(Creativity.ROUTINE)
            // A task without a creativity does not match a creativity filter.
            assertEquals(listOf(call), vm.shown)
            vm.toggleEnergy(Energy.LOW)
            vm.toggleCreativity(Creativity.CREATIVE)
            assertEquals(listOf(sketch), vm.shown)
        }

    @Test
    fun `filters by maximum estimate, only matching estimated tasks`() =
        test { vm ->
            vm.toggleMaxMinutes(15)
            assertEquals(listOf(call), vm.shown)
            vm.toggleMaxMinutes(60)
            assertEquals(listOf(call, sketch), vm.shown)
        }

    @Test
    fun `combines filters and clears them`() =
        test { vm ->
            vm.toggleContext(home.id)
            vm.toggleEnergy(Energy.LOW)
            vm.toggleMaxMinutes(120)
            assertEquals(emptyList<GtdTask>(), vm.shown)
            assertEquals(3, vm.uiState.value.filter.activeCount)
            vm.clearFilter()
            assertEquals(NextFilter(), vm.uiState.value.filter)
            assertEquals(3, vm.shown.size)
        }

    @Test
    fun `drops the filter of a context deleted elsewhere`() =
        test { vm ->
            vm.toggleContext(phone.id)
            contextFlow.value = contextFlow.value.copy(data = listOf(home))
            assertNull(vm.uiState.value.filter.contextId)
            assertEquals(3, vm.shown.size)
        }

    @Test
    fun `completing hides the task and offers undo`() =
        test { vm ->
            vm.complete(call)
            coVerify { completion.complete(call) }
            assertEquals(listOf(sketch, water), vm.shown)
            val offer = vm.uiState.value.snackbar as CompletionSnackbar.Undo
            assertEquals(Completion(call.id, call.title, 2), offer.completion)

            vm.undo(offer)
            coVerify { completion.undo(Completion(call.id, call.title, 2)) }
            assertNull(vm.uiState.value.snackbar)
            assertEquals(listOf(call, sketch, water), vm.shown)
        }

    @Test
    fun `the task is hidden while the server answers and comes back when it refuses`() =
        test { vm ->
            val answer = CompletableDeferred<CompletionResult>()
            coEvery { completion.complete(call) } coAnswers { answer.await() }
            vm.complete(call)
            assertFalse(call in vm.shown)
            answer.complete(CompletionResult.NotDone(CompletionMessage.CONFLICT))
            assertTrue(call in vm.shown)
            assertEquals(CompletionSnackbar.Failed(CompletionMessage.CONFLICT), vm.uiState.value.snackbar)
        }

    @Test
    fun `a failed undo keeps the task hidden and says why`() =
        test { vm ->
            coEvery { completion.undo(any()) } returns CompletionResult.NotDone(CompletionMessage.OFFLINE)
            vm.complete(call)
            vm.undo(vm.uiState.value.snackbar as CompletionSnackbar.Undo)
            assertFalse(call in vm.shown)
            assertEquals(CompletionSnackbar.Failed(CompletionMessage.OFFLINE), vm.uiState.value.snackbar)
        }

    @Test
    fun `dismissing an older snackbar keeps the newer one`() =
        test { vm ->
            vm.complete(call)
            val first = vm.uiState.value.snackbar!!
            vm.complete(sketch)
            val second = vm.uiState.value.snackbar!!
            vm.dismiss(first)
            assertEquals(second, vm.uiState.value.snackbar)
            vm.dismiss(second)
            assertNull(vm.uiState.value.snackbar)
        }

    @Test
    fun `offline the list is read-only and completing does nothing`() =
        test { vm ->
            nextFlow.value = nextFlow.value.copy(offline = true)
            assertTrue(vm.uiState.value.offline)
            vm.complete(call)
            coVerify(exactly = 0) { completion.complete(any()) }
            assertEquals(3, vm.shown.size)
        }
}
