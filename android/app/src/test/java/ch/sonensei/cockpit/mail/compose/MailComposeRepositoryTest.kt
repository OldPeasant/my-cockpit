package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.mail.list.MailResult
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.ByteArrayInputStream
import java.util.UUID

/** Draft calls against a fake server: bodies, `If-Match`, the version read from `ETag`, and how failures map. */
class MailComposeRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: MailComposeRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun draftJson(n: Int = 1) =
        """{"id":"${id(n)}","accountId":"${id(80)}","folderId":"${id(90)}","identityId":null,
        |"from":{"name":"Me","address":"me@example.org"},
        |"to":[{"name":"Ann Example","address":"ann@example.org"}],"cc":[],"bcc":[{"name":null,"address":"b@example.org"}],
        |"subject":"Re: Plan","html":"<p></p><blockquote>old</blockquote>","text":"> old","inReplyTo":"<x@example.org>",
        |"references":"<x@example.org>","savedAt":"2026-10-22T06:00:00Z",
        |"attachments":[{"partId":"2","filename":"plan.pdf","contentId":"","sizeBytes":10,"inline":false},
        |{"partId":"3","filename":"logo.png","contentId":"logo","sizeBytes":5,"inline":true}]}
        """.trimMargin().replace("\n", "")

    private fun draft(
        etag: String? = "\"7\"",
        code: Int = 200,
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .apply { if (etag != null) addHeader("ETag", etag) }
        .body(draftJson())
        .build()

    private fun status(code: Int) =
        MockResponse
            .Builder()
            .code(code)
            .addHeader("Content-Type", "application/problem+json")
            .body("""{"status":$code}""")
            .build()

    private val content =
        DraftContent(
            to = listOf(Recipient("ann@example.org", "Ann Example")),
            cc = emptyList(),
            bcc = listOf(Recipient("b@example.org")),
            subject = "Plan",
            html = "<p>Hi</p>",
            keep = listOf("2", "3"),
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
        repository = MailComposeRepository(retrofit.create(MailComposeApi::class.java))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `a new draft is created in the account with its content and comes back with its version`() =
        runBlocking {
            server.enqueue(draft(code = 201))

            val result = repository.create(id(80), content) as MailResult.Ok

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/mail/drafts", request.url.encodedPath)
            val body = request.body!!.utf8()
            assertTrue(body, body.contains("\"accountId\":\"${id(80)}\""))
            assertTrue(body, body.contains("{\"address\":\"ann@example.org\",\"name\":\"Ann Example\"}"))
            assertTrue(body, body.contains("\"html\":\"<p>Hi</p>\""))
            val d = result.value
            assertEquals(id(1), d.id)
            assertEquals("\"7\"", d.etag)
            assertEquals(listOf(Recipient("ann@example.org", "Ann Example")), d.to)
            assertEquals(listOf(Recipient("b@example.org")), d.bcc)
            assertEquals("Re: Plan", d.subject)
            assertEquals("> old", d.text)
            assertEquals(listOf("2", "3"), d.attachments.map { it.partId })
            assertTrue(d.attachments[1].inline)
        }

    @Test
    fun `a save sends the version as If-Match and lists the attachments it keeps`() =
        runBlocking {
            server.enqueue(draft(etag = "\"8\""))

            val result = repository.update(id(1), "\"7\"", content) as MailResult.Ok

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/api/v1/mail/drafts/${id(1)}", request.url.encodedPath)
            assertEquals("\"7\"", request.headers["If-Match"])
            assertTrue(request.body!!.utf8().contains("\"attachments\":[{\"partId\":\"2\"},{\"partId\":\"3\"}]"))
            assertEquals("\"8\"", result.value.etag)
        }

    @Test
    fun `a stale version is a 412 failure, an answer without ETag is unreadable`() =
        runBlocking {
            server.enqueue(status(412))
            server.enqueue(draft(etag = null))

            assertEquals(MailResult.Failed(412), repository.update(id(1), "\"6\"", content))
            assertEquals(MailResult.Failed(null), repository.get(id(1)))
        }

    @Test
    fun `reply, reply all and forward start drafts from the message`() =
        runBlocking {
            repeat(3) { server.enqueue(draft(code = 201)) }

            repository.start(ComposeMode.Reply(id(5)))
            repository.start(ComposeMode.ReplyAll(id(5)))
            repository.start(ComposeMode.Forward(id(5)))

            assertEquals("/api/v1/mail/messages/${id(5)}/reply", server.takeRequest().url.encodedPath)
            assertEquals("/api/v1/mail/messages/${id(5)}/reply-all", server.takeRequest().url.encodedPath)
            assertEquals("/api/v1/mail/messages/${id(5)}/forward", server.takeRequest().url.encodedPath)
        }

    @Test
    fun `a picked file is uploaded as multipart with the version`() =
        runBlocking {
            server.enqueue(draft(etag = "\"9\""))
            val file =
                object : PickedFile {
                    override val name = "notes.txt"
                    override val mime = "text/plain"
                    override val sizeBytes = 5L

                    override fun open() = ByteArrayInputStream("hello".toByteArray())
                }

            val result = repository.attach(id(1), "\"8\"", file) as MailResult.Ok

            val request = server.takeRequest()
            assertEquals("/api/v1/mail/drafts/${id(1)}/attachments", request.url.encodedPath)
            assertEquals("\"8\"", request.headers["If-Match"])
            val body = request.body!!.utf8()
            assertTrue(body, body.contains("filename=\"notes.txt\""))
            assertTrue(body, body.contains("Content-Type: text/plain"))
            assertTrue(body, body.contains("hello"))
            assertEquals("\"9\"", result.value.etag)
        }

    @Test
    fun `send maps the answers of the server`() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "application/json")
                    .body("""{"sentMessageId":null,"appendedToSent":true,"draftRemoved":true}""")
                    .build(),
            )
            server.enqueue(status(400))
            server.enqueue(status(422))
            server.enqueue(status(412))
            server.enqueue(status(503))
            server.enqueue(status(404))

            assertEquals(SendOutcome.Sent, repository.send(id(1), "\"7\""))
            val request = server.takeRequest()
            assertEquals("/api/v1/mail/drafts/${id(1)}/send", request.url.encodedPath)
            assertEquals("\"7\"", request.headers["If-Match"])
            assertEquals(SendOutcome.Invalid, repository.send(id(1), "\"7\""))
            assertEquals(SendOutcome.Rejected, repository.send(id(1), "\"7\""))
            assertEquals(SendOutcome.Conflict, repository.send(id(1), "\"7\""))
            assertEquals(SendOutcome.Offline, repository.send(id(1), "\"7\""))
            assertEquals(SendOutcome.Failed(404), repository.send(id(1), "\"7\""))
        }

    @Test
    fun `discard deletes the draft, one already gone counts as discarded`() =
        runBlocking {
            server.enqueue(MockResponse.Builder().code(204).build())
            server.enqueue(status(404))
            server.enqueue(status(412))

            assertEquals(MailResult.Ok(Unit), repository.discard(id(1), "\"7\""))
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("\"7\"", request.headers["If-Match"])
            assertEquals(MailResult.Ok(Unit), repository.discard(id(1), "\"7\""))
            assertEquals(MailResult.Failed(412), repository.discard(id(1), "\"7\""))
        }

    @Test
    fun `accounts are listed by name`() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "application/json")
                    .body(
                        """{"items":[{"id":"${id(2)}","displayName":"Work","emailAddress":"w@example.org"},
                        |{"id":"${id(1)}","displayName":"home","emailAddress":"h@example.org"}]}
                        """.trimMargin().replace("\n", ""),
                    ).build(),
            )

            val accounts = (repository.accounts() as MailResult.Ok).value

            assertEquals(listOf("home", "Work"), accounts.map { it.name })
        }
}
