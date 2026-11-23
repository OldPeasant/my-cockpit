package ch.sonensei.cockpit.gtd.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every Tickler string exists in English and German, is translated, and only the back-on text has one placeholder. */
class TicklerStringsTest {
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
            .filterKeys { it.startsWith("tickler_") || it == "nav_tickler" }
    }

    private val english = strings(File(res, "values/strings_projects_lists.xml"))
    private val german = strings(File(res, "values-de/strings_projects_lists.xml"))

    @Test
    fun `every tickler string exists in English and German`() {
        assertEquals(setOf("nav_tickler", "tickler_empty", "tickler_back_on"), english.keys)
        assertEquals(english.keys, german.keys)
        german.forEach { (key, text) -> assertTrue("$key is blank in German", text.isNotBlank()) }
        assertEquals("Wiedervorlage", german["nav_tickler"])
        assertEquals("Tickler", english["nav_tickler"])
    }

    @Test
    fun `the German texts are translated, not copied`() {
        english.forEach { (key, text) -> assertNotEquals(key, text, german.getValue(key)) }
    }

    @Test
    fun `only the back-on text has a placeholder, exactly one`() {
        listOf(english, german).forEach { texts ->
            texts.forEach { (key, text) ->
                val expected = if (key == "tickler_back_on") listOf("%1\$s") else emptyList()
                assertEquals(key, expected, placeholder.findAll(text).map { it.value }.toList())
            }
        }
    }
}
