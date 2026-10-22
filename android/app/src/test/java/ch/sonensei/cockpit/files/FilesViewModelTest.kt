package ch.sonensei.cockpit.files

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
            val vm = FilesViewModel(repo)
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
            val vm = FilesViewModel(repo)
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
            val vm = FilesViewModel(repo)
            vm.open(pdf, target)
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
            val vm = FilesViewModel(repo)
            vm.save(pdf) { null }
            advanceUntilIdle()
            vm.save(pdf) { null }
            gate.complete(FilesResult.Offline)
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.download(pdf.id, any()) }
            assertEquals(FilesMessage.OFFLINE, vm.uiState.value.downloadMessage)
            vm.dismissDownloadMessage()
            assertNull(vm.uiState.value.downloadMessage)
        }

    @Test
    fun `saving reports the saved file`() =
        runTest(dispatcher) {
            coEvery { repo.download(pdf.id, any()) } returns FilesResult.Ok(3L)
            val vm = FilesViewModel(repo)
            vm.save(pdf) { null }
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
            val vm = FilesViewModel(repo)
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
            val vm = FilesViewModel(repo)
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
    fun `picking nothing uploads nothing`() =
        runTest(dispatcher) {
            val vm = FilesViewModel(repo)
            vm.upload(emptyList())
            assertEquals(emptyList<UploadItem>(), vm.uiState.value.uploads)
        }
}
