package ch.sonensei.cockpit.newitems

import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.RefreshResult
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.mail.list.MailNextAction
import ch.sonensei.cockpit.mail.list.MailPage
import ch.sonensei.cockpit.mail.list.MailRepository
import ch.sonensei.cockpit.mail.list.MailResult
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
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.time.ZoneOffset
import java.util.UUID

/** "Next action & archive" on a mail row of the New view: the mail leaves, the task opens; failures keep the mail. */
@OptIn(ExperimentalCoroutinesApi::class)
class NewNextActionViewModelTest {
    private val loadedAt = Instant.parse("2026-10-20T12:00:00Z")
    private val plumber = task("Call the plumber", "2026-10-20T08:00:00Z", 1)
    private val invoice = mail("Invoice", "2026-10-20T09:00:00Z", 3)
    private val party = mail("Party", "2026-10-20T11:00:00Z", 4)
    private val taskId: UUID = UUID.fromString("00000000-0000-7000-9000-000000000003")

    private val inbox = MutableStateFlow(Cached(listOf(plumber), offline = false, refreshedAt = loadedAt))
    private val tasks =
        mockk<TaskRepository> {
            every { observe(TaskView.INBOX) } returns inbox
            coEvery { refresh(TaskView.INBOX) } returns RefreshResult.Refreshed
        }
    private val mailRepo = mockk<MailRepository>()
    private val reader = mockk<MailReadRepository>()
    private val cache = NewMailCache()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { mailRepo.messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage(listOf(party, invoice), null))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.loaded(): NewViewModel {
        val vm = NewViewModel(tasks, mailRepo, reader, cache, { NewFilter.ALL }, Clock.fixed(loadedAt, ZoneOffset.UTC))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.refresh()
        return vm
    }

    private fun NewViewModel.keys() = uiState.value.rows.map { it.key }

    private fun nextAction(
        archived: Boolean,
        errorCode: String? = null,
    ) = MailNextAction(invoice.id, taskId, "Invoice", archived, errorCode)

    @Test
    fun anArchivedMailLeavesTheListAndItsTaskOpens() =
        runTest {
            coEvery { mailRepo.toNextActions(listOf(invoice.id)) } returns MailResult.Ok(listOf(nextAction(archived = true)))
            val vm = loaded()

            vm.nextAction(invoice.id)

            val state = vm.uiState.value
            assertEquals(listOf("mail:${party.id}", "task:${plumber.id}"), vm.keys())
            assertEquals(taskId, state.openTask)
            assertNull(state.notice)
            assertTrue(state.converting.isEmpty())
            // The offline copy forgets the archived mail too.
            assertEquals(listOf(party), cache.last?.messages)
            coVerify(exactly = 0) { reader.gtd(any(), any()) }
        }

    @Test
    fun theOpenedTaskIsForgottenOnceShown() =
        runTest {
            coEvery { mailRepo.toNextActions(listOf(invoice.id)) } returns MailResult.Ok(listOf(nextAction(archived = true)))
            val vm = loaded()
            vm.nextAction(invoice.id)

            vm.taskOpened()

            assertNull(vm.uiState.value.openTask)
        }

    @Test
    fun aFailedArchiveKeepsTheMailOpensTheTaskAndNamesTheMail() =
        runTest {
            coEvery { mailRepo.toNextActions(listOf(invoice.id)) } returns
                MailResult.Ok(listOf(nextAction(archived = false, errorCode = "NO_ROLE_FOLDER")))
            val vm = loaded()

            vm.nextAction(invoice.id)

            val state = vm.uiState.value
            assertEquals(listOf("mail:${party.id}", "mail:${invoice.id}", "task:${plumber.id}"), vm.keys())
            assertEquals(taskId, state.openTask)
            assertEquals(NewNotice.NotArchived("Invoice", "NO_ROLE_FOLDER"), state.notice)
            assertTrue(state.converting.isEmpty())
        }

    @Test
    fun aFailedCreationLeavesTheMailWithAnError() =
        runTest {
            coEvery { mailRepo.toNextActions(listOf(invoice.id)) } returns MailResult.Failed(404)
            val vm = loaded()

            vm.nextAction(invoice.id)

            val state = vm.uiState.value
            assertEquals(NewNotice.NextActionFailed, state.notice)
            assertNull(state.openTask)
            assertEquals(listOf("mail:${party.id}", "mail:${invoice.id}", "task:${plumber.id}"), vm.keys())
            assertTrue(state.converting.isEmpty())
        }

    @Test
    fun anEmptyAnswerCountsAsAFailedCreation() =
        runTest {
            coEvery { mailRepo.toNextActions(listOf(invoice.id)) } returns MailResult.Ok(emptyList())
            val vm = loaded()

            vm.nextAction(invoice.id)

            assertEquals(NewNotice.NextActionFailed, vm.uiState.value.notice)
            assertNull(vm.uiState.value.openTask)
        }

    @Test
    fun noConnectionDuringTheCallSaysSo() =
        runTest {
            coEvery { mailRepo.toNextActions(listOf(invoice.id)) } returns MailResult.Offline
            val vm = loaded()

            vm.nextAction(invoice.id)

            assertEquals(NewNotice.TaskOffline, vm.uiState.value.notice)
            assertEquals(listOf("mail:${party.id}", "mail:${invoice.id}", "task:${plumber.id}"), vm.keys())
        }

    @Test
    fun offlineOrAnUnknownMailSendsNothing() =
        runTest {
            coEvery { mailRepo.messages(Mailbox.Unified, null) } returns MailResult.Offline
            cache.store(cache.session(), CachedMail(listOf(invoice), null, loadedAt))
            val vm = loaded()

            vm.nextAction(invoice.id)
            vm.nextAction(UUID.fromString("00000000-0000-4000-9000-000000000099"))

            coVerify(exactly = 0) { mailRepo.toNextActions(any()) }
            assertNull(vm.uiState.value.openTask)
        }

    @Test
    fun aSecondTapWhileItRunsSendsNothingMoreAndTheRowWaits() =
        runTest {
            val pending = CompletableDeferred<MailResult<List<MailNextAction>>>()
            coEvery { mailRepo.toNextActions(listOf(invoice.id)) } coAnswers { pending.await() }
            val vm = loaded()

            vm.nextAction(invoice.id)
            assertEquals(setOf(invoice.id), vm.uiState.value.converting)
            vm.nextAction(invoice.id)
            vm.toTask(invoice.id)
            pending.complete(MailResult.Ok(listOf(nextAction(archived = true))))

            coVerify(exactly = 1) { mailRepo.toNextActions(any()) }
            coVerify(exactly = 0) { reader.gtd(any(), any()) }
            assertEquals(taskId, vm.uiState.value.openTask)
        }

    @Test
    fun aNewActionClearsTheLastNotice() =
        runTest {
            val pending = CompletableDeferred<MailResult<List<MailNextAction>>>()
            coEvery { mailRepo.toNextActions(listOf(party.id)) } returns MailResult.Failed(409)
            coEvery { mailRepo.toNextActions(listOf(invoice.id)) } coAnswers { pending.await() }
            val vm = loaded()
            vm.nextAction(party.id)
            assertEquals(NewNotice.NextActionFailed, vm.uiState.value.notice)

            vm.nextAction(invoice.id)

            assertNull(vm.uiState.value.notice)
            pending.complete(MailResult.Ok(listOf(nextAction(archived = true))))
        }
}
