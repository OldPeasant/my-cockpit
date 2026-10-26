package ch.sonensei.cockpit.newitems

import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.RefreshResult
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.mail.list.MailPage
import ch.sonensei.cockpit.mail.list.MailRepository
import ch.sonensei.cockpit.mail.list.MailResult
import ch.sonensei.cockpit.mail.list.Mailbox
import ch.sonensei.cockpit.mail.read.MailGtdAction
import ch.sonensei.cockpit.mail.read.MailReadRepository
import ch.sonensei.cockpit.newitems.NewTestData.mail
import ch.sonensei.cockpit.newitems.NewTestData.task
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
class NewViewModelTest {
    private val loadedAt = Instant.parse("2026-10-20T12:00:00Z")
    private val plumber = task("Call the plumber", "2026-10-20T08:00:00Z", 1)
    private val invoice = mail("Invoice", "2026-10-20T09:00:00Z", 3)
    private val party = mail("Party", "2026-10-20T11:00:00Z", 4)

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

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = NewViewModel(tasks, mailRepo, reader, cache, filters, clock)

    private fun mailAnswers(result: MailResult<MailPage>) = coEvery { mailRepo.messages(Mailbox.Unified, null) } returns result

    private val dinner = mail("Dinner", "2026-10-20T11:30:00Z", 5)
    private val receipt = mail("Receipt", "2026-10-19T09:00:00Z", 6)
    private val newsletter = mail("Newsletter", "2026-10-18T09:00:00Z", 7)

    /** Keeps [NewViewModel.uiState] collected for the rest of the test, as the screen does. */
    private fun NewViewModel.collected(
        scope: CoroutineScope,
        scheduler: TestCoroutineScheduler,
    ) = apply { scope.launch(UnconfinedTestDispatcher(scheduler)) { uiState.collect {} } }

    private fun NewViewModel.keys() = uiState.value.rows.map { it.key }

