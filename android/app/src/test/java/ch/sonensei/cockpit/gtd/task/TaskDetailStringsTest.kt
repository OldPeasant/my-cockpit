package ch.sonensei.cockpit.gtd.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every task detail string exists in English and German, is not blank, keeps its placeholders and is translated. */
class TaskDetailStringsTest {
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

    private val english = strings(File(res, "values/strings_task_detail.xml"))
    private val german = strings(File(res, "values-de/strings_task_detail.xml"))

    /** Words that are the same in both languages. */
    private val same = setOf("task_detail_ok", "task_detail_notes_hint", "task_detail_waiting_person")

    @Test
    fun `every string exists in German, is not blank and keeps its placeholders`() {
        assertTrue(english.size > 10)
        assertTrue(english.keys.all { it.startsWith("task_detail_") })
        assertEquals(english.keys, german.keys)
        german.forEach { (key, text) -> assertTrue("$key is blank in German", text.isNotBlank()) }
        english.forEach { (key, text) -> assertEquals(key, placeholders(text), placeholders(german.getValue(key))) }
    }

    @Test
    fun `the German texts are translated, not copied`() {
        english.filterKeys { it !in same }.forEach { (key, text) -> assertNotEquals(key, text, german.getValue(key)) }
    }

    @Test
    fun `the strings the screen fills in have their placeholder`() {
        mapOf(
            "task_detail_message_invalid" to "%1\$s",
            "task_detail_message_moved" to "%1\$s",
            "task_detail_offline_from" to "%1\$s",
            "task_detail_item_toggle" to "%1\$s",
            "task_detail_item_delete" to "%1\$s",
            "task_detail_minutes" to "%1\$d",
            "task_detail_error_estimate" to "%1\$d",
            "task_detail_error_too_long#one" to "%1\$d",
            "task_detail_error_too_long#other" to "%1\$d",
        ).forEach { (key, expected) -> assertEquals(key, listOf(expected), placeholders(english.getValue(key))) }
    }
}
