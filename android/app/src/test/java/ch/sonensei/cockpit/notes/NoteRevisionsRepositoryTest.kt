package ch.sonensei.cockpit.notes

import ch.sonensei.cockpit.core.network.generated.apis.NotesApi
import ch.sonensei.cockpit.core.network.generated.apis.SearchApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.util.UUID

/** A note's earlier versions against a fake server: listed newest first, restored over the note's version. */
class NoteRevisionsRepositoryTest {
    private val server = MockWebServer()
    private lateinit var notes: NotesRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val base = Note(id(1), "Trip", "Trip", "Body", pinned = true, tags = listOf("home"), updatedAt = Instant.EPOCH, version = 3)

    private fun revisionJson(
        number: Int,
        title: String? = "Version $number",
        body: String = "Body $number",
        savedAt: String = "2026-10-0${number}T09:00:00Z",
    ) = """{"number":$number,${title?.let { "\"title\":\"$it\"," } ?: ""}"bodyMd":"$body","savedAt":"$savedAt",
        |"replacedAt":"2026-10-20T09:00:00Z"}
        """.trimMargin().replace("\n", "")

    private fun noteJson(
        version: Long,
        title: String = "Trip",
        body: String = "Body",
    ) = """{"id":"${id(1)}","title":"$title","displayTitle":"$title","bodyMd":"$body","pinned":true,"archived":false,
        |"tags":["home"],"createdAt":"2026-10-01T09:00:00Z","updatedAt":"2026-10-20T09:00:00Z","version":$version}
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
        notes =
            NotesRepository(
                retrofit.create(NotesApi::class.java),
                retrofit.create(NotesWriteApi::class.java),
                retrofit.create(SearchApi::class.java),
            )
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `the revisions are asked for the note and mapped with their number, title, body and save time`() =
        runBlocking {
            server.enqueue(json("""{"items":[${revisionJson(2)},${revisionJson(1, title = null, body = "First line")}]}"""))

            val result = notes.revisions(id(1)) as NotesResult.Ok

            assertEquals(
                listOf(
                    NoteRevision(2, "Version 2", "Body 2", Instant.parse("2026-10-02T09:00:00Z")),
                    NoteRevision(1, null, "First line", Instant.parse("2026-10-01T09:00:00Z")),
                ),
                result.value,
            )
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/v1/notes/${id(1)}/revisions", request.url.encodedPath)
        }

    @Test
    fun `the revisions are newest first, also when the server sends them in another order`() =
        runBlocking {
            server.enqueue(json("""{"items":[${revisionJson(3)},${revisionJson(7)},${revisionJson(5)}]}"""))

            val result = notes.revisions(id(1)) as NotesResult.Ok

            assertEquals(listOf(7, 5, 3), result.value.map { it.number })
        }

    @Test
    fun `a note without revisions has an empty list`() =
        runBlocking {
            server.enqueue(json("""{"items":[]}"""))
            assertEquals(NotesResult.Ok(emptyList<NoteRevision>()), notes.revisions(id(1)))
        }

    @Test
    fun `revisions that cannot load are offline for no connection or a server error, failed for a refusal or bad answer`() =
        runBlocking {
            server.enqueue(status(404))
            assertEquals(NotesResult.Failed(404), notes.revisions(id(1)))
            server.enqueue(status(503))
            assertEquals(NotesResult.Offline, notes.revisions(id(1)))
            server.enqueue(json("""{"items":[{"number":1}]}"""))
            assertEquals(NotesResult.Failed(null), notes.revisions(id(1)))
            server.enqueue(json("""{"items":[${revisionJson(1, savedAt = "yesterday")}]}"""))
            assertEquals(NotesResult.Failed(null), notes.revisions(id(1)))
            server.close()
            assertEquals(NotesResult.Offline, notes.revisions(id(1)))
        }

    @Test
    fun `restore posts the revision number with the note's version as If-Match and returns the restored note`() =
        runBlocking {
            server.enqueue(json(noteJson(version = 4, title = "Version 2", body = "Body 2")))

            val result = notes.restoreRevision(base, 2) as SaveResult.Saved

            assertEquals(4L, result.note.version)
            assertEquals("Version 2", result.note.title)
            assertEquals("Body 2", result.note.bodyMd)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/notes/${id(1)}/revisions/2/restore", request.url.encodedPath)
            assertEquals("\"3\"", request.headers["If-Match"])
        }

    @Test
    fun `a restore refused as outdated (412 or 409) is a conflict with the server's current note`() =
        runBlocking {
            server.enqueue(status(412))
            server.enqueue(json(noteJson(version = 9, body = "Theirs")))
            val outdated = notes.restoreRevision(base, 2) as SaveResult.Conflict
            assertEquals(9L, outdated.current!!.version)
            assertEquals("Theirs", outdated.current.bodyMd)
            server.takeRequest()
            val reload = server.takeRequest()
            assertEquals("GET", reload.method)
            assertEquals("/api/v1/notes/${id(1)}", reload.url.encodedPath)

            server.enqueue(status(409))
            server.enqueue(json(noteJson(version = 10)))
            assertEquals(10L, (notes.restoreRevision(base, 2) as SaveResult.Conflict).current!!.version)
        }

    @Test
    fun `a restore of a note deleted elsewhere is a conflict without a current note`() =
        runBlocking {
            server.enqueue(status(404))
            server.enqueue(status(404))
            assertEquals(SaveResult.Conflict(null), notes.restoreRevision(base, 2))
        }

    @Test
    fun `other refusals of a restore are failures, a server error or no connection is offline`() =
        runBlocking {
            server.enqueue(status(400))
            assertEquals(SaveResult.Failed(400), notes.restoreRevision(base, 2))
            server.enqueue(status(412))
            server.enqueue(status(403))
            assertEquals(SaveResult.Failed(403), notes.restoreRevision(base, 2))
            server.enqueue(status(500))
            assertEquals(SaveResult.Offline, notes.restoreRevision(base, 2))
            server.enqueue(json("{not json"))
            assertEquals(SaveResult.Failed(null), notes.restoreRevision(base, 2))
            server.close()
            assertEquals(SaveResult.Offline, notes.restoreRevision(base, 2))
        }
}
