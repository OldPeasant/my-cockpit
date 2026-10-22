package ch.sonensei.cockpit.mail.compose

import androidx.compose.ui.text.input.TextFieldValue
import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.mail.list.MailResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
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
 * Regressions of the review of the compose task: work of a closed compose, attachments removed while a write runs,
 * an autosave that already writes, and a draft another client changed before a discard or send.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MailComposeReviewFixesTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<MailComposeRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Work", "me@example.org")

    private fun draft(
        etag: String = "\"1\"",
        draftId: UUID = id(1),
        attachments: List<DraftAttachment> = emptyList(),
    ) = Draft(draftId, work.id, etag, emptyList(), emptyList(), emptyList(), "", "", "", attachments)

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

    private fun TestScope.newCompose(vm: MailComposeViewModel = MailComposeViewModel(repository)): MailComposeViewModel {
        vm.start(ComposeMode.New(work.id))
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `an upload of a left compose ends in its own draft, not in the next compose`() =
        runTest(dispatcher) {
            coEvery { repository.create(any(), any()) } returns MailResult.Ok(draft()) andThen MailResult.Ok(draft(draftId = id(2)))
            val uploaded = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.attach(id(1), "\"1\"", any()) } coAnswers { uploaded.await() }
            val vm = newCompose()
            vm.setBody(TextFieldValue("first"))
            vm.attach(listOf(file("big.iso")))
            runCurrent()
            vm.leave()
            runCurrent()
            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)

            vm.start(ComposeMode.New(work.id))
            advanceUntilIdle()
            uploaded.complete(MailResult.Ok(draft("\"2\"", attachments = listOf(DraftAttachment("2", "big.iso", 9)))))
            advanceUntilIdle()

            val s = vm.uiState.value
            assertEquals(ComposePhase.EDITING, s.phase)
            assertEquals(emptyList<DraftAttachment>(), s.attachments)
            assertEquals(emptyList<String>(), s.uploading)
            assertEquals(DraftState.NONE, s.draft)

            vm.setBody(TextFieldValue("second"))
            vm.save()
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.create(work.id, match { it.html == "<p>second</p>" && it.keep.isEmpty() }) }
            coVerify(exactly = 0) { repository.update(any(), any(), any()) }
        }

    @Test
    fun `an attachment removed while a save runs stays removed and is dropped by the next save`() =
        runTest(dispatcher) {
            val a = DraftAttachment("2", "a.pdf", 10)
            val b = DraftAttachment("3", "b.pdf", 20)
            coEvery { repository.start(ComposeMode.Forward(id(5))) } returns MailResult.Ok(draft(attachments = listOf(a, b)))
            val saved = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.update(id(1), "\"1\"", any()) } coAnswers { saved.await() }
            coEvery { repository.update(id(1), "\"2\"", any()) } returns MailResult.Ok(draft("\"3\""))
            val vm = MailComposeViewModel(repository)
            vm.start(ComposeMode.Forward(id(5)))
            advanceUntilIdle()

            vm.removeAttachment("2")
            vm.save()
            runCurrent()
            vm.removeAttachment("3")
            // The new version numbers its parts anew: b is part 2 now.
            saved.complete(MailResult.Ok(draft("\"2\"", attachments = listOf(DraftAttachment("2", "b.pdf", 20)))))
            runCurrent()

            assertEquals(emptyList<DraftAttachment>(), vm.uiState.value.attachments)
            assertEquals(DraftState.UNSAVED, vm.uiState.value.draft)
            vm.save()
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.update(id(1), "\"1\"", match { it.keep == listOf("3") }) }
            coVerify(exactly = 1) { repository.update(id(1), "\"2\"", match { it.keep.isEmpty() }) }
        }

    @Test
    fun `attaching after removing an attachment saves the removal first`() =
        runTest(dispatcher) {
            val a = DraftAttachment("2", "a.pdf", 10)
            coEvery { repository.start(ComposeMode.Forward(id(5))) } returns MailResult.Ok(draft(attachments = listOf(a)))
            coEvery { repository.update(id(1), "\"1\"", any()) } returns MailResult.Ok(draft("\"2\""))
            coEvery { repository.attach(id(1), "\"2\"", any()) } returns
                MailResult.Ok(draft("\"3\"", attachments = listOf(DraftAttachment("2", "c.txt", 1))))
            val vm = MailComposeViewModel(repository)
            vm.start(ComposeMode.Forward(id(5)))
            advanceUntilIdle()

            vm.removeAttachment("2")
            vm.attach(listOf(file("c.txt")))
            advanceUntilIdle()

            coVerifyOrder {
                repository.update(id(1), "\"1\"", match { it.keep.isEmpty() })
                repository.attach(id(1), "\"2\"", any())
            }
            assertEquals(
                listOf("c.txt"),
                vm.uiState.value.attachments
                    .map { it.name },
            )
        }

    @Test
    fun `leaving while an autosave creates the draft waits for it instead of creating a second one`() =
        runTest(dispatcher) {
            val created = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.create(any(), any()) } coAnswers { created.await() }
            val vm = newCompose()
            vm.setBody(TextFieldValue("a"))
            advanceTimeBy(10_001)
            runCurrent()
            assertEquals(DraftState.SAVING, vm.uiState.value.draft)

            vm.setBody(TextFieldValue("ab"))
            vm.leave()
            runCurrent()
            created.complete(MailResult.Ok(draft()))
            advanceUntilIdle()

            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
            coVerify(exactly = 1) { repository.create(any(), any()) }
            coVerify(exactly = 1) { repository.update(id(1), "\"1\"", match { it.html == "<p>ab</p>" }) }
        }

    @Test
    fun `a discard of a draft changed meanwhile deletes the newest version`() =
        runTest(dispatcher) {
            coEvery { repository.discard(id(1), "\"1\"") } returns MailResult.Failed(412)
            coEvery { repository.get(id(1)) } returns MailResult.Ok(draft("\"4\""))
            coEvery { repository.discard(id(1), "\"4\"") } returns MailResult.Ok(Unit)
            val vm = newCompose()
            vm.setBody(TextFieldValue("x"))
            vm.save()
            advanceUntilIdle()

            vm.discard()
            advanceUntilIdle()

            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
            coVerify(exactly = 1) { repository.discard(id(1), "\"4\"") }
        }

    @Test
    fun `a discard of a draft deleted meanwhile counts as discarded`() =
        runTest(dispatcher) {
            coEvery { repository.discard(id(1), "\"1\"") } returns MailResult.Failed(412)
            coEvery { repository.get(id(1)) } returns MailResult.Failed(404)
            val vm = newCompose()
            vm.setBody(TextFieldValue("x"))
            vm.save()
            advanceUntilIdle()

            vm.discard()
            advanceUntilIdle()

            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
            assertNull(vm.uiState.value.problem)
        }

    @Test
    fun `a send of a draft changed meanwhile stores the screen over the newest version and sends that`() =
        runTest(dispatcher) {
            coEvery { repository.send(id(1), "\"1\"") } returns SendOutcome.Conflict
            coEvery { repository.get(id(1)) } returns MailResult.Ok(draft("\"4\""))
            coEvery { repository.update(id(1), "\"4\"", any()) } returns MailResult.Ok(draft("\"5\""))
            coEvery { repository.send(id(1), "\"5\"") } returns SendOutcome.Sent
            val vm = newCompose()
            vm.setTo("ann@example.org")
            vm.setBody(TextFieldValue("Hello"))

            vm.send()
            advanceUntilIdle()

            assertEquals(ComposePhase.SENT, vm.uiState.value.phase)
            coVerify(exactly = 1) { repository.update(id(1), "\"4\"", match { it.html == "<p>Hello</p>" }) }
            coVerify(exactly = 1) { repository.send(id(1), "\"5\"") }
        }

    @Test
    fun `a send refused as invalid with recipients says the mail is too large`() =
        runTest(dispatcher) {
            coEvery { repository.send(any(), any()) } returns SendOutcome.Invalid
            val vm = newCompose()
            vm.setTo("ann@example.org")

            vm.send()
            advanceUntilIdle()

            assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)
            assertEquals(ComposeProblem.TOO_LARGE, vm.uiState.value.problem)
        }

    @Test
    fun `an upload of a file that cannot be read says the file could not be attached`() =
        runTest(dispatcher) {
            coEvery { repository.attach(any(), any(), any()) } returns MailResult.Failed(null)
            val vm = newCompose()

            vm.attach(listOf(file("gone.txt")))
            advanceUntilIdle()

            assertEquals(ComposeProblem.ATTACH_FAILED, vm.uiState.value.problem)
        }

    @Test
    fun `start returns the id of the compose its state carries, a new one after closing`() =
        runTest(dispatcher) {
            val vm = MailComposeViewModel(repository)
            val first = vm.start(ComposeMode.New(work.id))
            advanceUntilIdle()
            assertEquals(first, vm.start(ComposeMode.New(work.id)))
            assertEquals(first, vm.uiState.value.session)

            vm.abandon()
            val second = vm.start(ComposeMode.New(work.id))
            advanceUntilIdle()
            assertEquals(second, vm.uiState.value.session)
            assertEquals(false, first == second)
        }
}
