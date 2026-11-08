package ch.sonensei.cockpit.mail.read

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.sonensei.cockpit.files.DriveEntry
import ch.sonensei.cockpit.files.FilesRepository
import ch.sonensei.cockpit.files.FilesResult
import ch.sonensei.cockpit.files.FolderCrumb
import ch.sonensei.cockpit.mail.list.MailResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.UUID

/** The attachment actions of the read view: open, preview, save to device, and "Save to Files" with its folder picker. */
@OptIn(ExperimentalCoroutinesApi::class)
class MailAttachmentsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<MailAttachmentRepository>()
    private val files = mockk<FilesRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val pdf = MailAttachment("2", "report.pdf", 2048, "application/pdf")
    private val photo = MailAttachment("3", "photo.jpg", 4096, "image/jpeg")
    private val zip = MailAttachment("4", "archive.zip", 10_000, "application/zip")

    private fun folder(
        n: Int,
        name: String,
    ) = DriveEntry.Folder(id(n), name, null)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(message: UUID? = id(1)): MailAttachmentsViewModel =
        MailAttachmentsViewModel(repository, files, dispatcher).also {
            it.show(message)
            advanceUntilIdle()
        }

    private val MailAttachmentsViewModel.state get() = uiState.value

    /** A download that writes [content] into what its target opens. */
    private fun downloads(
        content: String = "%PDF-1.4",
        partId: String = pdf.partId,
    ) = coEvery { repository.download(id(1), partId, any()) } coAnswers {
        val out = thirdArg<() -> OutputStream?>()() ?: return@coAnswers MailResult.Failed(null)
        out.use { it.write(content.toByteArray()) }
        MailResult.Ok(content.length.toLong())
    }

    /** A download that opens its target (so a cache file exists) and then fails with [result]. */
    private fun downloadFails(result: MailResult<Long>) =
        coEvery { repository.download(id(1), any(), any()) } coAnswers {
            thirdArg<() -> OutputStream?>()()?.close()
            result
        }

    // Open

    @Test
    fun `open downloads into the cache file and asks the screen to open it with the mail's type`() =
        runTest(dispatcher) {
            downloads()
            val vm = viewModel()
            val cache = File(tmp.root, "report.pdf")

            vm.open(pdf) { cache }
            assertEquals("2", vm.state.working)
            advanceUntilIdle()

            assertEquals(OpenAttachment(cache, "application/pdf"), vm.opens.first())
            assertEquals("%PDF-1.4", cache.readText())
            assertNull(vm.state.working)
            assertNull(vm.state.notice)
        }

    @Test
    fun `a failed open says so and removes the partial cache file`() =
        runTest(dispatcher) {
            downloadFails(MailResult.Failed(404))
            val vm = viewModel()
            val cache = File(tmp.root, "report.pdf")

            vm.open(pdf) { cache }
            advanceUntilIdle()

            assertEquals(AttachmentNotice.Failed(AttachmentAction.OPEN), vm.state.notice)
            assertFalse(cache.exists())
            assertNull(vm.state.working)
            assertNull(withTimeoutOrNull(1) { vm.opens.first() })
        }

    @Test
    fun `an open without a connection is reported as offline`() =
        runTest(dispatcher) {
            downloadFails(MailResult.Offline)
            val vm = viewModel()

            vm.open(pdf) { File(tmp.root, "report.pdf") }
            advanceUntilIdle()

            assertEquals(AttachmentNotice.Failed(AttachmentAction.OPEN, offline = true), vm.state.notice)
        }

    @Test
    fun `a cache file that cannot be created fails the open`() =
        runTest(dispatcher) {
            downloads()
            val vm = viewModel()

            vm.open(pdf) { File(tmp.root, "missing-dir/report.pdf") }
            advanceUntilIdle()

            assertEquals(AttachmentNotice.Failed(AttachmentAction.OPEN), vm.state.notice)
        }

    @Test
    fun `no app for the attachment is a notice the user can dismiss`() =
        runTest(dispatcher) {
            val vm = viewModel()

            vm.noApp()
            assertEquals(AttachmentNotice.NoApp, vm.state.notice)

            vm.dismissNotice()
            assertNull(vm.state.notice)
        }

    @Test
    fun `nothing is opened without a mail`() =
        runTest(dispatcher) {
            val vm = viewModel(message = null)

            vm.open(pdf) { File(tmp.root, "report.pdf") }
            vm.preview(pdf) { File(tmp.root, "report.pdf") }
            advanceUntilIdle()

            assertNull(vm.state.working)
            coVerify(exactly = 0) { repository.download(any(), any(), any()) }
        }

    // Preview

    @Test
    fun `a PDF preview downloads it and shows it in the app`() =
        runTest(dispatcher) {
            downloads()
            val vm = viewModel()
            val cache = File(tmp.root, "report.pdf")

            vm.preview(pdf) { cache }
            advanceUntilIdle()

            assertEquals(AttachmentPreview(pdf, cache, AttachmentPreviewKind.PDF), vm.state.preview)
            assertNull(vm.state.working)

            vm.closePreview()
            assertNull(vm.state.preview)
        }

    @Test
    fun `an image preview is shown as an image`() =
        runTest(dispatcher) {
            downloads(partId = photo.partId)
            val vm = viewModel()
            val cache = File(tmp.root, "photo.jpg")

            vm.preview(photo) { cache }
            advanceUntilIdle()

            assertEquals(AttachmentPreview(photo, cache, AttachmentPreviewKind.IMAGE), vm.state.preview)
        }

    @Test
    fun `an attachment the app cannot show is not downloaded for a preview`() =
        runTest(dispatcher) {
            val vm = viewModel()

            vm.preview(zip) { File(tmp.root, "archive.zip") }
            advanceUntilIdle()

            assertNull(vm.state.preview)
            assertNull(vm.state.working)
            coVerify(exactly = 0) { repository.download(any(), any(), any()) }
        }

    @Test
    fun `a failed preview says so and shows nothing`() =
        runTest(dispatcher) {
            downloadFails(MailResult.Failed(500))
            val vm = viewModel()

            vm.preview(pdf) { File(tmp.root, "report.pdf") }
            advanceUntilIdle()

            assertNull(vm.state.preview)
            assertEquals(AttachmentNotice.Failed(AttachmentAction.PREVIEW), vm.state.notice)
        }

    @Test
    fun `preview kinds follow the content type, then the file name for a generic or missing type`() {
        fun kind(
            name: String,
            type: String?,
        ) = previewKind(MailAttachment("1", name, null, type))

        assertEquals(AttachmentPreviewKind.IMAGE, kind("a.bin", "image/png"))
        assertEquals(AttachmentPreviewKind.IMAGE, kind("a", "IMAGE/JPEG; name=a.jpg"))
        assertEquals(AttachmentPreviewKind.IMAGE, kind("a", "image/webp"))
        assertEquals(AttachmentPreviewKind.PDF, kind("a", "application/pdf"))
        assertNull(kind("photo.jpg", "image/heic"))
        assertNull(kind("report.pdf", "text/plain"))
        assertEquals(AttachmentPreviewKind.PDF, kind("Report.PDF", "application/octet-stream"))
        assertEquals(AttachmentPreviewKind.IMAGE, kind("photo.JPEG", null))
        assertEquals(AttachmentPreviewKind.IMAGE, kind("scan.gif", null))
        assertNull(kind("notes.txt", null))
        assertNull(kind("README", null))
    }

    @Test
    fun `the cache file is chosen for the mail, so two mails never share one`() =
        runTest(dispatcher) {
            downloads()
            val vm = viewModel()
            var mail: UUID? = null

            vm.open(pdf) {
                mail = it
                File(tmp.root, "report.pdf")
            }
            advanceUntilIdle()

            assertEquals(id(1), mail)
        }

    @Test
    fun `open from the preview hands over the file already downloaded, without downloading again`() =
        runTest(dispatcher) {
            downloads()
            val vm = viewModel()
            val cache = File(tmp.root, "report.pdf")
            vm.preview(pdf) { cache }
            advanceUntilIdle()

            vm.openPreviewed()
            advanceUntilIdle()

            assertEquals(OpenAttachment(cache, "application/pdf"), vm.opens.first())
            assertNull(vm.state.preview)
            coVerify(exactly = 1) { repository.download(any(), any(), any()) }
        }

    @Test
    fun `open from the preview without a preview does nothing`() =
        runTest(dispatcher) {
            val vm = viewModel()

            vm.openPreviewed()

            assertNull(withTimeoutOrNull(1) { vm.opens.first() })
        }

    // Save to device

    @Test
    fun `save to device writes the attachment where the user chose and confirms it`() =
        runTest(dispatcher) {
            downloads()
            val vm = viewModel()
            val out = ByteArrayOutputStream()
            var discarded = false

            vm.save(pdf, { out }) { discarded = true }
            assertEquals("2", vm.state.working)
            advanceUntilIdle()

            assertEquals("%PDF-1.4", out.toString())
            assertEquals(AttachmentNotice.SavedToDevice("report.pdf"), vm.state.notice)
            assertNull(vm.state.working)
            assertFalse(discarded)
        }

    @Test
    fun `a failed save to device discards the chosen document`() =
        runTest(dispatcher) {
            downloadFails(MailResult.Failed(null))
            val vm = viewModel()
            var discarded = false

            vm.save(pdf, { ByteArrayOutputStream() }) { discarded = true }
            advanceUntilIdle()

            assertTrue(discarded)
            assertEquals(AttachmentNotice.Failed(AttachmentAction.SAVE), vm.state.notice)
        }

    @Test
    fun `a save to device without a connection is offline and discarded`() =
        runTest(dispatcher) {
            downloadFails(MailResult.Offline)
            val vm = viewModel()
            var discarded = false

            vm.save(pdf, { ByteArrayOutputStream() }) { discarded = true }
            advanceUntilIdle()

            assertTrue(discarded)
            assertEquals(AttachmentNotice.Failed(AttachmentAction.SAVE, offline = true), vm.state.notice)
        }

    @Test
    fun `a save to device without a mail discards the document and downloads nothing`() =
        runTest(dispatcher) {
            val vm = viewModel(message = null)
            var discarded = false

            vm.save(pdf, { ByteArrayOutputStream() }) { discarded = true }
            advanceUntilIdle()

            assertTrue(discarded)
            coVerify(exactly = 0) { repository.download(any(), any(), any()) }
            assertEquals(AttachmentNotice.Failed(AttachmentAction.SAVE), vm.state.notice)
        }

    @Test
    fun `a save answer that arrives before its mail is shown waits for that mail, then saves`() =
        runTest(dispatcher) {
            downloads()
            val vm = viewModel(message = null)
            val out = ByteArrayOutputStream()
            var discarded = false

            vm.save(pdf, { out }, id(1)) { discarded = true }
            advanceUntilIdle()
            coVerify(exactly = 0) { repository.download(any(), any(), any()) }

            vm.show(id(1))
            advanceUntilIdle()

            assertEquals("%PDF-1.4", out.toString())
            assertEquals(AttachmentNotice.SavedToDevice("report.pdf"), vm.state.notice)
            assertFalse(discarded)
        }

    @Test
    fun `a waiting save is discarded and says so when another mail is shown`() =
        runTest(dispatcher) {
            val vm = viewModel(message = null)
            var discarded = false

            vm.save(pdf, { ByteArrayOutputStream() }, id(1)) { discarded = true }
            vm.show(id(2))
            advanceUntilIdle()

            assertTrue(discarded)
            assertEquals(AttachmentNotice.Failed(AttachmentAction.SAVE), vm.state.notice)
            coVerify(exactly = 0) { repository.download(any(), any(), any()) }
        }

    @Test
    fun `a save for another mail than the one shown is discarded and says so`() =
        runTest(dispatcher) {
            val vm = viewModel(message = id(2))
            var discarded = false

            vm.save(pdf, { ByteArrayOutputStream() }, id(1)) { discarded = true }
            advanceUntilIdle()

            assertTrue(discarded)
            assertEquals(AttachmentNotice.Failed(AttachmentAction.SAVE), vm.state.notice)
            coVerify(exactly = 0) { repository.download(any(), any(), any()) }
        }

    @Test
    fun `a second save answer before its mail is shown replaces the first, whose document is discarded`() =
        runTest(dispatcher) {
            downloads(partId = photo.partId, content = "JFIF")
            val vm = viewModel(message = null)
            val first = ByteArrayOutputStream()
            val second = ByteArrayOutputStream()
            var firstDiscarded = false
            var secondDiscarded = false

            vm.save(pdf, { first }, id(1)) { firstDiscarded = true }
            vm.save(photo, { second }, id(1)) { secondDiscarded = true }
            advanceUntilIdle()
            assertTrue("the replaced save left its empty document behind", firstDiscarded)

            vm.show(id(1))
            advanceUntilIdle()

            assertEquals("", first.toString())
            assertEquals("JFIF", second.toString())
            assertEquals(AttachmentNotice.SavedToDevice("photo.jpg"), vm.state.notice)
            assertFalse(secondDiscarded)
            coVerify(exactly = 0) { repository.download(any(), pdf.partId, any()) }
        }

    @Test
    fun `a save still waiting for its mail when the screen goes away discards its document`() =
        runTest(dispatcher) {
            val store = ViewModelStore()
            val factory = viewModelFactory { initializer { MailAttachmentsViewModel(repository, files, dispatcher) } }
            val vm = ViewModelProvider(store, factory)[MailAttachmentsViewModel::class.java]
            var discarded = 0

            vm.save(pdf, { ByteArrayOutputStream() }, id(1)) { discarded++ }
            advanceUntilIdle()
            assertEquals(0, discarded)

            store.clear()
            advanceUntilIdle()

            assertEquals("the waiting save left its empty document behind", 1, discarded)
            coVerify(exactly = 0) { repository.download(any(), any(), any()) }
        }

    @Test
    fun `a screen that goes away without a waiting save discards nothing`() =
        runTest(dispatcher) {
            downloads()
            val store = ViewModelStore()
            val factory = viewModelFactory { initializer { MailAttachmentsViewModel(repository, files, dispatcher) } }
            val vm = ViewModelProvider(store, factory)[MailAttachmentsViewModel::class.java]
            vm.show(id(1))
            var discarded = 0
            vm.save(pdf, { ByteArrayOutputStream() }) { discarded++ }
            advanceUntilIdle()

            store.clear()
            advanceUntilIdle()

            assertEquals(AttachmentNotice.SavedToDevice("report.pdf"), vm.state.notice)
            assertEquals("a saved document must stay", 0, discarded)
        }

    // One action at a time

    @Test
    fun `while one attachment downloads, other actions wait and an ignored save is discarded`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<MailResult<Long>>()
            coEvery { repository.download(id(1), pdf.partId, any()) } coAnswers {
                thirdArg<() -> OutputStream?>()()?.close()
                gate.await()
            }
            val vm = viewModel()

            vm.open(pdf) { File(tmp.root, "report.pdf") }
            advanceUntilIdle()
            assertEquals("2", vm.state.working)

            var discarded = false
            vm.open(photo) { File(tmp.root, "photo.jpg") }
            vm.preview(photo) { File(tmp.root, "photo.jpg") }
            vm.save(photo, { ByteArrayOutputStream() }) { discarded = true }
            vm.pickFolder(photo)
            advanceUntilIdle()

            assertTrue(discarded)
            assertEquals("2", vm.state.working)
            assertNull(vm.state.picker)
            coVerify(exactly = 1) { repository.download(any(), any(), any()) }

            gate.complete(MailResult.Ok(8))
            advanceUntilIdle()
            assertNull(vm.state.working)
        }

    @Test
    fun `a new action clears the previous notice and the preview`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<MailResult<Long>>()
            downloads()
            val vm = viewModel()
            vm.preview(pdf) { File(tmp.root, "report.pdf") }
            advanceUntilIdle()
            vm.noApp()
            coEvery { repository.download(id(1), pdf.partId, any()) } coAnswers { gate.await() }

            vm.save(pdf, { ByteArrayOutputStream() })

            assertNull(vm.state.notice)
            assertNull(vm.state.preview)
            assertEquals("2", vm.state.working)
            gate.complete(MailResult.Ok(1))
            advanceUntilIdle()
        }

    // show()

    @Test
    fun `showing another mail drops the running download, its notice, preview and picker`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<MailResult<Long>>()
            coEvery { repository.download(id(1), pdf.partId, any()) } coAnswers { gate.await() }
            val vm = viewModel()
            var discarded = false
            vm.save(pdf, { ByteArrayOutputStream() }) { discarded = true }
            advanceUntilIdle()

            vm.show(id(2))
            advanceUntilIdle()

            assertEquals(MailAttachmentsUiState(), vm.state)
            assertTrue("a cancelled save leaves no empty document", discarded)
            gate.complete(MailResult.Ok(8))
            advanceUntilIdle()
            assertNull(vm.state.notice)
        }

    @Test
    fun `after a cancelled download the next mail's attachments can be used at once`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<MailResult<Long>>()
            coEvery { repository.download(id(1), any(), any()) } coAnswers { gate.await() }
            coEvery { files.list(null) } returns FilesResult.Ok(emptyList())
            val vm = viewModel()
            vm.open(pdf) { File(tmp.root, "report.pdf") }
            advanceUntilIdle()

            vm.show(id(2))
            vm.pickFolder(photo)
            advanceUntilIdle()

            assertEquals(photo, vm.state.picker?.attachment)
        }

    @Test
    fun `showing another mail during an open removes the partial cache file and opens nothing`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<MailResult<Long>>()
            coEvery { repository.download(id(1), pdf.partId, any()) } coAnswers {
                thirdArg<() -> OutputStream?>()()!!.use { it.write("%PDF-1.4 half".toByteArray()) }
                gate.await()
            }
            val vm = viewModel()
            val cache = File(tmp.root, "report.pdf")
            vm.open(pdf) { cache }
            advanceUntilIdle()
            assertTrue(cache.exists())

            vm.show(id(2))
            advanceUntilIdle()
            gate.complete(MailResult.Ok(13))
            advanceUntilIdle()

            assertFalse("the half-downloaded file stays in the cache", cache.exists())
            assertNull(withTimeoutOrNull(1) { vm.opens.first() })
            assertEquals(MailAttachmentsUiState(), vm.state)
        }

    @Test
    fun `showing another mail during a save to Files drops its notice`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<MailResult<SavedAttachment>>()
            coEvery { files.list(null) } returns FilesResult.Ok(emptyList())
            coEvery { repository.saveToFiles(id(1), "2", null) } coAnswers { gate.await() }
            val vm = viewModel()
            vm.pickFolder(pdf)
            advanceUntilIdle()
            vm.saveToFiles()
            advanceUntilIdle()

            vm.show(id(2))
            gate.complete(MailResult.Ok(SavedAttachment(id(10), "report.pdf", id(50))))
            advanceUntilIdle()

            assertEquals(MailAttachmentsUiState(), vm.state)
        }

    @Test
    fun `save here while another attachment downloads stores nothing and keeps the picker`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<MailResult<Long>>()
            coEvery { files.list(null) } returns FilesResult.Ok(emptyList())
            coEvery { repository.download(id(1), photo.partId, any()) } coAnswers {
                thirdArg<() -> OutputStream?>()()?.close()
                gate.await()
            }
            val vm = viewModel()
            vm.pickFolder(pdf)
            advanceUntilIdle()
            vm.open(photo) { File(tmp.root, "photo.jpg") }
            advanceUntilIdle()

            vm.saveToFiles()
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.saveToFiles(any(), any(), any()) }
            assertEquals(pdf, vm.state.picker?.attachment)
            assertEquals("3", vm.state.working)
            gate.complete(MailResult.Ok(1))
            advanceUntilIdle()
        }

    @Test
    fun `type parameters, case and padding are ignored when choosing the preview kind`() {
        assertEquals(AttachmentPreviewKind.PDF, previewKind(MailAttachment("1", "x", null, "Application/PDF; name=x.pdf")))
        assertEquals(AttachmentPreviewKind.IMAGE, previewKind(MailAttachment("1", "x.bmp", null, " application/octet-stream ")))
        assertNull(previewKind(MailAttachment("1", "trailing-dot.", null, null)))
    }

    @Test
    fun `showing the same mail again keeps what is shown`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.noApp()

            vm.show(id(1))

            assertEquals(AttachmentNotice.NoApp, vm.state.notice)
        }

    @Test
    fun `showing no mail closes the picker`() =
        runTest(dispatcher) {
            coEvery { files.list(null) } returns FilesResult.Ok(listOf(folder(20, "Projects")))
            val vm = viewModel()
            vm.pickFolder(pdf)
            advanceUntilIdle()

            vm.show(null)

            assertNull(vm.state.picker)
        }

    // Folder picker and Save to Files

    @Test
    fun `the picker opens at the drive root with only its folders`() =
        runTest(dispatcher) {
            val file = DriveEntry.File(id(30), "budget.xlsx", "application/vnd.ms-excel", 1, java.time.Instant.EPOCH)
            coEvery { files.list(null) } coAnswers { FilesResult.Ok(listOf(folder(20, "Projects"), file, folder(21, "Taxes"))) }
            val vm = viewModel()
            vm.noApp()

            vm.pickFolder(pdf)
            assertTrue(vm.state.picker!!.loading)
            assertNull("opening the picker clears the notice", vm.state.notice)
            advanceUntilIdle()

            val picker = vm.state.picker!!
            assertEquals(pdf, picker.attachment)
            assertEquals(emptyList<FolderCrumb>(), picker.path)
            assertEquals(listOf(folder(20, "Projects"), folder(21, "Taxes")), picker.folders)
            assertFalse(picker.loading)
            assertFalse(picker.failed)
        }

    @Test
    fun `the picker walks into folders and back along its path`() =
        runTest(dispatcher) {
            coEvery { files.list(null) } returns FilesResult.Ok(listOf(folder(20, "Projects")))
            coEvery { files.list(id(20)) } returns FilesResult.Ok(listOf(folder(22, "2026")))
            coEvery { files.list(id(22)) } returns FilesResult.Ok(emptyList())
            val vm = viewModel()
            vm.pickFolder(pdf)
            advanceUntilIdle()

            vm.openFolder(folder(20, "Projects"))
            advanceUntilIdle()
            vm.openFolder(folder(22, "2026"))
            advanceUntilIdle()
            assertEquals(listOf(FolderCrumb(id(20), "Projects"), FolderCrumb(id(22), "2026")), vm.state.picker!!.path)
            assertEquals(emptyList<DriveEntry.Folder>(), vm.state.picker!!.folders)

            vm.goToFolder(1)
            advanceUntilIdle()
            assertEquals(listOf(FolderCrumb(id(20), "Projects")), vm.state.picker!!.path)
            assertEquals(listOf(folder(22, "2026")), vm.state.picker!!.folders)

            vm.goToFolder(-3)
            advanceUntilIdle()
            assertEquals(emptyList<FolderCrumb>(), vm.state.picker!!.path)
            assertEquals(listOf(folder(20, "Projects")), vm.state.picker!!.folders)
        }

    @Test
    fun `a folder that cannot be listed is marked failed and can be left`() =
        runTest(dispatcher) {
            coEvery { files.list(null) } returns FilesResult.Ok(listOf(folder(20, "Projects")))
            coEvery { files.list(id(20)) } returns FilesResult.Offline
            val vm = viewModel()
            vm.pickFolder(pdf)
            advanceUntilIdle()

            vm.openFolder(folder(20, "Projects"))
            advanceUntilIdle()
            assertTrue(vm.state.picker!!.failed)
            assertFalse(vm.state.picker!!.loading)

            vm.goToFolder(0)
            advanceUntilIdle()
            assertFalse(vm.state.picker!!.failed)
            assertEquals(listOf(folder(20, "Projects")), vm.state.picker!!.folders)
        }

    @Test
    fun `a slow listing of a folder already left does not replace the folder shown`() =
        runTest(dispatcher) {
            val slow = CompletableDeferred<FilesResult<List<DriveEntry>>>()
            coEvery { files.list(null) } returns FilesResult.Ok(listOf(folder(20, "Projects")))
            coEvery { files.list(id(20)) } coAnswers { slow.await() }
            coEvery { files.list(id(21)) } returns FilesResult.Ok(listOf(folder(23, "Receipts")))
            val vm = viewModel()
            vm.pickFolder(pdf)
            advanceUntilIdle()

            vm.openFolder(folder(20, "Projects"))
            advanceUntilIdle()
            vm.goToFolder(0)
            vm.openFolder(folder(21, "Taxes"))
            advanceUntilIdle()
            slow.complete(FilesResult.Ok(listOf(folder(99, "Wrong"))))
            advanceUntilIdle()

            assertEquals(listOf(FolderCrumb(id(21), "Taxes")), vm.state.picker!!.path)
            assertEquals(listOf(folder(23, "Receipts")), vm.state.picker!!.folders)
        }

    @Test
    fun `closing the picker stops its listing`() =
        runTest(dispatcher) {
            val slow = CompletableDeferred<FilesResult<List<DriveEntry>>>()
            coEvery { files.list(null) } coAnswers { slow.await() }
            val vm = viewModel()
            vm.pickFolder(pdf)
            advanceUntilIdle()

            vm.closePicker()
            slow.complete(FilesResult.Ok(listOf(folder(20, "Projects"))))
            advanceUntilIdle()

            assertNull(vm.state.picker)
        }

    @Test
    fun `picker navigation without a picker does nothing`() =
        runTest(dispatcher) {
            val vm = viewModel()

            vm.openFolder(folder(20, "Projects"))
            vm.goToFolder(0)
            vm.saveToFiles()
            advanceUntilIdle()

            assertEquals(MailAttachmentsUiState(), vm.state)
            coVerify(exactly = 0) { files.list(any()) }
            coVerify(exactly = 0) { repository.saveToFiles(any(), any(), any()) }
        }

    @Test
    fun `the picker does not open without a mail`() =
        runTest(dispatcher) {
            val vm = viewModel(message = null)

            vm.pickFolder(pdf)
            advanceUntilIdle()

            assertNull(vm.state.picker)
        }

    @Test
    fun `saving at the drive root stores in the default attachments folder`() =
        runTest(dispatcher) {
            coEvery { files.list(null) } returns FilesResult.Ok(emptyList())
            coEvery { repository.saveToFiles(id(1), "2", null) } returns MailResult.Ok(SavedAttachment(id(10), "report.pdf", id(50)))
            val vm = viewModel()
            vm.pickFolder(pdf)
            advanceUntilIdle()

            vm.saveToFiles()
            assertNull(vm.state.picker)
            assertEquals("2", vm.state.working)
            advanceUntilIdle()

            assertEquals(AttachmentNotice.SavedToFiles("report.pdf"), vm.state.notice)
            assertNull(vm.state.working)
            coVerify(exactly = 1) { repository.saveToFiles(id(1), "2", null) }
        }

    @Test
    fun `saving in a picked folder names its path`() =
        runTest(dispatcher) {
            coEvery { files.list(any()) } returns FilesResult.Ok(emptyList())
            coEvery { repository.saveToFiles(id(1), "2", id(22)) } returns
                MailResult.Ok(SavedAttachment(id(10), "report (1).pdf", id(22)))
            val vm = viewModel()
            vm.pickFolder(pdf)
            vm.openFolder(folder(20, "Projects"))
            vm.openFolder(folder(22, "2026"))
            advanceUntilIdle()

            vm.saveToFiles()
            advanceUntilIdle()

            assertEquals(AttachmentNotice.SavedToFiles("report (1).pdf", "/Projects/2026"), vm.state.notice)
        }

    @Test
    fun `a failed move says the file stayed in the default folder`() =
        runTest(dispatcher) {
            coEvery { files.list(any()) } returns FilesResult.Ok(emptyList())
            coEvery { repository.saveToFiles(id(1), "2", id(20)) } returns MailResult.Ok(SavedAttachment(id(10), "report.pdf", id(50)))
            val vm = viewModel()
            vm.pickFolder(pdf)
            vm.openFolder(folder(20, "Projects"))
            advanceUntilIdle()

            vm.saveToFiles()
            advanceUntilIdle()

            assertEquals(AttachmentNotice.SavedToFiles("report.pdf", notMoved = "/Projects"), vm.state.notice)
        }

    @Test
    fun `a too large attachment is reported as such`() =
        runTest(dispatcher) {
            coEvery { files.list(null) } returns FilesResult.Ok(emptyList())
            coEvery { repository.saveToFiles(id(1), "2", null) } returns MailResult.Failed(413)
            val vm = viewModel()
            vm.pickFolder(pdf)
            advanceUntilIdle()

            vm.saveToFiles()
            advanceUntilIdle()

            assertEquals(AttachmentNotice.Failed(AttachmentAction.FILES, tooLarge = true), vm.state.notice)
        }

    @Test
    fun `save to files without a connection is offline`() =
        runTest(dispatcher) {
            coEvery { files.list(null) } returns FilesResult.Ok(emptyList())
            coEvery { repository.saveToFiles(id(1), "2", null) } returns MailResult.Offline
            val vm = viewModel()
            vm.pickFolder(pdf)
            advanceUntilIdle()

            vm.saveToFiles()
            advanceUntilIdle()

            assertEquals(AttachmentNotice.Failed(AttachmentAction.FILES, offline = true), vm.state.notice)
        }

    @Test
    fun `a refused save to files is a plain failure`() =
        runTest(dispatcher) {
            coEvery { files.list(null) } returns FilesResult.Ok(emptyList())
            coEvery { repository.saveToFiles(id(1), "2", null) } returns MailResult.Failed(404)
            val vm = viewModel()
            vm.pickFolder(pdf)
            advanceUntilIdle()

            vm.saveToFiles()
            advanceUntilIdle()

            assertEquals(AttachmentNotice.Failed(AttachmentAction.FILES), vm.state.notice)
        }

    @Test
    fun `save here pressed twice stores the attachment once`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<MailResult<SavedAttachment>>()
            coEvery { files.list(null) } returns FilesResult.Ok(emptyList())
            coEvery { repository.saveToFiles(id(1), "2", null) } coAnswers { gate.await() }
            val vm = viewModel()
            vm.pickFolder(pdf)
            advanceUntilIdle()

            vm.saveToFiles()
            vm.saveToFiles()
            advanceUntilIdle()
            gate.complete(MailResult.Ok(SavedAttachment(id(10), "report.pdf", id(50))))
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.saveToFiles(any(), any(), any()) }
        }
}
