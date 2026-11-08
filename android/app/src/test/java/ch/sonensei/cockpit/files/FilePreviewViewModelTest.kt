package ch.sonensei.cockpit.files

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

/** The in-app preview in the view model (SPEC.md §8.2): what each type shows, previous/next, failures and late answers. */
@OptIn(ExperimentalCoroutinesApi::class)
class FilePreviewViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<FilesRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private val docs = DriveEntry.Folder(id(1), "Documents", null)
    private val photo = DriveEntry.File(id(2), "Ferien.jpg", "image/jpeg", 4096, at)
    private val pdf = DriveEntry.File(id(3), "2025.pdf", "application/pdf", 2048, at)
    private val notes = DriveEntry.File(id(4), "notes.txt", "text/plain; charset=utf-8", 12, at)
    private val archive = DriveEntry.File(id(5), "backup.zip", "application/zip", 9000, at)

    /** The cache files the view model was given for PDFs, by file. */
    private val targets = mutableListOf<UUID>()

    private fun target(file: DriveEntry.File): File {
        targets += file.id
        return File(temp.root, "${file.id}.pdf")
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A view model showing the drive root with [entries]. */
    private fun TestScope.listed(vararg entries: DriveEntry): FilesViewModel {
        coEvery { repo.list(null) } returns FilesResult.Ok(entries.toList())
        val vm = FilesViewModel(repo, dispatcher)
        vm.refresh()
        advanceUntilIdle()
        return vm
    }

    /** The download writes [content] into whatever target the view model gave it, like the real repository does. */
    private fun downloadWrites(
        file: DriveEntry.File,
        content: String,
        result: FilesResult<Long> = FilesResult.Ok(content.length.toLong()),
    ) {
        coEvery { repo.download(file.id, any()) } coAnswers {
            val out = secondArg<() -> OutputStream?>()() ?: return@coAnswers FilesResult.Failed(null)
            out.use { it.write(content.toByteArray()) }
            result
        }
    }

    private fun FilesViewModel.content() = uiState.value.preview?.content

    @Test
    fun `an image is shown at once, loaded by the screen, among the files of the folder in the list's order`() =
        runTest(dispatcher) {
            val vm = listed(docs, photo, pdf, notes)

            vm.preview(photo, ::target)

            val preview = vm.uiState.value.preview!!
            assertEquals(PreviewContent.Image, preview.content)
            // Folders are not previewed: previous and next go through the files only.
            assertEquals(listOf(photo, pdf, notes), preview.files)
            assertEquals(0, preview.index)
            assertEquals(photo, preview.file)
            assertFalse(preview.hasPrevious)
            assertTrue(preview.hasNext)
            advanceUntilIdle()
            assertEquals(PreviewContent.Image, vm.content())
            coVerify(exactly = 0) { repo.previewText(any()) }
            coVerify(exactly = 0) { repo.download(any(), any()) }
            assertEquals(emptyList<UUID>(), targets)
        }

    @Test
    fun `a type without an in-app preview offers to open it in another app, without asking the server`() =
        runTest(dispatcher) {
            val vm = listed(archive)
            vm.preview(archive, ::target)
            advanceUntilIdle()
            assertEquals(PreviewContent.Unsupported, vm.content())
            coVerify(exactly = 0) { repo.previewText(any()) }
            coVerify(exactly = 0) { repo.download(any(), any()) }
        }

    @Test
    fun `a text file loads its start from the server, and says when it is cut`() =
        runTest(dispatcher) {
            coEvery { repo.previewText(notes.id) } returnsMany
                listOf(FilesResult.Ok(TextPreview("Einkaufsliste", false)), FilesResult.Ok(TextPreview("Anfang", true)))
            val vm = listed(notes)

            vm.preview(notes, ::target)
            assertEquals(PreviewContent.Loading, vm.content())
            advanceUntilIdle()
            assertEquals(PreviewContent.Text("Einkaufsliste", truncated = false), vm.content())

            vm.retryPreview()
            advanceUntilIdle()
            assertEquals(PreviewContent.Text("Anfang", truncated = true), vm.content())
            coVerify(exactly = 2) { repo.previewText(notes.id) }
        }

    @Test
    fun `a text type the server does not preview (415) falls back to open with`() =
        runTest(dispatcher) {
            val script = DriveEntry.File(id(6), "deploy.sh", "application/x-sh", 40, at)
            coEvery { repo.previewText(script.id) } returns FilesResult.Failed(415)
            val vm = listed(script)
            vm.preview(script, ::target)
            advanceUntilIdle()
            assertEquals(PreviewContent.Unsupported, vm.content())
        }

    @Test
    fun `a text preview offline or refused says so, and retry loads it again`() =
        runTest(dispatcher) {
            coEvery { repo.previewText(notes.id) } returnsMany
                listOf(FilesResult.Offline, FilesResult.Failed(404), FilesResult.Failed(null), FilesResult.Ok(TextPreview("ok", false)))
            val vm = listed(notes)

            vm.preview(notes, ::target)
            advanceUntilIdle()
            assertEquals(PreviewContent.Problem(FilesMessage.OFFLINE), vm.content())

            vm.retryPreview()
            assertEquals(PreviewContent.Loading, vm.content())
            advanceUntilIdle()
            assertEquals(PreviewContent.Problem(FilesMessage.FAILED), vm.content())

            vm.retryPreview()
            advanceUntilIdle()
            assertEquals(PreviewContent.Problem(FilesMessage.FAILED), vm.content())

            vm.retryPreview()
            advanceUntilIdle()
            assertEquals(PreviewContent.Text("ok", false), vm.content())
        }

    @Test
    fun `a PDF is downloaded into the cache file given for it, then shown from there`() =
        runTest(dispatcher) {
            downloadWrites(pdf, "%PDF-1.7 fictitious")
            val vm = listed(pdf)

            vm.preview(pdf, ::target)
            assertEquals(PreviewContent.Loading, vm.content())
            advanceUntilIdle()

            val cache = File(temp.root, "${pdf.id}.pdf")
            assertEquals(PreviewContent.Pdf(cache), vm.content())
            assertEquals("%PDF-1.7 fictitious", cache.readText())
            assertEquals(listOf(pdf.id), targets)
            // A preview is not a download: the list's download state stays untouched.
            assertNull(vm.uiState.value.downloading)
            assertNull(vm.uiState.value.downloadMessage)
        }

    @Test
    fun `a failed PDF download says why and deletes what it wrote`() =
        runTest(dispatcher) {
            downloadWrites(pdf, "%PDF-cut", FilesResult.Offline)
            val vm = listed(pdf)
            vm.preview(pdf, ::target)
            advanceUntilIdle()
            assertEquals(PreviewContent.Problem(FilesMessage.OFFLINE), vm.content())
            assertFalse(File(temp.root, "${pdf.id}.pdf").exists())

            downloadWrites(pdf, "%PDF-cut", FilesResult.Failed(404))
            vm.retryPreview()
            advanceUntilIdle()
            assertEquals(PreviewContent.Problem(FilesMessage.FAILED), vm.content())
            assertFalse(File(temp.root, "${pdf.id}.pdf").exists())

            downloadWrites(pdf, "%PDF-1.7 fictitious")
            vm.retryPreview()
            advanceUntilIdle()
            assertEquals(PreviewContent.Pdf(File(temp.root, "${pdf.id}.pdf")), vm.content())
        }

    @Test
    fun `a cache file that cannot be written fails the PDF preview`() =
        runTest(dispatcher) {
            downloadWrites(pdf, "%PDF-1.7 fictitious")
            val vm = listed(pdf)
            // Its folder does not exist, so it cannot be opened for writing.
            vm.preview(pdf) { File(temp.root, "missing/${it.id}.pdf") }
            advanceUntilIdle()
            assertEquals(PreviewContent.Problem(FilesMessage.FAILED), vm.content())
        }

    @Test
    fun `previous and next go through the folder's files and stop at either end`() =
        runTest(dispatcher) {
            coEvery { repo.previewText(notes.id) } returns FilesResult.Ok(TextPreview("Einkaufsliste", false))
            val vm = listed(docs, photo, archive, notes)

            vm.preview(archive, ::target)
            assertEquals(
                1,
                vm.uiState.value.preview!!
                    .index,
            )
            assertTrue(
                vm.uiState.value.preview!!
                    .hasPrevious,
            )
            assertTrue(
                vm.uiState.value.preview!!
                    .hasNext,
            )

            vm.previewStep(1)
            advanceUntilIdle()
            assertEquals(
                notes,
                vm.uiState.value.preview!!
                    .file,
            )
            assertEquals(PreviewContent.Text("Einkaufsliste", false), vm.content())
            assertFalse(
                vm.uiState.value.preview!!
                    .hasNext,
            )

            // Past the last file nothing changes and nothing loads again.
            vm.previewStep(1)
            advanceUntilIdle()
            assertEquals(
                notes,
                vm.uiState.value.preview!!
                    .file,
            )
            coVerify(exactly = 1) { repo.previewText(notes.id) }

            vm.previewStep(-1)
            vm.previewStep(-1)
            assertEquals(
                photo,
                vm.uiState.value.preview!!
                    .file,
            )
            assertEquals(PreviewContent.Image, vm.content())
            vm.previewStep(-1)
            assertEquals(
                photo,
                vm.uiState.value.preview!!
                    .file,
            )
            assertEquals(
                0,
                vm.uiState.value.preview!!
                    .index,
            )
        }

    @Test
    fun `a file not among the folder's entries is previewed alone`() =
        runTest(dispatcher) {
            val vm = listed(docs)
            vm.preview(photo, ::target)
            val preview = vm.uiState.value.preview!!
            assertEquals(listOf(photo), preview.files)
            assertEquals(0, preview.index)
            assertFalse(preview.hasPrevious)
            assertFalse(preview.hasNext)
        }

    @Test
    fun `stepping, retrying or closing without a preview does nothing`() =
        runTest(dispatcher) {
            val vm = listed(photo, pdf)
            vm.previewStep(1)
            vm.retryPreview()
            vm.closePreview()
            advanceUntilIdle()
            assertNull(vm.uiState.value.preview)
            coVerify(exactly = 0) { repo.download(any(), any()) }
        }

    @Test
    fun `a text that arrives after the preview moved on is not shown for the next file`() =
        runTest(dispatcher) {
            val late = CompletableDeferred<FilesResult<TextPreview>>()
            coEvery { repo.previewText(notes.id) } coAnswers { late.await() }
            val vm = listed(notes, photo)

            vm.preview(notes, ::target)
            advanceUntilIdle()
            vm.previewStep(1)
            late.complete(FilesResult.Ok(TextPreview("too late", false)))
            advanceUntilIdle()

            assertEquals(
                photo,
                vm.uiState.value.preview!!
                    .file,
            )
            assertEquals(PreviewContent.Image, vm.content())
        }

    @Test
    fun `the first of two texts answering last does not replace the second`() =
        runTest(dispatcher) {
            val readme = DriveEntry.File(id(7), "README.md", "text/markdown", 30, at)
            val first = CompletableDeferred<FilesResult<TextPreview>>()
            val second = CompletableDeferred<FilesResult<TextPreview>>()
            coEvery { repo.previewText(readme.id) } coAnswers { first.await() }
            coEvery { repo.previewText(notes.id) } coAnswers { second.await() }
            val vm = listed(readme, notes)

            vm.preview(readme, ::target)
            advanceUntilIdle()
            vm.previewStep(1)
            advanceUntilIdle()
            second.complete(FilesResult.Ok(TextPreview("second", false)))
            advanceUntilIdle()
            first.complete(FilesResult.Ok(TextPreview("first", false)))
            advanceUntilIdle()

            assertEquals(
                notes,
                vm.uiState.value.preview!!
                    .file,
            )
            assertEquals(PreviewContent.Text("second", false), vm.content())
        }

    @Test
    fun `stepping away while a PDF downloads drops it and deletes the partial file`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            coEvery { repo.download(pdf.id, any()) } coAnswers {
                secondArg<() -> OutputStream?>()()!!.use { it.write("%PDF-cut".toByteArray()) }
                gate.await()
                FilesResult.Ok(8L)
            }
            val vm = listed(pdf, photo)

            vm.preview(pdf, ::target)
            advanceUntilIdle()
            val cache = File(temp.root, "${pdf.id}.pdf")
            assertTrue(cache.exists())

            vm.previewStep(1)
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(PreviewContent.Image, vm.content())
            assertFalse("the cut-off PDF stayed in the cache", cache.exists())
        }

    @Test
    fun `opening the same PDF again while its first download still runs keeps the new file`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            var downloads = 0
            coEvery { repo.download(pdf.id, any()) } coAnswers {
                val first = ++downloads == 1
                val content = if (first) "%PDF-old" else "%PDF-new"
                secondArg<() -> OutputStream?>()()!!.use { it.write(content.toByteArray()) }
                // The first transfer only ends after the preview was opened again, and does not stop when cancelled.
                if (first) withContext(NonCancellable) { gate.await() }
                FilesResult.Ok(content.length.toLong())
            }
            var loads = 0
            val target = { file: DriveEntry.File -> File(temp.root, "${file.id}-${++loads}.pdf") }
            val vm = listed(pdf)

            vm.preview(pdf, target)
            advanceUntilIdle()
            vm.closePreview()
            vm.preview(pdf, target)
            advanceUntilIdle()
            val second = File(temp.root, "${pdf.id}-2.pdf")
            assertEquals(PreviewContent.Pdf(second), vm.content())

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals("the stale load replaced the new one", PreviewContent.Pdf(second), vm.content())
            assertEquals("%PDF-new", second.readText())
            assertFalse("the stale load kept its file", File(temp.root, "${pdf.id}-1.pdf").exists())
        }

    @Test
    fun `closing the preview cancels its loading, and a late answer does not open it again`() =
        runTest(dispatcher) {
            val late = CompletableDeferred<FilesResult<TextPreview>>()
            coEvery { repo.previewText(notes.id) } coAnswers { late.await() }
            val vm = listed(notes)

            vm.preview(notes, ::target)
            advanceUntilIdle()
            vm.closePreview()
            assertNull(vm.uiState.value.preview)
            late.complete(FilesResult.Ok(TextPreview("too late", false)))
            advanceUntilIdle()
            assertNull(vm.uiState.value.preview)
        }

    @Test
    fun `closing the preview while a PDF downloads deletes the partial file`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            coEvery { repo.download(pdf.id, any()) } coAnswers {
                secondArg<() -> OutputStream?>()()!!.use { it.write("%PDF-cut".toByteArray()) }
                gate.await()
                FilesResult.Ok(8L)
            }
            val vm = listed(pdf)
            vm.preview(pdf, ::target)
            advanceUntilIdle()

            vm.closePreview()
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            assertNull(vm.uiState.value.preview)
            assertFalse(File(temp.root, "${pdf.id}.pdf").exists())
        }

    @Test
    fun `a preview opened again after closing starts fresh on the tapped file`() =
        runTest(dispatcher) {
            coEvery { repo.previewText(notes.id) } returns FilesResult.Ok(TextPreview("Einkaufsliste", false))
            val vm = listed(photo, notes)
            vm.preview(photo, ::target)
            vm.previewStep(1)
            advanceUntilIdle()
            vm.closePreview()

            vm.preview(photo, ::target)
            assertEquals(FilePreview(listOf(photo, notes), 0, PreviewContent.Image), vm.uiState.value.preview)
        }
}
