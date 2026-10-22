package ch.sonensei.cockpit.capture.queue

import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.generated.apis.BookmarksApi
import ch.sonensei.cockpit.core.network.generated.apis.CaptureApi
import ch.sonensei.cockpit.core.network.generated.apis.FilesApi
import ch.sonensei.cockpit.core.network.generated.models.CreateBookmarkRequest
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.TimeUnit

/** Edge cases of sending read-later bookmarks from the capture queue (SPEC.md §8.4, §9.3). */
@RunWith(AndroidJUnit4::class)
class ReadLaterUploaderTest {
    private val server = MockWebServer()
    private lateinit var q: QueueHarness

    @Before
    fun setUp() {
        server.start()
        q = QueueHarness(server, clock = TickingClock())
    }

    @After
    fun tearDown() {
        q.close()
        server.close()
    }

    private fun takeAll(): List<RecordedRequest> = generateSequence { server.takeRequest(0, TimeUnit.SECONDS) }.toList()

    /** The harness's uploader, with [bookmarksApi] in place of the real client. */
    private fun uploaderWith(bookmarksApi: BookmarksApi) =
        CaptureUploader(
            q.session,
            q.dao,
            q.store,
            q.retrofitApi(CaptureApi::class.java),
            q.retrofitApi(FilesApi::class.java),
            bookmarksApi,
            q.clock,
        )

    private fun urlOf(request: RecordedRequest) =
        Json
            .parseToJsonElement(request.body!!.utf8())
            .jsonObject["url"]!!
            .jsonPrimitive.content

    @Test
    fun `bookmarks are sent oldest first`() =
        runBlocking {
            q.queue.readLater("https://example.org/first")
            q.queue.readLater("https://example.org/second")
            q.queue.readLater("https://example.org/third")
            repeat(3) { server.enqueue(answer(201, bookmarkJson("https://example.org/x"))) }

            assertEquals(SendResult.DONE, q.uploader.sendAll())

            assertEquals(
                listOf("https://example.org/first", "https://example.org/second", "https://example.org/third"),
                takeAll().map(::urlOf),
            )
            assertEquals(CaptureQueueStatus(0, null), q.queue.status.first())
        }

    @Test
    fun `a bookmark without title sends no title so the server takes the page's one`() =
        runBlocking {
            q.queue.readLater("https://example.org/a")
            server.enqueue(answer(201, bookmarkJson("https://example.org/a")))

            q.uploader.sendAll()

            val body =
                Json
                    .parseToJsonElement(takeAll().single().body!!.utf8())
                    .jsonObject
            val title = body["title"]
            assertTrue("title must be absent or null, was $title", title == null || title.toString() == "null")
        }

    @Test
    fun `a capture the server cannot take now stops the run before any bookmark is sent`() =
        runBlocking {
            q.queue.capture("Call plumber")
            q.queue.readLater("https://example.org/a")
            server.enqueue(answer(503))

            assertEquals(SendResult.RETRY, q.uploader.sendAll())

            assertEquals(listOf(CAPTURE), takeAll().map { it.url.encodedPath })
            assertEquals(1, q.dao.sendableBookmarks().size)
            assertEquals(CaptureQueueStatus(2, "Server error (HTTP 503)"), q.queue.status.first())
        }

    @Test
    fun `401 for a bookmark keeps it and the later ones queued as not logged in`() =
        runBlocking {
            q.queue.readLater("https://example.org/a")
            q.queue.readLater("https://example.org/b")
            server.enqueue(answer(401, """{"title":"Unauthorized","status":401}"""))

            assertEquals(SendResult.RETRY, q.uploader.sendAll())

            assertEquals(1, takeAll().size)
            assertEquals(2, q.dao.sendableBookmarks().size)
            assertEquals(CaptureQueueStatus(2, CaptureUploader.NOT_LOGGED_IN), q.queue.status.first())
        }

    @Test
    fun `429 and 504 are retried, other 4xx answers are rejected for good`() =
        runBlocking {
            for (code in listOf(429, 504)) {
                q.queue.readLater("https://example.org/$code")
                server.enqueue(answer(code))
                assertEquals("HTTP $code", SendResult.RETRY, q.uploader.sendAll())
                assertEquals(
                    "HTTP $code",
                    "Server error (HTTP $code)",
                    q.dao
                        .sendableBookmarks()
                        .single()
                        .lastError,
                )
                server.enqueue(answer(201, bookmarkJson("https://example.org/$code")))
                assertEquals(SendResult.DONE, q.uploader.sendAll())
                assertTrue(q.dao.sendableBookmarks().isEmpty())
            }

            q.queue.readLater("https://example.org/gone")
            server.enqueue(answer(422, """{"title":"Unprocessable","status":422}"""))
            assertEquals(SendResult.DONE, q.uploader.sendAll())
            assertTrue(q.dao.sendableBookmarks().isEmpty())
            assertEquals(CaptureQueueStatus(1, "Rejected by the server (HTTP 422)"), q.queue.status.first())

            // Rejected bookmarks are never sent again.
            takeAll()
            assertEquals(SendResult.DONE, q.uploader.sendAll())
            assertTrue(takeAll().isEmpty())
        }

