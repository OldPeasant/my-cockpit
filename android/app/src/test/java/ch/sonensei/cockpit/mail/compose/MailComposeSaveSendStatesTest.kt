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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.util.UUID

/**
 * Draft save and send states the other view model tests leave out: recovery paths of send and discard, an autosave
 * still waiting when the compose ends, and work that answers after the compose was left or discarded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MailComposeSaveSendStatesTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<MailComposeRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Work", "me@example.org")

    private fun draft(
        etag: String = "\"1\"",
        attachments: List<DraftAttachment> = emptyList(),
    ) = Draft(id(1), work.id, etag, emptyList(), emptyList(), emptyList(), "", "", "", attachments)

    private fun file(name: String) =
        object : PickedFile {
            override val name = name
            override val mime = "text/plain"
            override val sizeBytes: Long? = null

            override fun open(): InputStream? = null
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.accounts() } returns MailResult.Ok(listOf(work))
        coEvery { repository.create(any(), any()) } returns MailResult.Ok(draft())
        coEvery { repository.update(any(), any(), any()) } returns MailResult.Ok(draft("\"2\""))
        coEvery { repository.discard(any(), any()) } returns MailResult.Ok(Unit)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newCompose(): MailComposeViewModel {
        val vm = MailComposeViewModel(repository)
        vm.start(ComposeMode.New(work.id))
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `a send refused as invalid when the fields hold only separators says a recipient is missing`() =
        runTest(dispatcher) {
            coEvery { repository.send(any(), any()) } returns SendOutcome.Invalid
            val vm = newCompose()
            vm.setTo(" , ; ")

            vm.send()
            advanceUntilIdle()

            assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)
            assertEquals(ComposeProblem.NO_RECIPIENT, vm.uiState.value.problem)
        }

    @Test
    fun `a send conflict whose newest version cannot be fetched says offline and keeps the text`() =
        runTest(dispatcher) {
            coEvery { repository.send(id(1), "\"1\"") } returns SendOutcome.Conflict
            coEvery { repository.get(id(1)) } returns MailResult.Offline
            val vm = newCompose()
            vm.setTo("ann@example.org")
            vm.setBody(TextFieldValue("Hello"))

            vm.send()
            advanceUntilIdle()

            val s = vm.uiState.value
            assertEquals(ComposePhase.EDITING, s.phase)
            assertEquals(ComposeProblem.OFFLINE, s.problem)
            assertEquals("Hello", s.body.text)
            coVerify(exactly = 0) { repository.update(any(), any(), any()) }
            coVerify(exactly = 1) { repository.send(any(), any()) }
        }

    @Test
    fun `after a conflict the stored version is the newest, so the next send uses it without saving again`() =
        runTest(dispatcher) {
            val kept = DraftAttachment("2", "plan.pdf", 10)
            coEvery { repository.send(id(1), "\"1\"") } returns SendOutcome.Conflict
            coEvery { repository.get(id(1)) } returns MailResult.Ok(draft("\"4\"", listOf(kept)))
            coEvery { repository.update(id(1), "\"4\"", any()) } returns MailResult.Ok(draft("\"5\"", listOf(kept)))
            coEvery { repository.send(id(1), "\"5\"") } returns SendOutcome.Rejected andThen SendOutcome.Sent
            val vm = newCompose()
            vm.setTo("ann@example.org")

            vm.send()
            advanceUntilIdle()
            assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)
            assertEquals(ComposeProblem.REJECTED, vm.uiState.value.problem)
            assertEquals(listOf(kept), vm.uiState.value.attachments)

            vm.send()
            advanceUntilIdle()

            assertEquals(ComposePhase.SENT, vm.uiState.value.phase)
            coVerify(exactly = 1) { repository.update(any(), any(), any()) }
            coVerify(exactly = 2) { repository.send(id(1), "\"5\"") }
        }

    @Test
    fun `an autosave still waiting when the mail is sent never runs`() =
        runTest(dispatcher) {
            coEvery { repository.send(any(), any()) } returns SendOutcome.Sent
            val vm = newCompose()
            vm.setTo("ann@example.org")
            vm.setBody(TextFieldValue("Hello"))

            vm.send()
            advanceUntilIdle()
            advanceTimeBy(30_000)
            runCurrent()

            assertEquals(ComposePhase.SENT, vm.uiState.value.phase)
            coVerify(exactly = 1) { repository.create(any(), any()) }
            coVerify(exactly = 0) { repository.update(any(), any(), any()) }
        }

    @Test
    fun `leaving saves at once and the autosave that was waiting does not write again`() =
        runTest(dispatcher) {
            val vm = newCompose()
            vm.setBody(TextFieldValue("Draft"))
            advanceTimeBy(5_000)

            vm.leave()
            runCurrent()
            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
            advanceTimeBy(30_000)
            runCurrent()

            coVerify(exactly = 1) { repository.create(work.id, match { it.html == "<p>Draft</p>" }) }
            coVerify(exactly = 0) { repository.update(any(), any(), any()) }
        }

    @Test
    fun `discarding a reply the server still prepares closes at once and deletes it once it exists`() =
        runTest(dispatcher) {
            val prepared = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.start(ComposeMode.Reply(id(5))) } coAnswers { prepared.await() }
            val vm = MailComposeViewModel(repository)
            vm.start(ComposeMode.Reply(id(5)))
            runCurrent()
            assertEquals(ComposePhase.STARTING, vm.uiState.value.phase)

            vm.discard()
            runCurrent()
            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)

            prepared.complete(MailResult.Ok(draft()))
            advanceUntilIdle()

            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
            coVerify(exactly = 1) { repository.discard(id(1), "\"1\"") }
        }

    @Test
    fun `files still queued for upload are dropped once the draft is discarded`() =
        runTest(dispatcher) {
            val first = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.attach(id(1), "\"1\"", any()) } coAnswers { first.await() }
            val vm = newCompose()
            vm.attach(listOf(file("one.txt"), file("two.txt")))
            runCurrent()
            coVerify(exactly = 1) { repository.attach(any(), any(), any()) }

            vm.discard()
            runCurrent()
            first.complete(MailResult.Ok(draft("\"2\"", listOf(DraftAttachment("2", "one.txt", 1)))))
            advanceUntilIdle()

            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
            coVerify(exactly = 1) { repository.attach(any(), any(), any()) }
            coVerify(exactly = 1) { repository.discard(id(1), "\"2\"") }
        }

    @Test
    fun `a discard of a changed draft whose newest version cannot be fetched stays open and says why`() =
        runTest(dispatcher) {
            coEvery { repository.discard(id(1), "\"1\"") } returns MailResult.Failed(412)
            coEvery { repository.get(id(1)) } returns MailResult.Offline
            val vm = newCompose()
            vm.setBody(TextFieldValue("x"))
            vm.save()
            advanceUntilIdle()

            vm.discard()
            advanceUntilIdle()
            assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)
            assertEquals(ComposeProblem.OFFLINE, vm.uiState.value.problem)

            coEvery { repository.get(id(1)) } returns MailResult.Failed(500)
            vm.discard()
            advanceUntilIdle()
            assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)
            assertEquals(ComposeProblem.DISCARD_FAILED, vm.uiState.value.problem)
            assertEquals("x", vm.uiState.value.body.text)
        }

    @Test
    fun `a new mail left before its accounts arrived stays closed and does not retry`() =
        runTest(dispatcher) {
            val accounts = CompletableDeferred<MailResult<List<MailAccount>>>()
            coEvery { repository.accounts() } coAnswers { accounts.await() }
            val vm = MailComposeViewModel(repository)
            vm.start(ComposeMode.New(work.id))
            runCurrent()

            vm.leave()
            accounts.complete(MailResult.Offline)
            advanceUntilIdle()
            vm.retry()
            advanceUntilIdle()

            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
            assertNull(vm.uiState.value.problem)
            coVerify(exactly = 1) { repository.accounts() }
        }

    @Test
    fun `a removal made during a write drops one of the equal attachments, not an inline part of the same name`() {
        val before =
            listOf(
                DraftAttachment("2", "scan.pdf", 10),
                DraftAttachment("3", "scan.pdf", 10),
                DraftAttachment("4", "logo.png", 5, inline = true),
            )
        // The user removed part 2 while the write ran; the server's new version numbers the parts anew.
        val now = before.filterNot { it.partId == "2" }
        val returned =
            listOf(
                DraftAttachment("5", "scan.pdf", 10, inline = true),
                DraftAttachment("6", "scan.pdf", 10),
                DraftAttachment("7", "scan.pdf", 10),
                DraftAttachment("8", "logo.png", 5, inline = true),
            )

        assertEquals(listOf("5", "7", "8"), returned.withoutRemoved(before, now).map { it.partId })
        assertEquals(returned, returned.withoutRemoved(before, before))
    }

    @Test
    fun `a reply left while it is prepared shows no problem when the server then fails`() =
        runTest(dispatcher) {
            val prepared = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.start(ComposeMode.Forward(id(5))) } coAnswers { prepared.await() }
            val vm = MailComposeViewModel(repository)
            vm.start(ComposeMode.Forward(id(5)))
            runCurrent()

            vm.leave()
            prepared.complete(MailResult.Failed(500))
            advanceUntilIdle()

            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
            assertNull(vm.uiState.value.problem)
        }
}
