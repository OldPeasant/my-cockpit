package ch.sonensei.cockpit.calendar.edit

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Form validation, the repeat presets, the bodies sent and the scopes offered. */
class EventFormTest {
    private val zurich = ZoneId.of("Europe/Zurich")
    private val calendar = UUID.fromString("00000000-0000-7000-8000-000000000090")
    private val day = LocalDate.parse("2026-10-21")

    private val form =
        EventForm(
            title = "Dentist",
            calendarId = calendar,
            startDate = day,
            startTime = LocalTime.of(10, 0),
            endDate = day,
            endTime = LocalTime.of(11, 0),
            zone = zurich,
        )

    @Test
    fun `a complete form has no errors`() = assertEquals(emptySet<FormError>(), form.errors)

    @Test
    fun `a blank title, a missing calendar and an end before the start are errors`() {
        val bad = form.copy(title = "  ", calendarId = null, endTime = LocalTime.of(9, 0))
        assertEquals(setOf(FormError.TITLE_MISSING, FormError.CALENDAR_MISSING, FormError.END_BEFORE_START), bad.errors)
    }

    @Test
    fun `a timed event must end after its start, an all-day one not before its first day`() {
        assertFalse(form.copy(endTime = LocalTime.of(10, 0)).endsAfterStart)
        assertTrue(form.copy(endDate = day.plusDays(1), endTime = LocalTime.of(9, 0)).endsAfterStart)
        assertTrue(form.copy(allDay = true, endTime = LocalTime.of(9, 0)).endsAfterStart)
        assertFalse(form.copy(allDay = true, endDate = day.minusDays(1)).endsAfterStart)
    }

    @Test
    fun `presets are FREQ rules, other rules are custom`() {
        assertEquals(Repeat.NONE, repeatOf(null))
        assertEquals(Repeat.NONE, repeatOf(" "))
        assertEquals(Repeat.WEEKLY, repeatOf("RRULE:FREQ=WEEKLY"))
        assertEquals(Repeat.DAILY, repeatOf("FREQ=DAILY"))
        assertEquals(Repeat.CUSTOM, repeatOf("FREQ=WEEKLY;BYDAY=MO,WE"))
        assertEquals("FREQ=MONTHLY", form.copy(repeat = Repeat.MONTHLY).rrule)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", form.copy(repeat = Repeat.CUSTOM, rule = "RRULE:FREQ=WEEKLY;BYDAY=MO").rrule)
        assertEquals(null, form.rrule)
    }

    @Test
    fun `moving the start keeps the length`() {
        val moved = form.withStartTime(LocalTime.of(23, 30))
        assertEquals(day.plusDays(1) to LocalTime.of(0, 30), moved.endDate to moved.endTime)
        val otherDay = form.copy(endDate = day.plusDays(2)).withStartDate(day.plusDays(5))
        assertEquals(day.plusDays(7), otherDay.endDate)
    }

    @Test
    fun `the create body has the times in UTC, the zone and the rule`() {
        val id = UUID.fromString("00000000-0000-7000-8000-000000000001")
        val body = createBody(form.copy(repeat = Repeat.WEEKLY, location = " Room 2 "), id)
        assertEquals(JsonPrimitive(id.toString()), body["id"])
        assertEquals(JsonPrimitive("2026-10-21T08:00:00Z"), body["start"])
        assertEquals(JsonPrimitive("2026-10-21T09:00:00Z"), body["end"])
        assertEquals(JsonPrimitive("Europe/Zurich"), body["timeZone"])
        assertEquals(JsonPrimitive("FREQ=WEEKLY"), body["rrule"])
        assertEquals(JsonPrimitive("Room 2"), body["location"])
        assertFalse("description" in body)
    }

    @Test
    fun `an all-day create body has dates with an exclusive end and no zone`() {
        val body = createBody(form.copy(allDay = true, endDate = day.plusDays(1)), UUID.randomUUID())
        assertEquals(JsonPrimitive("2026-10-21"), body["startDate"])
        assertEquals(JsonPrimitive("2026-10-23"), body["endDate"])
        assertFalse("start" in body)
        assertFalse("timeZone" in body)
    }

    @Test
    fun `a patch has only the changed fields, cleared ones as null`() {
        val patch = patchBody(form.copy(location = "Room 2"), form.copy(title = "Dentist ", location = "", description = "Bring card"))
        assertEquals(setOf("location", "description"), patch.keys)
        assertEquals(JsonNull, patch["location"])
        assertEquals(emptySet<String>(), patchBody(form, form).keys)
    }

    @Test
    fun `changed times are sent together`() {
        val patch = patchBody(form, form.copy(endTime = LocalTime.of(12, 0)))
        assertEquals(setOf("start", "end"), patch.keys)
        val toAllDay = patchBody(form, form.copy(allDay = true))
        assertEquals(setOf("allDay", "startDate", "endDate"), toAllDay.keys)
        val toTimed = patchBody(form.copy(allDay = true), form)
        assertEquals(setOf("allDay", "start", "end", "timeZone"), toTimed.keys)
    }

    @Test
    fun `a new rule or calendar can't apply to one occurrence`() {
        assertEquals(EditScope.entries, scopesFor(patchBody(form, form.copy(title = "Doctor"))))
        val newRule = patchBody(form.copy(repeat = Repeat.WEEKLY), form.copy(repeat = Repeat.DAILY))
        assertEquals(JsonPrimitive("FREQ=DAILY"), newRule["rrule"])
        assertEquals(listOf(EditScope.FOLLOWING, EditScope.ALL), scopesFor(newRule))
        val noRule = patchBody(form.copy(repeat = Repeat.WEEKLY), form)
        assertEquals(JsonNull, noRule["rrule"])
        assertEquals(listOf(EditScope.FOLLOWING, EditScope.ALL), scopesFor(patchBody(form, form.copy(calendarId = UUID.randomUUID()))))
    }

    @Test
    fun `a time skipped by the DST change moves forward by the gap`() {
        // 29 March 2026: Zurich clocks jump from 02:00 to 03:00.
        assertEquals("2026-03-29T01:30:00Z", instant(LocalDate.parse("2026-03-29"), LocalTime.of(2, 30), zurich))
    }
}