    @Test
    fun `a rejected bookmark does not stop the next one`() =
        runBlocking {
            q.queue.readLater("https://example.org/bad")
            q.queue.readLater("https://example.org/good")
            server.enqueue(answer(400, """{"title":"Bad Request","status":400}"""))
            server.enqueue(answer(201, bookmarkJson("https://example.org/good")))

            assertEquals(SendResult.DONE, q.uploader.sendAll())

            assertEquals(listOf("https://example.org/bad", "https://example.org/good"), takeAll().map(::urlOf))
            assertEquals(
                1,
                q.queue.status
                    .first()
                    .pendingCount,
            )
        }

    @Test
    fun `a bookmark that fails unexpectedly is retried later while the next one is still sent`() =
        runBlocking {
            val bookmarksApi = mockk<BookmarksApi>()
            val real = q.retrofitApi(BookmarksApi::class.java)
            coEvery { bookmarksApi.createBookmark(any()) } answers {
                if (firstArg<CreateBookmarkRequest>().url.endsWith("/broken")) throw IllegalStateException("unreadable")
                runBlocking { real.createBookmark(firstArg()) }
            }
            val uploader = uploaderWith(bookmarksApi)
            q.queue.readLater("https://example.org/broken")
            q.queue.readLater("https://example.org/fine")
            server.enqueue(answer(201, bookmarkJson("https://example.org/fine")))

            assertEquals(SendResult.RETRY, uploader.sendAll())

            assertEquals(listOf("https://example.org/fine"), takeAll().map(::urlOf))
            val left = q.dao.sendableBookmarks().single()
            assertEquals("https://example.org/broken", left.url)
            assertEquals(CaptureUploader.UNEXPECTED_ERROR, left.lastError)
        }

    @Test
    fun `a newer bookmark error replaces an older capture error in the status`() =
        runBlocking {
            q.queue.capture("Call plumber")
            q.queue.readLater("https://example.org/a")
            // The capture is rejected for good, then the bookmark of the same run hits a server error.
            server.enqueue(answer(400, """{"title":"Bad Request","status":400}"""))
            server.enqueue(answer(503))

            assertEquals(SendResult.RETRY, q.uploader.sendAll())
            assertEquals(CaptureQueueStatus(2, "Server error (HTTP 503)"), q.queue.status.first())

            // The rejected capture is not sent again; the bookmark is rejected now, its newest error is shown.
            server.enqueue(answer(404, """{"title":"Not Found","status":404}"""))
            assertEquals(SendResult.DONE, q.uploader.sendAll())
            assertEquals(listOf(CAPTURE, BOOKMARKS, BOOKMARKS), takeAll().map { it.url.encodedPath })
            assertEquals(CaptureQueueStatus(2, "Rejected by the server (HTTP 404)"), q.queue.status.first())
        }

    @Test
    fun `a newer capture error replaces an older bookmark error in the status`() =
        runBlocking {
            q.queue.readLater("https://example.org/a")
            server.enqueue(answer(404, """{"title":"Not Found","status":404}"""))
            q.uploader.sendAll()
            assertEquals(CaptureQueueStatus(1, "Rejected by the server (HTTP 404)"), q.queue.status.first())

            q.queue.capture("Call plumber")
            server.close()
            q.uploader.sendAll()

            assertEquals(CaptureQueueStatus(2, CaptureUploader.OFFLINE), q.queue.status.first())
        }

    @Test
    fun `an offline bookmark is sent on the next run`() =
        runBlocking {
            q.queue.readLater("https://example.org/a", "A page")
            q.queue.readLater("https://example.org/b")
            val real = q.retrofitApi(BookmarksApi::class.java)
            var online = false
            val bookmarksApi = mockk<BookmarksApi>()
            coEvery { bookmarksApi.createBookmark(any()) } answers {
                if (!online) throw IOException("no network")
                runBlocking { real.createBookmark(firstArg()) }
            }
            val uploader = uploaderWith(bookmarksApi)

            assertEquals(SendResult.RETRY, uploader.sendAll())
            // Offline stops the run: the second bookmark is not even tried.
            assertEquals(CaptureUploader.OFFLINE, q.dao.sendableBookmarks()[0].lastError)
            assertNull(q.dao.sendableBookmarks()[1].lastError)
            assertEquals(CaptureQueueStatus(2, CaptureUploader.OFFLINE), q.queue.status.first())

            online = true
            server.enqueue(answer(201, bookmarkJson("https://example.org/a")))
            server.enqueue(answer(201, bookmarkJson("https://example.org/b")))
            assertEquals(SendResult.DONE, uploader.sendAll())
            assertEquals(listOf("https://example.org/a", "https://example.org/b"), takeAll().map(::urlOf))
            assertEquals(CaptureQueueStatus(0, null), q.queue.status.first())
        }

