package ch.sonensei.cockpit.capture.share

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.capture.queue.CaptureAttachment
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import ch.sonensei.cockpit.capture.queue.QueueHarness
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch

/** The capture sheet's syntax preview, "save and add another" and picked attachments, against a mocked queue. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ShareViewModelCaptureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val queue = mockk<CaptureQueue>()
    private val captureId = UUID.fromString("00000000-0000-7000-9000-000000000001")
    private val today = LocalDate.of(2026, 3, 1)
    private val garden = SyntaxProject("00000000-0000-7000-8000-000000000002", "Garden")
    private val home = SyntaxContext("00000000-0000-7000-8000-000000000011", "@home")
    private val vocabularies = MutableStateFlow(CaptureVocabulary(listOf(garden), listOf(home)))

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        TestFilesProvider.dir = File(context.cacheDir, "picked-${System.nanoTime()}").apply { mkdirs() }
        Robolectric.setupContentProvider(TestFilesProvider::class.java, TestFilesProvider.AUTHORITY)
        coEvery { queue.capture(any(), any(), any()) } returns captureId
        coEvery { queue.readLater(any(), any()) } returns captureId
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        TestFilesProvider.dir.deleteRecursively()
        ShareViewModelTest.GatedProvider.gate = CountDownLatch(0)
    }

    private fun viewModel(q: CaptureQueue = queue) = ShareViewModel(q, context, vocabularies) { today }

    private fun unnamed(n: Int): Uri = Uri.parse("content://${TestFilesProvider.AUTHORITY}/missing-$n")

    private fun gated(name: String): Uri = Uri.parse("content://${ShareViewModelTest.GatedProvider.AUTHORITY}/$name")

    private fun ShareViewModel.awaitFiles(): ShareUiState = runBlocking { withTimeout(5_000) { uiState.first { !it.loadingFiles } } }

    private fun ShareViewModel.await(condition: (ShareUiState) -> Boolean): ShareUiState =
        runBlocking { withTimeout(5_000) { uiState.first(condition) } }

    private fun picked(
        viewModel: ShareViewModel,
        uris: List<Uri>,
    ): ShareUiState {
        viewModel.addFiles(uris)
        return viewModel.awaitFiles()
    }

    // Syntax preview

    @Test
    fun `typing shows the recognized project, contexts, due date and waiting`() {
        val viewModel = viewModel()

        viewModel.onTextChange("Plant tulips #garden @home !tomorrow ^Anna")

        val preview = viewModel.uiState.value.preview!!
        assertEquals("Plant tulips", preview.title)
        assertEquals(garden, preview.project)
        assertEquals(listOf(home), preview.contexts)
        assertEquals(today.plusDays(1), preview.dueDate)
        assertTrue(preview.waiting)
        assertEquals("Anna", preview.waitingFor)
    }

    @Test
    fun `no preview while nothing or only spaces are typed`() {
        val viewModel = viewModel()
        assertNull(viewModel.uiState.value.preview)

        viewModel.onTextChange("Buy #garden")
        viewModel.onTextChange("   ")

        assertNull(viewModel.uiState.value.preview)
    }

    @Test
    fun `plain text has a preview without tokens`() {
        val viewModel = viewModel()

        viewModel.onTextChange("Call the plumber")

        assertFalse(
            viewModel.uiState.value.preview!!
                .hasTokens,
        )
    }

    @Test
    fun `a project cached after typing is recognized without retyping`() {
        vocabularies.value = CaptureVocabulary.EMPTY
        val viewModel = viewModel()
        viewModel.onTextChange("Buy seeds #garden")
        assertNull(
            viewModel.uiState.value.preview!!
                .project,
        )

        vocabularies.value = CaptureVocabulary(listOf(garden))

        assertEquals(
            garden,
            viewModel.uiState.value.preview!!
                .project,
        )
        assertEquals("Buy seeds #garden", viewModel.uiState.value.text)
    }

    @Test
    fun `the shared title is previewed`() {
        val viewModel = viewModel()

        viewModel.load(SharedContent("Water plants @home", null, emptyList()))

        assertEquals(
            listOf(home),
            viewModel.uiState.value.preview!!
                .contexts,
        )
    }

    @Test
    fun `the preview is local only and queues nothing`() {
        val viewModel = viewModel()

        viewModel.onTextChange("Plant #garden !today")

        coVerify(exactly = 0) { queue.capture(any(), any(), any()) }
        coVerify(exactly = 0) { queue.readLater(any(), any()) }
    }

    // Save and add another

    @Test
    fun `save and add another queues the capture and clears the sheet for the next one`() {
        val photo = TestFilesProvider.share("beach.jpg", "beach")
        val viewModel = viewModel()
        viewModel.load(SharedContent("Holiday #garden", "bring towels", listOf(photo)))
        viewModel.awaitFiles()

        viewModel.save(addAnother = true)

        coVerify(exactly = 1) { queue.capture("Holiday #garden", "bring towels", listOf(CaptureAttachment(photo, "beach.jpg"))) }
        val state = viewModel.uiState.value
        assertEquals(ShareUiState(savedAndCleared = 1), state)
        assertFalse(state.saved)
        assertFalse(state.canSave)
    }

    @Test
    fun `each capture saved with add another counts, a plain save after it closes the sheet`() {
        val viewModel = viewModel()

        viewModel.onTextChange("First")
        viewModel.save(addAnother = true)
        viewModel.onTextChange("Second @home")
        viewModel.save(addAnother = true)

        assertEquals(2, viewModel.uiState.value.savedAndCleared)
        assertEquals(
            listOf(home),
            viewModel
                .also { it.onTextChange("Third @home") }
                .uiState.value.preview!!
                .contexts,
        )
        viewModel.save()

        assertTrue(viewModel.uiState.value.saved)
        coVerifyOrder {
            queue.capture("First", "", emptyList())
            queue.capture("Second @home", "", emptyList())
            queue.capture("Third @home", "", emptyList())
        }
    }

    @Test
    fun `a blank title is not saved with add another`() {
        val viewModel = viewModel()

        viewModel.save(addAnother = true)

        assertEquals(ShareError.EMPTY_TEXT, viewModel.uiState.value.error)
        assertEquals(0, viewModel.uiState.value.savedAndCleared)
        coVerify(exactly = 0) { queue.capture(any(), any(), any()) }
    }

    @Test
    fun `a failed save with add another keeps the form and its error`() {
        coEvery { queue.capture(any(), any(), any()) } throws IllegalStateException("database closed")
        val viewModel = viewModel()
        viewModel.onTextChange("Keep me")
        viewModel.onNotesChange("and my notes")

        viewModel.save(addAnother = true)

        val state = viewModel.uiState.value
        assertEquals(ShareError.FAILED, state.error)
        assertEquals("Keep me", state.text)
        assertEquals("and my notes", state.notes)
        assertEquals(0, state.savedAndCleared)
        assertFalse(state.saving)
        assertTrue(state.canSave)
    }

    @Test
    fun `the sheet is cleared only once the queue has the capture`() {
        val gate = CompletableDeferred<UUID>()
        coEvery { queue.capture(any(), any(), any()) } coAnswers { gate.await() }
        val viewModel = viewModel()
        viewModel.onTextChange("Slow storage")

        viewModel.save(addAnother = true)
        assertTrue(viewModel.uiState.value.saving)
        assertEquals("Slow storage", viewModel.uiState.value.text)
        viewModel.save(addAnother = true)
        gate.complete(captureId)

        assertEquals(1, viewModel.uiState.value.savedAndCleared)
        coVerify(exactly = 1) { queue.capture(any(), any(), any()) }
    }

    @Test
    fun `a read-later bookmark saved with add another clears the sheet and says where it went`() {
        val viewModel = viewModel()
        viewModel.load(SharedContent("Tulip guide", null, emptyList(), url = "https://example.org/tulips"))
        viewModel.onDestinationChange(ShareDestination.READ_LATER)

        viewModel.save(addAnother = true)

        coVerify(exactly = 1) { queue.readLater("https://example.org/tulips", "Tulip guide") }
        val state = viewModel.uiState.value
        assertEquals(1, state.savedAndCleared)
        assertTrue(state.lastSavedReadLater)
        assertNull(state.url)
        assertFalse(state.offersReadLater)
    }

    @Test
    fun `an Inbox capture saved with add another is not reported as read later`() {
        val viewModel = viewModel()
        viewModel.load(SharedContent("Tulip guide", null, emptyList(), url = "https://example.org/tulips"))

        viewModel.save(addAnother = true)

        assertFalse(viewModel.uiState.value.lastSavedReadLater)
        coVerify(exactly = 1) { queue.capture("Tulip guide", "", emptyList()) }
    }

    // Picked attachments

    @Test
    fun `picked files are named after the provider and fill a blank title`() {
        val scan = TestFilesProvider.share("scan.pdf", "scan")
        val viewModel = viewModel()

        val state = picked(viewModel, listOf(scan))

        assertEquals(listOf(SharedFile(scan, "scan.pdf")), state.files)
        assertEquals("scan.pdf", state.text)
        assertTrue(state.canSave)
    }

    @Test
    fun `picked files do not replace a typed title`() {
        val viewModel = viewModel()
        viewModel.onTextChange("Receipts for taxes")

        val state = picked(viewModel, listOf(TestFilesProvider.share("r1.jpg", "r1"), TestFilesProvider.share("r2.jpg", "r2")))

        assertEquals("Receipts for taxes", state.text)
        assertEquals(listOf("r1.jpg", "r2.jpg"), state.files.map { it.name })
    }

    @Test
    fun `picked files are added after the attached ones and numbered after them when unnamed`() {
        val viewModel = viewModel()
        picked(viewModel, listOf(TestFilesProvider.share("a.jpg", "a")))

        val state = picked(viewModel, listOf(unnamed(7)))

        assertEquals(listOf("a.jpg", "attachment-2"), state.files.map { it.name })
    }

    @Test
    fun `a file picked twice or already attached is attached once`() {
        val a = TestFilesProvider.share("a.jpg", "a")
        val b = TestFilesProvider.share("b.jpg", "b")
        val viewModel = viewModel()
        picked(viewModel, listOf(a))

        val state = picked(viewModel, listOf(b, a, b))

        assertEquals(listOf(a, b), state.files.map { it.uri })
    }

    @Test
    fun `an empty pick changes nothing`() {
        val viewModel = viewModel()
        viewModel.onTextChange("Nothing picked")
        val before = viewModel.uiState.value

        viewModel.addFiles(emptyList())

        assertEquals(before, viewModel.uiState.value)
    }

    @Test
    fun `files cannot be added while saving or after the capture is queued`() {
        val gate = CompletableDeferred<UUID>()
        coEvery { queue.capture(any(), any(), any()) } coAnswers { gate.await() }
        val viewModel = viewModel()
        viewModel.onTextChange("Saving")
        viewModel.save()

        viewModel.addFiles(listOf(TestFilesProvider.share("late.jpg", "late")))
        assertTrue(
            viewModel.uiState.value.files
                .isEmpty(),
        )
        assertFalse(viewModel.uiState.value.loadingFiles)
        gate.complete(captureId)
        assertTrue(viewModel.uiState.value.saved)
        viewModel.addFiles(listOf(TestFilesProvider.share("later.jpg", "later")))

        assertTrue(
            viewModel.uiState.value.files
                .isEmpty(),
        )
        coVerify(exactly = 1) { queue.capture("Saving", "", emptyList()) }
    }

    @Test
    fun `picked and shared files are queued together`() {
        val shared = TestFilesProvider.share("shared.jpg", "s")
        val pick = TestFilesProvider.share("picked.pdf", "p")
        val viewModel = viewModel()
        viewModel.load(SharedContent("Both", null, listOf(shared)))
        viewModel.awaitFiles()
        picked(viewModel, listOf(pick))

        viewModel.save()

        assertTrue(viewModel.uiState.value.saved)
        coVerify(exactly = 1) {
            queue.capture("Both", "", listOf(CaptureAttachment(shared, "shared.jpg"), CaptureAttachment(pick, "picked.pdf")))
        }
    }

    @Test
    fun `save waits until every file load is done, also when a later one finishes first`() {
        Robolectric.setupContentProvider(ShareViewModelTest.GatedProvider::class.java, ShareViewModelTest.GatedProvider.AUTHORITY)
        ShareViewModelTest.GatedProvider.gate = CountDownLatch(1)
        val pick = TestFilesProvider.share("picked.pdf", "p")
        val viewModel = viewModel()

        viewModel.load(SharedContent("Mixed", null, listOf(gated("slow.jpg"))))
        viewModel.addFiles(listOf(pick))
        val during = viewModel.await { s -> s.files.any { it.uri == pick } }

        assertTrue("the share's names are still loading", during.loadingFiles)
        assertFalse(during.canSave)
        viewModel.save()
        coVerify(exactly = 0) { queue.capture(any(), any(), any()) }
        ShareViewModelTest.GatedProvider.gate.countDown()
        val after = viewModel.awaitFiles()

        assertEquals(listOf("slow.jpg", "picked.pdf"), after.files.map { it.name })
        assertTrue(after.canSave)
    }

    @Test
    fun `picking past the attachment limit shows the error and save refuses until one is removed`() {
        val viewModel = viewModel()
        viewModel.onTextChange("Many photos")
        picked(viewModel, (1..CaptureQueue.MAX_ATTACHMENTS).map(::unnamed))
        assertNull(viewModel.uiState.value.error)

        val over = picked(viewModel, listOf(unnamed(CaptureQueue.MAX_ATTACHMENTS + 1)))

        assertEquals(ShareError.TOO_MANY_FILES, over.error)
        viewModel.save()
        assertFalse(viewModel.uiState.value.saved)
        coVerify(exactly = 0) { queue.capture(any(), any(), any()) }

        viewModel.removeFile(unnamed(1))

        assertNull(viewModel.uiState.value.error)
        assertEquals(CaptureQueue.MAX_ATTACHMENTS, viewModel.uiState.value.files.size)
        viewModel.save()
        assertTrue(viewModel.uiState.value.saved)
        coVerify(exactly = 1) { queue.capture("Many photos", "", match { it.size == CaptureQueue.MAX_ATTACHMENTS }) }
    }

    @Test
    fun `removing one file while still over the limit keeps the error`() {
        val viewModel = viewModel()
        viewModel.onTextChange("Far too many")
        picked(viewModel, (1..CaptureQueue.MAX_ATTACHMENTS + 2).map(::unnamed))

        viewModel.removeFile(unnamed(1))

        assertEquals(ShareError.TOO_MANY_FILES, viewModel.uiState.value.error)
        assertEquals(CaptureQueue.MAX_ATTACHMENTS + 1, viewModel.uiState.value.files.size)
    }

    @Test
    fun `remove takes out exactly that file and keeps another error`() {
        val a = TestFilesProvider.share("a.jpg", "a")
        val b = TestFilesProvider.share("b.jpg", "b")
        val viewModel = viewModel()
        viewModel.onTextChange("x".repeat(3))
        viewModel.onNotesChange("n".repeat(ShareViewModel.MAX_NOTES_LENGTH + 1))
        picked(viewModel, listOf(a, b))
        viewModel.save()
        assertEquals(ShareError.NOTES_TOO_LONG, viewModel.uiState.value.error)

        viewModel.removeFile(a)

        assertEquals(listOf(SharedFile(b, "b.jpg")), viewModel.uiState.value.files)
        assertEquals(ShareError.NOTES_TOO_LONG, viewModel.uiState.value.error)
    }

    @Test
    fun `removing a file that is not attached changes nothing`() {
        val viewModel = viewModel()
        picked(viewModel, listOf(TestFilesProvider.share("a.jpg", "a")))
        val before = viewModel.uiState.value

        viewModel.removeFile(unnamed(99))

        assertEquals(before, viewModel.uiState.value)
    }

    @Test
    fun `a removed file is not queued`() {
        val keep = TestFilesProvider.share("keep.jpg", "k")
        val drop = TestFilesProvider.share("drop.jpg", "d")
        val viewModel = viewModel()
        viewModel.onTextChange("Only one")
        picked(viewModel, listOf(keep, drop))

        viewModel.removeFile(drop)
        viewModel.save()

        coVerify(exactly = 1) { queue.capture("Only one", "", listOf(CaptureAttachment(keep, "keep.jpg"))) }
    }

    @Test
    fun `files cannot be removed while saving`() {
        val gate = CompletableDeferred<UUID>()
        coEvery { queue.capture(any(), any(), any()) } coAnswers { gate.await() }
        val a = TestFilesProvider.share("a.jpg", "a")
        val viewModel = viewModel()
        viewModel.onTextChange("Saving")
        picked(viewModel, listOf(a))
        viewModel.save()

        viewModel.removeFile(a)

        assertEquals(
            listOf(a),
            viewModel.uiState.value.files
                .map { it.uri },
        )
        gate.complete(captureId)
        coVerify(exactly = 1) { queue.capture("Saving", "", listOf(CaptureAttachment(a, "a.jpg"))) }
    }

    @Test
    fun `attaching files to a shared page turns a read-later choice into an Inbox capture`() {
        val pdf = TestFilesProvider.share("tulips.pdf", "t")
        val viewModel = viewModel()
        viewModel.load(SharedContent("Tulip guide", "https://example.org/tulips", emptyList(), url = "https://example.org/tulips"))
        viewModel.onDestinationChange(ShareDestination.READ_LATER)
        assertTrue(viewModel.uiState.value.readLater)

        val state = picked(viewModel, listOf(pdf))

        assertFalse(state.offersReadLater)
        assertFalse(state.readLater)
        viewModel.save()
        coVerify(exactly = 0) { queue.readLater(any(), any()) }
        coVerify(exactly = 1) {
            queue.capture("Tulip guide", "https://example.org/tulips", listOf(CaptureAttachment(pdf, "tulips.pdf")))
        }
    }

    @Test
    fun `removing the last file offers read later again`() {
        val pdf = TestFilesProvider.share("tulips.pdf", "t")
        val viewModel = viewModel()
        viewModel.load(SharedContent("Tulip guide", null, emptyList(), url = "https://example.org/tulips"))
        picked(viewModel, listOf(pdf))
        assertFalse(viewModel.uiState.value.offersReadLater)

        viewModel.removeFile(pdf)

        assertTrue(viewModel.uiState.value.offersReadLater)
    }

    // With the real offline queue

    @Test
    fun `picked files are copied into the offline queue on save and survive the source going away`() {
        val server = MockWebServer().apply { start() }
        QueueHarness(server).use { harness ->
            // Offline: nothing may reach the network while capturing.
            server.close()
            val keep = TestFilesProvider.share("receipt.jpg", "receipt")
            val drop = TestFilesProvider.share("wrong.jpg", "wrong")
            val viewModel = viewModel(harness.queue)
            viewModel.onTextChange("Expense report @home")
            picked(viewModel, listOf(keep, drop))
            viewModel.removeFile(drop)

            viewModel.save(addAnother = true)
            viewModel.await { it.savedAndCleared == 1 }
            TestFilesProvider.dir.deleteRecursively()

            val pending = runBlocking { harness.dao.sendable() }.single()
            assertEquals("Expense report @home", pending.capture.text)
            val attachment = pending.attachments.single()
            assertEquals("receipt.jpg", attachment.name)
            assertEquals("receipt", File(attachment.localPath).readText())
            assertTrue(File(attachment.localPath).startsWith(harness.root))
            assertEquals(1, harness.scheduler.scheduled)
        }
    }

    @Test
    fun `an unreadable picked file is reported and nothing is queued offline`() {
        val server = MockWebServer().apply { start() }
        QueueHarness(server).use { harness ->
            server.close()
            val viewModel = viewModel(harness.queue)
            viewModel.onTextChange("Lost file")
            picked(viewModel, listOf(unnamed(1)))

            viewModel.save(addAnother = true)
            val state = viewModel.await { !it.saving }

            assertEquals(ShareError.UNREADABLE_FILE, state.error)
            assertEquals(0, state.savedAndCleared)
            assertEquals(listOf("attachment-1"), state.files.map { it.name })
            assertTrue(runBlocking { harness.dao.sendable() }.isEmpty())
        }
    }
}
