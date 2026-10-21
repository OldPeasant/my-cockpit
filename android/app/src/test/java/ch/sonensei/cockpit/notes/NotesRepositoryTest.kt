package ch.sonensei.cockpit.notes

import ch.sonensei.cockpit.core.network.generated.apis.NotesApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.UUID

/** The notes calls against a fake server: what is sent, and how answers and refusals are reported. */
class NotesRepositoryTest {
    private val server = MockWebServer()
    private lateinit var notes: NotesRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun noteJson(
        n: Int,
        pinned: Boolean = false,
        updatedAt: String = "2026-10-20T09:00:00Z",
        version: Long = 1,
        title: String? = "Note $n",
        body: String = "Body $n",
    ) = """{"id":"${id(n)}","title":${title?.let { "\"$it\"" } ?: "null"},"displayTitle":"${title ?: "Body"}","bodyMd":"$body",
        |"pinned":$pinned,"archived":false,"tags":["home"],"createdAt":"2026-10-01T09:00:00Z","updatedAt":"$updatedAt",
        |"version":$version}
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

    @Before
    fun setUp() {
        server.start()
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        notes = NotesRepository(retrofit.create(NotesApi::class.java), retrofit.create(NotesWriteApi::class.java))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `the list is pinned first, then the most recently changed, across pages`() =
        runBlocking {
            server.enqueue(
                json(
                    """{"items":[${noteJson(
                        1,
                        updatedAt = "2026-10-19T09:00:00Z",
                    )},${noteJson(2, pinned = true, updatedAt = "2026-10-01T09:00:00Z")}],
                    |"nextCursor":"c2"}
                    """.trimMargin(),
                ),
            )
            server.enqueue(json("""{"items":[${noteJson(3, updatedAt = "2026-10-20T09:00:00Z")}],"nextCursor":null}"""))

            val result = notes.list(tag = "home") as NotesResult.Ok

            assertEquals(listOf(id(2), id(3), id(1)), result.value.map { it.id })
            val first = server.takeRequest().url
            assertEquals("/api/v1/notes", first.encodedPath)
            assertEquals("home", first.queryParameter("tag"))
            assertEquals(null, first.queryParameter("archived"))
            assertEquals("c2", server.takeRequest().url.queryParameter("cursor"))
        }

    @Test
    fun `a failed list is offline for no connection or a server error, failed for a refusal`() =
        runBlocking {
            server.enqueue(status(503))
            assertEquals(NotesResult.Offline, notes.list(null))
            server.enqueue(status(400))
            assertEquals(NotesResult.Failed(400), notes.list(null))
            server.close()
            assertEquals(NotesResult.Offline, notes.list(null))
        }

    @Test
    fun `saving a note patches only title and body with its version as If-Match`() =
        runBlocking {
            server.enqueue(json(noteJson(1, version = 4, title = null, body = "New")))
            val base =
                Note(id(1), "Old", "Old", "Body", pinned = true, tags = listOf("home"), updatedAt = java.time.Instant.EPOCH, version = 3)

            val result = notes.save(base, NoteDraft(title = "  ", bodyMd = "New")) as SaveResult.Saved

            assertEquals(4L, result.note.version)
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("\"3\"", request.headers["If-Match"])
            val sent = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals(setOf("title", "bodyMd"), sent.keys)
            assertEquals(JsonNull, sent["title"])
            assertEquals("New", sent["bodyMd"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a new note is created without a blank title`() =
        runBlocking {
            server.enqueue(json(noteJson(7), code = 201))

            val result = notes.save(null, NoteDraft(title = "", bodyMd = "Hello"))

            assertTrue(result is SaveResult.Saved)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            val sent = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals(setOf("bodyMd"), sent.keys)
        }

    @Test
    fun `a save refused as outdated is a conflict with the server's current note`() =
        runBlocking {
            server.enqueue(status(412))
            server.enqueue(json(noteJson(1, version = 9, body = "Theirs")))
            val base = Note(id(1), null, "Mine", "Mine", false, emptyList(), java.time.Instant.EPOCH, 3)

            val result = notes.save(base, NoteDraft("", "Mine changed")) as SaveResult.Conflict

            assertEquals(9L, result.current!!.version)
            assertEquals("Theirs", result.current.bodyMd)
        }

    @Test
    fun `a save of a deleted note is a conflict without a current note`() =
        runBlocking {
            server.enqueue(status(404))
            server.enqueue(status(404))
            val base = Note(id(1), null, "Mine", "Mine", false, emptyList(), java.time.Instant.EPOCH, 3)

            assertEquals(SaveResult.Conflict(null), notes.save(base, NoteDraft("", "x")))
        }

    @Test
    fun `other refusals are failures, a server error is offline`() =
        runBlocking {
            val base = Note(id(1), null, "Mine", "Mine", false, emptyList(), java.time.Instant.EPOCH, 3)
            server.enqueue(status(400))
            assertEquals(SaveResult.Failed(400), notes.save(base, NoteDraft("", "x")))
            server.enqueue(status(502))
            assertEquals(SaveResult.Offline, notes.save(base, NoteDraft("", "x")))
            server.enqueue(json("{not json"))
            assertEquals(SaveResult.Failed(null), notes.save(base, NoteDraft("", "x")))
        }

    @Test
    fun `an uploaded photo returns the Markdown to insert`() =
        runBlocking {
            val fileId = id(42)
            server.enqueue(
                json(
                    """{"fileId":"$fileId","markdown":"![](cockpit-file:$fileId)","mime":"image/jpeg","name":"p.jpg",
                    |"reference":"cockpit-file:$fileId","size":3}
                    """.trimMargin(),
                    code = 201,
                ),
            )

            val result = notes.uploadImage(id(1), NotePhoto(byteArrayOf(1, 2, 3), "image/jpeg", "p.jpg"))

            assertEquals(NotesResult.Ok("![](cockpit-file:$fileId)"), result)
            val request = server.takeRequest()
            assertEquals("/api/v1/notes/${id(1)}/images", request.url.encodedPath)
            val sent = request.body!!.utf8()
            assertTrue(sent.contains("name=\"file\"; filename=\"p.jpg\""))
            assertTrue(sent.contains("Content-Type: image/jpeg"))
            assertFalse(request.headers["Content-Type"].isNullOrEmpty())
        }

    @Test
    fun `a note on two pages is listed once, and the order holds across pages`() =
        runBlocking {
            // Page 2 repeats note 1 (changed between the page requests) and brings an older pinned note.
            server.enqueue(
                json(
                    """{"items":[${noteJson(1, updatedAt = "2026-10-19T09:00:00Z")},${noteJson(2, updatedAt = "2026-10-18T09:00:00Z")}],
                    |"nextCursor":"c2"}
                    """.trimMargin(),
                ),
            )
            server.enqueue(
                json(
                    """{"items":[${noteJson(1, updatedAt = "2026-10-19T09:00:00Z")},
                    |${noteJson(4, pinned = true, updatedAt = "2026-09-01T09:00:00Z")},
                    |${noteJson(5, pinned = true, updatedAt = "2026-10-02T09:00:00Z")}],"nextCursor":null}
                    """.trimMargin(),
                ),
            )

            val result = notes.list(null) as NotesResult.Ok

            assertEquals(listOf(id(5), id(4), id(1), id(2)), result.value.map { it.id })
            assertEquals(null, server.takeRequest().url.queryParameter("tag"))
        }

    @Test
    fun `an empty list is ok and asks for one page only`() =
        runBlocking {
            server.enqueue(json("""{"items":[],"nextCursor":null}"""))

            assertEquals(NotesResult.Ok(emptyList<Note>()), notes.list("nothing"))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a save refused with 409 is a conflict too`() =
        runBlocking {
            server.enqueue(status(409))
            server.enqueue(json(noteJson(1, version = 5, body = "Theirs")))
            val base = Note(id(1), null, "Mine", "Mine", false, emptyList(), java.time.Instant.EPOCH, 3)

            val result = notes.save(base, NoteDraft("", "Mine changed")) as SaveResult.Conflict

            assertEquals(5L, result.current!!.version)
            assertEquals("PATCH", server.takeRequest().method)
            val reload = server.takeRequest()
            assertEquals("GET", reload.method)
            assertEquals("/api/v1/notes/${id(1)}", reload.url.encodedPath)
        }

    @Test
    fun `a conflict whose current version cannot be loaded is offline or failed, never saved`() =
        runBlocking {
            val base = Note(id(1), null, "Mine", "Mine", false, emptyList(), java.time.Instant.EPOCH, 3)
            server.enqueue(status(412))
            server.enqueue(status(503))
            assertEquals(SaveResult.Offline, notes.save(base, NoteDraft("", "x")))
            server.enqueue(status(412))
            server.enqueue(status(403))
            assertEquals(SaveResult.Failed(403), notes.save(base, NoteDraft("", "x")))
        }

    @Test
    fun `a refused create is a failure, not a conflict`() =
        runBlocking {
            server.enqueue(status(404))
            assertEquals(SaveResult.Failed(404), notes.save(null, NoteDraft("T", "x")))
            server.enqueue(status(409))
            assertEquals(SaveResult.Failed(409), notes.save(null, NoteDraft("T", "x")))
            // No reload of a "current" note was attempted.
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `a title is sent trimmed`() =
        runBlocking {
            server.enqueue(json(noteJson(7), code = 201))

            notes.save(null, NoteDraft(title = "  Trip  ", bodyMd = ""))

            val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals("Trip", sent["title"]!!.jsonPrimitive.content)
            assertEquals("", sent["bodyMd"]!!.jsonPrimitive.content)
        }

    @Test
    fun `tags and a single note are loaded, a missing note is a failure`() =
        runBlocking {
            server.enqueue(json("""{"items":[{"tag":"home","count":3},{"tag":"work","count":1}]}"""))
            assertEquals(NotesResult.Ok(listOf(NoteTag("home", 3), NoteTag("work", 1))), notes.tags())
            server.enqueue(json(noteJson(3, body = "Three")))
            assertEquals("Three", (notes.get(id(3)) as NotesResult.Ok).value.bodyMd)
            server.enqueue(status(404))
            assertEquals(NotesResult.Failed(404), notes.get(id(4)))
        }

    @Test
    fun `an upload without a connection is offline`() =
        runBlocking {
            server.close()
            assertEquals(NotesResult.Offline, notes.uploadImage(id(1), NotePhoto(byteArrayOf(1), "image/png", "p.png")))
        }

    @Test
    fun `an unreadable answer is a failure without a status, never offline or ok`() =
        runBlocking {
            server.enqueue(json("""{"id":"not json"""))
            assertEquals(NotesResult.Failed(null), notes.get(id(1)))
            server.enqueue(json(noteJson(1, updatedAt = "yesterday")))
            assertEquals(NotesResult.Failed(null), notes.get(id(1)))
            server.enqueue(json("""{"items":[{"tag":"home"}]}"""))
            assertEquals(NotesResult.Failed(null), notes.tags())
        }

    @Test
    fun `an image too large is a failure`() =
        runBlocking {
            server.enqueue(status(413))
            assertEquals(NotesResult.Failed(413), notes.uploadImage(id(1), NotePhoto(byteArrayOf(1), "image/png", "p.png")))
        }
}
