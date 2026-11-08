package ch.sonensei.cockpit.bookmarks

import ch.sonensei.cockpit.core.network.generated.apis.BookmarksApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
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

    @Test
    fun `toggling read later sends only the read-later flag with If-Match`() =
        runBlocking {
            server.enqueue(json(bookmarkJson(1).replace("\"readLater\":true", "\"readLater\":false")))

            val result = repo.setReadLater(bookmark(1), readLater = false) as BookmarksResult.Ok

            assertEquals(false, result.value.readLater)
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/api/v1/bookmarks/${id(1)}", request.url.encodedPath)
            assertEquals("\"7\"", request.headers["If-Match"])
            assertEquals("""{"readLater":false}""", request.body?.utf8())
        }

    @Test
    fun `a new bookmark sends the trimmed address, title, parsed tags and read later`() =
        runBlocking {
            server.enqueue(json(bookmarkJson(1), code = 201))

            val result = repo.create(NewBookmark(" https://example.org/1 ", " Page 1 ", "#reading, Rust rust", readLater = true))

            assertEquals(id(1), (result as BookmarksResult.Ok).value.id)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/bookmarks", request.url.encodedPath)
            assertEquals(
                """{"url":"https://example.org/1","title":"Page 1","tags":["reading","Rust"],"readLater":true}""",
                request.body?.utf8(),
            )
        }

    @Test
    fun `a new bookmark without title and tags leaves them out for the server to fill`() =
        runBlocking {
            server.enqueue(json(bookmarkJson(1), code = 201))

            repo.create(NewBookmark("https://example.org/1", "   ", " , # "))

            assertEquals("""{"url":"https://example.org/1","readLater":false}""", server.takeRequest().body?.utf8())
        }

    @Test
    fun `a refused new bookmark is a failure with its status, no connection is offline`() =
        runBlocking {
            server.enqueue(status(400))
            server.enqueue(status(500))

            assertEquals(BookmarksResult.Failed(400), repo.create(NewBookmark("https://example.org/bad")))
            assertEquals(BookmarksResult.Offline, repo.create(NewBookmark("https://example.org/1")))
            server.close()
            assertEquals(BookmarksResult.Offline, repo.create(NewBookmark("https://example.org/1")))
        }

    @Test
    fun `a refused new bookmark names the invalid fields of the Problem Details`() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(400)
                    .addHeader("Content-Type", "application/problem+json")
                    .body(
                        """{"status":400,"title":"Bad Request","errors":[{"field":"tags","message":"too long"},""" +
                            """{"field":"url","message":"must be a valid http or https URL"}]}""",
                    ).build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(400)
                    .addHeader("Content-Type", "application/problem+json")
                    .body("""{"status":400,"title":"Bad Request"}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(400)
                    .body("not json")
                    .build(),
            )

            assertEquals(BookmarksResult.Failed(400, setOf("tags", "url")), repo.create(NewBookmark("https://example.org/1")))
            assertEquals(BookmarksResult.Failed(400), repo.create(NewBookmark("https://example.org/1")))
            assertEquals(BookmarksResult.Failed(400), repo.create(NewBookmark("https://example.org/1")))
        }

    @Test
    fun `only a 400 reports invalid fields, and errors without a field name none`() =
        runBlocking {
            fun problem(
                code: Int,
                errors: String,
            ) = MockResponse
                .Builder()
                .code(code)
                .addHeader("Content-Type", "application/problem+json")
                .body("""{"status":$code,"title":"Refused","errors":$errors}""")
                .build()
            server.enqueue(problem(400, """[{"message":"something is off"},{"field":"url","message":"private host"}]"""))
            server.enqueue(problem(409, """[{"field":"url","message":"already saved"}]"""))
            server.enqueue(problem(400, """[]"""))

            assertEquals(BookmarksResult.Failed(400, setOf("url")), repo.create(NewBookmark("https://example.org/1")))
            assertEquals(BookmarksResult.Failed(409), repo.create(NewBookmark("https://example.org/1")))
            assertEquals(BookmarksResult.Failed(400), repo.create(NewBookmark("https://example.org/1")))
        }

    @Test
    fun `a repeated Inbox task the server already has is ok and linked`() =
        runBlocking {
            // The idempotent create answers 200 with the task made before.
            server.enqueue(json("""{"id":"${id(50)}","title":"Page 1"}""", code = 200))
            server.enqueue(json("""{"id":"${id(51)}"}""", code = 201))

            assertEquals(BookmarksResult.Ok(true), repo.toInbox(bookmark(1), id(50)))
            assertEquals(
                true,
                server
                    .takeRequest()
                    .body!!
                    .utf8()
                    .contains("\"id\":\"${id(50)}\""),
            )
        }

    @Test
    fun `the readable copy is the file preview, and says when it was cut short`() =
        runBlocking {
            val file = id(90)
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "text/html")
                    .addHeader("X-Preview-Truncated", "TRUE")
                    .body("<p>Slow <a href=\"/next\">reading</a></p>")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "text/html")
                    .body("<p>Short</p>")
                    .build(),
            )

            val long = repo.snapshot(file)
            val short = repo.snapshot(file)

            assertEquals(BookmarksResult.Ok(BookmarkSnapshot("<p>Slow <a href=\"/next\">reading</a></p>", truncated = true)), long)
            assertEquals(BookmarksResult.Ok(BookmarkSnapshot("<p>Short</p>", truncated = false)), short)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/v1/files/$file/preview", request.url.encodedPath)
        }

    @Test
    fun `a readable copy that is gone or unreachable is a failure or offline`() =
        runBlocking {
            server.enqueue(status(404))
            server.enqueue(status(503))

            assertEquals(BookmarksResult.Failed(404), repo.snapshot(id(90)))
            assertEquals(BookmarksResult.Offline, repo.snapshot(id(90)))
        }

    @Test
    fun `saving a copy asks the server with If-Match and returns the bookmark with its copy`() =
        runBlocking {
            server.enqueue(json(bookmarkJson(1, extra = ",\"snapshotFileId\":\"${id(90)}\"")))

            val result = repo.saveSnapshot(bookmark(1)) as BookmarksResult.Ok

            assertEquals(id(90), result.value?.snapshotFileId)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/bookmarks/${id(1)}/snapshot", request.url.encodedPath)
            assertEquals("\"7\"", request.headers["If-Match"])
        }

    @Test
    fun `a page the server cannot read is ok without a bookmark, other refusals stay failures`() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(502)
                    .addHeader("Content-Type", "application/problem+json")
                    .body("""{"status":502,"title":"Bad Gateway"}""")
                    .build(),
            )
            server.enqueue(status(412))
            server.enqueue(status(404))
            server.enqueue(status(503))

            assertEquals(BookmarksResult.Ok(null), repo.saveSnapshot(bookmark(1)))
            assertEquals(BookmarksResult.Failed(412), repo.saveSnapshot(bookmark(1)))
            assertEquals(BookmarksResult.Failed(404), repo.saveSnapshot(bookmark(1)))
            assertEquals(BookmarksResult.Offline, repo.saveSnapshot(bookmark(1)))
        }

    @Test
    fun `to Inbox creates an Inbox task with the title and address and links the bookmark as its origin`() =
        runBlocking {
            val task = id(50)
            server.enqueue(json("""{"id":"$task","title":"Page 1"}""", code = 201))
            server.enqueue(json("""{"id":"${id(51)}"}""", code = 201))

            assertEquals(BookmarksResult.Ok(true), repo.toInbox(bookmark(1), id(50)))

            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/api/v1/tasks", create.url.encodedPath)
            // The client-generated id makes the create idempotent: a retry after a lost answer makes no second task.
            assertEquals(
                """{"id":"$task","title":"Page 1","notesMd":"https://example.org/1","list":"INBOX"}""",
                create.body?.utf8(),
            )
            val link = server.takeRequest()
            assertEquals("POST", link.method)
            assertEquals("/api/v1/links", link.url.encodedPath)
            assertEquals(
                """{"source":{"type":"TASK","id":"$task"},"target":{"type":"BOOKMARK","id":"${id(1)}"},"label":"origin"}""",
                link.body?.utf8(),
            )
        }

    @Test
    fun `to Inbox links the task the server answered with`() =
        runBlocking {
            // The server is the source of truth for the task it made; the link names that task.
            server.enqueue(json("""{"id":"${id(60)}","title":"Page 1"}""", code = 200))
            server.enqueue(json("""{"id":"${id(61)}"}""", code = 201))

            assertEquals(BookmarksResult.Ok(true), repo.toInbox(bookmark(1), id(50)))

            val create = server.takeRequest().body?.utf8()
            assertEquals(true, create?.contains("\"id\":\"${id(50)}\""))
            assertEquals(
                """{"source":{"type":"TASK","id":"${id(60)}"},"target":{"type":"BOOKMARK","id":"${id(1)}"},"label":"origin"}""",
                server.takeRequest().body?.utf8(),
            )
        }

    @Test
    fun `a task title longer than the server allows is cut to 500 characters`() =
        runBlocking {
            server.enqueue(json("""{"id":"${id(50)}"}""", code = 201))
            server.enqueue(json("""{"id":"${id(51)}"}""", code = 201))
            val long = "x".repeat(600)

            repo.toInbox(bookmark(1).copy(title = long, displayTitle = long), id(50))

            val body = server.takeRequest().body!!.utf8()
            assertEquals(true, body.contains("\"title\":\"${"x".repeat(500)}\""))
            assertEquals(false, body.contains("x".repeat(501)))
        }

    @Test
    fun `a task whose link fails is still ok but reported unlinked`() =
        runBlocking {
            server.enqueue(json("""{"id":"${id(50)}"}""", code = 201))
            server.enqueue(status(400))
            server.enqueue(json("""{"id":"${id(52)}"}""", code = 201))
            server.enqueue(status(503))

            assertEquals(BookmarksResult.Ok(false), repo.toInbox(bookmark(1), id(50)))
            assertEquals(BookmarksResult.Ok(false), repo.toInbox(bookmark(1), id(50)))
            assertEquals(4, server.requestCount)
        }

    @Test
    fun `a task whose link has no connection is still ok but reported unlinked`() =
        runBlocking {
            val writeApi = mockk<BookmarksWriteApi>()
            coEvery { writeApi.createTask(any()) } returns
                Response.success(JsonObject(mapOf("id" to JsonPrimitive(id(50).toString()))))
            coEvery { writeApi.createLink(any()) } throws IOException("connection lost")
            val offlineLink = BookmarksRepository(mockk(), writeApi)

            assertEquals(BookmarksResult.Ok(false), offlineLink.toInbox(bookmark(1), id(50)))
            coVerify(exactly = 1) { writeApi.createLink(any()) }
        }

    @Test
    fun `a refused or unreadable task makes no link`() =
        runBlocking {
            server.enqueue(status(400))
            server.enqueue(json("""{"title":"no id"}""", code = 201))
            server.enqueue(json("""{"id":"not-a-uuid"}""", code = 201))
            server.enqueue(status(503))

            assertEquals(BookmarksResult.Failed(400), repo.toInbox(bookmark(1), id(50)))
            assertEquals(BookmarksResult.Failed(null), repo.toInbox(bookmark(1), id(50)))
            assertEquals(BookmarksResult.Failed(null), repo.toInbox(bookmark(1), id(50)))
            assertEquals(BookmarksResult.Offline, repo.toInbox(bookmark(1), id(50)))
            // Only the four task calls, no link call.
            assertEquals(4, server.requestCount)
            repeat(4) { assertEquals("/api/v1/tasks", server.takeRequest().url.encodedPath) }
        }
}
