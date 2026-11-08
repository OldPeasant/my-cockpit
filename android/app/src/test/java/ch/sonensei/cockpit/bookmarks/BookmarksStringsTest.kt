package ch.sonensei.cockpit.bookmarks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every bookmarks string exists in English and German, is not blank and keeps its placeholders. */
class BookmarksStringsTest {
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

    private val english = texts(File(res, "values/strings_bookmarks.xml"))
    private val german = texts(File(res, "values-de/strings_bookmarks.xml"))

    @Test
    fun `every bookmarks string exists in German with the same placeholders`() {
        assertEquals(english.keys, german.keys)
        german.forEach { (key, text) -> assertTrue("$key is blank in German", text.isNotBlank()) }
        english.forEach { (key, text) -> assertEquals(key, placeholders(text), placeholders(german.getValue(key))) }
    }

    @Test
    fun `the strings of add, read later, the reader and to Inbox are in both languages`() {
        val added =
            listOf(
                "bookmarks_add",
                "bookmarks_add_heading",
                "bookmarks_field_url",
                "bookmarks_field_title_optional",
                "bookmarks_add_invalid_url",
                "bookmarks_add_failed",
                "bookmarks_read_later_add",
                "bookmarks_read_later_remove",
                "bookmarks_to_inbox",
                "bookmarks_added_to_inbox",
                "bookmarks_added_to_inbox_unlinked",
                "bookmarks_open_reader",
                "bookmarks_reader_back",
                "bookmarks_reader_original",
                "bookmarks_reader_no_copy",
                "bookmarks_reader_save",
                "bookmarks_reader_save_again",
                "bookmarks_reader_offline",
                "bookmarks_reader_failed",
                "bookmarks_reader_unreadable",
                "bookmarks_reader_truncated",
            )
        added.forEach { key ->
            assertTrue("$key missing in English", english[key]?.isNotBlank() == true)
            assertTrue("$key missing in German", german[key]?.isNotBlank() == true)
        }
        // The site of the original is filled into the label in both languages.
        assertEquals(listOf("%1\$s"), placeholders(german.getValue("bookmarks_reader_original")))
    }
}
