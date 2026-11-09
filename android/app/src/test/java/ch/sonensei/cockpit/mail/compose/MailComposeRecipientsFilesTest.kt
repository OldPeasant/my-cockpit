package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.files.DriveEntry
import ch.sonensei.cockpit.files.FilesRepository
import ch.sonensei.cockpit.files.FilesResult
import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.mail.list.MailResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.util.UUID

/** Recipient chips and suggestions, and attaching a file of cockpit Files. */
@OptIn(ExperimentalCoroutinesApi::class)
class MailComposeRecipientsFilesTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<MailComposeRepository>()
    private val lookup = mockk<ComposeLookup>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Work", "me@example.org")
    private val ann = RecipientSuggestion("ann@example.org", "Ann Example", id(30))
    private val ben = RecipientSuggestion("ben@example.org", null)
    private val reports = DriveEntry.Folder(id(40), "Reports", null)
    private val plan = DriveEntry.File(id(41), "plan.pdf", "application/pdf", 2048, Instant.parse("2026-11-01T08:00:00Z"))

    private fun draft(
        etag: String = "\"1\"",
        attachments: List<DraftAttachment> = emptyList(),
    ) = Draft(id(1), work.id, null, etag, emptyList(), emptyList(), emptyList(), "", "", "", attachments)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.accounts() } returns MailResult.Ok(listOf(work))
        coEvery { repository.identities(any()) } returns MailResult.Ok(emptyList())
        coEvery { repository.create(any(), any()) } returns MailResult.Ok(draft())
        coEvery { repository.update(any(), any(), any()) } returns MailResult.Ok(draft())
        coEvery { lookup.suggestions(any()) } returns MailResult.Ok(listOf(ann, ben))
        coEvery { lookup.folder(null) } returns MailResult.Ok(listOf(reports))
        coEvery { lookup.folder(reports.id) } returns MailResult.Ok(listOf(plan))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newCompose(): MailComposeViewModel {
        val vm = MailComposeViewModel(repository, lookup)
        vm.start(ComposeMode.New(work.id))
        advanceUntilIdle()
        return vm
    }

    // Field text <-> chips

    @Test
    fun `a field splits into finished recipients and the text still typed`() {
        assertEquals(RecipientEntry(emptyList(), "an"), recipientEntry("an"))
        assertEquals(
            RecipientEntry(listOf("Ann <ann@example.org>", "ben@example.org"), "ca"),
            recipientEntry("Ann <ann@example.org>; ben@example.org, ca"),
        )
        assertEquals(RecipientEntry(listOf("\"Doe, Carl\" <carl@example.org>"), ""), recipientEntry("\"Doe, Carl\" <carl@example.org>, "))
        // A separator inside quotes does not finish the recipient.
        assertEquals(RecipientEntry(emptyList(), "\"Doe, Ca"), recipientEntry("\"Doe, Ca"))
    }

    @Test
    fun `choosing a suggestion replaces the typed text with a finished recipient`() {
        val field = chooseSuggestion("ben@example.org, an", ann)
        assertEquals("ben@example.org, Ann Example <ann@example.org>, ", field)
        assertEquals(listOf(Recipient("ben@example.org"), Recipient("ann@example.org", "Ann Example")), parseRecipients(field).recipients)
    }

    @Test
    fun `removing a chip keeps the others and the typed text`() {
        assertEquals("ben@example.org, ca", removeRecipient("ann@example.org, ben@example.org, ca", 0))
        assertEquals("ann@example.org, ", removeRecipient("ann@example.org, ", 3))
        assertEquals("", removeRecipient("ann@example.org, ", 0))
    }

    @Test
    fun `done finishes the typed text as a chip, recipients of a draft start as chips`() {
        assertEquals("ann@example.org, ben@example.org, ", finishRecipient("ann@example.org, ben@example.org"))
        assertEquals("ann@example.org, ", finishRecipient("ann@example.org, "))
        assertEquals("", recipientField(emptyList()))
        assertEquals("Ann <ann@example.org>, ", recipientField(listOf(Recipient("ann@example.org", "Ann"))))
    }

    // Suggestions

    @Test
    fun `typing asks for suggestions of the typed text after a pause, only the last lookup shows`() =
        runTest(dispatcher) {
            val vm = newCompose()

            vm.setTo("ben@example.org, a")
            advanceTimeBy(100)
            vm.setTo("ben@example.org, an")
            advanceTimeBy(MailComposeViewModel.SUGGEST_DEBOUNCE_MILLIS - 1)
            runCurrent()
            assertTrue(
                vm.suggestions.value.items
                    .isEmpty(),
            )
            advanceTimeBy(2)
            runCurrent()

            // ben is already a recipient of the field, so only ann is suggested.
            assertEquals(RecipientSuggestions(RecipientField.TO, listOf(ann)), vm.suggestions.value)
            coVerify(exactly = 1) { lookup.suggestions("an") }
            coVerify(exactly = 0) { lookup.suggestions("a") }
        }

    @Test
    fun `suggestions belong to the field typed in, and hide once a recipient is chosen`() =
        runTest(dispatcher) {
            val vm = newCompose()
            vm.showCcBcc()
            vm.setCc("be")
            advanceTimeBy(MailComposeViewModel.SUGGEST_DEBOUNCE_MILLIS + 1)
            assertEquals(RecipientField.CC, vm.suggestions.value.field)

            vm.setCc(chooseSuggestion(vm.uiState.value.cc, ben))
            advanceUntilIdle()

            assertEquals(RecipientSuggestions(), vm.suggestions.value)
            assertEquals("ben@example.org, ", vm.uiState.value.cc)
        }

    @Test
    fun `a failed lookup shows no suggestions`() =
        runTest(dispatcher) {
            coEvery { lookup.suggestions(any()) } returns MailResult.Offline
            val vm = newCompose()

            vm.setBcc("x")
            advanceUntilIdle()

            assertTrue(
                vm.suggestions.value.items
                    .isEmpty(),
            )
        }

    // Files

    @Test
    fun `the Files picker browses folders and back up`() =
        runTest(dispatcher) {
            val vm = newCompose()

            vm.openFiles()
            advanceUntilIdle()
            assertEquals(listOf<DriveEntry>(reports), vm.filePicker.value?.entries)

            vm.openFolder(reports)
            advanceUntilIdle()
            assertEquals(reports.id, vm.filePicker.value?.folderId)
            assertEquals(listOf<DriveEntry>(plan), vm.filePicker.value?.entries)

            vm.folderUp()
            advanceUntilIdle()
            assertNull(vm.filePicker.value?.folderId)

            vm.closeFiles()
            assertNull(vm.filePicker.value)
        }

    @Test
    fun `a folder that cannot be listed says why and can be retried`() =
        runTest(dispatcher) {
            coEvery { lookup.folder(null) } returns MailResult.Offline andThen MailResult.Ok(listOf(reports))
            val vm = newCompose()

            vm.openFiles()
            advanceUntilIdle()
            assertEquals(FilePickerProblem.OFFLINE, vm.filePicker.value?.problem)

            vm.retryFiles()
            advanceUntilIdle()
            assertNull(vm.filePicker.value?.problem)
            assertEquals(listOf<DriveEntry>(reports), vm.filePicker.value?.entries)
        }

    @Test
    fun `picking a file saves the draft, then adds the file by its id and lists the attachment`() =
        runTest(dispatcher) {
            val stored = slot<DraftContent>()
            coEvery { repository.update(id(1), "\"1\"", capture(stored)) } returns
                MailResult.Ok(draft("\"2\"", listOf(DraftAttachment("2", "plan.pdf", 2048))))
            val vm = newCompose()
            vm.setTo("ann@example.org")
            vm.openFiles()
            advanceUntilIdle()

            vm.pickFile(plan)
            assertNull(vm.filePicker.value)
            assertEquals(listOf("plan.pdf"), vm.uiState.value.uploading)
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.create(work.id, any()) }
            assertEquals(listOf(plan.id), stored.captured.files)
            assertEquals(listOf(Recipient("ann@example.org")), stored.captured.to)
            val s = vm.uiState.value
            assertEquals(listOf("plan.pdf"), s.listedAttachments.map { it.name })
            assertTrue(s.uploading.isEmpty())
            assertEquals(DraftState.SAVED, s.draft)
        }

    @Test
    fun `a file too large for the mail says so`() =
        runTest(dispatcher) {
            coEvery { repository.update(id(1), "\"1\"", any()) } returns MailResult.Failed(400)
            val vm = newCompose()
            vm.setTo("ann@example.org")

            vm.pickFile(plan)
            advanceUntilIdle()

            assertEquals(ComposeProblem.TOO_LARGE, vm.uiState.value.problem)
            assertTrue(
                vm.uiState.value.listedAttachments
                    .isEmpty(),
            )
        }

    // Lookup and API

    @Test
    fun `the lookup asks the contacts autocomplete, drops entries without address and repeats`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse
                        .Builder()
                        .addHeader("Content-Type", "application/json")
                        .body(
                            """[{"contactId":"${id(30)}","email":"ann@example.org","name":"Ann Example"},
                            |{"contactId":"${id(31)}","email":null,"name":"No Mail"},
                            |{"contactId":null,"email":"ANN@example.org","name":"Ann"},
                            |{"contactId":null,"email":"ben@example.org","name":null}]
                            """.trimMargin(),
                        ).build(),
                )
                val api =
                    Retrofit
                        .Builder()
                        .baseUrl(server.url("/"))
                        .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                        .build()
                        .create(MailComposeApi::class.java)
                val remote = RemoteComposeLookup(api, mockk<FilesRepository>())

                val result = remote.suggestions(" an ") as MailResult.Ok

                val request = server.takeRequest()
                assertEquals("/api/v1/contacts/autocomplete", request.url.encodedPath)
                assertEquals("an", request.url.queryParameter("q"))
                assertEquals(listOf(ann, ben), result.value)
                assertEquals(MailResult.Ok(emptyList<RecipientSuggestion>()), remote.suggestions("  "))
            }
        }

    @Test
    fun `the lookup lists folders through Files and maps its failures`() =
        runBlocking {
            val files = mockk<FilesRepository>()
            coEvery { files.list(null) } returns FilesResult.Ok(listOf(reports))
            coEvery { files.list(reports.id) } returns FilesResult.Offline
            coEvery { files.list(plan.id) } returns FilesResult.Failed(404)
            val remote = RemoteComposeLookup(mockk(), files)

            assertEquals(MailResult.Ok(listOf<DriveEntry>(reports)), remote.folder(null))
            assertEquals(MailResult.Offline, remote.folder(reports.id))
            assertEquals(MailResult.Failed(404), remote.folder(plan.id))
        }

    @Test
    fun `a save adding a file sends its id next to the kept parts`() =
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
                val api =
                    Retrofit
                        .Builder()
                        .baseUrl(server.url("/"))
                        .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                        .build()
                        .create(MailComposeApi::class.java)

                MailComposeRepository(api).update(
                    id(1),
                    "\"7\"",
                    DraftContent(emptyList(), emptyList(), emptyList(), "", "", keep = listOf("2"), files = listOf(plan.id)),
                )

                val body = server.takeRequest().body!!.utf8()
                assertTrue(body, body.contains("\"attachments\":[{\"partId\":\"2\"},{\"fileId\":\"${plan.id}\"}]"))
            }
        }
}
