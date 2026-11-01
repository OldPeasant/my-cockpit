package ch.sonensei.cockpit.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every Dashboard string and plural exists in English and German, is not blank and keeps its placeholders. */
class DashboardStringsTest {
    private val res = File("src/main/res")
    private val placeholder = Regex("%(\\d+\\$)?[sdf]")

    /** `name` (plurals as `name/quantity`) to text. */
    private fun texts(file: File): Map<String, String> {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
        val strings = document.getElementsByTagName("string")
        val plurals = document.getElementsByTagName("plurals")
        val single = (0 until strings.length).map { strings.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
        val counted =
            (0 until plurals.length).map { plurals.item(it) as Element }.flatMap { plural ->
                val items = plural.getElementsByTagName("item")
                (0 until items.length).map { items.item(it) as Element }.map {
                    "${plural.getAttribute("name")}/${it.getAttribute("quantity")}" to it.textContent
                }
            }
        return single + counted
    }

    private fun placeholders(text: String) =
        placeholder
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()

    private val english = texts(File(res, "values/strings_dashboard.xml"))
    private val german = texts(File(res, "values-de/strings_dashboard.xml"))

    @Test
    fun `every dashboard string exists in German with the same placeholders`() {
        assertTrue("nav_dashboard" in english)
        assertTrue("dashboard_inbox_tasks/other" in english)
        assertEquals(english.keys, german.keys)
        german.forEach { (key, text) -> assertTrue("$key is blank in German", text.isNotBlank()) }
        english.forEach { (key, text) -> assertEquals(key, placeholders(text), placeholders(german.getValue(key))) }
    }

    @Test
    fun `every widget has a title in both languages, and the German tab label is Uebersicht`() {
        val titles =
            listOf(
                "dashboard_today",
                "dashboard_inbox",
                "dashboard_upcoming",
                "dashboard_waiting_overdue",
                "dashboard_stalled_projects",
                "dashboard_weekly_review",
                "dashboard_read_later",
                "dashboard_pinned_notes",
            )
        titles.forEach { key ->
            assertTrue("$key missing in English", english[key]?.isNotBlank() == true)
            assertTrue("$key missing in German", german[key]?.isNotBlank() == true)
        }
        assertEquals("Übersicht", german["nav_dashboard"])
    }
}
