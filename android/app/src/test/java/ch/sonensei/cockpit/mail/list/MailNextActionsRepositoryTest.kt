package ch.sonensei.cockpit.mail.list

import ch.sonensei.cockpit.core.network.generated.apis.MailAccountsApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.UUID

/**
 * "Next action & archive" against a fake server: the request body, batching at 100 ids, the per-mail results (archived
 * or not, with the server's reason) and the failures of the whole call.
 */
class MailNextActionsRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: MailRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun task(n: Int): UUID = UUID.fromString("00000000-0000-7000-9000-%012d".format(n))

    private fun json(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    /** One result per id: a task numbered like the mail, archived unless the id is in [notArchived]. */
    private fun results(
        ids: List<UUID>,
        notArchived: Set<UUID> = emptySet(),
    ) = json(
        ids.joinToString(",", """{"results":[""", "]}") { i ->
            val n = i.toString().takeLast(12).toInt()
            val task = """{"id":"${task(n)}","title":"Subject $n","list":"NEXT"}"""
            if (i in notArchived) {
                """{"id":"$i","task":$task,"archived":false,"archiveErrorCode":"NO_ROLE_FOLDER"}"""
            } else {
                """{"id":"$i","task":$task,"archived":true}"""
            }
        },
    )

    private fun JsonObject.ids() = getValue("ids").jsonArray.map { UUID.fromString(it.jsonPrimitive.content) }

    @Before
    fun setUp() {
        server.start()
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        repository = MailRepository(retrofit.create(MailApi::class.java), retrofit.create(MailAccountsApi::class.java))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `the call posts the ids with list NEXT and archive true, and maps each result`() =
        runBlocking {
            val ids = listOf(id(1), id(2))
            server.enqueue(results(ids, notArchived = setOf(id(2))))

            val result = repository.toNextActions(ids)

            assertEquals(
                MailResult.Ok(
                    listOf(
                        MailNextAction(id(1), task(1), "Subject 1", archived = true),
                        MailNextAction(id(2), task(2), "Subject 2", archived = false, archiveErrorCode = "NO_ROLE_FOLDER"),
                    ),
                ),
                result,
            )
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/mail/messages/to-tasks", request.url.encodedPath)
            val sent = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals(ids, sent.ids())
            // The defaults are sent explicitly, so the server's own defaults can never change what the button does.
            assertEquals("NEXT", sent.getValue("list").jsonPrimitive.content)
            assertEquals(true, sent.getValue("archive").jsonPrimitive.boolean)
        }

    @Test
    fun `an error code next to an archived mail is ignored, a missing title is empty`() =
        runBlocking {
            server.enqueue(
                json(
                    """{"results":[{"id":"${id(1)}","task":{"id":"${task(1)}"},"archived":true,"archiveErrorCode":"STALE"}]}""",
                ),
            )

            assertEquals(
                MailResult.Ok(listOf(MailNextAction(id(1), task(1), "", archived = true))),
                repository.toNextActions(listOf(id(1))),
            )
        }

    @Test
    fun `more than 100 mails are sent in batches of at most 100, results in order, duplicates once`() =
        runBlocking {
            val ids = (1..250).map(::id)
            ids.chunked(100).forEach { server.enqueue(results(it)) }

            val result = repository.toNextActions(ids + id(1))

            val sent = (1..3).map { Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject.ids() }
            assertEquals(listOf(100, 100, 50), sent.map { it.size })
            assertEquals(ids, sent.flatten())
            assertEquals(3, server.requestCount)
            assertEquals(ids, (result as MailResult.Ok).value.map { it.mailId })
        }

    @Test
    fun `exactly 100 mails go in one request`() =
        runBlocking {
            val ids = (1..100).map(::id)
            server.enqueue(results(ids))

            repository.toNextActions(ids)

            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a refusal fails the call with its status, a server error or no answer counts as offline`() =
        runBlocking {
            server.enqueue(status(404))
            assertEquals(MailResult.Failed(404), repository.toNextActions(listOf(id(1))))

            server.enqueue(status(400))
            assertEquals(MailResult.Failed(400), repository.toNextActions(listOf(id(1))))

            server.enqueue(status(503))
            assertEquals(MailResult.Offline, repository.toNextActions(listOf(id(1))))

            server.close()
            assertEquals(MailResult.Offline, repository.toNextActions(listOf(id(1))))
        }

    @Test
    fun `a result without its task or an unreadable answer fails the call`() =
        runBlocking {
            server.enqueue(json("""{"results":[{"id":"${id(1)}","archived":false}]}"""))
            assertEquals(MailResult.Failed(null), repository.toNextActions(listOf(id(1))))

            server.enqueue(json("""{"results":[{"id":"not-a-uuid","task":{"id":"${task(1)}"}}]}"""))
            assertEquals(MailResult.Failed(null), repository.toNextActions(listOf(id(1))))

            server.enqueue(json("""{"results":"""))
            assertEquals(MailResult.Failed(null), repository.toNextActions(listOf(id(1))))
        }

    @Test
    fun `a failed later batch fails the whole call, after the earlier batch was sent`() =
        runBlocking {
            val ids = (1..150).map(::id)
            server.enqueue(results(ids.take(100)))
            server.enqueue(status(404))

            assertEquals(MailResult.Failed(404), repository.toNextActions(ids))
            // The first batch reached the server: its tasks exist and its mails were archived (see the test report, TW-1).
            assertEquals(2, server.requestCount)
        }
}
