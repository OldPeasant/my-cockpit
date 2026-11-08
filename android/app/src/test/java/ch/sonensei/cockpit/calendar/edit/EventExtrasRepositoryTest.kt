package ch.sonensei.cockpit.calendar.edit

import ch.sonensei.cockpit.calendar.agenda.CalendarResult
import ch.sonensei.cockpit.core.network.generated.apis.CalendarsApi
import ch.sonensei.cockpit.core.network.generated.apis.ContactsApi
import ch.sonensei.cockpit.core.network.generated.apis.MailAccountsApi
import ch.sonensei.cockpit.core.network.generated.apis.NotesApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.DateTimeException
import java.time.ZoneId
import java.util.UUID

/** What the editor's extras ask the server and how the answers (and refusals) come back. */
class EventExtrasRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repository: EventExtrasRepository
    private lateinit var events: EventEditRepository

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val stamp = "\"createdAt\":\"2026-10-01T09:00:00Z\",\"updatedAt\":\"2026-10-01T09:00:00Z\",\"version\":1"

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

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

    private fun calendarJson(
        n: Int,
        source: String,
        readOnly: Boolean = false,
    ) = """{"id":"${id(n)}","name":"Calendar $n","isDefault":false,"readOnly":$readOnly,"sourceId":"${id(80)}",
        |"sourceType":"$source","visible":true,$stamp}
        """.trimMargin().replace("\n", "")

    private fun accountJson(
        n: Int,
        name: String,
        email: String,
    ) = """{"id":"${id(n)}","displayName":"$name","emailAddress":"$email","hasPassword":true,"notifyNewMail":false,
        |"imap":{"host":"imap.example.org","port":993,"security":"SSL_TLS"},
        |"smtp":{"host":"smtp.example.org","port":465,"security":"SSL_TLS"},
        |"syncWindowMonths":6,"type":"IMAP_SMTP","username":"$email",$stamp}
        """.trimMargin().replace("\n", "")

    private fun endJson(
        n: Int,
        type: String,
        title: String?,
        available: Boolean = true,
        subtitle: String? = null,
    ) = buildString {
        append("{\"id\":\"${id(n)}\",\"type\":\"$type\",\"available\":$available")
        title?.let { append(",\"title\":\"$it\"") }
        subtitle?.let { append(",\"subtitle\":\"$it\"") }
        append("}")
    }

    private fun linkJson(
        n: Int,
        direction: String,
        source: String,
        target: String,
    ) = """{"id":"${id(n)}","direction":"$direction","origin":"MANUAL","source":$source,"target":$target,
        |"createdAt":"2026-10-01T09:00:00Z"}
        """.trimMargin().replace("\n", "")

    private val event = id(1)
    private val eventEnd = endJson(1, "EVENT", "Planning")

    private fun taskJson(n: Int) = """{"id":"${id(n)}","title":"Planning","list":"INBOX","position":0,"contextIds":[],$stamp}"""

    private fun noteJson(n: Int) =
        """{"id":"${id(n)}","bodyMd":"","displayTitle":"Planning","archived":false,"pinned":false,"tags":[],$stamp}"""

    @Before
    fun setUp() {
        server.start()
        val retrofit =
            Retrofit
                .Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()))
                .build()
        repository =
            EventExtrasRepository(
                retrofit.create(CalendarsApi::class.java),
                retrofit.create(ContactsApi::class.java),
                retrofit.create(MailAccountsApi::class.java),
                retrofit.create(TasksApi::class.java),
                retrofit.create(NotesApi::class.java),
                retrofit.create(EventLinksApi::class.java),
            )
        events = EventEditRepository(retrofit.create(CalendarsApi::class.java), retrofit.create(EventWriteApi::class.java))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `only writable calendars of the local source count as local`() =
        runBlocking {
            server.enqueue(
                json(
                    """{"items":[${calendarJson(90, "LOCAL")},${calendarJson(91, "LOCAL", readOnly = true)},
                    |${calendarJson(92, "GOOGLE")},${calendarJson(93, "ICS_FEED", readOnly = true)},${calendarJson(94, "CALDAV")}]}
                    """.trimMargin(),
                ),
            )
            assertEquals(CalendarResult.Ok(setOf(id(90))), repository.localCalendars())
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/v1/calendar/calendars", request.url.encodedPath)
        }

    @Test
    fun `only writable Google and Microsoft calendars answer invitations`() =
        runBlocking {
            server.enqueue(
                json(
                    """{"items":[${calendarJson(90, "LOCAL")},${calendarJson(91, "GOOGLE")},
                    |${calendarJson(92, "GOOGLE", readOnly = true)},${calendarJson(93, "MICROSOFT")},
                    |${calendarJson(94, "MICROSOFT", readOnly = true)},${calendarJson(95, "CALDAV")},
                    |${calendarJson(96, "ICS_FEED", readOnly = true)}]}
                    """.trimMargin(),
                ),
            )
            assertEquals(CalendarResult.Ok(setOf(id(91), id(93))), repository.invitationCalendars())
            assertEquals("/api/v1/calendar/calendars", server.takeRequest().url.encodedPath)

            server.enqueue(status(503))
            assertEquals(CalendarResult.Offline, repository.invitationCalendars())
        }

    @Test
    fun `the own addresses are the mail account addresses in lower case, once each`() =
        runBlocking {
            server.enqueue(
                json(
                    """{"items":[${accountJson(70, "Work", "Ada.Example@Example.ORG")},
                    |${accountJson(71, "Again", "ada.example@example.org")},${accountJson(72, "Private", "ada@example.net")}]}
                    """.trimMargin(),
                ),
            )
            assertEquals(CalendarResult.Ok(setOf("ada.example@example.org", "ada@example.net")), repository.ownAddresses())
            assertEquals("/api/v1/mail/accounts", server.takeRequest().url.encodedPath)

            server.enqueue(json("""{"items":[]}"""))
            assertEquals(CalendarResult.Ok(emptySet<String>()), repository.ownAddresses())
            server.enqueue(status(403))
            assertEquals(CalendarResult.Failed(403), repository.ownAddresses())
        }

    @Test
    fun `refusals and missing answers are reported like the other calendar calls`() =
        runBlocking {
            server.enqueue(status(403))
            assertEquals(CalendarResult.Failed(403), repository.localCalendars())
            server.enqueue(status(503))
            assertEquals(CalendarResult.Offline, repository.localCalendars())
            server.enqueue(json("{\"items\":[{\"broken\":true}]}"))
            assertEquals(CalendarResult.Failed(null), repository.localCalendars())
            server.enqueue(json("not json"))
            assertEquals(CalendarResult.Failed(null), repository.mailAccounts())
            server.close()
            assertEquals(CalendarResult.Offline, repository.suggestions("ada"))
        }

    @Test
    fun `an answer the client can't read is a refusal without status, a cancellation is not swallowed`() =
        runBlocking {
            assertEquals(CalendarResult.Failed(null), calendarCall { throw IllegalArgumentException("bad value") })
            assertEquals(CalendarResult.Failed(null), calendarCall { throw DateTimeException("bad date") })
            assertEquals(CalendarResult.Failed(null), calendarCall { ZoneId.of("Not/AZone") })
            try {
                calendarCall { throw CancellationException("left the editor") }
                fail("a cancellation must reach the caller")
            } catch (e: CancellationException) {
                assertEquals("left the editor", e.message)
            }
        }

    @Test
    fun `suggestions ask for eight matches and keep those with an address, once each`() =
        runBlocking {
            server.enqueue(
                json(
                    """[{"name":"Ada Example","email":"ada@example.org"},{"name":"No Mail"},{"name":"Blank","email":" "},
                    |{"name":"Ada Again","email":"ADA@example.org"},{"name":" ","email":"bob@example.org"}]
                    """.trimMargin(),
                ),
            )
            assertEquals(
                CalendarResult.Ok(listOf(Attendee("ada@example.org", "Ada Example"), Attendee("bob@example.org", null))),
                repository.suggestions("ad a"),
            )
            val url = server.takeRequest().url
            assertEquals("/api/v1/contacts/autocomplete", url.encodedPath)
            assertEquals("ad a", url.queryParameter("q"))
            assertEquals("8", url.queryParameter("limit"))
        }

    @Test
    fun `mail accounts are read with their name and address`() =
        runBlocking {
            server.enqueue(json("""{"items":[${accountJson(70, "Work", "ada@example.org")}]}"""))
            assertEquals(CalendarResult.Ok(listOf(MailAccount(id(70), "Work", "ada@example.org"))), repository.mailAccounts())
            assertEquals("/api/v1/mail/accounts", server.takeRequest().url.encodedPath)
        }

    @Test
    fun `an invitation is posted for the event with its method and account and counts the recipients`() =
        runBlocking {
            server.enqueue(json("""{"method":"REQUEST","recipients":["ada@example.org","bob@example.org"]}"""))
            assertEquals(CalendarResult.Ok(2), repository.sendInvitations(event, InvitationMethod.REQUEST, id(70)))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/calendar/events/$event/invitations", request.url.encodedPath)
            val body = request.json()
            assertEquals(JsonPrimitive(id(70).toString()), body["accountId"])
            assertEquals(JsonPrimitive("REQUEST"), body["method"])
            // No recipients: every guest (the generated client writes the absent list as null).
            assertTrue(body["recipients"] == null || body["recipients"] is JsonNull)

            server.enqueue(json("""{"method":"CANCEL","recipients":["carol@example.org"]}"""))
            assertEquals(
                CalendarResult.Ok(1),
                repository.sendInvitations(event, InvitationMethod.CANCEL, id(70), listOf("carol@example.org")),
            )
            val cancel = server.takeRequest().json()
            assertEquals(JsonPrimitive("CANCEL"), cancel["method"])
            assertEquals(JsonArray(listOf(JsonPrimitive("carol@example.org"))), cancel["recipients"])

            server.enqueue(status(422))
            assertEquals(CalendarResult.Failed(422), repository.sendInvitations(event, InvitationMethod.REQUEST, id(70)))
        }

    @Test
    fun `links show the other end in either direction, unavailable items without title`() =
        runBlocking {
            server.enqueue(
                json(
                    """{"items":[
                    |${linkJson(50, "OUTGOING", eventEnd, endJson(10, "TASK", "Prepare slides", subtitle = "Inbox"))},
                    |${linkJson(51, "INCOMING", endJson(11, "NOTE", "Minutes"), eventEnd)},
                    |${linkJson(52, "OUTGOING", eventEnd, endJson(12, "EMAIL", "Secret", available = false, subtitle = "x"))},
                    |${linkJson(53, "INCOMING", endJson(13, "JOURNAL_ENTRY", "Day"), eventEnd)}]}
                    """.trimMargin(),
                ),
            )
            assertEquals(
                CalendarResult.Ok(
                    listOf(
                        LinkedItem(id(50), LinkedType.TASK, id(10), "Prepare slides", "Inbox"),
                        LinkedItem(id(51), LinkedType.NOTE, id(11), "Minutes", null),
                        LinkedItem(id(52), LinkedType.EMAIL, id(12), null, null),
                        LinkedItem(id(53), LinkedType.JOURNAL_ENTRY, id(13), "Day", null),
                    ),
                ),
                repository.links(event),
            )
            val url = server.takeRequest().url
            assertEquals("/api/v1/links", url.encodedPath)
            assertEquals("EVENT", url.queryParameter("type"))
            assertEquals(event.toString(), url.queryParameter("id"))

            server.enqueue(status(404))
            assertEquals(CalendarResult.Failed(404), repository.links(event))
        }

    @Test
    fun `a task from the event goes to the Inbox and is linked to the event`() =
        runBlocking {
            server.enqueue(json(taskJson(20), 201))
            server.enqueue(json(linkJson(60, "OUTGOING", endJson(20, "TASK", "Planning"), eventEnd), 201))
            assertEquals(LinkedCreateResult.Created("Planning (2026-10-21)"), repository.createTask("Planning (2026-10-21)", event))

            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/api/v1/tasks", create.url.encodedPath)
            assertEquals(JsonPrimitive("Planning (2026-10-21)"), create.json()["title"])
            assertEquals(JsonPrimitive("INBOX"), create.json()["list"])

            val link = server.takeRequest()
            assertEquals("POST", link.method)
            assertEquals("/api/v1/links", link.url.encodedPath)
            assertEquals(
                JsonObject(mapOf("id" to JsonPrimitive(id(20).toString()), "type" to JsonPrimitive("TASK"))),
                link.json()["source"],
            )
            assertEquals(
                JsonObject(mapOf("id" to JsonPrimitive(event.toString()), "type" to JsonPrimitive("EVENT"))),
                link.json()["target"],
            )
        }

    @Test
    fun `a note from the event is created and linked to the event`() =
        runBlocking {
            server.enqueue(json(noteJson(21), 201))
            server.enqueue(json(linkJson(61, "OUTGOING", endJson(21, "NOTE", "Planning"), eventEnd), 201))
            assertEquals(LinkedCreateResult.Created("Planning (2026-10-21)"), repository.createNote("Planning (2026-10-21)", event))
            val create = server.takeRequest()
            assertEquals("/api/v1/notes", create.url.encodedPath)
            assertEquals(JsonPrimitive("Planning (2026-10-21)"), create.json()["title"])
            assertEquals(JsonPrimitive("NOTE"), server.takeRequest().json()["source"]!!.jsonObject["type"])
        }

    @Test
    fun `a created item whose link fails is reported as not linked`() =
        runBlocking {
            server.enqueue(json(taskJson(20), 201))
            server.enqueue(status(409))
            assertEquals(LinkedCreateResult.NotLinked("Planning"), repository.createTask("Planning", event))
            server.enqueue(json(noteJson(21), 201))
            server.enqueue(status(500))
            assertEquals(LinkedCreateResult.NotLinked("Planning"), repository.createNote("Planning", event))
        }

    @Test
    fun `nothing is linked when the item could not be created`() =
        runBlocking {
            server.enqueue(status(400))
            assertEquals(LinkedCreateResult.Failed, repository.createTask("Planning", event))
            server.enqueue(status(503))
            assertEquals(LinkedCreateResult.Failed, repository.createNote("Planning", event))
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `a loaded event carries its reminders once each, ascending, and its guests with their answers`() =
        runBlocking {
            server.enqueue(
                json(
                    """{"id":"$event","calendarId":"${id(90)}","title":"Planning","allDay":false,"exdates":[],
                    |"reminderMinutes":[60,10,60],"status":"CONFIRMED","syncState":"LOCAL","transparency":"OPAQUE",
                    |"attendees":[{"email":"ada@example.org","name":"Ada","status":"ACCEPTED"},{"email":"bob@example.org"}],
                    |"start":"2026-10-21T08:00:00Z","end":"2026-10-21T09:00:00Z",$stamp}
                    """.trimMargin().replace("\n", ""),
                ),
            )
            val details = (events.event(event) as CalendarResult.Ok).value
            assertEquals(listOf(10, 60), details.reminderMinutes)
            assertEquals(
                listOf(
                    Attendee("ada@example.org", "Ada", AttendeeStatus.ACCEPTED),
                    Attendee("bob@example.org", null, AttendeeStatus.NEEDS_ACTION),
                ),
                details.attendees,
            )
        }
}
