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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.util.UUID

/**
 * Writes that overlap (edits while a save runs, saves in a row, answers arriving late) and the less common paths of
 * starting, leaving, discarding and sending. Answers are held back with [CompletableDeferred], never with real delays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MailComposeViewModelConcurrencyTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<MailComposeRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Work", "me@example.org")
    private val home = MailAccount(id(81), "Home", "me@example.net")

    private fun draft(
        etag: String = "\"1\"",
        draftId: UUID = id(1),
        to: List<Recipient> = emptyList(),
        subject: String = "",
    ) = Draft(draftId, work.id, etag, to, emptyList(), emptyList(), subject, "", "", emptyList())

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
        coEvery { repository.accounts() } returns MailResult.Ok(listOf(home, work))
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
    fun `an edit during a manual save stays unsaved and is autosaved with the version that save returned`() =
        runTest(dispatcher) {
            val created = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.create(any(), any()) } coAnswers { created.await() }
            val vm = newCompose()
            vm.setBody(TextFieldValue("a"))
            vm.save()
            runCurrent()
            assertEquals(DraftState.SAVING, vm.uiState.value.draft)

            vm.setBody(TextFieldValue("ab"))
            created.complete(MailResult.Ok(draft("\"1\"")))
            runCurrent()

            assertEquals(DraftState.UNSAVED, vm.uiState.value.draft)
            coVerify(exactly = 1) { repository.create(work.id, match { it.html == "<p>a</p>" }) }

            advanceTimeBy(10_001)
            runCurrent()

            coVerify(exactly = 1) { repository.update(id(1), "\"1\"", match { it.html == "<p>ab</p>" }) }
            assertEquals(DraftState.SAVED, vm.uiState.value.draft)
        }

    @Test
    fun `an edit during an autosave is autosaved again`() =
        runTest(dispatcher) {
            val created = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.create(any(), any()) } coAnswers { created.await() }
            val vm = newCompose()
            vm.setBody(TextFieldValue("a"))
            advanceTimeBy(10_001)
            runCurrent()
            assertEquals(DraftState.SAVING, vm.uiState.value.draft)

            vm.setBody(TextFieldValue("ab"))
            created.complete(MailResult.Ok(draft("\"1\"")))
            runCurrent()
            assertEquals(DraftState.UNSAVED, vm.uiState.value.draft)

            // SPEC.md §7.3: drafts autosave every 10 s, so the second edit is stored without another keystroke.
            advanceTimeBy(10_001)
            runCurrent()

            coVerify(exactly = 1) { repository.update(id(1), "\"1\"", match { it.html == "<p>ab</p>" }) }
            assertEquals(DraftState.SAVED, vm.uiState.value.draft)
        }

    @Test
    fun `two saves in a row create the draft once, the second updates the version the first returned`() =
        runTest(dispatcher) {
            val created = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.create(any(), any()) } coAnswers { created.await() }
            val vm = newCompose()
            vm.setSubject("one")
            vm.save()
            runCurrent()
            vm.setSubject("two")
            vm.save()
            runCurrent()

            created.complete(MailResult.Ok(draft("\"1\"")))
            runCurrent()

            coVerify(exactly = 1) { repository.create(any(), any()) }
            coVerify(exactly = 1) { repository.update(id(1), "\"1\"", match { it.subject == "two" }) }
            assertEquals(DraftState.SAVED, vm.uiState.value.draft)
        }

    @Test
    fun `saving twice without a change in between writes once`() =
        runTest(dispatcher) {
            val vm = newCompose()
            vm.setSubject("one")
            vm.save()
            vm.save()
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.create(any(), any()) }
            coVerify(exactly = 0) { repository.update(any(), any(), any()) }
        }

    @Test
    fun `a reply answering after the user left and opened a new mail does not fill the new mail`() =
        runTest(dispatcher) {
            val reply = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.start(ComposeMode.Reply(id(5))) } coAnswers { reply.await() }
            val vm = MailComposeViewModel(repository)
            vm.start(ComposeMode.Reply(id(5)))
            runCurrent()
            vm.leave()
            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)

            vm.start(ComposeMode.New(work.id))
            advanceUntilIdle()
            reply.complete(MailResult.Ok(draft(draftId = id(7), to = listOf(Recipient("ann@example.org")), subject = "Re: Plan")))
            advanceUntilIdle()

            val s = vm.uiState.value
            assertEquals(ComposeMode.New(work.id), s.mode)
            assertEquals("", s.to)
            assertEquals("", s.subject)
            assertFalse(s.accountLocked)
            assertEquals(DraftState.NONE, s.draft)
        }

    @Test
    fun `a reply left while the server prepares it is discarded once it exists`() =
        runTest(dispatcher) {
            val reply = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.start(ComposeMode.Reply(id(5))) } coAnswers { reply.await() }
            val vm = MailComposeViewModel(repository)
            vm.start(ComposeMode.Reply(id(5)))
            runCurrent()

            vm.leave()
            reply.complete(MailResult.Ok(draft("\"1\"", draftId = id(7))))
            advanceUntilIdle()

            // The recorded assumption: a reply or forward left untouched is discarded, not kept in Drafts.
            coVerify(exactly = 1) { repository.discard(id(7), "\"1\"") }
        }

    @Test
    fun `while sending nothing can be edited, sent again or discarded`() =
        runTest(dispatcher) {
            val sent = CompletableDeferred<SendOutcome>()
            coEvery { repository.send(any(), any()) } coAnswers { sent.await() }
            val vm = newCompose()
            vm.setTo("ann@example.org")
            vm.send()
            runCurrent()

            vm.setTo("ben@example.org")
            vm.setBody(TextFieldValue("late"))
            vm.send()
            vm.discard()
            vm.leave()
            runCurrent()

            assertEquals(ComposePhase.SENDING, vm.uiState.value.phase)
            assertEquals("ann@example.org", vm.uiState.value.to)
            assertEquals("", vm.uiState.value.body.text)
            sent.complete(SendOutcome.Sent)
            advanceUntilIdle()
            assertEquals(ComposePhase.SENT, vm.uiState.value.phase)
            coVerify(exactly = 1) { repository.send(any(), any()) }
            coVerify(exactly = 0) { repository.discard(any(), any()) }
        }

    @Test
    fun `send waits until the uploads are done`() =
        runTest(dispatcher) {
            val uploaded = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.attach(any(), any(), any()) } coAnswers { uploaded.await() }
            val vm = newCompose()
            vm.setTo("ann@example.org")
            vm.attach(listOf(file("plan.pdf")))
            runCurrent()

            vm.send()
            runCurrent()

            assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)
            coVerify(exactly = 0) { repository.send(any(), any()) }
            uploaded.complete(MailResult.Ok(draft("\"2\"")))
            advanceUntilIdle()
            assertEquals(emptyList<String>(), vm.uiState.value.uploading)
        }

    @Test
    fun `a successful save clears a save problem but keeps a send problem`() =
        runTest(dispatcher) {
            coEvery { repository.send(any(), any()) } returns SendOutcome.Rejected
            val vm = newCompose()
            vm.setTo("ann@example.org")
            vm.send()
            advanceUntilIdle()
            assertEquals(ComposeProblem.REJECTED, vm.uiState.value.problem)

            vm.setSubject("again")
            advanceTimeBy(10_001)
            runCurrent()
            assertEquals(DraftState.SAVED, vm.uiState.value.draft)
            assertEquals(ComposeProblem.REJECTED, vm.uiState.value.problem)

            coEvery { repository.update(any(), any(), any()) } returns MailResult.Offline andThen MailResult.Ok(draft("\"3\""))
            vm.setSubject("offline")
            vm.save()
            // Not idle: the autosave pending for the edit would already retry.
            runCurrent()
            assertEquals(ComposeProblem.OFFLINE, vm.uiState.value.problem)
            vm.save()
            runCurrent()
            assertEquals(null, vm.uiState.value.problem)
        }

    @Test
    fun `opening the same mail again keeps the text, after closing it starts over`() =
        runTest(dispatcher) {
            val vm = newCompose()
            vm.setBody(TextFieldValue("kept"))

            vm.start(ComposeMode.New(work.id))
            advanceUntilIdle()
            assertEquals("kept", vm.uiState.value.body.text)

            vm.abandon()
            vm.start(ComposeMode.New(work.id))
            advanceUntilIdle()
            assertEquals("", vm.uiState.value.body.text)
            assertEquals(DraftState.NONE, vm.uiState.value.draft)
        }

    @Test
    fun `a new mail whose accounts could not load says so and loads them on retry`() =
        runTest(dispatcher) {
            coEvery { repository.accounts() } returns MailResult.Failed(500) andThen MailResult.Offline andThen MailResult.Ok(listOf(work))
            val vm = MailComposeViewModel(repository)
            vm.start(ComposeMode.New())
            advanceUntilIdle()
            assertEquals(ComposeProblem.START_FAILED, vm.uiState.value.problem)
            assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)

            vm.retry()
            advanceUntilIdle()
            assertEquals(ComposeProblem.OFFLINE, vm.uiState.value.problem)

            vm.retry()
            advanceUntilIdle()
            assertEquals(null, vm.uiState.value.problem)
            assertEquals(work.id, vm.uiState.value.accountId)
        }

    @Test
    fun `a reply the server cannot prepare says so and stays without form`() =
        runTest(dispatcher) {
            coEvery { repository.start(any()) } returns MailResult.Failed(404)
            val vm = MailComposeViewModel(repository)

            vm.start(ComposeMode.Forward(id(5)))
            advanceUntilIdle()

            assertEquals(ComposePhase.STARTING, vm.uiState.value.phase)
            assertEquals(ComposeProblem.START_FAILED, vm.uiState.value.problem)
            assertFalse(vm.uiState.value.editable)
        }

    @Test
    fun `retry does nothing once the draft exists`() =
        runTest(dispatcher) {
            coEvery { repository.start(any()) } returns MailResult.Ok(draft())
            val vm = MailComposeViewModel(repository)
            vm.start(ComposeMode.Reply(id(5)))
            advanceUntilIdle()

            vm.retry()
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.start(any()) }
        }

    @Test
    fun `leaving while a reply is being prepared closes at once`() =
        runTest(dispatcher) {
            coEvery { repository.start(any()) } returns MailResult.Offline
            val vm = MailComposeViewModel(repository)
            vm.start(ComposeMode.Reply(id(5)))
            advanceUntilIdle()

            vm.leave()

            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
        }

    @Test
    fun `when the newer version cannot be fetched the save fails and the text stays unsaved`() =
        runTest(dispatcher) {
            coEvery { repository.update(id(1), "\"1\"", any()) } returns MailResult.Failed(412)
            coEvery { repository.get(id(1)) } returns MailResult.Offline
            val vm = newCompose()
            vm.setSubject("one")
            vm.save()
            advanceUntilIdle()

            vm.setSubject("two")
            vm.save()
            advanceUntilIdle()

            assertEquals(DraftState.UNSAVED, vm.uiState.value.draft)
            assertEquals(ComposeProblem.OFFLINE, vm.uiState.value.problem)
            assertEquals("two", vm.uiState.value.subject)
        }

    @Test
    fun `a discard the server refuses says so, an unsaved new mail is discarded without a call`() =
        runTest(dispatcher) {
            coEvery { repository.discard(any(), any()) } returns MailResult.Failed(500)
            val vm = newCompose()
            vm.setBody(TextFieldValue("x"))
            vm.save()
            advanceUntilIdle()

            vm.discard()
            advanceUntilIdle()
            assertEquals(ComposeProblem.DISCARD_FAILED, vm.uiState.value.problem)
            assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)

            val fresh = newCompose()
            fresh.setBody(TextFieldValue("never saved"))
            fresh.discard()
            advanceUntilIdle()
            assertEquals(ComposePhase.CLOSED, fresh.uiState.value.phase)
            coVerify(exactly = 1) { repository.discard(any(), any()) }
        }

    @Test
    fun `an invalid bcc blocks the save and is cleared by editing it`() =
        runTest(dispatcher) {
            val vm = newCompose()
            vm.showCcBcc()
            vm.setBcc("nobody")
            vm.setTo("also wrong")

            vm.save()
            advanceUntilIdle()
            assertEquals(setOf(RecipientField.TO, RecipientField.BCC), vm.uiState.value.invalid)

            vm.setBcc("bcc@example.org")
            assertEquals(setOf(RecipientField.TO), vm.uiState.value.invalid)
            coVerify(exactly = 0) { repository.create(any(), any()) }
        }

    @Test
    fun `a file is not uploaded when the draft cannot be stored first, an offline upload says so`() =
        runTest(dispatcher) {
            coEvery { repository.create(any(), any()) } returns MailResult.Offline andThen MailResult.Ok(draft())
            coEvery { repository.attach(any(), any(), any()) } returns MailResult.Offline
            val vm = newCompose()

            vm.attach(listOf(file("a.txt")))
            advanceUntilIdle()
            coVerify(exactly = 0) { repository.attach(any(), any(), any()) }
            assertEquals(emptyList<String>(), vm.uiState.value.uploading)
            assertEquals(ComposeProblem.OFFLINE, vm.uiState.value.problem)

            vm.attach(listOf(file("b.txt")))
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.attach(id(1), "\"1\"", any()) }
            assertEquals(ComposeProblem.OFFLINE, vm.uiState.value.problem)
            assertEquals(emptyList<DraftAttachment>(), vm.uiState.value.attachments)
        }
}
