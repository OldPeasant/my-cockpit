package ch.sonensei.cockpit.mail.read

import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.mail.list.MailApi
import ch.sonensei.cockpit.mail.list.MailListAction
import ch.sonensei.cockpit.mail.list.MailResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.UUID

/** The read view's menu actions and the mail fields they need (flag, account, folder) against a fake server. */
class MailReadActionsRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: MailReadRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun json(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun body() = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

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
    fun `a move names the target folder, other actions leave it out`() =
        runBlocking {
            server.enqueue(json("""{"results":[{"id":"${id(1)}","ok":true}]}"""))
            server.enqueue(json("""{"results":[{"id":"${id(1)}","ok":true}]}"""))
            server.enqueue(json("""{"results":[{"id":"${id(1)}","ok":true}]}"""))

            assertEquals(MailResult.Ok(MailActionOutcome(true)), repository.act(id(1), MailListAction.MOVE, id(92)))
            assertEquals(MailResult.Ok(MailActionOutcome(true)), repository.act(id(1), MailListAction.UNREAD))
            assertEquals(MailResult.Ok(MailActionOutcome(true)), repository.act(id(1), MailListAction.FLAG))

            val move = body()
            assertEquals("MOVE", move.getValue("action").jsonPrimitive.content)
            assertEquals("${id(92)}", move.getValue("targetFolderId").jsonPrimitive.content)
            val unread = body()
            assertEquals("UNREAD", unread.getValue("action").jsonPrimitive.content)
            assertFalse("targetFolderId" in unread)
            val flag = body()
            assertEquals("FLAG", flag.getValue("action").jsonPrimitive.content)
            assertFalse("targetFolderId" in flag)
        }

    @Test
    fun `a refused move keeps the server's error code`() =
        runBlocking {
            server.enqueue(json("""{"results":[{"id":"${id(1)}","ok":false,"errorCode":"OTHER_ACCOUNT"}]}"""))

            assertEquals(MailResult.Ok(MailActionOutcome(false, "OTHER_ACCOUNT")), repository.act(id(1), MailListAction.MOVE, id(92)))
        }

    @Test
    fun `a message is read with its flag, account and folder`() =
        runBlocking {
            server.enqueue(
                json(
                    """{"id":"${id(1)}","accountId":"${id(80)}","folderId":"${id(90)}","subject":"Report","from":[],"to":[],"cc":[],
                    |"seen":true,"flagged":true,"body":{"html":"","text":"Hi"},"attachments":[]}
                    """.trimMargin().replace("\n", ""),
                ),
            )
            server.enqueue(
                json(
                    """{"id":"${id(2)}","accountId":"not-a-uuid","subject":"Report","from":[],"to":[],"cc":[],
                    |"body":{"html":"","text":"Hi"},"attachments":[]}
                    """.trimMargin().replace("\n", ""),
                ),
            )

            val first = (repository.message(id(1)) as MailResult.Ok).value
            assertEquals(true, first.flagged)
            assertEquals(id(80), first.accountId)
            assertEquals(id(90), first.folderId)

            // Without the flag the mail is not flagged; an unreadable account or a missing folder is unknown.
            val second = (repository.message(id(2)) as MailResult.Ok).value
            assertEquals(false, second.flagged)
            assertNull(second.accountId)
            assertNull(second.folderId)
        }
}
