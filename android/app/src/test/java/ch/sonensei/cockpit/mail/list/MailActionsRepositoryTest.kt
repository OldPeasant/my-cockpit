package ch.sonensei.cockpit.mail.list

import ch.sonensei.cockpit.core.network.generated.apis.MailAccountsApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.UUID

/** Bulk actions against a fake server: the request body, batching at the server's limit, and per-mail results. */
class MailActionsRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: MailRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun json(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    /** An answer where the mails in [ok] were done and every other id of [ids] was refused. */
    private fun results(
        ids: List<UUID>,
        ok: Set<UUID> = ids.toSet(),
    ) = json(
        ids.joinToString(",", """{"results":[""", "]}") { i ->
            if (i in ok) """{"id":"$i","ok":true}""" else """{"id":"$i","ok":false,"errorCode":"NOT_FOUND"}"""
        },
    )

    private fun body(): JsonObject = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

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
    fun `a bulk mark sends the wire name and all ids, without a target folder`() =
        runBlocking {
            val ids = listOf(id(1), id(2), id(3))
            listOf(MailListAction.READ, MailListAction.UNREAD, MailListAction.FLAG, MailListAction.UNFLAG).forEach { action ->
                server.enqueue(results(ids))

                assertEquals(MailResult.Ok(ids.toSet()), repository.actAll(ids, action))

                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/api/v1/mail/messages/actions", request.url.encodedPath)
                val sent = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                assertEquals(action.name, sent.getValue("action").jsonPrimitive.content)
                assertEquals(ids, sent.ids())
                // The server refuses a targetFolderId for anything but MOVE: it must not be sent at all.
                assertFalse("$action", "targetFolderId" in sent)
            }
        }

    @Test
    fun `archive, delete and spam leave the target folder out too`() =
        runBlocking {
            listOf(MailListAction.ARCHIVE, MailListAction.DELETE, MailListAction.SPAM).forEach { action ->
                server.enqueue(results(listOf(id(1))))
                repository.actAll(listOf(id(1)), action)
                assertFalse("$action", "targetFolderId" in body())
            }
        }

    @Test
    fun `a move names the target folder`() =
        runBlocking {
            server.enqueue(results(listOf(id(1), id(2))))

            val result = repository.actAll(listOf(id(1), id(2)), MailListAction.MOVE, id(92))

            assertEquals(MailResult.Ok(setOf(id(1), id(2))), result)
            val sent = body()
            assertEquals("MOVE", sent.getValue("action").jsonPrimitive.content)
            assertEquals("${id(92)}", sent.getValue("targetFolderId").jsonPrimitive.content)
        }

    @Test
    fun `the single-mail action and mark read send no target folder`() =
        runBlocking {
            server.enqueue(results(listOf(id(1))))
            server.enqueue(results(listOf(id(2))))
            repository.act(id(1), MailListAction.ARCHIVE)
            repository.markRead(id(2))
            assertFalse("targetFolderId" in body())
            assertFalse("targetFolderId" in body())
        }

    @Test
    fun `only the mails the server did are in the result`() =
        runBlocking {
            val ids = listOf(id(1), id(2), id(3))
            // id(3) is missing from the answer, id(2) refused, and an unasked id is ignored.
            server.enqueue(
                json(
                    """{"results":[{"id":"${id(1)}","ok":true},{"id":"${id(2)}","ok":false,"errorCode":"NOT_FOUND"},""" +
                        """{"id":"${id(9)}","ok":true}]}""",
                ),
            )

            assertEquals(MailResult.Ok(setOf(id(1))), repository.actAll(ids, MailListAction.FLAG))
        }

    @Test
    fun `more ids than the server takes go in batches of at most the limit`() =
        runBlocking {
            val ids = (1..MailRepository.MAX_ACTION_IDS + 1).map(::id)
            val first = ids.take(MailRepository.MAX_ACTION_IDS)
            val second = ids.drop(MailRepository.MAX_ACTION_IDS)
            server.enqueue(results(first, ok = first.toSet() - id(5)))
            server.enqueue(results(second))

            val result = repository.actAll(ids, MailListAction.ARCHIVE)

            assertEquals(MailResult.Ok(ids.toSet() - id(5)), result)
            assertEquals(first, body().ids())
            assertEquals(second, body().ids())
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `exactly the limit is one request, and duplicates count once`() =
        runBlocking {
            val ids = (1..MailRepository.MAX_ACTION_IDS).map(::id)
            server.enqueue(results(ids))

            val result = repository.actAll(ids + ids.take(3), MailListAction.READ)

            assertEquals(MailResult.Ok(ids.toSet()), result)
            assertEquals(ids, body().ids())
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a batch that fails as a whole fails the call, even after an earlier batch went through`() =
        runBlocking {
            val ids = (1..MailRepository.MAX_ACTION_IDS + 2).map(::id)
            server.enqueue(results(ids.take(MailRepository.MAX_ACTION_IDS)))
            server.enqueue(status(503))

            assertEquals(MailResult.Offline, repository.actAll(ids, MailListAction.DELETE))
        }

    @Test
    fun `a refused request is a failure with its status, no connection is offline`() =
        runBlocking {
            server.enqueue(status(400))
            assertEquals(MailResult.Failed(400), repository.actAll(listOf(id(1)), MailListAction.MOVE, id(92)))

            server.close()
            assertEquals(MailResult.Offline, repository.actAll(listOf(id(1)), MailListAction.READ))
        }

    @Test
    fun `no ids sends nothing`() =
        runBlocking {
            assertEquals(MailResult.Ok(emptySet<UUID>()), repository.actAll(emptyList(), MailListAction.READ))
            assertEquals(0, server.requestCount)
        }
}
