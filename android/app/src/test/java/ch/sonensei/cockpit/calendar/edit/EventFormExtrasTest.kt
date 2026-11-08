package ch.sonensei.cockpit.calendar.edit

import ch.sonensei.cockpit.core.network.generated.models.AttendeeDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Reminders and guests in the form, the bodies that carry them, and the helpers of the new editor sections. */
class EventFormExtrasTest {
    private val calendar = UUID.fromString("00000000-0000-7000-8000-000000000090")
    private val day = LocalDate.parse("2026-10-21")
    private val id = UUID.fromString("00000000-0000-7000-8000-000000000001")

    private val form =
        EventForm(
            title = "Planning",
            calendarId = calendar,
            startDate = day,
            startTime = LocalTime.of(10, 0),
            endDate = day,
            endTime = LocalTime.of(11, 0),
            zone = ZoneId.of("Europe/Zurich"),
        )

    private fun ints(array: JsonArray) = array.map { (it as JsonPrimitive).content.toInt() }

    @Test
    fun `reminders are added once, kept ascending and removed`() {
        val withTwo = form.withReminder(60).withReminder(10).withReminder(60)
        assertEquals(listOf(10, 60), withTwo.reminders)
        assertEquals(listOf(0, 10, 60), withTwo.withReminder(0).reminders)
        assertEquals(listOf(60), withTwo.withoutReminder(10).reminders)
        // Removing one that isn't there changes nothing.
        assertEquals(listOf(10, 60), withTwo.withoutReminder(15).reminders)
    }

    @Test
    fun `the reminder presets match the web and the custom limit is four weeks`() {
        assertEquals(listOf(0, 5, 10, 15, 30, 60, 120, 1440, 10080), REMINDER_PRESETS)
        assertEquals(4 * 7 * 24 * 60, MAX_REMINDER_MINUTES)
    }

    @Test
    fun `a custom reminder is a whole number from 0 to the limit`() {
        assertEquals(0, customReminderMinutes("0"))
        assertEquals(45, customReminderMinutes(" 45 "))
        assertEquals(MAX_REMINDER_MINUTES, customReminderMinutes("$MAX_REMINDER_MINUTES"))
        assertNull(customReminderMinutes("${MAX_REMINDER_MINUTES + 1}"))
        assertNull(customReminderMinutes("-1"))
        assertNull(customReminderMinutes(""))
        assertNull(customReminderMinutes("   "))
        assertNull(customReminderMinutes("1.5"))
        assertNull(customReminderMinutes("ten"))
        assertNull(customReminderMinutes("99999999999"))
    }

    @Test
    fun `guests are trimmed, deduplicated ignoring case and removed ignoring case`() {
        val one = form.withAttendee("  ada@example.org ", "  Ada Example  ")
        assertEquals(listOf(Attendee("ada@example.org", "Ada Example", AttendeeStatus.NEEDS_ACTION)), one.attendees)
        assertSame(one, one.withAttendee("ADA@example.org", "Other"))
        assertSame(one, one.withAttendee("   "))
        assertEquals(
            null,
            form
                .withAttendee("bob@example.org", "  ")
                .attendees
                .single()
                .name,
        )
        val two = one.withAttendee("bob@example.org")
        assertEquals(listOf("ada@example.org", "bob@example.org"), two.attendees.map { it.email })
        assertEquals(listOf("bob@example.org"), two.withoutAttendee("Ada@Example.org").attendees.map { it.email })
        assertEquals(two.attendees, two.withoutAttendee("nobody@example.org").attendees)
    }

    @Test
    fun `an email address needs a local part, an at sign and a dotted domain`() {
        assertTrue(isEmailAddress("ada@example.org"))
        assertTrue(isEmailAddress("  ada.lovelace+cal@mail.example.org "))
        assertFalse(isEmailAddress("ada"))
        assertFalse(isEmailAddress("ada@example"))
        assertFalse(isEmailAddress("@example.org"))
        assertFalse(isEmailAddress("ada@@example.org"))
        assertFalse(isEmailAddress("ada lovelace@example.org"))
        assertFalse(isEmailAddress(""))
    }

