package ch.sonensei.cockpit.dashboard

import ch.sonensei.cockpit.gtd.data.ChangeSignal
import ch.sonensei.cockpit.gtd.data.MailRefreshTriggers
import ch.sonensei.cockpit.gtd.projects.ProjectOpenRequests
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The dashboard's unread-mail count (the Inbox widget, `WidgetData.Inbox.unreadMail`) follows new mail while the
 * dashboard is shown: a mail change event reloads it quietly, a burst once, and a failed reload keeps what is shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardMailRefreshTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<DashboardRepository>()
    private val signals = MutableSharedFlow<ChangeSignal>(extraBufferCapacity = 64)

    private val today = WidgetData.Today(tasks = emptyList(), events = emptyList(), habitsDue = 0, habitsDone = 0)
    private val oneUnread = WidgetData.Inbox(Count(2, more = false), Count(1, more = false))
    private val threeUnread = WidgetData.Inbox(Count(2, more = false), Count(3, more = false))
    private val review = WidgetData.WeeklyReview(daysSinceLastReview = 3, openStep = null)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.layout() } returns DashboardResult.Ok(layoutOf(WidgetId.TODAY, WidgetId.INBOX))
        coEvery { repo.load(WidgetId.TODAY) } returns DashboardResult.Ok(today)
        coEvery { repo.load(WidgetId.WEEKLY_REVIEW) } returns DashboardResult.Ok(review)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** The dashboard shown and loaded, following the mail changes as `DashboardRoute` does while started. */
    private fun TestScope.shown(): DashboardViewModel {
        val vm = DashboardViewModel(repo, ProjectOpenRequests(), MailRefreshTriggers(signals, QUIET_MS))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.followMailChanges() }
        vm.refresh()
        advanceUntilIdle()
        return vm
    }

    private fun signal(vararg signal: ChangeSignal) = signal.forEach { signals.tryEmit(it) }

    private fun TestScope.quiet() {
        advanceTimeBy(QUIET_MS + 1)
        advanceUntilIdle()
    }

    @Test
    fun `an email event updates the unread mail count`() =
        runTest(dispatcher) {
            coEvery { repo.load(WidgetId.INBOX) } returnsMany listOf(DashboardResult.Ok(oneUnread), DashboardResult.Ok(threeUnread))
            val vm = shown()
            assertEquals(WidgetState(oneUnread, loading = false), vm.uiState.value.state(WidgetId.INBOX))

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()

            coVerify(exactly = 2) { repo.load(WidgetId.INBOX) }
            assertEquals(WidgetState(threeUnread, loading = false), vm.uiState.value.state(WidgetId.INBOX))
        }

    @Test
    fun `a failed event reload keeps the unread mail count and shows no error`() =
        runTest(dispatcher) {
            coEvery { repo.load(WidgetId.INBOX) } returnsMany
                listOf(DashboardResult.Ok(oneUnread), DashboardResult.Offline, DashboardResult.Failed)
            val vm = shown()

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()
            signal(ChangeSignal.Changed("MAIL_FOLDER"))
            quiet()

            coVerify(exactly = 3) { repo.load(WidgetId.INBOX) }
            assertEquals(WidgetState(oneUnread, loading = false, error = null), vm.uiState.value.state(WidgetId.INBOX))
            assertEquals(WidgetState(today, loading = false), vm.uiState.value.state(WidgetId.TODAY))
        }

    @Test
    fun `a burst of mail events gives at most one widget reload`() =
        runTest(dispatcher) {
            coEvery { repo.load(WidgetId.INBOX) } returns DashboardResult.Ok(oneUnread)
            shown()
            coVerify(exactly = 2) { repo.load(any()) }

            repeat(10) {
                signal(ChangeSignal.Changed("EMAIL"))
                advanceTimeBy(80)
            }
            signal(ChangeSignal.Changed("MAIL_FOLDER"))
            quiet()

            coVerify(exactly = 3) { repo.load(any()) }
            coVerify(exactly = 1) { repo.layout() }
        }

    @Test
    fun `a task event reloads no widget`() =
        runTest(dispatcher) {
            coEvery { repo.load(WidgetId.INBOX) } returns DashboardResult.Ok(oneUnread)
            shown()

            signal(ChangeSignal.Changed("TASK"), ChangeSignal.Changed("PROJECT"), ChangeSignal.Connected)
            advanceTimeBy(10 * QUIET_MS)
            advanceUntilIdle()

            coVerify(exactly = 2) { repo.load(any()) }
        }

    @Test
    fun `an event while the dashboard loads starts no second load but reloads the count once it ends`() =
        runTest(dispatcher) {
            val slow = CompletableDeferred<DashboardResult<WidgetData>>()
            coEvery { repo.load(WidgetId.INBOX) } returns DashboardResult.Ok(oneUnread) coAndThen { slow.await() }
            val vm = shown()
            vm.refresh()
            advanceUntilIdle()

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()
            signal(ChangeSignal.Changed("MAIL_FOLDER"))
            quiet()

            coVerify(exactly = 2) { repo.load(WidgetId.INBOX) }
            coVerify(exactly = 2) { repo.load(WidgetId.TODAY) }
            slow.complete(DashboardResult.Ok(threeUnread))
            advanceUntilIdle()
            assertEquals(WidgetState(threeUnread, loading = false), vm.uiState.value.state(WidgetId.INBOX))
            // The load may have read the count before the new mail was stored: the count alone reloads once more.
            coVerify(exactly = 3) { repo.load(WidgetId.INBOX) }
            coVerify(exactly = 2) { repo.load(WidgetId.TODAY) }
            coVerify(exactly = 2) { repo.layout() }
        }

    @Test
    fun `an event during an event reload reloads the count once more afterwards`() =
        runTest(dispatcher) {
            val slow = CompletableDeferred<DashboardResult<WidgetData>>()
            coEvery { repo.load(WidgetId.INBOX) } returns
                DashboardResult.Ok(oneUnread) coAndThen { slow.await() } andThen DashboardResult.Ok(threeUnread)
            val vm = shown()

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()
            signal(ChangeSignal.Changed("EMAIL"))
            quiet()
            coVerify(exactly = 2) { repo.load(WidgetId.INBOX) }

            slow.complete(DashboardResult.Ok(oneUnread))
            advanceUntilIdle()

            coVerify(exactly = 3) { repo.load(WidgetId.INBOX) }
            assertEquals(WidgetState(threeUnread, loading = false), vm.uiState.value.state(WidgetId.INBOX))
        }

    @Test
    fun `a refresh during an event reload drops the event reload's older answer`() =
        runTest(dispatcher) {
            val stale = CompletableDeferred<DashboardResult<WidgetData>>()
            coEvery { repo.load(WidgetId.INBOX) } returns
                DashboardResult.Ok(oneUnread) coAndThen { stale.await() } andThen DashboardResult.Ok(threeUnread)
            val vm = shown()

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()
            coVerify(exactly = 2) { repo.load(WidgetId.INBOX) }
            vm.refresh()
            advanceUntilIdle()
            assertEquals(WidgetState(threeUnread, loading = false), vm.uiState.value.state(WidgetId.INBOX))

            stale.complete(DashboardResult.Ok(oneUnread))
            advanceUntilIdle()

            assertEquals(WidgetState(threeUnread, loading = false), vm.uiState.value.state(WidgetId.INBOX))
            coVerify(exactly = 3) { repo.load(WidgetId.INBOX) }
        }

    @Test
    fun `a dashboard without mail widgets loads nothing on a mail event`() =
        runTest(dispatcher) {
            coEvery { repo.layout() } returns DashboardResult.Ok(layoutOf(WidgetId.WEEKLY_REVIEW))
            shown()

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()

            coVerify(exactly = 1) { repo.load(any()) }
        }

    private companion object {
        const val QUIET_MS = 1_000L
    }
}
