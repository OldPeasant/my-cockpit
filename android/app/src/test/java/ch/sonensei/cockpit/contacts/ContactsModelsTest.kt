package ch.sonensei.cockpit.contacts

import ch.sonensei.cockpit.core.network.generated.infrastructure.Serializer
import ch.sonensei.cockpit.core.network.generated.models.ContactRelatedResponse
import ch.sonensei.cockpit.core.network.generated.models.ContactResponse
import ch.sonensei.cockpit.core.network.generated.models.LinkListResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** How server answers become what the contacts screens show: blank fields, addresses, mail and link directions. */
class ContactsModelsTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun contact(fields: String): Contact =
        Serializer.kotlinxSerializationJson
            .decodeFromString(
                ContactResponse.serializer(),
                """{"id":"${id(1)}","displayName":"Anna Muster","source":"CARDDAV","createdAt":"2026-10-01T09:00:00Z",
                |"updatedAt":"2026-10-01T09:00:00Z","version":1,$fields}
                """.trimMargin().replace("\n", ""),
            ).toContact()

    private fun related(
        related: String,
        links: String = "[]",
    ): ContactRelated {
        val json = Serializer.kotlinxSerializationJson
        val response = json.decodeFromString(ContactRelatedResponse.serializer(), related.trimMargin().replace("\n", ""))
        val linkList = json.decodeFromString(LinkListResponse.serializer(), """{"items":${links.trimMargin().replace("\n", "")}}""")
        return response.toRelated(linkList.items)
    }

    @Test
    fun `addresses become one line each, skipping blank parts, and an address with nothing in it is dropped`() {
        val anna =
            contact(
                """"emails":[],"phones":[],"addresses":[
                |{"street":" Bahnhofstrasse 1 ","postalCode":"8001","city":"Zürich","region":"ZH","country":"CH","label":"work"},
                |{"postalCode":"3000"},
                |{"street":"  ","postalCode":" ","city":"Bern","country":""},
                |{"label":"old"},
                |{"street":" ","city":" "}]
                """,
            )
        assertEquals(
            listOf(
                LabeledValue("Bahnhofstrasse 1, 8001 Zürich, ZH, CH", "work"),
                LabeledValue("3000", null),
                LabeledValue("Bern", null),
            ),
            anna.addresses,
        )
    }

    @Test
    fun `blank organization, title and notes are left out, numbers and addresses are trimmed, blank ones dropped`() {
        val anna =
            contact(
                """"organization":"  ","title":"","notesMd":" \n ",
                |"emails":[{"address":" anna@example.org "},{"address":"   ","label":"work"}],
                |"phones":[{"number":"","label":"home"},{"number":" 079 000 00 01 ","label":"mobile"}],"addresses":[]
                """,
            )
        assertNull(anna.organization)
        assertNull(anna.title)
        assertNull(anna.notesMd)
        assertNull(anna.birthday)
        assertEquals(listOf(LabeledValue("anna@example.org", null)), anna.emails)
        assertEquals(listOf(LabeledValue("079 000 00 01", "mobile")), anna.phones)
        assertEquals("CARDDAV", anna.source)
    }

    @Test
    fun `filled organization, title and notes are kept as they are`() {
        val anna =
            contact(
                """"organization":"Beispiel AG","title":"Engineer","notesMd":"Met at the meetup",
                |"emails":[],"phones":[],"addresses":[]
                """,
            )
        assertEquals("Beispiel AG", anna.organization)
        assertEquals("Engineer", anna.title)
        assertEquals("Met at the meetup", anna.notesMd)
    }

    @Test
    fun `mails tell whether they came from the contact, prefer the received time and drop a blank subject`() {
        val result =
            related(
                """{"contactId":"${id(1)}","waitingFor":[{"id":"${id(2)}","title":"Send the offer"}],"events":[],"mails":[
                |{"id":"${id(3)}","accountId":"${id(9)}","direction":"TO","from":[],"to":[],"seen":true,"subject":" ",
                |"sentAt":"2026-10-19T07:30:00Z"},
                |{"id":"${id(4)}","accountId":"${id(9)}","direction":"FROM","from":[],"to":[],"seen":false,"subject":"Hello",
                |"sentAt":"2026-10-20T07:00:00Z","receivedAt":"2026-10-20T08:00:00+02:00"},
                |{"id":"${id(5)}","accountId":"${id(9)}","direction":"FROM","from":[],"to":[],"seen":false}]}
                """,
            )
        assertEquals(
            listOf(
                RelatedMail(id(3), null, fromContact = false, at = Instant.parse("2026-10-19T07:30:00Z")),
                RelatedMail(id(4), "Hello", fromContact = true, at = Instant.parse("2026-10-20T06:00:00Z")),
                RelatedMail(id(5), null, fromContact = true, at = null),
            ),
            result.mails,
        )
        assertEquals(listOf(RelatedWaiting(id(2), "Send the offer", null)), result.waitingFor)
    }

    @Test
    fun `a timed event keeps its start instant, an all-day one its date`() {
        val result =
            related(
                """{"contactId":"${id(1)}","waitingFor":[],"mails":[],"events":[
                |{"eventId":"${id(6)}","calendarId":"${id(9)}","title":"Call","allDay":false,"start":"2026-11-03T10:00:00+01:00"},
                |{"eventId":"${id(7)}","calendarId":"${id(9)}","title":"Lunch","allDay":true,"startDate":"2026-11-02"}]}
                """,
            )
        assertEquals(RelatedEvent(id(6), "Call", Instant.parse("2026-11-03T09:00:00Z"), null), result.events[0])
        assertEquals(RelatedEvent(id(7), "Lunch", null, LocalDate.of(2026, 11, 2)), result.events[1])
    }

    @Test
    fun `a link shows its other end, the target when outgoing, the source when incoming`() {
        val result =
            related(
                """{"contactId":"${id(1)}","waitingFor":[],"mails":[],"events":[]}""",
                """[{"id":"${id(10)}","direction":"OUTGOING","origin":"MANUAL","createdAt":"2026-10-01T09:00:00Z",
                |"source":{"type":"CONTACT","id":"${id(1)}","title":"Anna Muster","available":true},
                |"target":{"type":"TASK","id":"${id(11)}","title":"Send the offer","subtitle":"Next action","available":true}},
                |{"id":"${id(12)}","direction":"INCOMING","origin":"SYNC","createdAt":"2026-10-01T09:00:00Z",
                |"source":{"type":"JOURNAL_ENTRY","id":"${id(13)}","available":false},
                |"target":{"type":"CONTACT","id":"${id(1)}","title":"Anna Muster","available":true}}]
                """,
            )
        assertEquals(
            listOf(
                RelatedLink(id(10), "TASK", "Send the offer", "Next action", true),
                RelatedLink(id(12), "JOURNAL_ENTRY", null, null, false),
            ),
            result.links,
        )
        // The contact's own end never shows up as a linked item.
        assertTrue(result.links.none { it.type == "CONTACT" })
    }

    @Test
    fun `nothing related maps to empty lists`() {
        val result = related("""{"contactId":"${id(1)}","waitingFor":[],"mails":[],"events":[]}""")
        assertEquals(ContactRelated(emptyList(), emptyList(), emptyList(), emptyList()), result)
    }
}
