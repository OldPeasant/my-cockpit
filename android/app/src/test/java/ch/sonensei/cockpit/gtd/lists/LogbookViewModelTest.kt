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

    /** Answers `repository.page(cursor)` from [answers] in order, each run when the request comes (it may suspend). */
    private fun pages(
        cursor: String?,
        vararg answers: suspend () -> LogbookLoad,
    ) {
        val queue = ArrayDeque(answers.toList())
        coEvery { repository.page(cursor) } coAnswers { queue.removeFirst()() }
    }

    @Test
    fun `a task on two pages is listed once when the next page is loaded`() {
        coEvery { repository.page(null) } returns page(taxes, call, next = "c2")
        coEvery { repository.page("c2") } returns page(call, old)
        test { vm ->
            vm.loadMore()
            assertEquals(listOf(taxes, call, old), vm.uiState.value.tasks)
        }
    }

    @Test
    fun `a reload over several pages lists a task that moved to the next page once`() {
        pages(null, { page(taxes, next = "c2") }, { page(late, taxes, next = "c2") })
        pages("c2", { page(call, next = "c3") }, { page(taxes, call, next = "c3") })
        test { vm ->
            vm.loadMore()
            changes.emit(Unit)
            assertEquals(listOf(late, taxes, call), vm.uiState.value.tasks)
            assertTrue(vm.uiState.value.canLoadMore)
        }
    }

    @Test
    fun `a reload stops at the last page even when more pages were shown before`() {
        pages(null, { page(taxes, next = "c2") }, { page(late, taxes) })
        coEvery { repository.page("c2") } returns page(call, next = "c3")
        test { vm ->
            vm.loadMore()
            changes.emit(Unit)
            assertEquals(listOf(late, taxes), vm.uiState.value.tasks)
            assertFalse(vm.uiState.value.canLoadMore)
            vm.loadMore()
            coVerify(exactly = 1) { repository.page("c2") }
        }
    }

    @Test
    fun `a reload that fails on a later page keeps the whole list that was shown`() {
        pages(null, { page(taxes, next = "c2") }, { page(late, next = "c2") })
        pages("c2", { page(call, next = "c3") }, { LogbookLoad.NotLoaded(LoadError.FAILED) })
        test { vm ->
            vm.loadMore()
            changes.emit(Unit)
            val state = vm.uiState.value
            assertEquals(listOf(taxes, call), state.tasks)
            assertEquals(LoadError.FAILED, state.error)
            assertTrue(state.readOnly)
            assertFalse(state.loading)
        }
    }

    @Test
    fun `only one next page is requested at a time`() {
        val answer = CompletableDeferred<LogbookLoad>()
        coEvery { repository.page(null) } returns page(taxes, next = "c2")
        coEvery { repository.page("c2") } coAnswers { answer.await() }
        test { vm ->
            vm.loadMore()
            assertTrue(vm.uiState.value.loadingMore)
            vm.loadMore()
            answer.complete(page(call))
            coVerify(exactly = 1) { repository.page("c2") }
            assertFalse(vm.uiState.value.loadingMore)
            assertEquals(listOf(taxes, call), vm.uiState.value.tasks)
        }
    }

    @Test
    fun `no next page is requested while the first page loads`() {
        val first = CompletableDeferred<LogbookLoad>()
        coEvery { repository.page(null) } coAnswers { first.await() }
        test { vm ->
            assertTrue(vm.uiState.value.loading)
            vm.loadMore()
            first.complete(page(taxes, next = "c2"))
            coVerify(exactly = 0) { repository.page("c2") }
            assertFalse(vm.uiState.value.loadingMore)
        }
    }

    @Test
    fun `a change while the next page loads cancels it, so its late answer is not appended`() {
        val slow = CompletableDeferred<LogbookLoad>()
        pages(null, { page(taxes, next = "c2") }, { page(late, taxes, next = "c2") })
        pages("c2", { slow.await() }, { page(old) })
        test { vm ->
            vm.loadMore()
            assertTrue(vm.uiState.value.loadingMore)
            changes.emit(Unit)
            slow.complete(page(call))
            val state = vm.uiState.value
            assertEquals(listOf(late, taxes), state.tasks)
            assertFalse(state.loadingMore)
            assertTrue(state.canLoadMore)
            vm.loadMore()
            assertEquals(listOf(late, taxes, old), vm.uiState.value.tasks)
        }
    }

    @Test
    fun `a next page answering after a running reload is appended once`() {
        val reload = CompletableDeferred<LogbookLoad>()
        val more = CompletableDeferred<LogbookLoad>()
        pages(null, { page(taxes, next = "c2") }, { reload.await() })
        coEvery { repository.page("c2") } coAnswers { more.await() }
        test { vm ->
            changes.emit(Unit)
            vm.loadMore()
            reload.complete(page(late, taxes, next = "c2"))
            assertEquals(listOf(late, taxes), vm.uiState.value.tasks)
            more.complete(page(taxes, call))
            val state = vm.uiState.value
            assertEquals(listOf(late, taxes, call), state.tasks)
            assertFalse(state.loadingMore)
            assertFalse(state.canLoadMore)
        }
    }

    @Test
    fun `a reload answering after a next page leaves a consistent list that can load more again`() {
        val reload = CompletableDeferred<LogbookLoad>()
        val more = CompletableDeferred<LogbookLoad>()
        pages(null, { page(taxes, next = "c2") }, { reload.await() })
        pages("c2", { more.await() }, { page(call) })
        test { vm ->
            changes.emit(Unit)
            vm.loadMore()
            more.complete(page(call, next = "c3"))
            reload.complete(page(late, taxes, next = "c2"))
            val state = vm.uiState.value
            assertEquals(listOf(late, taxes), state.tasks)
            assertFalse(state.loadingMore)
            assertTrue(state.canLoadMore)
            vm.loadMore()
            assertEquals(listOf(late, taxes, call), vm.uiState.value.tasks)
        }
    }

    @Test
    fun `an older reload answering last does not replace a newer one`() {
        val older = CompletableDeferred<LogbookLoad>()
        val newer = CompletableDeferred<LogbookLoad>()
        pages(null, { page(taxes) }, { older.await() }, { newer.await() })
        test { vm ->
            changes.emit(Unit)
            changes.emit(Unit)
            newer.complete(page(late, taxes))
            older.complete(page(old))
            assertEquals(listOf(late, taxes), vm.uiState.value.tasks)
        }
    }

    @Test
    fun `retry after a failed first load shows the spinner again, without the error`() {
        val second = CompletableDeferred<LogbookLoad>()
        pages(null, { LogbookLoad.NotLoaded(LoadError.OFFLINE) }, { second.await() })
        test { vm ->
            assertFalse(vm.uiState.value.loading)
            vm.retry()
            assertTrue(vm.uiState.value.loading)
            assertNull(vm.uiState.value.error)
            second.complete(LogbookLoad.NotLoaded(LoadError.FAILED))
            assertFalse(vm.uiState.value.loading)
            assertEquals(LoadError.FAILED, vm.uiState.value.error)
            assertFalse(vm.uiState.value.loaded)
        }
    }

    @Test
    fun `a reload clears the error of a failed next page`() {
        coEvery { repository.page(null) } returns page(taxes, next = "c2")
        coEvery { repository.page("c2") } returns LogbookLoad.NotLoaded(LoadError.FAILED)
        test { vm ->
            vm.loadMore()
            assertEquals(LoadError.FAILED, vm.uiState.value.moreError)
            changes.emit(Unit)
            assertNull(vm.uiState.value.moreError)
        }
    }

    @Test
    fun `a reload closes the opened task when it left the logbook, and refreshes it when it is still there`() {
        val renamed = LoggedTask(taxes.task.copy(title = "File taxes (renamed)", version = 4), taxes.completedAt)
        pages(null, { page(taxes, call) }, { page(renamed, call) }, { page(renamed) })
        test { vm ->
            vm.open(taxes)
            changes.emit(Unit)
            assertEquals(renamed, vm.uiState.value.opened)
            vm.open(call)
            changes.emit(Unit)
            assertNull(vm.uiState.value.opened)
        }
    }

    @Test
    fun `a conflicting reopen keeps the task, reports the conflict and reloads`() {
        pages(null, { page(taxes, call) }, { page(call) })
        coEvery { repository.reopen(taxes) } returns ActionOutcome.CONFLICT
        test { vm ->
            vm.open(taxes)
            vm.reopen(taxes)
            val state = vm.uiState.value
            assertEquals(ActionSnackbar(ActionSnackbar.Kind.REOPENED, "Task 1", ActionOutcome.CONFLICT), state.action)
            coVerify(exactly = 2) { repository.page(null) }
            // The reload shows the task was already reopened elsewhere: it leaves the list and its dialog closes.
            assertEquals(listOf(call), state.tasks)
            assertNull(state.opened)
            assertTrue(state.busy.isEmpty())
        }
    }

    @Test
    fun `a failed reopen keeps the task and does not reload`() {
        coEvery { repository.page(null) } returns page(taxes)
        coEvery { repository.reopen(taxes) } returns ActionOutcome.FAILED
        test { vm ->
            vm.reopen(taxes)
            assertEquals(listOf(taxes), vm.uiState.value.tasks)
            assertEquals(
                ActionOutcome.FAILED,
                vm.uiState.value.action
                    ?.outcome,
            )
            assertTrue(
                vm.uiState.value.busy
                    .isEmpty(),
            )
            coVerify(exactly = 1) { repository.page(null) }
        }
    }

    @Test
    fun `reopening one task keeps another task's dialog open`() {
        pages(null, { page(taxes, call) }, { page(taxes) })
        coEvery { repository.reopen(call) } returns ActionOutcome.DONE
        test { vm ->
            vm.open(taxes)
            vm.reopen(call)
            assertEquals(listOf(taxes), vm.uiState.value.tasks)
            assertEquals(taxes, vm.uiState.value.opened)
        }
    }

    @Test
    fun `the screen callbacks reach the view model`() {
        coEvery { repository.page(null) } returns page(taxes, call, next = "c2")
        coEvery { repository.page("c2") } returns page(old)
        coEvery { repository.reopen(call) } returns ActionOutcome.OFFLINE
        test { vm ->
            val callbacks = LogbookCallbacks.of(vm)
            callbacks.open(taxes)
            assertEquals(taxes, vm.uiState.value.opened)
            callbacks.close()
            assertNull(vm.uiState.value.opened)
            callbacks.loadMore()
            assertEquals(listOf(taxes, call, old), vm.uiState.value.tasks)
            callbacks.reopen(call)
            coVerify(exactly = 1) { repository.reopen(call) }
            callbacks.dismissAction(vm.uiState.value.action!!)
            assertNull(vm.uiState.value.action)
            callbacks.retry()
            coVerify(exactly = 2) { repository.page(null) }
        }
    }

    @Test
    fun `dismissing an older snackbar keeps the newer one`() {
        coEvery { repository.page(null) } returns page(taxes, call)
        coEvery { repository.reopen(any()) } returns ActionOutcome.OFFLINE
        test { vm ->
            vm.reopen(taxes)
            val first = vm.uiState.value.action!!
            vm.reopen(call)
            vm.dismissAction(first)
            assertEquals(
                "Task 2",
                vm.uiState.value.action
                    ?.title,
            )
        }
    }
}
