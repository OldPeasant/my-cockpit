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

/** The view model's "Add bookmark" dialog, read-later toggle, "To Inbox task" and snapshot reader. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookmarksAddReaderViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<BookmarksRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun bookmark(
        n: Int,
        readLater: Boolean = true,
        readAt: Instant? = null,
        createdAt: Instant = Instant.EPOCH,
        version: Long = 1,
        snapshotFileId: UUID? = null,
    ) = Bookmark(
        id(n),
        "https://example.org/$n",
        "Page $n",
        "Page $n",
        null,
        null,
        listOf("reading"),
        readLater,
        readAt,
        createdAt,
        version,
        snapshotFileId,
    )

    private val copy = BookmarkSnapshot("<p>Slow reading</p>", truncated = false)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.tags() } returns BookmarksResult.Ok(listOf(BookmarkTag("reading", 2)))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = BookmarksViewModel(repo, MutableStateFlow(0))

    private fun failure(vm: BookmarksViewModel) = (vm.uiState.value.snackbar as BookmarksSnackbar.Failed).failure

    private fun ids(vm: BookmarksViewModel) =
        vm.uiState.value.bookmarks
            .map { it.id }

    // --- Add bookmark ---

    @Test
    fun `the add dialog presets read later in the queue but not in all bookmarks, and a second open keeps it`() =
        runTest(dispatcher) {
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(emptyList())
            val vm = viewModel()

            vm.startAdd()
            assertEquals(AddBookmarkState(NewBookmark("", readLater = true)), vm.uiState.value.adding)
            vm.cancelAdd()
            assertNull(vm.uiState.value.adding)

            vm.selectView(BookmarkView.ALL)
            advanceUntilIdle()
            vm.startAdd()
            assertEquals(AddBookmarkState(NewBookmark("", readLater = false)), vm.uiState.value.adding)

            // A draft kept after a failed save is not replaced by opening again.
            coEvery { repo.create(any()) } returns BookmarksResult.Offline
            vm.add(NewBookmark("https://example.org/typed"))
            advanceUntilIdle()
            val open = vm.uiState.value.adding
            vm.startAdd()
            assertEquals(open, vm.uiState.value.adding)
        }

    @Test
    fun `an address that is no web address is refused without asking the server and keeps the input`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.startAdd()
            val typed = NewBookmark("example.org/no-scheme", "My title", "reading", readLater = true)

            vm.add(typed)

            assertEquals(AddBookmarkState(typed, failure = AddFailure.INVALID_URL), vm.uiState.value.adding)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.create(any()) }
        }

    @Test
    fun `adding without an open dialog does nothing`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.add(NewBookmark("https://example.org/1"))
            advanceUntilIdle()
            assertNull(vm.uiState.value.adding)
            coVerify(exactly = 0) { repo.create(any()) }
        }

    @Test
    fun `a saved bookmark closes the dialog, joins the list in order and reloads the tags`() =
        runTest(dispatcher) {
            val older = bookmark(1, createdAt = Instant.parse("2026-10-01T09:00:00Z"))
            val newest = bookmark(2, createdAt = Instant.parse("2026-10-03T09:00:00Z"))
            val added = bookmark(3, createdAt = Instant.parse("2026-10-02T09:00:00Z"))
            coEvery { repo.list(BookmarkView.QUEUE, null) } returns BookmarksResult.Ok(listOf(newest, older))
            val typed = NewBookmark("https://example.org/3", readLater = true)
            val saved = CompletableDeferred<BookmarksResult<Bookmark>>()
            coEvery { repo.create(typed) } coAnswers { saved.await() }
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.startAdd()
            vm.add(typed)
            runCurrent()
            assertTrue(
                vm.uiState.value.adding!!
                    .saving,
            )
            // While it is saved, neither cancel nor a second save does anything.
            vm.cancelAdd()
            vm.add(typed)
            assertTrue(
                vm.uiState.value.adding!!
                    .saving,
            )

            saved.complete(BookmarksResult.Ok(added))
            advanceUntilIdle()

            assertNull(vm.uiState.value.adding)
            assertEquals(listOf(id(2), id(3), id(1)), ids(vm))
            coVerify(exactly = 1) { repo.create(typed) }
            coVerify(exactly = 2) { repo.tags() }
        }

    @Test
    fun `a saved bookmark that is not read later stays out of the queue`() =
        runTest(dispatcher) {
            coEvery { repo.list(BookmarkView.QUEUE, null) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.create(any()) } returns BookmarksResult.Ok(bookmark(2, readLater = false))
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.startAdd()
            vm.add(NewBookmark("https://example.org/2", readLater = false))
            advanceUntilIdle()

            assertNull(vm.uiState.value.adding)
            assertEquals(listOf(id(1)), ids(vm))
        }

    @Test
    fun `a bookmark saved before the list was loaded is not shown as the whole list`() =
        runTest(dispatcher) {
            coEvery { repo.create(any()) } returns BookmarksResult.Ok(bookmark(2))
            val vm = viewModel()

            vm.startAdd()
            vm.add(NewBookmark("https://example.org/2", readLater = true))
            advanceUntilIdle()

            assertNull(vm.uiState.value.adding)
            assertTrue(
                vm.uiState.value.bookmarks
                    .isEmpty(),
            )
        }

    @Test
    fun `a bookmark saved during a load restarts the load so it is not lost`() =
        runTest(dispatcher) {
            val stale = CompletableDeferred<BookmarksResult<List<Bookmark>>>()
            var loads = 0
            coEvery { repo.list(any(), any()) } coAnswers {
                when (++loads) {
                    1 -> BookmarksResult.Ok(listOf(bookmark(1)))
                    2 -> stale.await()
                    else -> BookmarksResult.Ok(listOf(bookmark(2), bookmark(1)))
                }
            }
            coEvery { repo.create(any()) } returns BookmarksResult.Ok(bookmark(2, createdAt = Instant.parse("2026-10-02T09:00:00Z")))
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.refresh()
            runCurrent()
            vm.startAdd()
            vm.add(NewBookmark("https://example.org/2", readLater = true))
            advanceUntilIdle()
            stale.complete(BookmarksResult.Ok(listOf(bookmark(1))))
            advanceUntilIdle()

            assertEquals(listOf(id(2), id(1)), ids(vm))
            coVerify(exactly = 3) { repo.list(any(), any()) }
        }

    @Test
    fun `a failed save keeps the dialog open with the input and the reason`() =
        runTest(dispatcher) {
            val typed = NewBookmark("https://example.org/x", "Title typed", "a b", readLater = false)
            coEvery { repo.create(typed) } returns BookmarksResult.Offline andThen BookmarksResult.Failed(400, setOf("url")) andThen
                BookmarksResult.Failed(409) andThen BookmarksResult.Failed(null)
            val vm = viewModel()
            vm.startAdd()

            vm.add(typed)
            advanceUntilIdle()
            assertEquals(AddBookmarkState(typed, failure = AddFailure.OFFLINE), vm.uiState.value.adding)

            vm.add(typed)
            advanceUntilIdle()
            // The server refused the address (e.g. a private host).
            assertEquals(AddBookmarkState(typed, failure = AddFailure.INVALID_URL), vm.uiState.value.adding)

            vm.add(typed)
            advanceUntilIdle()
            assertEquals(AddBookmarkState(typed, failure = AddFailure.FAILED), vm.uiState.value.adding)

            vm.add(typed)
            advanceUntilIdle()
            assertEquals(AddBookmarkState(typed, failure = AddFailure.FAILED), vm.uiState.value.adding)

            vm.cancelAdd()
            assertNull(vm.uiState.value.adding)
        }

    @Test
    fun `a 400 marks the field the server names, and one naming no known field is a general failure`() =
        runTest(dispatcher) {
            val typed = NewBookmark("https://example.org/x", tags = "reading")
            coEvery { repo.create(typed) } returns BookmarksResult.Failed(400, setOf("tags")) andThen
                BookmarksResult.Failed(400, setOf("title")) andThen BookmarksResult.Failed(400)
            val vm = viewModel()
            vm.startAdd()

            vm.add(typed)
            advanceUntilIdle()
            assertEquals(AddBookmarkState(typed, failure = AddFailure.INVALID_TAGS), vm.uiState.value.adding)

            vm.add(typed)
            advanceUntilIdle()
            assertEquals(AddBookmarkState(typed, failure = AddFailure.FAILED), vm.uiState.value.adding)

            vm.add(typed)
            advanceUntilIdle()
            assertEquals(AddBookmarkState(typed, failure = AddFailure.FAILED), vm.uiState.value.adding)
        }

    @Test
    fun `too many or too long tags are refused without asking the server and keep the input`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.startAdd()
            val tooLong = NewBookmark("https://example.org/1", tags = "ok ${"a".repeat(51)}")
            val tooMany = NewBookmark("https://example.org/1", tags = (1..51).joinToString(" ") { "t$it" })

            vm.add(tooLong)
            assertEquals(AddBookmarkState(tooLong, failure = AddFailure.INVALID_TAGS), vm.uiState.value.adding)
            vm.add(tooMany)
            assertEquals(AddBookmarkState(tooMany, failure = AddFailure.INVALID_TAGS), vm.uiState.value.adding)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.create(any()) }
        }

    @Test
    fun `addresses the server accepts as copied from a browser are sent to it`() =
        runTest(dispatcher) {
            coEvery { repo.create(any()) } returns BookmarksResult.Ok(bookmark(1))
            val vm = viewModel()
            val urls =
                listOf(
                    "https://münchen.example/",
                    "https://my_site.example.org/",
                    "https://example.org/search?q=a|b",
                    "https://example.org/a b",
                )

            for (url in urls) {
                vm.startAdd()
                vm.add(NewBookmark(url))
                advanceUntilIdle()
                assertNull(url, vm.uiState.value.adding)
            }
            urls.forEach { url -> coVerify(exactly = 1) { repo.create(NewBookmark(url)) } }
        }

    @Test
    fun `an add without an answer loads the list again, so a bookmark the server saved shows up`() =
        runTest(dispatcher) {
            val typed = NewBookmark("https://example.org/9")
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(emptyList()) andThen BookmarksResult.Ok(listOf(bookmark(9)))
            coEvery { repo.create(typed) } returns BookmarksResult.Offline
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.startAdd()

            vm.add(typed)
            advanceUntilIdle()
            assertEquals(
                AddFailure.OFFLINE,
                vm.uiState.value.adding
                    ?.failure,
            )
            assertEquals(listOf(bookmark(9)), vm.uiState.value.bookmarks)
            coVerify(exactly = 2) { repo.list(any(), any()) }
        }

    @Test
    fun `an add without an answer before the list was loaded, or one the server refused, does not load the list`() =
        runTest(dispatcher) {
            val typed = NewBookmark("https://example.org/9")
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(emptyList())
            coEvery { repo.create(typed) } returns BookmarksResult.Offline andThen BookmarksResult.Failed(400, setOf("url")) andThen
                BookmarksResult.Failed(409)
            val vm = viewModel()
            vm.startAdd()

            // No list yet: a reload would show a partial list as the whole one.
            vm.add(typed)
            advanceUntilIdle()
            assertEquals(
                AddFailure.OFFLINE,
                vm.uiState.value.adding
                    ?.failure,
            )
            coVerify(exactly = 0) { repo.list(any(), any()) }

            vm.refresh()
            advanceUntilIdle()
            // An answer says what happened: nothing to look up.
            vm.add(typed)
            advanceUntilIdle()
            assertEquals(
                AddFailure.INVALID_URL,
                vm.uiState.value.adding
                    ?.failure,
            )
            vm.add(typed)
            advanceUntilIdle()
            assertEquals(
                AddFailure.FAILED,
                vm.uiState.value.adding
                    ?.failure,
            )
            coVerify(exactly = 1) { repo.list(any(), any()) }
        }

    @Test
    fun `a retry after a failed save clears the old reason while it saves`() =
        runTest(dispatcher) {
            val typed = NewBookmark("https://example.org/x")
            val second = CompletableDeferred<BookmarksResult<Bookmark>>()
            var calls = 0
            coEvery { repo.create(typed) } coAnswers { if (++calls == 1) BookmarksResult.Offline else second.await() }
            val vm = viewModel()
            vm.startAdd()
            vm.add(typed)
            advanceUntilIdle()

            vm.add(typed)
            runCurrent()
            assertEquals(AddBookmarkState(typed, saving = true), vm.uiState.value.adding)
            second.complete(BookmarksResult.Ok(bookmark(9)))
            advanceUntilIdle()
            assertNull(vm.uiState.value.adding)
        }

    @Test
    fun `a saved bookmark joins a tag-filtered list only when it has the tag, regardless of case`() =
        runTest(dispatcher) {
            val cooking = bookmark(2, readLater = false).copy(tags = listOf("cooking"))
            val reading =
                bookmark(3, readLater = false, createdAt = Instant.parse("2026-10-02T09:00:00Z")).copy(tags = listOf("READING"))
            coEvery { repo.list(BookmarkView.ALL, null) } returns BookmarksResult.Ok(emptyList())
            coEvery { repo.list(BookmarkView.ALL, "reading") } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.create(NewBookmark("https://example.org/2")) } returns BookmarksResult.Ok(cooking)
            coEvery { repo.create(NewBookmark("https://example.org/3")) } returns BookmarksResult.Ok(reading)
            val vm = viewModel()
            vm.selectView(BookmarkView.ALL)
            advanceUntilIdle()
            vm.selectTag("reading")
            advanceUntilIdle()

            vm.startAdd()
            vm.add(NewBookmark("https://example.org/2"))
            advanceUntilIdle()
            assertNull(vm.uiState.value.adding)
            assertEquals(listOf(id(1)), ids(vm))

            vm.startAdd()
            vm.add(NewBookmark("https://example.org/3"))
            advanceUntilIdle()
            assertEquals(listOf(id(3), id(1)), ids(vm))
        }

    @Test
    fun `a saved bookmark the list already shows is replaced, not shown twice`() =
        runTest(dispatcher) {
            val again = bookmark(1, version = 2).copy(title = "Page 1 again", displayTitle = "Page 1 again")
            coEvery { repo.list(BookmarkView.QUEUE, null) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.create(any()) } returns BookmarksResult.Ok(again)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.startAdd()
            vm.add(NewBookmark("https://example.org/1", readLater = true))
            advanceUntilIdle()

            assertEquals(listOf(again), vm.uiState.value.bookmarks)
        }

    // --- Read later ---

    @Test
    fun `taking a bookmark out of read later drops it from the queue, putting one in keeps it in all bookmarks`() =
        runTest(dispatcher) {
            val out = bookmark(1, readLater = false, version = 2)
            coEvery { repo.list(BookmarkView.QUEUE, null) } returns BookmarksResult.Ok(listOf(bookmark(1), bookmark(2)))
            coEvery { repo.list(BookmarkView.ALL, null) } returns BookmarksResult.Ok(listOf(out))
            coEvery { repo.setReadLater(bookmark(1), false) } returns BookmarksResult.Ok(out)
            coEvery { repo.setReadLater(out, true) } returns BookmarksResult.Ok(bookmark(1, version = 3))
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.toggleReadLater(bookmark(1))
            assertTrue(id(1) in vm.uiState.value.busy)
            advanceUntilIdle()
            assertEquals(listOf(id(2)), ids(vm))
            assertTrue(
                vm.uiState.value.busy
                    .isEmpty(),
            )

            vm.selectView(BookmarkView.ALL)
            advanceUntilIdle()
            vm.toggleReadLater(out)
            advanceUntilIdle()
            assertEquals(listOf(bookmark(1, version = 3)), vm.uiState.value.bookmarks)
        }

    @Test
    fun `a failed read-later toggle keeps the bookmark and says why, a conflict reloads it`() =
        runTest(dispatcher) {
            val theirs = bookmark(1, version = 5)
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.setReadLater(bookmark(1), false) } returns BookmarksResult.Offline andThen BookmarksResult.Failed(412)
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(theirs)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.toggleReadLater(bookmark(1))
            advanceUntilIdle()
            assertEquals(BookmarkFailure.OFFLINE, failure(vm))
            assertEquals(listOf(bookmark(1)), vm.uiState.value.bookmarks)

            vm.toggleReadLater(bookmark(1))
            advanceUntilIdle()
            assertEquals(BookmarkFailure.CONFLICT, failure(vm))
            assertEquals(listOf(theirs), vm.uiState.value.bookmarks)
        }

    // --- To Inbox task ---

    @Test
    fun `to Inbox says it was added and whether it was linked, each time as a new note`() =
        runTest(dispatcher) {
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.toInbox(bookmark(1), any()) } returns BookmarksResult.Ok(true) andThen BookmarksResult.Ok(false)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.toInbox(bookmark(1))
            assertTrue(id(1) in vm.uiState.value.busy)
            advanceUntilIdle()
            val first = vm.uiState.value.snackbar as BookmarksSnackbar.AddedToInbox
            assertTrue(first.linked)
            assertTrue(
                vm.uiState.value.busy
                    .isEmpty(),
            )

            vm.toInbox(bookmark(1))
            advanceUntilIdle()
            val second = vm.uiState.value.snackbar as BookmarksSnackbar.AddedToInbox
            assertFalse(second.linked)
            assertNotEquals(first.id, second.id)
            // The bookmark itself is unchanged.
            assertEquals(listOf(bookmark(1)), vm.uiState.value.bookmarks)
        }

    @Test
    fun `a second to Inbox while the first runs makes no second task`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<BookmarksResult<Boolean>>()
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.toInbox(any(), any()) } coAnswers { answer.await() }
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.toInbox(bookmark(1))
            runCurrent()
            vm.toInbox(bookmark(1))
            answer.complete(BookmarksResult.Ok(true))
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.toInbox(any(), any()) }
        }

    @Test
    fun `to Inbox retried after a lost answer sends the same task id, a later one a new UUIDv7`() =
        runTest(dispatcher) {
            val sent = mutableListOf<UUID>()
            var calls = 0
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1), bookmark(2)))
            coEvery { repo.toInbox(any(), any()) } coAnswers {
                sent += secondArg<UUID>()
                if (++calls == 1) BookmarksResult.Offline else BookmarksResult.Ok(true)
            }
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.toInbox(bookmark(1))
            advanceUntilIdle()
            vm.toInbox(bookmark(1))
            advanceUntilIdle()
            vm.toInbox(bookmark(1))
            advanceUntilIdle()
            vm.toInbox(bookmark(2))
            advanceUntilIdle()

            assertEquals(4, sent.size)
            // The retry after offline repeats the id, so the server returns the task it may have made already.
            assertEquals(sent[0], sent[1])
            // After an answer, the next task is a new one, and each bookmark has its own.
            assertEquals(3, sent.toSet().size)
            assertTrue(sent.all { it.version() == 7 })
        }

    @Test
    fun `to Inbox keeps the task id only while the answer is unknown, any server answer ends it`() =
        runTest(dispatcher) {
            val sent = mutableListOf<UUID>()
            val answers =
                ArrayDeque(
                    listOf<BookmarksResult<Boolean>>(
                        BookmarksResult.Offline,
                        BookmarksResult.Offline,
                        BookmarksResult.Failed(409),
                        BookmarksResult.Ok(false),
                        BookmarksResult.Failed(null),
                        BookmarksResult.Ok(true),
                    ),
                )
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.toInbox(any(), any()) } coAnswers {
                sent += secondArg<UUID>()
                answers.removeFirst()
            }
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            repeat(6) {
                vm.toInbox(bookmark(1))
                advanceUntilIdle()
            }

            // Two lost answers and the refusal that follows them all carry the first id.
            assertEquals(listOf(sent[0], sent[0], sent[0]), sent.subList(0, 3))
            // After the refusal, the unlinked task and the unreadable answer, each next action is a new task.
            assertEquals(listOf(sent[0]) + sent.subList(3, 6), sent.distinct())
        }

    @Test
    fun `a failed to Inbox says why`() =
        runTest(dispatcher) {
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.toInbox(any(), any()) } returns BookmarksResult.Offline andThen BookmarksResult.Failed(400)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.toInbox(bookmark(1))
            advanceUntilIdle()
            // No answer: the task may exist, so the note does not claim that nothing was changed.
            assertTrue(vm.uiState.value.snackbar is BookmarksSnackbar.InboxOffline)
            vm.toInbox(bookmark(1))
            advanceUntilIdle()
            assertEquals(BookmarkFailure.FAILED, failure(vm))
            assertTrue(
                vm.uiState.value.busy
                    .isEmpty(),
            )
        }

    // --- Reader ---

    @Test
    fun `the reader loads the latest bookmark and its readable copy`() =
        runTest(dispatcher) {
            val fresh = bookmark(1, version = 4, snapshotFileId = id(90))
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(fresh)
            coEvery { repo.snapshot(id(90)) } returns BookmarksResult.Ok(copy)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.openReader(bookmark(1))
            assertEquals(ReaderState(bookmark(1), loading = true), vm.uiState.value.reading)
            advanceUntilIdle()

            assertEquals(ReaderState(fresh, snapshot = copy), vm.uiState.value.reading)
            // The list shows the latest version too.
            assertEquals(listOf(fresh), vm.uiState.value.bookmarks)

            vm.closeReader()
            assertNull(vm.uiState.value.reading)
        }

    @Test
    fun `a bookmark without a copy opens the reader empty without asking for one`() =
        runTest(dispatcher) {
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(bookmark(1))
            val vm = viewModel()

            vm.openReader(bookmark(1))
            advanceUntilIdle()

            assertEquals(ReaderState(bookmark(1)), vm.uiState.value.reading)
            coVerify(exactly = 0) { repo.snapshot(any()) }
        }

    @Test
    fun `a reader that cannot load says why, and retry loads it again`() =
        runTest(dispatcher) {
            val fresh = bookmark(1, snapshotFileId = id(90))
            coEvery { repo.get(id(1)) } returns BookmarksResult.Offline andThen BookmarksResult.Failed(403) andThen
                BookmarksResult.Ok(fresh)
            coEvery { repo.snapshot(id(90)) } returns BookmarksResult.Ok(copy)
            val vm = viewModel()

            vm.openReader(bookmark(1))
            advanceUntilIdle()
            assertEquals(ReaderState(bookmark(1), failure = ReaderFailure.OFFLINE), vm.uiState.value.reading)

            vm.retryReader()
            assertEquals(ReaderState(bookmark(1), loading = true), vm.uiState.value.reading)
            advanceUntilIdle()
            assertEquals(ReaderState(bookmark(1), failure = ReaderFailure.FAILED), vm.uiState.value.reading)

            vm.retryReader()
            advanceUntilIdle()
            assertEquals(ReaderState(fresh, snapshot = copy), vm.uiState.value.reading)
        }

    @Test
    fun `a copy that cannot be loaded says why, offline or failed`() =
        runTest(dispatcher) {
            val fresh = bookmark(1, snapshotFileId = id(90))
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(fresh)
            coEvery { repo.snapshot(id(90)) } returns BookmarksResult.Offline andThen BookmarksResult.Failed(404)
            val vm = viewModel()

            vm.openReader(bookmark(1))
            advanceUntilIdle()
            assertEquals(ReaderState(fresh, failure = ReaderFailure.OFFLINE), vm.uiState.value.reading)

            vm.retryReader()
            advanceUntilIdle()
            assertEquals(ReaderState(fresh, failure = ReaderFailure.FAILED), vm.uiState.value.reading)
        }

    @Test
    fun `a bookmark gone meanwhile closes the reader and leaves the list`() =
        runTest(dispatcher) {
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1), bookmark(2)))
            coEvery { repo.get(id(1)) } returns BookmarksResult.Failed(404)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()

            vm.openReader(bookmark(1))
            advanceUntilIdle()

            assertNull(vm.uiState.value.reading)
            assertEquals(listOf(id(2)), ids(vm))
        }

    @Test
    fun `retry does nothing without a reader or while it loads`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<BookmarksResult<Bookmark>>()
            coEvery { repo.get(id(1)) } coAnswers { answer.await() }
            val vm = viewModel()
            vm.retryReader()
            assertNull(vm.uiState.value.reading)

            vm.openReader(bookmark(1))
            runCurrent()
            vm.retryReader()
            vm.saveSnapshot()
            answer.complete(BookmarksResult.Ok(bookmark(1)))
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.get(id(1)) }
            coVerify(exactly = 0) { repo.saveSnapshot(any()) }
        }

    @Test
    fun `saving a copy does nothing without a reader, and retry waits while a copy is saved`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<BookmarksResult<Bookmark?>>()
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(bookmark(1))
            coEvery { repo.saveSnapshot(any()) } coAnswers { answer.await() }
            val vm = viewModel()
            vm.saveSnapshot()
            vm.cancelAdd()
            advanceUntilIdle()
            assertNull(vm.uiState.value.reading)
            assertNull(vm.uiState.value.adding)
            coVerify(exactly = 0) { repo.saveSnapshot(any()) }

            vm.openReader(bookmark(1))
            advanceUntilIdle()
            vm.saveSnapshot()
            runCurrent()
            vm.retryReader()
            answer.complete(BookmarksResult.Ok(null))
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.get(id(1)) }
            assertEquals(
                ReaderFailure.UNREADABLE,
                vm.uiState.value.reading!!
                    .failure,
            )
        }

    @Test
    fun `a reader closed while it loads is not opened again by the late answer`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<BookmarksResult<Bookmark>>()
            coEvery { repo.get(id(1)) } coAnswers { answer.await() }
            val vm = viewModel()

            vm.openReader(bookmark(1))
            runCurrent()
            vm.closeReader()
            answer.complete(BookmarksResult.Ok(bookmark(1, snapshotFileId = id(90))))
            advanceUntilIdle()

            assertNull(vm.uiState.value.reading)
            coVerify(exactly = 0) { repo.snapshot(any()) }
        }

    @Test
    fun `the copy of a bookmark read earlier does not show up in the reader of another one`() =
        runTest(dispatcher) {
            val first = CompletableDeferred<BookmarksResult<BookmarkSnapshot>>()
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(bookmark(1, snapshotFileId = id(91)))
            coEvery { repo.get(id(2)) } returns BookmarksResult.Ok(bookmark(2, snapshotFileId = id(92)))
            coEvery { repo.snapshot(id(91)) } coAnswers { first.await() }
            coEvery { repo.snapshot(id(92)) } returns BookmarksResult.Ok(copy)
            val vm = viewModel()

            vm.openReader(bookmark(1))
            runCurrent()
            vm.closeReader()
            vm.openReader(bookmark(2))
            advanceUntilIdle()
            first.complete(BookmarksResult.Ok(BookmarkSnapshot("<p>Wrong page</p>", false)))
            advanceUntilIdle()

            assertEquals(
                id(2),
                vm.uiState.value.reading!!
                    .bookmark.id,
            )
            assertEquals(
                copy,
                vm.uiState.value.reading!!
                    .snapshot,
            )
        }

    @Test
    fun `saving a copy stores it and shows it`() =
        runTest(dispatcher) {
            val saved = bookmark(1, version = 2, snapshotFileId = id(90))
            val answer = CompletableDeferred<BookmarksResult<Bookmark?>>()
            coEvery { repo.list(BookmarkView.QUEUE, null) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(bookmark(1))
            coEvery { repo.saveSnapshot(bookmark(1)) } coAnswers { answer.await() }
            coEvery { repo.snapshot(id(90)) } returns BookmarksResult.Ok(copy)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.openReader(bookmark(1))
            advanceUntilIdle()

            vm.saveSnapshot()
            assertTrue(
                vm.uiState.value.reading!!
                    .saving,
            )
            runCurrent()
            // A second save while the first runs is ignored.
            vm.saveSnapshot()
            answer.complete(BookmarksResult.Ok(saved))
            advanceUntilIdle()

            assertEquals(ReaderState(saved, snapshot = copy), vm.uiState.value.reading)
            assertEquals(listOf(saved), vm.uiState.value.bookmarks)
            coVerify(exactly = 1) { repo.saveSnapshot(any()) }
        }

    @Test
    fun `saving a copy and the other changes of the bookmark never run at the same time`() =
        runTest(dispatcher) {
            val saving = CompletableDeferred<BookmarksResult<Bookmark?>>()
            val toggling = CompletableDeferred<BookmarksResult<Bookmark>>()
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(bookmark(1))
            coEvery { repo.saveSnapshot(any()) } coAnswers { saving.await() }
            coEvery { repo.setRead(any(), any()) } coAnswers { toggling.await() }
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.openReader(bookmark(1))
            advanceUntilIdle()

            // A copy is being saved: the bookmark is busy, so marking it read waits.
            vm.saveSnapshot()
            assertTrue(id(1) in vm.uiState.value.busy)
            runCurrent()
            vm.toggleRead(bookmark(1))
            saving.complete(BookmarksResult.Ok(null))
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.setRead(any(), any()) }
            assertTrue(
                vm.uiState.value.busy
                    .isEmpty(),
            )

            // Marking it read runs: saving a copy waits.
            vm.toggleRead(bookmark(1))
            runCurrent()
            vm.saveSnapshot()
            toggling.complete(BookmarksResult.Ok(bookmark(1, readAt = Instant.EPOCH, version = 2)))
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.saveSnapshot(any()) }
            assertFalse(
                vm.uiState.value.reading!!
                    .saving,
            )
        }

    @Test
    fun `leaving the reader while a copy is saved lets the save finish and puts the new version into the list`() =
        runTest(dispatcher) {
            val saving = CompletableDeferred<BookmarksResult<Bookmark?>>()
            val saved = bookmark(1, version = 2, snapshotFileId = id(90))
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(bookmark(1))
            coEvery { repo.saveSnapshot(any()) } coAnswers { saving.await() }
            coEvery { repo.setReadLater(any(), any()) } returns BookmarksResult.Ok(bookmark(1, readLater = false, version = 3))
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.openReader(bookmark(1))
            advanceUntilIdle()

            vm.saveSnapshot()
            runCurrent()
            assertTrue(id(1) in vm.uiState.value.busy)
            vm.closeReader()
            advanceUntilIdle()
            assertNull(vm.uiState.value.reading)

            // The save still runs: the bookmark stays busy, so no change goes out with the old version.
            assertTrue(id(1) in vm.uiState.value.busy)
            vm.toggleReadLater(bookmark(1))
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.setReadLater(any(), any()) }

            saving.complete(BookmarksResult.Ok(saved))
            advanceUntilIdle()
            assertEquals(listOf(saved), vm.uiState.value.bookmarks)
            assertTrue(
                vm.uiState.value.busy
                    .isEmpty(),
            )
            assertNull(vm.uiState.value.reading)
            coVerify(exactly = 0) { repo.snapshot(any()) }

            // The next change from the list sends the saved version, not the one from before the save.
            vm.toggleReadLater(bookmark(1))
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.setReadLater(saved, false) }
            assertNull(vm.uiState.value.snackbar)
        }

    @Test
    fun `a reader reopened while its copy is saved shows the save until it ends`() =
        runTest(dispatcher) {
            val saving = CompletableDeferred<BookmarksResult<Bookmark?>>()
            val saved = bookmark(1, version = 2, snapshotFileId = id(90))
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1), bookmark(2)))
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(bookmark(1))
            coEvery { repo.get(id(2)) } returns BookmarksResult.Ok(bookmark(2))
            coEvery { repo.saveSnapshot(any()) } coAnswers { saving.await() }
            coEvery { repo.snapshot(id(90)) } returns BookmarksResult.Ok(copy)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.openReader(bookmark(1))
            advanceUntilIdle()

            vm.saveSnapshot()
            runCurrent()
            vm.closeReader()
            // Another bookmark's reader is not saving.
            vm.openReader(bookmark(2))
            advanceUntilIdle()
            assertEquals(ReaderState(bookmark(2)), vm.uiState.value.reading)

            vm.openReader(bookmark(1))
            advanceUntilIdle()
            // Its own load is done, but the save still runs, so the reader says so.
            assertEquals(ReaderState(bookmark(1), saving = true), vm.uiState.value.reading)

            saving.complete(BookmarksResult.Ok(saved))
            advanceUntilIdle()
            assertEquals(ReaderState(saved, snapshot = copy), vm.uiState.value.reading)
            assertTrue(
                vm.uiState.value.busy
                    .isEmpty(),
            )

            // A reader opened after the save is not saving.
            vm.closeReader()
            vm.openReader(saved)
            assertEquals(ReaderState(saved, loading = true), vm.uiState.value.reading)
        }

    @Test
    fun `a reader reopened while its copy is saved stops showing the save when it fails`() =
        runTest(dispatcher) {
            val saving = CompletableDeferred<BookmarksResult<Bookmark?>>()
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(bookmark(1))
            coEvery { repo.saveSnapshot(any()) } coAnswers { saving.await() }
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.openReader(bookmark(1))
            advanceUntilIdle()

            vm.saveSnapshot()
            runCurrent()
            vm.closeReader()
            vm.openReader(bookmark(1))
            advanceUntilIdle()
            assertEquals(ReaderState(bookmark(1), saving = true), vm.uiState.value.reading)

            saving.complete(BookmarksResult.Ok(null))
            advanceUntilIdle()
            assertEquals(ReaderState(bookmark(1), failure = ReaderFailure.UNREADABLE), vm.uiState.value.reading)
        }

    @Test
    fun `a reader reopened while its copy is saved shows the new copy, not the older version its own load answers late`() =
        runTest(dispatcher) {
            val saving = CompletableDeferred<BookmarksResult<Bookmark?>>()
            val reopened = CompletableDeferred<BookmarksResult<Bookmark>>()
            val saved = bookmark(1, version = 2, snapshotFileId = id(90))
            var gets = 0
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            // The first open loads at once; the reopened reader's load answers only after the save.
            coEvery { repo.get(id(1)) } coAnswers { if (++gets == 1) BookmarksResult.Ok(bookmark(1)) else reopened.await() }
            coEvery { repo.saveSnapshot(any()) } coAnswers { saving.await() }
            coEvery { repo.snapshot(id(90)) } returns BookmarksResult.Ok(copy)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.openReader(bookmark(1))
            advanceUntilIdle()

            vm.saveSnapshot()
            runCurrent()
            vm.closeReader()
            vm.openReader(bookmark(1))
            runCurrent()
            assertEquals(ReaderState(bookmark(1), loading = true, saving = true), vm.uiState.value.reading)

            saving.complete(BookmarksResult.Ok(saved))
            advanceUntilIdle()
            assertEquals(ReaderState(saved, snapshot = copy), vm.uiState.value.reading)

            // The older version answered late does not replace the saved one, in the reader or the list.
            reopened.complete(BookmarksResult.Ok(bookmark(1)))
            advanceUntilIdle()
            assertEquals(ReaderState(saved, snapshot = copy), vm.uiState.value.reading)
            assertEquals(listOf(saved), vm.uiState.value.bookmarks)
            assertTrue(
                vm.uiState.value.busy
                    .isEmpty(),
            )
        }

    @Test
    fun `a reader reopened while its copy is saved retries its failed load only after the save ended`() =
        runTest(dispatcher) {
            val saving = CompletableDeferred<BookmarksResult<Bookmark?>>()
            var gets = 0
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            // The first open loads; the reopened reader is offline; its retry loads again.
            coEvery { repo.get(id(1)) } coAnswers {
                if (++gets == 2) BookmarksResult.Offline else BookmarksResult.Ok(bookmark(1))
            }
            coEvery { repo.saveSnapshot(any()) } coAnswers { saving.await() }
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.openReader(bookmark(1))
            advanceUntilIdle()

            vm.saveSnapshot()
            runCurrent()
            vm.closeReader()
            vm.openReader(bookmark(1))
            advanceUntilIdle()
            assertEquals(
                ReaderState(bookmark(1), saving = true, failure = ReaderFailure.OFFLINE),
                vm.uiState.value.reading,
            )

            // While the save runs, a retry asks nothing.
            vm.retryReader()
            advanceUntilIdle()
            assertEquals(2, gets)

            saving.complete(BookmarksResult.Offline)
            advanceUntilIdle()
            assertEquals(BookmarkFailure.OFFLINE, failure(vm))
            assertEquals(ReaderState(bookmark(1), failure = ReaderFailure.OFFLINE), vm.uiState.value.reading)
            assertTrue(
                vm.uiState.value.busy
                    .isEmpty(),
            )

            vm.retryReader()
            advanceUntilIdle()
            assertEquals(3, gets)
            assertEquals(ReaderState(bookmark(1)), vm.uiState.value.reading)
        }

    @Test
    fun `a save that fails after the reader was left does not close a reader opened on another bookmark`() =
        runTest(dispatcher) {
            val saving = CompletableDeferred<BookmarksResult<Bookmark?>>()
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1), bookmark(2)))
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(bookmark(1))
            coEvery { repo.get(id(2)) } returns BookmarksResult.Ok(bookmark(2))
            coEvery { repo.saveSnapshot(bookmark(1)) } coAnswers { saving.await() }
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.openReader(bookmark(1))
            advanceUntilIdle()

            vm.saveSnapshot()
            runCurrent()
            vm.closeReader()
            vm.openReader(bookmark(2))
            advanceUntilIdle()
            // Bookmark 1 is gone meanwhile.
            saving.complete(BookmarksResult.Failed(BookmarksRepository.NOT_FOUND))
            advanceUntilIdle()

            assertEquals(ReaderState(bookmark(2)), vm.uiState.value.reading)
            assertEquals(listOf(bookmark(2)), vm.uiState.value.bookmarks)
        }

    @Test
    fun `a copy saved after the reader was left does not reopen it or change the reader of another bookmark`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<BookmarksResult<Bookmark?>>()
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(bookmark(1))
            coEvery { repo.get(id(2)) } returns BookmarksResult.Ok(bookmark(2))
            coEvery { repo.saveSnapshot(bookmark(1)) } coAnswers { answer.await() }
            val vm = viewModel()
            vm.openReader(bookmark(1))
            advanceUntilIdle()

            vm.saveSnapshot()
            runCurrent()
            vm.closeReader()
            answer.complete(BookmarksResult.Ok(bookmark(1, version = 2, snapshotFileId = id(90))))
            advanceUntilIdle()
            assertNull(vm.uiState.value.reading)

            vm.openReader(bookmark(2))
            advanceUntilIdle()
            assertEquals(ReaderState(bookmark(2)), vm.uiState.value.reading)
            coVerify(exactly = 0) { repo.snapshot(any()) }
        }

    @Test
    fun `a copy saved while the reader shows another bookmark updates the list and does not stop that reader's load`() =
        runTest(dispatcher) {
            val saving = CompletableDeferred<BookmarksResult<Bookmark?>>()
            val otherCopy = CompletableDeferred<BookmarksResult<BookmarkSnapshot>>()
            val saved = bookmark(1, version = 2, snapshotFileId = id(90))
            val other = bookmark(2, snapshotFileId = id(92))
            val news = BookmarkSnapshot("<p>Other page</p>", truncated = false)
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1), other))
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(bookmark(1))
            coEvery { repo.get(id(2)) } returns BookmarksResult.Ok(other)
            coEvery { repo.saveSnapshot(bookmark(1)) } coAnswers { saving.await() }
            coEvery { repo.snapshot(id(92)) } coAnswers { otherCopy.await() }
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.openReader(bookmark(1))
            advanceUntilIdle()

            vm.saveSnapshot()
            runCurrent()
            vm.closeReader()
            vm.openReader(other)
            runCurrent()
            // The copy of bookmark 2 is still loading when the save of bookmark 1 answers.
            saving.complete(BookmarksResult.Ok(saved))
            advanceUntilIdle()
            otherCopy.complete(BookmarksResult.Ok(news))
            advanceUntilIdle()

            assertEquals(ReaderState(other, snapshot = news), vm.uiState.value.reading)
            assertEquals(listOf(saved, other), vm.uiState.value.bookmarks)
            coVerify(exactly = 0) { repo.snapshot(id(90)) }
        }

    @Test
    fun `a reader opened again on the bookmark while its copy is saved shows the new copy once it is saved`() =
        runTest(dispatcher) {
            val saving = CompletableDeferred<BookmarksResult<Bookmark?>>()
            val saved = bookmark(1, version = 2, snapshotFileId = id(90))
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(bookmark(1))
            coEvery { repo.saveSnapshot(any()) } coAnswers { saving.await() }
            coEvery { repo.snapshot(id(90)) } returns BookmarksResult.Ok(copy)
            val vm = viewModel()
            vm.openReader(bookmark(1))
            advanceUntilIdle()

            vm.saveSnapshot()
            runCurrent()
            vm.closeReader()
            vm.openReader(bookmark(1))
            advanceUntilIdle()
            // The reopened reader shows that the first save still runs.
            assertEquals(ReaderState(bookmark(1), saving = true), vm.uiState.value.reading)

            // The first save still runs: a second one would go out with the old version.
            vm.saveSnapshot()
            runCurrent()
            coVerify(exactly = 1) { repo.saveSnapshot(any()) }

            saving.complete(BookmarksResult.Ok(saved))
            advanceUntilIdle()
            assertEquals(ReaderState(saved, snapshot = copy), vm.uiState.value.reading)
            assertTrue(
                vm.uiState.value.busy
                    .isEmpty(),
            )
        }

    @Test
    fun `leaving the reader while the new copy loads stops the load and keeps the saved version in the list`() =
        runTest(dispatcher) {
            val saved = bookmark(1, version = 2, snapshotFileId = id(90))
            val loading = CompletableDeferred<BookmarksResult<BookmarkSnapshot>>()
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(bookmark(1))
            coEvery { repo.saveSnapshot(bookmark(1)) } returns BookmarksResult.Ok(saved)
            coEvery { repo.snapshot(id(90)) } coAnswers { loading.await() }
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.openReader(bookmark(1))
            advanceUntilIdle()

            vm.saveSnapshot()
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.reading!!
                    .loading,
            )
            vm.closeReader()
            loading.complete(BookmarksResult.Ok(copy))
            advanceUntilIdle()

            assertNull(vm.uiState.value.reading)
            assertEquals(listOf(saved), vm.uiState.value.bookmarks)
            assertTrue(
                vm.uiState.value.busy
                    .isEmpty(),
            )
        }

    @Test
    fun `a save refused as outdated after the reader was left loads the latest version into the list`() =
        runTest(dispatcher) {
            val saving = CompletableDeferred<BookmarksResult<Bookmark?>>()
            val theirs = bookmark(1, version = 5)
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(bookmark(1)) andThen BookmarksResult.Ok(theirs)
            coEvery { repo.saveSnapshot(any()) } coAnswers { saving.await() }
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.openReader(bookmark(1))
            advanceUntilIdle()

            vm.saveSnapshot()
            runCurrent()
            vm.closeReader()
            saving.complete(BookmarksResult.Failed(412))
            advanceUntilIdle()

            assertEquals(BookmarkFailure.CONFLICT, failure(vm))
            assertEquals(listOf(theirs), vm.uiState.value.bookmarks)
            assertNull(vm.uiState.value.reading)
            assertTrue(
                vm.uiState.value.busy
                    .isEmpty(),
            )
        }

    @Test
    fun `a new copy that cannot be loaded after saving says why and keeps the saved bookmark`() =
        runTest(dispatcher) {
            val saved = bookmark(1, version = 2, snapshotFileId = id(90))
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(bookmark(1))
            coEvery { repo.saveSnapshot(bookmark(1)) } returns BookmarksResult.Ok(saved)
            coEvery { repo.snapshot(id(90)) } returns BookmarksResult.Offline
            val vm = viewModel()
            vm.openReader(bookmark(1))
            advanceUntilIdle()

            vm.saveSnapshot()
            advanceUntilIdle()

            assertEquals(ReaderState(saved, failure = ReaderFailure.OFFLINE), vm.uiState.value.reading)
        }

    @Test
    fun `a page the server cannot read says so and keeps the earlier copy`() =
        runTest(dispatcher) {
            val withCopy = bookmark(1, snapshotFileId = id(90))
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(withCopy)
            coEvery { repo.snapshot(id(90)) } returns BookmarksResult.Ok(copy)
            coEvery { repo.saveSnapshot(withCopy) } returns BookmarksResult.Ok(null)
            val vm = viewModel()
            vm.openReader(withCopy)
            advanceUntilIdle()

            vm.saveSnapshot()
            advanceUntilIdle()

            assertEquals(ReaderState(withCopy, snapshot = copy, failure = ReaderFailure.UNREADABLE), vm.uiState.value.reading)
        }

    @Test
    fun `a refused copy says why in a snackbar, a conflict reloads the bookmark of the reader`() =
        runTest(dispatcher) {
            val theirs = bookmark(1, version = 5)
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(bookmark(1)) andThen BookmarksResult.Ok(theirs)
            coEvery { repo.saveSnapshot(bookmark(1)) } returns BookmarksResult.Offline andThen BookmarksResult.Failed(412)
            val vm = viewModel()
            vm.openReader(bookmark(1))
            advanceUntilIdle()

            vm.saveSnapshot()
            advanceUntilIdle()
            assertEquals(BookmarkFailure.OFFLINE, failure(vm))
            assertEquals(ReaderState(bookmark(1)), vm.uiState.value.reading)

            vm.saveSnapshot()
            advanceUntilIdle()
            assertEquals(BookmarkFailure.CONFLICT, failure(vm))
            assertEquals(ReaderState(theirs), vm.uiState.value.reading)
        }

    @Test
    fun `saving a copy of a bookmark gone meanwhile closes the reader and leaves the list`() =
        runTest(dispatcher) {
            coEvery { repo.list(any(), any()) } returns BookmarksResult.Ok(listOf(bookmark(1), bookmark(2)))
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(bookmark(1))
            coEvery { repo.saveSnapshot(any()) } returns BookmarksResult.Failed(404)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.openReader(bookmark(1))
            advanceUntilIdle()

            vm.saveSnapshot()
            advanceUntilIdle()

            assertNull(vm.uiState.value.reading)
            assertEquals(listOf(id(2)), ids(vm))
        }

    @Test
    fun `changes in the reader use its version, also after the bookmark left the queue`() =
        runTest(dispatcher) {
            val read = bookmark(1, readAt = Instant.EPOCH, version = 2)
            coEvery { repo.list(BookmarkView.QUEUE, null) } returns BookmarksResult.Ok(listOf(bookmark(1)))
            coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(bookmark(1))
            coEvery { repo.setRead(bookmark(1), true) } returns BookmarksResult.Ok(read)
            coEvery { repo.setReadLater(read, false) } returns BookmarksResult.Ok(read.copy(readLater = false, version = 3))
            coEvery { repo.toInbox(read.copy(readLater = false, version = 3), any()) } returns BookmarksResult.Ok(true)
            val vm = viewModel()
            vm.refresh()
            advanceUntilIdle()
            vm.openReader(bookmark(1))
            advanceUntilIdle()

            // Read: it leaves the queue, the reader stays on it.
            vm.toggleRead(bookmark(1))
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.bookmarks
                    .isEmpty(),
            )
            assertEquals(
                read,
                vm.uiState.value.reading!!
                    .bookmark,
            )

            // The screen passes what it shows; the latest version (2) is sent, not the one the reader opened with.
            vm.toggleReadLater(bookmark(1))
            advanceUntilIdle()
            assertEquals(
                3L,
                vm.uiState.value.reading!!
                    .bookmark.version,
            )
            assertFalse(
                vm.uiState.value.reading!!
                    .bookmark.readLater,
            )

            vm.toInbox(bookmark(1))
            advanceUntilIdle()
            assertEquals(true, (vm.uiState.value.snackbar as BookmarksSnackbar.AddedToInbox).linked)
        }
}
