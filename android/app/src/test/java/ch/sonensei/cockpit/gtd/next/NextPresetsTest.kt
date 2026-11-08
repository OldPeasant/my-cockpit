package ch.sonensei.cockpit.gtd.next

import ch.sonensei.cockpit.core.settings.UserSettings
import ch.sonensei.cockpit.core.settings.UserSettingsChange
import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class NextPresetsTest {
    private val context = UUID.fromString("01890000-0000-7000-8000-000000000081")
    private val area = UUID.fromString("01890000-0000-7000-8000-000000000090")
    private val project = UUID.fromString("01890000-0000-7000-8000-000000000091")

    /** What the web app (`JSON.stringify(preset)`) writes for a full filter. */
    private val webText =
        """{"name":"Calls","filter":{"context":"$context","energy":"LOW","creativity":"ROUTINE","maxMinutes":15,""" +
            """"area":"$area","project":"$project","due":"week"}}"""
    private val full =
        NextFilter(context, Energy.LOW, Creativity.ROUTINE, 15, area, project, DueWindow.WEEK)

    @Test
    fun `reads a preset written by the web app`() {
        assertEquals(NextPreset("Calls", full), NextPresets.parse(webText))
    }

    @Test
    fun `writes presets exactly as the web app does`() {
        assertEquals(webText, NextPresets.encode(NextPreset("Calls", full)))
        assertEquals("""{"name":"All","filter":{}}""", NextPresets.encode(NextPreset("  All ", NextFilter())))
    }

    @Test
    fun `skips unreadable presets and invalid filter values`() {
        assertNull(NextPresets.parse("not json"))
        assertNull(NextPresets.parse("""{"filter":{}}"""))
        assertNull(NextPresets.parse("""{"name":"  ","filter":{}}"""))
        assertNull(NextPresets.parse("[1]"))
        val lenient =
            NextPresets.parse(
                """{"name":"Odd","filter":{"context":"nope","energy":"HUGE","maxMinutes":"30","due":"later","project":7}}""",
            )
        assertEquals(NextPreset("Odd", NextFilter(maxMinutes = 30)), lenient)
        assertEquals(NextPreset("No filter", NextFilter()), NextPresets.parse("""{"name":"No filter"}"""))
        assertNull(NextPresets.parse("""{"name":"Zero","filter":{"maxMinutes":0}}""")?.filter?.maxMinutes)
        assertEquals(listOf("Calls"), NextPresets.read(listOf("x", webText)).map { it.name })
    }

    @Test
    fun `saving replaces a preset of the same name in place and keeps unreadable texts`() {
        val other = NextPresets.encode(NextPreset("Home", NextFilter(energy = Energy.HIGH)))
        val texts = listOf("garbage", webText, other)
        val saved = NextPresets.saved(texts, NextPreset("calls", NextFilter(due = DueWindow.TODAY)))
        assertEquals(3, saved.size)
        assertEquals("garbage", saved[0])
        assertEquals(NextPreset("calls", NextFilter(due = DueWindow.TODAY)), NextPresets.parse(saved[1]))
        assertEquals(other, saved[2])

        val appended = NextPresets.saved(texts, NextPreset("New", NextFilter()))
        assertEquals(4, appended.size)
        assertEquals("New", NextPresets.parse(appended[3])?.name)
    }

    @Test
    fun `removing deletes the presets of that name in any case`() {
        val other = NextPresets.encode(NextPreset("Home", NextFilter()))
        assertEquals(listOf("garbage", other), NextPresets.removed(listOf("garbage", webText, other), " CALLS "))
        assertEquals(listOf(other), NextPresets.removed(listOf(other), "Missing"))
    }

    @Test
    fun `due windows follow the server`() {
        val today = LocalDate.of(2026, 11, 8)
        assertTrue(DueWindow.OVERDUE.contains(today.minusDays(1), today))
        assertFalse(DueWindow.OVERDUE.contains(today, today))
        assertTrue(DueWindow.TODAY.contains(today, today))
        assertFalse(DueWindow.TODAY.contains(today.plusDays(1), today))
        assertTrue(DueWindow.WEEK.contains(today, today))
        assertTrue(DueWindow.WEEK.contains(today.plusDays(6), today))
        assertFalse(DueWindow.WEEK.contains(today.plusDays(7), today))
        assertFalse(DueWindow.WEEK.contains(today.minusDays(1), today))
        assertEquals(DueWindow.WEEK, DueWindow.of("week"))
        assertNull(DueWindow.of("month"))
    }

    @Test
    fun `the settings carry the preset texts both ways`() {
        val json = JsonObject(mapOf("nextActionPresets" to JsonArray(listOf(JsonPrimitive(webText), JsonPrimitive(3)))))
        assertEquals(listOf(webText), UserSettings.parse(json).nextActionPresets)
        assertEquals(emptyList<String>(), UserSettings.parse(JsonObject(emptyMap())).nextActionPresets)

        val change = UserSettingsChange(nextActionPresets = listOf(webText))
        assertFalse(change.isEmpty)
        assertEquals(
            Json.parseToJsonElement("""{"nextActionPresets":[${JsonPrimitive(webText)}]}"""),
            change.toJson(),
        )
        assertEquals("""{"nextActionPresets":[]}""", UserSettingsChange(nextActionPresets = emptyList()).toJson().toString())
    }
}
