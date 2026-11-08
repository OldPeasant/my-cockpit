package ch.sonensei.cockpit.gtd.inbox

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.settings.LocalUserSettings
import ch.sonensei.cockpit.core.settings.UserSettings
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Inbox processing shows the energy/creativity labels the user named in the synced settings. */
@RunWith(AndroidJUnit4::class)
class InboxUserLabelsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val task = GtdTask(UUID.fromString("00000000-0000-7000-8000-000000000001"), "Water the plants", TaskList.INBOX, 0, 1)
    private val processing =
        InboxUiState(
            tasks = listOf(task),
            refreshedAt = Instant.parse("2026-10-20T09:00:00Z"),
            today = LocalDate.of(2026, 10, 20),
            processing = true,
        )
    private val renamed =
        UserSettings(
            energyLabels = mapOf("LOW" to "Drained"),
            creativityLabels = mapOf("CREATIVE" to "Artsy"),
        )

    private val calls = mutableListOf<String>()
    private var settings by mutableStateOf<UserSettings?>(null)

    private fun show(initial: UserSettings?) {
        settings = initial
        val callbacks =
            InboxCallbacks(
                setEnergy = { calls += "energy $it" },
                setCreativity = { calls += "creativity $it" },
            )
        composeRule.setContent {
            CockpitTheme {
                CompositionLocalProvider(LocalUserSettings provides settings) { InboxContent(processing, callbacks) }
            }
        }
    }

    @Test
    fun `renamed labels replace the app's, the others keep the translation`() {
        show(renamed)

        composeRule.onNodeWithText("Drained").assertExists()
        composeRule.onNodeWithText("Low").assertDoesNotExist()
        composeRule.onNodeWithText("High").assertExists()
        composeRule.onNodeWithText("Artsy").assertExists()
        composeRule.onNodeWithText("Routine").assertExists()
    }

    @Test
    fun `a renamed chip still sets its level`() {
        show(renamed)

        composeRule.onNodeWithText("Drained").performScrollTo().performClick()
        composeRule.onNodeWithText("Artsy").performScrollTo().performClick()

        assertEquals(listOf("energy LOW", "creativity CREATIVE"), calls)
    }

    @Test
    fun `labels follow settings loaded while the screen is shown`() {
        show(null)
        composeRule.onNodeWithText("Low").assertExists()

        settings = renamed
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Drained").assertExists()
        composeRule.onNodeWithText("Low").assertDoesNotExist()

        // Logout: the settings are gone, the app's labels return.
        settings = null
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Low").assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun `in German the server's English default labels are translated, renamed ones shown as typed`() {
        val fromServer =
            UserSettings.parse(
                Json
                    .parseToJsonElement(
                        """{"energyLabels":{"LOW":"Drained","MEDIUM":"Medium","HIGH":"High"},
                           "creativityLabels":{"ROUTINE":"Routine","MIXED":"Mixed","CREATIVE":"Creative"}}""",
                    ).jsonObject,
            )
        show(fromServer)

        composeRule.onNodeWithText("Drained").assertExists()
        composeRule.onNodeWithText("Hoch").assertExists()
        composeRule.onNodeWithText("High").assertDoesNotExist()
        composeRule.onNodeWithText("Gemischt").assertExists()
        composeRule.onNodeWithText("Kreativ").assertExists()
        composeRule.onNodeWithText("Creative").assertDoesNotExist()
    }
}
