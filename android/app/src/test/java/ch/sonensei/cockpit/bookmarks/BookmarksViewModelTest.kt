package ch.sonensei.cockpit.bookmarks

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarksViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<BookmarksRepository>()
    private val pending = MutableStateFlow(0)

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun bookmark(
        n: Int,
        title: String = "Page $n",
        tags: List<String> = listOf("reading"),
        readAt: Instant? = null,
        description: String? = null,
        version: Long = 1,
    ) = Bookmark(id(n), "https://example.org/$n", title, title, description, null, tags, true, readAt, Instant.EPOCH, version)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.tags() } returns BookmarksResult.Ok(listOf(BookmarkTag("reading", 2), BookmarkTag("rust", 1)))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = BookmarksViewModel(repo, pending)

    /** The kind of the failure snackbar shown. */
    private fun failure(vm: BookmarksViewModel) = (vm.uiState.value.snackbar as BookmarksSnackbar.Failed).failure

    @Test
    fun `two equal failures in a row are two snackbars, and the end of the first keeps the second`() =
        runTest(dispatcher) {
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1), bookmark(2)))
            coEvery { repo.setRead(any(), any()) } returns BookmarksResult.Offline
            coEvery { repo.delete(any()) } returns BookmarksResult.Offline
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.toggleRead(bookmark(1))
            advanceUntilIdle()
            val first = vm.uiState.value.snackbar!!
            vm.delete(bookmark(2))
            advanceUntilIdle()
            val second = vm.uiState.value.snackbar!!
            assertEquals(BookmarkFailure.OFFLINE, failure(vm))
            // Equal content, but a snackbar of its own: the screen shows it again.
            assertNotEquals(first, second)

            vm.dismiss(first)
            assertEquals(second, vm.uiState.value.snackbar)
            vm.dismiss(second)
            assertNull(vm.uiState.value.snackbar)

            vm.delete(bookmark(2))
            advanceUntilIdle()
            assertEquals(BookmarkFailure.OFFLINE, failure(vm))
        }

    @Test
    fun `a failed save opens the editor again with the draft, after a conflict on the reloaded version`() =
        runTest(dispatcher) {
            val draft = BookmarkDraft("Mine", "kotlin", "Long notes typed on the train")
            val theirs = bookmark(1, "Theirs", version = 5)
            val saved = bookmark(1, "Mine", tags = listOf("kotlin"), description = draft.notes, version = 6)
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1), bookmark(2)))
            coEvery { repo.save(bookmark(1), draft) } returns BookmarksResult.Offline andThen BookmarksResult.Failed(412)
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(theirs)
            coEvery { repo.save(theirs, draft) } returns BookmarksResult.Ok(saved)
            coEvery { repo.save(bookmark(2), any()) } returns BookmarksResult.Failed(404)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.edit(bookmark(1))
            vm.save(draft)
            assertNull(vm.uiState.value.editing)
            advanceUntilIdle()
            assertEquals(BookmarkFailure.OFFLINE, failure(vm))
            assertEquals(bookmark(1), vm.uiState.value.editing)
            assertEquals(draft, vm.uiState.value.draft)

            vm.save(draft)
            advanceUntilIdle()
            assertEquals(BookmarkFailure.CONFLICT, failure(vm))
            assertEquals(theirs, vm.uiState.value.editing)
            assertEquals(draft, vm.uiState.value.draft)

            vm.save(draft)
            advanceUntilIdle()
            coVerify { repo.save(theirs, draft) }
            assertNull(vm.uiState.value.editing)
            assertNull(vm.uiState.value.draft)
            assertEquals(saved, vm.uiState.value.bookmarks[0])

            // A bookmark gone meanwhile is not edited again.
            vm.edit(bookmark(2))
            vm.save(BookmarkDraft("Gone", "", ""))
            advanceUntilIdle()
            assertNull(vm.uiState.value.editing)
            assertNull(vm.uiState.value.draft)
        }

    @Test
    fun `a load that started before a change does not bring the old bookmark back`() =
        runTest(dispatcher) {
            val stale = CompletableDeferred<BookmarksResult<List<Bookmark>>>()
            var loads = 0
            coEvery { repo.list(any(), any()) } coAnswers {
                when (++loads) {
                    1 -> BookmarksResult.Ok(listOf(bookmark(1), bookmark(2)))
                    2 -> stale.await()
                    else -> BookmarksResult.Ok(listOf(bookmark(2)))
                }
            }
            coEvery { repo.delete(bookmark(1)) } returns BookmarksResult.Ok(Unit)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            // A reload (resume, a sent share) is running while the user deletes a bookmark.
            vm.refresh()
            runCurrent()
            vm.delete(bookmark(1))
            advanceUntilIdle()
            stale.complete(BookmarksResult.Ok(listOf(bookmark(1), bookmark(2))))
            advanceUntilIdle()

            assertEquals(
                listOf(id(2)),
                vm.uiState.value.bookmarks
                    .map { it.id },
            )
            assertFalse(vm.uiState.value.loading)
            coVerify(exactly = 3) { repo.list(any(), any()) }
        }

    @Test
    fun `starts with the read-later queue, then switches to all bookmarks and filters by tag`() =
        runTest(dispatcher) {
            coEvery { repo.list(BookmarkView.QUEUE, null) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.list(BookmarkView.ALL, null) } returns BookmarksResult.Ok(listOf(bookmark(1), bookmark(2)))
            coEvery { repo.list(BookmarkView.ALL, "rust") } returns BookmarksResult.Ok(listOf(bookmark(2)))
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            assertEquals(BookmarkView.QUEUE, vm.uiState.value.view)
            assertEquals(listOf(bookmark(1)), vm.uiState.value.shown)
            assertEquals(2, vm.uiState.value.tags.size)

            vm.selectView(BookmarkView.ALL)
            assertFalse(vm.uiState.value.loaded)
            advanceUntilIdle()
            assertEquals(2, vm.uiState.value.shown.size)

            vm.selectTag("rust")
            advanceUntilIdle()
            assertEquals(listOf(bookmark(2)), vm.uiState.value.shown)
            vm.selectTag("RUST")
            advanceUntilIdle()
            assertNull(vm.uiState.value.tag)
        }

    @Test
    fun `the search filters the loaded bookmarks by every word`() =
        runTest(dispatcher) {
            val list = listOf(bookmark(1, "Kotlin coroutines"), bookmark(2, "Rust book", tags = listOf("rust"), description = "ownership"))
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(list)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.search("rust OWNERSHIP")
            assertEquals(
                listOf(id(2)),
                vm.uiState.value.shown
                    .map { it.id },
            )
            vm.search("example.org/1")
            assertEquals(
                listOf(id(1)),
                vm.uiState.value.shown
                    .map { it.id },
            )
            vm.search("#rust")
            assertEquals(
                listOf(id(2)),
                vm.uiState.value.shown
                    .map { it.id },
            )
            vm.search("nothing")
            assertTrue(
                vm.uiState.value.shown
                    .isEmpty(),
            )
            vm.search("  ")
            assertEquals(2, vm.uiState.value.shown.size)
        }

    @Test
    fun `a failed load keeps the list and says why, and a pulled refresh is marked as such`() =
        runTest(dispatcher) {
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1))) andThen BookmarksResult.Offline andThen
                BookmarksResult.Failed(400)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.refresh(pulled = true)
            assertTrue(vm.uiState.value.refreshing)
            assertTrue(vm.uiState.value.loading)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.refreshing)
            assertEquals(BookmarksMessage.OFFLINE, vm.uiState.value.message)
            assertEquals(listOf(bookmark(1)), vm.uiState.value.bookmarks)

            vm.refresh()
            advanceUntilIdle()
            assertEquals(BookmarksMessage.FAILED, vm.uiState.value.message)
        }

    @Test
    fun `a list for an earlier view that answers last is dropped`() =
        runTest(dispatcher) {
            val queue = CompletableDeferred<BookmarksResult<List<Bookmark>>>()
            coEvery { repo.list(BookmarkView.QUEUE, null) } coAnswers { queue.await() }
            coEvery { repo.list(BookmarkView.ALL, null) } returns BookmarksResult.Ok(listOf(bookmark(2)))
            val vm = viewModel()
            vm.refresh()
            runCurrent()
            vm.selectView(BookmarkView.ALL)
            advanceUntilIdle()
            queue.complete(BookmarksResult.Ok(listOf(bookmark(1))))
            advanceUntilIdle()
            assertEquals(listOf(bookmark(2)), vm.uiState.value.bookmarks)
        }

    @Test
    fun `marking read in the queue drops the bookmark, in all bookmarks it stays as read`() =
        runTest(dispatcher) {
            val read = bookmark(1, readAt = Instant.EPOCH, version = 2)
            coEvery { repo.list(BookmarkView.QUEUE, null) } returns BookmarksResult.Ok(listOf(bookmark(1), bookmark(2)))
            coEvery { repo.list(BookmarkView.ALL, null) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.setRead(bookmark(1), true) } returns BookmarksResult.Ok(read)
            coEvery { repo.setRead(read, false) } returns BookmarksResult.Ok(bookmark(1, version = 3))
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.toggleRead(bookmark(1))
            assertTrue(id(1) in vm.uiState.value.busy)
            advanceUntilIdle()
            assertEquals(
                listOf(id(2)),
                vm.uiState.value.bookmarks
                    .map { it.id },
            )
            assertTrue(
                vm.uiState.value.busy
                    .isEmpty(),
            )

            vm.selectView(BookmarkView.ALL)
            coEvery { repo.list(BookmarkView.ALL, null) } returns BookmarksResult.Ok(listOf(read))
            vm.refresh()
            advanceUntilIdle()
            vm.toggleRead(read)
            advanceUntilIdle()
            assertEquals(listOf(bookmark(1, version = 3)), vm.uiState.value.bookmarks)
        }

    @Test
    fun `editing saves the draft and closes the dialog, a tag removed in the tag filter drops it`() =
        runTest(dispatcher) {
            val draft = BookmarkDraft("New", "kotlin", "notes")
            val saved = bookmark(1, "New", tags = listOf("kotlin"), description = "notes", version = 2)
            coEvery { repo.list(BookmarkView.QUEUE, "reading") } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.list(BookmarkView.QUEUE, null) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.save(bookmark(1), draft) } returns BookmarksResult.Ok(saved)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.selectTag("reading")
            advanceUntilIdle()

            vm.edit(bookmark(1))
            assertEquals(bookmark(1), vm.uiState.value.editing)
            vm.save(draft)
            assertNull(vm.uiState.value.editing)
            advanceUntilIdle()
            coVerify { repo.save(bookmark(1), draft) }
            assertTrue(
                vm.uiState.value.bookmarks
                    .isEmpty(),
            )
            coVerify(atLeast = 2) { repo.tags() }

            vm.edit(bookmark(1))
            vm.cancelEdit()
            assertNull(vm.uiState.value.editing)
        }

    @Test
    fun `a conflict reloads the bookmark and says so, a bookmark gone meanwhile is dropped`() =
        runTest(dispatcher) {
            val theirs = bookmark(1, "Theirs", version = 5)
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1), bookmark(2)))
            coEvery { repo.setRead(bookmark(1), true) } returns BookmarksResult.Failed(412)
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(theirs)
            coEvery { repo.setRead(bookmark(2), true) } returns BookmarksResult.Failed(404)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.toggleRead(bookmark(1))
            advanceUntilIdle()
            assertEquals(BookmarkFailure.CONFLICT, failure(vm))
            assertEquals(theirs, vm.uiState.value.bookmarks[0])

            vm.toggleRead(bookmark(2))
            advanceUntilIdle()
            assertEquals(
                listOf(id(1)),
                vm.uiState.value.bookmarks
                    .map { it.id },
            )
        }

    @Test
    fun `offline changes keep the bookmark and say so`() =
        runTest(dispatcher) {
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.delete(bookmark(1)) } returns BookmarksResult.Offline
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.delete(bookmark(1))
            advanceUntilIdle()
            assertEquals(BookmarkFailure.OFFLINE, failure(vm))
            assertEquals(listOf(bookmark(1)), vm.uiState.value.bookmarks)
            vm.dismiss(vm.uiState.value.snackbar!!)
            assertNull(vm.uiState.value.snackbar)
        }

    @Test
    fun `delete moves to the Trash with an undo that restores and reloads`() =
        runTest(dispatcher) {
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1), bookmark(2))) andThen
                BookmarksResult.Ok(listOf(bookmark(1), bookmark(2)))
            coEvery { repo.delete(bookmark(1)) } returns BookmarksResult.Ok(Unit)
            coEvery { repo.restore(id(1)) } returns BookmarksResult.Ok(Unit)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.delete(bookmark(1))
            advanceUntilIdle()
            assertEquals(
                listOf(id(2)),
                vm.uiState.value.bookmarks
                    .map { it.id },
            )
            val trashed = vm.uiState.value.snackbar as BookmarksSnackbar.Trashed
            assertEquals(bookmark(1), trashed.bookmark)

            vm.undo(trashed)
            assertNull(vm.uiState.value.snackbar)
            advanceUntilIdle()
            coVerify { repo.restore(id(1)) }
            assertEquals(
                listOf(id(1), id(2)),
                vm.uiState.value.bookmarks
                    .map { it.id },
            )
        }

    @Test
    fun `a failed undo says why`() =
        runTest(dispatcher) {
            coEvery { repo.restore(id(1)) } returns BookmarksResult.Offline
            val vm = viewModel()
            vm.undo(BookmarksSnackbar.Trashed(bookmark(1)))
            advanceUntilIdle()
            assertEquals(BookmarkFailure.OFFLINE, failure(vm))
        }

    @Test
    fun `a share sent by the capture queue reloads the list without restarting`() =
        runTest(dispatcher) {
            val shared = bookmark(9, "Shared article")
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1))) andThen
                BookmarksResult.Ok(listOf(shared, bookmark(1)))
            pending.value = 1
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            assertEquals(
                listOf(id(1)),
                vm.uiState.value.bookmarks
                    .map { it.id },
            )

            pending.value = 0
            advanceUntilIdle()
            assertEquals(
                listOf(id(9), id(1)),
                vm.uiState.value.bookmarks
                    .map { it.id },
            )
            coVerify(exactly = 2) { repo.list(BookmarkView.QUEUE, null) }

            // A new share waiting does not reload: the list grows only once it is sent.
            pending.value = 1
            advanceUntilIdle()
            coVerify(exactly = 2) { repo.list(BookmarkView.QUEUE, null) }
        }

    @Test
    fun `the no-browser note is shown`() {
        val vm = viewModel()
        vm.noBrowser()
        assertEquals(BookmarksSnackbar.NoBrowser, vm.uiState.value.snackbar)
    }

    @Test
    fun `a first load that fails shows nothing loaded and says why, failing tags keep the old ones`() =
        runTest(dispatcher) {
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Offline andThen BookmarksResult.Ok(listOf(bookmark(1)))
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.loaded)
            assertFalse(vm.uiState.value.loading)
            assertEquals(BookmarksMessage.OFFLINE, vm.uiState.value.message)
            assertEquals(2, vm.uiState.value.tags.size)

            coEvery { repo.tags() } returns BookmarksResult.Failed(400)
            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.loaded)
            assertNull(vm.uiState.value.message)
            assertEquals(listOf(bookmark(1)), vm.uiState.value.bookmarks)
            assertEquals(2, vm.uiState.value.tags.size)
        }

    @Test
    fun `choosing the shown view or no tag again does not reload`() =
        runTest(dispatcher) {
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.selectView(BookmarkView.QUEUE)
            vm.selectTag(null)
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.list(any(), any()) }
            assertTrue(vm.uiState.value.loaded)
        }

    @Test
    fun `a second change of a bookmark while the first runs is ignored`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<BookmarksResult<Bookmark>>()
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.setRead(any(), any()) } coAnswers { answer.await() }
            coEvery { repo.delete(any()) } returns BookmarksResult.Ok(Unit)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.toggleRead(bookmark(1))
            runCurrent()
            vm.toggleRead(bookmark(1))
            vm.delete(bookmark(1))
            runCurrent()
            answer.complete(BookmarksResult.Ok(bookmark(1, readAt = Instant.EPOCH, version = 2)))
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.setRead(any(), any()) }
            coVerify(exactly = 0) { repo.delete(any()) }
            assertTrue(
                vm.uiState.value.busy
                    .isEmpty(),
            )
        }

    @Test
    fun `after a conflict the next try sends the reloaded version, even from an outdated row`() =
        runTest(dispatcher) {
            val theirs = bookmark(1, "Theirs", version = 5)
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.setRead(bookmark(1), true) } returns BookmarksResult.Failed(409)
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(theirs)
            coEvery { repo.setRead(theirs, true) } returns BookmarksResult.Ok(theirs.copy(readAt = Instant.EPOCH, version = 6))
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.toggleRead(bookmark(1))
            advanceUntilIdle()
            assertEquals(BookmarkFailure.CONFLICT, failure(vm))

            // The row the user taps may still hold version 1: the latest loaded one is sent.
            vm.toggleRead(bookmark(1))
            advanceUntilIdle()
            coVerify { repo.setRead(theirs, true) }
            assertTrue(
                vm.uiState.value.bookmarks
                    .isEmpty(),
            )
        }

    @Test
    fun `a conflict whose bookmark is gone drops it, one that cannot be reloaded keeps it`() =
        runTest(dispatcher) {
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1), bookmark(2)))
            coEvery { repo.delete(bookmark(1)) } returns BookmarksResult.Failed(412)
            coEvery { repo.get(id(1)) } returns BookmarksResult.Failed(404)
            coEvery { repo.delete(bookmark(2)) } returns BookmarksResult.Failed(412)
            coEvery { repo.get(id(2)) } returns BookmarksResult.Offline
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.delete(bookmark(1))
            advanceUntilIdle()
            assertEquals(
                listOf(id(2)),
                vm.uiState.value.bookmarks
                    .map { it.id },
            )
            assertEquals(BookmarkFailure.CONFLICT, failure(vm))

            vm.delete(bookmark(2))
            advanceUntilIdle()
            assertEquals(listOf(bookmark(2)), vm.uiState.value.bookmarks)
        }

    @Test
    fun `a refused change says it failed, deleting a bookmark gone meanwhile just drops it`() =
        runTest(dispatcher) {
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1), bookmark(2)))
            coEvery { repo.save(bookmark(1), any()) } returns BookmarksResult.Failed(400)
            coEvery { repo.delete(bookmark(2)) } returns BookmarksResult.Failed(404)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.edit(bookmark(1))
            vm.save(BookmarkDraft("Title", "", ""))
            advanceUntilIdle()
            assertEquals(BookmarkFailure.FAILED, failure(vm))
            assertEquals(bookmark(1), vm.uiState.value.bookmarks[0])
            // Tags are reloaded only after a saved change.
            coVerify(exactly = 1) { repo.tags() }

            vm.dismiss(vm.uiState.value.snackbar!!)
            vm.delete(bookmark(2))
            advanceUntilIdle()
            assertNull(vm.uiState.value.snackbar)
            assertEquals(listOf(bookmark(1)), vm.uiState.value.bookmarks)
        }

    @Test
    fun `saving with nothing being edited does nothing`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.save(BookmarkDraft("Title", "", ""))
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.save(any(), any()) }
        }

    @Test
    fun `an edit in all bookmarks stays in place`() =
        runTest(dispatcher) {
            val draft = BookmarkDraft("New", "", "")
            val saved = bookmark(2, "New", tags = emptyList(), version = 2)
            coEvery { repo.list(BookmarkView.ALL, null) } returns BookmarksResult.Ok(listOf(bookmark(1), bookmark(2), bookmark(3)))
            coEvery { repo.save(bookmark(2), draft) } returns BookmarksResult.Ok(saved)
            val vm = viewModel()
            vm.selectView(BookmarkView.ALL)
            advanceUntilIdle()

            vm.edit(bookmark(2))
            vm.save(draft)
            advanceUntilIdle()
            assertEquals(listOf(bookmark(1), saved, bookmark(3)), vm.uiState.value.bookmarks)
        }

    @Test
    fun `dismissing an older snackbar keeps a newer one`() =
        runTest(dispatcher) {
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.delete(any()) } returns BookmarksResult.Offline
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.delete(bookmark(1))
            advanceUntilIdle()
            val older = vm.uiState.value.snackbar!!
            vm.noBrowser()

            // The first snackbar's end must not hide the second one.
            vm.dismiss(older)
            assertEquals(BookmarksSnackbar.NoBrowser, vm.uiState.value.snackbar)
            vm.dismiss(BookmarksSnackbar.NoBrowser)
            assertNull(vm.uiState.value.snackbar)
        }

    @Test
    fun `a share sent before the first load does not load the list`() =
        runTest(dispatcher) {
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            pending.value = 1
            viewModel()
            advanceUntilIdle()
            pending.value = 0
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.list(any(), any()) }
        }

    @Test
    fun `a save conflict whose reload is offline reopens the sent version, one whose bookmark is gone does not reopen`() =
        runTest(dispatcher) {
            val draft = BookmarkDraft("Mine", "kotlin", "Notes for the fictitious article")
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1), bookmark(2)))
            coEvery { repo.save(any(), draft) } returns BookmarksResult.Failed(409)
            coEvery { repo.get(id(1)) } returns BookmarksResult.Offline
            coEvery { repo.get(id(2)) } returns BookmarksResult.Failed(404)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.edit(bookmark(1))
            vm.save(draft)
            advanceUntilIdle()
            assertEquals(BookmarkFailure.CONFLICT, failure(vm))
            assertEquals(bookmark(1), vm.uiState.value.editing)
            assertEquals(draft, vm.uiState.value.draft)

            vm.cancelEdit()
            assertNull(vm.uiState.value.editing)
            assertNull(vm.uiState.value.draft)

            vm.edit(bookmark(2))
            vm.save(draft)
            advanceUntilIdle()
            assertNull(vm.uiState.value.editing)
            assertNull(vm.uiState.value.draft)
            assertEquals(
                listOf(id(1)),
                vm.uiState.value.bookmarks
                    .map { it.id },
            )
        }

    @Test
    fun `a failed save does not take over the editor of another bookmark opened meanwhile`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<BookmarksResult<Bookmark>>()
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1), bookmark(2)))
            coEvery { repo.save(bookmark(1), any()) } coAnswers { answer.await() }
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.edit(bookmark(1))
            vm.save(BookmarkDraft("Mine", "", ""))
            runCurrent()
            vm.edit(bookmark(2))
            answer.complete(BookmarksResult.Offline)
            advanceUntilIdle()

            assertEquals(BookmarkFailure.OFFLINE, failure(vm))
            assertEquals(bookmark(2), vm.uiState.value.editing)
            assertNull(vm.uiState.value.draft)
        }

    @Test
    fun `marking a bookmark read during a pulled refresh restarts the load, still marked as pulled`() =
        runTest(dispatcher) {
            val stale = CompletableDeferred<BookmarksResult<List<Bookmark>>>()
            val fresh = CompletableDeferred<BookmarksResult<List<Bookmark>>>()
            var loads = 0
            coEvery { repo.list(any(), any()) } coAnswers {
                when (++loads) {
                    1 -> BookmarksResult.Ok(listOf(bookmark(1), bookmark(2)))
                    2 -> stale.await()
                    else -> fresh.await()
                }
            }
            val read = bookmark(1, readAt = Instant.EPOCH, version = 2)
            coEvery { repo.setRead(bookmark(1), read = true) } returns BookmarksResult.Ok(read)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.refresh(pulled = true)
            runCurrent()
            vm.toggleRead(bookmark(1))
            runCurrent()
            // The read bookmark left the queue; the pulled refresh goes on with a new load.
            assertEquals(
                listOf(id(2)),
                vm.uiState.value.bookmarks
                    .map { it.id },
            )
            assertTrue(vm.uiState.value.loading)
            assertTrue(vm.uiState.value.refreshing)
            coVerify(exactly = 3) { repo.list(any(), any()) }

            stale.complete(BookmarksResult.Ok(listOf(bookmark(1), bookmark(2))))
            advanceUntilIdle()
            assertEquals(
                listOf(id(2)),
                vm.uiState.value.bookmarks
                    .map { it.id },
            )
            assertTrue(vm.uiState.value.loading)

            fresh.complete(BookmarksResult.Ok(listOf(bookmark(2), bookmark(3))))
            advanceUntilIdle()
            assertEquals(
                listOf(id(2), id(3)),
                vm.uiState.value.bookmarks
                    .map { it.id },
            )
            assertFalse(vm.uiState.value.loading)
            assertFalse(vm.uiState.value.refreshing)
        }

    @Test
    fun `a change without a running load does not start one`() =
        runTest(dispatcher) {
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1), bookmark(2)))
            coEvery { repo.delete(bookmark(1)) } returns BookmarksResult.Ok(Unit)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.delete(bookmark(1))
            advanceUntilIdle()
            assertFalse(vm.uiState.value.loading)
            coVerify(exactly = 1) { repo.list(any(), any()) }
        }

    @Test
    fun `trashing the same bookmark twice gives two snackbars`() =
        runTest(dispatcher) {
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.delete(any()) } returns BookmarksResult.Ok(Unit)
            coEvery { repo.restore(id(1)) } returns BookmarksResult.Ok(Unit)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.delete(bookmark(1))
            advanceUntilIdle()
            val first = vm.uiState.value.snackbar as BookmarksSnackbar.Trashed
            vm.undo(first)
            advanceUntilIdle()
            vm.delete(bookmark(1))
            advanceUntilIdle()
            val second = vm.uiState.value.snackbar as BookmarksSnackbar.Trashed
            assertEquals(first.bookmark, second.bookmark)
            assertNotEquals(first, second)
            // The end of the first (undone) snackbar does not clear the second.
            vm.dismiss(first)
            assertEquals(second, vm.uiState.value.snackbar)
        }
}
