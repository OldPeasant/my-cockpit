package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.files.DriveEntry
import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.mail.list.MailResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.time.Instant
import java.util.UUID

/**
 * Review fixes of the recipient chips (CR-2, CR-3): a search typed in a recipient field does not fail an autosave or a
 * file attach, and recipients already chosen are neither suggested nor added twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MailComposeRecipientsFilesReviewFixesTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<MailComposeRepository>()
    private val lookup = mockk<ComposeLookup>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Work", "me@example.org")
    private val ann = RecipientSuggestion("ann@example.org", "Ann Example", id(30))
    private val ben = RecipientSuggestion("ben@example.org", null)
    private val plan = DriveEntry.File(id(41), "plan.pdf", "application/pdf", 2048, Instant.parse("2026-11-01T08:00:00Z"))

    private fun draft(
        etag: String = "\"1\"",
        attachments: List<DraftAttachment> = emptyList(),
    ) = Draft(id(1), work.id, null, etag, emptyList(), emptyList(), emptyList(), "", "", "", attachments)

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
        coEvery { repository.identities(any()) } returns MailResult.Ok(emptyList())
        coEvery { repository.create(any(), any()) } returns MailResult.Ok(draft())
        coEvery { repository.update(any(), any(), any()) } returns MailResult.Ok(draft("\"2\""))
        coEvery { repository.send(any(), any()) } returns SendOutcome.Sent
        coEvery { lookup.suggestions(any()) } returns MailResult.Ok(listOf(ann, ben))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newCompose(): MailComposeViewModel {
        val vm = MailComposeViewModel(repository, lookup)
        vm.start(ComposeMode.New(work.id))
        advanceUntilIdle()
        return vm
    }

    // CR-2: a search fragment and saves that are not asked for

    @Test
    fun `an autosave during a recipient search saves the rest and leaves the fragment out`() =
        runTest(dispatcher) {
            val vm = newCompose()
            vm.setTo("ben@example.org, an")
            vm.setSubject("Plan")

            advanceTimeBy(MailComposeViewModel.AUTOSAVE_MILLIS + 1)
            runCurrent()

            val s = vm.uiState.value
            assertNull(s.problem)
            assertTrue(s.invalid.isEmpty())
            assertEquals("ben@example.org, an", s.to)
            coVerify(exactly = 1) {
                repository.create(work.id, match { it.to == listOf(Recipient("ben@example.org")) && it.subject == "Plan" })
            }
            // The fragment is not stored yet, so the draft is not shown as saved and no further autosave loops.
            assertEquals(DraftState.UNSAVED, s.draft)
            advanceTimeBy(3 * MailComposeViewModel.AUTOSAVE_MILLIS)
            runCurrent()
            coVerify(exactly = 0) { repository.update(any(), any(), any()) }
        }

    @Test
    fun `an autosave keeps a typed text that already is an address`() =
        runTest(dispatcher) {
            val vm = newCompose()
            vm.setTo("ben@example.org")

            advanceTimeBy(MailComposeViewModel.AUTOSAVE_MILLIS + 1)
            runCurrent()

            coVerify(exactly = 1) { repository.create(work.id, match { it.to == listOf(Recipient("ben@example.org")) }) }
            assertEquals(DraftState.SAVED, vm.uiState.value.draft)
        }

    @Test
    fun `a finished invalid recipient still fails the autosave`() =
        runTest(dispatcher) {
            val vm = newCompose()
            vm.setTo("nobody, an")

            advanceTimeBy(MailComposeViewModel.AUTOSAVE_MILLIS + 1)
            runCurrent()

            assertEquals(ComposeProblem.INVALID_ADDRESS, vm.uiState.value.problem)
            assertEquals(setOf(RecipientField.TO), vm.uiState.value.invalid)
            coVerify(exactly = 0) { repository.create(any(), any()) }
        }

    @Test
    fun `an explicit save, send or leave still checks the fragment after an autosave left it out`() =
        runTest(dispatcher) {
            val vm = newCompose()
            vm.setTo("ben@example.org, an")
            advanceTimeBy(MailComposeViewModel.AUTOSAVE_MILLIS + 1)
            runCurrent()

            vm.save()
            advanceUntilIdle()
            assertEquals(ComposeProblem.INVALID_ADDRESS, vm.uiState.value.problem)
            assertEquals(setOf(RecipientField.TO), vm.uiState.value.invalid)

            vm.send()
            advanceUntilIdle()
            assertEquals(ComposeProblem.INVALID_ADDRESS, vm.uiState.value.problem)
            assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)
            coVerify(exactly = 0) { repository.send(any(), any()) }

            vm.leave()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.leaveBlocked)
        }

    @Test
    fun `a fragment left out by an autosave is stored once it is finished as a chip`() =
        runTest(dispatcher) {
            val vm = newCompose()
            vm.setTo("ben@example.org, an")
            advanceTimeBy(MailComposeViewModel.AUTOSAVE_MILLIS + 1)
            runCurrent()
            assertEquals(DraftState.UNSAVED, vm.uiState.value.draft)

            vm.setTo(chooseSuggestion(vm.uiState.value.to, ann))
            advanceTimeBy(MailComposeViewModel.AUTOSAVE_MILLIS + 1)
            runCurrent()

            coVerify(exactly = 1) {
                repository.update(
                    id(1),
                    "\"1\"",
                    match { it.to == listOf(Recipient("ben@example.org"), Recipient("ann@example.org", "Ann Example")) },
                )
            }
            assertEquals(DraftState.SAVED, vm.uiState.value.draft)
            assertNull(vm.uiState.value.problem)
        }

    @Test
    fun `a field marked invalid by an explicit save stays checked by the autosave until it is edited`() =
        runTest(dispatcher) {
            val vm = newCompose()
            vm.setTo("ben@example.org, an")
            vm.save()
            advanceUntilIdle()
            assertEquals(setOf(RecipientField.TO), vm.uiState.value.invalid)

            // An edit of another field schedules an autosave; it must not silently drop the marked fragment.
            vm.setSubject("Plan")
            advanceTimeBy(MailComposeViewModel.AUTOSAVE_MILLIS + 1)
            runCurrent()

            assertEquals(ComposeProblem.INVALID_ADDRESS, vm.uiState.value.problem)
            assertEquals(setOf(RecipientField.TO), vm.uiState.value.invalid)
            coVerify(exactly = 0) { repository.create(any(), any()) }
        }

    @Test
    fun `picking a file of Files during a recipient search adds it`() =
        runTest(dispatcher) {
            coEvery { repository.update(id(1), "\"1\"", any()) } returns
                MailResult.Ok(draft("\"2\"", listOf(DraftAttachment("2", "plan.pdf", 2048))))
            val vm = newCompose()
            vm.setTo("ben@example.org, an")

            vm.pickFile(plan)
            advanceUntilIdle()

            val s = vm.uiState.value
            assertNull(s.problem)
            assertTrue(s.invalid.isEmpty())
            assertEquals(listOf("plan.pdf"), s.listedAttachments.map { it.name })
            assertEquals("ben@example.org, an", s.to)
            coVerify(exactly = 1) {
                repository.update(id(1), "\"1\"", match { it.files == listOf(plan.id) && it.to == listOf(Recipient("ben@example.org")) })
            }
        }

    @Test
    fun `a device upload during a recipient search goes ahead`() =
        runTest(dispatcher) {
            coEvery { repository.attach(id(1), "\"1\"", any()) } returns
                MailResult.Ok(draft("\"2\"", listOf(DraftAttachment("2", "notes.txt", 12))))
            val vm = newCompose()
            vm.setTo("an")

            vm.attach(listOf(file("notes.txt")))
            advanceUntilIdle()

            assertNull(vm.uiState.value.problem)
            assertEquals(
                listOf("notes.txt"),
                vm.uiState.value.listedAttachments
                    .map { it.name },
            )
            coVerify(exactly = 1) { repository.create(work.id, match { it.to.isEmpty() }) }
        }

    // CR-3: recipients already chosen

    @Test
    fun `suggestions leave out recipients already in the field, whatever their case`() =
        runTest(dispatcher) {
            val vm = newCompose()

            vm.setTo("Ann <ANN@Example.org>, e")
            advanceTimeBy(MailComposeViewModel.SUGGEST_DEBOUNCE_MILLIS + 1)
            runCurrent()

            assertEquals(RecipientSuggestions(RecipientField.TO, listOf(ben)), vm.suggestions.value)
        }

    @Test
    fun `suggestions of another field are not filtered by this one`() =
        runTest(dispatcher) {
            val vm = newCompose()
            vm.setTo("ann@example.org, ")
            vm.showCcBcc()

            vm.setCc("e")
            advanceTimeBy(MailComposeViewModel.SUGGEST_DEBOUNCE_MILLIS + 1)
            runCurrent()

            assertEquals(RecipientSuggestions(RecipientField.CC, listOf(ann, ben)), vm.suggestions.value)
        }

    @Test
    fun `choosing a recipient already chosen adds no second chip and clears the typed text`() {
        assertEquals("ann@example.org, ", chooseSuggestion("ann@example.org, an", ann))
        assertEquals("Ann <ANN@example.org>, ", chooseSuggestion("Ann <ANN@example.org>, an", ann))
        assertEquals(setOf("ann@example.org", "ben@example.org"), chosenAddresses("Ann <ANN@example.org>, ben@example.org, ca"))
    }
}
