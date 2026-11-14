package ch.sonensei.cockpit.mail.list

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** "Next action & archive" on the list's multi-selection: archived mails leave, the list stays, the rest is named. */
@OptIn(ExperimentalCoroutinesApi::class)
class MailNextActionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<MailRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun task(n: Int): UUID = UUID.fromString("00000000-0000-7000-9000-%012d".format(n))

    private val work = MailAccount(id(80), "Ann Example", "me@example.org")
    private val workInbox = MailFolder(id(90), work.id, "INBOX", "INBOX", FolderRole.INBOX, 0)

    private fun mail(
        n: Int,
        subject: String = "Subject $n",
    ) = MailSummary(id(n), work.id, "Anna Example", subject, "", Instant.EPOCH, false, false, false, workInbox.id)

    private fun created(
        n: Int,
        archived: Boolean = true,
    ) = MailNextAction(id(n), task(n), "Task $n", archived, if (archived) null else "NO_ROLE_FOLDER")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(work, listOf(workInbox))))
        coEvery { repo.messages(Mailbox.Unified, null) } returns
            MailResult.Ok(MailPage(listOf(mail(1), mail(2), mail(3, subject = " "), mail(4)), null))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.loaded(): MailListViewModel {
        val vm = MailListViewModel(repo)
        vm.refresh()
        advanceUntilIdle()
        return vm
    }

    private val MailListViewModel.state get() = uiState.value

    private fun MailListViewModel.ids() = state.messages.map { it.id }

    @Test
    fun `the selected mails get one next action each, the archived ones leave and the list stays`() =
        runTest(dispatcher) {
            coEvery { repo.toNextActions(listOf(id(1), id(2))) } returns MailResult.Ok(listOf(created(1), created(2)))
            val vm = loaded()
            vm.toggleSelection(id(2))
            vm.toggleSelection(id(1))

            vm.nextActionsForSelection()
            // The selection ends at once and the action cannot run twice.
            assertFalse(vm.state.selecting)
            assertTrue(vm.state.makingNextActions)
            advanceUntilIdle()

            assertEquals(listOf(id(3), id(4)), vm.ids())
            assertEquals(MailNextActionsDone(2, emptyList(), vm.state.nextActions!!.id), vm.state.nextActions)
            assertFalse(vm.state.makingNextActions)
            assertNull(vm.state.message)
            assertEquals(Mailbox.Unified, vm.state.mailbox)
            // The list order of the selection is sent, not the order picked.
            coVerify(exactly = 1) { repo.toNextActions(listOf(id(1), id(2))) }
        }

    @Test
    fun `a mail not archived stays in the list and is named by its subject, or the task title without one`() =
        runTest(dispatcher) {
            coEvery { repo.toNextActions(listOf(id(1), id(2), id(3))) } returns
                MailResult.Ok(listOf(created(1), created(2, archived = false), created(3, archived = false)))
            val vm = loaded()
            listOf(1, 2, 3).forEach { vm.toggleSelection(id(it)) }

            vm.nextActionsForSelection()
            advanceUntilIdle()

            assertEquals(listOf(id(2), id(3), id(4)), vm.ids())
            val done = vm.state.nextActions!!
            assertEquals(3, done.created)
            assertEquals(listOf("Subject 2", "Task 3"), done.notArchived)
        }

    @Test
    fun `a failed call leaves every mail in place with a message`() =
        runTest(dispatcher) {
            listOf(MailResult.Failed(404), MailResult.Offline).forEach { failure ->
                coEvery { repo.toNextActions(listOf(id(1), id(2))) } returns failure
                val vm = loaded()
                vm.toggleSelection(id(1))
                vm.toggleSelection(id(2))

                vm.nextActionsForSelection()
                advanceUntilIdle()

                assertEquals("$failure", listOf(id(1), id(2), id(3), id(4)), vm.ids())
                assertEquals("$failure", MailListMessage.NEXT_ACTIONS_FAILED, vm.state.message)
                assertNull("$failure", vm.state.nextActions)
                assertFalse("$failure", vm.state.makingNextActions)
            }
        }

    @Test
    fun `without a selection, or while one runs, nothing is sent`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<MailResult<List<MailNextAction>>>()
            coEvery { repo.toNextActions(any()) } coAnswers { pending.await() }
            val vm = loaded()

            vm.nextActionsForSelection()
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.toNextActions(any()) }

            vm.toggleSelection(id(1))
            vm.nextActionsForSelection()
            advanceUntilIdle()
            vm.toggleSelection(id(2))
            vm.nextActionsForSelection()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.toNextActions(any()) }
            // The second selection is still there for when the first call is done.
            assertEquals(setOf(id(2)), vm.state.selected)

            pending.complete(MailResult.Ok(listOf(created(1))))
            advanceUntilIdle()
            assertEquals(listOf(id(2), id(3), id(4)), vm.ids())
        }

    @Test
    fun `the snackbar is dismissed only for its own answer, and two answers are told apart`() =
        runTest(dispatcher) {
            coEvery { repo.toNextActions(listOf(id(1))) } returns MailResult.Ok(listOf(created(1)))
            coEvery { repo.toNextActions(listOf(id(2))) } returns MailResult.Ok(listOf(created(2)))
            val vm = loaded()
            vm.toggleSelection(id(1))
            vm.nextActionsForSelection()
            advanceUntilIdle()
            val first = vm.state.nextActions!!
            vm.toggleSelection(id(2))
            vm.nextActionsForSelection()
            advanceUntilIdle()
            val second = vm.state.nextActions!!
            assertEquals(first.created, second.created)
            assertNotEquals(first, second)

            vm.dismissNextActions(first)
            assertEquals(second, vm.state.nextActions)
            vm.dismissNextActions(second)
            assertNull(vm.state.nextActions)
        }
}
