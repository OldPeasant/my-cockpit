package ch.sonensei.cockpit.mail.list

import ch.sonensei.cockpit.core.network.generated.apis.MailAccountsApi
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

/** "Load older from server" against a fake server: the load-older call and reading the list up to its new end. */
class MailLoadOlderRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: MailRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val folder = Mailbox.Folder(id(80), id(91))

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

    private fun summaryJson(n: Int) = """{"id":"${id(n)}","accountId":"${id(80)}","folderId":"${id(91)}","subject":"Subject $n"}"""

    private fun page(
        numbers: IntRange,
        cursor: String?,
        vararg extra: Int,
    ): MockResponse {
        val items = (numbers.toList() + extra.toList()).joinToString(",") { summaryJson(it) }
        val next = cursor?.let { "\"$it\"" } ?: "null"
        return json("""{"items":[$items],"nextCursor":$next}""")
    }

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
    fun `load older posts to the folder with the server's default limit and reads how many were added`() =
        runBlocking {
            server.enqueue(json("""{"loaded":42,"hasMore":true}"""))

            assertEquals(MailResult.Ok(42), repository.loadOlder(folder))

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/mail/folders/${id(91)}/load-older", request.url.encodedPath)
            assertEquals("${MailRepository.LOAD_OLDER_LIMIT}", request.url.queryParameter("limit"))
        }

    @Test
    fun `an answer without counts means nothing was added`() =
        runBlocking {
            server.enqueue(json("{}"))
            assertEquals(MailResult.Ok(0), repository.loadOlder(folder))
        }

    @Test
    fun `a refused load older is a failure with its status, an unreachable server is offline`() =
        runBlocking {
            server.enqueue(status(404))
            server.enqueue(status(503))

            assertEquals(MailResult.Failed(404), repository.loadOlder(folder))
            assertEquals(MailResult.Offline, repository.loadOlder(folder))

            server.close()
            assertEquals(MailResult.Offline, repository.loadOlder(folder))
        }

    @Test
    fun `messages up to a count read one page when it fits`() =
        runBlocking {
            server.enqueue(page(1..3, null))

            val result = (repository.messagesUpTo(folder, 5) as MailResult.Ok).value

            assertEquals((1..3).map(::id), result.items.map { it.id })
            assertNull(result.nextCursor)
            val request = server.takeRequest()
            assertEquals("${id(80)}", request.url.queryParameter("accountId"))
            assertEquals("${id(91)}", request.url.queryParameter("folderId"))
            assertNull(request.url.queryParameter("cursor"))
            assertEquals("5", request.url.queryParameter("limit"))
        }

    @Test
    fun `messages up to a count read page by page in the server's largest pages, without duplicates`() =
        runBlocking {
            val max = MailRepository.MAX_PAGE_SIZE
            server.enqueue(page(1..max, "c2"))
            // The list moved meanwhile: the last mail of the first page comes again.
            server.enqueue(page(max + 1..max + 50, "c3", max))

            val result = (repository.messagesUpTo(folder, max + 50) as MailResult.Ok).value

            assertEquals((1..max + 50).map(::id), result.items.map { it.id })
            // Enough mails were read: the cursor after them is kept for infinite scroll.
            assertEquals("c3", result.nextCursor)
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("$max", first.url.queryParameter("limit"))
            assertEquals("c2", second.url.queryParameter("cursor"))
            assertEquals("50", second.url.queryParameter("limit"))
        }

    @Test
    fun `messages up to a count stop at an empty page or an empty cursor`() =
        runBlocking {
            server.enqueue(page(1..2, "c2"))
            server.enqueue(json("""{"items":[],"nextCursor":"c3"}"""))
            server.enqueue(page(1..2, ""))

            val stopped = (repository.messagesUpTo(folder, 10) as MailResult.Ok).value
            assertEquals(listOf(id(1), id(2)), stopped.items.map { it.id })
            assertEquals("c3", stopped.nextCursor)

            val ended = (repository.messagesUpTo(folder, 10) as MailResult.Ok).value
            assertNull(ended.nextCursor)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun `messages up to a count of zero still ask for one mail`() =
        runBlocking {
            server.enqueue(page(1..1, null))
            repository.messagesUpTo(folder, 0)
            assertEquals("1", server.takeRequest().url.queryParameter("limit"))
        }

    @Test
    fun `a failed page fails the whole read`() =
        runBlocking {
            server.enqueue(page(1..2, "c2"))
            server.enqueue(status(500))

            assertEquals(MailResult.Offline, repository.messagesUpTo(folder, 10))
        }
}
