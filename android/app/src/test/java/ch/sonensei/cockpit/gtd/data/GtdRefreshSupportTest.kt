package ch.sonensei.cockpit.gtd.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.DateTimeException

/** Paging and the offline flag, without a server. */
class GtdRefreshSupportTest {
    private data class Page(
        val items: List<Int>,
        val next: String?,
    )

    @Test
    fun `paging stops after the page limit when the server always sends a next cursor`() =
        runBlocking {
            val cursors = mutableListOf<String?>()

            val all =
                allPages(
                    page = { cursor ->
                        cursors += cursor
                        Response.success(Page(listOf(cursors.size), "c${cursors.size}"))
                    },
                    items = Page::items,
                    nextCursor = Page::next,
                )

            assertEquals(MAX_PAGES, cursors.size)
            assertEquals((1..MAX_PAGES).toList(), all)
            assertEquals(listOf(null, "c1", "c2"), cursors.take(3))
        }

    @Test
    fun `a single page without a next cursor is fetched once`() =
        runBlocking {
            var calls = 0

            val all =
                allPages(
                    page = {
                        calls++
                        Response.success(Page(emptyList(), null))
                    },
                    items = Page::items,
                    nextCursor = Page::next,
                )

            assertEquals(1, calls)
            assertEquals(emptyList<Int>(), all)
        }

    @Test
    fun `a non-2xx answer or a missing body is an HttpFailure with the status only`() {
        val error =
            runCatching { body(Response.error<Page>(404, """{"detail":"secret body"}""".toResponseBody())) }.exceptionOrNull()
        assertEquals(404, (error as HttpFailure).status)
        assertEquals("HTTP 404", error.message)

        val empty = runCatching { body(Response.success<Page>(null)) }.exceptionOrNull()
        assertEquals(200, (empty as HttpFailure).status)
    }

    @Test
    fun `failures are classified as offline or failed`() =
        runBlocking {
            val connectivity = GtdConnectivity()

            assertEquals(RefreshResult.Offline, connectivity.refreshing { throw IOException("unreachable") })
            assertEquals(RefreshResult.Offline, connectivity.refreshing { throw HttpFailure(500) })
            assertEquals(RefreshResult.Offline, connectivity.refreshing { throw HttpFailure(503) })
            assertEquals(RefreshResult.Failed(499), connectivity.refreshing { throw HttpFailure(499) })
            assertEquals(RefreshResult.Failed(401), connectivity.refreshing { throw HttpFailure(401) })
            assertEquals(RefreshResult.Failed(null), connectivity.refreshing { throw SerializationException("bad") })
            assertEquals(RefreshResult.Failed(null), connectivity.refreshing { throw IllegalArgumentException("bad uuid") })
            assertEquals(RefreshResult.Failed(null), connectivity.refreshing { throw DateTimeException("bad date") })
        }

    @Test
    fun `a rejected load leaves the offline flag as it is, a successful one clears it`() =
        runBlocking {
            val connectivity = GtdConnectivity()
            assertFalse(connectivity.offline.value)

            connectivity.refreshing { throw IOException("unreachable") }
            assertTrue(connectivity.offline.value)

            connectivity.refreshing { throw HttpFailure(400) }
            assertTrue(connectivity.offline.value)

            assertEquals(RefreshResult.Refreshed, connectivity.refreshing { })
            assertFalse(connectivity.offline.value)

            connectivity.refreshing { throw HttpFailure(400) }
            assertFalse(connectivity.offline.value)

            connectivity.refreshing { throw HttpFailure(502) }
            connectivity.reset()
            assertFalse(connectivity.offline.value)
        }

    /** Loads [view] with [load] and stores nothing. */
    private suspend fun GtdConnectivity.loadView(
        view: String,
        load: suspend () -> Int,
    ): RefreshResult = refreshing(view, load, store = {})

    private suspend fun GtdConnectivity.failureOf(view: String) = failure(view).first()

    @Test
    fun `a rejected or unreadable load is kept as the failure of its view`() =
        runBlocking {
            val connectivity = GtdConnectivity()
            assertNull(connectivity.failureOf("NEXT"))

            assertEquals(RefreshResult.Failed(403), connectivity.loadView("NEXT") { throw HttpFailure(403) })
            assertEquals(RefreshResult.Failed(403), connectivity.failureOf("NEXT"))
            assertFalse("a rejected load is not offline", connectivity.offline.value)

            connectivity.loadView("NEXT") { throw SerializationException("fictitious unreadable body") }
            assertEquals(RefreshResult.Failed(null), connectivity.failureOf("NEXT"))
        }

