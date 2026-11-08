package ch.sonensei.cockpit.gtd.next

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The save dialog while a save is running, and the messages of a failed save. */
@RunWith(AndroidJUnit4::class)
class NextScreenPresetSavingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val task =
        GtdTask(UUID.fromString("00000000-0000-7000-8000-000000000001"), "Plant tulips", TaskList.NEXT, 0, 1)
    private val filtered =
        NextUiState(
            tasks = listOf(task),
            total = 1,
            filter = NextFilter(due = DueWindow.TODAY),
            refreshedAt = Instant.parse("2026-11-08T09:00:00Z"),
            today = LocalDate.of(2026, 11, 8),
        )
    private val calls = mutableListOf<String>()

    private fun show(state: NextUiState) {
        val callbacks =
            NextCallbacks(
                savePreset = { calls += "save" },
                cancelSavePreset = { calls += "cancel" },
            )
        composeRule.setContent { CockpitTheme { NextContent(state, callbacks) } }
    }

    @Test
    fun `while saving the name and the save button are disabled, cancel still works`() {
        show(filtered.copy(presets = PresetsUiState(editor = PresetEditor("Today", saving = true))))
        composeRule.onNodeWithTag(NextTags.PRESET_NAME).assertIsNotEnabled()
        composeRule.onNodeWithTag(NextTags.PRESET_CONFIRM).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(NextTags.PRESET_CANCEL).assertIsEnabled().performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun `a failed save shows why in the dialog and can be tried again`() {
        show(filtered.copy(presets = PresetsUiState(editor = PresetEditor("Today", PresetMessage.OFFLINE))))
        composeRule.onNodeWithTag(NextTags.PRESET_DIALOG_MESSAGE).assertIsDisplayed()
        composeRule.onNodeWithTag(NextTags.PRESET_CONFIRM).assertIsEnabled().performClick()
        assertEquals(listOf("save"), calls)
    }

    @Test
    fun `an empty name is reported in the dialog`() {
        show(filtered.copy(presets = PresetsUiState(editor = PresetEditor("", PresetMessage.NAME_REQUIRED))))
        composeRule.onNodeWithTag(NextTags.PRESET_DIALOG_MESSAGE).assertIsDisplayed()
        // The dialog's message, not one above the list.
        composeRule.onNodeWithTag(NextTags.PRESET_MESSAGE).assertDoesNotExist()
    }
}
