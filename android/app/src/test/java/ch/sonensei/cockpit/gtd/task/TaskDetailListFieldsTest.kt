package ch.sonensei.cockpit.gtd.task

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** The edit form's list chips, scheduled/tickler dates, waiting fields and repeat editor; checklist reordering. */
@RunWith(AndroidJUnit4::class)
class TaskDetailListFieldsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today: LocalDate = LocalDate.now()
    private val seeds = GtdTask(id(1), "Buy seeds", TaskList.NEXT, 0, version = 3)
    private val tomatoes = ChecklistItem(id(40), "Tomatoes", done = false, position = 0, version = 1)
    private val basil = ChecklistItem(id(41), "Basil", done = false, position = 1, version = 1)
    private val soil = ChecklistItem(id(42), "Soil", done = false, position = 2, version = 1)

    private lateinit var source: FakeTaskDetailSource
    private lateinit var vm: TaskDetailViewModel

    /** The screen on a real view model with a fake server, the form opened. */
    private fun editing(detail: TaskDetail) {
        source = FakeTaskDetailSource(detail)
        vm = TaskDetailViewModel(source, flowOf(emptyList()), flowOf(emptyList()), flowOf(null))
        vm.open(detail.task.id)
        composeRule.setContent {
            CockpitTheme {
                val state = vm.uiState.collectAsStateWithLifecycle().value
                TaskDetailContent(state, TaskDetailCallbacks.of(vm))
            }
        }
        composeRule.onNodeWithTag(TaskDetailTags.EDIT).performClick()
    }

    private fun draft(): TaskDraft = vm.uiState.value.draft!!

    private fun tag(tag: String) = composeRule.onNodeWithTag(tag).performScrollTo()

    private fun save() {
        // The form's buttons are in the bar above it, not in the scrolling form.
        composeRule.onNodeWithTag(TaskDetailTags.SAVE).performClick()
        composeRule.waitForIdle()
    }

    // --- list ---

    @Test
    fun `every list is offered with the task's own selected, choosing one saves a move`() {
        editing(TaskDetail(seeds))
        tag(TaskDetailListTags.list(TaskList.NEXT)).assertIsSelected()
        listOf(TaskList.INBOX, TaskList.WAITING, TaskList.SCHEDULED, TaskList.SOMEDAY).forEach {
            tag(TaskDetailListTags.list(it)).assertIsNotSelected()
        }
        tag(TaskDetailListTags.list(TaskList.INBOX)).performClick()
        tag(TaskDetailListTags.list(TaskList.INBOX)).assertIsSelected()
        tag(TaskDetailListTags.list(TaskList.NEXT)).assertIsNotSelected()
        save()
        assertEquals(TaskMove(TaskList.INBOX), source.relists.single().second)
        composeRule.onNodeWithTag(TaskDetailTags.FORM).assertDoesNotExist()
    }

    @Test
    fun `Waiting shows the person field and needs it, waiting-since only for a task already waiting`() {
        editing(TaskDetail(seeds))
        composeRule.onNodeWithTag(TaskDetailListTags.WAITING_FOR).assertDoesNotExist()
        tag(TaskDetailListTags.list(TaskList.WAITING)).performClick()
        tag(TaskDetailListTags.WAITING_FOR).assertIsDisplayed()
        composeRule.onNodeWithTag(TaskDetailListTags.WAITING_SINCE).assertDoesNotExist()
        save()
        // The message is the text field's supporting text, merged into the field.
        composeRule.onNodeWithTag(TaskDetailTags.error(TaskField.WAITING_FOR), useUnmergedTree = true).assertExists()
        assertTrue(source.relists.isEmpty())
        tag(TaskDetailListTags.WAITING_FOR).performTextReplacement("Anna Example")
        composeRule.onNodeWithTag(TaskDetailTags.error(TaskField.WAITING_FOR), useUnmergedTree = true).assertDoesNotExist()
        save()
        assertEquals(TaskMove(TaskList.WAITING, waitingForText = "Anna Example"), source.relists.single().second)
    }

    @Test
    fun `a waiting task shows its waiting-since day and can change the person`() {
        val since = LocalDate.of(2026, 10, 17)
        editing(TaskDetail(seeds.copy(list = TaskList.WAITING, waitingForText = "Anna Example", waitingSince = since)))
        tag(TaskDetailListTags.WAITING_SINCE).assertIsDisplayed()
        tag(TaskDetailListTags.WAITING_FOR).assert(hasText("Anna Example"))
        tag(TaskDetailListTags.WAITING_FOR).performTextReplacement("Ben Example")
        save()
        assertTrue(source.relists.isEmpty())
        assertEquals(JsonObject(mapOf("waitingForText" to JsonPrimitive("Ben Example"))), source.updates.single().second)
    }

    @Test
    fun `the waiting-since picker opens and can be cancelled`() {
        val since = LocalDate.of(2026, 10, 17)
        editing(TaskDetail(seeds.copy(list = TaskList.WAITING, waitingForText = "Anna Example", waitingSince = since)))
        tag(TaskDetailListTags.WAITING_SINCE).performClick()
        // The dialog's Cancel, not the form's.
        composeRule.onNode(hasText("Cancel") and !hasTestTag(TaskDetailTags.CANCEL)).performClick()
        assertEquals(since, draft().waitingSince)
    }

    @Test
    fun `confirming the waiting-since picker keeps the day it opened on`() {
        val since = LocalDate.of(2026, 10, 17)
        editing(TaskDetail(seeds.copy(list = TaskList.WAITING, waitingForText = "Anna Example", waitingSince = since)))
        tag(TaskDetailListTags.WAITING_SINCE).performClick()
        composeRule.onNodeWithText("OK").performClick()
        assertEquals(since, draft().waitingSince)
        composeRule.onNodeWithText("OK").assertDoesNotExist()
    }

    // --- scheduled date ---

    @Test
    fun `a scheduled quick chip puts the task on Scheduled, removing the date takes it to Next`() {
        editing(TaskDetail(seeds.copy(list = TaskList.SOMEDAY)))
        composeRule.onNodeWithTag(TaskDetailListTags.dateClear("scheduled")).assertDoesNotExist()
        tag(TaskDetailListTags.dateChip("scheduled", "week")).performClick()
        tag(TaskDetailListTags.dateChip("scheduled", "week")).assertIsSelected()
        tag(TaskDetailListTags.list(TaskList.SCHEDULED)).assertIsSelected()
        assertEquals(today.plusWeeks(1), draft().scheduledDate)

        tag(TaskDetailListTags.dateClear("scheduled")).performClick()
        tag(TaskDetailListTags.list(TaskList.NEXT)).assertIsSelected()
        assertNull(draft().scheduledDate)
        composeRule.onNodeWithTag(TaskDetailListTags.dateClear("scheduled")).assertDoesNotExist()
    }

    @Test
    fun `choosing another list drops the scheduled date`() {
        editing(TaskDetail(seeds.copy(list = TaskList.SCHEDULED, scheduledDate = today.plusDays(4))))
        tag(TaskDetailListTags.dateClear("scheduled")).assertIsDisplayed()
        tag(TaskDetailListTags.list(TaskList.SOMEDAY)).performClick()
        assertNull(draft().scheduledDate)
        composeRule.onNodeWithTag(TaskDetailListTags.dateClear("scheduled")).assertDoesNotExist()
    }

    @Test
    fun `Scheduled without a date shows the problem at the date, a chip clears it and saves the move`() {
        editing(TaskDetail(seeds))
        tag(TaskDetailListTags.list(TaskList.SCHEDULED)).performClick()
        save()
        tag(TaskDetailTags.error(TaskField.SCHEDULED)).assertIsDisplayed()
        tag(TaskDetailListTags.dateChip("scheduled", "tomorrow")).performClick()
        composeRule.onNodeWithTag(TaskDetailTags.error(TaskField.SCHEDULED)).assertDoesNotExist()
        save()
        assertEquals(TaskMove(TaskList.SCHEDULED, scheduledDate = today.plusDays(1)), source.relists.single().second)
    }

    @Test
    fun `the date picker opens on the field and confirming keeps the day`() {
        editing(TaskDetail(seeds.copy(list = TaskList.SCHEDULED, scheduledDate = today.plusDays(4))))
        tag(TaskDetailListTags.date("scheduled")).performClick()
        composeRule.onNodeWithText("OK").performClick()
        assertEquals(today.plusDays(4), draft().scheduledDate)
        assertEquals(TaskList.SCHEDULED, draft().list)
    }

    // --- tickler ---

    @Test
    fun `a tickler chip sets the day, the list stays, and clearing it removes it`() {
        editing(TaskDetail(seeds.copy(list = TaskList.SOMEDAY)))
        tag(TaskDetailListTags.dateChip("tickler", "month")).performClick()
        tag(TaskDetailListTags.dateChip("tickler", "month")).assertIsSelected()
        tag(TaskDetailListTags.dateChip("tickler", "tomorrow")).assertIsNotSelected()
        assertEquals(today.plusMonths(1), draft().ticklerDate)
        tag(TaskDetailListTags.list(TaskList.SOMEDAY)).assertIsSelected()

        tag(TaskDetailListTags.dateClear("tickler")).performClick()
        assertNull(draft().ticklerDate)
        tag(TaskDetailListTags.dateChip("tickler", "tomorrow")).performClick()
        save()
        assertEquals(TaskMove(TaskList.SOMEDAY, ticklerDate = today.plusDays(1)), source.relists.single().second)
    }

    @Test
    fun `a past tickler resent by a list change is shown as a problem at the tickler`() {
        editing(TaskDetail(seeds.copy(list = TaskList.SOMEDAY), ticklerDate = today.minusDays(1)))
        tag(TaskDetailListTags.list(TaskList.NEXT)).performClick()
        save()
        tag(TaskDetailTags.error(TaskField.TICKLER)).assert(hasText("Pick a day after today."))
        assertTrue(source.relists.isEmpty())
        tag(TaskDetailListTags.dateClear("tickler")).performClick()
        composeRule.onNodeWithTag(TaskDetailTags.error(TaskField.TICKLER)).assertDoesNotExist()
        save()
        assertEquals(TaskMove(TaskList.NEXT), source.relists.single().second)
    }

    // --- repeat ---

    @Test
    fun `a preset with an interval and a mode is saved as rule and mode`() {
        editing(TaskDetail(seeds))
        tag(TaskDetailListTags.repeat(RepeatPreset.NONE)).assertIsSelected()
        composeRule.onNodeWithTag(TaskDetailListTags.REPEAT_INTERVAL).assertDoesNotExist()
        composeRule.onNodeWithTag(TaskDetailListTags.repeatMode(RecurrenceMode.FIXED)).assertDoesNotExist()

        tag(TaskDetailListTags.repeat(RepeatPreset.WEEKDAYS)).performClick()
        tag(TaskDetailListTags.repeatMode(RecurrenceMode.FIXED)).assertIsSelected()
        tag(TaskDetailListTags.REPEAT_INTERVAL).performTextReplacement("2")
        tag(TaskDetailListTags.repeatMode(RecurrenceMode.AFTER_COMPLETION)).performClick()
        save()
        assertEquals(
            JsonObject(
                mapOf(
                    "recurrenceRule" to JsonPrimitive("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TU,WE,TH,FR"),
                    "recurrenceMode" to JsonPrimitive("AFTER_COMPLETION"),
                ),
            ),
            source.updates.single().second,
        )
        // The read-only view shows the new repeat in words.
        val row = hasTestTag(TaskDetailTags.property("repeat")) and hasAnyDescendant(hasText("Weekdays, every 2 weeks · After completion"))
        composeRule.onNode(row).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the interval takes only up to three digits, zero is refused at the field`() {
        editing(TaskDetail(seeds))
        tag(TaskDetailListTags.repeat(RepeatPreset.DAILY)).performClick()
        tag(TaskDetailListTags.REPEAT_INTERVAL).performTextReplacement("12a345")
        assertEquals("123", draft().repeat.interval)
        tag(TaskDetailListTags.REPEAT_INTERVAL).performTextReplacement("0")
        save()
        tag(TaskDetailTags.error(TaskField.RECURRENCE)).assert(hasText("Enter a number from 1 to 999."))
        assertTrue(source.updates.isEmpty())
        tag(TaskDetailListTags.REPEAT_INTERVAL).performTextReplacement("3")
        composeRule.onNodeWithTag(TaskDetailTags.error(TaskField.RECURRENCE)).assertDoesNotExist()
    }

    @Test
    fun `an existing custom rule is shown as text and can be edited`() {
        editing(TaskDetail(seeds, recurrenceRule = "FREQ=MONTHLY;BYMONTHDAY=15", recurrenceMode = RecurrenceMode.FIXED))
        tag(TaskDetailListTags.repeat(RepeatPreset.CUSTOM)).assertIsSelected()
        tag(TaskDetailListTags.REPEAT_RULE).assert(hasText("FREQ=MONTHLY;BYMONTHDAY=15"))
        composeRule.onNodeWithTag(TaskDetailListTags.REPEAT_INTERVAL).assertDoesNotExist()
        tag(TaskDetailListTags.REPEAT_RULE).performTextReplacement("BYMONTHDAY=1")
        save()
        tag(TaskDetailTags.error(TaskField.RECURRENCE)).assert(hasText("The rule needs a FREQ, e.g. FREQ=WEEKLY;BYDAY=MO."))
        tag(TaskDetailListTags.REPEAT_RULE).performTextReplacement("freq=monthly;bymonthday=1")
        save()
        assertEquals(JsonObject(mapOf("recurrenceRule" to JsonPrimitive("FREQ=MONTHLY;BYMONTHDAY=1"))), source.updates.single().second)
    }

    @Test
    fun `choosing Custom starts from the preset's rule, an empty custom rule is required`() {
        editing(TaskDetail(seeds, recurrenceRule = "FREQ=WEEKLY;INTERVAL=3", recurrenceMode = RecurrenceMode.FIXED))
        tag(TaskDetailListTags.repeat(RepeatPreset.WEEKLY)).assertIsSelected()
        tag(TaskDetailListTags.REPEAT_INTERVAL).assert(hasText("3"))
        tag(TaskDetailListTags.repeat(RepeatPreset.CUSTOM)).performClick()
        tag(TaskDetailListTags.REPEAT_RULE).assert(hasText("FREQ=WEEKLY;INTERVAL=3"))
        tag(TaskDetailListTags.REPEAT_RULE).performTextReplacement("")
        save()
        tag(TaskDetailTags.error(TaskField.RECURRENCE)).assertIsDisplayed()
        assertTrue(source.updates.isEmpty())
    }

    @Test
    fun `a missing scheduled date is not reported as a missing title`() {
        editing(TaskDetail(seeds))
        tag(TaskDetailListTags.list(TaskList.SCHEDULED)).performClick()
        save()
        tag(TaskDetailTags.error(TaskField.SCHEDULED)).assert(mentionsNoTitle)
    }

    @Test
    fun `a missing custom rule is not reported as a missing title`() {
        editing(TaskDetail(seeds))
        tag(TaskDetailListTags.repeat(RepeatPreset.CUSTOM)).performClick()
        save()
        tag(TaskDetailTags.error(TaskField.RECURRENCE)).assert(mentionsNoTitle)
    }

    @Test
    fun `a missing waiting-for person is not reported as a missing title`() {
        editing(TaskDetail(seeds))
        tag(TaskDetailListTags.list(TaskList.WAITING)).performClick()
        save()
        composeRule.onNodeWithTag(TaskDetailTags.error(TaskField.WAITING_FOR), useUnmergedTree = true).assert(mentionsNoTitle)
    }

    private val mentionsNoTitle = hasText("title", substring = true, ignoreCase = true).not()

    @Test
    fun `each missing field names what it needs, a blank title still asks for a title`() {
        editing(TaskDetail(seeds))
        composeRule.onNodeWithTag(TaskDetailTags.TITLE_INPUT).performScrollTo().performTextReplacement("  ")
        tag(TaskDetailListTags.list(TaskList.SCHEDULED)).performClick()
        tag(TaskDetailListTags.repeat(RepeatPreset.CUSTOM)).performClick()
        save()
        composeRule.onNodeWithTag(TaskDetailTags.error(TaskField.TITLE), useUnmergedTree = true).assert(hasText("Enter a title."))
        tag(TaskDetailTags.error(TaskField.SCHEDULED)).assert(hasText("Pick a date."))
        tag(TaskDetailTags.error(TaskField.RECURRENCE)).assert(hasText("Enter a rule, e.g. FREQ=WEEKLY;BYDAY=MO."))
        assertTrue(source.relists.isEmpty())
        assertTrue(source.updates.isEmpty())
    }

    @Test
    fun `a missing waiting-for person asks for the person`() {
        editing(TaskDetail(seeds))
        tag(TaskDetailListTags.list(TaskList.WAITING)).performClick()
        save()
        composeRule
            .onNodeWithTag(TaskDetailTags.error(TaskField.WAITING_FOR), useUnmergedTree = true)
            .assert(hasText("Enter who you are waiting for."))
        assertTrue(source.relists.isEmpty())
    }

    @Test
    fun `choosing no repeat removes the rule and hides the mode`() {
        editing(TaskDetail(seeds, recurrenceRule = "FREQ=DAILY", recurrenceMode = RecurrenceMode.AFTER_COMPLETION))
        tag(TaskDetailListTags.repeatMode(RecurrenceMode.AFTER_COMPLETION)).assertIsSelected()
        tag(TaskDetailListTags.repeat(RepeatPreset.NONE)).performClick()
        composeRule.onNodeWithTag(TaskDetailListTags.repeatMode(RecurrenceMode.FIXED)).assertDoesNotExist()
        save()
        assertEquals(
            2,
            source.updates
                .single()
                .second.size,
        )
        composeRule.onNodeWithTag(TaskDetailTags.property("repeat")).assertDoesNotExist()
    }

    @Test
    fun `a server problem at the rule is shown at the repeat field`() {
        editing(TaskDetail(seeds))
        source.updateResult = TaskResult.Invalid(mapOf("recurrenceRule" to "unsupported rule"), "Invalid task")
        tag(TaskDetailListTags.repeat(RepeatPreset.YEARLY)).performClick()
        save()
        tag(TaskDetailTags.error(TaskField.RECURRENCE)).assert(hasText("unsupported rule"))
    }

    // --- read-only repeat text ---

    private fun showRepeat(
        rule: String,
        mode: RecurrenceMode?,
    ) {
        composeRule.setContent {
            CockpitTheme {
                TaskDetailContent(
                    TaskDetailUiState(loading = false, detail = TaskDetail(seeds, recurrenceRule = rule, recurrenceMode = mode)),
                    TaskDetailCallbacks(),
                )
            }
        }
    }

    private fun repeatShows(text: String) {
        val row = hasTestTag(TaskDetailTags.property("repeat")) and hasAnyDescendant(hasText(text))
        composeRule.onNode(row).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the read-only view shows a preset with interval and mode in words`() {
        showRepeat("FREQ=MONTHLY;INTERVAL=3", RecurrenceMode.FIXED)
        repeatShows("every 3 months · On schedule")
    }

    @Test
    fun `the read-only view shows a custom rule as it is`() {
        showRepeat("FREQ=WEEKLY;BYDAY=SA", RecurrenceMode.AFTER_COMPLETION)
        repeatShows("FREQ=WEEKLY;BYDAY=SA · After completion")
    }

    @Test
    fun `the read-only view shows a daily rule without a mode by its name`() {
        showRepeat("FREQ=DAILY", null)
        repeatShows("Daily")
    }

    @Test
    fun `a blank rule shows no repeat`() {
        showRepeat("  ", RecurrenceMode.FIXED)
        composeRule.onNodeWithTag(TaskDetailTags.property("repeat")).assertDoesNotExist()
    }

    // --- checklist reorder ---

    private val moved = mutableListOf<Pair<String, Int>>()

    private fun showChecklist(readOnly: Boolean = false) {
        val state =
            TaskDetailUiState(loading = false, detail = TaskDetail(seeds, checklist = listOf(tomatoes, basil, soil)), offline = readOnly)
        composeRule.setContent {
            CockpitTheme { TaskDetailContent(state, TaskDetailCallbacks(moveItem = { item, to -> moved += item.text to to })) }
        }
    }

    private fun hasAction(label: String) =
        SemanticsMatcher("has custom action $label") { node ->
            node.config
                .getOrNull(SemanticsActions.CustomActions)
                .orEmpty()
                .any { it.label == label }
        }

    @Test
    fun `items offer Move up and Move down where they can go`() {
        showChecklist()
        tag(TaskDetailTags.item(tomatoes.id)).assert(hasAction("Move down")).assert(hasAction("Move up").not())
        tag(TaskDetailTags.item(basil.id)).assert(hasAction("Move up")).assert(hasAction("Move down"))
        tag(TaskDetailTags.item(soil.id)).assert(hasAction("Move up")).assert(hasAction("Move down").not())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the accessibility actions move an item by one`() {
        showChecklist()
        tag(TaskDetailTags.item(basil.id)).performCustomAccessibilityActionWithLabel("Move up")
        tag(TaskDetailTags.item(basil.id)).performCustomAccessibilityActionWithLabel("Move down")
        tag(TaskDetailTags.item(tomatoes.id)).performCustomAccessibilityActionWithLabel("Move down")
        assertEquals(listOf("Basil" to 0, "Basil" to 2, "Tomatoes" to 1), moved)
    }

    @Test
    fun `a read-only checklist offers no move actions`() {
        showChecklist(readOnly = true)
        tag(TaskDetailTags.item(basil.id)).assert(hasAction("Move up").not()).assert(hasAction("Move down").not())
    }

    @Test
    fun `long-pressing an item and dragging it down two rows moves it there`() {
        showChecklist()
        val row = tag(TaskDetailTags.item(tomatoes.id))
        val height =
            row
                .fetchSemanticsNode()
                .size.height
                .toFloat()
        row.performTouchInput {
            down(Offset(20f, centerY))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(10) { moveBy(Offset(0f, height * 2 / 10)) }
            up()
        }
        composeRule.waitForIdle()
        assertEquals(listOf("Tomatoes" to 2), moved)
    }

    @Test
    fun `dragging less than half a row does not move the item`() {
        showChecklist()
        val row = tag(TaskDetailTags.item(soil.id))
        val height =
            row
                .fetchSemanticsNode()
                .size.height
                .toFloat()
        row.performTouchInput {
            down(Offset(20f, centerY))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(4) { moveBy(Offset(0f, -height * 0.4f / 4)) }
            up()
        }
        composeRule.waitForIdle()
        assertTrue(moved.isEmpty())
    }

    @Test
    fun `dragging up past the top lands the item first`() {
        showChecklist()
        val row = tag(TaskDetailTags.item(soil.id))
        val height =
            row
                .fetchSemanticsNode()
                .size.height
                .toFloat()
        row.performTouchInput {
            down(Offset(20f, centerY))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(10) { moveBy(Offset(0f, -height * 5 / 10)) }
            up()
        }
        composeRule.waitForIdle()
        assertEquals(listOf("Soil" to 0), moved)
    }
}
