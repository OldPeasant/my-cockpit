package ch.sonensei.cockpit.contacts

import ch.sonensei.cockpit.core.network.generated.apis.ContactsApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.LocalDate
import java.util.UUID

/** The contacts calls against a fake server: what is asked, and how answers and refusals are reported. */
class ContactsRepositoryTest {
    private val server = MockWebServer()
    private lateinit var contacts: ContactsRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun contactJson(
        n: Int,
        name: String = "Contact $n",
    ) = """{"id":"${id(n)}","displayName":"$name","organization":" ","emails":[{"address":"c$n@example.org","label":"work"},
        |{"address":" "}],"phones":[{"number":"044 000 00 0$n"}],"addresses":[{"street":"Musterweg 1","postalCode":"8000",
        |"city":"Zürich","label":"home"},{"city":" "}],"birthday":"1990-05-17","source":"GOOGLE","createdAt":"2026-10-01T09:00:00Z",
        |"updatedAt":"2026-10-01T09:00:00Z","version":1}
        """.trimMargin().replace("\n", "")

    private fun json(body: String) =
        MockResponse
            .Builder()
            .code(200)
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
        contacts = ContactsRepository(retrofit.create(ContactsApi::class.java))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `the list searches and follows the pages in the server's order`() =
        runBlocking {
            server.enqueue(json("""{"items":[${contactJson(2, "Anna")}],"nextCursor":"c2"}"""))
            server.enqueue(json("""{"items":[${contactJson(1, "Bert")}],"nextCursor":null}"""))

            val result = contacts.list(" ann ") as ContactsResult.Ok
            assertEquals(listOf("Anna", "Bert"), result.value.map { it.displayName })
            val first = server.takeRequest().url
            assertEquals("ann", first.queryParameter("q"))
            assertEquals("c2", server.takeRequest().url.queryParameter("cursor"))

            val anna = result.value.first()
            assertEquals(null, anna.organization)
            assertEquals(listOf(LabeledValue("c2@example.org", "work")), anna.emails)
            assertEquals(listOf(LabeledValue("Musterweg 1, 8000 Zürich", "home")), anna.addresses)
            assertEquals(LocalDate.of(1990, 5, 17), anna.birthday)
            assertEquals("GOOGLE", anna.source)
        }

    @Test
    fun `a blank search lists everything`() =
        runBlocking {
            server.enqueue(json("""{"items":[]}"""))
            contacts.list("  ")
            assertEquals(null, server.takeRequest().url.queryParameter("q"))
        }

