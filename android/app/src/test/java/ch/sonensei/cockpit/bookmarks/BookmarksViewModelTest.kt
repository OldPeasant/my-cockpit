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
            assertEquals(BookmarksSnackbar.Failed(BookmarkFailure.CONFLICT), vm.uiState.value.snackbar)
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
            assertEquals(BookmarksSnackbar.Failed(BookmarkFailure.OFFLINE), vm.uiState.value.snackbar)
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
            assertEquals(BookmarksSnackbar.Failed(BookmarkFailure.OFFLINE), vm.uiState.value.snackbar)
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
}
