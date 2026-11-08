package ch.sonensei.cockpit.mail.read

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

/** "Create note" in the read view model: busy while it runs, a notice with the note's title or a failure. */
@OptIn(ExperimentalCoroutinesApi::class)
class MailReadNoteViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val reader = mockk<MailReadRepository>()
    private val mail = mockk<MailRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun message(n: Int = 1) =
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
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun opened(result: MailResult<MailMessage> = MailResult.Ok(message())): MailReadViewModel {
        coEvery { reader.message(id(1)) } returns result
        return readViewModel(reader, mail).also { it.open(id(1)) }
    }

    @Test
    fun `a created note is reported with its title and the mail stays open`() =
        runTest(dispatcher) {
            coEvery { reader.gtd(id(1), MailGtdAction.NOTE) } returns MailResult.Ok("Quarterly report")
            val vm = opened()
            advanceUntilIdle()

            vm.gtd(MailGtdAction.NOTE)
            assertTrue(vm.uiState.value.busy)
            advanceUntilIdle()

            assertEquals(MailReadNotice.Created(MailGtdAction.NOTE, "Quarterly report"), vm.uiState.value.notice)
            assertFalse(vm.uiState.value.busy)
            assertFalse(vm.uiState.value.removed)
            assertEquals(
                id(1),
                vm.uiState.value.message
                    ?.id,
            )
            coVerify(exactly = 1) { reader.gtd(id(1), MailGtdAction.NOTE) }
            coVerify(exactly = 0) { reader.gtd(id(1), MailGtdAction.TASK) }
            coVerify(exactly = 0) { reader.gtd(id(1), MailGtdAction.WAITING) }
        }

    @Test
    fun `a note the server refused or could not be reached for is reported as failed`() =
        runTest(dispatcher) {
            val vm = opened()
            advanceUntilIdle()

            listOf<MailResult<String>>(MailResult.Failed(500),MailResult.Failed(null), MailResult.Offline).forEach { result ->
                coEvery { reader.gtd(id(1), MailGtdAction.NOTE) } returns result
                vm.gtd(MailGtdAction.NOTE)
                advanceUntilIdle()
                assertEquals("$result", MailReadNotice.GtdFailed(MailGtdAction.NOTE), vm.uiState.value.notice)
                assertFalse("$result", vm.uiState.value.busy)
                assertFalse("$result", vm.uiState.value.removed)
            }
        }

    @Test
    fun `a second tap on create note while one runs creates only one note`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<MailResult<String>>()
            coEvery { reader.gtd(id(1), MailGtdAction.NOTE) } coAnswers { pending.await() }
            val vm = opened()
            advanceUntilIdle()

            vm.gtd(MailGtdAction.NOTE)
            advanceUntilIdle()
            vm.gtd(MailGtdAction.NOTE)
            vm.gtd(MailGtdAction.TASK)
            assertNull(vm.uiState.value.notice)
            pending.complete(MailResult.Ok("Quarterly report"))
            advanceUntilIdle()

            coVerify(exactly = 1) { reader.gtd(id(1), MailGtdAction.NOTE) }
            coVerify(exactly = 0) { reader.gtd(id(1), MailGtdAction.TASK) }
            assertEquals(MailReadNotice.Created(MailGtdAction.NOTE, "Quarterly report"), vm.uiState.value.notice)
        }

    @Test
    fun `no note is created before the mail is shown`() =
        runTest(dispatcher) {
            val vm = opened(MailResult.Offline)
            advanceUntilIdle()

            vm.gtd(MailGtdAction.NOTE)
            advanceUntilIdle()

            coVerify(exactly = 0) { reader.gtd(any(), any()) }
            assertNull(vm.uiState.value.notice)
            assertFalse(vm.uiState.value.busy)
        }

    @Test
    fun `a note answer for a closed mail is not shown on the next one`() =
        runTest(dispatcher) {
            val created = CompletableDeferred<MailResult<String>>()
            coEvery { reader.gtd(id(1), MailGtdAction.NOTE) } coAnswers { created.await() }
            coEvery { reader.message(id(2)) } returns MailResult.Ok(message(n = 2))
            val vm = opened()
            advanceUntilIdle()

            vm.gtd(MailGtdAction.NOTE)
            advanceUntilIdle()
            vm.close()
            vm.open(id(2))
            advanceUntilIdle()
            created.complete(MailResult.Ok("Quarterly report"))
            advanceUntilIdle()

            assertEquals(
                id(2),
                vm.uiState.value.message
                    ?.id,
            )
            assertNull(vm.uiState.value.notice)
            assertFalse(vm.uiState.value.busy)
        }
}
