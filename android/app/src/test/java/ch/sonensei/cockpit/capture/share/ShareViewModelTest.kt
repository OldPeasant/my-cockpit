package ch.sonensei.cockpit.capture.share

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.capture.queue.CaptureAttachment
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The sheet's state machine against a mocked queue; file names come from a stand-in provider of the sending app. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ShareViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val queue = mockk<CaptureQueue>()
    private val captureId = UUID.fromString("00000000-0000-7000-9000-000000000001")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        TestFilesProvider.dir = File(context.cacheDir, "shared-${System.nanoTime()}").apply { mkdirs() }
        Robolectric.setupContentProvider(TestFilesProvider::class.java, TestFilesProvider.AUTHORITY)
        coEvery { queue.capture(any(), any(), any()) } returns captureId
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        TestFilesProvider.dir.deleteRecursively()
    }

    private fun viewModel() = ShareViewModel(queue, context)

    /** A content URI the provider knows no name for (as if the sender's file is gone). */
    private fun unnamed(n: Int): Uri = Uri.parse("content://${TestFilesProvider.AUTHORITY}/missing-$n")

    private fun ShareViewModel.awaitFiles(): ShareUiState = runBlocking { withTimeout(5_000) { uiState.first { !it.loadingFiles } } }

    private fun loaded(content: SharedContent): ShareViewModel = viewModel().apply { load(content) }.also { it.awaitFiles() }

    @Test
    fun `load fills the sheet and names the files after the provider`() {
        val photo = TestFilesProvider.share("beach.jpg", "beach")
        val viewModel = viewModel()

        viewModel.load(SharedContent("Holiday", "https://example.org/holiday", listOf(photo)))
        val state = viewModel.awaitFiles()

        assertEquals("Holiday", state.text)
        assertEquals("https://example.org/holiday", state.notes)
        assertEquals(listOf(SharedFile(photo, "beach.jpg")), state.files)
        assertTrue(state.canSave)
    }

    @Test
    fun `a share without files is ready at once`() {
        val viewModel = viewModel()

        viewModel.load(SharedContent("Buy milk", null, emptyList()))

        val state = viewModel.uiState.value
        assertFalse(state.loadingFiles)
        assertEquals("", state.notes)
        assertTrue(state.canSave)
    }

    @Test
    fun `a second load keeps the user's edits`() {
        val viewModel = loaded(SharedContent("Original", "first notes", emptyList()))
        viewModel.onTextChange("Edited")
        viewModel.onNotesChange("edited notes")

        viewModel.load(SharedContent("Other share", "other notes", listOf(TestFilesProvider.share("x.png", "x"))))

        val state = viewModel.uiState.value
        assertEquals("Edited", state.text)
        assertEquals("edited notes", state.notes)
        assertTrue(state.files.isEmpty())
    }

    @Test
    fun `files without a display name get a numbered name`() {
        val named = TestFilesProvider.share("report.pdf", "pdf")

        val state = loaded(SharedContent("Docs", null, listOf(named, unnamed(2)))).uiState.value

        assertEquals(listOf("report.pdf", "attachment-2"), state.files.map { it.name })
    }

    @Test
    fun `a provider that fails, answers nothing or a blank name still gives every file a name`() {
        Robolectric.setupContentProvider(QuirkyProvider::class.java, QuirkyProvider.AUTHORITY)
        val uris = listOf("denied", "nothing", "blank").map { Uri.parse("content://${QuirkyProvider.AUTHORITY}/$it") }

        val state = loaded(SharedContent("Odd files", null, uris)).uiState.value

        assertEquals(listOf("attachment-1", "attachment-2", "attachment-3"), state.files.map { it.name })
        assertTrue(state.canSave)
    }

    @Test
    fun `a single file without text is titled with its name`() {
        val state = loaded(SharedContent("", null, listOf(TestFilesProvider.share("scan.pdf", "scan")))).uiState.value

        assertEquals("scan.pdf", state.text)
    }

    @Test
    fun `three files without text are all named in the title`() {
        val files = listOf("a.jpg", "b.jpg", "c.jpg").map { TestFilesProvider.share(it, it) }

        assertEquals("a.jpg, b.jpg, c.jpg", loaded(SharedContent("", null, files)).uiState.value.text)
    }

    @Test
    fun `more than three files without text name three and count the rest`() {
        val files = listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg").map { TestFilesProvider.share(it, it) }

        assertEquals("a.jpg, b.jpg, c.jpg +2", loaded(SharedContent("", null, files)).uiState.value.text)
    }

    @Test
    fun `shared text is not replaced by file names`() {
        val state = loaded(SharedContent("Receipts", null, listOf(TestFilesProvider.share("r.jpg", "r")))).uiState.value

        assertEquals("Receipts", state.text)
    }

    @Test
    fun `save while the file names load does nothing and shows no error`() {
        Robolectric.setupContentProvider(GatedProvider::class.java, GatedProvider.AUTHORITY)
        GatedProvider.gate = CountDownLatch(1)
        val viewModel = viewModel()

        viewModel.load(SharedContent("Photo", null, listOf(Uri.parse("content://${GatedProvider.AUTHORITY}/p.jpg"))))
        val during = viewModel.uiState.value
        assertTrue(during.loadingFiles)
        assertFalse(during.canSave)
        viewModel.save()
        assertNull(viewModel.uiState.value.error)
        GatedProvider.gate.countDown()
        val after = viewModel.awaitFiles()

        assertEquals(listOf("p.jpg"), after.files.map { it.name })
        assertTrue(after.canSave)
        coVerify(exactly = 0) { queue.capture(any(), any(), any()) }
    }

    /** A sender's provider that denies `denied`, returns no cursor for `nothing` and a blank name for anything else. */
    class QuirkyProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? =
            when (uri.lastPathSegment) {
                "denied" -> throw SecurityException("Permission denial")
                "nothing" -> null
                else -> MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(" ")) }
            }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        companion object {
            const val AUTHORITY = "ch.sonensei.cockpit.test.quirky"
        }
    }

    /** A sender whose provider answers the name query only once [gate] opens. */
    class GatedProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            gate.await(5, TimeUnit.SECONDS)
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(uri.lastPathSegment)) }
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        companion object {
            const val AUTHORITY = "ch.sonensei.cockpit.test.gated"

            @Volatile var gate = CountDownLatch(0)
        }
    }

    @Test
    fun `a blank title is not queued and shows an error until the user types`() {
        val viewModel = loaded(SharedContent("Something", null, emptyList()))
        viewModel.onTextChange("   ")

        viewModel.save()

        assertEquals(ShareError.EMPTY_TEXT, viewModel.uiState.value.error)
        coVerify(exactly = 0) { queue.capture(any(), any(), any()) }
        viewModel.onTextChange("Something else")
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `editing the notes clears an error`() {
        val viewModel = loaded(SharedContent("", null, emptyList()))
        viewModel.save()
        assertEquals(ShareError.EMPTY_TEXT, viewModel.uiState.value.error)

        viewModel.onNotesChange("a note")

        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `save queues the edited text, notes and the named files`() {
        val photo = TestFilesProvider.share("beach.jpg", "beach")
        val viewModel = loaded(SharedContent("Holiday", "https://example.org/holiday", listOf(photo, unnamed(2))))
        viewModel.onTextChange("Holiday @home")

        viewModel.save()

        assertTrue(viewModel.uiState.value.saved)
        assertFalse(viewModel.uiState.value.saving)
        assertFalse(viewModel.uiState.value.canSave)
        coVerify(exactly = 1) {
            queue.capture(
                "Holiday @home",
                "https://example.org/holiday",
                listOf(CaptureAttachment(photo, "beach.jpg"), CaptureAttachment(unnamed(2), "attachment-2")),
            )
        }
    }

    @Test
    fun `exactly the attachment limit is queued`() {
        val files = (1..CaptureQueue.MAX_ATTACHMENTS).map(::unnamed)
        val viewModel = loaded(SharedContent("Many", null, files))

        viewModel.save()

        assertTrue(viewModel.uiState.value.saved)
        coVerify(exactly = 1) { queue.capture("Many", "", match { it.size == CaptureQueue.MAX_ATTACHMENTS }) }
    }

    @Test
    fun `one file over the attachment limit is refused before queueing`() {
        val files = (1..CaptureQueue.MAX_ATTACHMENTS + 1).map(::unnamed)
        val viewModel = loaded(SharedContent("Too many", null, files))

        viewModel.save()

        val state = viewModel.uiState.value
        assertEquals(ShareError.TOO_MANY_FILES, state.error)
        assertFalse(state.saved)
        assertFalse(state.saving)
        coVerify(exactly = 0) { queue.capture(any(), any(), any()) }
    }

    @Test
    fun `notes at the server's limit are queued`() {
        val notes = "n".repeat(ShareViewModel.MAX_NOTES_LENGTH)
        val viewModel = loaded(SharedContent("Long article", notes, emptyList()))

        viewModel.save()

        assertTrue(viewModel.uiState.value.saved)
        coVerify(exactly = 1) { queue.capture("Long article", notes, emptyList()) }
    }

    @Test
    fun `notes over the server's limit are refused before queueing until shortened`() {
        val viewModel = loaded(SharedContent("Long article", "n".repeat(ShareViewModel.MAX_NOTES_LENGTH + 1), emptyList()))

        viewModel.save()

        val state = viewModel.uiState.value
        assertEquals(ShareError.NOTES_TOO_LONG, state.error)
        assertFalse(state.saved)
        assertFalse(state.saving)
        coVerify(exactly = 0) { queue.capture(any(), any(), any()) }
        viewModel.onNotesChange("n".repeat(ShareViewModel.MAX_NOTES_LENGTH))
        assertNull(viewModel.uiState.value.error)
        viewModel.save()
        assertTrue(viewModel.uiState.value.saved)
    }

    @Test
    fun `a text the queue rejects is reported as too long`() {
        coEvery { queue.capture(any(), any(), any()) } throws IllegalArgumentException("At most 100000 characters")
        val viewModel = loaded(SharedContent("x".repeat(CaptureQueue.MAX_TEXT_LENGTH + 1), null, emptyList()))

        viewModel.save()

        assertEquals(ShareError.TEXT_TOO_LONG, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.saved)
    }

    @Test
    fun `an unreadable file is reported and the save can be retried`() {
        coEvery { queue.capture(any(), any(), any()) } throws IOException("Cannot open attachment") andThen captureId
        val viewModel = loaded(SharedContent("Invoice", null, listOf(TestFilesProvider.share("i.pdf", "i"))))

        viewModel.save()

        val failed = viewModel.uiState.value
        assertEquals(ShareError.UNREADABLE_FILE, failed.error)
        assertFalse(failed.saving)
        assertFalse(failed.saved)
        assertTrue(failed.canSave)

        viewModel.save()

        assertTrue(viewModel.uiState.value.saved)
        assertNull(viewModel.uiState.value.error)
        coVerify(exactly = 2) { queue.capture(any(), any(), any()) }
    }

    @Test
    fun `an unexpected storage failure is reported as failed`() {
        coEvery { queue.capture(any(), any(), any()) } throws IllegalStateException("database closed")
        val viewModel = loaded(SharedContent("Note", null, emptyList()))

        viewModel.save()

        assertEquals(ShareError.FAILED, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.saved)
        assertFalse(viewModel.uiState.value.saving)
    }

    @Test
    fun `a second tap while saving queues only once`() {
        val gate = CompletableDeferred<UUID>()
        coEvery { queue.capture(any(), any(), any()) } coAnswers { gate.await() }
        val viewModel = loaded(SharedContent("Once", null, emptyList()))

        viewModel.save()
        assertTrue(viewModel.uiState.value.saving)
        assertFalse(viewModel.uiState.value.canSave)
        viewModel.save()
        assertNull(viewModel.uiState.value.error)
        gate.complete(captureId)

        assertTrue(viewModel.uiState.value.saved)
        coVerify(exactly = 1) { queue.capture(any(), any(), any()) }
    }

    @Test
    fun `save after the capture is queued does nothing`() {
        val viewModel = loaded(SharedContent("Done", null, emptyList()))
        viewModel.save()

        viewModel.save()

        assertTrue(viewModel.uiState.value.saved)
        assertNull(viewModel.uiState.value.error)
        coVerify(exactly = 1) { queue.capture(any(), any(), any()) }
    }
}
