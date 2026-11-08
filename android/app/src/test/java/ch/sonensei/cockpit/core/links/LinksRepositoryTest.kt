package ch.sonensei.cockpit.core.links

import ch.sonensei.cockpit.core.network.generated.apis.LinksApi
import ch.sonensei.cockpit.core.network.generated.apis.NotesApi
import ch.sonensei.cockpit.core.network.generated.apis.SearchApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.search.SearchGroup
import ch.sonensei.cockpit.search.SearchHit
import ch.sonensei.cockpit.search.SearchRepository
import ch.sonensei.cockpit.search.SearchResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.UUID

/** `/api/v1/links` (plus task/note creation and the global search) against a fake server: requests, mapping, failures. */
class LinksRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: LinksRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val stamp = "\"createdAt\":\"2026-10-01T09:00:00Z\",\"updatedAt\":\"2026-10-01T09:00:00Z\",\"version\":1"

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

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

    private fun endJson(
        n: Int,
        type: String,
        title: String? = null,
        available: Boolean = true,
        subtitle: String? = null,
        deepLink: String? = null,
    ) = buildString {
        append("{\"id\":\"${id(n)}\",\"type\":\"$type\",\"available\":$available")
        title?.let { append(",\"title\":\"$it\"") }
        subtitle?.let { append(",\"subtitle\":\"$it\"") }
        deepLink?.let { append(",\"deepLink\":\"$it\"") }
        append("}")
    }

    private fun linkJson(
        n: Int,
        direction: String,
        source: String,
        target: String,
        origin: String = "MANUAL",
    ) = """{"id":"${id(n)}","direction":"$direction","origin":"$origin","source":$source,"target":$target,
        |"createdAt":"2026-10-01T09:00:00Z"}
        """.trimMargin().replace("\n", "")

    private val task = ItemRef("TASK", id(1))
    private val taskEnd = endJson(1, "TASK", "Plan the trip")

    private fun taskJson(n: Int) = """{"id":"${id(n)}","title":"Call Anna","list":"INBOX","position":0,"contextIds":[],$stamp}"""

    private fun noteJson(n: Int) =
        """{"id":"${id(n)}","bodyMd":"","displayTitle":"Meeting notes","archived":false,"pinned":false,"tags":[],$stamp}"""

    @Before
    fun setUp() {
        server.start()
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        repository =
            LinksRepository(
                retrofit.create(LinksApi::class.java),
                retrofit.create(TasksApi::class.java),
                retrofit.create(NotesApi::class.java),
                SearchRepository(retrofit.create(SearchApi::class.java)),
            )
    }

    @After
    fun tearDown() = server.close()

    // --- list ---

    @Test
    fun `lists an item's links by type and id, showing the other end in both directions`() =
        runBlocking {
            val note = endJson(3, "NOTE", "Packing list", subtitle = "Travel", deepLink = "/notes/${id(3)}")
            val mail = endJson(4, "EMAIL", "Flight booking")
            val outgoing = linkJson(11, "OUTGOING", taskEnd, note)
            val incoming = linkJson(12, "INCOMING", mail, taskEnd, origin = "SYNC")
            server.enqueue(json("""{"items":[$outgoing,$incoming]}"""))

            val result = repository.list(task)

            val url = server.takeRequest().url
            assertEquals("/api/v1/links", url.encodedPath)
            assertEquals("TASK", url.queryParameter("type"))
            assertEquals(id(1).toString(), url.queryParameter("id"))
            assertEquals(
                LinksResult.Ok(
                    listOf(
                        LinkedItem(id(11), "NOTE", id(3), "Packing list", "Travel", "/notes/${id(3)}", available = true, manual = true),
                        LinkedItem(id(12), "EMAIL", id(4), "Flight booking", null, null, available = true, manual = false),
                    ),
                ),
                result,
            )
        }

    @Test
    fun `an unavailable end keeps its type and id, blank texts are dropped`() =
        runBlocking {
            val gone = endJson(3, "NOTE", " ", available = false, subtitle = "", deepLink = " ")
            server.enqueue(json("""{"items":[${linkJson(11, "OUTGOING", taskEnd, gone)}]}"""))

            val result = repository.list(task) as LinksResult.Ok

            assertEquals(listOf(LinkedItem(id(11), "NOTE", id(3), null, null, null, available = false, manual = true)), result.value)
        }

    @Test
    fun `an item type the server does not know fails without asking`() =
        runBlocking {
            assertEquals(LinksResult.Failed(null), repository.list(ItemRef("UNKNOWN", id(1))))
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `listing reports server errors as offline, refusals with their status, unreadable answers without one`() =
        runBlocking {
            server.enqueue(status(503))
            assertEquals(LinksResult.Offline, repository.list(task))

            server.enqueue(status(400))
            assertEquals(LinksResult.Failed(400), repository.list(task))

            server.enqueue(status(204))
            assertEquals("a success without a body", LinksResult.Failed(204), repository.list(task))

            server.enqueue(json("not json"))
            assertEquals(LinksResult.Failed(null), repository.list(task))

            server.enqueue(json("""{"items":[${linkJson(11, "OUTGOING", taskEnd, endJson(3, "NOT_A_TYPE"))}]}"""))
            assertEquals(LinksResult.Failed(null), repository.list(task))
        }

    @Test
    fun `no connection counts as offline`() =
        runBlocking {
            server.close()

            assertEquals(LinksResult.Offline, repository.list(task))
            assertEquals(LinksResult.Offline, repository.remove(id(11)))
            assertEquals(LinksResult.Offline, repository.createTask("Call Anna"))
        }

    // --- link ---

    @Test
    fun `linking posts both ends and answers with the target as seen from the source`() =
        runBlocking {
            server.enqueue(json(linkJson(11, "OUTGOING", taskEnd, endJson(3, "NOTE", "Packing list")), code = 201))

            val result = repository.link(task, ItemRef("NOTE", id(3)))

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/links", request.url.encodedPath)
            val body = request.json()
            assertEquals(
                mapOf("id" to JsonPrimitive(id(1).toString()), "type" to JsonPrimitive("TASK")),
                body.getValue("source").jsonObject.toMap(),
            )
            assertEquals(
                mapOf("id" to JsonPrimitive(id(3).toString()), "type" to JsonPrimitive("NOTE")),
                body.getValue("target").jsonObject.toMap(),
            )
            assertEquals(
                LinksResult.Ok(LinkedItem(id(11), "NOTE", id(3), "Packing list", null, null, available = true, manual = true)),
                result,
            )
        }

    @Test
    fun `a create answer marked incoming still shows the end that is not the item`() =
        runBlocking {
            // The answer of a create is "as seen from the source"; the item is the source here.
            server.enqueue(json(linkJson(11, "INCOMING", taskEnd, endJson(3, "NOTE", "Packing list")), code = 201))

            val result = repository.link(task, ItemRef("NOTE", id(3))) as LinksResult.Ok

            assertEquals(id(3), result.value.itemId)
            assertEquals("NOTE", result.value.type)
        }

    @Test
    fun `a pair that is linked already is a conflict`() =
        runBlocking {
            server.enqueue(status(409))

            assertEquals(LinksResult.Conflict, repository.link(task, ItemRef("NOTE", id(3))))
        }

    @Test
    fun `linking reports refusals, server errors and unknown types`() =
        runBlocking {
            server.enqueue(status(400))
            assertEquals(LinksResult.Failed(400), repository.link(task, ItemRef("NOTE", id(3))))

            server.enqueue(status(500))
            assertEquals(LinksResult.Offline, repository.link(task, ItemRef("NOTE", id(3))))

            assertEquals(LinksResult.Failed(null), repository.link(ItemRef("UNKNOWN", id(1)), ItemRef("NOTE", id(3))))
            assertEquals(LinksResult.Failed(null), repository.link(task, ItemRef("UNKNOWN", id(3))))
            assertEquals(2, server.requestCount)
        }

    // --- remove ---

    @Test
    fun `removing deletes the link by id`() =
        runBlocking {
            server.enqueue(status(204))

            assertEquals(LinksResult.Ok(Unit), repository.remove(id(11)))

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/v1/links/${id(11)}", request.url.encodedPath)
        }

    @Test
    fun `a link that is gone already counts as removed`() =
        runBlocking {
            server.enqueue(status(404))

            assertEquals(LinksResult.Ok(Unit), repository.remove(id(11)))
        }

    @Test
    fun `removing a sync link is refused, server errors count as offline`() =
        runBlocking {
            server.enqueue(status(409))
            assertEquals(LinksResult.Failed(409), repository.remove(id(11)))

            server.enqueue(status(403))
            assertEquals(LinksResult.Failed(403), repository.remove(id(11)))

            server.enqueue(status(502))
            assertEquals(LinksResult.Offline, repository.remove(id(11)))
        }

    // --- create ---

    @Test
    fun `creating a task posts its title only, so it goes to the Inbox, and answers with its id`() =
        runBlocking {
            server.enqueue(json(taskJson(7), code = 201))

            assertEquals(LinksResult.Ok(id(7)), repository.createTask("Call Anna"))

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/tasks", request.url.encodedPath)
            val body = request.json()
            assertEquals(JsonPrimitive("Call Anna"), body["title"])
            assertTrue("no list: the server's default (Inbox)", body["list"] == null || body["list"] == JsonNull)
        }

    @Test
    fun `creating a note posts its title and answers with its id`() =
        runBlocking {
            server.enqueue(json(noteJson(8), code = 201))

            assertEquals(LinksResult.Ok(id(8)), repository.createNote("Meeting notes"))

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/notes", request.url.encodedPath)
            assertEquals(JsonPrimitive("Meeting notes"), request.json()["title"])
        }

    @Test
    fun `creating reports refusals, server errors and unreadable answers`() =
        runBlocking {
            server.enqueue(status(400))
            assertEquals(LinksResult.Failed(400), repository.createTask("Call Anna"))

            server.enqueue(status(503))
            assertEquals(LinksResult.Offline, repository.createNote("Meeting notes"))

            server.enqueue(json("""{"id":"not-a-uuid"}""", code = 201))
            assertEquals(LinksResult.Failed(null), repository.createNote("Meeting notes"))
        }

    // --- search ---

    @Test
    fun `search uses the global search over all types`() =
        runBlocking {
            server.enqueue(
                json(
                    """{"groups":[{"type":"NOTE","items":[
                    |{"id":"${id(3)}","type":"NOTE","title":"Packing list","updatedAt":"2026-10-01T09:00:00Z"}]}]}
                    """.trimMargin().replace("\n", ""),
                ),
            )

            val result = repository.search("pack")

            val url = server.takeRequest().url
            assertEquals("/api/v1/search", url.encodedPath)
            assertEquals("pack", url.queryParameter("q"))
            assertEquals(emptyList<String>(), url.queryParameterValues("types"))
            assertEquals(SearchResult.Ok(listOf(SearchGroup("NOTE", listOf(SearchHit(id(3), "NOTE", "Packing list", null, null))))), result)
        }
}
