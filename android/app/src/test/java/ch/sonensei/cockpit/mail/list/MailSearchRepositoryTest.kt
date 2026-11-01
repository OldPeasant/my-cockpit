package ch.sonensei.cockpit.mail.list

import ch.sonensei.cockpit.core.network.generated.apis.MailAccountsApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.UUID

/** `GET /api/v1/mail/search`: the scope and options asked for, and how hits, `hasMore` and server failures are read. */
class MailSearchRepositoryTest {
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

    private fun summaryJson(n: Int) =
        """{"id":"${id(n)}","accountId":"${id(80)}","folderId":"${id(90)}","subject":"Invoice $n","snippet":"",""" +
            """"from":[{"name":"Anna Example","address":"anna@example.org"}],"receivedAt":"2026-10-21T08:00:00Z","seen":true}"""

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
    fun `a folder search names account and folder, and asks the cache only`() =
        runBlocking {
            server.enqueue(json("""{"items":[${summaryJson(1)},${summaryJson(2)}],"hasMore":false,"server":null}"""))

            val result = repository.search("invoice 2026", Mailbox.Folder(id(80), id(90)), server = false)

            val request = server.takeRequest()
            assertEquals("/api/v1/mail/search", request.url.encodedPath)
            assertEquals("invoice 2026", request.url.queryParameter("q"))
            assertEquals("${id(80)}", request.url.queryParameter("accountId"))
            assertEquals("${id(90)}", request.url.queryParameter("folderId"))
            assertNull(request.url.queryParameter("server"))
            assertEquals("50", request.url.queryParameter("limit"))
            val value = (result as MailResult.Ok).value
            assertEquals(listOf(id(1), id(2)), value.items.map { it.id })
            assertEquals("Invoice 1", value.items.first().subject)
            assertFalse(value.hasMore)
            assertEquals(emptyList<UUID>(), value.failedAccounts)
        }

    @Test
    fun `the unified inbox searches all folders, server=true asks the mail servers and reads their failures`() =
        runBlocking {
            server.enqueue(
                json(
                    """{"items":[${summaryJson(1)}],"hasMore":true,"server":{"failures":[""" +
                        """{"accountId":"${id(81)}","errorCode":"TIMEOUT","message":"timeout"},""" +
                        """{"accountId":"${id(81)}","errorCode":"TIMEOUT"},{"accountId":"not-a-uuid"}],""" +
                        """"loaded":3,"searchedFolders":4,"truncated":false}}""",
                ),
            )

            val result = repository.search("invoice", Mailbox.Unified, server = true)

            val request = server.takeRequest()
            assertNull(request.url.queryParameter("accountId"))
            assertNull(request.url.queryParameter("folderId"))
            assertEquals("true", request.url.queryParameter("server"))
            val value = (result as MailResult.Ok).value
            assertTrue(value.hasMore)
            assertEquals(listOf(id(81)), value.failedAccounts)
        }

    @Test
    fun `a truncated server search also means only the newest results are listed`() =
        runBlocking {
            server.enqueue(json("""{"items":[],"hasMore":false,"server":{"failures":[],"truncated":true}}"""))

            val value = (repository.search("x", Mailbox.Unified, server = true) as MailResult.Ok).value

            assertTrue(value.hasMore)
            assertTrue(value.items.isEmpty())
        }

    @Test
    fun `a refusal keeps its status, a 5xx is offline`() =
        runBlocking {
            server.enqueue(MockResponse.Builder().code(400).build())
            server.enqueue(MockResponse.Builder().code(503).build())

            assertEquals(MailResult.Failed(400), repository.search("x", Mailbox.Unified, server = false))
            assertEquals(MailResult.Offline, repository.search("x", Mailbox.Unified, server = false))
        }
}
