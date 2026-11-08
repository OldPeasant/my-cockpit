package ch.sonensei.cockpit.dashboard

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<DashboardRepository>()

    private val review = WidgetData.WeeklyReview(daysSinceLastReview = 3, openStep = null)
    private val inbox = WidgetData.Inbox(Count(2, more = false), Count(1, more = false))

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `before the first load every widget shows in the default order, loading`() {
        val vm = DashboardViewModel(repo)
        assertEquals(WidgetId.entries, vm.uiState.value.widgets)
        WidgetId.entries.forEach { assertEquals(WidgetState(), vm.uiState.value.state(it)) }
    }

    @Test
    fun `the stored layout decides which widgets load and in which order`() =
        runTest(dispatcher) {
            coEvery { repo.layout() } returns DashboardResult.Ok(layoutOf(WidgetId.WEEKLY_REVIEW, WidgetId.INBOX))
            coEvery { repo.load(WidgetId.WEEKLY_REVIEW) } returns DashboardResult.Ok(review)
            coEvery { repo.load(WidgetId.INBOX) } returns DashboardResult.Ok(inbox)
            val vm = DashboardViewModel(repo)

            vm.refresh()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(listOf(WidgetId.WEEKLY_REVIEW, WidgetId.INBOX), state.widgets)
            assertEquals(WidgetState(review, loading = false), state.state(WidgetId.WEEKLY_REVIEW))
            assertEquals(WidgetState(inbox, loading = false), state.state(WidgetId.INBOX))
            assertEquals(setOf(WidgetId.WEEKLY_REVIEW, WidgetId.INBOX), state.states.keys)
            coVerify(exactly = 0) { repo.load(WidgetId.TODAY) }
        }

    @Test
    fun `without a readable layout all widgets load in the default order`() =
        runTest(dispatcher) {
            coEvery { repo.layout() } returns DashboardResult.Offline
            coEvery { repo.load(any()) } returns DashboardResult.Ok(review)
            val vm = DashboardViewModel(repo)

            vm.refresh()
            advanceUntilIdle()

            assertEquals(WidgetId.entries, vm.uiState.value.widgets)
            WidgetId.entries.forEach { coVerify(exactly = 1) { repo.load(it) } }
        }

    @Test
    fun `one failing widget shows its error while the others show their data`() =
        runTest(dispatcher) {
            coEvery { repo.layout() } returns DashboardResult.Ok(layoutOf(WidgetId.INBOX, WidgetId.WEEKLY_REVIEW, WidgetId.TODAY))
            coEvery { repo.load(WidgetId.INBOX) } returns DashboardResult.Failed
            coEvery { repo.load(WidgetId.WEEKLY_REVIEW) } returns DashboardResult.Ok(review)
            coEvery { repo.load(WidgetId.TODAY) } returns DashboardResult.Offline
            val vm = DashboardViewModel(repo)

            vm.refresh()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(WidgetState(null, loading = false, error = WidgetError.FAILED), state.state(WidgetId.INBOX))
            assertEquals(WidgetState(null, loading = false, error = WidgetError.OFFLINE), state.state(WidgetId.TODAY))
            assertEquals(WidgetState(review, loading = false), state.state(WidgetId.WEEKLY_REVIEW))
        }

    @Test
    fun `pull to refresh reloads every widget, keeps data on a failure and clears an error on success`() =
        runTest(dispatcher) {
            coEvery { repo.layout() } returns DashboardResult.Ok(layoutOf(WidgetId.INBOX, WidgetId.WEEKLY_REVIEW))
            coEvery { repo.load(WidgetId.INBOX) } returnsMany listOf(DashboardResult.Ok(inbox), DashboardResult.Offline)
            coEvery { repo.load(WidgetId.WEEKLY_REVIEW) } returnsMany listOf(DashboardResult.Failed, DashboardResult.Ok(review))
            val vm = DashboardViewModel(repo)
            vm.refresh()
            advanceUntilIdle()

            vm.refresh(pulled = true)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.refreshing)
            assertEquals(WidgetState(inbox, loading = false, error = WidgetError.OFFLINE), state.state(WidgetId.INBOX))
            assertEquals(WidgetState(review, loading = false), state.state(WidgetId.WEEKLY_REVIEW))
            coVerify(exactly = 2) { repo.layout() }
            coVerify(exactly = 2) { repo.load(WidgetId.INBOX) }
            coVerify(exactly = 2) { repo.load(WidgetId.WEEKLY_REVIEW) }
        }

    @Test
    fun `a slow widget does not hold back the others`() =
        runTest(dispatcher) {
            val slow = CompletableDeferred<DashboardResult<WidgetData>>()
            coEvery { repo.layout() } returns DashboardResult.Ok(layoutOf(WidgetId.INBOX, WidgetId.WEEKLY_REVIEW))
            coEvery { repo.load(WidgetId.INBOX) } coAnswers { slow.await() }
            coEvery { repo.load(WidgetId.WEEKLY_REVIEW) } returns DashboardResult.Ok(review)
            val vm = DashboardViewModel(repo)

            vm.refresh(pulled = true)
            advanceUntilIdle()

            assertEquals(
                review,
                vm.uiState.value
                    .state(WidgetId.WEEKLY_REVIEW)
                    .data,
            )
            assertTrue(
                vm.uiState.value
                    .state(WidgetId.INBOX)
                    .loading,
            )
            assertTrue("refreshing until all answered", vm.uiState.value.refreshing)

            slow.complete(DashboardResult.Ok(inbox))
            advanceUntilIdle()
            assertEquals(
                inbox,
                vm.uiState.value
                    .state(WidgetId.INBOX)
                    .data,
            )
            assertNull(
                vm.uiState.value
                    .state(WidgetId.INBOX)
                    .error,
            )
            assertFalse(vm.uiState.value.refreshing)
        }

    @Test
    fun `a layout that cannot be reloaded keeps the one shown`() =
        runTest(dispatcher) {
            coEvery { repo.layout() } returnsMany listOf(DashboardResult.Ok(layoutOf(WidgetId.INBOX)), DashboardResult.Failed)
            coEvery { repo.load(WidgetId.INBOX) } returns DashboardResult.Ok(inbox)
            val vm = DashboardViewModel(repo)
            vm.refresh()
            advanceUntilIdle()

            vm.refresh(pulled = true)
            advanceUntilIdle()

            assertEquals(listOf(WidgetId.INBOX), vm.uiState.value.widgets)
            coVerify(exactly = 2) { repo.load(WidgetId.INBOX) }
            coVerify(exactly = 0) { repo.load(WidgetId.TODAY) }
        }

    @Test
    fun `a newer refresh drops the answer of the older one`() =
        runTest(dispatcher) {
            val stale = CompletableDeferred<DashboardResult<WidgetData>>()
            val fresh = WidgetData.Inbox(Count(7, more = false), Count(0, more = false))
            coEvery { repo.layout() } returns DashboardResult.Ok(layoutOf(WidgetId.INBOX))
            coEvery { repo.load(WidgetId.INBOX) } coAnswers { stale.await() } coAndThen { DashboardResult.Ok(fresh) }
            val vm = DashboardViewModel(repo)
            vm.refresh()
            advanceUntilIdle()

            vm.refresh(pulled = true)
            advanceUntilIdle()
            stale.complete(DashboardResult.Ok(inbox))
            advanceUntilIdle()

            assertEquals(WidgetState(fresh, loading = false), vm.uiState.value.state(WidgetId.INBOX))
            assertFalse(vm.uiState.value.refreshing)
        }

    @Test
    fun `only a pull shows the refresh indicator`() =
        runTest(dispatcher) {
            val slow = CompletableDeferred<DashboardResult<WidgetData>>()
            coEvery { repo.layout() } returns DashboardResult.Ok(layoutOf(WidgetId.INBOX))
            coEvery { repo.load(WidgetId.INBOX) } coAnswers { slow.await() }
            val vm = DashboardViewModel(repo)

            vm.refresh()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.refreshing)
            assertTrue(
                vm.uiState.value
                    .state(WidgetId.INBOX)
                    .loading,
            )
            slow.complete(DashboardResult.Ok(inbox))
            advanceUntilIdle()
            assertFalse(
                vm.uiState.value
                    .state(WidgetId.INBOX)
                    .loading,
            )
        }
}
