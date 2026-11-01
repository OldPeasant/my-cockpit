package ch.sonensei.cockpit.bookmarks

import ch.sonensei.cockpit.core.network.generated.apis.BookmarksApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.UUID

/** The bookmark calls against a fake server: what is asked, and how answers and refusals are reported. */
class BookmarksRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repo: BookmarksRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun bookmarkJson(
        n: Int,
        createdAt: String = "2026-10-0${n}T09:00:00Z",
        extra: String = "",
    ) = """{"id":"${id(n)}","url":"https://www.example.org/$n","displayTitle":"Page $n","title":"Page $n",
        |"tags":["reading"],"readLater":true,"metadataStatus":"FETCHED","createdAt":"$createdAt",
        |"updatedAt":"$createdAt","version":3$extra}
        """.trimMargin().replace("\n", "")

    private fun json(
        body: String,
        code: Int = 200,
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    private fun bookmark(n: Int) =
        Bookmark(
            id(n),
            "https://example.org/$n",
            "Page $n",
            "Page $n",
            null,
            null,
            listOf("reading"),
            true,
            null,
            java.time.Instant.EPOCH,
            7,
        )

    @Before
    fun setUp() {
        server.start()
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        repo = BookmarksRepository(retrofit.create(BookmarksApi::class.java), retrofit.create(BookmarksWriteApi::class.java))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `the queue asks for unread read-later bookmarks, follows the pages and keeps the newest first`() =
        runBlocking {
            server.enqueue(json("""{"items":[${bookmarkJson(1)}],"nextCursor":"c2"}"""))
            server.enqueue(json("""{"items":[${bookmarkJson(2, extra = ",\"favicon\":\"https://example.org/i.png\"")}]}"""))

            val result = repo.list(BookmarkView.QUEUE, "reading") as BookmarksResult.Ok

            assertEquals(listOf(id(2), id(1)), result.value.map { it.id })
            assertEquals("https://example.org/i.png", result.value[0].favicon)
            assertEquals("example.org", result.value[0].site)
            val first = server.takeRequest().url
            assertEquals("true", first.queryParameter("readLater"))
            assertEquals("false", first.queryParameter("read"))
            assertEquals("reading", first.queryParameter("tag"))
            assertEquals("c2", server.takeRequest().url.queryParameter("cursor"))
        }

    @Test
    fun `all bookmarks are asked without the queue filters`() =
        runBlocking {
            server.enqueue(json("""{"items":[]}"""))
            repo.list(BookmarkView.ALL, null)
            val url = server.takeRequest().url
            assertNull(url.queryParameter("readLater"))
            assertNull(url.queryParameter("read"))
            assertNull(url.queryParameter("tag"))
        }

    @Test
    fun `marking read sends only the read flag with If-Match`() =
        runBlocking {
            server.enqueue(json(bookmarkJson(1, extra = ",\"readAt\":\"2026-10-05T09:00:00Z\"")))

            val result = repo.setRead(bookmark(1), read = true) as BookmarksResult.Ok

            assertEquals(true, result.value.read)
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("\"7\"", request.headers["If-Match"])
            assertEquals("""{"read":true}""", request.body?.utf8())
        }

    @Test
    fun `saving sends title, notes and parsed tags, and blank fields clear them`() =
        runBlocking {
            server.enqueue(json(bookmarkJson(1)))
            server.enqueue(json(bookmarkJson(1)))

            repo.save(bookmark(1), BookmarkDraft(" New title ", "#rust, kotlin rust", " Worth a read "))
            repo.save(bookmark(1), BookmarkDraft("  ", "", ""))

            assertEquals(
                """{"title":"New title","description":"Worth a read","tags":["rust","kotlin"]}""",
                server.takeRequest().body?.utf8(),
            )
            assertEquals("""{"title":null,"description":null,"tags":[]}""", server.takeRequest().body?.utf8())
        }

    @Test
    fun `delete and restore call the bookmark and the Trash`() =
        runBlocking {
            server.enqueue(status(204))
            server.enqueue(status(204))

            assertEquals(BookmarksResult.Ok(Unit), repo.delete(bookmark(1)))
            assertEquals(BookmarksResult.Ok(Unit), repo.restore(id(1)))

            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertEquals("/api/v1/bookmarks/${id(1)}", delete.url.encodedPath)
            assertEquals("\"7\"", delete.headers["If-Match"])
            val restore = server.takeRequest()
            assertEquals("POST", restore.method)
            assertEquals("/api/v1/trash/BOOKMARK/${id(1)}/restore", restore.url.encodedPath)
        }

    @Test
    fun `refusals are failures, server errors and no connection are offline`() =
        runBlocking {
            server.enqueue(status(412))
            server.enqueue(status(503))
            server.enqueue(json("{not json"))

            assertEquals(BookmarksResult.Failed(412), repo.setRead(bookmark(1), read = false))
            assertEquals(BookmarksResult.Offline, repo.tags())
            assertEquals(BookmarksResult.Failed(null), repo.get(id(1)))
            server.close()
            assertEquals(BookmarksResult.Offline, repo.delete(bookmark(1)))
        }
}
