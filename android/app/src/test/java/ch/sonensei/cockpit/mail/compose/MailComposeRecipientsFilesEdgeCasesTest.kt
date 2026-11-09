package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.files.DriveEntry
import ch.sonensei.cockpit.files.FilesRepository
import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.mail.list.MailResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.util.UUID

/**
 * Edge cases of recipient chips, suggestions and attaching from cockpit Files (test-writer): quotes in chips, stale and
 * cancelled lookups, suggestions and the picker across composes, and a Files attachment while offline, with an invalid
 * address, after or during a discard, over a newer version and next to a send.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MailComposeRecipientsFilesEdgeCasesTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<MailComposeRepository>()
    private val lookup = mockk<ComposeLookup>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Work", "me@example.org")
    private val ann = RecipientSuggestion("ann@example.org", "Ann Example", id(30))
    private val ben = RecipientSuggestion("ben@example.org", null)
    private val reports = DriveEntry.Folder(id(40), "Reports", null)
    private val archive = DriveEntry.Folder(id(42), "Archive", null)
    private val plan = DriveEntry.File(id(41), "plan.pdf", "application/pdf", 2048, Instant.parse("2026-11-01T08:00:00Z"))
    private val notes = DriveEntry.File(id(43), "notes.txt", "text/plain", 12, Instant.parse("2026-11-02T08:00:00Z"))
    private val reply = ComposeMode.Reply(id(5))

    private fun draft(
        etag: String = "\"1\"",
        attachments: List<DraftAttachment> = emptyList(),
        to: List<Recipient> = emptyList(),
    ) = Draft(id(1), work.id, null, etag, to, emptyList(), emptyList(), "", "", "", attachments)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.accounts() } returns MailResult.Ok(listOf(work))
        coEvery { repository.identities(any()) } returns MailResult.Ok(emptyList())
        coEvery { repository.create(any(), any()) } returns MailResult.Ok(draft())
        coEvery { repository.update(any(), any(), any()) } returns MailResult.Ok(draft())
        coEvery { repository.discard(any(), any()) } returns MailResult.Ok(Unit)
        coEvery { repository.send(any(), any()) } returns SendOutcome.Sent
        coEvery { lookup.suggestions(any()) } returns MailResult.Ok(listOf(ann, ben))
        coEvery { lookup.folder(null) } returns MailResult.Ok(listOf(reports))
        coEvery { lookup.folder(reports.id) } returns MailResult.Ok(listOf(plan))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newCompose(vm: MailComposeViewModel = MailComposeViewModel(repository, lookup)): MailComposeViewModel {
        vm.start(ComposeMode.New(work.id))
        advanceUntilIdle()
        return vm
    }

    /** A new compose whose draft is stored once (etag "1") with [to] as its recipient. */
    private fun TestScope.savedCompose(to: String = "ann@example.org"): MailComposeViewModel {
        val vm = newCompose()
        vm.setTo(to)
        vm.save()
        advanceUntilIdle()
        assertEquals(DraftState.SAVED, vm.uiState.value.draft)
        return vm
    }

    // Quotes in chips

    @Test
    fun `a suggestion whose name holds a comma becomes one quoted chip that reads back as the contact`() {
        val doe = RecipientSuggestion("carl@example.org", "Doe, Carl")

        val field = chooseSuggestion("ann@example.org, do", doe)

        assertEquals("ann@example.org, \"Doe, Carl\" <carl@example.org>, ", field)
        assertEquals(RecipientEntry(listOf("ann@example.org", "\"Doe, Carl\" <carl@example.org>"), ""), recipientEntry(field))
        assertEquals(listOf(Recipient("ann@example.org"), Recipient("carl@example.org", "Doe, Carl")), parseRecipients(field).recipients)
        // Removing the quoted chip does not split it at its inner comma.
        assertEquals("ann@example.org, ", removeRecipient(field, 1))
    }

    @Test
    fun `a semicolon inside quotes does not finish a chip, one outside does`() {
        assertEquals(RecipientEntry(emptyList(), "\"Doe; Ca"), recipientEntry("\"Doe; Ca"))
        assertEquals(
            RecipientEntry(listOf("\"Doe; Carl\" <carl@example.org>"), "be"),
            recipientEntry("\"Doe; Carl\" <carl@example.org>;   be"),
        )
    }

    @Test
    fun `a name with a pair of quotes stays one chip`() {
        val jj = RecipientSuggestion("jj@example.org", "Jane \"JJ\" Doe")

        val field = chooseSuggestion("", jj)

        assertEquals(listOf("Jane \"JJ\" Doe <jj@example.org>"), recipientEntry(field).tokens)
        assertEquals("", recipientEntry(field).pending)
        assertEquals(listOf(Recipient("jj@example.org", "Jane \"JJ\" Doe")), parseRecipients(field).recipients)
    }

    @Test
    fun `a name with a single quote character still becomes a finished, valid chip`() {
        // Fictitious display name with one stray double quote (as some mail clients send it).
        val stray = RecipientSuggestion("pat@example.org", "Pat \"Quill")

        val field = chooseSuggestion("", stray)

        assertEquals("", recipientEntry(field).pending)
        assertEquals(1, recipientEntry(field).tokens.size)
        assertTrue(parseRecipients(field).invalid.isEmpty())
        assertEquals(listOf("pat@example.org"), parseRecipients(field).recipients.map { it.address })
    }

    @Test
    fun `a reply to a sender whose name has a single quote character can be saved`() =
        runTest(dispatcher) {
            coEvery { repository.start(reply) } returns
                MailResult.Ok(draft(to = listOf(Recipient("pat@example.org", "Pat \"Quill"))))
            val vm = MailComposeViewModel(repository, lookup)
            vm.start(reply)
            advanceUntilIdle()

            vm.setSubject("Re: plan")
            vm.save()
            advanceUntilIdle()

            assertNull(vm.uiState.value.problem)
            assertTrue(
                vm.uiState.value.invalid
                    .isEmpty(),
            )
            coVerify(exactly = 1) { repository.update(id(1), "\"1\"", match { c -> c.to.map { it.address } == listOf("pat@example.org") }) }
        }

    @Test
    fun `edge inputs of the chip helpers leave the field as it is`() {
        assertEquals(RecipientEntry(emptyList(), ""), recipientEntry(""))
        assertEquals(RecipientEntry(listOf("ann@example.org"), ""), recipientEntry("ann@example.org,   "))
        assertEquals("ann@example.org, ", removeRecipient("ann@example.org, ", -1))
        assertEquals("ann@example.org,   ", finishRecipient("ann@example.org,   "))
        assertEquals("", finishRecipient(""))
        // Separators collapse: blank tokens between them are not chips.
        assertEquals(listOf("ann@example.org", "ben@example.org"), recipientEntry("ann@example.org,, ;ben@example.org, ").tokens)
    }

    @Test
    fun `done on an unfinished quoted name keeps the chips before it and the name is not taken as an address`() {
        val field = finishRecipient("ann@example.org, \"Doe, Ca")

        assertEquals(listOf("ann@example.org"), recipientEntry(field).tokens)
        assertEquals(listOf(Recipient("ann@example.org")), parseRecipients(field).recipients)
        assertEquals(1, parseRecipients(field).invalid.size)
    }

    @Test
    fun `typed text after chips keeps the chips when the field is written back`() {
        val entry = recipientEntry("Ann <ann@example.org>, be")

        assertEquals("Ann <ann@example.org>, ben", entry.copy(pending = "ben").text())
        assertEquals("ben", RecipientEntry(emptyList(), "ben").text())
    }

    // Suggestions

    @Test
    fun `only the typed text after the chips is looked up, trimmed`() =
        runTest(dispatcher) {
            val vm = newCompose()

            vm.setTo("\"Doe, Carl\" <carl@example.org>,   be  ")
            advanceUntilIdle()

            coVerify(exactly = 1) { lookup.suggestions("be") }
            assertEquals(RecipientField.TO, vm.suggestions.value.field)
        }

    @Test
    fun `nothing typed after the chips looks nothing up and hides shown suggestions`() =
        runTest(dispatcher) {
            val vm = newCompose()
            vm.setTo("an")
            advanceUntilIdle()
            assertEquals(listOf(ann, ben), vm.suggestions.value.items)

            vm.setTo("ann@example.org,   ")
            advanceUntilIdle()

            assertEquals(RecipientSuggestions(), vm.suggestions.value)
            coVerify(exactly = 1) { lookup.suggestions(any()) }
        }

    @Test
    fun `a lookup still running when typing goes on never shows its answer`() =
        runTest(dispatcher) {
            val slow = CompletableDeferred<MailResult<List<RecipientSuggestion>>>()
            coEvery { lookup.suggestions("a") } coAnswers { slow.await() }
            coEvery { lookup.suggestions("an") } returns MailResult.Ok(listOf(ann))
            val vm = newCompose()

            vm.setTo("a")
            advanceTimeBy(MailComposeViewModel.SUGGEST_DEBOUNCE_MILLIS + 1)
            runCurrent()
            coVerify(exactly = 1) { lookup.suggestions("a") }
            vm.setTo("an")
            slow.complete(MailResult.Ok(listOf(ben)))
            advanceUntilIdle()

            assertEquals(RecipientSuggestions(RecipientField.TO, listOf(ann)), vm.suggestions.value)
        }

    @Test
    fun `leaving the recipient fields cancels a waiting lookup`() =
        runTest(dispatcher) {
            val vm = newCompose()

            vm.setTo("an")
            vm.clearSuggestions()
            advanceUntilIdle()

            assertEquals(RecipientSuggestions(), vm.suggestions.value)
            coVerify(exactly = 0) { lookup.suggestions(any()) }
        }

    @Test
    fun `starting another compose hides the suggestions and drops a lookup of the old one`() =
        runTest(dispatcher) {
            val slow = CompletableDeferred<MailResult<List<RecipientSuggestion>>>()
            val vm = newCompose()
            vm.setTo("an")
            advanceUntilIdle()
            assertEquals(listOf(ann, ben), vm.suggestions.value.items)

            // A lookup of the old compose is still running when the next compose starts.
            coEvery { lookup.suggestions("be") } coAnswers { slow.await() }
            vm.setCc("be")
            advanceTimeBy(MailComposeViewModel.SUGGEST_DEBOUNCE_MILLIS + 1)
            runCurrent()
            // Another mode, so this is a new compose (the same mode again keeps the open one).
            vm.start(ComposeMode.New())
            assertEquals(RecipientSuggestions(), vm.suggestions.value)
            slow.complete(MailResult.Ok(listOf(ben)))
            advanceUntilIdle()

            assertEquals(RecipientSuggestions(), vm.suggestions.value)
        }

    @Test
    fun `a reply that is still being prepared looks nothing up`() =
        runTest(dispatcher) {
            val preparing = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.start(reply) } coAnswers { preparing.await() }
            val vm = MailComposeViewModel(repository, lookup)
            vm.start(reply)
            runCurrent()

            vm.setTo("an")
            advanceTimeBy(MailComposeViewModel.SUGGEST_DEBOUNCE_MILLIS + 1)
            runCurrent()

            assertEquals(RecipientSuggestions(), vm.suggestions.value)
            coVerify(exactly = 0) { lookup.suggestions(any()) }
            preparing.complete(MailResult.Failed(500))
            advanceUntilIdle()
        }

    @Test
    fun `a reply starts with its recipients as chips and nothing typed`() =
        runTest(dispatcher) {
            coEvery { repository.start(reply) } returns
                MailResult.Ok(draft(to = listOf(Recipient("carl@example.org", "Doe, Carl"), Recipient("ben@example.org"))))
            val vm = MailComposeViewModel(repository, lookup)
            vm.start(reply)
            advanceUntilIdle()

            val entry = recipientEntry(vm.uiState.value.to)
            assertEquals(listOf("\"Doe, Carl\" <carl@example.org>", "ben@example.org"), entry.tokens)
            assertEquals("", entry.pending)
            assertEquals("", vm.uiState.value.cc)
        }

    // The Files picker

    @Test
    fun `the picker does not open while a reply is still being prepared`() =
        runTest(dispatcher) {
            val preparing = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.start(reply) } coAnswers { preparing.await() }
            val vm = MailComposeViewModel(repository, lookup)
            vm.start(reply)
            runCurrent()

            vm.openFiles()
            advanceUntilIdle()

            assertNull(vm.filePicker.value)
            coVerify(exactly = 0) { lookup.folder(any()) }
            preparing.complete(MailResult.Failed(500))
            advanceUntilIdle()
        }

    @Test
    fun `the picker shows the folder loading at once, and at the root up stays there`() =
        runTest(dispatcher) {
            val vm = newCompose()

            vm.openFiles()
            assertEquals(FilePickerState(path = emptyList(), loading = true), vm.filePicker.value)
            advanceUntilIdle()
            vm.folderUp()
            runCurrent()

            assertEquals(FilePickerState(entries = listOf(reports), loading = false), vm.filePicker.value)
            coVerify(exactly = 1) { lookup.folder(null) }
        }

    @Test
    fun `going up before a folder answers shows the parent, not the late answer of the folder`() =
        runTest(dispatcher) {
            val slow = CompletableDeferred<MailResult<List<DriveEntry>>>()
            coEvery { lookup.folder(reports.id) } coAnswers { slow.await() }
            val vm = newCompose()
            vm.openFiles()
            advanceUntilIdle()

            vm.openFolder(reports)
            runCurrent()
            assertEquals(reports.id, vm.filePicker.value?.folderId)
            vm.folderUp()
            slow.complete(MailResult.Ok(listOf(plan)))
            advanceUntilIdle()

            val picker = vm.filePicker.value
            assertNull(picker?.folderId)
            assertEquals(listOf<DriveEntry>(reports), picker?.entries)
        }

    @Test
    fun `nested folders build the path, a failed one keeps it for the retry`() =
        runTest(dispatcher) {
            coEvery { lookup.folder(reports.id) } returns MailResult.Ok(listOf(archive, plan))
            coEvery { lookup.folder(archive.id) } returns MailResult.Failed(500) andThen MailResult.Ok(listOf(notes))
            val vm = newCompose()
            vm.openFiles()
            advanceUntilIdle()
            vm.openFolder(reports)
            advanceUntilIdle()

            vm.openFolder(archive)
            advanceUntilIdle()
            assertEquals(FilePickerProblem.FAILED, vm.filePicker.value?.problem)
            assertEquals(
                listOf("Reports", "Archive"),
                vm.filePicker.value
                    ?.path
                    ?.map { it.name },
            )

            vm.retryFiles()
            advanceUntilIdle()
            assertNull(vm.filePicker.value?.problem)
            assertEquals(listOf<DriveEntry>(notes), vm.filePicker.value?.entries)
            assertEquals(archive.id, vm.filePicker.value?.folderId)
        }

    @Test
    fun `closing the picker drops a listing still running`() =
        runTest(dispatcher) {
            val slow = CompletableDeferred<MailResult<List<DriveEntry>>>()
            coEvery { lookup.folder(null) } coAnswers { slow.await() }
            val vm = newCompose()

            vm.openFiles()
            runCurrent()
            vm.closeFiles()
            slow.complete(MailResult.Ok(listOf(reports)))
            advanceUntilIdle()

            assertNull(vm.filePicker.value)
        }

    @Test
    fun `starting another compose closes the picker, a listing of the old one does not reopen it`() =
        runTest(dispatcher) {
            val slow = CompletableDeferred<MailResult<List<DriveEntry>>>()
            coEvery { lookup.folder(null) } coAnswers { slow.await() }
            val vm = newCompose()
            vm.openFiles()
            runCurrent()

            vm.start(ComposeMode.New())
            assertNull(vm.filePicker.value)
            slow.complete(MailResult.Ok(listOf(reports)))
            advanceUntilIdle()

            assertNull(vm.filePicker.value)
        }

    @Test
    fun `navigating or retrying without an open picker does nothing`() =
        runTest(dispatcher) {
            val vm = newCompose()

            vm.openFolder(reports)
            vm.folderUp()
            vm.retryFiles()
            advanceUntilIdle()

            assertNull(vm.filePicker.value)
            coVerify(exactly = 0) { lookup.folder(any()) }
        }

    // Attaching a file of Files

    @Test
    fun `a file picked offline before the first save says offline and adds nothing`() =
        runTest(dispatcher) {
            coEvery { repository.create(any(), any()) } returns MailResult.Offline
            val vm = newCompose()
            vm.setTo("ann@example.org")

            vm.pickFile(plan)
            advanceUntilIdle()

            val s = vm.uiState.value
            assertEquals(ComposeProblem.OFFLINE, s.problem)
            assertTrue(s.uploading.isEmpty())
            assertTrue(s.listedAttachments.isEmpty())
            assertEquals(DraftState.UNSAVED, s.draft)
            coVerify(exactly = 0) { repository.update(any(), any(), any()) }
        }

    @Test
    fun `a file picked while the stored draft cannot be reached says offline and keeps the attachments`() =
        runTest(dispatcher) {
            val vm = savedCompose()
            coEvery { repository.update(id(1), "\"1\"", match { it.files.isNotEmpty() }) } returns MailResult.Offline

            vm.pickFile(plan)
            advanceUntilIdle()

            val s = vm.uiState.value
            assertEquals(ComposeProblem.OFFLINE, s.problem)
            assertTrue(s.uploading.isEmpty())
            assertTrue(s.listedAttachments.isEmpty())
            assertTrue(s.editable)
        }

    @Test
    fun `a file picked next to an invalid recipient is not added and marks the field`() =
        runTest(dispatcher) {
            val vm = newCompose()
            vm.setTo("nobody, ")

            vm.pickFile(plan)
            advanceUntilIdle()

            val s = vm.uiState.value
            assertEquals(ComposeProblem.INVALID_ADDRESS, s.problem)
            assertEquals(setOf(RecipientField.TO), s.invalid)
            assertTrue(s.uploading.isEmpty())
            coVerify(exactly = 0) { repository.create(any(), any()) }
            coVerify(exactly = 0) { repository.update(any(), any(), any()) }
        }

    @Test
    fun `an invalid recipient typed into a stored draft blocks the file too`() =
        runTest(dispatcher) {
            val vm = savedCompose()
            vm.setCc("not-an-address, ")

            vm.pickFile(plan)
            advanceUntilIdle()

            assertEquals(ComposeProblem.INVALID_ADDRESS, vm.uiState.value.problem)
            assertEquals(setOf(RecipientField.CC), vm.uiState.value.invalid)
            coVerify(exactly = 0) { repository.update(any(), any(), match { it.files.isNotEmpty() }) }
        }

    @Test
    fun `a file picked after the compose was discarded is not added`() =
        runTest(dispatcher) {
            val vm = savedCompose()
            vm.discard()
            advanceUntilIdle()
            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)

            vm.openFiles()
            vm.pickFile(plan)
            advanceUntilIdle()

            assertNull(vm.filePicker.value)
            assertTrue(
                vm.uiState.value.uploading
                    .isEmpty(),
            )
            coVerify(exactly = 0) { repository.update(any(), any(), match { it.files.isNotEmpty() }) }
            coVerify(exactly = 0) { lookup.folder(any()) }
        }

    @Test
    fun `a discard while the file's save runs drops the file`() =
        runTest(dispatcher) {
            val vm = savedCompose()
            val saving = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.update(id(1), "\"1\"", match { it.files.isEmpty() }) } coAnswers { saving.await() }
            vm.setSubject("Plan")

            vm.pickFile(plan)
            runCurrent()
            vm.discard()
            runCurrent()
            saving.complete(MailResult.Ok(draft("\"2\"")))
            advanceUntilIdle()

            assertEquals(ComposePhase.CLOSED, vm.uiState.value.phase)
            coVerify(exactly = 0) { repository.update(any(), any(), match { it.files.isNotEmpty() }) }
            coVerify(exactly = 1) { repository.discard(id(1), any()) }
        }

    @Test
    fun `a newer version on the server is overwritten with the file still added`() =
        runTest(dispatcher) {
            val vm = savedCompose()
            coEvery { repository.update(id(1), "\"1\"", match { it.files.isNotEmpty() }) } returns MailResult.Failed(412)
            coEvery { repository.get(id(1)) } returns MailResult.Ok(draft("\"3\""))
            coEvery { repository.update(id(1), "\"3\"", any()) } returns
                MailResult.Ok(draft("\"4\"", listOf(DraftAttachment("2", "plan.pdf", 2048))))

            vm.pickFile(plan)
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.update(id(1), "\"3\"", match { it.files == listOf(plan.id) }) }
            assertEquals(
                listOf("plan.pdf"),
                vm.uiState.value.listedAttachments
                    .map { it.name },
            )
            assertNull(vm.uiState.value.problem)
        }

    @Test
    fun `a change typed while the first save runs goes along with the file`() =
        runTest(dispatcher) {
            val creating = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.create(any(), any()) } coAnswers { creating.await() }
            coEvery { repository.update(id(1), "\"1\"", any()) } returns
                MailResult.Ok(draft("\"2\"", listOf(DraftAttachment("2", "plan.pdf", 2048))))
            val vm = newCompose()
            vm.setTo("ann@example.org")

            vm.pickFile(plan)
            runCurrent()
            vm.setSubject("Quarterly plan")
            creating.complete(MailResult.Ok(draft()))
            advanceUntilIdle()

            coVerify(exactly = 1) {
                repository.update(id(1), "\"1\"", match { it.subject == "Quarterly plan" && it.files == listOf(plan.id) })
            }
            assertEquals(DraftState.SAVED, vm.uiState.value.draft)
        }

    @Test
    fun `two files picked one after another are both added, each over the version before`() =
        runTest(dispatcher) {
            val vm = savedCompose()
            coEvery { repository.update(id(1), "\"1\"", match { it.files == listOf(plan.id) }) } returns
                MailResult.Ok(draft("\"2\"", listOf(DraftAttachment("2", "plan.pdf", 2048))))
            coEvery { repository.update(id(1), "\"2\"", match { it.files == listOf(notes.id) }) } returns
                MailResult.Ok(draft("\"3\"", listOf(DraftAttachment("2", "plan.pdf", 2048), DraftAttachment("3", "notes.txt", 12))))

            vm.pickFile(plan)
            vm.pickFile(notes)
            assertEquals(listOf("plan.pdf", "notes.txt"), vm.uiState.value.uploading)
            advanceUntilIdle()

            assertEquals(
                listOf("plan.pdf", "notes.txt"),
                vm.uiState.value.listedAttachments
                    .map { it.name },
            )
            assertTrue(
                vm.uiState.value.uploading
                    .isEmpty(),
            )
            coVerify(exactly = 1) { repository.update(id(1), "\"2\"", match { it.keep == listOf("2") && it.files == listOf(notes.id) }) }
        }

    @Test
    fun `send waits while a file is being added`() =
        runTest(dispatcher) {
            val vm = savedCompose()
            val adding = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.update(id(1), "\"1\"", match { it.files.isNotEmpty() }) } coAnswers { adding.await() }

            vm.pickFile(plan)
            runCurrent()
            vm.send()
            runCurrent()

            assertEquals(ComposePhase.EDITING, vm.uiState.value.phase)
            coVerify(exactly = 0) { repository.send(any(), any()) }
            adding.complete(MailResult.Ok(draft("\"2\"", listOf(DraftAttachment("2", "plan.pdf", 2048)))))
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.uploading
                    .isEmpty(),
            )
        }

    @Test
    fun `a refused file says it failed, a file that is too large for the server says so`() =
        runTest(dispatcher) {
            val vm = savedCompose()
            coEvery { repository.update(id(1), "\"1\"", match { it.files == listOf(plan.id) }) } returns MailResult.Failed(500)
            coEvery { repository.update(id(1), "\"1\"", match { it.files == listOf(notes.id) }) } returns MailResult.Failed(413)

            vm.pickFile(plan)
            advanceUntilIdle()
            assertEquals(ComposeProblem.ATTACH_FAILED, vm.uiState.value.problem)

            vm.pickFile(notes)
            advanceUntilIdle()
            assertEquals(ComposeProblem.TOO_LARGE, vm.uiState.value.problem)
            assertTrue(
                vm.uiState.value.listedAttachments
                    .isEmpty(),
            )
        }

    @Test
    fun `a file added after a failure clears the problem`() =
        runTest(dispatcher) {
            val vm = savedCompose()
            coEvery { repository.update(id(1), "\"1\"", match { it.files.isNotEmpty() }) } returns
                MailResult.Offline andThen MailResult.Ok(draft("\"2\"", listOf(DraftAttachment("2", "plan.pdf", 2048))))

            vm.pickFile(plan)
            advanceUntilIdle()
            assertEquals(ComposeProblem.OFFLINE, vm.uiState.value.problem)

            vm.pickFile(plan)
            advanceUntilIdle()
            assertNull(vm.uiState.value.problem)
            assertEquals(
                listOf("plan.pdf"),
                vm.uiState.value.listedAttachments
                    .map { it.name },
            )
        }

    @Test
    fun `an attachment removed while a file is added does not come back`() =
        runTest(dispatcher) {
            coEvery { repository.start(reply) } returns MailResult.Ok(draft(attachments = listOf(DraftAttachment("7", "old.pdf", 5))))
            val vm = MailComposeViewModel(repository, lookup)
            vm.start(reply)
            advanceUntilIdle()
            val adding = CompletableDeferred<MailResult<Draft>>()
            coEvery { repository.update(id(1), "\"1\"", match { it.files.isNotEmpty() }) } coAnswers { adding.await() }
            coEvery { repository.update(id(1), "\"2\"", any()) } returns
                MailResult.Ok(draft("\"3\"", listOf(DraftAttachment("9", "plan.pdf", 2048))))

            vm.pickFile(plan)
            runCurrent()
            vm.removeAttachment("7")
            adding.complete(
                MailResult.Ok(draft("\"2\"", listOf(DraftAttachment("8", "old.pdf", 5), DraftAttachment("9", "plan.pdf", 2048)))),
            )
            advanceUntilIdle()

            assertEquals(
                listOf("plan.pdf"),
                vm.uiState.value.listedAttachments
                    .map { it.name },
            )
            // The removal is saved afterwards (autosave), keeping only the added file.
            coVerify(exactly = 1) { repository.update(id(1), "\"2\"", match { it.keep == listOf("9") && it.files.isEmpty() }) }
        }

    // API

    @Test
    fun `a save keeping parts only sends no file ids`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse
                        .Builder()
                        .addHeader("Content-Type", "application/json")
                        .addHeader("ETag", "\"8\"")
                        .body("""{"id":"${id(1)}","attachments":[]}""")
                        .build(),
                )
                MailComposeRepository(api(server)).update(
                    id(1),
                    "\"7\"",
                    DraftContent(emptyList(), emptyList(), emptyList(), "", "", keep = emptyList(), files = listOf(plan.id, notes.id)),
                )

                val body = server.takeRequest().body!!.utf8()
                assertTrue(body, body.contains("\"attachments\":[{\"fileId\":\"${plan.id}\"},{\"fileId\":\"${notes.id}\"}]"))
                assertFalse(body, body.contains("partId"))
            }
        }

    @Test
    fun `the lookup asks for at most eight, ignores a malformed contact id and blank names, and maps a failure`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse
                        .Builder()
                        .addHeader("Content-Type", "application/json")
                        .body("""[{"contactId":"not-a-uuid","email":" ann@example.org ","name":"  "}]""")
                        .build(),
                )
                server.enqueue(MockResponse.Builder().code(403).build())
                val remote = RemoteComposeLookup(api(server), mockk<FilesRepository>())

                val result = remote.suggestions("ann")
                val request = server.takeRequest()
                val failed = remote.suggestions("ann")

                assertEquals("8", request.url.queryParameter("limit"))
                assertEquals(MailResult.Ok(listOf(RecipientSuggestion("ann@example.org", null, null))), result)
                assertEquals(MailResult.Failed(403), failed)
            }
        }

    private fun api(server: MockWebServer): MailComposeApi =
        Retrofit
            .Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MailComposeApi::class.java)
}
