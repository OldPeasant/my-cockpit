package ch.sonensei.cockpit.core.links

import ch.sonensei.cockpit.search.SearchGroup
import ch.sonensei.cockpit.search.SearchHit
import ch.sonensei.cockpit.search.SearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** The linked-items panel's state: load, debounced search, add, remove, create linked, conflicts and failures. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkedItemsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val serverUrl = MutableStateFlow<HttpUrl?>("https://cockpit.example.org/".toHttpUrl())
    private val source = FakeLinks()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val task = ItemRef("TASK", id(1))
    private val contact = ItemRef("CONTACT", id(2))

    private fun link(
        n: Int,
        type: String = "NOTE",
        title: String = "Linked $n",
        manual: Boolean = true,
    ) = LinkedItem(
        linkId = id(100 + n),
        type = type,
        itemId = id(n),
        title = title,
        subtitle = null,
        deepLink = "/${type.lowercase()}s/${id(n)}",
        available = true,
        manual = manual,
    )

    private fun hit(
        n: Int,
        type: String = "NOTE",
        title: String = "Hit $n",
    ) = SearchHit(id(n), type, title, null, "/${type.lowercase()}s/${id(n)}")

    private fun vm() = LinkedItemsViewModel(source, serverUrl, debounceMillis = DEBOUNCE)

    private fun TestScope.opened(
        item: ItemRef = task,
        links: List<LinkedItem> = emptyList(),
    ): LinkedItemsViewModel {
        source.onList = { LinksResult.Ok(links) }
        val vm = vm()
        vm.open(item)
        advanceUntilIdle()
        return vm
    }

    private fun TestScope.passDebounce() {
        advanceTimeBy(DEBOUNCE)
        runCurrent()
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // --- load ---

    @Test
    fun `opening an item loads its links, showing loading until they arrive`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<LinksResult<List<LinkedItem>>>()
            source.onList = { answer.await() }
            val vm = vm()

            vm.open(task)
            runCurrent()
            assertEquals(task, vm.uiState.value.item)
            assertNull("loading", vm.uiState.value.links)
            assertEquals(listOf("list TASK ${id(1)}"), source.calls)

            answer.complete(LinksResult.Ok(listOf(link(3))))
            advanceUntilIdle()

            assertEquals(listOf(link(3)), vm.uiState.value.links)
            assertNull(vm.uiState.value.loadError)
        }

    @Test
    fun `the configured server is offered for web links and follows changes`() =
        runTest(dispatcher) {
            val vm = opened()
            assertEquals("https://cockpit.example.org/".toHttpUrl(), vm.uiState.value.server)

            serverUrl.value = null
            runCurrent()
            assertNull(vm.uiState.value.server)
        }

    @Test
    fun `opening the same item again keeps what is shown without loading again`() =
        runTest(dispatcher) {
            val vm = opened(links = listOf(link(3)))

            vm.open(task)
            advanceUntilIdle()

            assertEquals(1, source.calls.count { it.startsWith("list") })
            assertEquals(listOf(link(3)), vm.uiState.value.links)
        }

    @Test
    fun `opening another item starts over and keeps the server`() =
        runTest(dispatcher) {
            val vm = opened(links = listOf(link(3)))
            vm.openSearch()
            vm.startCreate(LinkedCreateKind.Note)
            source.onList = { LinksResult.Ok(listOf(link(4))) }

            vm.open(contact)
            runCurrent()
            val state = vm.uiState.value
            assertEquals(contact, state.item)
            assertFalse(state.adding)
            assertNull(state.creating)
            assertEquals("https://cockpit.example.org/".toHttpUrl(), state.server)
            advanceUntilIdle()

            assertEquals(listOf(link(4)), vm.uiState.value.links)
            assertEquals("list CONTACT ${id(2)}", source.calls.last())
        }

    @Test
    fun `an offline load says so and a failed one says it failed`() =
        runTest(dispatcher) {
            source.onList = { LinksResult.Offline }
            val vm = vm()
            vm.open(task)
            advanceUntilIdle()
            assertEquals(LinksError.Offline, vm.uiState.value.loadError)
            assertNull(vm.uiState.value.links)

            source.onList = { LinksResult.Failed(400) }
            vm.reload()
            advanceUntilIdle()
            assertEquals(LinksError.Failed, vm.uiState.value.loadError)
        }

    @Test
    fun `retrying a failed load clears the error and shows the links`() =
        runTest(dispatcher) {
            source.onList = { LinksResult.Conflict }
            val vm = vm()
            vm.open(task)
            advanceUntilIdle()
            assertEquals(LinksError.Failed, vm.uiState.value.loadError)

            val answer = CompletableDeferred<LinksResult<List<LinkedItem>>>()
            source.onList = { answer.await() }
            vm.reload()
            runCurrent()
            assertNull("the error is gone while retrying", vm.uiState.value.loadError)

            answer.complete(LinksResult.Ok(listOf(link(3))))
            advanceUntilIdle()
            assertEquals(listOf(link(3)), vm.uiState.value.links)
        }

    @Test
    fun `an older load answering after a reload does not replace the newer answer`() =
        runTest(dispatcher) {
            val older = CompletableDeferred<LinksResult<List<LinkedItem>>>()
            val newer = CompletableDeferred<LinksResult<List<LinkedItem>>>()
            source.onList = { older.await() }
            val vm = vm()
            vm.open(task)
            runCurrent()
            source.onList = { newer.await() }
            vm.reload()
            runCurrent()

            newer.complete(LinksResult.Ok(listOf(link(4))))
            runCurrent()
            older.complete(LinksResult.Ok(listOf(link(3))))
            advanceUntilIdle()

            assertEquals(listOf(link(4)), vm.uiState.value.links)
        }

    @Test
    fun `reload without an item does nothing`() =
        runTest(dispatcher) {
            val vm = vm()
            vm.reload()
            advanceUntilIdle()
            assertTrue(source.calls.isEmpty())
        }

    // --- remove ---

    @Test
    fun `removing a manual link marks it while running, then drops it`() =
        runTest(dispatcher) {
            val vm = opened(links = listOf(link(3), link(4)))
            val answer = CompletableDeferred<LinksResult<Unit>>()
            source.onRemove = { answer.await() }

            vm.remove(link(3))
            runCurrent()
            assertEquals(setOf(link(3).linkId), vm.uiState.value.removing)
            assertEquals("remove ${link(3).linkId}", source.calls.last())

            answer.complete(LinksResult.Ok(Unit))
            advanceUntilIdle()
            assertEquals(listOf(link(4)), vm.uiState.value.links)
            assertTrue(
                vm.uiState.value.removing
                    .isEmpty(),
            )
            assertNull(vm.uiState.value.actionError)
        }

    @Test
    fun `a sync link is not removed here`() =
        runTest(dispatcher) {
            val sync = link(3, manual = false)
            val vm = opened(links = listOf(sync))

            vm.remove(sync)
            advanceUntilIdle()

            assertTrue(source.calls.none { it.startsWith("remove") })
            assertEquals(listOf(sync), vm.uiState.value.links)
        }

    @Test
    fun `tapping remove twice while it runs removes once`() =
        runTest(dispatcher) {
            val vm = opened(links = listOf(link(3)))
            val answer = CompletableDeferred<LinksResult<Unit>>()
            source.onRemove = { answer.await() }

            vm.remove(link(3))
            runCurrent()
            vm.remove(link(3))
            answer.complete(LinksResult.Ok(Unit))
            advanceUntilIdle()

            assertEquals(1, source.calls.count { it.startsWith("remove") })
            assertEquals(emptyList<LinkedItem>(), vm.uiState.value.links)
        }

    @Test
    fun `a failed removal keeps the link and says why`() =
        runTest(dispatcher) {
            val vm = opened(links = listOf(link(3)))

            source.onRemove = { LinksResult.Offline }
            vm.remove(link(3))
            advanceUntilIdle()
            assertEquals(LinksError.Offline, vm.uiState.value.actionError)
            assertEquals(listOf(link(3)), vm.uiState.value.links)
            assertTrue(
                vm.uiState.value.removing
                    .isEmpty(),
            )

            source.onRemove = { LinksResult.Failed(409) }
            vm.remove(link(3))
            assertNull("a new attempt clears the old error", vm.uiState.value.actionError)
            advanceUntilIdle()
            assertEquals(LinksError.Failed, vm.uiState.value.actionError)

            vm.dismissError()
            assertNull(vm.uiState.value.actionError)
        }

    @Test
    fun `a removal finishing after another item was opened is ignored`() =
        runTest(dispatcher) {
            val vm = opened(links = listOf(link(3)))
            val answer = CompletableDeferred<LinksResult<Unit>>()
            source.onRemove = { answer.await() }
            vm.remove(link(3))
            runCurrent()

            source.onList = { LinksResult.Ok(listOf(link(3))) }
            vm.open(contact)
            advanceUntilIdle()
            answer.complete(LinksResult.Offline)
            advanceUntilIdle()

            assertEquals(listOf(link(3)), vm.uiState.value.links)
            assertNull(vm.uiState.value.actionError)
            assertTrue(
                vm.uiState.value.removing
                    .isEmpty(),
            )
        }

    // --- search ---

    @Test
    fun `typing searches once the pause is over, trimmed, for the last text only`() =
        runTest(dispatcher) {
            val vm = opened()
            vm.openSearch()
            assertTrue(vm.uiState.value.adding)
            source.onSearch = { SearchResult.Ok(listOf(SearchGroup("NOTE", listOf(hit(5))))) }

            vm.search("p")
            advanceTimeBy(100)
            vm.search(" plan")
            advanceTimeBy(DEBOUNCE - 1)
            runCurrent()
            assertTrue(source.calls.none { it.startsWith("search") })
            assertTrue("searching while the pause runs", vm.uiState.value.searching)

            advanceTimeBy(1)
            runCurrent()
            advanceUntilIdle()

            assertEquals(listOf("search plan"), source.calls.filter { it.startsWith("search") })
            val state = vm.uiState.value
            assertEquals(" plan", state.query)
            assertEquals("plan", state.resultsQuery)
            assertEquals(listOf(SearchGroup("NOTE", listOf(hit(5)))), state.results)
            assertFalse(state.searching)
        }

    @Test
    fun `the results leave out the item itself and the items it is linked to`() =
        runTest(dispatcher) {
            val vm = opened(links = listOf(link(3, type = "NOTE")))
            source.onSearch = {
                SearchResult.Ok(
                    listOf(
                        SearchGroup("TASK", listOf(hit(1, type = "TASK"), hit(6, type = "TASK"))),
                        SearchGroup("NOTE", listOf(hit(3, type = "NOTE"))),
                        // Same id, other type: a different item, so it stays.
                        SearchGroup("EVENT", listOf(hit(3, type = "EVENT"))),
                    ),
                )
            }

            vm.search("plan")
            advanceUntilIdle()

            assertEquals(
                listOf(
                    SearchGroup("TASK", listOf(hit(6, type = "TASK"))),
                    SearchGroup("EVENT", listOf(hit(3, type = "EVENT"))),
                ),
                vm.uiState.value.results,
            )
        }

    @Test
    fun `a removed link's item comes back into the results`() =
        runTest(dispatcher) {
            val vm = opened(links = listOf(link(3)))
            source.onSearch = { SearchResult.Ok(listOf(SearchGroup("NOTE", listOf(hit(3))))) }
            vm.search("plan")
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.results
                    .isEmpty(),
            )

            vm.remove(link(3))
            advanceUntilIdle()

            assertEquals(listOf(SearchGroup("NOTE", listOf(hit(3)))), vm.uiState.value.results)
        }

    @Test
    fun `a blank text clears the results without searching`() =
        runTest(dispatcher) {
            val vm = opened()
            source.onSearch = { SearchResult.Ok(listOf(SearchGroup("NOTE", listOf(hit(5))))) }
            vm.search("plan")
            advanceUntilIdle()

            vm.search("   ")
            advanceUntilIdle()

            assertEquals(1, source.calls.count { it.startsWith("search") })
            assertTrue(
                vm.uiState.value.results
                    .isEmpty(),
            )
            assertEquals("", vm.uiState.value.resultsQuery)
            assertFalse(vm.uiState.value.searching)
        }

    @Test
    fun `clearing the text while a search runs drops its answer`() =
        runTest(dispatcher) {
            val vm = opened()
            val answer = CompletableDeferred<SearchResult>()
            source.onSearch = { answer.await() }
            vm.search("plan")
            passDebounce()

            vm.search("")
            answer.complete(SearchResult.Ok(listOf(SearchGroup("NOTE", listOf(hit(5))))))
            advanceUntilIdle()

            assertTrue(
                vm.uiState.value.results
                    .isEmpty(),
            )
            assertEquals("", vm.uiState.value.resultsQuery)
        }

    @Test
    fun `the same text again does not search again`() =
        runTest(dispatcher) {
            val vm = opened()
            source.onSearch = { SearchResult.Ok(emptyList()) }
            vm.search("plan")
            advanceUntilIdle()

            vm.search("plan ")
            advanceUntilIdle()

            assertEquals(1, source.calls.count { it.startsWith("search") })
        }

    @Test
    fun `typing back to the shown text while a newer search waits keeps the shown results`() =
        runTest(dispatcher) {
            val vm = opened()
            source.onSearch = { q -> SearchResult.Ok(listOf(SearchGroup("NOTE", listOf(hit(if (q == "plan") 5 else 6))))) }
            vm.search("plan")
            advanceUntilIdle()

            // Types one more letter and deletes it again before the pause is over.
            vm.search("plans")
            advanceTimeBy(DEBOUNCE / 2)
            vm.search("plan")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals("plan", state.query)
            assertEquals("the results are for the text in the field", "plan", state.resultsQuery)
            assertEquals(listOf(SearchGroup("NOTE", listOf(hit(5)))), state.results)
            assertFalse("no endless spinner", state.searching)
        }

    @Test
    fun `typing back to the shown text while a newer search is answering drops that answer`() =
        runTest(dispatcher) {
            val vm = opened()
            source.onSearch = { SearchResult.Ok(listOf(SearchGroup("NOTE", listOf(hit(5))))) }
            vm.search("plan")
            advanceUntilIdle()

            // The search for one more letter is sent, then the letter is deleted before it answers.
            val answer = CompletableDeferred<SearchResult>()
            source.onSearch = { answer.await() }
            vm.search("plans")
            passDebounce()
            assertEquals(2, source.calls.count { it.startsWith("search") })
            vm.search("plan")
            answer.complete(SearchResult.Ok(listOf(SearchGroup("NOTE", listOf(hit(6))))))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals("plan", state.query)
            assertEquals("the results are for the text in the field", "plan", state.resultsQuery)
            assertEquals(listOf(SearchGroup("NOTE", listOf(hit(5)))), state.results)
            assertFalse("no endless spinner", state.searching)
        }

    @Test
    fun `an offline or refused search says so, and the same text searches again after a failure`() =
        runTest(dispatcher) {
            val vm = opened()
            source.onSearch = { SearchResult.Offline }
            vm.search("plan")
            advanceUntilIdle()
            assertEquals(LinksError.Offline, vm.uiState.value.searchError)
            assertTrue(
                vm.uiState.value.results
                    .isEmpty(),
            )
            assertFalse(vm.uiState.value.searching)

            source.onSearch = { SearchResult.Failed(400) }
            vm.search("plan")
            advanceUntilIdle()
            assertEquals(LinksError.Failed, vm.uiState.value.searchError)

            source.onSearch = { SearchResult.Ok(listOf(SearchGroup("NOTE", listOf(hit(5))))) }
            vm.search("plan")
            advanceUntilIdle()
            assertNull(vm.uiState.value.searchError)
            assertEquals(listOf(SearchGroup("NOTE", listOf(hit(5)))), vm.uiState.value.results)
            assertEquals(3, source.calls.count { it.startsWith("search") })
        }

    @Test
    fun `closing the search clears it and drops a running search`() =
        runTest(dispatcher) {
            val vm = opened()
            vm.openSearch()
            val answer = CompletableDeferred<SearchResult>()
            source.onSearch = { answer.await() }
            vm.search("plan")
            passDebounce()

            vm.closeSearch()
            answer.complete(SearchResult.Ok(listOf(SearchGroup("NOTE", listOf(hit(5))))))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.adding)
            assertEquals("", state.query)
            assertEquals("", state.resultsQuery)
            assertTrue(state.results.isEmpty())
            assertNull(state.searchError)
        }

    // --- add ---

    @Test
    fun `adding a hit links the item to it, closes the search and shows the new link`() =
        runTest(dispatcher) {
            val vm = opened(links = listOf(link(3)))
            vm.openSearch()
            source.onSearch = { SearchResult.Ok(listOf(SearchGroup("NOTE", listOf(hit(5))))) }
            vm.search("plan")
            advanceUntilIdle()
            val answer = CompletableDeferred<LinksResult<LinkedItem>>()
            source.onLink = { _, _ -> answer.await() }

            vm.add(hit(5))
            runCurrent()
            assertTrue("busy while linking", vm.uiState.value.busy)
            assertEquals("link TASK ${id(1)} -> NOTE ${id(5)}", source.calls.last())
            vm.add(hit(5))
            runCurrent()
            assertEquals("a second tap while busy links once", 1, source.calls.count { it.startsWith("link") })

            answer.complete(LinksResult.Ok(link(5)))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.busy)
            assertFalse(state.adding)
            assertEquals("", state.query)
            assertEquals(listOf(link(3), link(5)), state.links)
            assertEquals("no reload needed", 1, source.calls.count { it.startsWith("list") })
        }

    @Test
    fun `adding a pair that is linked already closes the search and loads the links`() =
        runTest(dispatcher) {
            val vm = opened()
            vm.openSearch()
            source.onLink = { _, _ -> LinksResult.Conflict }
            source.onList = { LinksResult.Ok(listOf(link(5))) }

            vm.add(hit(5))
            advanceUntilIdle()

            assertFalse(vm.uiState.value.adding)
            assertNull(vm.uiState.value.actionError)
            assertEquals(listOf(link(5)), vm.uiState.value.links)
            assertEquals(2, source.calls.count { it.startsWith("list") })
        }

    @Test
    fun `a failed add keeps the search open and says why`() =
        runTest(dispatcher) {
            val vm = opened(links = listOf(link(3)))
            vm.openSearch()
            source.onSearch = { SearchResult.Ok(listOf(SearchGroup("NOTE", listOf(hit(5))))) }
            vm.search("plan")
            advanceUntilIdle()

            source.onLink = { _, _ -> LinksResult.Offline }
            vm.add(hit(5))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.adding)
            assertFalse(vm.uiState.value.busy)
            assertEquals(LinksError.Offline, vm.uiState.value.actionError)
            assertEquals("plan", vm.uiState.value.query)
            assertEquals(listOf(link(3)), vm.uiState.value.links)

            source.onLink = { _, _ -> LinksResult.Failed(400) }
            vm.add(hit(5))
            advanceUntilIdle()
            assertEquals(LinksError.Failed, vm.uiState.value.actionError)
        }

    @Test
    fun `adding while the links still load loads them again instead of showing only the new link`() =
        runTest(dispatcher) {
            val first = CompletableDeferred<LinksResult<List<LinkedItem>>>()
            source.onList = { first.await() }
            val vm = vm()
            vm.open(task)
            runCurrent()
            source.onLink = { _, _ -> LinksResult.Ok(link(5)) }
            source.onList = { LinksResult.Ok(listOf(link(3), link(5))) }

            vm.add(hit(5))
            advanceUntilIdle()

            assertEquals(listOf(link(3), link(5)), vm.uiState.value.links)
        }

    @Test
    fun `an add finishing after another item was opened is ignored`() =
        runTest(dispatcher) {
            val vm = opened()
            val answer = CompletableDeferred<LinksResult<LinkedItem>>()
            source.onLink = { _, _ -> answer.await() }
            vm.add(hit(5))
            runCurrent()

            source.onList = { LinksResult.Ok(emptyList()) }
            vm.open(contact)
            advanceUntilIdle()
            answer.complete(LinksResult.Ok(link(5)))
            advanceUntilIdle()

            assertEquals(emptyList<LinkedItem>(), vm.uiState.value.links)
            assertFalse(vm.uiState.value.busy)
        }

    @Test
    fun `nothing is added before an item is opened`() =
        runTest(dispatcher) {
            val vm = vm()
            vm.add(hit(5))
            vm.startCreate(LinkedCreateKind.Task)
            vm.create("Call Anna")
            advanceUntilIdle()
            assertTrue(source.calls.isEmpty())
        }

    // --- create linked ---

    @Test
    fun `creating a linked task creates it with the trimmed title, links it and closes the dialog`() =
        runTest(dispatcher) {
            val vm = opened(item = contact, links = listOf(link(3)))
            vm.startCreate(LinkedCreateKind.Task)
            assertEquals(LinkedCreateKind.Task, vm.uiState.value.creating)
            val created = CompletableDeferred<LinksResult<UUID>>()
            source.onCreateTask = { created.await() }
            source.onLink = { _, target -> LinksResult.Ok(link(7, type = target.type, title = "Call Anna")) }

            vm.create("  Call Anna ")
            runCurrent()
            assertTrue("busy while creating", vm.uiState.value.busy)
            created.complete(LinksResult.Ok(id(7)))
            advanceUntilIdle()

            assertEquals(listOf("createTask Call Anna", "link CONTACT ${id(2)} -> TASK ${id(7)}"), source.calls.drop(1))
            val state = vm.uiState.value
            assertNull(state.creating)
            assertFalse(state.busy)
            assertEquals(listOf(link(3), link(7, type = "TASK", title = "Call Anna")), state.links)
        }

    @Test
    fun `creating a linked note creates a note and links it`() =
        runTest(dispatcher) {
            val vm = opened()
            vm.startCreate(LinkedCreateKind.Note)
            source.onCreateNote = { LinksResult.Ok(id(8)) }
            source.onLink = { _, target -> LinksResult.Ok(link(8, type = target.type)) }

            vm.create("Meeting notes")
            advanceUntilIdle()

            assertEquals(listOf("createNote Meeting notes", "link TASK ${id(1)} -> NOTE ${id(8)}"), source.calls.drop(1))
            assertEquals(listOf(link(8, type = "NOTE")), vm.uiState.value.links)
        }

    @Test
    fun `a blank title or no open dialog creates nothing`() =
        runTest(dispatcher) {
            val vm = opened()
            vm.create("Call Anna")
            vm.startCreate(LinkedCreateKind.Task)
            vm.create("   ")
            advanceUntilIdle()

            assertEquals(listOf("list TASK ${id(1)}"), source.calls)
            assertEquals(LinkedCreateKind.Task, vm.uiState.value.creating)
        }

    @Test
    fun `a second tap on create while it runs creates once`() =
        runTest(dispatcher) {
            val vm = opened()
            vm.startCreate(LinkedCreateKind.Task)
            val answer = CompletableDeferred<LinksResult<UUID>>()
            source.onCreateTask = { answer.await() }

            vm.create("Call Anna")
            runCurrent()
            vm.create("Call Anna")
            runCurrent()
            answer.complete(LinksResult.Ok(id(7)))
            advanceUntilIdle()

            assertEquals(1, source.calls.count { it.startsWith("createTask") })
        }

    @Test
    fun `a failed create keeps the dialog open and says why`() =
        runTest(dispatcher) {
            val vm = opened()
            vm.startCreate(LinkedCreateKind.Task)
            source.onCreateTask = { LinksResult.Offline }

            vm.create("Call Anna")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(LinkedCreateKind.Task, state.creating)
            assertFalse(state.busy)
            assertEquals(LinksError.Offline, state.actionError)
            assertTrue("nothing to link", source.calls.none { it.startsWith("link") })

            source.onCreateTask = { LinksResult.Failed(400) }
            vm.create("Call Anna")
            advanceUntilIdle()
            assertEquals(LinksError.Failed, vm.uiState.value.actionError)
        }

    @Test
    fun `a created item whose link conflicts closes the dialog and loads the links`() =
        runTest(dispatcher) {
            val vm = opened()
            vm.startCreate(LinkedCreateKind.Note)
            source.onCreateNote = { LinksResult.Ok(id(8)) }
            source.onLink = { _, _ -> LinksResult.Conflict }
            source.onList = { LinksResult.Ok(listOf(link(8))) }

            vm.create("Meeting notes")
            advanceUntilIdle()

            assertNull(vm.uiState.value.creating)
            assertNull(vm.uiState.value.actionError)
            assertEquals(listOf(link(8)), vm.uiState.value.links)
            assertEquals(2, source.calls.count { it.startsWith("list") })
        }

    @Test
    fun `a created item that could not be linked closes the dialog and says so`() =
        runTest(dispatcher) {
            val vm = opened()
            vm.startCreate(LinkedCreateKind.Task)
            source.onCreateTask = { LinksResult.Ok(id(7)) }
            source.onLink = { _, _ -> LinksResult.Offline }

            vm.create("Call Anna")
            advanceUntilIdle()

            assertNull(vm.uiState.value.creating)
            assertFalse(vm.uiState.value.busy)
            assertEquals(LinksError.Offline, vm.uiState.value.actionError)
            assertEquals(emptyList<LinkedItem>(), vm.uiState.value.links)
        }

    @Test
    fun `a create finishing after another item was opened neither links nor shows anything`() =
        runTest(dispatcher) {
            val vm = opened()
            vm.startCreate(LinkedCreateKind.Task)
            val answer = CompletableDeferred<LinksResult<UUID>>()
            source.onCreateTask = { answer.await() }
            vm.create("Call Anna")
            runCurrent()

            vm.open(contact)
            advanceUntilIdle()
            answer.complete(LinksResult.Ok(id(7)))
            advanceUntilIdle()

            assertTrue(source.calls.none { it.startsWith("link") })
            assertFalse(vm.uiState.value.busy)
            assertEquals(emptyList<LinkedItem>(), vm.uiState.value.links)
        }

    @Test
    fun `a link of a created item finishing after another item was opened is ignored`() =
        runTest(dispatcher) {
            val vm = opened()
            vm.startCreate(LinkedCreateKind.Note)
            source.onCreateNote = { LinksResult.Ok(id(8)) }
            val linked = CompletableDeferred<LinksResult<LinkedItem>>()
            source.onLink = { _, _ -> linked.await() }
            vm.create("Meeting notes")
            runCurrent()
            assertEquals("link TASK ${id(1)} -> NOTE ${id(8)}", source.calls.last())

            vm.open(contact)
            advanceUntilIdle()
            linked.complete(LinksResult.Ok(link(8)))
            advanceUntilIdle()

            assertEquals(emptyList<LinkedItem>(), vm.uiState.value.links)
            assertFalse(vm.uiState.value.busy)
        }

    @Test
    fun `starting a create or a search clears the last action error, cancel closes the dialog`() =
        runTest(dispatcher) {
            val vm = opened(links = listOf(link(3)))
            source.onRemove = { LinksResult.Offline }
            vm.remove(link(3))
            advanceUntilIdle()
            assertEquals(LinksError.Offline, vm.uiState.value.actionError)

            vm.startCreate(LinkedCreateKind.Note)
            assertNull(vm.uiState.value.actionError)
            vm.cancelCreate()
            assertNull(vm.uiState.value.creating)

            vm.remove(link(3))
            advanceUntilIdle()
            vm.openSearch()
            assertNull(vm.uiState.value.actionError)
        }

    private class FakeLinks : LinksSource {
        val calls = mutableListOf<String>()
        var onList: suspend (ItemRef) -> LinksResult<List<LinkedItem>> = { LinksResult.Ok(emptyList()) }
        var onLink: suspend (ItemRef, ItemRef) -> LinksResult<LinkedItem> = { _, _ -> LinksResult.Failed(500) }
        var onRemove: suspend (UUID) -> LinksResult<Unit> = { LinksResult.Ok(Unit) }
        var onSearch: suspend (String) -> SearchResult = { SearchResult.Ok(emptyList()) }
        var onCreateTask: suspend (String) -> LinksResult<UUID> = { LinksResult.Failed(500) }
        var onCreateNote: suspend (String) -> LinksResult<UUID> = { LinksResult.Failed(500) }

        override suspend fun list(item: ItemRef): LinksResult<List<LinkedItem>> {
            calls += "list ${item.type} ${item.id}"
            return onList(item)
        }

        override suspend fun link(
            source: ItemRef,
            target: ItemRef,
        ): LinksResult<LinkedItem> {
            calls += "link ${source.type} ${source.id} -> ${target.type} ${target.id}"
            return onLink(source, target)
        }

        override suspend fun remove(linkId: UUID): LinksResult<Unit> {
            calls += "remove $linkId"
            return onRemove(linkId)
        }

        override suspend fun search(query: String): SearchResult {
            calls += "search $query"
            return onSearch(query)
        }

        override suspend fun createTask(title: String): LinksResult<UUID> {
            calls += "createTask $title"
            return onCreateTask(title)
        }

        override suspend fun createNote(title: String): LinksResult<UUID> {
            calls += "createNote $title"
            return onCreateNote(title)
        }
    }

    private companion object {
        const val DEBOUNCE = LinkedItemsViewModel.SEARCH_DEBOUNCE_MS
    }
}
