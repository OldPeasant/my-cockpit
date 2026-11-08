package ch.sonensei.cockpit.habits

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** The archived view, the row menu and the create/edit form, driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
class HabitsManageScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 10, 22)
    private val water =
        Habit(id(1), "Drink water", HabitSchedule.DAILY, null, emptyList(), 3, today, 1, true, false, 0, false, null, longestStreak = 5)
    private val run =
        Habit(id(2), "Run", HabitSchedule.TIMES_PER_WEEK, 3, emptyList(), 1, today, 0, true, false, 1, true, null, longestStreak = 3)
    private val piano =
        Habit(id(3), "Piano", HabitSchedule.DAILY, null, emptyList(), 1, today, 0, true, false, 0, false, null, archived = true)

    private val calls = mutableListOf<String>()
    private val drafts = mutableListOf<HabitDraft>()

    private val callbacks =
        HabitsCallbacks(
            toggle = { calls += "toggle $it" },
            showArchived = { calls += "showArchived $it" },
            create = { calls += "create" },
            edit = { calls += "edit $it" },
            toggleArchived = { calls += "toggleArchived $it" },
            updateDraft = { drafts += it },
            save = { calls += "save" },
            cancelEdit = { calls += "cancel" },
        )

    private fun show(state: HabitsUiState) =
        composeRule.setContent {
            CockpitTheme { HabitsContent(state, callbacks) }
        }

    private fun list(
        vararg habits: Habit,
        archived: Boolean = false,
    ) = HabitsUiState(archived = archived, habits = habits.toList(), loading = false, loaded = true)

    private fun editing(
        draft: HabitDraft,
        habitId: UUID? = null,
        tried: Boolean = false,
        saving: Boolean = false,
        error: HabitEditorError? = null,
    ) = list(water).copy(editor = HabitEditor(habitId, draft, saving = saving, tried = tried, error = error))

    // --- List, views and menu ---

    @Test
    fun `the view switch asks for the archived or the active habits`() {
        show(list(water))

        composeRule.onNodeWithTag(HabitsTags.VIEW_ARCHIVED).assertTextContains("Archived").performClick()
        composeRule.onNodeWithTag(HabitsTags.VIEW_ACTIVE).assertTextContains("Habits").performClick()

        assertEquals(listOf("showArchived true", "showArchived false"), calls)
    }

    @Test
    fun `the new habit button opens the form in the active view`() {
        show(list(water))

        composeRule
            .onNode(hasText("New habit") and hasAnyAncestor(hasTestTag(HabitsTags.NEW)), useUnmergedTree = true)
            .assertExists()
        composeRule.onNodeWithTag(HabitsTags.NEW).performClick()

        assertEquals(listOf("create"), calls)
    }

    @Test
    fun `the archived view has no new habit button and says when it is empty`() {
        show(list(archived = true))

        composeRule.onNodeWithTag(HabitsTags.NEW).assertDoesNotExist()
        composeRule.onNodeWithTag(HabitsTags.EMPTY).assertTextContains("No archived habits", substring = true)
    }

    @Test
    fun `the row menu edits a habit`() {
        show(list(water))

        composeRule.onNodeWithTag(HabitsTags.menu(water.id)).performClick()
        composeRule.onNodeWithTag(HabitsTags.MENU_EDIT).assertTextContains("Edit").performClick()

        assertEquals(listOf("edit ${water.id}"), calls)
        composeRule.onNodeWithTag(HabitsTags.MENU_EDIT).assertDoesNotExist()
    }

    @Test
    fun `the row menu archives an active habit without checking it`() {
        show(list(water))

        composeRule.onNodeWithTag(HabitsTags.menu(water.id)).performClick()
        composeRule.onNodeWithTag(HabitsTags.MENU_ARCHIVE).assertTextContains("Archive").performClick()

        assertEquals(listOf("toggleArchived ${water.id}"), calls)
    }

    @Test
    fun `an archived habit cannot be checked off and its menu unarchives it`() {
        show(list(piano, archived = true))

        composeRule.onNodeWithTag(HabitsTags.habit(piano.id)).assert(isToggleable().not()).performClick()
        composeRule.onNodeWithTag(HabitsTags.menu(piano.id)).performClick()
        composeRule.onNodeWithTag(HabitsTags.MENU_ARCHIVE).assertTextContains("Unarchive").performClick()

        assertEquals(listOf("toggleArchived ${piano.id}"), calls)
    }

    @Test
    fun `the menu of a habit with a change on its way cannot be opened`() {
        show(list(water).copy(checking = setOf(water.id)))

        composeRule.onNodeWithTag(HabitsTags.menu(water.id)).assertIsNotEnabled()
    }

    @Test
    fun `the best streak is shown in days or weeks`() {
        show(list(water, run))

        composeRule.onNodeWithTag(HabitsTags.habit(water.id)).assertTextContains("best 5 days", substring = true)
        composeRule.onNodeWithTag(HabitsTags.habit(run.id)).assertTextContains("1-week streak, best 3 weeks", substring = true)
    }

    @Test
    fun `a single best day uses the singular, no best streak shows nothing`() {
        show(list(water.copy(longestStreak = 1), run.copy(longestStreak = 0, currentStreak = 0)))

        composeRule.onNodeWithTag(HabitsTags.habit(water.id)).assertTextContains("best 1 day", substring = true)
        composeRule
            .onNodeWithTag(HabitsTags.habit(run.id))
            .assert(hasText("best", substring = true).not())
    }

    @Test
    fun `partial progress toward the target is announced as its state`() {
        show(list(water))

        composeRule
            .onNodeWithTag(HabitsTags.habit(water.id))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Today: 1 of 3"))
            .assertTextContains("today 1/3", substring = true)
    }

    @Test
    fun `an archived habit with a target above one shows times a day, not today's progress`() {
        show(list(piano.copy(targetCount = 3, todayCount = 1), archived = true))

        // An archived row is not a toggle, so its texts are not merged into the row node.
        composeRule
            .onNode(hasText("Daily, 3× a day") and hasAnyAncestor(hasTestTag(HabitsTags.habit(piano.id))), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `a conflict message can be dismissed`() {
        show(list(water).copy(message = HabitsMessage.CONFLICT))

        composeRule.onNodeWithText("This habit was changed elsewhere", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(HabitsTags.RETRY).assertDoesNotExist()
    }

    // --- Editor dialog ---

    @Test
    fun `a new habit form has its title and add button, an edit its own`() {
        show(editing(HabitDraft()))

        composeRule
            .onNode(hasText("New habit") and hasAnyAncestor(hasTestTag(HabitEditorTags.DIALOG)), useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(HabitEditorTags.SAVE).assertTextContains("Add habit")
    }

    @Test
    fun `an edit form has the edit title and save button`() {
        show(editing(HabitDraft.of(water), habitId = water.id))

        composeRule.onNodeWithText("Edit habit").assertIsDisplayed()
        composeRule.onNodeWithTag(HabitEditorTags.SAVE).assertTextContains("Save")
    }

    @Test
    fun `typing a name changes the draft`() {
        show(editing(HabitDraft()))

        composeRule.onNodeWithTag(HabitEditorTags.NAME).performTextInput("Read")

        assertEquals(HabitDraft(name = "Read"), drafts.last())
    }

    @Test
    fun `the schedule buttons change the schedule`() {
        val draft = HabitDraft(name = "Read")
        show(editing(draft))

        composeRule.onNodeWithTag(HabitEditorTags.schedule(HabitSchedule.WEEKDAYS)).performClick()
        composeRule.onNodeWithTag(HabitEditorTags.schedule(HabitSchedule.TIMES_PER_WEEK)).performClick()

        assertEquals(
            listOf(draft.copy(schedule = HabitSchedule.WEEKDAYS), draft.copy(schedule = HabitSchedule.TIMES_PER_WEEK)),
            drafts,
        )
    }

    @Test
    fun `a daily habit shows neither days a week nor weekday chips`() {
        show(editing(HabitDraft(name = "Read")))

        composeRule.onNodeWithTag(HabitEditorTags.TIMES_PER_WEEK).assertDoesNotExist()
        composeRule.onNodeWithTag(HabitEditorTags.weekday(DayOfWeek.MONDAY)).assertDoesNotExist()
    }

    @Test
    fun `weekday chips add and remove days`() {
        val draft = HabitDraft(name = "Piano", schedule = HabitSchedule.WEEKDAYS, weekdays = setOf(DayOfWeek.MONDAY))
        show(editing(draft))

        composeRule.onNodeWithTag(HabitEditorTags.weekday(DayOfWeek.TUESDAY)).performScrollTo().performClick()
        composeRule.onNodeWithTag(HabitEditorTags.weekday(DayOfWeek.MONDAY)).performScrollTo().performClick()

        assertEquals(
            listOf(draft.copy(weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)), draft.copy(weekdays = emptySet())),
            drafts,
        )
    }

    @Test
    fun `the days a week stepper stays within 1 to 7`() {
        val draft = HabitDraft(name = "Run", schedule = HabitSchedule.TIMES_PER_WEEK, timesPerWeek = 7)
        show(editing(draft))

        composeRule.onNodeWithTag(HabitEditorTags.TIMES_PER_WEEK).assertTextEquals("7")
        composeRule.onNodeWithTag(HabitEditorTags.more(HabitEditorTags.TIMES_PER_WEEK)).assertIsNotEnabled()
        composeRule.onNodeWithTag(HabitEditorTags.less(HabitEditorTags.TIMES_PER_WEEK)).performClick()

        assertEquals(listOf(draft.copy(timesPerWeek = 6)), drafts)
    }

    @Test
    fun `the times a day stepper does not go below 1`() {
        val draft = HabitDraft(name = "Water", targetCount = 1)
        show(editing(draft))

        composeRule.onNodeWithTag(HabitEditorTags.less(HabitEditorTags.TARGET)).assertIsNotEnabled()
        composeRule.onNodeWithTag(HabitEditorTags.more(HabitEditorTags.TARGET)).performScrollTo().performClick()

        assertEquals(listOf(draft.copy(targetCount = 2)), drafts)
    }

    @Test
    fun `the times a day stepper does not go above 1000`() {
        val draft = HabitDraft(name = "Water", targetCount = 1000)
        show(editing(draft))

        composeRule.onNodeWithTag(HabitEditorTags.more(HabitEditorTags.TARGET)).assertIsNotEnabled()
        composeRule.onNodeWithTag(HabitEditorTags.less(HabitEditorTags.TARGET)).performScrollTo().performClick()

        assertEquals(listOf(draft.copy(targetCount = 999)), drafts)
    }

    @Test
    fun `a reminder can be removed`() {
        val draft = HabitDraft(name = "Water", reminderTime = LocalTime.of(7, 30))
        show(editing(draft))

        composeRule.onNodeWithTag(HabitEditorTags.REMINDER_CLEAR).performScrollTo().performClick()

        assertEquals(listOf(draft.copy(reminderTime = null)), drafts)
    }

    @Test
    fun `without a reminder the field says none and offers no remove button`() {
        show(editing(HabitDraft(name = "Water")))

        composeRule.onNodeWithTag(HabitEditorTags.REMINDER).assertTextContains("None")
        composeRule.onNodeWithTag(HabitEditorTags.REMINDER_CLEAR).assertDoesNotExist()
    }

    @Test
    fun `a reminder is picked in the time picker, starting at 8 o'clock`() {
        val draft = HabitDraft(name = "Water")
        show(editing(draft))

        composeRule.onNodeWithTag(HabitEditorTags.REMINDER).performScrollTo().performClick()
        composeRule.onNodeWithText("OK").performClick()

        assertEquals(listOf(draft.copy(reminderTime = LocalTime.of(8, 0))), drafts)
    }

    @Test
    fun `cancelling the time picker keeps the reminder`() {
        show(editing(HabitDraft(name = "Water")))

        composeRule.onNodeWithTag(HabitEditorTags.REMINDER).performScrollTo().performClick()
        composeRule.onNodeWithText("OK").assertIsDisplayed()
        composeRule.onNode(hasText("Cancel") and hasTestTag(HabitEditorTags.CANCEL).not()).performClick()

        composeRule.onNodeWithText("OK").assertDoesNotExist()
        assertEquals(emptyList<HabitDraft>(), drafts)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `the problem shows only after a save was tried`() {
        show(editing(HabitDraft(), tried = false))

        composeRule.onNodeWithTag(HabitEditorTags.PROBLEM).assertDoesNotExist()
    }

    @Test
    fun `a tried form without a name says what is missing`() {
        show(editing(HabitDraft(), tried = true))

        composeRule.onNodeWithTag(HabitEditorTags.PROBLEM).assertTextEquals("Give the habit a name.")
    }

    @Test
    fun `a tried form without weekdays says to pick a day`() {
        show(editing(HabitDraft(name = "Piano", schedule = HabitSchedule.WEEKDAYS, weekdays = emptySet()), tried = true))

        composeRule.onNodeWithTag(HabitEditorTags.PROBLEM).assertTextEquals("Pick at least one day.")
    }

    @Test
    fun `a save conflict says the latest version is loaded`() {
        show(editing(HabitDraft(name = "Water"), habitId = water.id, error = HabitEditorError.CONFLICT))

        composeRule.onNodeWithTag(HabitEditorTags.ERROR).assertTextContains("changed elsewhere", substring = true)
    }

    @Test
    fun `save and cancel call back`() {
        show(editing(HabitDraft(name = "Water")))

        composeRule.onNodeWithTag(HabitEditorTags.SAVE).performClick()
        composeRule.onNodeWithTag(HabitEditorTags.CANCEL).performClick()

        assertEquals(listOf("save", "cancel"), calls)
    }

    @Test
    fun `while saving the form cannot be saved, cancelled or changed`() {
        show(editing(HabitDraft(name = "Water"), saving = true))

        composeRule.onNodeWithTag(HabitEditorTags.SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(HabitEditorTags.CANCEL).assertIsNotEnabled()
        composeRule.onNodeWithTag(HabitEditorTags.NAME).assertIsNotEnabled()
        composeRule.onNodeWithTag(HabitEditorTags.more(HabitEditorTags.TARGET)).assertIsNotEnabled()
        composeRule.onNodeWithTag(HabitEditorTags.schedule(HabitSchedule.WEEKDAYS)).assertIsNotEnabled()
    }

    @Test
    fun `a form that is not saving can be saved`() {
        show(editing(HabitDraft(name = "Water")))

        composeRule.onNodeWithTag(HabitEditorTags.SAVE).assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "de")
    fun `in German the view switch and the form are translated`() {
        show(list(water).copy(editor = HabitEditor(null, HabitDraft(), tried = true)))

        composeRule.onNodeWithTag(HabitsTags.VIEW_ARCHIVED).assertTextContains("Archiviert")
        composeRule.onNodeWithTag(HabitEditorTags.SAVE).assertTextContains("Hinzufügen")
        composeRule.onNodeWithTag(HabitEditorTags.PROBLEM).assertTextEquals("Gib der Gewohnheit einen Namen.")
    }

    @Test
    @Config(qualifiers = "de")
    fun `in German the best streak and progress state are translated`() {
        show(list(water))

        composeRule
            .onNodeWithTag(HabitsTags.habit(water.id))
            .assertTextContains("Bestwert 5 Tage", substring = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "heute 1 von 3"))
    }
}
