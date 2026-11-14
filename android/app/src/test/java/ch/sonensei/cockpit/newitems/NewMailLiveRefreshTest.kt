package ch.sonensei.cockpit.newitems

import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.ChangeSignal
import ch.sonensei.cockpit.gtd.data.MailRefreshTriggers
import ch.sonensei.cockpit.gtd.data.RefreshResult
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.mail.list.MailPage
import ch.sonensei.cockpit.mail.list.MailRepository
import ch.sonensei.cockpit.mail.list.MailResult
import ch.sonensei.cockpit.mail.list.MailSummary
import ch.sonensei.cockpit.mail.list.Mailbox
import ch.sonensei.cockpit.mail.read.MailReadRepository
import ch.sonensei.cockpit.newitems.NewTestData.mail
import ch.sonensei.cockpit.newitems.NewTestData.task
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import java.time.ZoneOffset

/**
 * The New view reloads its mail part while shown when the server announces new or changed mail (`EMAIL`,
 * `MAIL_FOLDER`) or the change stream reconnects; a burst gives one reload, and a failed event reload keeps the view.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewMailLiveRefreshTest {
    private val loadedAt = Instant.parse("2026-10-20T12:00:00Z")
    private val plumber = task("Call the plumber", "2026-10-20T08:00:00Z", 1)
    private val invoice = mail("Invoice", "2026-10-20T09:00:00Z", 3)
    private val party = mail("Party", "2026-10-20T11:00:00Z", 4)
    private val dinner = mail("Dinner", "2026-10-20T11:30:00Z", 5)
    private val receipt = mail("Receipt", "2026-10-19T09:00:00Z", 6)

    private val inbox = MutableStateFlow(Cached(listOf(plumber), offline = false, refreshedAt = loadedAt))
    private val tasks =
        mockk<TaskRepository> {
            every { observe(TaskView.INBOX) } returns inbox
            coEvery { refresh(TaskView.INBOX) } returns RefreshResult.Refreshed
        }
    private val mailRepo = mockk<MailRepository>()
    private val reader = mockk<MailReadRepository>()
    private val cache = NewMailCache()
    private val filters =
        object : NewFilterStore {
            var stored = NewFilter.ALL

            override fun read() = stored

            override fun write(filter: NewFilter) {
                stored = filter
            }
        }
    private val clock = Clock.fixed(loadedAt, ZoneOffset.UTC)
    private val signals = MutableSharedFlow<ChangeSignal>(extraBufferCapacity = 64)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = NewViewModel(tasks, mailRepo, reader, cache, filters, clock, MailRefreshTriggers(signals, QUIET_MS))

    /** The screen shown: its state collected and the mail changes followed, as `NewRoute` does while started. */
    private fun TestScope.shown(vm: NewViewModel): Job {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.followMailChanges() }
    }

    private fun firstPages(vararg results: MailResult<MailPage>) =
        coEvery { mailRepo.messages(Mailbox.Unified, null) } returnsMany results.toList()

    private fun ok(
        vararg mails: MailSummary,
        cursor: String? = null,
    ) = MailResult.Ok(MailPage(mails.toList(), cursor))

    private fun NewViewModel.keys() = uiState.value.rows.map { it.key }

    private fun signal(vararg signal: ChangeSignal) = signal.forEach { signals.tryEmit(it) }

    /** Past the quiet time after the last signal. */
    private fun TestScope.quiet() = advanceTimeBy(QUIET_MS + 1)

    @Test
    fun anEmailEventShowsTheNewMailAtTheTopWithFilterAll() =
        runTest {
            firstPages(ok(party, invoice), ok(dinner, party, invoice))
            val vm = viewModel()
            shown(vm)
            vm.refresh()

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()

            assertEquals(NewFilter.ALL, vm.uiState.value.filter)
            assertEquals(listOf("mail:${dinner.id}", "mail:${party.id}", "mail:${invoice.id}", "task:${plumber.id}"), vm.keys())
            assertFalse(vm.uiState.value.loading)
            assertEquals(listOf(dinner, party, invoice), cache.last?.messages)
            coVerify(exactly = 2) { mailRepo.messages(Mailbox.Unified, null) }
        }

    @Test
    fun aMailFolderEventShowsTheNewMailAtTheTopWithFilterMail() =
        runTest {
            filters.stored = NewFilter.MAIL
            firstPages(ok(party, invoice), ok(dinner, party, invoice))
            val vm = viewModel()
            shown(vm)
            vm.refresh()

            signal(ChangeSignal.Changed("MAIL_FOLDER"))
            quiet()

            assertEquals(NewFilter.MAIL, vm.uiState.value.filter)
            assertEquals(listOf("mail:${dinner.id}", "mail:${party.id}", "mail:${invoice.id}"), vm.keys())
        }

    @Test
    fun theMailReloadsOnlyAfterTheQuietTime() =
        runTest {
            firstPages(ok(party), ok(dinner, party))
            val vm = viewModel()
            shown(vm)
            vm.refresh()

            signal(ChangeSignal.Changed("EMAIL"))
            advanceTimeBy(QUIET_MS - 1)
            coVerify(exactly = 1) { mailRepo.messages(Mailbox.Unified, null) }
            assertEquals(listOf("mail:${party.id}", "task:${plumber.id}"), vm.keys())

            advanceTimeBy(2)
            coVerify(exactly = 2) { mailRepo.messages(Mailbox.Unified, null) }
            assertEquals(listOf("mail:${dinner.id}", "mail:${party.id}", "task:${plumber.id}"), vm.keys())
        }

    @Test
    fun aBurstOfMailEventsGivesOneReload() =
        runTest {
            firstPages(ok(party), ok(dinner, party))
            val vm = viewModel()
            shown(vm)
            vm.refresh()

            repeat(12) {
                signal(ChangeSignal.Changed("EMAIL"))
                advanceTimeBy(50)
            }
            signal(ChangeSignal.Changed("MAIL_FOLDER"))
            advanceTimeBy(10 * QUIET_MS)

            coVerify(exactly = 2) { mailRepo.messages(Mailbox.Unified, null) }
            assertEquals(listOf("mail:${dinner.id}", "mail:${party.id}", "task:${plumber.id}"), vm.keys())
        }

    @Test
    fun aTaskEventDoesNotReloadTheMail() =
        runTest {
            firstPages(ok(party), ok(dinner, party))
            val vm = viewModel()
            shown(vm)
            vm.refresh()

            signal(ChangeSignal.Changed("TASK"), ChangeSignal.Changed("PROJECT"), ChangeSignal.Connected)
            advanceTimeBy(10 * QUIET_MS)

            coVerify(exactly = 1) { mailRepo.messages(Mailbox.Unified, null) }
            assertEquals(listOf("mail:${party.id}", "task:${plumber.id}"), vm.keys())
        }

    @Test
    fun aReconnectReloadsTheMailBecauseMissedEventsAreNotReplayed() =
        runTest {
            firstPages(ok(party), ok(dinner, party))
            val vm = viewModel()
            shown(vm)
            vm.refresh()

            signal(ChangeSignal.Reconnected)
            quiet()

            assertEquals(listOf("mail:${dinner.id}", "mail:${party.id}", "task:${plumber.id}"), vm.keys())
        }

    @Test
    fun anOfflineOrFailedEventReloadKeepsTheMailsAndRaisesNoBanner() =
        runTest {
            firstPages(ok(party, invoice), MailResult.Offline, MailResult.Failed(500))
            val vm = viewModel()
            shown(vm)
            vm.refresh()

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()
            val afterOffline = vm.uiState.value
            signal(ChangeSignal.Changed("EMAIL"))
            quiet()
            val afterFailure = vm.uiState.value

            for (state in listOf(afterOffline, afterFailure)) {
                assertEquals(listOf("mail:${party.id}", "mail:${invoice.id}", "task:${plumber.id}"), state.rows.map { it.key })
                assertFalse(state.mailOffline)
                assertFalse(state.mailFailed)
                assertFalse(state.offline)
                assertFalse(state.loading)
                assertTrue(state.canConvert)
            }
            assertEquals(loadedAt, afterFailure.mailLoadedAt)
            assertEquals(listOf(party, invoice), cache.last?.messages)
            coVerify(exactly = 3) { mailRepo.messages(Mailbox.Unified, null) }
        }

    @Test
    fun anEventReloadShowsNoLoadingIndicatorWhileItRuns() =
        runTest {
            val answer = CompletableDeferred<MailResult<MailPage>>()
            coEvery { mailRepo.messages(Mailbox.Unified, null) } returns ok(party) coAndThen { answer.await() }
            val vm = viewModel()
            shown(vm)
            vm.refresh()

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()
            coVerify(exactly = 2) { mailRepo.messages(Mailbox.Unified, null) }
            assertFalse(vm.uiState.value.loading)
            assertFalse(vm.uiState.value.firstLoad)

            answer.complete(ok(dinner, party))
            assertEquals(listOf("mail:${dinner.id}", "mail:${party.id}", "task:${plumber.id}"), vm.keys())
        }

    @Test
    fun anEventDuringARunningLoadDoesNotStartASecondOneButReloadsOnceItEnds() =
        runTest {
            val manual = CompletableDeferred<MailResult<MailPage>>()
            coEvery { mailRepo.messages(Mailbox.Unified, null) } coAnswers { manual.await() } andThen ok(dinner, party)
            val vm = viewModel()
            shown(vm)
            vm.refresh()

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()
            signal(ChangeSignal.Changed("MAIL_FOLDER"))
            quiet()
            coVerify(exactly = 1) { mailRepo.messages(Mailbox.Unified, null) }
            assertTrue(vm.uiState.value.loading)

            // The running load may have read the server before the new mail was stored: one more reload follows it.
            manual.complete(ok(party))
            assertFalse(vm.uiState.value.loading)
            coVerify(exactly = 2) { mailRepo.messages(Mailbox.Unified, null) }
            assertEquals(listOf("mail:${dinner.id}", "mail:${party.id}", "task:${plumber.id}"), vm.keys())

            advanceTimeBy(10 * QUIET_MS)
            coVerify(exactly = 2) { mailRepo.messages(Mailbox.Unified, null) }
        }

    @Test
    fun anEventDuringALoadMoreStillReloadsTheNewestMails() =
        runTest {
            val nextPage = CompletableDeferred<MailResult<MailPage>>()
            firstPages(ok(party, invoice, cursor = "page-2"), ok(dinner, party, cursor = "page-2-new"))
            coEvery { mailRepo.messages(Mailbox.Unified, "page-2") } coAnswers { nextPage.await() }
            val vm = viewModel()
            shown(vm)
            vm.refresh()
            vm.loadMore()
            assertTrue(vm.uiState.value.loadingMore)

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()

            coVerify(exactly = 2) { mailRepo.messages(Mailbox.Unified, null) }
            assertEquals("mail:${dinner.id}", vm.keys().first())
            assertTrue(vm.uiState.value.loadingMore)

            nextPage.complete(ok(receipt))
            assertFalse(vm.uiState.value.loadingMore)
            assertEquals(
                listOf("mail:${dinner.id}", "mail:${party.id}", "mail:${invoice.id}", "task:${plumber.id}", "mail:${receipt.id}"),
                vm.keys(),
            )
        }

    @Test
    fun aRefreshAfterLoadMoreDropsTheAnswerOfAnEarlierEventReload() =
        runTest {
            val stale = CompletableDeferred<MailResult<MailPage>>()
            val fresh = CompletableDeferred<MailResult<MailPage>>()
            coEvery { mailRepo.messages(Mailbox.Unified, null) } returns
                ok(party, invoice, cursor = "page-2") coAndThen { stale.await() } coAndThen { fresh.await() }
            coEvery { mailRepo.messages(Mailbox.Unified, "page-2") } returns ok(receipt)
            val vm = viewModel()
            shown(vm)
            vm.refresh()

            // An event reload runs, a page is loaded meanwhile, then the user pulls to refresh.
            signal(ChangeSignal.Changed("EMAIL"))
            quiet()
            vm.loadMore()
            vm.refresh()
            assertTrue(vm.uiState.value.loading)

            stale.complete(ok(party))
            assertTrue(vm.uiState.value.loading)
            assertEquals(
                listOf("mail:${party.id}", "mail:${invoice.id}", "task:${plumber.id}", "mail:${receipt.id}"),
                vm.keys(),
            )

            fresh.complete(ok(dinner, party, invoice))
            assertFalse(vm.uiState.value.loading)
            assertEquals(listOf("mail:${dinner.id}", "mail:${party.id}", "mail:${invoice.id}", "task:${plumber.id}"), vm.keys())
            coVerify(exactly = 3) { mailRepo.messages(Mailbox.Unified, null) }
        }

    @Test
    fun anEventReloadKeepsThePagesLoadedBehindTheFirst() =
        runTest {
            firstPages(ok(party, invoice, cursor = "page-2"), ok(dinner, party, cursor = "page-2-new"))
            coEvery { mailRepo.messages(Mailbox.Unified, "page-2") } returns ok(receipt, cursor = "page-3")
            val vm = viewModel()
            shown(vm)
            vm.refresh()
            vm.loadMore()

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()

            assertEquals(
                listOf("mail:${dinner.id}", "mail:${party.id}", "mail:${invoice.id}", "task:${plumber.id}", "mail:${receipt.id}"),
                vm.keys(),
            )
            assertTrue(vm.uiState.value.moreMail)
            assertEquals("page-3", cache.last?.nextCursor)
        }

    @Test
    fun aSuccessfulEventReloadEndsAnEarlierFailureBanner() =
        runTest {
            firstPages(ok(party), MailResult.Failed(500), ok(dinner, party))
            val vm = viewModel()
            shown(vm)
            vm.refresh()
            vm.refresh()
            assertTrue(vm.uiState.value.mailFailed)

            signal(ChangeSignal.Changed("EMAIL"))
            quiet()

            assertFalse(vm.uiState.value.mailFailed)
            assertFalse(vm.uiState.value.mailOffline)
            assertEquals(listOf("mail:${dinner.id}", "mail:${party.id}", "task:${plumber.id}"), vm.keys())
        }

    @Test
    fun noEventReloadsOnceTheScreenStoppedFollowing() =
        runTest {
            firstPages(ok(party), ok(dinner, party))
            val vm = viewModel()
            val following = shown(vm)
            vm.refresh()

            following.cancel()
            signal(ChangeSignal.Changed("EMAIL"), ChangeSignal.Reconnected)
            advanceTimeBy(10 * QUIET_MS)

            coVerify(exactly = 1) { mailRepo.messages(Mailbox.Unified, null) }
        }

    private companion object {
        const val QUIET_MS = 1_000L
    }
}
