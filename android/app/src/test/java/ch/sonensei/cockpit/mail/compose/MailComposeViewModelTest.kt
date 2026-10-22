package ch.sonensei.cockpit.mail.compose

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.mail.list.MailResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.util.UUID

/** Draft save states (autosave, manual, leaving), send states, attachments and discard. */
@OptIn(ExperimentalCoroutinesApi::class)
class MailComposeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<MailComposeRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Work", "me@example.org")
    private val home = MailAccount(id(81), "Home", "me@example.net")

    private fun draft(
        etag: String = "\"1\"",
        attachments: List<DraftAttachment> = emptyList(),
        to: List<Recipient> = emptyList(),
    ) = Draft(id(1), work.id, etag, to, emptyList(), emptyList(), "", "", "", attachments)

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
        // Autosave runs 10 s after a change; tests that care stub their own answer.
        coEvery { repository.create(any(), any()) } returns MailResult.Ok(draft())
        coEvery { repository.update(any(), any(), any()) } returns MailResult.Ok(draft())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newCompose(accountId: UUID? = work.id): MailComposeViewModel {
        val vm = MailComposeViewModel(repository)
        vm.start(ComposeMode.New(accountId))
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `a new mail starts empty in the account of the open folder, nothing stored`() =
        runTest(dispatcher) {
            val vm = newCompose()

            val s = vm.uiState.value
            assertEquals(ComposePhase.EDITING, s.phase)
            assertEquals(work.id, s.accountId)
            assertEquals(listOf(home, work), s.accounts)
            assertEquals(DraftState.NONE, s.draft)
            assertFalse(s.accountLocked)
            coVerify(exactly = 0) { repository.create(any(), any()) }
        }

    @Test
    fun `without a folder account the first account is used, an unknown one is replaced`() =
        runTest(dispatcher) {
            assertEquals(home.id, newCompose(null).uiState.value.accountId)
            assertEquals(home.id, newCompose(id(99)).uiState.value.accountId)
        }

    @Test
    fun `a change is autosaved after 10 s, as a new draft in the account, then as a new version`() =
        runTest(dispatcher) {
            val created = slot<DraftContent>()
            coEvery { repository.create(work.id, capture(created)) } returns MailResult.Ok(draft("\"1\""))
            coEvery { repository.update(id(1), "\"1\"", any()) } returns MailResult.Ok(draft("\"2\""))
            val vm = newCompose()

            vm.setTo("Ann Example <ann@example.org>")
            vm.setSubject("Plan")
            vm.setBody(TextFieldValue("Hi **Ann**"))
            assertEquals(DraftState.UNSAVED, vm.uiState.value.draft)
            advanceTimeBy(9_999)
            coVerify(exactly = 0) { repository.create(any(), any()) }
            advanceTimeBy(2)
            runCurrent()

            assertEquals(DraftState.SAVED, vm.uiState.value.draft)
            assertTrue(vm.uiState.value.accountLocked)
            assertEquals(listOf(Recipient("ann@example.org", "Ann Example")), created.captured.to)
            assertEquals("Plan", created.captured.subject)
            assertEquals("<p>Hi <strong>Ann</strong></p>", created.captured.html)

            vm.setSubject("Plan B")
            advanceTimeBy(10_001)
            runCurrent()
            coVerify(exactly = 1) { repository.update(id(1), "\"1\"", match { it.subject == "Plan B" }) }
            assertEquals(DraftState.SAVED, vm.uiState.value.draft)
        }

    @Test
    fun `moving the cursor is not a change`() =
        runTest(dispatcher) {
            val vm = newCompose()
            vm.setBody(TextFieldValue("x"))
            coEvery { repository.create(any(), any()) } returns MailResult.Ok(draft())
            advanceUntilIdle()

            vm.setBody(TextFieldValue("x", TextRange(0)))
            advanceUntilIdle()

            assertEquals(DraftState.SAVED, vm.uiState.value.draft)
            coVerify(exactly = 1) { repository.create(any(), any()) }
        }

    @Test
    fun `a failed save keeps the text and says why`() =
        runTest(dispatcher) {
            coEvery { repository.create(any(), any()) } returns MailResult.Offline
            val vm = newCompose()
            vm.setBody(TextFieldValue("Draft text"))

            vm.save()
            advanceUntilIdle()

            val s = vm.uiState.value
            assertEquals(DraftState.UNSAVED, s.draft)
            assertEquals(ComposeProblem.OFFLINE, s.problem)
            assertEquals("Draft text", s.body.text)

            coEvery { repository.create(any(), any()) } returns MailResult.Failed(500)
            vm.save()
            advanceUntilIdle()
            assertEquals(ComposeProblem.SAVE_FAILED, vm.uiState.value.problem)
        }

    @Test
    fun `an invalid recipient blocks the save and marks its field until it is edited`() =
        runTest(dispatcher) {
            val vm = newCompose()
            vm.showCcBcc()
            vm.setCc("not an address")

            vm.save()
            advanceUntilIdle()

            assertEquals(setOf(RecipientField.CC), vm.uiState.value.invalid)
            assertEquals(ComposeProblem.INVALID_ADDRESS, vm.uiState.value.problem)
            coVerify(exactly = 0) { repository.create(any(), any()) }

            vm.setCc("carl@example.org")
            assertEquals(emptySet<RecipientField>(), vm.uiState.value.invalid)
        }

    @Test
    fun `a stale version is overwritten with the text on screen`() =
        runTest(dispatcher) {
            coEvery { repository.create(any(), any()) } returns
                MailResult.Ok(draft("\"1\"", listOf(DraftAttachment("2", "a.pdf", 1), DraftAttachment("3", "b.pdf", 1))))
            coEvery { repository.update(id(1), "\"1\"", any()) } returns MailResult.Failed(412)
            coEvery { repository.get(id(1)) } returns MailResult.Ok(draft("\"5\"", listOf(DraftAttachment("2", "a.pdf", 1))))
            coEvery { repository.update(id(1), "\"5\"", any()) } returns MailResult.Ok(draft("\"6\""))
            val vm = newCompose()
            vm.setSubject("one")
            vm.save()
            advanceUntilIdle()

            vm.setSubject("two")
            vm.save()
            advanceUntilIdle()

            coVerify { repository.update(id(1), "\"5\"", match { it.subject == "two" && it.keep == listOf("2") }) }
            assertEquals(DraftState.SAVED, vm.uiState.value.draft)
        }

    @Test
    fun `send saves the latest text first, then sends that version and closes`() =
        runTest(dispatcher) {
            coEvery { repository.create(any(), any()) } returns MailResult.Ok(draft("\"1\""))
            coEvery { repository.send(id(1), "\"1\"") } returns SendOutcome.Sent
            val vm = newCompose()
            vm.setTo("ann@example.org")
            vm.setBody(TextFieldValue("Hello"))

            vm.send()
            assertEquals(ComposePhase.SENDING, vm.uiState.value.phase)
            assertFalse(vm.uiState.value.editable)
            advanceUntilIdle()

            assertEquals(ComposePhase.SENT, vm.uiState.value.phase)
            coVerify(exactly = 1) { repository.create(work.id, match { it.html == "<p>Hello</p>" }) }
            coVerify(exactly = 1) { repository.send(id(1), "\"1\"") }
        }

    @Test
    fun `send without recipient does not save nor send`() =
        runTest(dispatcher) {
            val vm = newCompose()
            vm.setBody(TextFieldValue("Hello"))

            vm.send()
            advanceUntilIdle()

            assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)
            assertEquals(ComposeProblem.NO_RECIPIENT, vm.uiState.value.problem)
            coVerify(exactly = 0) { repository.send(any(), any()) }
        }

    @Test
    fun `a failed send returns to editing with the reason, the draft stays`() =
        runTest(dispatcher) {
            coEvery { repository.create(any(), any()) } returns MailResult.Ok(draft())
            val vm = newCompose()
            vm.setTo("ann@example.org")
            val outcomes =
                mapOf(
                    SendOutcome.Rejected to ComposeProblem.REJECTED,
                    SendOutcome.Invalid to ComposeProblem.NO_RECIPIENT,
                    SendOutcome.Offline to ComposeProblem.OFFLINE,
                    SendOutcome.Conflict to ComposeProblem.SEND_FAILED,
                    SendOutcome.Failed(500) to ComposeProblem.SEND_FAILED,
                )
            outcomes.forEach { (outcome, problem) ->
                coEvery { repository.send(any(), any()) } returns outcome
                vm.send()
                advanceUntilIdle()
                assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)
                assertEquals(problem, vm.uiState.value.problem)
            }
            coVerify(exactly = 1) { repository.create(any(), any()) }
        }

    @Test
    fun `send stops when the save before it fails`() =
        runTest(dispatcher) {
            coEvery { repository.create(any(), any()) } returns MailResult.Offline
            val vm = newCompose()
            vm.setTo("ann@example.org")

            vm.send()
            advanceUntilIdle()

            assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)
            assertEquals(ComposeProblem.OFFLINE, vm.uiState.value.problem)
            coVerify(exactly = 0) { repository.send(any(), any()) }
        }

    @Test
    fun `a reply starts from the server's draft and keeps its quote below the typed text`() =
        runTest(dispatcher) {
            val reply =
                Draft(
                    id(1),
                    work.id,
                    "\"1\"",
                    listOf(Recipient("ann@example.org", "Ann Example")),
                    listOf(Recipient("ben@example.org")),
                    emptyList(),
                    "Re: Plan",
                    "<p></p><blockquote>old</blockquote>",
                    "\n> old\n",
                    listOf(DraftAttachment("3", "logo.png", 5, inline = true)),
                )
            coEvery { repository.start(ComposeMode.ReplyAll(id(5))) } returns MailResult.Ok(reply)
            coEvery { repository.update(id(1), "\"1\"", any()) } returns MailResult.Ok(draft("\"2\""))
            val vm = MailComposeViewModel(repository)

            vm.start(ComposeMode.ReplyAll(id(5)))
            assertEquals(ComposePhase.STARTING, vm.uiState.value.phase)
            advanceUntilIdle()

            val s = vm.uiState.value
            assertEquals(ComposePhase.EDITING, s.phase)
            assertEquals("Ann Example <ann@example.org>", s.to)
            assertEquals("ben@example.org", s.cc)
            assertTrue(s.showCcBcc)
            assertEquals("Re: Plan", s.subject)
            assertEquals("> old", s.quotedText)
            assertTrue(s.accountLocked)
            assertEquals(DraftState.SAVED, s.draft)
            assertEquals(emptyList<DraftAttachment>(), s.listedAttachments)

            vm.setBody(TextFieldValue("Agreed"))
            vm.save()
            advanceUntilIdle()
            coVerify {
                repository.update(
                    id(1),
                    "\"1\"",
                    match { it.html == "<p>Agreed</p><p></p><blockquote>old</blockquote>" && it.keep == listOf("3") },
                )
            }
        }

    @Test
    fun `a reply that cannot start offers to retry`() =
        runTest(dispatcher) {
            coEvery { repository.start(any()) } returns MailResult.Offline andThen MailResult.Ok(draft())
            val vm = MailComposeViewModel(repository)
            vm.start(ComposeMode.Reply(id(5)))
            advanceUntilIdle()
            assertEquals(ComposePhase.STARTING, vm.uiState.value.phase)
            assertEquals(ComposeProblem.OFFLINE, vm.uiState.value.problem)

            vm.retry()
            advanceUntilIdle()

            assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)
            assertNull(vm.uiState.value.problem)
        }

    @Test
    fun `an untouched reply is discarded when leaving, an edited one is saved`() =
        runTest(dispatcher) {
            coEvery { repository.start(any()) } returns MailResult.Ok(draft())
            coEvery { repository.discard(id(1), "\"1\"") } returns MailResult.Ok(Unit)
            coEvery { repository.update(id(1), "\"1\"", any()) } returns MailResult.Ok(draft("\"2\""))
            val vm = MailComposeViewModel(repository)
            vm.start(ComposeMode.Reply(id(5)))
            advanceUntilIdle()

            vm.leave()
            advanceUntilIdle()
            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
            coVerify(exactly = 1) { repository.discard(id(1), "\"1\"") }

            vm.start(ComposeMode.Reply(id(5)))
            advanceUntilIdle()
            vm.setBody(TextFieldValue("Thanks"))
            vm.leave()
            advanceUntilIdle()
            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
            coVerify(exactly = 1) { repository.update(id(1), "\"1\"", any()) }
            coVerify(exactly = 1) { repository.discard(any(), any()) }
        }

    @Test
    fun `leaving with a failed save stays and offers to leave without saving`() =
        runTest(dispatcher) {
            coEvery { repository.create(any(), any()) } returns MailResult.Offline
            val vm = newCompose()
            vm.setBody(TextFieldValue("Draft"))

            vm.leave()
            advanceUntilIdle()
            assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)
            assertTrue(vm.uiState.value.leaveBlocked)

            vm.abandon()
            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
        }

    @Test
    fun `leaving an empty new mail stores nothing`() =
        runTest(dispatcher) {
            val vm = newCompose()
            vm.leave()
            advanceUntilIdle()
            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
            coVerify(exactly = 0) { repository.create(any(), any()) }
        }

    @Test
    fun `attaching saves the draft first, then uploads each file with the newest version`() =
        runTest(dispatcher) {
            coEvery { repository.create(any(), any()) } returns MailResult.Ok(draft("\"1\""))
            coEvery { repository.attach(id(1), "\"1\"", any()) } returns
                MailResult.Ok(draft("\"2\"", listOf(DraftAttachment("2", "a.txt", 1))))
            coEvery { repository.attach(id(1), "\"2\"", any()) } returns
                MailResult.Ok(draft("\"3\"", listOf(DraftAttachment("2", "a.txt", 1), DraftAttachment("3", "b.txt", 1))))
            val vm = newCompose()

            vm.attach(listOf(file("a.txt"), file("b.txt")))
            assertEquals(listOf("a.txt", "b.txt"), vm.uiState.value.uploading)
            advanceUntilIdle()

            assertEquals(emptyList<String>(), vm.uiState.value.uploading)
            assertEquals(
                listOf("a.txt", "b.txt"),
                vm.uiState.value.listedAttachments
                    .map { it.name },
            )
            coVerify(exactly = 1) { repository.create(work.id, any()) }

            coEvery { repository.update(id(1), "\"3\"", any()) } returns
                MailResult.Ok(draft("\"4\"", listOf(DraftAttachment("3", "b.txt", 1))))
            vm.removeAttachment("2")
            vm.save()
            advanceUntilIdle()
            coVerify { repository.update(id(1), "\"3\"", match { it.keep == listOf("3") }) }
            assertEquals(
                listOf("b.txt"),
                vm.uiState.value.listedAttachments
                    .map { it.name },
            )
        }

    @Test
    fun `a refused upload says whether the file was too large`() =
        runTest(dispatcher) {
            coEvery { repository.create(any(), any()) } returns MailResult.Ok(draft())
            coEvery { repository.attach(any(), any(), any()) } returns MailResult.Failed(400) andThen MailResult.Failed(500)
            val vm = newCompose()

            vm.attach(listOf(file("big.iso")))
            advanceUntilIdle()
            assertEquals(ComposeProblem.TOO_LARGE, vm.uiState.value.problem)

            vm.attach(listOf(file("x.txt")))
            advanceUntilIdle()
            assertEquals(ComposeProblem.ATTACH_FAILED, vm.uiState.value.problem)
        }

    @Test
    fun `discard deletes the stored draft and closes, a failure keeps it open`() =
        runTest(dispatcher) {
            coEvery { repository.create(any(), any()) } returns MailResult.Ok(draft())
            coEvery { repository.discard(id(1), "\"1\"") } returns MailResult.Offline andThen MailResult.Ok(Unit)
            val vm = newCompose()
            vm.setBody(TextFieldValue("x"))
            vm.save()
            advanceUntilIdle()

            vm.discard()
            advanceUntilIdle()
            assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)
            assertEquals(ComposeProblem.OFFLINE, vm.uiState.value.problem)

            vm.discard()
            advanceUntilIdle()
            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
        }

    @Test
    fun `the account can be changed only before the first save`() =
        runTest(dispatcher) {
            coEvery { repository.create(any(), any()) } returns MailResult.Ok(draft())
            val vm = newCompose()

            vm.selectAccount(home.id)
            assertEquals(home.id, vm.uiState.value.accountId)
            vm.setBody(TextFieldValue("x"))
            vm.save()
            advanceUntilIdle()
            coVerify { repository.create(home.id, any()) }

            vm.selectAccount(work.id)
            assertEquals(home.id, vm.uiState.value.accountId)
        }

    @Test
    fun `no account to send from is reported`() =
        runTest(dispatcher) {
            coEvery { repository.accounts() } returns MailResult.Ok(emptyList())
            val vm = newCompose(null)

            assertEquals(ComposeProblem.NO_ACCOUNT, vm.uiState.value.problem)
            vm.setBody(TextFieldValue("x"))
            vm.save()
            advanceUntilIdle()
            assertEquals(ComposeProblem.NO_ACCOUNT, vm.uiState.value.problem)
            coVerify(exactly = 0) { repository.create(any(), any()) }
        }

    @Test
    fun `formatting edits the body`() =
        runTest(dispatcher) {
            val vm = newCompose()
            vm.setBody(TextFieldValue("word", TextRange(0, 4)))

            vm.format(TextFormat.BOLD)

            assertEquals("**word**", vm.uiState.value.body.text)
            assertEquals(DraftState.UNSAVED, vm.uiState.value.draft)
        }
}
