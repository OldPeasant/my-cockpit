package ch.sonensei.cockpit.journal

import ch.sonensei.cockpit.core.network.generated.apis.JournalApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.notes.NotePhoto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The journal calls against a fake server: what is sent, and how answers and refusals are reported. */
class JournalRepositoryTest {
    private val server = MockWebServer()
    private lateinit var journal: JournalRepository
    private val day = LocalDate.of(2026, 10, 22)
    private val id = UUID.fromString("00000000-0000-7000-8000-000000000001")

    private fun entryJson(
        body: String = "Walked by the lake",
        version: Long = 1,
        mood: Int? = 4,
    ) = """{"id":"$id","date":"2026-10-22","displayTitle":"Journal 2026-10-22","bodyMd":"$body","mood":${mood ?: "null"},
        |"tags":["walk"],"createdAt":"2026-10-22T07:00:00Z","updatedAt":"2026-10-22T08:00:00Z","version":$version}
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

    private val base = JournalEntry(id, day, "Walked", 4, listOf("walk"), Instant.EPOCH, 3)

    @Before
    fun setUp() {
        server.start()
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        journal = JournalRepository(retrofit.create(JournalApi::class.java))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `loads the entry of the day`() =
        runBlocking {
            server.enqueue(json(entryJson()))

            val entry = (journal.get(day) as JournalResult.Ok).value!!

            assertEquals("Walked by the lake", entry.bodyMd)
            assertEquals(4, entry.mood)
            assertEquals(listOf("walk"), entry.tags)
            assertEquals("/api/v1/journal/entries/2026-10-22", server.takeRequest().url.encodedPath)
        }

    @Test
    fun `a day without an entry loads as none`() =
        runBlocking {
            server.enqueue(status(404))

            assertEquals(JournalResult.Ok(null), journal.get(day))
        }

    @Test
    fun `a new entry is created without If-Match`() =
        runBlocking {
            server.enqueue(json(entryJson(body = "First words", mood = null), code = 201))

            val result = journal.saveEntry(day, null, JournalDraft("First words")) as JournalSaveResult.Saved

            assertEquals("First words", result.entry.bodyMd)
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertNull(request.headers["If-Match"])
            val sent = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals("First words", sent["bodyMd"]!!.jsonPrimitive.content)
            assertEquals(0, sent["tags"]!!.jsonArray.size)
        }

    @Test
    fun `a save replaces the entry over its version with the draft's mood and tags`() =
        runBlocking {
            server.enqueue(json(entryJson(body = "Walked far", version = 4)))

            val result = journal.saveEntry(day, base, base.draft.copy(bodyMd = "Walked far")) as JournalSaveResult.Saved

            assertEquals(4L, result.entry.version)
            val request = server.takeRequest()
            assertEquals("\"3\"", request.headers["If-Match"])
            val sent = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals(4, sent["mood"]!!.jsonPrimitive.int)
            assertEquals("walk", sent["tags"]!!.jsonArray[0].jsonPrimitive.content)
        }

    @Test
    fun `a save refused as outdated is a conflict with the server's entry`() =
        runBlocking {
            server.enqueue(status(412))
            server.enqueue(json(entryJson(body = "Theirs", version = 9)))

            val result = journal.saveEntry(day, base, base.draft.copy(bodyMd = "Mine")) as JournalSaveResult.Conflict

            assertEquals("Theirs", result.current!!.bodyMd)
        }

    @Test
    fun `a create refused because the day got an entry elsewhere is a conflict`() =
        runBlocking {
            // The server requires If-Match once the day has an entry: 428 Precondition Required.
            server.enqueue(status(428))
            server.enqueue(json(entryJson(body = "From the web")))

            val result = journal.saveEntry(day, null, JournalDraft("Mine")) as JournalSaveResult.Conflict

            assertEquals("From the web", result.current!!.bodyMd)
        }

    @Test
    fun `a create that loses the race against a concurrent create is a conflict`() =
        runBlocking {
            server.enqueue(status(409))
            server.enqueue(json(entryJson(body = "From the web")))

            val result = journal.saveEntry(day, null, JournalDraft("Mine")) as JournalSaveResult.Conflict

            assertEquals("From the web", result.current!!.bodyMd)
        }

    @Test
    fun `a save over an entry deleted elsewhere is a conflict without a current entry`() =
        runBlocking {
            server.enqueue(status(412))
            server.enqueue(status(404))

            assertEquals(JournalSaveResult.Conflict(null), journal.saveEntry(day, base, base.draft.copy(bodyMd = "Mine")))
        }

    @Test
    fun `a conflict whose current entry cannot be fetched is reported as that problem, never as a conflict`() =
        runBlocking {
            // The server is down for the fetch: the save stays retryable instead of offering a decision without data.
            server.enqueue(status(412))
            server.enqueue(status(503))
            assertEquals(JournalSaveResult.Offline, journal.saveEntry(day, base, base.draft.copy(bodyMd = "Mine")))

            server.enqueue(status(428))
            server.enqueue(status(403))
            assertEquals(JournalSaveResult.Failed(403), journal.saveEntry(day, null, JournalDraft("Mine")))
        }

    @Test
    fun `an unreadable answer is a failure without a status`() =
        runBlocking {
            server.enqueue(json("""{"id":"not-a-uuid"}"""))
            assertEquals(JournalResult.Failed(null), journal.get(day))

            server.enqueue(json("""{"id":"$id","date":"2026-10-22","bodyMd":"x","tags":[],"updatedAt":"yesterday","version":1}"""))
            assertEquals(JournalSaveResult.Failed(null), journal.saveEntry(day, base, base.draft.copy(bodyMd = "x")))
        }

    @Test
    fun `server errors are offline, refusals are failures`() =
        runBlocking {
            server.enqueue(status(503))
            assertEquals(JournalSaveResult.Offline, journal.saveEntry(day, base, base.draft.copy(bodyMd = "Mine")))
            server.enqueue(status(400))
            assertEquals(JournalSaveResult.Failed(400), journal.saveEntry(day, base, base.draft.copy(bodyMd = "Mine")))
            server.enqueue(status(500))
            assertEquals(JournalResult.Offline, journal.get(day))
        }

    @Test
    fun `a save sends the draft's mood and tags`() =
        runBlocking {
            server.enqueue(json(entryJson(mood = 2)))

            journal.saveEntry(day, base, JournalDraft("Walked", 2, listOf("rain", "walk")))

            val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals(2, sent["mood"]!!.jsonPrimitive.int)
            assertEquals(listOf("rain", "walk"), sent["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        }

    @Test
    fun `the text-only save keeps the entry's mood and tags`() =
        runBlocking {
            server.enqueue(json(entryJson()))

            journal.save(day, base, "Walked twice")

            val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals("Walked twice", sent["bodyMd"]!!.jsonPrimitive.content)
            assertEquals(4, sent["mood"]!!.jsonPrimitive.int)
            assertEquals("walk", sent["tags"]!!.jsonArray[0].jsonPrimitive.content)
        }

    @Test
    fun `the days of a range come with their moods`() =
        runBlocking {
            server.enqueue(
                json("""{"from":"2026-10-01","to":"2026-10-31","items":[{"date":"2026-10-03","mood":5},{"date":"2026-10-09"}]}"""),
            )

            val days = journal.days(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))

            assertEquals(JournalResult.Ok(mapOf(LocalDate.of(2026, 10, 3) to 5, LocalDate.of(2026, 10, 9) to null)), days)
            val url = server.takeRequest().url
            assertEquals("/api/v1/journal/heatmap", url.encodedPath)
            assertEquals("2026-10-01", url.queryParameter("from"))
            assertEquals("2026-10-31", url.queryParameter("to"))
        }

    @Test
    fun `on this day returns the earlier years only`() =
        runBlocking {
            val earlier = entryJson(body = "Years ago").replace("2026-10-22", "2024-10-22")
            server.enqueue(json("""{"date":"2026-10-22","items":[${entryJson()},$earlier]}"""))

            val result = (journal.onThisDay(day) as JournalResult.Ok).value

            assertEquals(listOf(LocalDate.of(2024, 10, 22)), result.map { it.date })
            val url = server.takeRequest().url
            assertEquals("/api/v1/journal/on-this-day", url.encodedPath)
            assertEquals("2026-10-22", url.queryParameter("date"))

            server.enqueue(status(503))
            assertEquals(JournalResult.Offline, journal.onThisDay(day))
            server.enqueue(status(400))
            assertEquals(JournalResult.Failed(400), journal.days(day, day))
        }

    @Test
    fun `no connection is offline`() =
        runBlocking {
            server.close()
            assertEquals(JournalResult.Offline, journal.get(day))
        }

    @Test
    fun `a photo is uploaded as the part file and its Markdown is returned`() =
        runBlocking {
            server.enqueue(
                json(
                    """{"fileId":"$id","markdown":"![lake.jpg](cockpit-file:$id)","mime":"image/jpeg","name":"lake.jpg",
                    |"reference":"cockpit-file:$id","size":3}
                    """.trimMargin(),
                    code = 201,
                ),
            )

            val result = journal.uploadImage(NotePhoto(byteArrayOf(1, 2, 3), "image/jpeg", "lake.jpg"))

            assertEquals(JournalResult.Ok("![lake.jpg](cockpit-file:$id)"), result)
            val request = server.takeRequest()
            assertEquals("/api/v1/journal/images", request.url.encodedPath)
            assertTrue(request.body!!.utf8().contains("name=\"file\"; filename=\"lake.jpg\""))
        }
}
