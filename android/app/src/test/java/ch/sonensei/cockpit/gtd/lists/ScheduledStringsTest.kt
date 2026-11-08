package ch.sonensei.cockpit.gtd.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every Scheduled string exists in English and German, is not blank, is translated and has no placeholder. */
class ScheduledStringsTest {
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
            .filterKeys { it.startsWith("scheduled_") || it == "nav_scheduled" }
    }

    private val english = strings(File(res, "values/strings_projects_lists.xml"))
    private val german = strings(File(res, "values-de/strings_projects_lists.xml"))

    @Test
    fun `every scheduled string exists in German`() {
        val expected =
            setOf(
                "nav_scheduled",
                "scheduled_empty",
                "scheduled_today",
                "scheduled_tomorrow",
                "scheduled_yesterday",
                "scheduled_no_date",
            )
        assertTrue("missing in English: ${expected - english.keys}", english.keys.containsAll(expected))
        assertEquals(english.keys, german.keys)
        german.forEach { (key, text) -> assertTrue("$key is blank in German", text.isNotBlank()) }
    }

    @Test
    fun `the German texts are translated, not copied`() {
        english.forEach { (key, text) -> assertNotEquals(key, text, german.getValue(key)) }
    }

    @Test
    fun `no scheduled string has a placeholder`() {
        (english + german.mapKeys { "de:${it.key}" }).forEach { (key, text) ->
            assertEquals(key, emptyList<String>(), placeholder.findAll(text).map { it.value }.toList())
        }
    }
}
