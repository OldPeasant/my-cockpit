package ch.sonensei.cockpit.trash

import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.core.network.generated.models.TrashItemResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/** The countdown to the permanent deletion, the type labels, item keys, and the translations. */
class TrashModelsTest {
    private val now = Instant.parse("2026-10-29T12:00:00Z")

    private fun daysLeft(left: Duration) = daysUntilPurge(now.plus(left), now)

    @Test
    fun `whole days count exactly, a started day counts as a full one`() {
        assertEquals(1, daysLeft(Duration.ofDays(1)))
        assertEquals(2, daysLeft(Duration.ofDays(1).plusSeconds(1)))
        assertEquals(30, daysLeft(Duration.ofDays(30)))
        assertEquals(1, daysLeft(Duration.ofSeconds(1)))
        assertEquals(1, daysLeft(Duration.ofNanos(1)))
        assertEquals(1, daysLeft(Duration.ofHours(23).plusMinutes(59)))
    }

    @Test
    fun `a due or overdue item has no days left`() {
        assertEquals(0, daysLeft(Duration.ZERO))
        assertEquals(0, daysLeft(Duration.ofSeconds(-1)))
        assertEquals(0, daysLeft(Duration.ofDays(-3)))
    }

    @Test
    fun `every server item type has its own label, unknown types a generic one`() {
        val expected =
            mapOf(
                "TASK" to R.string.trash_type_task,
                "PROJECT" to R.string.trash_type_project,
                "NOTE" to R.string.trash_type_note,
                "EVENT" to R.string.trash_type_event,
                "EMAIL" to R.string.trash_type_email,
                "FILE" to R.string.trash_type_file,
                "FOLDER" to R.string.trash_type_folder,
                "CONTACT" to R.string.trash_type_contact,
                "BOOKMARK" to R.string.trash_type_bookmark,
                "JOURNAL_ENTRY" to R.string.trash_type_journal,
                "HABIT" to R.string.trash_type_habit,
            )
        expected.forEach { (type, label) -> assertEquals(type, label, trashTypeLabel(type)) }
        // The client knows every type the API can send. The weekly review is a well-known item that
        // can never be trashed, so it needs no trash label.
        TrashItemResponse.Type.entries.filter { it.value != "WEEKLY_REVIEW" }.forEach {
            assertNotEquals(it.value, R.string.trash_type_other, trashTypeLabel(it.value))
        }
        assertEquals(R.string.trash_type_other, trashTypeLabel("SPACESHIP"))
        assertEquals(R.string.trash_type_other, trashTypeLabel(""))
        assertEquals(R.string.trash_type_other, trashTypeLabel("task"))
    }

    @Test
    fun `items with the same id but different types have different keys`() {
        val id = UUID.fromString("00000000-0000-7000-8000-000000000001")
        val at = Instant.parse("2026-10-01T09:00:00Z")
        val task = TrashItem(id, "TASK", "Fictitious", null, at, at)
        val note = task.copy(type = "NOTE")
        assertNotEquals(task.key, note.key)
        assertEquals(task.key, task.copy(title = "Renamed").key)
    }

    /** `name -> text` for strings, `name#quantity -> text` for plural items. */
    private fun texts(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = doc.getElementsByTagName("string")
        val result = mutableMapOf<String, String>()
        for (i in 0 until strings.length) {
            val node = strings.item(i) as Element
            result[node.getAttribute("name")] = node.textContent
        }
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val plural = plurals.item(i) as Element
            val items = plural.getElementsByTagName("item")
            for (j in 0 until items.length) {
                val item = items.item(j) as Element
                result["${plural.getAttribute("name")}#${item.getAttribute("quantity")}"] = item.textContent
            }
        }
        return result
    }

    private val placeholder = Regex("""%(\d+\$)?[sd]""")

    @Test
    fun `every Trash string and plural exists in German with the same placeholders`() {
        val res = File("src/main/res")
        val english = texts(File(res, "values/strings_trash.xml"))
        val german = texts(File(res, "values-de/strings_trash.xml"))

        assertTrue(english.isNotEmpty())
        assertTrue("trash_purge_in_days#one" in english && "trash_purge_in_days#other" in english)
        assertEquals(english.keys, german.keys)
        german.forEach { (key, text) ->
            assertTrue("$key is blank in German", text.isNotBlank())
            assertEquals(
                "$key placeholders",
                placeholder.findAll(english.getValue(key)).map { it.value }.toList(),
                placeholder.findAll(text).map { it.value }.toList(),
            )
        }
    }
}