    @Test
    fun `a create always sends the reminders, guests only for a local calendar`() {
        val full =
            form
                .withReminder(30)
                .withReminder(0)
                .withAttendee("ada@example.org", "Ada")
                .withAttendee("bob@example.org")
        val synced = createBody(full, id)
        assertEquals(listOf(0, 30), ints(synced.getValue("reminderMinutes").jsonArray))
        assertFalse("attendees" in synced)

        val local = createBody(full, id, withAttendees = true)
        assertEquals(
            JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "email" to JsonPrimitive("ada@example.org"),
                            "name" to JsonPrimitive("Ada"),
                            "status" to JsonPrimitive("NEEDS_ACTION"),
                        ),
                    ),
                    JsonObject(mapOf("email" to JsonPrimitive("bob@example.org"), "status" to JsonPrimitive("NEEDS_ACTION"))),
                ),
            ),
            local.getValue("attendees"),
        )
        // An event without reminders says so (the server would otherwise add none either way), no empty guest list.
        val bare = createBody(form, id, withAttendees = true)
        assertEquals(JsonArray(emptyList()), bare.getValue("reminderMinutes"))
        assertFalse("attendees" in bare)
    }

    @Test
    fun `a change sends reminders and guests only when they changed, guests only for a local calendar`() {
        val initial = form.withReminder(10).withAttendee("ada@example.org")
        assertEquals(JsonObject(emptyMap()), patchBody(initial, initial, withAttendees = true))

        val fewerReminders = initial.withoutReminder(10)
        assertEquals(JsonObject(mapOf("reminderMinutes" to JsonArray(emptyList()))), patchBody(initial, fewerReminders, true))

        val otherGuests = initial.withoutAttendee("ada@example.org").withAttendee("bob@example.org")
        assertEquals(JsonObject(emptyMap()), patchBody(initial, otherGuests))
        val local = patchBody(initial, otherGuests, withAttendees = true)
        assertEquals(setOf("attendees"), local.keys)
        assertEquals(
            listOf("bob@example.org"),
            local.getValue("attendees").jsonArray.map {
                (it as JsonObject).getValue("email").let { e ->
                    (e as JsonPrimitive).content
                }
            },
        )

        // Removing every guest sends an empty list, so they are removed on the server too.
        assertEquals(JsonArray(emptyList()), patchBody(initial, initial.withoutAttendee("ada@example.org"), true).getValue("attendees"))
    }

    @Test
    fun `a guest's answer is mapped from the API, an unknown or missing one is no answer yet`() {
        assertEquals(
            Attendee("ada@example.org", "Ada", AttendeeStatus.ACCEPTED),
            AttendeeDto("ada@example.org", "Ada", AttendeeDto.Status.ACCEPTED).toAttendee(),
        )
        assertEquals(Attendee("bob@example.org", null, AttendeeStatus.NEEDS_ACTION), AttendeeDto("bob@example.org", " ").toAttendee())
        AttendeeDto.Status.entries.forEach { status ->
            assertEquals(status.name, AttendeeDto("c@example.org", status = status).toAttendee().status.name)
        }
    }

    @Test
    fun `the time zone list holds region ids and UTC and is searched by any part, spaces as underscores`() {
        assertTrue("Europe/Zurich" in TIME_ZONES)
        assertTrue("UTC" in TIME_ZONES)
        assertFalse(TIME_ZONES.any { it.startsWith("Etc/") || it.startsWith("SystemV/") })
        assertEquals(TIME_ZONES.sorted(), TIME_ZONES)
        assertEquals(TIME_ZONES, zonesMatching("  "))
        assertTrue("America/New_York" in zonesMatching("new york"))
        assertTrue(zonesMatching("zurich").contains("Europe/Zurich"))
        assertTrue(zonesMatching("zurich").all { it.contains("zurich", ignoreCase = true) })
        assertEquals(emptyList<String>(), zonesMatching("Atlantis/Nowhere"))
    }

    @Test
    fun `the invitation prompt sends only over a loaded account, with someone to tell, and not twice`() {
        val account = MailAccount(UUID.fromString("00000000-0000-7000-8000-000000000070"), "Work mail", "me@example.org")
        val ready =
            InvitationUiState(
                eventId = id,
                title = "Planning",
                recipients = listOf("ada@example.org"),
                removed = emptyList(),
                afterSave = true,
                accounts = listOf(account),
                accountId = account.id,
            )
        assertTrue(ready.canSend)
        // No account picked, accounts still loading, or the picked one is not among them.
        assertFalse(ready.copy(accountId = null).canSend)
        assertFalse(ready.copy(accounts = null).canSend)
        assertFalse(ready.copy(accountId = id).canSend)
        assertFalse(ready.copy(sending = true).canSend)
        // Only removed guests: sent when they are to be told, nothing to send otherwise.
        val onlyRemoved = ready.copy(recipients = emptyList(), removed = listOf("bob@example.org"))
        assertTrue(onlyRemoved.canSend)
        assertFalse(onlyRemoved.copy(tellRemoved = false).canSend)
        assertFalse(ready.copy(recipients = emptyList()).canSend)
    }
}
