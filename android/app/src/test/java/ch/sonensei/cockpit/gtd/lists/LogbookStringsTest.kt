package ch.sonensei.cockpit.gtd.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every Logbook string exists in English and German, is not blank, is translated and keeps its placeholders. */
class LogbookStringsTest {
    private val res = File("src/main/res")
    private val placeholder = Regex("%(\\d+\\$)?[sdf]")

    private fun strings(file: File): Map<String, String> {
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
            .filterKeys { it.startsWith("logbook_") || it == "nav_logbook" }
    }

    private fun placeholders(text: String) =
        placeholder
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()

    private val english = strings(File(res, "values/strings_projects_lists.xml"))
    private val german = strings(File(res, "values-de/strings_projects_lists.xml"))

    @Test
    fun `every logbook string exists in German with the same placeholders`() {
        assertTrue("nav_logbook" in english)
        assertTrue(english.size > 1)
        assertEquals(english.keys, german.keys)
        german.forEach { (key, text) -> assertTrue("$key is blank in German", text.isNotBlank()) }
        english.forEach { (key, text) -> assertEquals(key, placeholders(text), placeholders(german.getValue(key))) }
    }

    @Test
    fun `the strings with a title or value have exactly one placeholder`() {
        listOf("logbook_reopen_label", "logbook_reopened", "logbook_completed_at", "logbook_project", "logbook_contexts").forEach { key ->
            assertEquals(key, listOf("%1\$s"), placeholders(english.getValue(key)))
        }
    }

    @Test
    fun `the German texts are translated, not copied`() {
        // "Logbook" vs "Logbuch" etc.: no German text may equal its English one.
        english.forEach { (key, text) -> assertNotEquals(key, text, german.getValue(key)) }
    }
}
