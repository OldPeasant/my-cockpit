package ch.sonensei.cockpit.files

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<FilesRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val docs = DriveEntry.Folder(id(1), "Documents", null)
    private val tax = DriveEntry.Folder(id(2), "tax", null)
    private val pdf = DriveEntry.File(id(3), "2025.pdf", "application/pdf", 2048, Instant.parse("2026-10-01T09:00:00Z"))

    private class Source(
        override val name: String,
    ) : UploadSource {
        override val mime = "text/plain"
        override val sizeBytes: Long? = null

        override fun open(): InputStream? = null
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `browsing goes into folders, back up, and to any folder of the path`() =
        runTest(dispatcher) {
            coEvery { repo.list(null) } returns FilesResult.Ok(listOf(docs))
            coEvery { repo.list(docs.id) } returns FilesResult.Ok(listOf(tax))
            coEvery { repo.list(tax.id) } returns FilesResult.Ok(listOf(pdf))
            val vm = FilesViewModel(repo, dispatcher)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(listOf(docs), vm.uiState.value.entries)
            assertTrue(vm.uiState.value.loaded)

            vm.openFolder(docs)
            assertEquals(emptyList<DriveEntry>(), vm.uiState.value.entries)
            assertFalse(vm.uiState.value.loaded)
            advanceUntilIdle()
            vm.openFolder(tax)
            advanceUntilIdle()
            assertEquals(
                listOf("Documents", "tax"),
                vm.uiState.value.path
                    .map { it.name },
            )
            assertEquals(listOf(pdf), vm.uiState.value.entries)

            assertTrue(vm.up())
            advanceUntilIdle()
            assertEquals(listOf(tax), vm.uiState.value.entries)
            vm.goTo(0)
            advanceUntilIdle()
            assertEquals(emptyList<FolderCrumb>(), vm.uiState.value.path)
            assertEquals(listOf(docs), vm.uiState.value.entries)
            assertFalse(vm.up())
        }

    @Test
    fun `a failed load keeps what was shown and says why`() =
        runTest(dispatcher) {
            coEvery { repo.list(null) } returnsMany listOf(FilesResult.Ok(listOf(docs)), FilesResult.Offline, FilesResult.Failed(500))
            val vm = FilesViewModel(repo, dispatcher)
            vm.refresh()
            advanceUntilIdle()
            vm.refresh()
            advanceUntilIdle()
            assertEquals(listOf(docs), vm.uiState.value.entries)
            assertEquals(FilesMessage.OFFLINE, vm.uiState.value.message)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(FilesMessage.FAILED, vm.uiState.value.message)
            assertFalse(vm.uiState.value.loading)
        }

    @Test
    fun `opening a file downloads it into the target, then asks to open it`() =
        runTest(dispatcher) {
            val target = temp.newFile("2025.pdf")
            coEvery { repo.download(pdf.id, any()) } coAnswers {
                secondArg<() -> java.io.OutputStream?>()()!!.use { it.write("pdf".toByteArray()) }
                FilesResult.Ok(3L)
            }
            val vm = FilesViewModel(repo, dispatcher)
            vm.open(pdf) { target }
            assertEquals(pdf.id, vm.uiState.value.downloading)
            advanceUntilIdle()
            assertNull(vm.uiState.value.downloading)
            assertEquals(FilesEvent.Open(target, "application/pdf"), vm.events.first())
            assertEquals("pdf", target.readText())
        }

    @Test
    fun `a failed download says so and opens nothing, a second tap while downloading is ignored`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<FilesResult<Long>>()
            coEvery { repo.download(pdf.id, any()) } coAnswers { gate.await() }
            val vm = FilesViewModel(repo, dispatcher)
            vm.save(pdf, { null })
            advanceUntilIdle()
            vm.save(pdf, { null })
            gate.complete(FilesResult.Offline)
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.download(pdf.id, any()) }
            assertEquals(FilesMessage.OFFLINE, vm.uiState.value.downloadMessage)
            vm.dismissDownloadMessage()
            assertNull(vm.uiState.value.downloadMessage)
        }

    @Test
    fun `a second open while one downloads touches no file, and the first is still opened`() =
        runTest(dispatcher) {
            val other = DriveEntry.File(id(4), "b.txt", "text/plain", 1, Instant.parse("2026-10-01T09:00:00Z"))
            val first = temp.newFile("2025.pdf")
            val gate = CompletableDeferred<Unit>()
            coEvery { repo.download(pdf.id, any()) } coAnswers {
                secondArg<() -> OutputStream?>()()!!.use { it.write("pdf".toByteArray()) }
                gate.await()
                FilesResult.Ok(3L)
            }
            val vm = FilesViewModel(repo, dispatcher)
            vm.open(pdf) { first }
            advanceUntilIdle()

            var secondTarget = false
            vm.open(other) {
                secondTarget = true
                first.delete()
                temp.newFile("b.txt")
            }
            gate.complete(Unit)
            advanceUntilIdle()

            assertFalse("the second target was chosen", secondTarget)
            coVerify(exactly = 0) { repo.download(other.id, any()) }
            assertEquals(FilesEvent.Open(first, "application/pdf"), vm.events.first())
            assertEquals("pdf", first.readText())
        }

    @Test
    fun `a failed open deletes what it wrote into the cache`() =
        runTest(dispatcher) {
            val target = temp.newFile("2025.pdf")
            coEvery { repo.download(pdf.id, any()) } coAnswers {
                secondArg<() -> OutputStream?>()()!!.use { it.write("cut".toByteArray()) }
                FilesResult.Offline
            }
            val vm = FilesViewModel(repo, dispatcher)
            vm.open(pdf) { target }
            advanceUntilIdle()
            assertFalse(target.exists())
            assertEquals(FilesMessage.OFFLINE, vm.uiState.value.downloadMessage)
        }

    @Test
    fun `a failed save discards the chosen document, a successful one keeps it`() =
        runTest(dispatcher) {
            coEvery { repo.download(pdf.id, any()) } returnsMany listOf(FilesResult.Failed(404), FilesResult.Ok(3L))
            val vm = FilesViewModel(repo, dispatcher)
            var discarded = 0
            vm.save(pdf, { null }) { discarded++ }
            advanceUntilIdle()
            assertEquals(1, discarded)
            assertEquals(FilesMessage.FAILED, vm.uiState.value.downloadMessage)

            vm.save(pdf, { null }) { discarded++ }
            advanceUntilIdle()
            assertEquals(1, discarded)
            assertEquals(FilesEvent.Saved("2025.pdf"), vm.events.first())
        }

    @Test
    fun `a download cancelled with the screen discards what it wrote`() =
        runTest(dispatcher) {
            val target = temp.newFile("2025.pdf")
            coEvery { repo.download(pdf.id, any()) } coAnswers {
                secondArg<() -> OutputStream?>()()!!.use { it.write("cut".toByteArray()) }
                CompletableDeferred<FilesResult<Long>>().await()
            }
            val store = ViewModelStore()
            val factory = viewModelFactory { initializer { FilesViewModel(repo, dispatcher) } }
            val vm = ViewModelProvider(store, factory)[FilesViewModel::class.java]
            var discarded = 0
            vm.open(pdf) { target }
            vm.save(pdf, { null }) { discarded++ }
            advanceUntilIdle()
            assertTrue(target.exists())
            // The ignored save's document (created empty by the "save as" picker) is discarded right away.
            assertEquals("the ignored save left its document behind", 1, discarded)

            store.clear()
            advanceUntilIdle()
            assertFalse(target.exists())
            assertEquals("the ignored save was discarded again", 1, discarded)
        }

    @Test
    fun `a save ignored while another file downloads discards its document, the running download is not touched`() =
        runTest(dispatcher) {
            val other = DriveEntry.File(id(4), "b.txt", "text/plain", 1, Instant.parse("2026-10-01T09:00:00Z"))
            val first = temp.newFile("2025.pdf")
            val gate = CompletableDeferred<Unit>()
            coEvery { repo.download(pdf.id, any()) } coAnswers {
                secondArg<() -> OutputStream?>()()!!.use { it.write("pdf".toByteArray()) }
                gate.await()
                FilesResult.Ok(3L)
            }
            val vm = FilesViewModel(repo, dispatcher)
            vm.open(pdf) { first }
            advanceUntilIdle()

            var opened = false
            var discarded = 0
            val target: () -> OutputStream? = {
                opened = true
                null
            }
            vm.save(other, target) { discarded++ }
            advanceUntilIdle()
            assertEquals(1, discarded)
            assertFalse("the ignored save opened its document", opened)
            assertEquals(pdf.id, vm.uiState.value.downloading)

            gate.complete(Unit)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.download(other.id, any()) }
            assertEquals(FilesEvent.Open(first, "application/pdf"), vm.events.first())
            assertEquals("pdf", first.readText())
            assertEquals(1, discarded)
        }

    @Test
    fun `a save cancelled with the screen discards the chosen document`() =
        runTest(dispatcher) {
            coEvery { repo.download(pdf.id, any()) } coAnswers { CompletableDeferred<FilesResult<Long>>().await() }
            val store = ViewModelStore()
            val factory = viewModelFactory { initializer { FilesViewModel(repo, dispatcher) } }
            val vm = ViewModelProvider(store, factory)[FilesViewModel::class.java]
            var discarded = 0
            vm.save(pdf, { null }) { discarded++ }
            advanceUntilIdle()
            assertEquals("discarded while still downloading", 0, discarded)

            store.clear()
            advanceUntilIdle()
            assertEquals(1, discarded)
        }

    @Test
    fun `a discard that fails does not hide why the save failed, and the next save still works`() =
        runTest(dispatcher) {
            coEvery { repo.download(pdf.id, any()) } returnsMany listOf(FilesResult.Offline, FilesResult.Ok(3L))
            val vm = FilesViewModel(repo, dispatcher)
            vm.save(pdf, { null }) { throw IllegalStateException("fictitious: the provider is gone") }
            advanceUntilIdle()
            assertEquals(FilesMessage.OFFLINE, vm.uiState.value.downloadMessage)
            assertNull(vm.uiState.value.downloading)

            vm.save(pdf, { null })
            advanceUntilIdle()
            assertEquals(FilesEvent.Saved("2025.pdf"), vm.events.first())
        }

    @Test
    fun `saving reports the saved file`() =
        runTest(dispatcher) {
            coEvery { repo.download(pdf.id, any()) } returns FilesResult.Ok(3L)
            val vm = FilesViewModel(repo, dispatcher)
            vm.save(pdf, { null })
            advanceUntilIdle()
            assertEquals(FilesEvent.Saved("2025.pdf"), vm.events.first())
        }

    @Test
    fun `uploads go into the folder shown, report each outcome and reload it`() =
        runTest(dispatcher) {
            coEvery { repo.list(any()) } returns FilesResult.Ok(emptyList())
            coEvery { repo.upload(docs.id, match { it.name == "a.txt" }) } returns FilesResult.Ok(pdf)
            coEvery { repo.upload(docs.id, match { it.name == "big.iso" }) } returns FilesResult.Failed(413)
            coEvery { repo.upload(docs.id, match { it.name == "c.txt" }) } returns FilesResult.Offline
            coEvery { repo.upload(docs.id, match { it.name == "d.txt" }) } returns FilesResult.Failed(null)
            val vm = FilesViewModel(repo, dispatcher)
            vm.openFolder(docs)
            advanceUntilIdle()

            vm.upload(listOf(Source("a.txt"), Source("big.iso"), Source("c.txt"), Source("d.txt")))
            assertTrue(
                vm.uiState.value.uploads
                    .all { it.state == UploadState.UPLOADING },
            )
            advanceUntilIdle()
            assertEquals(
                listOf(UploadState.DONE, UploadState.TOO_LARGE, UploadState.OFFLINE, UploadState.FAILED),
                vm.uiState.value.uploads
                    .map { it.state },
            )
            coVerify(exactly = 2) { repo.list(docs.id) }

            vm.dismissUpload(
                vm.uiState.value.uploads[0]
                    .key,
            )
            assertEquals(
                listOf("big.iso", "c.txt", "d.txt"),
                vm.uiState.value.uploads
                    .map { it.name },
            )
        }

    @Test
    fun `an upload still running cannot be dismissed, and no reload happens after leaving the folder`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<FilesResult<DriveEntry.File>>()
            coEvery { repo.list(any()) } returns FilesResult.Ok(emptyList())
            coEvery { repo.upload(null, any()) } coAnswers { gate.await() }
            val vm = FilesViewModel(repo, dispatcher)
            vm.upload(listOf(Source("a.txt")))
            advanceUntilIdle()
            vm.dismissUpload(
                vm.uiState.value.uploads
                    .single()
                    .key,
            )
            assertEquals(1, vm.uiState.value.uploads.size)

            vm.openFolder(docs)
            advanceUntilIdle()
            gate.complete(FilesResult.Ok(pdf))
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.list(null) }
            assertEquals(
                UploadState.DONE,
                vm.uiState.value.uploads
                    .single()
                    .state,
            )
        }

    @Test
    fun `picked files are described in the view model's IO coroutine and go into the folder shown when picked`() =
        runTest(dispatcher) {
            coEvery { repo.list(any()) } returns FilesResult.Ok(emptyList())
            coEvery { repo.upload(docs.id, any()) } returns FilesResult.Ok(pdf)
            val vm = FilesViewModel(repo, dispatcher)
            vm.openFolder(docs)
            advanceUntilIdle()

            var described = false
            vm.uploadPicked {
                described = true
                listOf(Source("a.txt"))
            }
            // Not in the caller (the activity-result callback on the main thread).
            assertFalse(described)
            vm.goTo(0)
            advanceUntilIdle()

            assertTrue(described)
            coVerify(exactly = 1) { repo.upload(docs.id, match { it.name == "a.txt" }) }
            assertEquals(
                UploadState.DONE,
                vm.uiState.value.uploads
                    .single()
                    .state,
            )
        }

    @Test
    fun `picking nothing uploads nothing`() =
        runTest(dispatcher) {
            val vm = FilesViewModel(repo, dispatcher)
            vm.upload(emptyList())
            assertEquals(emptyList<UploadItem>(), vm.uiState.value.uploads)
        }
}
