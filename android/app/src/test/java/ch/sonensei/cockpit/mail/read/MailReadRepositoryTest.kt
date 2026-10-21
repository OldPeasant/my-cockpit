package ch.sonensei.cockpit.mail.read

import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.mail.list.MailApi
import ch.sonensei.cockpit.mail.list.MailResult
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

/** One message and its GTD actions against a fake server. */
class MailReadRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: MailReadRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

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
        repository = MailReadRepository(retrofit.create(MailApi::class.java))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `a message is read with its headers, sanitized body and listed attachments`() =
        runBlocking {
            // The id answered is the message's current copy (it moved), and differs from the one asked for.
            server.enqueue(
                json(
                    """{"id":"${id(2)}","accountId":"${id(80)}","folderId":"${id(90)}","subject":"Quarterly report",
                    |"from":[{"name":"Anna Example","address":"anna@example.org"}],
                    |"to":[{"name":null,"address":"me@example.org"},{"name":"Ben Example","address":"ben@example.org"}],
                    |"cc":[{"name":"Carla Example","address":null}],
                    |"sentAt":"2026-10-21T08:00:00+02:00","receivedAt":"2026-10-21T06:01:00Z","seen":false,
                    |"body":{"html":"<p>Hi</p>","text":"Hi","remoteImageCount":3,"remoteImagesBlocked":true},
                    |"attachments":[{"partId":"2","filename":"report.pdf","contentType":"application/pdf","sizeBytes":1024,"inline":false},
                    |{"partId":"3","filename":"logo.png","contentType":"image/png","inline":true},
                    |{"partId":"4","filename":" ","contentType":"text/plain","inline":false}],
                    |"messageId":"<fictitious@example.org>","version":5}
                    """.trimMargin().replace("\n", ""),
                ),
            )

            val message = (repository.message(id(1)) as MailResult.Ok).value

            assertEquals("/api/v1/mail/messages/${id(1)}", server.takeRequest().url.encodedPath)
            assertEquals(id(2), message.id)
            assertEquals("Quarterly report", message.subject)
            assertEquals("Anna Example <anna@example.org>", message.from)
            assertEquals(listOf("me@example.org", "Ben Example <ben@example.org>"), message.to)
            assertEquals(listOf("Carla Example"), message.cc)
            // The read view shows when it was sent.
            assertEquals(Instant.parse("2026-10-21T06:00:00Z"), message.date)
            assertEquals(false, message.seen)
            assertEquals("<p>Hi</p>", message.html)
            assertEquals("Hi", message.text)
            assertEquals(3, message.remoteImagesBlocked)
            assertEquals(listOf(MailAttachment("2", "report.pdf", 1024), MailAttachment("4", "4", null)), message.attachments)
        }

    @Test
    fun `a message without body, subject or dates is still shown`() =
        runBlocking {
            server.enqueue(json("""{"id":"${id(1)}","subject":null,"sentAt":null,"receivedAt":"2026-10-21T06:01:00Z","body":null}"""))
            server.enqueue(
                json("""{"id":"${id(1)}","body":{"html":null,"text":"Plain","remoteImageCount":2,"remoteImagesBlocked":false}}"""),
            )

            val bare = (repository.message(id(1)) as MailResult.Ok).value
            assertEquals("", bare.subject)
            assertEquals("", bare.from)
            assertEquals("", bare.html)
            assertEquals("", bare.text)
            assertEquals(Instant.parse("2026-10-21T06:01:00Z"), bare.date)

            // Images counted but not blocked are not reported as blocked.
            val plain = (repository.message(id(1)) as MailResult.Ok).value
            assertEquals("Plain", plain.text)
            assertEquals(0, plain.remoteImagesBlocked)
        }

    @Test
    fun `a gone message, a refusal and a server failure are told apart`() =
        runBlocking {
            server.enqueue(status(404))
            server.enqueue(status(403))
            server.enqueue(status(502))
            server.enqueue(json("""{"subject":"no id"}"""))

            assertEquals(MailResult.Failed(404), repository.message(id(1)))
            assertEquals(MailResult.Failed(403), repository.message(id(1)))
            assertEquals(MailResult.Offline, repository.message(id(1)))
            assertEquals(MailResult.Failed(null), repository.message(id(1)))

            server.close()
            assertEquals(MailResult.Offline, repository.message(id(1)))
        }

    @Test
    fun `to-task and to-waiting post to their endpoints and return the new item's title`() =
        runBlocking {
            val created = """{"id":"${id(50)}","linkId":"${id(51)}","messageId":"${id(1)}","title":"Quarterly report","type":"TASK"}"""
            server.enqueue(json(created, code = 201))
            server.enqueue(json("""{"id":"${id(52)}","linkId":"${id(53)}","type":"TASK"}""", code = 201))

            assertEquals(MailResult.Ok("Quarterly report"), repository.gtd(id(1), MailGtdAction.TASK))
            assertEquals(MailResult.Ok(""), repository.gtd(id(1), MailGtdAction.WAITING))

            val first = server.takeRequest()
            assertEquals("POST", first.method)
            assertEquals("/api/v1/mail/messages/${id(1)}/to-task", first.url.encodedPath)
            assertEquals("/api/v1/mail/messages/${id(1)}/to-waiting", server.takeRequest().url.encodedPath)
        }

    @Test
    fun `a refused or unreachable GTD action is reported`() =
        runBlocking {
            server.enqueue(status(409))
            server.enqueue(status(503))

            assertEquals(MailResult.Failed(409), repository.gtd(id(1), MailGtdAction.TASK))
            assertEquals(MailResult.Offline, repository.gtd(id(1), MailGtdAction.WAITING))
        }
}
