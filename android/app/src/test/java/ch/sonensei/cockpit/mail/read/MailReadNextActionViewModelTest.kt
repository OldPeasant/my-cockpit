package ch.sonensei.cockpit.mail.read

import ch.sonensei.cockpit.mail.list.MailNextAction
import ch.sonensei.cockpit.mail.list.MailRepository
import ch.sonensei.cockpit.mail.list.MailResult
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
import java.time.Instant
import java.util.UUID

/** "Next action & archive" in the read view: the task opens, a failed archive keeps it with a notice, a failed creation keeps the mail. */
@OptIn(ExperimentalCoroutinesApi::class)
class MailReadNextActionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val reader = mockk<MailReadRepository>()
    private val mail = mockk<MailRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val taskId: UUID = UUID.fromString("00000000-0000-7000-9000-000000000001")

    private fun message(n: Int) =
        MailMessage(
            id(n),
            "Quarterly report",
            "Anna Example <anna@example.org>",
            emptyList(),
            emptyList(),
            Instant.EPOCH,
            true,
            "<p>Hi</p>",
            "Hi",
            0,
            emptyList(),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { reader.message(id(1)) } returns MailResult.Ok(message(1))
        coEvery { reader.message(id(2)) } returns MailResult.Ok(message(2))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun opened(): MailReadViewModel = readViewModel(reader, mail).also { it.open(id(1)) }

    private fun created(
        archived: Boolean = true,
        errorCode: String? = null,
    ) = MailNextAction(id(1), taskId, "Quarterly report", archived, errorCode)

    @Test
    fun `a created and archived next action opens its task, without a notice`() =
        runTest(dispatcher) {
            coEvery { mail.toNextActions(listOf(id(1))) } returns MailResult.Ok(listOf(created()))
            val vm = opened()
            advanceUntilIdle()

            vm.nextAction()
            assertTrue(vm.uiState.value.busy)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(created(), state.nextAction)
            assertNull(state.notice)
            assertFalse(state.busy)
            // The view itself does not close: the route leaves for the list when the task detail is left.
            assertFalse(state.removed)
            coVerify(exactly = 1) { mail.toNextActions(listOf(id(1))) }
        }

    @Test
    fun `a failed archive still opens the task and names the mail with the server's reason`() =
        runTest(dispatcher) {
            coEvery { mail.toNextActions(listOf(id(1))) } returns
                MailResult.Ok(listOf(created(archived = false, errorCode = "NO_ROLE_FOLDER")))
            val vm = opened()
            advanceUntilIdle()

            vm.nextAction()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(taskId, state.nextAction?.taskId)
            assertEquals(MailReadNotice.NotArchived("Quarterly report", "NO_ROLE_FOLDER"), state.notice)
            assertEquals(id(1), state.message?.id)
            assertFalse(state.removed)
        }

    @Test
    fun `a failed creation leaves the mail in place with an error`() =
        runTest(dispatcher) {
            val failures =
                listOf<MailResult<List<MailNextAction>>>(
                    MailResult.Failed(404),
                    MailResult.Failed(null),
                    MailResult.Offline,
                    MailResult.Ok(emptyList()),
                )
            failures.forEach { failure ->
                coEvery { mail.toNextActions(listOf(id(1))) } returns failure
                val vm = opened()
                advanceUntilIdle()

                vm.nextAction()
                advanceUntilIdle()

                val state = vm.uiState.value
                assertEquals("$failure", MailReadNotice.NextActionFailed, state.notice)
                assertNull("$failure", state.nextAction)
                assertEquals("$failure", id(1), state.message?.id)
                assertFalse("$failure", state.removed)
                assertFalse("$failure", state.busy)
            }
        }

    @Test
    fun `closing the task detail forgets it, the mail stays loaded`() =
        runTest(dispatcher) {
            coEvery { mail.toNextActions(listOf(id(1))) } returns
                MailResult.Ok(listOf(created(archived = false, errorCode = "NO_ROLE_FOLDER")))
            val vm = opened()
            advanceUntilIdle()
            vm.nextAction()
            advanceUntilIdle()

            vm.nextActionShown()

            assertNull(vm.uiState.value.nextAction)
            // The notice naming the unarchived mail is still there when the mail shows again.
            assertEquals(MailReadNotice.NotArchived("Quarterly report", "NO_ROLE_FOLDER"), vm.uiState.value.notice)
            assertEquals(
                id(1),
                vm.uiState.value.message
                    ?.id,
            )
        }

    @Test
    fun `a second tap while the call runs sends nothing more`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<MailResult<List<MailNextAction>>>()
            coEvery { mail.toNextActions(listOf(id(1))) } coAnswers { pending.await() }
            val vm = opened()
            advanceUntilIdle()

            vm.nextAction()
            advanceUntilIdle()
            vm.nextAction()
            pending.complete(MailResult.Ok(listOf(created())))
            advanceUntilIdle()

            coVerify(exactly = 1) { mail.toNextActions(any()) }
            assertEquals(created(), vm.uiState.value.nextAction)
        }

    @Test
    fun `without a loaded mail nothing is sent`() =
        runTest(dispatcher) {
            coEvery { reader.message(id(1)) } returns MailResult.Offline
            val vm = opened()
            advanceUntilIdle()

            vm.nextAction()
            advanceUntilIdle()

            coVerify(exactly = 0) { mail.toNextActions(any()) }
        }

    @Test
    fun `an answer arriving after another mail was opened is dropped`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<MailResult<List<MailNextAction>>>()
            coEvery { mail.toNextActions(listOf(id(1))) } coAnswers { pending.await() }
            val vm = opened()
            advanceUntilIdle()

            vm.nextAction()
            advanceUntilIdle()
            vm.open(id(2))
            advanceUntilIdle()
            pending.complete(MailResult.Ok(listOf(created())))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(id(2), state.message?.id)
            assertNull(state.nextAction)
            assertNull(state.notice)
            assertFalse(state.busy)
        }
}
