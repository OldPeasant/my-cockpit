package ch.sonensei.cockpit.gtd.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
