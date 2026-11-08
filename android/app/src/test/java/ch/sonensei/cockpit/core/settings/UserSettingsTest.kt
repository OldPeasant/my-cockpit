package ch.sonensei.cockpit.core.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class UserSettingsTest {
    private fun parse(json: String) = UserSettings.parse(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `unknown or invalid values fall back to the defaults`() {
        val settings =
            parse(
                """{"theme":"sepia","weekStart":"FUNDAY","timeZone":"Mars/Olympus","energyLabels":"x",
                   "mailImagePolicy":42,"mailImageAllowedSenders":[" example.org ","",7]}""",
            )

        assertEquals(ThemeSetting.SYSTEM, settings.theme)
        assertNull(settings.weekStart)
        assertNull(settings.timeZone)
        assertTrue(settings.energyLabels.isEmpty())
        assertEquals(MailImagePolicy.BLOCK, settings.mailImagePolicy)
        assertEquals(listOf("example.org"), settings.mailImageAllowedSenders)
    }

    @Test
    fun `an empty document is the defaults`() {
        assertEquals(UserSettings(), parse("{}"))
    }

    @Test
    fun `valid values are read`() {
        val settings =
            parse(
                """{"theme":"light","weekStart":"WEDNESDAY","mailImagePolicy":"allow",
                   "creativityLabels":{"ROUTINE":" Chores ","MIXED":"","CREATIVE":"Creative","OTHER":"x"}}""",
            )

        assertEquals(ThemeSetting.LIGHT, settings.theme)
        assertEquals(DayOfWeek.WEDNESDAY, settings.weekStart)
        assertEquals(MailImagePolicy.ALLOW, settings.mailImagePolicy)
        assertEquals(mapOf("ROUTINE" to "Chores"), settings.creativityLabels)
    }

    @Test
    fun `a change sends only the set keys`() {
        assertTrue(UserSettingsChange().isEmpty)
        assertEquals(JsonObject(emptyMap()), UserSettingsChange().toJson())
        assertEquals(
            Json.parseToJsonElement("""{"theme":"dark","mailImagePolicy":"allow","mailImageAllowedSenders":["example.org"]}"""),
            UserSettingsChange(ThemeSetting.DARK, MailImagePolicy.ALLOW, listOf("example.org")).toJson(),
        )
    }
}
