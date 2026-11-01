package ch.sonensei.cockpit.search

import ch.sonensei.cockpit.core.network.ServerUrlRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** Searching as the user types: debounce, filters, the keyboard's search action, failures and out-of-order answers. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<SearchRepository>()
    private val serverUrls = mockk<ServerUrlRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun group(
        n: Int,
        title: String,
    ) = SearchGroup("TASK", listOf(SearchHit(id(n), "TASK", title, null, "/tasks/${id(n)}")))

    private fun ok(vararg groups: SearchGroup) = SearchResult.Ok(groups.toList())

    private fun vm() = SearchViewModel(repo, serverUrls)

    /** Lets the debounce of the last typed text pass and the search start. */
    private fun TestScope.passDebounce() {
        advanceTimeBy(SearchViewModel.SEARCH_DELAY_MS)
        runCurrent()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { serverUrls.serverUrl } returns flowOf("https://cockpit.example.org/".toHttpUrl())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `typing searches only once the pause is over, for the last text only`() =
        runTest(dispatcher) {
            coEvery { repo.search(any(), any()) } returns ok(group(1, "Plan the trip"))
            val vm = vm()

            vm.setQuery("p")
            advanceTimeBy(100)
            vm.setQuery("pl")
            advanceTimeBy(100)
            vm.setQuery("plan")
            advanceTimeBy(SearchViewModel.SEARCH_DELAY_MS - 1)
            runCurrent()
            coVerify(exactly = 0) { repo.search(any(), any()) }

            advanceTimeBy(1)
            runCurrent()
            coVerify(exactly = 1) { repo.search("plan", emptySet()) }
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.search(any(), any()) }
            assertEquals(listOf(group(1, "Plan the trip")), vm.uiState.value.groups)
            assertEquals(SearchKey("plan", emptySet()), vm.uiState.value.shownFor)
            assertFalse(vm.uiState.value.loading)
        }

    @Test
    fun `the text is searched trimmed but kept as typed`() =
        runTest(dispatcher) {
            coEvery { repo.search(any(), any()) } returns ok()
            val vm = vm()

            vm.setQuery("  plan ")
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.search("plan", emptySet()) }
            assertEquals("  plan ", vm.uiState.value.query)
        }

    @Test
    fun `a blank text searches nothing and shows the idle hint`() =
        runTest(dispatcher) {
            val vm = vm()

            vm.setQuery("   ")
            advanceUntilIdle()

            coVerify(exactly = 0) { repo.search(any(), any()) }
            assertTrue(vm.uiState.value.idle)
            assertFalse(vm.uiState.value.nothingFound)
            assertFalse(vm.uiState.value.loading)
        }

    @Test
    fun `clearing the text clears the results`() =
        runTest(dispatcher) {
            coEvery { repo.search("plan", any()) } returns ok(group(1, "Plan"))
            val vm = vm()
            vm.setQuery("plan")
            advanceUntilIdle()
            assertEquals(1, vm.uiState.value.groups.size)

            vm.setQuery("")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state.groups.isEmpty())
            assertNull(state.shownFor)
            assertNull(state.message)
            assertTrue(state.idle)
            coVerify(exactly = 1) { repo.search(any(), any()) }
        }

    @Test
    fun `clearing the text while a search runs drops its answer`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<SearchResult>()
            coEvery { repo.search("plan", any()) } coAnswers { answer.await() }
            val vm = vm()
            vm.setQuery("plan")
            passDebounce()
            assertTrue(vm.uiState.value.loading)

            vm.setQuery("")
            answer.complete(ok(group(1, "Plan")))
            advanceUntilIdle()

            assertFalse(vm.uiState.value.loading)
            assertTrue(
                vm.uiState.value.groups
                    .isEmpty(),
            )
            assertNull(vm.uiState.value.shownFor)
        }

    @Test
    fun `a search shows loading until its answer arrives`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<SearchResult>()
            coEvery { repo.search("plan", any()) } coAnswers { answer.await() }
            val vm = vm()
            vm.setQuery("plan")
            runCurrent()
            assertFalse("not loading while typing pauses", vm.uiState.value.loading)

            passDebounce()
            assertTrue(vm.uiState.value.loading)

            answer.complete(ok())
            advanceUntilIdle()
            assertFalse(vm.uiState.value.loading)
        }

    @Test
    fun `a filter chip searches right away with the selected types, and again without it when cleared`() =
        runTest(dispatcher) {
            coEvery { repo.search(any(), any()) } returns ok()
            val vm = vm()

            vm.setQuery("plan")
            vm.toggle(SearchFilter.Files)
            runCurrent()
            coVerify(exactly = 1) { repo.search("plan", setOf(SearchFilter.Files)) }

            vm.toggle(SearchFilter.Tasks)
            runCurrent()
            coVerify(exactly = 1) { repo.search("plan", setOf(SearchFilter.Files, SearchFilter.Tasks)) }

            vm.toggle(SearchFilter.Files)
            runCurrent()
            coVerify(exactly = 1) { repo.search("plan", setOf(SearchFilter.Tasks)) }
            assertEquals(setOf(SearchFilter.Tasks), vm.uiState.value.filters)

            advanceUntilIdle()
            // The debounced search of the typed text was replaced by the chip's search.
            coVerify(exactly = 0) { repo.search("plan", emptySet()) }
            coVerify(exactly = 3) { repo.search(any(), any()) }
        }

    @Test
    fun `a filter chip without text only selects the filter`() =
        runTest(dispatcher) {
            val vm = vm()

            vm.toggle(SearchFilter.Notes)
            advanceUntilIdle()

            assertEquals(setOf(SearchFilter.Notes), vm.uiState.value.filters)
            coVerify(exactly = 0) { repo.search(any(), any()) }
        }

    @Test
    fun `the keyboard's search action searches without waiting for the pause`() =
        runTest(dispatcher) {
            coEvery { repo.search(any(), any()) } returns ok(group(1, "Plan"))
            val vm = vm()

            vm.setQuery("plan")
            vm.submit()
            runCurrent()
            coVerify(exactly = 1) { repo.search("plan", emptySet()) }
            assertEquals(1, vm.uiState.value.groups.size)

            advanceUntilIdle()
            coVerify(exactly = 1) { repo.search(any(), any()) }
        }

    @Test
    fun `the keyboard's search action with a blank text searches nothing`() =
        runTest(dispatcher) {
            val vm = vm()

            vm.submit()
            advanceUntilIdle()

            coVerify(exactly = 0) { repo.search(any(), any()) }
        }

    @Test
    fun `offline says so, and retry searches again and shows the answer`() =
        runTest(dispatcher) {
            coEvery { repo.search("plan", any()) } returns SearchResult.Offline andThen ok(group(1, "Plan"))
            val vm = vm()
            vm.setQuery("plan")
            advanceUntilIdle()

            assertEquals(SearchMessage.OFFLINE, vm.uiState.value.message)
            assertFalse(vm.uiState.value.loading)
            assertFalse(vm.uiState.value.nothingFound)

            vm.retry()
            runCurrent()
            coVerify(exactly = 2) { repo.search("plan", emptySet()) }
            advanceUntilIdle()

            assertNull(vm.uiState.value.message)
            assertEquals(listOf(group(1, "Plan")), vm.uiState.value.groups)
        }

    @Test
    fun `a refused search says it failed`() =
        runTest(dispatcher) {
            coEvery { repo.search("plan", any()) } returns SearchResult.Failed(400)
            val vm = vm()
            vm.setQuery("plan")
            advanceUntilIdle()

            assertEquals(SearchMessage.FAILED, vm.uiState.value.message)
            assertFalse(vm.uiState.value.loading)
            assertFalse(vm.uiState.value.nothingFound)
        }

    @Test
    fun `typing again or changing a filter clears the failure message`() =
        runTest(dispatcher) {
            coEvery { repo.search(any(), any()) } returns SearchResult.Offline
            val vm = vm()
            vm.setQuery("plan")
            advanceUntilIdle()
            assertEquals(SearchMessage.OFFLINE, vm.uiState.value.message)

            vm.setQuery("plans")
            assertNull(vm.uiState.value.message)
            advanceUntilIdle()
            assertEquals(SearchMessage.OFFLINE, vm.uiState.value.message)

            vm.toggle(SearchFilter.Tasks)
            assertNull(vm.uiState.value.message)
        }

    @Test
    fun `nothing found is only said for the text and filters it was searched for`() =
        runTest(dispatcher) {
            val second = CompletableDeferred<SearchResult>()
            coEvery { repo.search("zzz", emptySet()) } returns ok()
            coEvery { repo.search("zzzq", emptySet()) } coAnswers { second.await() }
            coEvery { repo.search("zzz", setOf(SearchFilter.Notes)) } returns ok(group(1, "Zzz note"))
            val vm = vm()
            vm.setQuery("zzz")
            advanceUntilIdle()
            assertTrue(vm.uiState.value.nothingFound)

            // Typing on: the empty answer was for "zzz", not for "zzzq".
            vm.setQuery("zzzq")
            assertFalse(vm.uiState.value.nothingFound)
            passDebounce()
            assertFalse(vm.uiState.value.nothingFound)
            second.complete(ok())
            advanceUntilIdle()
            assertTrue(vm.uiState.value.nothingFound)

            // Same text as before but another filter: a new search, with hits.
            vm.setQuery("zzz")
            vm.toggle(SearchFilter.Notes)
            assertFalse(vm.uiState.value.nothingFound)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.nothingFound)
            assertEquals(listOf(group(1, "Zzz note")), vm.uiState.value.groups)
        }

    @Test
    fun `results of an earlier text are not marked as the results of the new text`() =
        runTest(dispatcher) {
            val bert = CompletableDeferred<SearchResult>()
            coEvery { repo.search("anna", any()) } returns ok(group(1, "Anna"))
            coEvery { repo.search("bert", any()) } coAnswers { bert.await() }
            val vm = vm()
            vm.setQuery("anna")
            advanceUntilIdle()
            assertEquals(vm.uiState.value.key, vm.uiState.value.shownFor)

            vm.setQuery("bert")
            passDebounce()
            val waiting = vm.uiState.value
            assertEquals(SearchKey("anna", emptySet()), waiting.shownFor)
            assertTrue(waiting.shownFor != waiting.key)

            bert.complete(ok(group(2, "Bert")))
            advanceUntilIdle()
            assertEquals(SearchKey("bert", emptySet()), vm.uiState.value.shownFor)
            assertEquals(listOf(group(2, "Bert")), vm.uiState.value.groups)
        }

    @Test
    fun `an older search answering last does not replace the newer answer`() =
        runTest(dispatcher) {
            val older = CompletableDeferred<SearchResult>()
            val newer = CompletableDeferred<SearchResult>()
            coEvery { repo.search("an", any()) } coAnswers { older.await() }
            coEvery { repo.search("anna", any()) } coAnswers { newer.await() }
            val vm = vm()
            vm.setQuery("an")
            passDebounce()
            vm.setQuery("anna")
            passDebounce()

            newer.complete(ok(group(2, "Anna")))
            runCurrent()
            older.complete(ok(group(1, "Andreas")))
            advanceUntilIdle()

            assertEquals(listOf(group(2, "Anna")), vm.uiState.value.groups)
            assertEquals(SearchKey("anna", emptySet()), vm.uiState.value.shownFor)
        }

    @Test
    fun `an older search failing last does not show a failure for the newer text`() =
        runTest(dispatcher) {
            val older = CompletableDeferred<SearchResult>()
            val newer = CompletableDeferred<SearchResult>()
            coEvery { repo.search("an", any()) } coAnswers { older.await() }
            coEvery { repo.search("anna", any()) } coAnswers { newer.await() }
            val vm = vm()
            vm.setQuery("an")
            passDebounce()
            vm.setQuery("anna")
            passDebounce()

            newer.complete(ok(group(2, "Anna")))
            runCurrent()
            older.complete(SearchResult.Offline)
            advanceUntilIdle()

            assertNull(vm.uiState.value.message)
            assertEquals(listOf(group(2, "Anna")), vm.uiState.value.groups)
            assertFalse(vm.uiState.value.loading)
        }

    @Test
    fun `setting the same text again does not search again`() =
        runTest(dispatcher) {
            coEvery { repo.search(any(), any()) } returns ok()
            val vm = vm()
            vm.setQuery("plan")
            advanceUntilIdle()

            vm.setQuery("plan")
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.search(any(), any()) }
        }

    @Test
    fun `the configured server is offered for web links`() =
        runTest(dispatcher) {
            val vm = vm()
            runCurrent()

            assertEquals("https://cockpit.example.org/".toHttpUrl(), vm.server.value)
        }
}
