package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.mail.list.MailResult
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

/** The identities call and the identity of a draft against a fake server. */
class MailComposeIdentityRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: MailComposeRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun json(
        body: String,
        code: Int = 200,
        etag: String? = "\"3\"",
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .apply { if (etag != null) addHeader("ETag", etag) }
        .body(body)
        .build()

    private fun draftJson(identityId: String?) =
        """{"id":"${id(7)}","accountId":"${id(80)}","identityId":${identityId?.let { "\"$it\"" } ?: "null"},
        |"to":[],"cc":[],"bcc":[],"subject":"Plan","html":"<p>Hi</p>","text":"Hi","attachments":[]}
        """.trimMargin().replace("\n", "")

    private val content =
        DraftContent(
            to = emptyList(),
            cc = emptyList(),
            bcc = emptyList(),
            subject = "Plan",
            html = "<p>Hi</p>",
            keep = emptyList(),
            identityId = id(61),
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
    fun `identities are read for the account in their order, unreadable ids are skipped`() =
        runBlocking {
            server.enqueue(
                json(
                    """{"items":[
                    |{"id":"${id(60)}","accountId":"${id(80)}","name":"Me","email":"me@example.org","isDefault":true,"position":0},
                    |{"id":"not-a-uuid","accountId":"${id(80)}","name":"Broken","email":"x@example.org"},
                    |{"id":"${id(61)}","name":null,"email":"support@example.org"}]}
                    """.trimMargin().replace("\n", ""),
                ),
            )

            val result = repository.identities(id(80)) as MailResult.Ok

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/v1/mail/accounts/${id(80)}/identities", request.url.encodedPath)
            assertEquals(
                listOf(
                    MailIdentity(id(60), id(80), "Me", "me@example.org", isDefault = true),
                    MailIdentity(id(61), id(80), "", "support@example.org", isDefault = false),
                ),
                result.value,
            )
        }

    @Test
    fun `failed identities calls map to a failure`() =
        runBlocking {
            server.enqueue(json("""{"status":404}""", code = 404, etag = null))

            assertEquals(MailResult.Failed(404), repository.identities(id(80)))
        }

    @Test
    fun `a new draft and a save send the chosen identity, none when the default is used`() =
        runBlocking {
            server.enqueue(json(draftJson(null), code = 201))
            server.enqueue(json(draftJson(null)))
            server.enqueue(json(draftJson(null)))

            repository.create(id(80), content)
            repository.update(id(7), "\"3\"", content)
            repository.update(id(7), "\"3\"", content.copy(identityId = null))

            val created = server.takeRequest().body!!.utf8()
            assertTrue(created, created.contains("\"identityId\":\"${id(61)}\""))
            val updated = server.takeRequest().body!!.utf8()
            assertTrue(updated, updated.contains("\"identityId\":\"${id(61)}\""))
            val withDefault = server.takeRequest().body!!.utf8()
            assertFalse(withDefault, withDefault.contains("\"identityId\":\""))
        }

    @Test
    fun `the identity of a draft is read back, an unreadable one counts as the default`() =
        runBlocking {
            server.enqueue(json(draftJson(id(61).toString())))
            server.enqueue(json(draftJson("garbage")))
            server.enqueue(json(draftJson(null)))

            assertEquals(id(61), (repository.get(id(7)) as MailResult.Ok).value.identityId)
            assertNull((repository.get(id(7)) as MailResult.Ok).value.identityId)
            assertNull((repository.get(id(7)) as MailResult.Ok).value.identityId)
        }

    @Test
    fun `continuing a draft reads it from the server, nothing new is created`() =
        runBlocking {
            server.enqueue(json(draftJson(id(61).toString())))

            val draft = (repository.start(ComposeMode.Draft(id(7))) as MailResult.Ok).value

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/v1/mail/drafts/${id(7)}", request.url.encodedPath)
            assertEquals(id(7), draft.id)
            assertEquals("\"3\"", draft.etag)
            assertEquals(id(61), draft.identityId)
            assertEquals("Plan", draft.subject)
        }
}
