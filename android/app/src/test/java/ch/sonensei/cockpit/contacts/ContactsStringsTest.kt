package ch.sonensei.cockpit.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every contacts string exists in English and German, is not blank and keeps its placeholders. */
class ContactsStringsTest {
    private val res = File("src/main/res")
    private val placeholder = Regex("%(\\d+\\$)?[sdf]")

    private fun texts(file: File): Map<String, String> {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length).map { strings.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
    }

    private fun placeholders(text: String) =
        placeholder
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()

    private val english = texts(File(res, "values/strings_contacts.xml"))
    private val german = texts(File(res, "values-de/strings_contacts.xml"))

    @Test
    fun `every contacts string exists in German with the same placeholders`() {
        assertEquals(english.keys, german.keys)
        german.forEach { (key, text) -> assertTrue("$key is blank in German", text.isNotBlank()) }
        english.forEach { (key, text) -> assertEquals(key, placeholders(text), placeholders(german.getValue(key))) }
    }

    @Test
    fun `the strings of create, edit, delete and the source line are in both languages`() {
        val added =
            listOf(
                "contacts_new",
                "contacts_edit",
                "contacts_delete",
                "contacts_edit_title",
                "contacts_save",
                "contacts_cancel",
                "contacts_given_name",
                "contacts_family_name",
                "contacts_display_name",
                "contacts_organization",
                "contacts_job_title",
                "contacts_label",
                "contacts_street",
                "contacts_postal_code",
                "contacts_city",
                "contacts_region",
                "contacts_country",
                "contacts_birthday_hint",
                "contacts_add_email",
                "contacts_add_phone",
                "contacts_add_address",
                "contacts_remove_field",
                "contacts_name_required",
                "contacts_invalid_birthday",
                "contacts_save_offline",
                "contacts_save_failed",
                "contacts_save_conflict",
                "contacts_save_invalid",
                "contacts_save_invalid_detail",
                "contacts_delete_conflict",
                "contacts_not_allowed",
                "contacts_delete_title",
                "contacts_delete_text",
                "contacts_trashed",
                "contacts_delete_failed",
                "contacts_source_local",
                "contacts_source_synced",
            )
        added.forEach { key ->
            assertTrue("$key missing in English", english[key]?.isNotBlank() == true)
            assertTrue("$key missing in German", german[key]?.isNotBlank() == true)
        }
        // The source, the contact's name and the trashed contact are filled in, in both languages.
        listOf(
            "contacts_not_allowed",
            "contacts_delete_text",
            "contacts_trashed",
            "contacts_source_synced",
            "contacts_save_invalid_detail",
        ).forEach { key ->
            assertEquals(key, listOf("%1\$s"), placeholders(english.getValue(key)))
            assertEquals(key, listOf("%1\$s"), placeholders(german.getValue(key)))
        }
    }

    @Test
    fun `German contacts strings quote with the low-high quotes the rest of the app uses`() {
        german.forEach { (key, text) ->
            assertTrue("$key uses guillemets: $text", '«' !in text && '»' !in text)
            assertTrue("$key uses straight quotes: $text", '"' !in text)
        }
        // The quoted contact name keeps its placeholder between „ and “.
        assertEquals("„%1\$s“ wird in den Papierkorb verschoben.", german.getValue("contacts_delete_text"))
    }
}
