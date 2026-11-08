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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

    private val attachments = DriveEntry.Folder(id(5), "Attachments", "ATTACHMENTS")
    private val txt = DriveEntry.File(id(6), "notes.txt", "text/plain", 12, Instant.parse("2026-10-01T09:00:00Z"))

    /** A view model showing the drive root with [entries]. */
    private fun TestScope.atRoot(vararg entries: DriveEntry): FilesViewModel {
        coEvery { repo.list(null) } returns FilesResult.Ok(entries.toList())
        val vm = FilesViewModel(repo, dispatcher)
        vm.refresh()
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `a long press selects and unselects, the auto-managed folders cannot be selected`() =
        runTest(dispatcher) {
            val vm = atRoot(attachments, docs, pdf)
            assertFalse(vm.uiState.value.selecting)

            vm.toggleSelected(pdf)
            vm.toggleSelected(docs)
            vm.toggleSelected(attachments)
            assertEquals(setOf(pdf.id, docs.id), vm.uiState.value.selected)
            assertTrue(vm.uiState.value.selecting)

            vm.toggleSelected(pdf)
            assertEquals(setOf(docs.id), vm.uiState.value.selected)
            vm.clearSelection()
            assertFalse(vm.uiState.value.selecting)
        }

    @Test
    fun `going to another folder ends the selection, a reload keeps only what is still there`() =
        runTest(dispatcher) {
            coEvery { repo.list(docs.id) } returns FilesResult.Ok(emptyList())
            val vm = atRoot(docs, pdf, txt)
            vm.toggleSelected(pdf)
            vm.toggleSelected(txt)

            // txt was deleted elsewhere.
            coEvery { repo.list(null) } returns FilesResult.Ok(listOf(docs, pdf))
            vm.refresh()
            advanceUntilIdle()
            assertEquals(setOf(pdf.id), vm.uiState.value.selected)

            // A failed reload changes nothing about the selection.
            coEvery { repo.list(null) } returns FilesResult.Offline
            vm.refresh()
            advanceUntilIdle()
            assertEquals(setOf(pdf.id), vm.uiState.value.selected)

            vm.openFolder(docs)
            assertEquals(emptySet<UUID>(), vm.uiState.value.selected)
            advanceUntilIdle()
            vm.toggleSelected(txt)
            vm.up()
            assertEquals(emptySet<UUID>(), vm.uiState.value.selected)
        }

    @Test
    fun `a new folder is created in the folder shown, the dialog closes, it is confirmed and the folder reloads`() =
        runTest(dispatcher) {
            val created = DriveEntry.Folder(id(7), "Taxes", null, version = 0)
            coEvery { repo.list(any()) } returns FilesResult.Ok(emptyList())
            coEvery { repo.createFolder(docs.id, "  Taxes ") } returns FilesResult.Ok(created)
            val vm = FilesViewModel(repo, dispatcher)
            vm.openFolder(docs)
            advanceUntilIdle()

            vm.startNewFolder()
            assertEquals(NameDialog(), vm.uiState.value.nameDialog)
            vm.saveName("  Taxes ")
            assertTrue(
                vm.uiState.value.nameDialog!!
                    .saving,
            )
            advanceUntilIdle()

            assertNull(vm.uiState.value.nameDialog)
            assertEquals(FilesEvent.Changed(FilesChange.CREATED, 1, "Taxes"), vm.events.first())
            coVerify(exactly = 2) { repo.list(docs.id) }
        }

    @Test
    fun `a name the server would refuse is not sent, and nothing is sent without a dialog`() =
        runTest(dispatcher) {
            val vm = atRoot(pdf)
            vm.saveName("Taxes")
            vm.startNewFolder()
            vm.saveName("   ")
            vm.saveName("a/b")
            vm.saveName("..")
            vm.saveName("a".repeat(MAX_NAME_LENGTH + 1))
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.createFolder(any(), any()) }
            assertEquals(NameDialog(), vm.uiState.value.nameDialog)
        }

    @Test
    fun `a taken name keeps the dialog open with the reason, and nothing is reloaded`() =
        runTest(dispatcher) {
            coEvery { repo.rename(pdf, "notes.txt") } returns FilesResult.Failed(409)
            val vm = atRoot(pdf, txt)
            vm.startRename(pdf)
            assertEquals(NameDialog(pdf), vm.uiState.value.nameDialog)
            vm.saveName("notes.txt")
            advanceUntilIdle()

            assertEquals(NameDialog(pdf, saving = false, message = ActionMessage.NAME_TAKEN), vm.uiState.value.nameDialog)
            coVerify(exactly = 1) { repo.list(null) }
        }

    @Test
    fun `a rename of something changed or gone elsewhere says so and reloads, offline only says so`() =
        runTest(dispatcher) {
            coEvery { repo.rename(pdf, any()) } returnsMany listOf(FilesResult.Failed(412), FilesResult.Failed(404), FilesResult.Offline)
            val vm = atRoot(pdf)
            vm.startRename(pdf)

            vm.saveName("2026.pdf")
            advanceUntilIdle()
            assertEquals(
                ActionMessage.CHANGED,
                vm.uiState.value.nameDialog!!
                    .message,
            )
            coVerify(exactly = 2) { repo.list(null) }

            vm.saveName("2026.pdf")
            advanceUntilIdle()
            assertEquals(
                ActionMessage.CHANGED,
                vm.uiState.value.nameDialog!!
                    .message,
            )
            coVerify(exactly = 3) { repo.list(null) }

            vm.saveName("2026.pdf")
            advanceUntilIdle()
            assertEquals(
                ActionMessage.OFFLINE,
                vm.uiState.value.nameDialog!!
                    .message,
            )
            assertFalse(
                vm.uiState.value.nameDialog!!
                    .saving,
            )
            coVerify(exactly = 3) { repo.list(null) }
        }

    @Test
    fun `a successful rename confirms the new name, other refusals are a failure`() =
        runTest(dispatcher) {
            coEvery { repo.rename(docs, any()) } returns FilesResult.Ok(Unit)
            coEvery { repo.rename(pdf, any()) } returns FilesResult.Failed(400)
            val vm = atRoot(docs, pdf)
            vm.startRename(pdf)
            vm.saveName("bad.pdf")
            advanceUntilIdle()
            assertEquals(
                ActionMessage.FAILED,
                vm.uiState.value.nameDialog!!
                    .message,
            )

            vm.startRename(docs)
            vm.saveName(" Archive ")
            advanceUntilIdle()
            assertNull(vm.uiState.value.nameDialog)
            assertEquals(FilesEvent.Changed(FilesChange.RENAMED, 1, "Archive"), vm.events.first())
        }

    @Test
    fun `while a name is saved, a second save and cancel are ignored`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<FilesResult<Unit>>()
            coEvery { repo.rename(pdf, any()) } coAnswers { gate.await() }
            val vm = atRoot(pdf)
            vm.startRename(pdf)
            vm.saveName("2026.pdf")
            advanceUntilIdle()
            vm.saveName("2027.pdf")
            vm.cancelName()
            assertTrue(
                vm.uiState.value.nameDialog!!
                    .saving,
            )

            gate.complete(FilesResult.Ok(Unit))
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.rename(pdf, any()) }
            assertNull(vm.uiState.value.nameDialog)
        }

    @Test
    fun `cancel closes the name dialog`() =
        runTest(dispatcher) {
            val vm = atRoot(pdf)
            vm.startNewFolder()
            vm.cancelName()
            assertNull(vm.uiState.value.nameDialog)
        }

    @Test
    fun `the move picker starts where the entry is, lists only folders, and cannot open the folder being moved`() =
        runTest(dispatcher) {
            val vm = atRoot(docs, tax, pdf)
            vm.startMove(docs)
            val picker = vm.uiState.value.movePicker!!
            assertEquals(listOf(docs), picker.entries)
            assertEquals(emptyList<FolderCrumb>(), picker.path)
            assertTrue(picker.loading)
            advanceUntilIdle()

            assertEquals(
                listOf(docs, tax),
                vm.uiState.value.movePicker!!
                    .folders,
            )
            assertFalse(
                "moving into the folder it is in",
                vm.uiState.value.movePicker!!
                    .canMove,
            )
            assertFalse(
                vm.uiState.value.movePicker!!
                    .canOpen(docs),
            )

            vm.pickerOpen(docs)
            advanceUntilIdle()
            assertEquals(
                emptyList<FolderCrumb>(),
                vm.uiState.value.movePicker!!
                    .path,
            )
            coVerify(exactly = 0) { repo.list(docs.id) }
        }

    @Test
    fun `a file moves into the folder browsed to, the picker closes, it is confirmed and the folder reloads`() =
        runTest(dispatcher) {
            coEvery { repo.list(tax.id) } returns FilesResult.Ok(listOf(txt))
            coEvery { repo.move(pdf, tax.id) } returns FilesResult.Ok(Unit)
            val vm = atRoot(docs, tax, pdf)
            vm.startMove(pdf)
            advanceUntilIdle()
            vm.pickerOpen(tax)
            advanceUntilIdle()
            val picker = vm.uiState.value.movePicker!!
            assertEquals(listOf(FolderCrumb(tax.id, "tax")), picker.path)
            assertEquals(emptyList<DriveEntry.Folder>(), picker.folders)
            assertTrue(picker.canMove)

            vm.confirmMove()
            assertTrue(
                vm.uiState.value.movePicker!!
                    .moving,
            )
            assertFalse(
                vm.uiState.value.movePicker!!
                    .canMove,
            )
            advanceUntilIdle()
            assertNull(vm.uiState.value.movePicker)
            assertEquals(FilesEvent.Changed(FilesChange.MOVED, 1, "2025.pdf"), vm.events.first())
            coVerify(exactly = 1) { repo.move(pdf, tax.id) }
            // The first load, the picker's first folder (the drive root), and the reload after the move.
            coVerify(exactly = 3) { repo.list(null) }
        }

    @Test
    fun `the picker goes back up to the drive root, and a move there asks for no folder`() =
        runTest(dispatcher) {
            coEvery { repo.list(any()) } returns FilesResult.Ok(emptyList())
            coEvery { repo.list(null) } returns FilesResult.Ok(listOf(docs))
            coEvery { repo.list(docs.id) } returns FilesResult.Ok(listOf(tax))
            coEvery { repo.move(pdf, null) } returns FilesResult.Ok(Unit)
            val vm = FilesViewModel(repo, dispatcher)
            vm.openFolder(docs)
            advanceUntilIdle()
            vm.openFolder(tax)
            advanceUntilIdle()

            vm.startMove(pdf)
            advanceUntilIdle()
            assertEquals(
                2,
                vm.uiState.value.movePicker!!
                    .path.size,
            )
            vm.pickerGoTo(5)
            vm.pickerGoTo(2)
            assertEquals(
                2,
                vm.uiState.value.movePicker!!
                    .path.size,
            )
            vm.pickerGoTo(1)
            advanceUntilIdle()
            assertEquals(
                listOf(tax),
                vm.uiState.value.movePicker!!
                    .folders,
            )
            vm.pickerGoTo(0)
            advanceUntilIdle()
            assertEquals(
                listOf(docs),
                vm.uiState.value.movePicker!!
                    .folders,
            )

            vm.confirmMove()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.move(pdf, null) }
        }

    @Test
    fun `a picker folder that cannot be loaded says why`() =
        runTest(dispatcher) {
            val vm = atRoot(docs, pdf)
            coEvery { repo.list(docs.id) } returns FilesResult.Offline
            vm.startMove(pdf)
            advanceUntilIdle()
            vm.pickerOpen(docs)
            advanceUntilIdle()
            val picker = vm.uiState.value.movePicker!!
            assertEquals(FilesMessage.OFFLINE, picker.message)
            assertFalse(picker.loading)
            assertEquals(emptyList<DriveEntry.Folder>(), picker.folders)
        }

    @Test
    fun `the selection moves together and ends when all moved`() =
        runTest(dispatcher) {
            coEvery { repo.move(any(), docs.id) } returns FilesResult.Ok(Unit)
            coEvery { repo.list(docs.id) } returns FilesResult.Ok(emptyList())
            val vm = atRoot(docs, tax, pdf, txt)
            vm.startMoveSelected()
            assertNull("nothing selected, nothing to move", vm.uiState.value.movePicker)

            vm.toggleSelected(tax)
            vm.toggleSelected(pdf)
            vm.toggleSelected(txt)
            vm.startMoveSelected()
            advanceUntilIdle()
            assertEquals(
                listOf(tax, pdf, txt),
                vm.uiState.value.movePicker!!
                    .entries,
            )
            vm.pickerOpen(docs)
            advanceUntilIdle()
            vm.confirmMove()
            advanceUntilIdle()

            assertNull(vm.uiState.value.movePicker)
            assertEquals(emptySet<UUID>(), vm.uiState.value.selected)
            assertEquals(FilesChange.MOVED, (vm.events.first() as FilesEvent.Changed).change)
            coVerify(exactly = 1) { repo.move(tax, docs.id) }
            coVerify(exactly = 1) { repo.move(pdf, docs.id) }
            coVerify(exactly = 1) { repo.move(txt, docs.id) }
        }

    @Test
    fun `a partly failed move keeps only what was not moved in the picker, with the reason`() =
        runTest(dispatcher) {
            coEvery { repo.list(docs.id) } returns FilesResult.Ok(emptyList())
            coEvery { repo.move(pdf, docs.id) } returns FilesResult.Failed(409)
            coEvery { repo.move(txt, docs.id) } returns FilesResult.Ok(Unit)
            val vm = atRoot(docs, pdf, txt)
            vm.toggleSelected(pdf)
            vm.toggleSelected(txt)
            vm.startMoveSelected()
            advanceUntilIdle()
            vm.pickerOpen(docs)
            advanceUntilIdle()
            vm.confirmMove()
            advanceUntilIdle()

            val picker = vm.uiState.value.movePicker!!
            assertEquals(listOf(pdf), picker.entries)
            assertEquals(ActionMessage.MOVE_CONFLICT, picker.moveMessage)
            assertFalse(picker.moving)
            assertEquals(1, (vm.events.first() as FilesEvent.Changed).count)

            // Trying again moves only what is left.
            coEvery { repo.move(pdf, docs.id) } returns FilesResult.Ok(Unit)
            vm.confirmMove()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.move(txt, docs.id) }
            coVerify(exactly = 2) { repo.move(pdf, docs.id) }
            assertNull(vm.uiState.value.movePicker)
        }

    /** TW-1: the confirmation of a partly failed move names an entry that was not moved. */
    @Test
    fun `the confirmation of a partly failed move names what was moved`() =
        runTest(dispatcher) {
            coEvery { repo.list(docs.id) } returns FilesResult.Ok(emptyList())
            coEvery { repo.move(pdf, docs.id) } returns FilesResult.Failed(409)
            coEvery { repo.move(txt, docs.id) } returns FilesResult.Ok(Unit)
            val vm = atRoot(docs, pdf, txt)
            vm.toggleSelected(pdf)
            vm.toggleSelected(txt)
            vm.startMoveSelected()
            advanceUntilIdle()
            vm.pickerOpen(docs)
            advanceUntilIdle()
            vm.confirmMove()
            advanceUntilIdle()

            assertEquals(FilesEvent.Changed(FilesChange.MOVED, 1, "notes.txt"), vm.events.first())
        }

    @Test
    fun `a move that fails completely sends no confirmation, and the reasons are told apart`() =
        runTest(dispatcher) {
            coEvery { repo.list(docs.id) } returns FilesResult.Ok(emptyList())
            coEvery { repo.move(pdf, docs.id) } returnsMany
                listOf(FilesResult.Offline, FilesResult.Failed(412), FilesResult.Failed(500), FilesResult.Failed(null))
            val vm = atRoot(docs, pdf)
            vm.startMove(pdf)
            advanceUntilIdle()
            vm.pickerOpen(docs)
            advanceUntilIdle()

            val messages =
                (1..4).map {
                    vm.confirmMove()
                    advanceUntilIdle()
                    vm.uiState.value.movePicker!!
                        .moveMessage
                }
            assertEquals(listOf(ActionMessage.OFFLINE, ActionMessage.CHANGED, ActionMessage.FAILED, ActionMessage.FAILED), messages)
            vm.cancelMove()
            assertNull(vm.uiState.value.movePicker)
            // Nothing moved: the only event would have been a confirmation.
            assertNull(withTimeoutOrNull(1) { vm.events.first() })
        }

    @Test
    fun `while moving, the picker cannot be closed, browsed or confirmed again`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<FilesResult<Unit>>()
            coEvery { repo.list(docs.id) } returns FilesResult.Ok(listOf(tax))
            coEvery { repo.move(pdf, docs.id) } coAnswers { gate.await() }
            val vm = atRoot(docs, pdf)
            vm.startMove(pdf)
            advanceUntilIdle()
            vm.pickerOpen(docs)
            advanceUntilIdle()
            vm.confirmMove()
            advanceUntilIdle()

            vm.cancelMove()
            vm.pickerOpen(tax)
            vm.pickerGoTo(0)
            vm.confirmMove()
            advanceUntilIdle()
            val picker = vm.uiState.value.movePicker!!
            assertTrue(picker.moving)
            assertEquals(listOf(FolderCrumb(docs.id, "Documents")), picker.path)

            gate.complete(FilesResult.Ok(Unit))
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.move(pdf, docs.id) }
            assertNull(vm.uiState.value.movePicker)
        }

    @Test
    fun `a delete moves the entry to the Trash, confirms it and reloads`() =
        runTest(dispatcher) {
            coEvery { repo.delete(pdf) } returns FilesResult.Ok(Unit)
            val vm = atRoot(docs, pdf)
            vm.delete(pdf)
            assertTrue(vm.uiState.value.working)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.working)
            assertNull(vm.uiState.value.actionMessage)
            assertEquals(FilesEvent.Changed(FilesChange.TRASHED, 1, "2025.pdf"), vm.events.first())
            coVerify(exactly = 2) { repo.list(null) }
        }

    @Test
    fun `deleting the selection counts what was already gone as done, and ends the selection`() =
        runTest(dispatcher) {
            coEvery { repo.delete(docs) } returns FilesResult.Failed(404)
            coEvery { repo.delete(pdf) } returns FilesResult.Ok(Unit)
            coEvery { repo.delete(txt) } returns FilesResult.Ok(Unit)
            val vm = atRoot(docs, pdf, txt)
            vm.deleteSelected()
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.delete(any()) }

            vm.toggleSelected(docs)
            vm.toggleSelected(pdf)
            vm.toggleSelected(txt)
            vm.deleteSelected()
            advanceUntilIdle()
            assertNull(vm.uiState.value.actionMessage)
            assertEquals(emptySet<UUID>(), vm.uiState.value.selected)
            assertEquals(FilesEvent.Changed(FilesChange.TRASHED, 2, "Documents"), vm.events.first())
        }

    @Test
    fun `a failed delete says why and keeps the selection, nothing deleted sends no confirmation`() =
        runTest(dispatcher) {
            coEvery { repo.delete(pdf) } returnsMany listOf(FilesResult.Failed(412), FilesResult.Offline, FilesResult.Failed(400))
            val vm = atRoot(pdf)
            vm.toggleSelected(pdf)
            val messages =
                (1..3).map {
                    vm.deleteSelected()
                    advanceUntilIdle()
                    vm.uiState.value.actionMessage
                }
            assertEquals(listOf(ActionMessage.CHANGED, ActionMessage.OFFLINE, ActionMessage.FAILED), messages)
            assertEquals(setOf(pdf.id), vm.uiState.value.selected)
            assertNull(withTimeoutOrNull(1) { vm.events.first() })

            vm.dismissActionMessage()
            assertNull(vm.uiState.value.actionMessage)
        }

    /** TW-1: like a move, the confirmation of a partly failed delete names an entry that is still there. */
    @Test
    fun `the confirmation of a partly failed delete names what was trashed`() =
        runTest(dispatcher) {
            coEvery { repo.delete(pdf) } returns FilesResult.Offline
            coEvery { repo.delete(txt) } returns FilesResult.Ok(Unit)
            val vm = atRoot(pdf, txt)
            vm.toggleSelected(pdf)
            vm.toggleSelected(txt)
            vm.deleteSelected()
            advanceUntilIdle()
            assertEquals(ActionMessage.OFFLINE, vm.uiState.value.actionMessage)
            assertEquals(FilesEvent.Changed(FilesChange.TRASHED, 1, "notes.txt"), vm.events.first())
        }

    /** TW-2: a refused delete (409: changed concurrently, or a special folder) is not a failed move. */
    @Test
    fun `a delete the server refuses with a conflict does not say an item was not moved`() =
        runTest(dispatcher) {
            coEvery { repo.delete(docs) } returns FilesResult.Failed(409)
            val vm = atRoot(docs)
            vm.delete(docs)
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.actionMessage)
            assertNotEquals(ActionMessage.MOVE_CONFLICT, vm.uiState.value.actionMessage)
        }

    @Test
    fun `a delete while another runs is ignored`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<FilesResult<Unit>>()
            coEvery { repo.delete(pdf) } coAnswers { gate.await() }
            val vm = atRoot(pdf, txt)
            vm.delete(pdf)
            advanceUntilIdle()
            vm.delete(txt)
            gate.complete(FilesResult.Ok(Unit))
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.delete(txt) }
            coVerify(exactly = 1) { repo.delete(pdf) }
        }
}