    @Test
    fun showsTasksAndMailsMergedAndKeepsTheMailForOffline() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(party, invoice), null)))
            val vm = viewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

            vm.refresh()

            assertEquals(
                listOf("mail:${party.id}", "mail:${invoice.id}", "task:${plumber.id}"),
                vm.uiState.value.rows
                    .map { it.key },
            )
            assertEquals(listOf(party, invoice), cache.last?.messages)
            assertTrue(vm.uiState.value.canConvert)
        }

    @Test
    fun offlineShowsTheCachedMailReadOnly() =
        runTest {
            cache.last = CachedMail(listOf(invoice), null, loadedAt)
            inbox.value = Cached(listOf(plumber), offline = true, refreshedAt = loadedAt)
            mailAnswers(MailResult.Offline)
            val vm = viewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

            vm.refresh()
            vm.toTask(invoice.id)

            val state = vm.uiState.value
            assertTrue(state.offline)
            assertTrue(state.mailOffline)
            assertFalse(state.canConvert)
            assertEquals(listOf("mail:${invoice.id}", "task:${plumber.id}"), state.rows.map { it.key })
            coVerify(exactly = 0) { reader.gtd(any(), any()) }
        }

    @Test
    fun theFilterIsRememberedAcrossViewModels() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(invoice), null)))
            val first = viewModel()
            first.setFilter(NewFilter.MAIL)

            val second = viewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { second.uiState.collect {} }
            second.refresh()

            assertEquals(NewFilter.MAIL, second.uiState.value.filter)
            assertEquals(
                listOf("mail:${invoice.id}"),
                second.uiState.value.rows
                    .map { it.key },
            )
        }

    @Test
    fun toTaskCreatesAnInboxTaskAndReloadsTheInbox() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(invoice), null)))
            coEvery { reader.gtd(invoice.id, MailGtdAction.TASK) } returns MailResult.Ok("Invoice")
            val vm = viewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            vm.refresh()

            vm.toTask(invoice.id)

            assertEquals(NewNotice.TaskCreated("Invoice"), vm.uiState.value.notice)
            assertTrue(
                vm.uiState.value.converting
                    .isEmpty(),
            )
            coVerify { tasks.refresh(TaskView.INBOX) }
        }

    @Test
    fun aRemovedMailLeavesTheList() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(party, invoice), null)))
            val vm = viewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            vm.refresh()

            vm.removed(party.id)

            assertEquals(
                listOf("mail:${invoice.id}", "task:${plumber.id}"),
                vm.uiState.value.rows
                    .map { it.key },
            )
            assertEquals(listOf(invoice), cache.last?.messages)
        }

    // --- Cache restore and first load ---

    @Test
    fun theLastLoadedMailIsShownAtOnceFromTheCache() =
        runTest {
            val earlier = Instant.parse("2026-10-20T10:00:00Z")
            cache.last = CachedMail(listOf(party, invoice), "page-2", earlier)

            val vm = viewModel().collected(backgroundScope, testScheduler)

            val state = vm.uiState.value
            assertEquals(listOf("mail:${party.id}", "mail:${invoice.id}"), vm.keys())
            assertEquals(earlier, state.mailLoadedAt)
            assertTrue(state.moreMail)
            assertFalse(state.loading)
            coVerify(exactly = 0) { mailRepo.messages(any(), any()) }
        }

    @Test
    fun beforeAnythingIsLoadedTheScreenIsInItsFirstLoad() =
        runTest {
            inbox.value = Cached(emptyList(), offline = false, refreshedAt = null)
            val vm = viewModel().collected(backgroundScope, testScheduler)

            assertTrue(vm.uiState.value.firstLoad)

            inbox.value = Cached(emptyList(), offline = false, refreshedAt = loadedAt)
            assertFalse(vm.uiState.value.firstLoad)
        }

    @Test
    fun aSuccessfulLoadRecordsTheClockTimeAndClearsTheLoadingFlag() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(invoice), null)))
            val vm = viewModel().collected(backgroundScope, testScheduler)

            vm.refresh()

            val state = vm.uiState.value
            assertEquals(loadedAt, state.mailLoadedAt)
            assertFalse(state.loading)
            assertFalse(state.moreMail)
            assertEquals(CachedMail(listOf(invoice), null, loadedAt), cache.last)
        }

    @Test
    fun whileTheFirstPageLoadsTheStateSaysLoading() =
        runTest {
            val answer = CompletableDeferred<MailResult<MailPage>>()
            coEvery { mailRepo.messages(Mailbox.Unified, null) } coAnswers { answer.await() }
            val vm = viewModel().collected(backgroundScope, testScheduler)

            vm.refresh()
            assertTrue(vm.uiState.value.loading)

            answer.complete(MailResult.Ok(MailPage(listOf(invoice), null)))
            assertFalse(vm.uiState.value.loading)
            assertEquals(listOf("mail:${invoice.id}", "task:${plumber.id}"), vm.keys())
        }

    // --- Failed and offline loads ---

    @Test
    fun aFailedFirstLoadKeepsNothingForOffline() =
        runTest {
            mailAnswers(MailResult.Failed(500))
            val vm = viewModel().collected(backgroundScope, testScheduler)

            vm.refresh()

            val state = vm.uiState.value
            assertTrue(state.mailFailed)
            assertFalse(state.mailOffline)
            assertFalse(state.loading)
            assertEquals(null, cache.last)
            assertEquals(listOf("task:${plumber.id}"), vm.keys())
        }

    @Test
    fun aFailedReloadKeepsTheMailsAlreadyShown() =
        runTest {
            coEvery { mailRepo.messages(Mailbox.Unified, null) } returnsMany
                listOf(MailResult.Ok(MailPage(listOf(party, invoice), null)), MailResult.Failed(403))
            val vm = viewModel().collected(backgroundScope, testScheduler)
            vm.refresh()

            vm.refresh()

            val state = vm.uiState.value
            assertTrue(state.mailFailed)
            assertFalse(state.offline)
            assertTrue(state.canConvert)
            assertEquals(listOf("mail:${party.id}", "mail:${invoice.id}", "task:${plumber.id}"), vm.keys())
        }

    @Test
    fun offlineWithoutAnyCachedMailShowsOnlyTheInbox() =
        runTest {
            mailAnswers(MailResult.Offline)
            val vm = viewModel().collected(backgroundScope, testScheduler)

            vm.refresh()

            val state = vm.uiState.value
            assertTrue(state.mailOffline)
            assertEquals(null, state.mailLoadedAt)
            assertFalse(state.canConvert)
            assertEquals(listOf("task:${plumber.id}"), vm.keys())
        }

    @Test
    fun aSuccessfulReloadEndsTheOfflineAndFailedStates() =
        runTest {
            coEvery { mailRepo.messages(Mailbox.Unified, null) } returnsMany
                listOf(MailResult.Offline, MailResult.Failed(null), MailResult.Ok(MailPage(listOf(invoice), null)))
            val vm = viewModel().collected(backgroundScope, testScheduler)

            vm.refresh()
            assertTrue(vm.uiState.value.mailOffline)
            vm.refresh()
            assertFalse(vm.uiState.value.mailOffline)
            assertTrue(vm.uiState.value.mailFailed)
            vm.refresh()

            val state = vm.uiState.value
            assertFalse(state.mailOffline)
            assertFalse(state.mailFailed)
            assertTrue(state.canConvert)
        }

    @Test
    fun aFailedInboxLoadIsReported() =
        runTest {
            inbox.value = Cached(listOf(plumber), offline = false, refreshedAt = loadedAt, failure = RefreshResult.Failed(500))
            val vm = viewModel().collected(backgroundScope, testScheduler)

            assertTrue(vm.uiState.value.inboxFailed)
            assertFalse(vm.uiState.value.offline)
        }

    @Test
    fun retryReloadsTheInboxAndTheMail() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(invoice), null)))
            val vm = viewModel().collected(backgroundScope, testScheduler)

            vm.retry()

            coVerify(exactly = 1) { tasks.refresh(TaskView.INBOX) }
            coVerify(exactly = 1) { mailRepo.messages(Mailbox.Unified, null) }
        }

    // --- Paging ---

    @Test
    fun loadMoreAppendsTheNextPageWithoutDuplicates() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(party, invoice), "page-2")))
            coEvery { mailRepo.messages(Mailbox.Unified, "page-2") } returns
                MailResult.Ok(MailPage(listOf(invoice, receipt), "page-3"))
            val vm = viewModel().collected(backgroundScope, testScheduler)
            vm.refresh()

            vm.loadMore()

            val state = vm.uiState.value
            assertEquals(
                listOf("mail:${party.id}", "mail:${invoice.id}", "task:${plumber.id}", "mail:${receipt.id}"),
                vm.keys(),
            )
            assertTrue(state.moreMail)
            assertFalse(state.loadingMore)
            assertEquals(CachedMail(listOf(party, invoice, receipt), "page-3", loadedAt), cache.last)
        }

    @Test
    fun theLastPageEndsPaging() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(party), "page-2")))
            coEvery { mailRepo.messages(Mailbox.Unified, "page-2") } returns MailResult.Ok(MailPage(listOf(invoice), null))
            val vm = viewModel().collected(backgroundScope, testScheduler)
            vm.refresh()

            vm.loadMore()
            vm.loadMore()

            assertFalse(vm.uiState.value.moreMail)
            coVerify(exactly = 1) { mailRepo.messages(Mailbox.Unified, "page-2") }
        }

    @Test
    fun loadMoreWithoutAnOlderPageDoesNothing() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(invoice), null)))
            val vm = viewModel().collected(backgroundScope, testScheduler)
            vm.refresh()

            vm.loadMore()

            coVerify(exactly = 1) { mailRepo.messages(any(), any()) }
            assertFalse(vm.uiState.value.loadingMore)
        }

    @Test
    fun loadMoreWhileTheFirstPageLoadsIsIgnored() =
        runTest {
            cache.last = CachedMail(listOf(party), "page-2", loadedAt)
            val answer = CompletableDeferred<MailResult<MailPage>>()
            coEvery { mailRepo.messages(Mailbox.Unified, null) } coAnswers { answer.await() }
            val vm = viewModel().collected(backgroundScope, testScheduler)

            vm.refresh()
            vm.loadMore()

            coVerify(exactly = 0) { mailRepo.messages(Mailbox.Unified, "page-2") }
            answer.complete(MailResult.Ok(MailPage(listOf(party), "page-2")))
        }

    @Test
    fun aSecondLoadMoreWhileOneRunsIsIgnored() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(party), "page-2")))
            val answer = CompletableDeferred<MailResult<MailPage>>()
            coEvery { mailRepo.messages(Mailbox.Unified, "page-2") } coAnswers { answer.await() }
            val vm = viewModel().collected(backgroundScope, testScheduler)
            vm.refresh()

            vm.loadMore()
            assertTrue(vm.uiState.value.loadingMore)
            vm.loadMore()
            answer.complete(MailResult.Ok(MailPage(listOf(invoice), null)))

            coVerify(exactly = 1) { mailRepo.messages(Mailbox.Unified, "page-2") }
            assertEquals(listOf("mail:${party.id}", "mail:${invoice.id}", "task:${plumber.id}"), vm.keys())
        }

    @Test
    fun anOfflineLoadMoreKeepsTheLoadedMailsAndItsCursor() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(party, invoice), "page-2")))
            coEvery { mailRepo.messages(Mailbox.Unified, "page-2") } returns MailResult.Offline
            val vm = viewModel().collected(backgroundScope, testScheduler)
            vm.refresh()

            vm.loadMore()

            val state = vm.uiState.value
            assertTrue(state.mailOffline)
            assertFalse(state.loadingMore)
            assertTrue(state.moreMail)
            assertFalse(state.canConvert)
            assertEquals(listOf("mail:${party.id}", "mail:${invoice.id}"), vm.keys())
            assertEquals(CachedMail(listOf(party, invoice), "page-2", loadedAt), cache.last)
        }

    @Test
    fun aFailedLoadMoreKeepsTheLoadedMails() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(party, invoice), "page-2")))
            coEvery { mailRepo.messages(Mailbox.Unified, "page-2") } returns MailResult.Failed(500)
            val vm = viewModel().collected(backgroundScope, testScheduler)
            vm.refresh()

            vm.loadMore()

            val state = vm.uiState.value
            assertTrue(state.mailFailed)
            assertFalse(state.loadingMore)
            assertTrue(state.moreMail)
            assertEquals(listOf("mail:${party.id}", "mail:${invoice.id}"), vm.keys())
        }

    @Test
    fun refreshKeepsThePagesLoadedBehindTheFirst() =
        runTest {
            coEvery { mailRepo.messages(Mailbox.Unified, null) } returnsMany
                listOf(
                    MailResult.Ok(MailPage(listOf(party, invoice), "page-2")),
                    // A new mail arrived: the first page now ends at the party mail.
                    MailResult.Ok(MailPage(listOf(dinner, party), "page-2-new")),
                )
            coEvery { mailRepo.messages(Mailbox.Unified, "page-2") } returns
                MailResult.Ok(MailPage(listOf(receipt), "page-3"))
            coEvery { mailRepo.messages(Mailbox.Unified, "page-3") } returns
                MailResult.Ok(MailPage(listOf(newsletter), null))
            val vm = viewModel().collected(backgroundScope, testScheduler)
            vm.refresh()
            vm.loadMore()

            vm.refresh()

            assertEquals(
                listOf("mail:${dinner.id}", "mail:${party.id}", "mail:${invoice.id}", "task:${plumber.id}", "mail:${receipt.id}"),
                vm.keys(),
            )
            // Paging continues behind the pages already loaded, not after the new first page.
            vm.loadMore()
            coVerify(exactly = 1) { mailRepo.messages(Mailbox.Unified, "page-3") }
            coVerify(exactly = 0) { mailRepo.messages(Mailbox.Unified, "page-2-new") }
            assertFalse(vm.uiState.value.moreMail)
        }

    @Test
    fun refreshDropsMailsThatLeftTheFirstPage() =
        runTest {
            coEvery { mailRepo.messages(Mailbox.Unified, null) } returnsMany
                listOf(
                    MailResult.Ok(MailPage(listOf(dinner, party, invoice), "page-2")),
                    // The party mail was archived elsewhere.
                    MailResult.Ok(MailPage(listOf(dinner, invoice), "page-2")),
                )
            val vm = viewModel().collected(backgroundScope, testScheduler)
            vm.refresh()

            vm.refresh()

            assertEquals(
                listOf(dinner, invoice),
                vm.uiState.value.rows
                    .filterIsInstance<NewRow.Mail>()
                    .map { it.mail },
            )
            assertEquals("page-2", cache.last?.nextCursor)
        }

    @Test
    fun refreshWithoutOverlapStartsOverWithTheNewPage() =
        runTest {
            coEvery { mailRepo.messages(Mailbox.Unified, null) } returnsMany
                listOf(
                    MailResult.Ok(MailPage(listOf(receipt, newsletter), "old-2")),
                    MailResult.Ok(MailPage(listOf(dinner, party), "new-2")),
                )
            val vm = viewModel().collected(backgroundScope, testScheduler)
            vm.refresh()

            vm.refresh()

            assertEquals(listOf("mail:${dinner.id}", "mail:${party.id}"), vm.keys())
            assertEquals("new-2", cache.last?.nextCursor)
        }

    @Test
    fun refreshThatReturnsTheWholeInboxReplacesThePages() =
        runTest {
            coEvery { mailRepo.messages(Mailbox.Unified, null) } returnsMany
                listOf(
                    MailResult.Ok(MailPage(listOf(party, invoice), "page-2")),
                    MailResult.Ok(MailPage(listOf(party), null)),
                )
            val vm = viewModel().collected(backgroundScope, testScheduler)
            vm.refresh()

            vm.refresh()

            assertEquals(listOf("mail:${party.id}", "task:${plumber.id}"), vm.keys())
            assertFalse(vm.uiState.value.moreMail)
        }

    @Test
    fun refreshCancelsARunningLoadMore() =
        runTest {
            coEvery { mailRepo.messages(Mailbox.Unified, null) } returnsMany
                listOf(MailResult.Ok(MailPage(listOf(party), "page-2")), MailResult.Ok(MailPage(listOf(dinner, party), "page-2")))
            val older = CompletableDeferred<MailResult<MailPage>>()
            coEvery { mailRepo.messages(Mailbox.Unified, "page-2") } coAnswers { older.await() }
            val vm = viewModel().collected(backgroundScope, testScheduler)
            vm.refresh()
            vm.loadMore()

            vm.refresh()
            older.complete(MailResult.Ok(MailPage(listOf(invoice), null)))

            val state = vm.uiState.value
            assertFalse(state.loadingMore)
            assertEquals(listOf("mail:${dinner.id}", "mail:${party.id}"), vm.keys())
            assertTrue(state.moreMail)
        }

    @Test
    fun anOlderRefreshAnsweringLastDoesNotOverwriteTheNewer() =
        runTest {
            val first = CompletableDeferred<MailResult<MailPage>>()
            val second = CompletableDeferred<MailResult<MailPage>>()
            coEvery { mailRepo.messages(Mailbox.Unified, null) } coAnswers { first.await() } coAndThen { second.await() }
            val vm = viewModel().collected(backgroundScope, testScheduler)

            vm.refresh()
            vm.refresh()
            second.complete(MailResult.Ok(MailPage(listOf(dinner), null)))
            first.complete(MailResult.Failed(500))

            val state = vm.uiState.value
            assertFalse(state.mailFailed)
            assertFalse(state.loading)
            assertEquals(listOf("mail:${dinner.id}", "task:${plumber.id}"), vm.keys())
            assertEquals(listOf(dinner), cache.last?.messages)
        }

    // --- Filters ---

    @Test
    fun theInboxFilterHidesTheMails() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(party, invoice), "page-2")))
            val vm = viewModel().collected(backgroundScope, testScheduler)
            vm.refresh()

            vm.setFilter(NewFilter.INBOX)

            assertEquals(NewFilter.INBOX, vm.uiState.value.filter)
            assertEquals(listOf("task:${plumber.id}"), vm.keys())
            assertEquals(NewFilter.INBOX, filters.stored)
        }

    @Test
    fun choosingTheShownFilterAgainWritesNothing() =
        runTest {
            var writes = 0
            val counting =
                object : NewFilterStore {
                    override fun read() = NewFilter.MAIL

                    override fun write(filter: NewFilter) {
                        writes++
                    }
                }
            val vm = NewViewModel(tasks, mailRepo, reader, cache, counting, clock)

            vm.setFilter(NewFilter.MAIL)
            assertEquals(0, writes)
            vm.setFilter(NewFilter.ALL)
            assertEquals(1, writes)
        }

    // --- "-> Inbox task" ---

    @Test
    fun aSecondTapWhileTheTaskIsCreatedDoesNothing() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(invoice), null)))
            val answer = CompletableDeferred<MailResult<String>>()
            coEvery { reader.gtd(invoice.id, MailGtdAction.TASK) } coAnswers { answer.await() }
            val vm = viewModel().collected(backgroundScope, testScheduler)
            vm.refresh()

            vm.toTask(invoice.id)
            vm.toTask(invoice.id)

            assertEquals(setOf(invoice.id), vm.uiState.value.converting)
            answer.complete(MailResult.Ok("Invoice"))
            coVerify(exactly = 1) { reader.gtd(invoice.id, MailGtdAction.TASK) }
            assertTrue(
                vm.uiState.value.converting
                    .isEmpty(),
            )
        }

    @Test
    fun aFailedConversionSaysSoAndDoesNotReloadTheInbox() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(invoice), null)))
            coEvery { reader.gtd(invoice.id, MailGtdAction.TASK) } returns MailResult.Failed(409)
            val vm = viewModel().collected(backgroundScope, testScheduler)
            vm.refresh()

            vm.toTask(invoice.id)

            assertEquals(NewNotice.TaskFailed, vm.uiState.value.notice)
            assertTrue(
                vm.uiState.value.converting
                    .isEmpty(),
            )
            coVerify(exactly = 0) { tasks.refresh(any()) }
            // The mail stays in the list.
            assertEquals(listOf("mail:${invoice.id}", "task:${plumber.id}"), vm.keys())
        }

    @Test
    fun anUnreachableServerDuringConversionSaysNothingWasCreated() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(invoice), null)))
            coEvery { reader.gtd(invoice.id, MailGtdAction.TASK) } returns MailResult.Offline
            val vm = viewModel().collected(backgroundScope, testScheduler)
            vm.refresh()

            vm.toTask(invoice.id)

            assertEquals(NewNotice.TaskOffline, vm.uiState.value.notice)
            coVerify(exactly = 0) { tasks.refresh(any()) }
        }

    @Test
    fun aMailThatIsNotListedCannotBecomeATask() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(invoice), null)))
            val vm = viewModel().collected(backgroundScope, testScheduler)
            vm.refresh()

            vm.toTask(party.id)

            coVerify(exactly = 0) { reader.gtd(any(), any()) }
            assertTrue(
                vm.uiState.value.converting
                    .isEmpty(),
            )
        }

    @Test
    fun aNewConversionClearsTheOldNoticeAndDismissClearsTheNew() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(party, invoice), null)))
            coEvery { reader.gtd(invoice.id, MailGtdAction.TASK) } returns MailResult.Failed(500)
            val answer = CompletableDeferred<MailResult<String>>()
            coEvery { reader.gtd(party.id, MailGtdAction.TASK) } coAnswers { answer.await() }
            val vm = viewModel().collected(backgroundScope, testScheduler)
            vm.refresh()
            vm.toTask(invoice.id)
            assertEquals(NewNotice.TaskFailed, vm.uiState.value.notice)

            vm.toTask(party.id)
            assertEquals(null, vm.uiState.value.notice)
            answer.complete(MailResult.Ok("Party"))
            assertEquals(NewNotice.TaskCreated("Party"), vm.uiState.value.notice)

            vm.dismissNotice()
            assertEquals(null, vm.uiState.value.notice)
        }

    // --- Read view feedback ---

    @Test
    fun anOpenedMailIsShownAsReadAndKeptSoForOffline() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(party, invoice), null)))
            val vm = viewModel().collected(backgroundScope, testScheduler)
            vm.refresh()

            vm.markSeen(invoice.id)

            val seen =
                vm.uiState.value.rows
                    .filterIsInstance<NewRow.Mail>()
                    .associate { it.mail.id to it.mail.seen }
            assertEquals(mapOf(party.id to false, invoice.id to true), seen)
            assertEquals(listOf(false, true), cache.last?.messages?.map { it.seen })
        }

    @Test
    fun readViewFeedbackBeforeTheFirstLoadKeepsNothing() =
        runTest {
            val vm = viewModel().collected(backgroundScope, testScheduler)

            vm.markSeen(invoice.id)
            vm.removed(invoice.id)

            assertEquals(null, cache.last)
        }

    @Test
    fun theUiStateFindsOnlyListedTasks() {
        val state = NewUiState(rows = listOf(NewRow.Task(plumber), NewRow.Mail(invoice)))

        assertEquals(plumber, state.task(plumber.id))
        assertEquals(null, state.task(invoice.id))
    }
}
