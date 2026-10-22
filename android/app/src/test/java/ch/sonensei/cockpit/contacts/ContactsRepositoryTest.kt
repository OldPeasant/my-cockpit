package ch.sonensei.cockpit.contacts

import ch.sonensei.cockpit.core.network.generated.apis.ContactsApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
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
        contacts = ContactsRepository(retrofit.create(ContactsApi::class.java), retrofit.create(ContactLinksApi::class.java))
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
    fun `related combines the contact's related items and its links`() =
        runBlocking {
            // Both calls run at once, so the answer is picked by path, not by arrival order.
            val relatedBody =
                """{"contactId":"${id(1)}","waitingFor":[{"id":"${id(2)}","title":"Send the offer","waitingSince":"2026-10-01"}],
                |"mails":[{"id":"${id(3)}","accountId":"${id(9)}","direction":"FROM","from":[],"to":[],"seen":true,
                |"subject":"Hello","receivedAt":"2026-10-20T08:00:00Z"}],
                |"events":[{"eventId":"${id(4)}","calendarId":"${id(9)}","title":"Lunch","allDay":true,"startDate":"2026-11-02"}]}
                """.trimMargin().replace("\n", "")
            val linksBody =
                """{"items":[{"id":"${id(5)}","direction":"INCOMING","origin":"MANUAL","createdAt":"2026-10-01T09:00:00Z",
                |"source":{"type":"NOTE","id":"${id(6)}","title":"Meeting notes","available":true},
                |"target":{"type":"CONTACT","id":"${id(1)}","title":"Anna","available":true}}]}
                """.trimMargin().replace("\n", "")
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.url.encodedPath) {
                            "/api/v1/contacts/${id(1)}/related" -> json(relatedBody)
                            "/api/v1/links" -> json(linksBody)
                            else -> status(404)
                        }
                }

            val related = (contacts.related(id(1)) as ContactsResult.Ok).value
            assertEquals("Send the offer", related.waitingFor.single().title)
            assertEquals(true, related.mails.single().fromContact)
            assertEquals(LocalDate.of(2026, 11, 2), related.events.single().startDate)
            assertEquals(RelatedLink(id(5), "NOTE", "Meeting notes", null, true), related.links.single())
            val paths = listOf(server.takeRequest().url, server.takeRequest().url).map { it.encodedPath + "?" + it.encodedQuery }
            assertEquals(setOf("/api/v1/contacts/${id(1)}/related?null", "/api/v1/links?type=CONTACT&id=${id(1)}"), paths.toSet())
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
}
