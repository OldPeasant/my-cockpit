package ch.sonensei.cockpit.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every settings string exists in English and German, with the same placeholders and plural forms. */
class SettingsStringsTest {
    private val res = File("src/main/res")
    private val placeholder = Regex("%(\\d+\\$)?[sdf]")

    private fun elements(
        file: File,
        tag: String,
    ): List<Element> {
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun strings(file: File): Map<String, String> = elements(file, "string").associate { it.getAttribute("name") to it.textContent }

    /** Plural name to its quantities and texts. */
    private fun plurals(file: File): Map<String, Map<String, String>> =
        elements(file, "plurals").associate { plural ->
            val items = plural.getElementsByTagName("item")
            plural.getAttribute("name") to
                (0 until items.length).associate {
                    val item = items.item(it) as Element
                    item.getAttribute("quantity") to item.textContent
                }
        }

    private fun placeholders(text: String) =
        placeholder
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()

    private val english = File(res, "values/strings_settings.xml")
    private val german = File(res, "values-de/strings_settings.xml")

    @Test
    fun `every settings string exists in German, is not blank and has the same placeholders`() {
        val en = strings(english)
        val de = strings(german)

        assertTrue(en.isNotEmpty())
        assertEquals(en.keys, de.keys)
        de.forEach { (key, text) -> assertTrue("$key is blank in German", text.isNotBlank()) }
        en.forEach { (key, text) -> assertEquals(key, placeholders(text), placeholders(de.getValue(key))) }
    }

    @Test
    fun `the pending captures plural has one and other in both languages, each with the count`() {
        val en = plurals(english)
        val de = plurals(german)

        assertEquals(setOf("settings_capture_pending"), en.keys)
        assertEquals(en.keys, de.keys)
        listOf(en, de).forEach { language ->
            val items = language.getValue("settings_capture_pending")
            assertEquals(setOf("one", "other"), items.keys)
            items.forEach { (quantity, text) -> assertEquals(quantity, listOf("%1\$d"), placeholders(text)) }
        }
    }
}
