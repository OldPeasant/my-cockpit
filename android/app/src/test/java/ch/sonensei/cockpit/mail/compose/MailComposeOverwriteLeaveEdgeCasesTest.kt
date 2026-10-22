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
 * Edge cases of the second review fixes: equal and inline attachments kept over a renumbered version, a second back
 * or an attach while leaving, a retry while a new mail's accounts load, and leaving an untouched reply.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MailComposeOverwriteLeaveEdgeCasesTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<MailComposeRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Work", "me@example.org")
    private val forward = ComposeMode.Forward(id(5))

    private fun draft(
        etag: String = "\"1\"",
        attachments: List<DraftAttachment> = emptyList(),
    ) = Draft(id(1), work.id, etag, emptyList(), emptyList(), emptyList(), "", "", "", attachments)

    private fun file(name: String) =
        object : PickedFile {
            override val name = name
            override val mime = "application/pdf"
            override val sizeBytes: Long? = 3

            override fun open() = "pdf".byteInputStream()
        }

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
    fun `an overwrite keeps as many equal files as were kept, not every equal file of the newest version`() =
        runTest(dispatcher) {
            val vm =
                forwardWith(
                    DraftAttachment("2", "scan.pdf", 10),
                    DraftAttachment("3", "scan.pdf", 10),
                    DraftAttachment("4", "notes.txt", 5),
                )
            coEvery { repository.update(id(1), "\"1\"", any()) } returns MailResult.Failed(412)
            // Another client attached a third equal scan.pdf in front: the newest version has three of them.
            coEvery { repository.get(id(1)) } returns
                MailResult.Ok(
                    draft(
                        "\"4\"",
                        listOf(
                            DraftAttachment("2", "scan.pdf", 10),
                            DraftAttachment("3", "scan.pdf", 10),
                            DraftAttachment("4", "scan.pdf", 10),
                            DraftAttachment("5", "notes.txt", 5),
                        ),
                    ),
                )
            coEvery { repository.update(id(1), "\"4\"", any()) } returns MailResult.Ok(draft("\"5\""))

            vm.removeAttachment("4")
            vm.save()
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.update(id(1), "\"4\"", match { it.keep == listOf("2", "3") }) }
        }

    @Test
    fun `an overwrite does not keep an inline part or a file of another size that shares the kept name`() =
        runTest(dispatcher) {
            val vm = forwardWith(DraftAttachment("2", "logo.png", 10), DraftAttachment("3", "plan.pdf", 20))
            coEvery { repository.update(id(1), "\"1\"", any()) } returns MailResult.Failed(412)
            // logo.png is inline now, plan.pdf was replaced by a larger file of the same name.
            coEvery { repository.get(id(1)) } returns
                MailResult.Ok(
                    draft(
                        "\"4\"",
                        listOf(DraftAttachment("2", "logo.png", 10, inline = true), DraftAttachment("3", "plan.pdf", 99)),
                    ),
                )
            coEvery { repository.update(id(1), "\"4\"", any()) } returns MailResult.Ok(draft("\"5\""))

            vm.setBody(TextFieldValue("See attached"))
            vm.save()
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.update(id(1), "\"1\"", match { it.keep == listOf("2", "3") }) }
            coVerify(exactly = 1) { repository.update(id(1), "\"4\"", match { it.keep.isEmpty() }) }
        }

    @Test
    fun `a second back while leaving saves does not save again`() =
        runTest(dispatcher) {
            val saved = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.create(any(), any()) } coAnswers { saved.await() }
            val vm = MailComposeViewModel(repository)
            vm.start(ComposeMode.New(work.id))
            advanceUntilIdle()
            vm.setBody(TextFieldValue("Hello"))

            vm.leave()
            runCurrent()
            vm.leave()
            runCurrent()
            saved.complete(MailResult.Ok(draft()))
            advanceUntilIdle()

            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
            assertFalse(vm.uiState.value.leaving)
            coVerify(exactly = 1) { repository.create(any(), any()) }
        }

    @Test
    fun `a file picked while leaving saves is not uploaded`() =
        runTest(dispatcher) {
            val saved = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.create(any(), any()) } coAnswers { saved.await() }
            val vm = MailComposeViewModel(repository)
            vm.start(ComposeMode.New(work.id))
            advanceUntilIdle()
            vm.setBody(TextFieldValue("Hello"))

            vm.leave()
            runCurrent()
            vm.attach(listOf(file("late.pdf")))
            assertTrue(
                vm.uiState.value.uploading
                    .isEmpty(),
            )
            saved.complete(MailResult.Ok(draft()))
            advanceUntilIdle()

            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
            coVerify(exactly = 0) { repository.attach(any(), any(), any()) }
        }

    @Test
    fun `leaving an untouched forward locks the screen until its draft is discarded`() =
        runTest(dispatcher) {
            val gone = CompletableDeferred<MailResult<Unit>>()
            val vm = forwardWith()
            coEvery { repository.discard(id(1), "\"1\"") } coAnswers { gone.await() }

            vm.leave()
            runCurrent()
            assertTrue(vm.uiState.value.leaving)
            assertFalse(vm.uiState.value.editable)
            vm.setSubject("Too late")
            vm.discard()
            runCurrent()

            gone.complete(MailResult.Ok(Unit))
            advanceUntilIdle()

            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
            assertFalse(vm.uiState.value.leaving)
            coVerify(exactly = 1) { repository.discard(id(1), "\"1\"") }
            coVerify(exactly = 0) { repository.update(any(), any(), any()) }
        }

    @Test
    fun `a retry while a new mail's accounts load does not load them twice`() =
        runTest(dispatcher) {
            val accounts = CompletableDeferred<MailResult<List<MailAccount>>>()
            var calls = 0
            coEvery { repository.accounts() } coAnswers {
                calls++
                if (calls == 1) MailResult.Offline else accounts.await()
            }
            val vm = MailComposeViewModel(repository)
            vm.start(ComposeMode.New())
            advanceUntilIdle()
            assertEquals(ComposeProblem.OFFLINE, vm.uiState.value.problem)

            vm.retry()
            runCurrent()
            vm.retry()
            runCurrent()
            accounts.complete(MailResult.Ok(listOf(work)))
            advanceUntilIdle()

            coVerify(exactly = 2) { repository.accounts() }
            assertEquals(work.id, vm.uiState.value.accountId)
            assertEquals(null, vm.uiState.value.problem)
        }

    @Test
    fun `a retry after the second start failed too starts it again`() =
        runTest(dispatcher) {
            val mode = ComposeMode.Reply(id(5))
            var calls = 0
            coEvery { repository.start(mode) } coAnswers {
                calls++
                if (calls < 3) MailResult.Failed(500) else MailResult.Ok(draft())
            }
            val vm = MailComposeViewModel(repository)
            vm.start(mode)
            advanceUntilIdle()
            vm.retry()
            advanceUntilIdle()
            assertEquals(ComposeProblem.START_FAILED, vm.uiState.value.problem)

            vm.retry()
            advanceUntilIdle()

            coVerify(exactly = 3) { repository.start(mode) }
            assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)
        }
}
