package ch.sonensei.cockpit.gtd.next

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.projects.Area
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The due and area/project filters and the presets on the screen, driven by a state the test controls. */
@RunWith(AndroidJUnit4::class)
class NextScreenPresetsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val home = Area(id(90), "Home", 0)
    private val garden = GtdProject(id(50), "Garden", ProjectStatus.ACTIVE, false, 1, areaId = home.id)
    private val task = GtdTask(id(1), "Plant tulips", TaskList.NEXT, 0, 1, projectId = garden.id)
    private val soon = NextPreset("Soon", NextFilter(due = DueWindow.WEEK))
    private val loaded =
        NextUiState(
            tasks = listOf(task),
            total = 1,
            areas = listOf(home),
            projects = listOf(garden),
            refreshedAt = Instant.parse("2026-11-08T09:00:00Z"),
            today = LocalDate.of(2026, 11, 8),
        )

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(loaded)

    private fun show(initial: NextUiState) {
        state = initial
        val callbacks =
            NextCallbacks(
                toggleDue = { calls += "due $it" },
                clearDue = { calls += "due any" },
                chooseArea = { calls += "area $it" },
                chooseProject = { calls += "project $it" },
                applyPreset = { calls += "apply ${it.name}" },
                removePreset = { calls += "remove ${it.name}" },
                openSavePreset = { calls += "open" },
                editPresetName = { calls += "name $it" },
                savePreset = { calls += "save" },
                cancelSavePreset = { calls += "cancel" },
            )
        composeRule.setContent { CockpitTheme { NextContent(state, callbacks) } }
    }

    @Test
    fun `due chips toggle the due window and any clears it`() {
        show(loaded.copy(filter = NextFilter(due = DueWindow.TODAY)))
        composeRule.onNodeWithTag(NextTags.FILTER_TOGGLE).performClick()
        composeRule.onNodeWithTag(NextTags.due(DueWindow.TODAY)).assertIsSelected()
        composeRule.onNodeWithTag(NextTags.DUE_ANY).performScrollTo().assertIsNotSelected()
        composeRule.onNodeWithText("Next 7 days").performScrollTo().performClick()
        composeRule.onNodeWithTag(NextTags.due(DueWindow.OVERDUE)).performScrollTo().performClick()
        composeRule.onNodeWithTag(NextTags.DUE_ANY).performScrollTo().performClick()
        assertEquals(listOf("due WEEK", "due OVERDUE", "due any"), calls)
    }

    @Test
    fun `area and project are chosen from menus`() {
        show(loaded)
        composeRule.onNodeWithTag(NextTags.FILTER_TOGGLE).performClick()
        composeRule
            .onNodeWithTag(NextTags.AREA)
            .performScrollTo()
            .assertIsNotSelected()
            .performClick()
        composeRule.onNodeWithTag(NextTags.area(home.id)).performClick()
        composeRule.onNodeWithTag(NextTags.PROJECT).performScrollTo().performClick()
        composeRule.onNodeWithTag(NextTags.project(garden.id)).performClick()
        state = state.copy(filter = NextFilter(areaId = home.id, projectId = garden.id))
        composeRule.onNodeWithTag(NextTags.AREA).assertIsSelected()
        composeRule.onNodeWithContentDescription("Area: Home").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Project: Garden").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(NextTags.AREA).performScrollTo().performClick()
        composeRule.onNodeWithTag(NextTags.ANY_AREA).performClick()
        composeRule.onNodeWithTag(NextTags.PROJECT).performScrollTo().performClick()
        composeRule.onNodeWithTag(NextTags.ANY_PROJECT).performClick()
        assertEquals(listOf("area ${home.id}", "project ${garden.id}", "area null", "project null"), calls)
    }

    @Test
    fun `presets are applied and deleted, the active one is selected`() {
        val other = NextPreset("Other", NextFilter())
        show(loaded.copy(presets = PresetsUiState(listOf(soon, other), active = soon)))
        composeRule.onNodeWithTag(NextTags.preset(0)).assertIsSelected()
        composeRule.onNodeWithTag(NextTags.preset(1)).assertIsNotSelected().performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithContentDescription("Delete preset “Soon”").performClick()
        assertEquals(listOf("apply Other", "remove Soon"), calls)
        // The active preset is already saved: nothing new to save.
        composeRule.onNodeWithTag(NextTags.PRESET_SAVE).assertDoesNotExist()
    }

    @Test
    fun `the delete button has a full touch target next to the chip and leaves the chip's taps to applying`() {
        show(loaded.copy(presets = PresetsUiState(listOf(soon))))
        composeRule.onNodeWithTag(NextTags.presetDelete(0)).assertTouchWidthIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag(NextTags.preset(0)).performClick()
        composeRule.onNodeWithTag(NextTags.presetDelete(0)).performClick()
        assertEquals(listOf("apply Soon", "remove Soon"), calls)
    }

    @Test
    fun `presets are hidden without presets or a filter to save`() {
        show(loaded)
        composeRule.onNodeWithTag(NextTags.PRESETS).assertDoesNotExist()
        state = loaded.copy(filter = NextFilter(due = DueWindow.TODAY))
        composeRule.onNodeWithTag(NextTags.PRESET_SAVE).performClick()
        assertEquals(listOf("open"), calls)
    }

    @Test
    fun `the save dialog sends the name and shows why saving failed`() {
        show(loaded.copy(filter = NextFilter(due = DueWindow.TODAY), presets = PresetsUiState(editor = PresetEditor())))
        composeRule.onNodeWithTag(NextTags.PRESET_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(NextTags.PRESET_NAME).performTextInput("Today")
        composeRule.onNodeWithTag(NextTags.PRESET_CONFIRM).performClick()
        state = state.copy(presets = PresetsUiState(editor = PresetEditor("Today", PresetMessage.TOO_MANY)))
        composeRule.onNodeWithText("You can save up to 50 presets. Delete one first.").assertIsDisplayed()
        composeRule.onNodeWithTag(NextTags.PRESET_CANCEL).performClick()
        assertEquals(listOf("name Today", "save", "cancel"), calls)
    }

    @Test
    fun `a failed delete is shown above the list`() {
        show(loaded.copy(presets = PresetsUiState(listOf(soon), message = PresetMessage.CONFLICT)))
        composeRule
            .onNodeWithTag(NextTags.PRESET_MESSAGE)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Your saved filters were changed on another device. Try again.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "de")
    fun `the new strings are German on a German device`() {
        show(loaded.copy(filter = NextFilter(due = DueWindow.TODAY)))
        composeRule.onNodeWithText("Filter speichern…").assertIsDisplayed()
        composeRule.onNodeWithTag(NextTags.FILTER_TOGGLE).performClick()
        composeRule.onNodeWithText("Fällig").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Nächste 7 Tage").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Alle Bereiche").performScrollTo().assertIsDisplayed()
    }
}
