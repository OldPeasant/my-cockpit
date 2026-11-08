package ch.sonensei.cockpit.mail.read

import ch.sonensei.cockpit.mail.list.MailListAction
import ch.sonensei.cockpit.mail.list.MailRepository
import ch.sonensei.cockpit.mail.list.MailResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** The read view's menu actions: mark unread, flag and unflag (optimistic), and move to a folder. */
@OptIn(ExperimentalCoroutinesApi::class)
class MailReadActionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val reader = mockk<MailReadRepository>()
    private val mail = mockk<MailRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun message(
        seen: Boolean = true,
        flagged: Boolean = false,
    ) = MailMessage(
        id(1),
        "Quarterly report",
        "Anna Example <anna@example.org>",
        emptyList(),
        emptyList(),
        Instant.EPOCH,
        seen,
        "<p>Hi</p>",
        "Hi",
        0,
        emptyList(),
        flagged = flagged,
        accountId = id(80),
        folderId = id(90),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { mail.markRead(any()) } returns MailResult.Ok(true)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.opened(message: MailMessage = message()): MailReadViewModel {
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message)
        val vm = readViewModel(reader, mail).also { it.open(id(1)) }
        advanceUntilIdle()
        return vm
    }

    private val MailReadViewModel.state get() = uiState.value

    @Test
    fun `mark unread asks the server and then returns to the list`() =
        runTest(dispatcher) {
            coEvery { reader.act(id(1), MailListAction.UNREAD, null) } returns MailResult.Ok(MailActionOutcome(true))
            val vm = opened()

            vm.act(MailListAction.UNREAD)
            advanceUntilIdle()

            assertTrue(vm.state.unread)
            assertFalse(vm.state.removed)
            assertNull(vm.state.notice)
            assertNull(vm.state.mark)
            coVerify(exactly = 1) { reader.act(id(1), MailListAction.UNREAD, null) }
        }

    @Test
    fun `mark unread waits for the mark read of the opening, so the mail ends up unread`() =
        runTest(dispatcher) {
            val markRead = CompletableDeferred<MailResult<Boolean>>()
            coEvery { mail.markRead(id(1)) } coAnswers { markRead.await() }
            coEvery { reader.act(id(1), MailListAction.UNREAD, null) } returns MailResult.Ok(MailActionOutcome(true))
            val vm = opened(message(seen = false))

            vm.act(MailListAction.UNREAD)
            advanceUntilIdle()
            coVerify(exactly = 0) { reader.act(any(), any(), any()) }
            assertTrue(vm.state.busy)

            markRead.complete(MailResult.Ok(true))
            advanceUntilIdle()

            coVerify(exactly = 1) { reader.act(id(1), MailListAction.UNREAD, null) }
            assertTrue(vm.state.unread)
        }

    @Test
    fun `a refused mark unread stays open and says the mail could not be changed`() =
        runTest(dispatcher) {
            coEvery { reader.act(id(1), MailListAction.UNREAD, null) } returns MailResult.Offline
            val vm = opened()

            vm.act(MailListAction.UNREAD)
            advanceUntilIdle()

            assertFalse(vm.state.unread)
            assertEquals(MailReadNotice.ActionFailed(MailListAction.UNREAD), vm.state.notice)
            assertFalse(vm.state.busy)
        }

    @Test
    fun `flag shows at once, and once the server took it the list is told`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<MailResult<MailActionOutcome>>()
            coEvery { reader.act(id(1), MailListAction.FLAG, null) } coAnswers { answer.await() }
            val vm = opened()

            vm.act(MailListAction.FLAG)
            advanceUntilIdle()
            assertEquals(true, vm.state.message?.flagged)
            assertNull(vm.state.mark)

            answer.complete(MailResult.Ok(MailActionOutcome(true)))
            advanceUntilIdle()

            assertEquals(true, vm.state.message?.flagged)
            assertEquals(MailMark(MailListAction.FLAG, 1), vm.state.mark)
            assertFalse(vm.state.removed)
            assertFalse(vm.state.unread)
        }

    @Test
    fun `a refused flag is taken back with a notice and the list is not told`() =
        runTest(dispatcher) {
            coEvery { reader.act(id(1), MailListAction.FLAG, null) } returns MailResult.Ok(MailActionOutcome(false, "REJECTED"))
            val vm = opened()

            vm.act(MailListAction.FLAG)
            advanceUntilIdle()

            assertEquals(false, vm.state.message?.flagged)
            assertEquals(MailReadNotice.ActionFailed(MailListAction.FLAG, "REJECTED"), vm.state.notice)
            assertNull(vm.state.mark)
        }

    @Test
    fun `unflag clears the flag, and an offline unflag puts it back`() =
        runTest(dispatcher) {
            coEvery { reader.act(id(1), MailListAction.UNFLAG, null) } returns MailResult.Ok(MailActionOutcome(true))
            val vm = opened(message(flagged = true))

            vm.act(MailListAction.UNFLAG)
            advanceUntilIdle()
            assertEquals(false, vm.state.message?.flagged)
            assertEquals(MailMark(MailListAction.UNFLAG, 1), vm.state.mark)

            coEvery { reader.act(id(1), MailListAction.FLAG, null) } returns MailResult.Offline
            vm.act(MailListAction.FLAG)
            advanceUntilIdle()
            assertEquals(false, vm.state.message?.flagged)
            assertEquals(MailReadNotice.ActionFailed(MailListAction.FLAG), vm.state.notice)
            // The last change the server took stays.
            assertEquals(MailMark(MailListAction.UNFLAG, 1), vm.state.mark)
        }

    @Test
    fun `the same flag change twice is told twice`() =
        runTest(dispatcher) {
            coEvery { reader.act(id(1), any(), null) } returns MailResult.Ok(MailActionOutcome(true))
            val vm = opened()

            vm.act(MailListAction.FLAG)
            advanceUntilIdle()
            vm.act(MailListAction.UNFLAG)
            advanceUntilIdle()
            vm.act(MailListAction.FLAG)
            advanceUntilIdle()

            assertEquals(MailMark(MailListAction.FLAG, 3), vm.state.mark)
        }

    @Test
    fun `move sends the target folder and closes the view`() =
        runTest(dispatcher) {
            coEvery { reader.act(id(1), MailListAction.MOVE, id(92)) } returns MailResult.Ok(MailActionOutcome(true))
            val vm = opened()

            vm.act(MailListAction.MOVE, id(92))
            advanceUntilIdle()

            assertTrue(vm.state.removed)
            coVerify(exactly = 1) { reader.act(id(1), MailListAction.MOVE, id(92)) }
        }

    @Test
    fun `a refused move stays open with the server's reason`() =
        runTest(dispatcher) {
            coEvery { reader.act(id(1), MailListAction.MOVE, id(92)) } returns MailResult.Ok(MailActionOutcome(false, "OTHER_ACCOUNT"))
            val vm = opened()

            vm.act(MailListAction.MOVE, id(92))
            advanceUntilIdle()

            assertFalse(vm.state.removed)
            assertEquals(MailReadNotice.ActionFailed(MailListAction.MOVE, "OTHER_ACCOUNT"), vm.state.notice)
        }

    @Test
    fun `a move without a folder and mark read are not sent`() =
        runTest(dispatcher) {
            val vm = opened()

            vm.act(MailListAction.MOVE)
            vm.act(MailListAction.READ)
            advanceUntilIdle()

            coVerify(exactly = 0) { reader.act(any(), any(), any()) }
            assertFalse(vm.state.busy)
        }

    @Test
    fun `a flag answer for a mail closed meanwhile does not change the next one`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<MailResult<MailActionOutcome>>()
            coEvery { reader.act(id(1), MailListAction.FLAG, null) } coAnswers { answer.await() }
            coEvery { reader.message(id(2)) } returns MailResult.Ok(message().copy(id = id(2)))
            val vm = opened()
            vm.act(MailListAction.FLAG)
            advanceUntilIdle()

            vm.close()
            vm.open(id(2))
            advanceUntilIdle()
            answer.complete(MailResult.Ok(MailActionOutcome(false)))
            advanceUntilIdle()

            assertEquals(id(2), vm.state.message?.id)
            assertEquals(false, vm.state.message?.flagged)
            assertNull(vm.state.notice)
            assertNull(vm.state.mark)
        }
}
