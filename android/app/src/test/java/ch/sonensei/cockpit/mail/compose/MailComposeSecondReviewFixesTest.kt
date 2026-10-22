package ch.sonensei.cockpit.mail.compose

import androidx.compose.ui.text.input.TextFieldValue
import ch.sonensei.cockpit.mail.list.MailAccount
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Regressions of the second review of the compose task: kept attachments over a version another client renumbered,
 * a send or edit while leaving, and a double retry while a reply is prepared.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MailComposeSecondReviewFixesTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<MailComposeRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Work", "me@example.org")
    private val forward = ComposeMode.Forward(id(5))

    private fun draft(
        etag: String = "\"1\"",
        attachments: List<DraftAttachment> = emptyList(),
    ) = Draft(id(1), work.id, etag, emptyList(), emptyList(), emptyList(), "", "", "", attachments)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.accounts() } returns MailResult.Ok(listOf(work))
        coEvery { repository.create(any(), any()) } returns MailResult.Ok(draft())
        coEvery { repository.update(any(), any(), any()) } returns MailResult.Ok(draft("\"2\""))
        coEvery { repository.discard(any(), any()) } returns MailResult.Ok(Unit)
        coEvery { repository.send(any(), any()) } returns SendOutcome.Sent
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.forwardWith(vararg attachments: DraftAttachment): MailComposeViewModel {
        coEvery { repository.start(forward) } returns MailResult.Ok(draft(attachments = attachments.toList()))
        val vm = MailComposeViewModel(repository)
        vm.start(forward)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `a save over a version that renumbered its parts keeps the attachments by file, not by part id`() =
        runTest(dispatcher) {
            val vm = forwardWith(DraftAttachment("2", "a.pdf", 10), DraftAttachment("3", "b.pdf", 20))
            coEvery { repository.update(id(1), "\"1\"", any()) } returns MailResult.Failed(412)
            // Another client removed a and added c: b is part 2 now, part 3 is c.
            coEvery { repository.get(id(1)) } returns
                MailResult.Ok(draft("\"4\"", listOf(DraftAttachment("2", "b.pdf", 20), DraftAttachment("3", "c.pdf", 30))))
            coEvery { repository.update(id(1), "\"4\"", any()) } returns
                MailResult.Ok(draft("\"5\"", listOf(DraftAttachment("2", "b.pdf", 20))))

            vm.removeAttachment("2")
            vm.save()
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.update(id(1), "\"1\"", match { it.keep == listOf("3") }) }
            coVerify(exactly = 1) { repository.update(id(1), "\"4\"", match { it.keep == listOf("2") }) }
            assertEquals(
                listOf("b.pdf"),
                vm.uiState.value.attachments
                    .map { it.name },
            )
        }

    @Test
    fun `a send after a conflict with a renumbered version sends the attachments kept on screen`() =
        runTest(dispatcher) {
            val vm = forwardWith(DraftAttachment("2", "a.pdf", 10), DraftAttachment("3", "b.pdf", 20))
            coEvery { repository.update(id(1), "\"1\"", any()) } returns
                MailResult.Ok(draft("\"2\"", listOf(DraftAttachment("2", "b.pdf", 20))))
            coEvery { repository.send(id(1), "\"2\"") } returns SendOutcome.Conflict
            // Another client added c in front: c is part 2 now, b is part 3.
            coEvery { repository.get(id(1)) } returns
                MailResult.Ok(draft("\"4\"", listOf(DraftAttachment("2", "c.pdf", 30), DraftAttachment("3", "b.pdf", 20))))
            coEvery { repository.update(id(1), "\"4\"", any()) } returns
                MailResult.Ok(draft("\"5\"", listOf(DraftAttachment("2", "b.pdf", 20))))
            coEvery { repository.send(id(1), "\"5\"") } returns SendOutcome.Sent

            vm.setTo("ann@example.org")
            vm.removeAttachment("2")
            vm.send()
            advanceUntilIdle()

            assertEquals(ComposePhase.SENT, vm.uiState.value.phase)
            coVerify(exactly = 1) { repository.update(id(1), "\"4\"", match { it.keep == listOf("3") }) }
            coVerify(exactly = 1) { repository.send(id(1), "\"5\"") }
        }

    @Test
    fun `a send or an edit while leaving saves is ignored and the screen closes`() =
        runTest(dispatcher) {
            val saved = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.create(any(), any()) } coAnswers { saved.await() }
            val vm = MailComposeViewModel(repository)
            vm.start(ComposeMode.New(work.id))
            advanceUntilIdle()
            vm.setTo("ann@example.org")
            vm.setBody(TextFieldValue("Hello"))

            vm.leave()
            runCurrent()
            assertTrue(vm.uiState.value.leaving)
            assertFalse(vm.uiState.value.editable)
            vm.send()
            vm.setBody(TextFieldValue("Hello again"))
            vm.discard()
            runCurrent()
            assertEquals("Hello", vm.uiState.value.body.text)

            saved.complete(MailResult.Ok(draft()))
            advanceUntilIdle()

            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
            coVerify(exactly = 0) { repository.send(any(), any()) }
            coVerify(exactly = 0) { repository.update(any(), any(), any()) }
            coVerify(exactly = 0) { repository.discard(any(), any()) }
        }

    @Test
    fun `a leave that cannot save unlocks the screen and offers to leave without saving`() =
        runTest(dispatcher) {
            coEvery { repository.create(any(), any()) } returns MailResult.Offline
            val vm = MailComposeViewModel(repository)
            vm.start(ComposeMode.New(work.id))
            advanceUntilIdle()
            vm.setBody(TextFieldValue("Hello"))

            vm.leave()
            advanceUntilIdle()

            val s = vm.uiState.value
            assertEquals(ComposePhase.EDITING, s.phase)
            assertTrue(s.leaveBlocked)
            assertFalse(s.leaving)
            assertTrue(s.editable)
        }

    @Test
    fun `a double retry while a reply is prepared starts it only once`() =
        runTest(dispatcher) {
            val mode = ComposeMode.Reply(id(5))
            val prepared = CompletableDeferred<MailResult<Draft>>()
            var calls = 0
            coEvery { repository.start(mode) } coAnswers {
                calls++
                if (calls == 1) MailResult.Offline else prepared.await()
            }
            val vm = MailComposeViewModel(repository)
            vm.start(mode)
            advanceUntilIdle()
            assertEquals(ComposeProblem.OFFLINE, vm.uiState.value.problem)

            vm.retry()
            vm.retry()
            runCurrent()
            vm.retry()
            prepared.complete(MailResult.Ok(draft()))
            advanceUntilIdle()

            coVerify(exactly = 2) { repository.start(mode) }
            assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)
        }
}