    @Test
    fun `a successful load clears the failure of its view only`() =
        runBlocking {
            val connectivity = GtdConnectivity()
            connectivity.loadView("NEXT") { throw HttpFailure(400) }
            connectivity.loadView("TODAY") { throw HttpFailure(401) }

            assertEquals(RefreshResult.Refreshed, connectivity.loadView("NEXT") { 7 })

            assertNull(connectivity.failureOf("NEXT"))
            assertEquals(RefreshResult.Failed(401), connectivity.failureOf("TODAY"))
            assertNull("a view never loaded has no failure", connectivity.failureOf("INBOX"))
        }

    @Test
    fun `an unreachable server is no failure and replaces an earlier one`() =
        runBlocking {
            val connectivity = GtdConnectivity()
            connectivity.loadView("NEXT") { throw HttpFailure(400) }

            assertEquals(RefreshResult.Offline, connectivity.loadView("NEXT") { throw IOException("fictitious unreachable server") })
            assertNull(connectivity.failureOf("NEXT"))
            assertTrue(connectivity.offline.value)

            connectivity.loadView("NEXT") { throw HttpFailure(400) }
            assertEquals(RefreshResult.Offline, connectivity.loadView("NEXT") { throw HttpFailure(503) })
            assertNull(connectivity.failureOf("NEXT"))
        }

    @Test
    fun `a running load hides the earlier failure until it fails again`() =
        runBlocking {
            val connectivity = GtdConnectivity()
            connectivity.loadView("NEXT") { throw HttpFailure(400) }
            val started = CompletableDeferred<Unit>()
            val answer = CompletableDeferred<Unit>()

            val retry =
                async {
                    connectivity.loadView("NEXT") {
                        started.complete(Unit)
                        answer.await()
                        throw HttpFailure(409)
                    }
                }
            started.await()
            assertNull("a retry shows loading, not the old error", connectivity.failureOf("NEXT"))

            answer.complete(Unit)
            assertEquals(RefreshResult.Failed(409), retry.await())
            assertEquals(RefreshResult.Failed(409), connectivity.failureOf("NEXT"))
        }

    @Test
    fun `clearing drops the failures of every view`() =
        runBlocking {
            val connectivity = GtdConnectivity()
            connectivity.loadView("NEXT") { throw HttpFailure(400) }
            connectivity.loadView("TODAY") { throw HttpFailure(404) }

            connectivity.clearing { }

            assertNull(connectivity.failureOf("NEXT"))
            assertNull(connectivity.failureOf("TODAY"))
        }

    @Test
    fun `a rejected load that outlives a clearing does not record its failure`() =
        runBlocking {
            val connectivity = GtdConnectivity()
            val started = CompletableDeferred<Unit>()
            val answer = CompletableDeferred<Unit>()
            val load =
                async {
                    connectivity.loadView("NEXT") {
                        started.complete(Unit)
                        answer.await()
                        throw HttpFailure(400)
                    }
                }
            started.await()

            connectivity.clearing { }
            answer.complete(Unit)

            assertEquals(RefreshResult.Failed(400), load.await())
            assertNull("the next session must not see the previous session's error", connectivity.failureOf("NEXT"))

            // A load started after the clearing records its failure again.
            connectivity.loadView("NEXT") { throw HttpFailure(400) }
            assertEquals(RefreshResult.Failed(400), connectivity.failureOf("NEXT"))
        }

    @Test
    fun `an answer dropped because of a clearing is neither stored nor recorded as a failure`() =
        runBlocking {
            val connectivity = GtdConnectivity()
            val started = CompletableDeferred<Unit>()
            val answer = CompletableDeferred<Unit>()
            val stored = mutableListOf<Int>()
            val load =
                async {
                    connectivity.refreshing(
                        "NEXT",
                        load = {
                            started.complete(Unit)
                            answer.await()
                            1
                        },
                        store = { stored += it },
                    )
                }
            started.await()

            connectivity.clearing { }
            answer.complete(Unit)

            assertEquals(RefreshResult.Failed(null), load.await())
            assertEquals(emptyList<Int>(), stored)
            assertNull(connectivity.failureOf("NEXT"))
        }
}
