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

/** Opening a mail (marks it read), its errors, and the actions: GTD notices, archive/delete close the view. */
@OptIn(ExperimentalCoroutinesApi::class)
class MailReadViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val reader = mockk<MailReadRepository>()
    private val mail = mockk<MailRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun message(
        n: Int = 1,
        seen: Boolean = false,
    ) = MailMessage(
        id(n),
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
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { mail.markRead(any()) } returns MailResult.Ok(true)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun opened(result: MailResult<MailMessage> = MailResult.Ok(message())): MailReadViewModel {
        coEvery { reader.message(id(1)) } returns result
        return MailReadViewModel(reader, mail).also { it.open(id(1)) }
    }

    @Test
    fun `opening an unread mail shows it and marks its current copy read`() =
        runTest(dispatcher) {
            // The message moved: the server answers with its current id.
            val vm = opened(MailResult.Ok(message(n = 2)))
            assertTrue(vm.uiState.value.loading)
            advanceUntilIdle()

            assertEquals(
                id(2),
                vm.uiState.value.message
                    ?.id,
            )
            assertFalse(vm.uiState.value.loading)
            assertNull(vm.uiState.value.error)
            coVerify(exactly = 1) { mail.markRead(id(2)) }
        }

    @Test
    fun `a mail already read is not marked again, and opening it again does not reload`() =
        runTest(dispatcher) {
            val vm = opened(MailResult.Ok(message(seen = true)))
            advanceUntilIdle()
            vm.open(id(1))
            advanceUntilIdle()

            coVerify(exactly = 0) { mail.markRead(any()) }
            coVerify(exactly = 1) { reader.message(id(1)) }
        }

    @Test
    fun `a failed mark-read leaves the mail shown`() =
        runTest(dispatcher) {
            coEvery { mail.markRead(id(1)) } returns MailResult.Offline
            val vm = opened()
            advanceUntilIdle()

            assertEquals(
                id(1),
                vm.uiState.value.message
                    ?.id,
            )
            assertNull(vm.uiState.value.error)
        }

    @Test
    fun `404 means the mail is gone, other refusals failed, no connection offline`() =
        runTest(dispatcher) {
            val cases =
                mapOf(
                    MailResult.Failed(404) to MailReadError.GONE,
                    MailResult.Failed(403) to MailReadError.FAILED,
                    MailResult.Failed(null) to MailReadError.FAILED,
                    MailResult.Offline to MailReadError.OFFLINE,
                )
            cases.forEach { (result, error) ->
                val vm = opened(result)
                advanceUntilIdle()
                assertEquals("$result", error, vm.uiState.value.error)
                assertFalse(vm.uiState.value.loading)
                assertNull(vm.uiState.value.message)
            }
            coVerify(exactly = 0) { mail.markRead(any()) }
        }

    @Test
    fun `retry loads again after a failure`() =
        runTest(dispatcher) {
            val vm = opened(MailResult.Offline)
            advanceUntilIdle()
            coEvery { reader.message(id(1)) } returns MailResult.Ok(message())

            vm.retry()
            assertTrue(vm.uiState.value.loading)
            assertNull(vm.uiState.value.error)
            advanceUntilIdle()

            assertEquals(
                id(1),
                vm.uiState.value.message
                    ?.id,
            )
        }

    @Test
    fun `a created task or waiting item is reported with its title`() =
        runTest(dispatcher) {
            coEvery { reader.gtd(id(1), MailGtdAction.TASK) } returns MailResult.Ok("Quarterly report")
            coEvery { reader.gtd(id(1), MailGtdAction.WAITING) } returns MailResult.Ok("Waiting: report")
            val vm = opened()
            advanceUntilIdle()

            vm.gtd(MailGtdAction.TASK)
            assertTrue(vm.uiState.value.busy)
            advanceUntilIdle()
            assertEquals(MailReadNotice.Created(MailGtdAction.TASK, "Quarterly report"), vm.uiState.value.notice)
            assertFalse(vm.uiState.value.busy)

            vm.gtd(MailGtdAction.WAITING)
            advanceUntilIdle()
            assertEquals(MailReadNotice.Created(MailGtdAction.WAITING, "Waiting: report"), vm.uiState.value.notice)
            assertFalse(vm.uiState.value.removed)
        }

    @Test
    fun `a failed GTD action is reported, and the next action clears the notice`() =
        runTest(dispatcher) {
            coEvery { reader.gtd(id(1), MailGtdAction.WAITING) } returns MailResult.Failed(409)
            coEvery { reader.gtd(id(1), MailGtdAction.TASK) } returns MailResult.Offline
            val vm = opened()
            advanceUntilIdle()

            vm.gtd(MailGtdAction.WAITING)
            advanceUntilIdle()
            assertEquals(MailReadNotice.GtdFailed(MailGtdAction.WAITING), vm.uiState.value.notice)

            val pending = CompletableDeferred<MailResult<String>>()
            coEvery { reader.gtd(id(1), MailGtdAction.TASK) } coAnswers { pending.await() }
            vm.gtd(MailGtdAction.TASK)
            assertNull(vm.uiState.value.notice)
            pending.complete(MailResult.Offline)
            advanceUntilIdle()
            assertEquals(MailReadNotice.GtdFailed(MailGtdAction.TASK), vm.uiState.value.notice)
        }

    @Test
    fun `an action runs only once while busy`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<MailResult<String>>()
            coEvery { reader.gtd(id(1), MailGtdAction.TASK) } coAnswers { pending.await() }
            coEvery { mail.act(id(1), MailListAction.DELETE) } returns MailResult.Ok(true)
            val vm = opened()
            advanceUntilIdle()

            vm.gtd(MailGtdAction.TASK)
            advanceUntilIdle()
            vm.gtd(MailGtdAction.TASK)
            vm.act(MailListAction.DELETE)
            advanceUntilIdle()
            pending.complete(MailResult.Ok("Quarterly report"))
            advanceUntilIdle()

            coVerify(exactly = 1) { reader.gtd(id(1), MailGtdAction.TASK) }
            coVerify(exactly = 0) { mail.act(any(), any()) }
            assertFalse(vm.uiState.value.removed)
        }

    @Test
    fun `no action runs before the mail is shown`() =
        runTest(dispatcher) {
            val vm = opened(MailResult.Offline)
            advanceUntilIdle()

            vm.gtd(MailGtdAction.TASK)
            vm.act(MailListAction.ARCHIVE)
            advanceUntilIdle()

            coVerify(exactly = 0) { reader.gtd(any(), any()) }
            coVerify(exactly = 0) { mail.act(any(), any()) }
            assertFalse(vm.uiState.value.busy)
        }

    @Test
    fun `archived or deleted on the server, the mail is removed and the view closes`() =
        runTest(dispatcher) {
            MailListAction.entries.forEach { action ->
                coEvery { mail.act(id(1), action) } returns MailResult.Ok(true)
                val vm = opened()
                advanceUntilIdle()

                vm.act(action)
                advanceUntilIdle()

                assertTrue("$action", vm.uiState.value.removed)
                assertNull(vm.uiState.value.notice)
                coVerify(exactly = 1) { mail.act(id(1), action) }
            }
        }

    @Test
    fun `a refused or offline archive or delete stays open and says so`() =
        runTest(dispatcher) {
            coEvery { mail.act(id(1), MailListAction.ARCHIVE) } returns MailResult.Ok(false)
            coEvery { mail.act(id(1), MailListAction.DELETE) } returns MailResult.Offline
            val vm = opened()
            advanceUntilIdle()

            vm.act(MailListAction.ARCHIVE)
            advanceUntilIdle()
            assertEquals(MailReadNotice.ActionFailed(MailListAction.ARCHIVE), vm.uiState.value.notice)
            assertFalse(vm.uiState.value.removed)

            vm.act(MailListAction.DELETE)
            advanceUntilIdle()
            assertEquals(MailReadNotice.ActionFailed(MailListAction.DELETE), vm.uiState.value.notice)
            assertFalse(vm.uiState.value.removed)
            assertFalse(vm.uiState.value.busy)
        }
}
