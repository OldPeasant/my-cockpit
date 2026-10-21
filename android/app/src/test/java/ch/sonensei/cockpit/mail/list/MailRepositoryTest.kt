package ch.sonensei.cockpit.mail.list

import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.time.Instant
import java.util.UUID

/** The mail list calls against a fake server: what is asked for, how answers are read, how failures are reported. */
class MailRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: MailRepository

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

    private fun summaryJson(
        n: Int,
        subject: String? = "Subject $n",
    ) = """{"id":"${id(n)}","accountId":"${id(80)}","folderId":"${id(90)}",
        |"subject":${subject?.let { "\"$it\"" } ?: "null"},"snippet":"Hello $n",
        |"from":[{"name":"Anna Example","address":"anna@example.org"}],"to":[],
        |"sentAt":"2026-10-21T07:59:00Z","receivedAt":"2026-10-21T08:00:00Z",
        |"seen":false,"flagged":true,"hasAttachments":true,"answered":false,"version":2}
        """.trimMargin().replace("\n", "")

    private fun folderJson(
        n: Int,
        path: String,
        name: String = path,
        role: String? = null,
        selectable: Boolean = true,
    ) = """{"id":"${id(n)}","accountId":"${id(80)}","name":"$name","path":"$path","delimiter":"/",""" +
        """"role":${role?.let { "\"$it\"" } ?: "null"},"selectable":$selectable,"version":1}"""

    @Before
    fun setUp() {
        server.start()
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        repository = MailRepository(retrofit.create(MailApi::class.java))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `the unified inbox asks for all accounts and reads a last page with a null cursor`() =
        runBlocking {
            server.enqueue(json("""{"items":[${summaryJson(1)},${summaryJson(2, subject = null)}],"nextCursor":null}"""))

            val result = repository.messages(Mailbox.Unified, null)

            val request = server.takeRequest()
            assertEquals("/api/v1/mail/messages", request.url.encodedPath)
            assertNull(request.url.queryParameter("accountId"))
            assertNull(request.url.queryParameter("folderId"))
            assertNull(request.url.queryParameter("cursor"))
            assertEquals("${MailRepository.PAGE_SIZE}", request.url.queryParameter("limit"))
            val page = (result as MailResult.Ok).value
            assertNull(page.nextCursor)
            assertEquals(listOf(id(1), id(2)), page.items.map { it.id })
            val first = page.items.first()
            assertEquals("Anna Example", first.sender)
            assertEquals("Subject 1", first.subject)
            assertEquals(Instant.parse("2026-10-21T08:00:00Z"), first.date)
            assertEquals(false, first.seen)
            assertEquals(true, first.flagged)
            assertEquals(true, first.hasAttachments)
            assertEquals("", page.items[1].subject)
        }

    @Test
    fun `a folder page names account, folder and cursor, and passes the next cursor on`() =
        runBlocking {
            server.enqueue(json("""{"items":[${summaryJson(3)}],"nextCursor":"opaque-cursor-2"}"""))

            val result = repository.messages(Mailbox.Folder(id(80), id(91)), "opaque-cursor-1")

            val request = server.takeRequest()
            assertEquals("${id(80)}", request.url.queryParameter("accountId"))
            assertEquals("${id(91)}", request.url.queryParameter("folderId"))
            assertEquals("opaque-cursor-1", request.url.queryParameter("cursor"))
            assertEquals("opaque-cursor-2", (result as MailResult.Ok).value.nextCursor)
        }

    @Test
    fun `an empty cursor or a page without one means the end`() =
        runBlocking {
            server.enqueue(json("""{"items":[],"nextCursor":""}"""))
            server.enqueue(json("""{"items":[]}"""))

            assertNull((repository.messages(Mailbox.Unified, null) as MailResult.Ok).value.nextCursor)
            assertNull((repository.messages(Mailbox.Unified, null) as MailResult.Ok).value.nextCursor)
        }

    @Test
    fun `accounts are sorted by name and list only selectable folders, special folders first`() =
        runBlocking {
            server.dispatcher =
                object : mockwebserver3.Dispatcher() {
                    override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                        when (request.url.encodedPath) {
                            "/api/v1/mail/accounts" ->
                                json(
                                    """{"items":[
                                    |{"id":"${id(80)}","displayName":"Work","emailAddress":"me@example.org"},
                                    |{"id":"${id(81)}","displayName":"","emailAddress":"alias@example.net"}]}
                                    """.trimMargin().replace("\n", ""),
                                )
                            "/api/v1/mail/accounts/${id(80)}/folders" ->
                                json(
                                    listOf(
                                        folderJson(93, "Projects"),
                                        folderJson(94, "Projects/2026", name = "2026"),
                                        folderJson(95, "Group", selectable = false),
                                        folderJson(92, "Trash", role = "TRASH"),
                                        folderJson(91, "Archives", role = "ARCHIVE"),
                                        folderJson(90, "INBOX", role = "INBOX"),
                                        folderJson(96, "alpha"),
                                    ).joinToString(",", """{"items":[""", "]}"),
                                )
                            "/api/v1/mail/accounts/${id(81)}/folders" -> json("""{"items":[]}""")
                            else -> status(404)
                        }
                }

            val accounts = (repository.accounts() as MailResult.Ok).value

            // The account without a display name is named by its address, and sorts before "Work".
            assertEquals(listOf("alias@example.net", "Work"), accounts.map { it.account.name })
            val work = accounts.single { it.account.id == id(80) }
            assertEquals(listOf(90, 91, 92, 96, 93, 94).map(::id), work.folders.map { it.id })
            assertEquals(1, work.folders.single { it.id == id(94) }.depth)
            assertEquals(emptyList<MailFolder>(), accounts.single { it.account.id == id(81) }.folders)
        }

    @Test
    fun `an action sends the wire name and the id, and reads the result for that id`() =
        runBlocking {
            server.enqueue(json("""{"results":[{"id":"${id(1)}","ok":true}]}"""))
            server.enqueue(json("""{"results":[{"id":"${id(2)}","ok":false,"errorCode":"MAIL_SERVER_UNAVAILABLE"}]}"""))
            server.enqueue(json("""{"results":[]}"""))
            server.enqueue(json("""{"results":[{"id":"${id(4)}","ok":true}]}"""))

            assertEquals(MailResult.Ok(true), repository.act(id(1), MailListAction.ARCHIVE))
            assertEquals(MailResult.Ok(false), repository.act(id(2), MailListAction.DELETE))
            // No result for this id (or one for another id): not done.
            assertEquals(MailResult.Ok(false), repository.markRead(id(3)))
            assertEquals(MailResult.Ok(true), repository.markRead(id(4)))

            val bodies = List(4) { Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject }
            assertEquals(listOf("ARCHIVE", "DELETE", "READ", "READ"), bodies.map { it["action"]!!.jsonPrimitive.content })
            assertEquals(listOf("${id(1)}"), bodies[0]["ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        }

    @Test
    fun `a 4xx is a refusal with its status, a 5xx or no connection is offline`() =
        runBlocking {
            server.enqueue(status(403))
            server.enqueue(status(404))
            server.enqueue(status(503))
            server.enqueue(status(500))

            assertEquals(MailResult.Failed(403), repository.messages(Mailbox.Unified, null))
            assertEquals(MailResult.Failed(404), repository.act(id(1), MailListAction.DELETE))
            assertEquals(MailResult.Offline, repository.messages(Mailbox.Unified, null))
            assertEquals(MailResult.Offline, repository.accounts())

            server.close()
            assertEquals(MailResult.Offline, repository.messages(Mailbox.Unified, null))
        }

    @Test
    fun `an unreadable answer or a malformed id is a refusal without status`() =
        runBlocking {
            server.enqueue(json("""{"items":[{"subject":"no id"}]}"""))
            server.enqueue(json("""{"items":[{"id":"not-a-uuid"}]}"""))
            server.enqueue(json("not json"))
            server.enqueue(json("", code = 204))

            assertEquals(MailResult.Failed(null), repository.messages(Mailbox.Unified, null))
            assertEquals(MailResult.Failed(null), repository.messages(Mailbox.Unified, null))
            assertEquals(MailResult.Failed(null), repository.messages(Mailbox.Unified, null))
            // A success without a body cannot be read either.
            assertEquals(MailResult.Failed(204), repository.messages(Mailbox.Unified, null))
        }

    @Test
    fun `one account whose folders fail makes the account list fail`() =
        runBlocking {
            server.dispatcher =
                object : mockwebserver3.Dispatcher() {
                    override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                        when (request.url.encodedPath) {
                            "/api/v1/mail/accounts" -> json("""{"items":[{"id":"${id(80)}","displayName":"Work"}]}""")
                            else -> status(403)
                        }
                }

            assertEquals(MailResult.Failed(403), repository.accounts())
        }
}