    @Test
    fun `related reads the contact's related items, without its links (the linked-items panel loads them)`() =
        runBlocking {
            val relatedBody =
                """{"contactId":"${id(1)}","waitingFor":[{"id":"${id(2)}","title":"Send the offer","waitingSince":"2026-10-01"}],
                |"mails":[{"id":"${id(3)}","accountId":"${id(9)}","direction":"FROM","from":[],"to":[],"seen":true,
                |"subject":"Hello","receivedAt":"2026-10-20T08:00:00Z"}],
                |"events":[{"eventId":"${id(4)}","calendarId":"${id(9)}","title":"Lunch","allDay":true,"startDate":"2026-11-02"}]}
                """.trimMargin().replace("\n", "")
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.url.encodedPath) {
                            "/api/v1/contacts/${id(1)}/related" -> json(relatedBody)
                            else -> status(404)
                        }
                }

            val related = (contacts.related(id(1)) as ContactsResult.Ok).value
            assertEquals("Send the offer", related.waitingFor.single().title)
            assertEquals(true, related.mails.single().fromContact)
            assertEquals(LocalDate.of(2026, 11, 2), related.events.single().startDate)
            assertEquals("/api/v1/contacts/${id(1)}/related", server.takeRequest().url.encodedPath)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `refusals, server errors and unreadable answers are reported`() =
        runBlocking {
            server.enqueue(status(404))
            assertEquals(ContactsResult.Failed(404), contacts.get(id(1)))
            server.enqueue(status(503))
            assertEquals(ContactsResult.Offline, contacts.get(id(1)))
            server.enqueue(json("""{"nope":1}"""))
            assertEquals(ContactsResult.Failed(null), contacts.get(id(1)))
        }

    @Test
    fun `no connection is offline`() =
        runBlocking {
            server.close()
            assertEquals(ContactsResult.Offline, contacts.list(null))
        }

    private val carlaJson =
        """{"id":"${id(5)}","displayName":"Carla Beispiel","givenName":"Carla","familyName":"Beispiel","emails":[
        |{"address":"carla@example.org"}],"phones":[],"addresses":[{"street":"Musterweg 1","city":"Zürich","label":"home"}],
        |"photoFileId":"${id(9)}","source":"LOCAL","createdAt":"2026-10-01T09:00:00Z","updatedAt":"2026-10-02T09:00:00Z","version":8}
        """.trimMargin().replace("\n", "")

    private val base =
        Contact(id(5), "Carla Beispiel", "Beispiel AG", null, emptyList(), emptyList(), emptyList(), null, null, "LOCAL", version = 7)

    private fun sent(request: RecordedRequest) = Json.parseToJsonElement(request.body!!.utf8()).jsonObject

    @Test
    fun `create posts the form without If-Match and reads back the saved contact`() =
        runBlocking {
            server.enqueue(json(carlaJson))
            val form = ContactForm(givenName = " Carla ", familyName = "Beispiel", emails = listOf(LabeledField("carla@example.org")))
            val request = form.toRequest()

            val saved = (contacts.create(request) as ContactsResult.Ok).value

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/api/v1/contacts", recorded.url.encodedPath)
            assertNull(recorded.headers["If-Match"])
            val body = sent(recorded)
            assertEquals("Carla", body["givenName"]?.jsonPrimitive?.content)
            val email = body["emails"]!!.jsonArray.single().jsonObject
            assertEquals("carla@example.org", email["address"]?.jsonPrimitive?.content)
            assertEquals(id(5), saved.id)
            assertEquals(8L, saved.version)
            assertEquals("Carla", saved.givenName)
            assertEquals("Beispiel", saved.familyName)
            assertEquals(id(9), saved.photoFileId)
            assertEquals("Zürich", saved.addressFields.single().city)
        }

    @Test
    fun `update patches the contact with its version as If-Match and sends cleared fields as null`() =
        runBlocking {
            server.enqueue(json(carlaJson))
            val request = ContactForm(givenName = "Carla", familyName = "Beispiel", organization = " ").toRequest()

            val saved = (contacts.update(base, request) as ContactsResult.Ok).value

            val recorded = server.takeRequest()
            assertEquals("PATCH", recorded.method)
            assertEquals("/api/v1/contacts/${id(5)}", recorded.url.encodedPath)
            assertEquals("\"7\"", recorded.headers["If-Match"])
            // The form replaces every field: an emptied organization must reach the server as null to clear it.
            val body = sent(recorded)
            assertEquals(JsonNull, body["organization"])
            assertEquals(0, body["emails"]!!.jsonArray.size)
            assertEquals(8L, saved.version)
        }

    @Test
    fun `update reports a change made elsewhere and other refusals by status`() =
        runBlocking {
            val request = ContactForm(givenName = "Carla").toRequest()
            server.enqueue(status(412))
            assertEquals(ContactsResult.Failed(412), contacts.update(base, request))
            server.enqueue(status(409))
            assertEquals(ContactsResult.Failed(409), contacts.update(base, request))
            server.enqueue(status(403))
            assertEquals(ContactsResult.Failed(403), contacts.update(base, request))
            server.enqueue(status(502))
            assertEquals(ContactsResult.Offline, contacts.update(base, request))
            server.enqueue(json("""{"nope":1}"""))
            assertEquals(ContactsResult.Failed(null), contacts.update(base, request))
        }

    @Test
    fun `create reports refusals`() =
        runBlocking {
            server.enqueue(status(400))
            assertEquals(ContactsResult.Failed(400), contacts.create(ContactForm(givenName = "Carla").toRequest()))
        }

    @Test
    fun `a refused save says which fields the server found wrong`() =
        runBlocking {
            val problem =
                """{"status":400,"detail":"Request validation failed","errors":[{"field":"emails[0].address",
                |"message":"is not an email address"},{"field":"phones[0].number","message":"must not be blank"}]}
                """.trimMargin().replace("\n", "")
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(400)
                    .addHeader("Content-Type", "application/problem+json")
                    .body(problem)
                    .build(),
            )
            assertEquals(
                ContactsResult.Failed(400, "emails[0].address is not an email address; phones[0].number must not be blank"),
                contacts.create(ContactForm(givenName = "Carla").toRequest()),
            )
            assertEquals("Request validation failed", problemDetail("""{"detail":"Request validation failed"}"""))
            assertEquals(null, problemDetail("not json"))
        }

    @Test
    fun `a problem without usable field errors falls back to its detail, and nothing usable gives no detail`() {
        // A field error with only a message, or only a field, still says something.
        assertEquals("must not be blank", problemDetail("""{"errors":[{"message":"must not be blank"}],"detail":"Invalid"}"""))
        assertEquals("birthday", problemDetail("""{"errors":[{"field":"birthday","message":null}]}"""))
        // Empty or blank field errors fall back to the detail.
        assertEquals("Invalid", problemDetail("""{"errors":[{"field":" "},{}],"detail":"Invalid"}"""))
        assertEquals("Invalid", problemDetail("""{"errors":[],"detail":"Invalid"}"""))
        // No body, a blank detail, or a body that is not a problem object.
        assertNull(problemDetail(null))
        assertNull(problemDetail("""{"detail":" "}"""))
        assertNull(problemDetail("""{"title":"Bad Request"}"""))
        assertNull(problemDetail("""["not","an","object"]"""))
    }

    @Test
    fun `a refused update says which fields the server found wrong, other refusals carry no detail`() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(400)
                    .addHeader("Content-Type", "application/problem+json")
                    .body("""{"status":400,"errors":[{"field":"photoFileId","message":"does not exist"}]}""")
                    .build(),
            )
            val request = ContactForm.of(base).toRequest()
            assertEquals(ContactsResult.Failed(400, "photoFileId does not exist"), contacts.update(base, request))
            // A 403 body is not read: only refused values (400) carry a detail.
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(403)
                    .addHeader("Content-Type", "application/problem+json")
                    .body("""{"status":403,"detail":"Read-only source"}""")
                    .build(),
            )
            assertEquals(ContactsResult.Failed(403), contacts.update(base, request))
        }

    @Test
    fun `delete moves the contact to the Trash with its version as If-Match`() =
        runBlocking {
            server.enqueue(status(204))

            assertEquals(ContactsResult.Ok(Unit), contacts.delete(base))

            val recorded = server.takeRequest()
            assertEquals("DELETE", recorded.method)
            assertEquals("/api/v1/contacts/${id(5)}", recorded.url.encodedPath)
            assertEquals("\"7\"", recorded.headers["If-Match"])
        }

    @Test
    fun `a refused delete reports its status, a server error is offline`() =
        runBlocking {
            for (code in listOf(403, 404, 405, 409, 412)) {
                server.enqueue(status(code))
                assertEquals(ContactsResult.Failed(code), contacts.delete(base))
            }
            server.enqueue(status(503))
            assertEquals(ContactsResult.Offline, contacts.delete(base))
        }

    @Test
    fun `writes without a connection are offline`() =
        runBlocking {
            server.close()
            val request = ContactForm(givenName = "Carla").toRequest()
            assertEquals(ContactsResult.Offline, contacts.create(request))
            assertEquals(ContactsResult.Offline, contacts.update(base, request))
            assertEquals(ContactsResult.Offline, contacts.delete(base))
        }
}