    @Test
    fun `a failing scheduler still keeps the bookmark queued`() =
        runBlocking {
            val failing =
                object : CaptureSyncScheduler {
                    override suspend fun schedule(): Unit = throw IllegalStateException("WorkManager unavailable")
                }
            val queue = CaptureQueue(q.dao, q.store, failing, q.clock)

            queue.readLater("https://example.org/a")

            assertEquals(1, q.dao.sendableBookmarks().size)
        }

    @Test
    fun `a title shortened at the limit does not split a surrogate pair`() =
        runBlocking {
            val title = "t".repeat(CaptureQueue.MAX_TITLE_LENGTH - 1) + "📚" + "more"
            q.queue.readLater("https://example.org/a", title)

            val stored =
                q.dao
                    .sendableBookmarks()
                    .single()
                    .title!!
            assertEquals(CaptureQueue.MAX_TITLE_LENGTH - 1, stored.length)
            assertFalse(stored.last().isHighSurrogate())
        }

    @Test
    fun `URLs at the server's limits are accepted, mixed case schemes too`() =
        runBlocking {
            val longest = "https://e.org/" + "a".repeat(CaptureQueue.MAX_URL_LENGTH - "https://e.org/".length)
            assertTrue(CaptureQueue.isBookmarkUrl(longest))
            assertFalse(CaptureQueue.isBookmarkUrl(longest + "a"))
            assertTrue(CaptureQueue.isBookmarkUrl("HTTPS://Example.org"))
            assertTrue(CaptureQueue.isBookmarkUrl("http://example.org?q=1"))
            assertFalse(CaptureQueue.isBookmarkUrl("https:///path"))
            assertFalse(CaptureQueue.isBookmarkUrl(""))
        }

    @Test
    fun `IPv6 hosts follow the server's port rules`() {
        assertTrue(CaptureQueue.isBookmarkUrl("https://[2001:db8::1]/a"))
        assertTrue(CaptureQueue.isBookmarkUrl("https://[2001:db8::1]"))
        assertTrue(CaptureQueue.isBookmarkUrl("https://[2001:db8::1]:/a"))
        assertTrue(CaptureQueue.isBookmarkUrl("https://user@[2001:db8::1]:8443/a"))
        assertFalse(CaptureQueue.isBookmarkUrl("https://[2001:db8::1]:abc/a"))
        assertFalse(CaptureQueue.isBookmarkUrl("https://[2001:db8::1]:123456/a"))
        assertFalse(CaptureQueue.isBookmarkUrl("https://[not-ipv6]/a"))
    }

    @Test
    fun `a host label longer than DNS allows is refused like the server's IDN check does`() {
        assertTrue(CaptureQueue.isBookmarkUrl("https://" + "a".repeat(63) + ".example/"))
        assertFalse(CaptureQueue.isBookmarkUrl("https://" + "a".repeat(64) + ".example/"))
    }

    // The server rejects these with 400 for good (BookmarkUrls.normalize / BookmarkService.normalizeUrl). readLater
    // promises to refuse "a URL the server always rejects", so the user is not told "Saved to read later" for a
    // bookmark that will stay rejected in the queue forever.

