package ch.sonensei.cockpit.mail.compose

import androidx.compose.ui.text.input.TextFieldValue
import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.mail.list.MailResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** Sending identity in compose (loaded per account, chosen, saved with the draft) and continuing a stored draft. */
@OptIn(ExperimentalCoroutinesApi::class)
class MailComposeIdentityDraftTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<MailComposeRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Work", "me@example.org")
    private val home = MailAccount(id(81), "Home", "me@example.net")

    private val workMain = MailIdentity(id(60), work.id, "Me", "me@example.org", isDefault = true)
    private val workSupport = MailIdentity(id(61), work.id, "Support", "support@example.org", isDefault = false)
    private val homeMain = MailIdentity(id(62), home.id, "Me", "me@example.net", isDefault = true)

    private fun draft(
        etag: String = "\"1\"",
        identityId: UUID? = null,
        accountId: UUID = work.id,
    ) = Draft(
        id = id(7),
        accountId = accountId,
        identityId = identityId,
        etag = etag,
        to = listOf(Recipient("ann@example.org", "Ann Example")),
        cc = emptyList(),
        bcc = emptyList(),
        subject = "Plan",
        html = "<p>Draft so far</p>",
        text = "Draft so far",
        attachments = emptyList(),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.accounts() } returns MailResult.Ok(listOf(home, work))
        coEvery { repository.identities(work.id) } returns MailResult.Ok(listOf(workMain, workSupport))
        coEvery { repository.identities(home.id) } returns MailResult.Ok(listOf(homeMain))
        coEvery { repository.create(any(), any()) } returns MailResult.Ok(draft())
        coEvery { repository.update(any(), any(), any()) } returns MailResult.Ok(draft("\"2\""))
        coEvery { repository.discard(any(), any()) } returns MailResult.Ok(Unit)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.compose(mode: ComposeMode): MailComposeViewModel {
        val vm = MailComposeViewModel(repository)
        vm.start(mode)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `a new mail loads the identities of its account, none chosen yet`() =
        runTest(dispatcher) {
            val vm = compose(ComposeMode.New(work.id))

            assertEquals(listOf(workMain, workSupport), vm.uiState.value.identities)
            assertNull(vm.uiState.value.identityId)
            coVerify(exactly = 1) { repository.identities(work.id) }
        }

    @Test
    fun `a chosen identity is an edit and is created with the new draft`() =
        runTest(dispatcher) {
            val created = slot<DraftContent>()
            coEvery { repository.create(work.id, capture(created)) } returns MailResult.Ok(draft(identityId = workSupport.id))
            val vm = compose(ComposeMode.New(work.id))

            vm.selectIdentity(workSupport.id)
            assertEquals(workSupport.id, vm.uiState.value.identityId)
            assertEquals(DraftState.UNSAVED, vm.uiState.value.draft)
            vm.save()
            advanceUntilIdle()

            assertEquals(workSupport.id, created.captured.identityId)
            assertEquals(DraftState.SAVED, vm.uiState.value.draft)
        }

    @Test
    fun `an identity not listed for the account, or the one already chosen, changes nothing`() =
        runTest(dispatcher) {
            val vm = compose(ComposeMode.New(work.id))

            vm.selectIdentity(homeMain.id)
            assertNull(vm.uiState.value.identityId)
            assertEquals(DraftState.NONE, vm.uiState.value.draft)

            vm.selectIdentity(workSupport.id)
            vm.save()
            advanceUntilIdle()
            vm.selectIdentity(workSupport.id)
            assertEquals(DraftState.SAVED, vm.uiState.value.draft)
        }

    @Test
    fun `another account forgets the identity and loads that account's identities`() =
        runTest(dispatcher) {
            val vm = compose(ComposeMode.New(work.id))
            vm.selectIdentity(workSupport.id)

            vm.selectAccount(home.id)
            assertNull(vm.uiState.value.identityId)
            assertEquals(emptyList<MailIdentity>(), vm.uiState.value.identities)
            advanceUntilIdle()

            assertEquals(home.id, vm.uiState.value.accountId)
            assertEquals(listOf(homeMain), vm.uiState.value.identities)
        }

    @Test
    fun `identities of an account left meanwhile are not shown for the new one`() =
        runTest(dispatcher) {
            val slowWork = CompletableDeferred<MailResult<List<MailIdentity>>>()
            coEvery { repository.identities(work.id) } coAnswers { slowWork.await() }
            val vm = compose(ComposeMode.New(work.id))

            vm.selectAccount(home.id)
            advanceUntilIdle()
            slowWork.complete(MailResult.Ok(listOf(workMain, workSupport)))
            advanceUntilIdle()

            assertEquals(home.id, vm.uiState.value.accountId)
            assertEquals(listOf(homeMain), vm.uiState.value.identities)
        }

    @Test
    fun `identities that cannot be read leave the picker without identities, the mail can still be written`() =
        runTest(dispatcher) {
            coEvery { repository.identities(work.id) } returns MailResult.Failed(500)
            val vm = compose(ComposeMode.New(work.id))

            assertEquals(emptyList<MailIdentity>(), vm.uiState.value.identities)
            assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)
            assertNull(vm.uiState.value.problem)
        }

    @Test
    fun `without any account no identities are asked for`() =
        runTest(dispatcher) {
            coEvery { repository.accounts() } returns MailResult.Ok(emptyList())
            compose(ComposeMode.New(null))

            coVerify(exactly = 0) { repository.identities(any()) }
        }

    @Test
    fun `a reply loads the identities of its account and keeps the identity the server chose`() =
        runTest(dispatcher) {
            coEvery { repository.start(ComposeMode.Reply(id(5))) } returns MailResult.Ok(draft(identityId = workSupport.id))
            val vm = compose(ComposeMode.Reply(id(5)))

            assertEquals(workSupport.id, vm.uiState.value.identityId)
            assertEquals(listOf(workMain, workSupport), vm.uiState.value.identities)
        }

    @Test
    fun `an opened draft shows its content and identity, saving updates that draft with the chosen identity`() =
        runTest(dispatcher) {
            coEvery { repository.start(ComposeMode.Draft(id(7))) } returns MailResult.Ok(draft(identityId = workMain.id))
            val saved = slot<DraftContent>()
            coEvery { repository.update(id(7), "\"1\"", capture(saved)) } returns MailResult.Ok(draft("\"2\"", workSupport.id))
            val vm = compose(ComposeMode.Draft(id(7)))

            val s = vm.uiState.value
            assertEquals(ComposePhase.EDITING, s.phase)
            assertEquals(DraftState.SAVED, s.draft)
            assertEquals(work.id, s.accountId)
            assertTrue(s.accountLocked)
            assertEquals(workMain.id, s.identityId)
            assertEquals("Plan", s.subject)
            assertEquals(recipientField(listOf(Recipient("ann@example.org", "Ann Example"))), s.to)

            vm.selectIdentity(workSupport.id)
            vm.save()
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.create(any(), any()) }
            assertEquals(workSupport.id, saved.captured.identityId)
            assertEquals("Plan", saved.captured.subject)
            assertTrue(saved.captured.html, saved.captured.html.contains("Draft so far"))
            assertEquals(DraftState.SAVED, vm.uiState.value.draft)
        }

    @Test
    fun `leaving an opened draft untouched keeps it, unlike an untouched reply`() =
        runTest(dispatcher) {
            coEvery { repository.start(ComposeMode.Draft(id(7))) } returns MailResult.Ok(draft())
            val vm = compose(ComposeMode.Draft(id(7)))

            vm.leave()
            advanceUntilIdle()

            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
            coVerify(exactly = 0) { repository.discard(any(), any()) }
            coVerify(exactly = 0) { repository.update(any(), any(), any()) }
        }

    @Test
    fun `leaving an edited opened draft saves it into the same draft`() =
        runTest(dispatcher) {
            coEvery { repository.start(ComposeMode.Draft(id(7))) } returns MailResult.Ok(draft())
            val vm = compose(ComposeMode.Draft(id(7)))

            vm.setSubject("Plan B")
            vm.leave()
            advanceUntilIdle()

            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
            coVerify(exactly = 1) { repository.update(id(7), "\"1\"", match { it.subject == "Plan B" }) }
            coVerify(exactly = 0) { repository.discard(any(), any()) }
        }

    @Test
    fun `a draft that cannot be opened offers retry and opens on retry`() =
        runTest(dispatcher) {
            coEvery { repository.start(ComposeMode.Draft(id(7))) } returns MailResult.Failed(404) andThen MailResult.Ok(draft())
            val vm = compose(ComposeMode.Draft(id(7)))
            assertEquals(ComposeProblem.START_FAILED, vm.uiState.value.problem)

            vm.retry()
            advanceUntilIdle()

            assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)
            assertEquals("Plan", vm.uiState.value.subject)
        }

    @Test
    fun `an opened draft puts its text into the editable body, not the quoted text, and saves the edited text alone`() =
        runTest(dispatcher) {
            coEvery { repository.start(ComposeMode.Draft(id(7))) } returns MailResult.Ok(draft())
            val saved = slot<DraftContent>()
            coEvery { repository.update(id(7), "\"1\"", capture(saved)) } returns MailResult.Ok(draft("\"2\""))
            val vm = compose(ComposeMode.Draft(id(7)))

            assertEquals("Draft so far", vm.uiState.value.body.text)
            assertEquals("", vm.uiState.value.quotedText)

            vm.setBody(TextFieldValue("Draft so far, now finished"))
            vm.save()
            advanceUntilIdle()

            assertEquals("<p>Draft so far, now finished</p>", saved.captured.html)
        }

    @Test
    fun `a reply keeps the original as quoted text below an empty body`() =
        runTest(dispatcher) {
            coEvery { repository.start(ComposeMode.Reply(id(5))) } returns MailResult.Ok(draft())
            val vm = compose(ComposeMode.Reply(id(5)))

            assertEquals("", vm.uiState.value.body.text)
            assertEquals("Draft so far", vm.uiState.value.quotedText)
        }

    @Test
    fun `an opened draft left while it still loads is kept, unlike a reply`() =
        runTest(dispatcher) {
            val opened = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.start(ComposeMode.Draft(id(7))) } coAnswers { opened.await() }
            val vm = compose(ComposeMode.Draft(id(7)))

            vm.leave()
            opened.complete(MailResult.Ok(draft()))
            advanceUntilIdle()

            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
            coVerify(exactly = 0) { repository.discard(any(), any()) }
        }

    @Test
    fun `a reply or opened draft loads the accounts for its From row, its account stays locked`() =
        runTest(dispatcher) {
            coEvery { repository.start(ComposeMode.Draft(id(7))) } returns MailResult.Ok(draft())
            val vm = compose(ComposeMode.Draft(id(7)))

            assertEquals(listOf(home, work), vm.uiState.value.accounts)
            assertEquals(work.id, vm.uiState.value.accountId)
            assertTrue(vm.uiState.value.accountLocked)
        }

    @Test
    fun `the account address replaces a chosen identity only while the account has no default identity`() =
        runTest(dispatcher) {
            coEvery { repository.identities(work.id) } returns MailResult.Ok(listOf(workSupport))
            coEvery { repository.start(ComposeMode.Draft(id(7))) } returns MailResult.Ok(draft(identityId = workSupport.id))
            val saved = slot<DraftContent>()
            coEvery { repository.update(id(7), "\"1\"", capture(saved)) } returns MailResult.Ok(draft("\"2\""))
            val vm = compose(ComposeMode.Draft(id(7)))

            vm.selectAccountAddress()
            assertNull(vm.uiState.value.identityId)
            assertEquals(DraftState.UNSAVED, vm.uiState.value.draft)
            vm.save()
            advanceUntilIdle()
            assertNull(saved.captured.identityId)
        }

    @Test
    fun `the account address is not chosen while the account has a default identity`() =
        runTest(dispatcher) {
            coEvery { repository.start(ComposeMode.Draft(id(7))) } returns MailResult.Ok(draft(identityId = workSupport.id))
            val vm = compose(ComposeMode.Draft(id(7)))

            vm.selectAccountAddress()

            assertEquals(workSupport.id, vm.uiState.value.identityId)
            assertEquals(DraftState.SAVED, vm.uiState.value.draft)
        }
}
