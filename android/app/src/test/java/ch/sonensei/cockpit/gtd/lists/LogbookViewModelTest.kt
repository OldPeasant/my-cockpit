package ch.sonensei.cockpit.gtd.lists

import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectRepository
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskList
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class LogbookViewModelTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val zone = ZoneId.of("Europe/Zurich")
    private val clock = Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), zone)

    private fun done(
        n: Int,
        at: String,
        projectId: UUID? = null,
        contexts: List<UUID> = emptyList(),
    ) = LoggedTask(GtdTask(id(n), "Task $n", TaskList.NEXT, n, 3, projectId = projectId, contextIds = contexts), Instant.parse(at))

    private val taxes = done(1, "2026-10-20T08:00:00Z", projectId = id(10), contexts = listOf(id(20)))
    private val call = done(2, "2026-10-19T21:30:00Z") // 23:30 in Zurich: still the 19th
    private val late = done(3, "2026-10-19T22:30:00Z") // 00:30 in Zurich: the 20th
    private val old = done(4, "2026-10-01T08:00:00Z")

    private val changes = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
    private val repository = mockk<LogbookRepository> { every { changes() } returns changes }
    private val projects =
        mockk<ProjectRepository> {
            every { observe() } returns
                MutableStateFlow(Cached(listOf(GtdProject(id(10), "Taxes", ProjectStatus.ACTIVE, false, 1)), false, null))
        }
    private val contexts =
        mockk<ContextRepository> {
            every { observe() } returns MutableStateFlow(Cached(listOf(GtdContext(id(20), "@desk", 0, 1)), false, null))
        }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun page(
        vararg tasks: LoggedTask,
        next: String? = null,
    ) = LogbookLoad.Loaded(LogbookPage(tasks.toList(), next))

    private fun test(block: suspend TestScope.(LogbookViewModel) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = LogbookViewModel(repository, projects, contexts, clock)
            backgroundScope.launch { viewModel.uiState.collect {} }
            block(viewModel)
        }

    @Test
    fun `lists done tasks newest first, grouped by local completion day, with projects and contexts`() {
        coEvery { repository.page(null) } returns page(taxes, late, call, old)
        test { vm ->
            val state = vm.uiState.value
            assertFalse(state.loading)
            assertTrue(state.loaded)
            assertEquals(listOf(taxes, late, call, old), state.tasks)
            assertEquals(
                listOf(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 1)),
                state.days.map { it.date },
            )
            assertEquals(listOf(listOf(taxes, late), listOf(call), listOf(old)), state.days.map { it.tasks })
            assertEquals(LocalDate.of(2026, 10, 20), state.today)
            assertEquals("Taxes", state.projects[id(10)])
            assertEquals("@desk", state.contexts[id(20)]?.name)
            assertFalse(state.canLoadMore)
        }
    }

    @Test
    fun `loads the next page on request and appends it`() {
        coEvery { repository.page(null) } returns page(taxes, next = "c2")
        coEvery { repository.page("c2") } returns page(call, old)
        test { vm ->
            assertTrue(vm.uiState.value.canLoadMore)
            vm.loadMore()
            assertEquals(listOf(taxes, call, old), vm.uiState.value.tasks)
            assertFalse(vm.uiState.value.canLoadMore)
            vm.loadMore()
            coVerify(exactly = 1) { repository.page("c2") }
        }
    }

    @Test
    fun `a failed next page is reported and can be retried`() {
        coEvery { repository.page(null) } returns page(taxes, next = "c2")
        coEvery { repository.page("c2") } returnsMany listOf(LogbookLoad.NotLoaded(LoadError.OFFLINE), page(call))
        test { vm ->
            vm.loadMore()
            assertEquals(LoadError.OFFLINE, vm.uiState.value.moreError)
            assertEquals(listOf(taxes), vm.uiState.value.tasks)
            assertTrue(vm.uiState.value.canLoadMore)
            vm.loadMore()
            assertNull(vm.uiState.value.moreError)
            assertEquals(listOf(taxes, call), vm.uiState.value.tasks)
        }
    }

    @Test
    fun `an empty logbook is loaded and empty`() {
        coEvery { repository.page(null) } returns page()
        test { vm ->
            assertTrue(vm.uiState.value.loaded)
            assertTrue(
                vm.uiState.value.tasks
                    .isEmpty(),
            )
            assertFalse(vm.uiState.value.loading)
        }
    }

    @Test
    fun `a failed first load is an error and retry loads`() {
        coEvery { repository.page(null) } returnsMany listOf(LogbookLoad.NotLoaded(LoadError.FAILED), page(taxes))
        test { vm ->
            assertEquals(LoadError.FAILED, vm.uiState.value.error)
            assertFalse(vm.uiState.value.loaded)
            assertFalse(vm.uiState.value.loading)
            vm.retry()
            assertNull(vm.uiState.value.error)
            assertEquals(listOf(taxes), vm.uiState.value.tasks)
        }
    }

    @Test
    fun `a failed reload keeps the list read-only`() {
        coEvery { repository.page(null) } returnsMany listOf(page(taxes), LogbookLoad.NotLoaded(LoadError.OFFLINE))
        test { vm ->
            changes.emit(Unit)
            assertEquals(listOf(taxes), vm.uiState.value.tasks)
            assertEquals(LoadError.OFFLINE, vm.uiState.value.error)
            assertTrue(vm.uiState.value.readOnly)
            vm.reopen(taxes)
            coVerify(exactly = 0) { repository.reopen(any()) }
        }
    }

    @Test
    fun `a reload after a change keeps as many tasks as were loaded`() {
        coEvery { repository.page(null) } returnsMany listOf(page(taxes, next = "c2"), page(late, taxes, next = "c2"))
        coEvery { repository.page("c2") } returnsMany listOf(page(call, next = "c3"), page(call, next = "c3"))
        test { vm ->
            vm.loadMore()
            assertEquals(listOf(taxes, call), vm.uiState.value.tasks)
            changes.emit(Unit)
            assertEquals(listOf(late, taxes, call), vm.uiState.value.tasks)
            assertTrue(vm.uiState.value.canLoadMore)
            coVerify(exactly = 2) { repository.page("c2") }
        }
    }

    @Test
    fun `reopen removes the task, reports it and reloads`() {
        val answer = CompletableDeferred<ActionOutcome>()
        coEvery { repository.page(null) } returnsMany listOf(page(taxes, call), page(call))
        coEvery { repository.reopen(taxes) } coAnswers { answer.await() }
        test { vm ->
            vm.open(taxes)
            vm.reopen(taxes)
            assertEquals(setOf(taxes.task.id), vm.uiState.value.busy)
            vm.reopen(taxes)
            answer.complete(ActionOutcome.DONE)
            coVerify(exactly = 1) { repository.reopen(taxes) }
            val state = vm.uiState.value
            assertEquals(listOf(call), state.tasks)
            assertTrue(state.busy.isEmpty())
            assertNull(state.opened)
            assertEquals(ActionSnackbar(ActionSnackbar.Kind.REOPENED, "Task 1", ActionOutcome.DONE), state.action)
            coVerify(exactly = 2) { repository.page(null) }
            vm.dismissAction(state.action!!)
            assertNull(vm.uiState.value.action)
        }
    }

    @Test
    fun `a refused reopen keeps the task and reports why`() {
        coEvery { repository.page(null) } returns page(taxes)
        coEvery { repository.reopen(taxes) } returns ActionOutcome.OFFLINE
        test { vm ->
            vm.open(taxes)
            vm.reopen(taxes)
            assertEquals(listOf(taxes), vm.uiState.value.tasks)
            assertEquals(taxes, vm.uiState.value.opened)
            assertEquals(
                ActionOutcome.OFFLINE,
                vm.uiState.value.action
                    ?.outcome,
            )
            vm.close()
            assertNull(vm.uiState.value.opened)
        }
    }
}
