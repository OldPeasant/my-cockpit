package ch.sonensei.cockpit.calendar.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every event editor string exists in English and German, is not blank, keeps its placeholders and is translated. */
class CalendarEditStringsTest {
    private val res = File("src/main/res")
    private val placeholder = Regex("%(\\d+\\$)?[sdf]")

    /** The strings by name, and each plural's items as `name#quantity`. */
    private fun strings(file: File): Map<String, String> {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
        val strings = document.getElementsByTagName("string")
        val items = document.getElementsByTagName("item")
        val single =
            (0 until strings.length)
                .map { strings.item(it) as Element }
                .associate { it.getAttribute("name") to it.textContent }
        val plural =
            (0 until items.length)
                .map { items.item(it) as Element }
                .associate { "${(it.parentNode as Element).getAttribute("name")}#${it.getAttribute("quantity")}" to it.textContent }
        return single + plural
    }

    private fun placeholders(text: String) =
        placeholder
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()

    private val english = strings(File(res, "values/strings_calendar_edit.xml"))
    private val german = strings(File(res, "values-de/strings_calendar_edit.xml"))

    /** The strings the reminders, guests, invitations, time zone and links sections added. */
    private val added =
        listOf(
            "calendar_edit_add_guest",
            "calendar_edit_add_reminder",
            "calendar_edit_create_note",
            "calendar_edit_create_task",
            "calendar_edit_guest_accepted",
            "calendar_edit_guest_declined",
            "calendar_edit_guest_delegated",
            "calendar_edit_guest_email",
            "calendar_edit_guest_invalid",
            "calendar_edit_guest_pending",
            "calendar_edit_guest_remove",
            "calendar_edit_guests",
            "calendar_edit_guests_read_only",
            "calendar_edit_guest_tentative",
            "calendar_edit_invite",
            "calendar_edit_invite_account",
            "calendar_edit_invite_after_save",
            "calendar_edit_invite_cancel",
            "calendar_edit_invite_cancel_sent#one",
            "calendar_edit_invite_cancel_sent#other",
            "calendar_edit_invite_failed",
            "calendar_edit_invite_load_failed",
            "calendar_edit_invite_loading",
            "calendar_edit_invite_no_accounts",
            "calendar_edit_invite_request",
            "calendar_edit_invite_request_sent#one",
            "calendar_edit_invite_request_sent#other",
            "calendar_edit_invite_save_first",
            "calendar_edit_invite_send",
            "calendar_edit_invite_skip",
            "calendar_edit_invite_tell_removed",
            "calendar_edit_invite_to",
            "calendar_edit_links",
            "calendar_edit_links_failed",
            "calendar_edit_links_loading",
            "calendar_edit_links_none",
            "calendar_edit_link_type_bookmark",
            "calendar_edit_link_type_contact",
            "calendar_edit_link_type_email",
            "calendar_edit_link_type_event",
            "calendar_edit_link_type_file",
            "calendar_edit_link_type_folder",
            "calendar_edit_link_type_habit",
            "calendar_edit_link_type_journal",
            "calendar_edit_link_type_note",
            "calendar_edit_link_type_other",
            "calendar_edit_link_type_project",
            "calendar_edit_link_type_task",
            "calendar_edit_link_unavailable",
            "calendar_edit_no_guests",
            "calendar_edit_no_reminders",
            "calendar_edit_note_created",
            "calendar_edit_note_failed",
            "calendar_edit_note_not_linked",
            "calendar_edit_reminder_at_start",
            "calendar_edit_reminder_custom",
            "calendar_edit_reminder_custom_invalid",
            "calendar_edit_reminder_custom_label",
            "calendar_edit_reminder_custom_title",
            "calendar_edit_reminder_days#one",
            "calendar_edit_reminder_days#other",
            "calendar_edit_reminder_hours#one",
            "calendar_edit_reminder_hours#other",
            "calendar_edit_reminder_minutes#one",
            "calendar_edit_reminder_minutes#other",
            "calendar_edit_reminder_remove",
            "calendar_edit_reminders",
            "calendar_edit_reminder_weeks#one",
            "calendar_edit_reminder_weeks#other",
            "calendar_edit_task_created",
            "calendar_edit_task_failed",
            "calendar_edit_task_not_linked",
            "calendar_edit_time_zone_change",
            "calendar_edit_time_zone_none",
            "calendar_edit_time_zone_search",
            "calendar_edit_time_zone_title",
        )

    /** Words that are the same in both languages. */
    private val same = setOf("calendar_edit_ok", "calendar_edit_guests", "calendar_edit_link_type_email", "calendar_edit_link_type_event")

    @Test
    fun `every new string exists in English and German`() {
        added.forEach { key ->
            assertTrue("$key is missing in English", key in english)
            assertTrue("$key is missing in German", key in german)
        }
    }

    @Test
    fun `every string exists in German, is not blank and keeps its placeholders`() {
        assertTrue(english.keys.all { it.startsWith("calendar_edit_") })
        assertEquals(english.keys, german.keys)
        german.forEach { (key, text) -> assertTrue("$key is blank in German", text.isNotBlank()) }
        english.forEach { (key, text) -> assertEquals(key, placeholders(text), placeholders(german.getValue(key))) }
    }

    @Test
    fun `the new German texts are translated, not copied`() {
        added.filter { it !in same }.forEach { key -> assertNotEquals(key, english.getValue(key), german.getValue(key)) }
    }

    @Test
    fun `the strings the sections fill in have their placeholder`() {
        listOf(
            "calendar_edit_time_zone_change",
            "calendar_edit_guest_remove",
            "calendar_edit_reminder_remove",
            "calendar_edit_invite_to",
            "calendar_edit_invite_tell_removed",
            "calendar_edit_task_created",
            "calendar_edit_note_created",
        ).forEach { key -> assertEquals(key, listOf("%1\$s"), placeholders(english.getValue(key))) }
        assertEquals(listOf("%1\$d"), placeholders(english.getValue("calendar_edit_reminder_custom_invalid")))
        listOf("minutes", "hours", "days", "weeks").forEach { unit ->
            listOf("one", "other").forEach { q ->
                val key = "calendar_edit_reminder_$unit#$q"
                assertEquals(key, 1, placeholders(english.getValue(key)).size)
            }
        }
    }
}