    @Test
    fun `a URL with a control character is refused and nothing is queued`() =
        runBlocking {
            for (url in listOf("https://example.org/a\u0001b", "https://example.org/a\u007Fb", "https://example.org/a\u0085b")) {
                assertFalse("isBookmarkUrl(${url.toByteArray().contentToString()})", CaptureQueue.isBookmarkUrl(url))
                assertTrue(runCatching { q.queue.readLater(url) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertTrue(q.dao.sendableBookmarks().isEmpty())
        }

    @Test
    fun `a URL whose encoded form is longer than the server accepts is refused`() {
        // 420 characters as typed, 2420 once the server percent-encodes each "é" as %C3%A9.
        val url = "https://example.org/" + "é".repeat(400)

        assertFalse(CaptureQueue.isBookmarkUrl(url))
    }

    // The limit applies to the whole URL as BookmarkUrls.normalize writes it, so every part counts in its encoded form.

    @Test
    fun `a character outside the basic plane counts with all four UTF-8 bytes`() {
        val base = "https://example.org/" // 20 characters; the server writes each emoji as 4 escapes (12 characters).
        assertTrue(CaptureQueue.isBookmarkUrl(base + "😀".repeat(169))) // 2048 once encoded
        assertFalse(CaptureQueue.isBookmarkUrl(base + "😀".repeat(169) + "a"))
    }

    @Test
    fun `a percent sign that starts no escape counts as an encoded percent sign`() {
        val base = "https://example.org/"
        // Each lone "%" becomes %25 (3 characters).
        assertTrue(CaptureQueue.isBookmarkUrl(base + "%".repeat(676))) // 2048 once encoded
        assertFalse(CaptureQueue.isBookmarkUrl(base + "%".repeat(677)))
        // A valid escape at the very end is kept, an incomplete one ("%4" -> %254) is not.
        assertTrue(CaptureQueue.isBookmarkUrl(base + "a".repeat(2025) + "%41"))
        assertTrue(CaptureQueue.isBookmarkUrl(base + "a".repeat(2024) + "%4"))
        assertFalse(CaptureQueue.isBookmarkUrl(base + "a".repeat(2025) + "%4"))
    }

    @Test
    fun `query, user info and port count in the encoded length too`() {
        // Query: "https://example.org/?" is 21 characters, 337 x "é" are 2022 once encoded.
        val query = "https://example.org/?" + "é".repeat(337)
        assertTrue(CaptureQueue.isBookmarkUrl(query + "a?b=c")) // "?" stays as it is in a query: 2048
        assertFalse(CaptureQueue.isBookmarkUrl(query + "a?b=cd"))
        // User info: "é" -> %C3%A9, plus the "@": "https://%C3%A9@example.org/" is 27 characters.
        assertTrue(CaptureQueue.isBookmarkUrl("https://é@example.org/" + "a".repeat(2021)))
        assertFalse(CaptureQueue.isBookmarkUrl("https://é@example.org/" + "a".repeat(2022)))
        // An "@" inside the user info is encoded (%40): "https://a%40b@example.org/" is 26 characters.
        assertTrue(CaptureQueue.isBookmarkUrl("https://a@b@example.org/" + "a".repeat(2022)))
        assertFalse(CaptureQueue.isBookmarkUrl("https://a@b@example.org/" + "a".repeat(2023)))
        // The port: "https://example.org:8443/" is 25 characters, 337 x "é" are 2022 once encoded.
        val port = "https://example.org:8443/" + "é".repeat(337)
        assertTrue(CaptureQueue.isBookmarkUrl(port + "a"))
        assertFalse(CaptureQueue.isBookmarkUrl(port + "aa"))
    }

    @Test
    fun `an IPv6 literal the server cannot parse as a URI is refused and nothing is queued`() =
        runBlocking {
            // These pass the server's IPV6 pattern, but its final URI(...) check in BookmarkUrls.normalize throws, so
            // POST /bookmarks answers 400 for good.
            val urls = listOf("https://[1]/a", "https://[:]/a", "https://[1.2.3.4]/a", "https://[1:2:3:4:5:6:7:8:9]/a")
            for (url in urls) {
                assertTrue("URI($url) throws", runCatching { URI(url) }.exceptionOrNull() is URISyntaxException)
            }
            for (url in urls) {
                assertFalse("isBookmarkUrl($url)", CaptureQueue.isBookmarkUrl(url))
                assertTrue(runCatching { q.queue.readLater(url) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertTrue(q.dao.sendableBookmarks().isEmpty())
        }

    @Test
    fun `every IPv6 form the server's URI parse accepts is still queued, other malformed literals are refused`() =
        runBlocking {
            // Valid RFC 4291 forms: unspecified, full, upper case, IPv4-mapped, IPv4-compatible, with a port.
            val valid =
                listOf(
                    "https://[::]/a",
                    "https://[2001:0db8:0000:0000:0000:0000:0000:0001]/a",
                    "https://[2001:DB8::A]/a",
                    "https://[::ffff:192.0.2.1]/a",
                    "https://[1:2:3:4:5:6:1.2.3.4]/a",
                    "https://[fe80::1]:8080/a?q=1#top",
                )
            // These also pass the server's IPV6 pattern, but its URI(...) check refuses them.
            val malformed = listOf("https://[1::2::3]/a", "https://[12345::1]/a", "https://[::1.2.3]/a", "https://[::1.2.3.256]/a")
            for (url in valid) assertTrue("URI($url) parses", runCatching { URI(url) }.isSuccess)
            for (url in malformed) assertTrue("URI($url) throws", runCatching { URI(url) }.exceptionOrNull() is URISyntaxException)

            for (url in valid) {
                assertTrue("isBookmarkUrl($url)", CaptureQueue.isBookmarkUrl(url))
                q.queue.readLater(url)
            }
            for (url in malformed) {
                assertFalse("isBookmarkUrl($url)", CaptureQueue.isBookmarkUrl(url))
                assertTrue(runCatching { q.queue.readLater(url) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertEquals(valid, q.dao.sendableBookmarks().map { it.url })
        }
}
