package ch.sonensei.cockpit.gtd.today

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.next.Completion
import ch.sonensei.cockpit.gtd.next.CompletionMessage
import ch.sonensei.cockpit.gtd.next.CompletionSnackbar
import ch.sonensei.cockpit.gtd.next.TaskListTags
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The screen alone, driven by a state the test controls. */
@RunWith(AndroidJUnit4::class)
class TodayScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 10, 20)
    private val overdue = GtdTask(id(1), "Pay the electricity bill", TaskList.NEXT, 0, 1, dueDate = today.minusDays(1))
    private val due = GtdTask(id(2), "Return library books", TaskList.NEXT, 1, 1, dueDate = today, dueTime = "17:00:00")
    private val scheduled = GtdTask(id(3), "Book the train", TaskList.NEXT, 2, 1, scheduledDate = today)
    private val loaded =
        TodayUiState(
            sections =
                listOf(
                    TodaySection(TodaySectionKind.OVERDUE, listOf(overdue)),
                    TodaySection(TodaySectionKind.DUE, listOf(due)),
                    TodaySection(TodaySectionKind.SCHEDULED, listOf(scheduled)),
                ),
            refreshedAt = Instant.parse("2026-10-20T09:00:00Z"),
            today = today,
        )

    private val calls = mutableListOf<String>()

    private fun show(state: TodayUiState) =
        composeRule.setContent {
            CockpitTheme {
                TodayContent(
                    state,
                    TodayCallbacks(
                        complete = { calls += "complete ${it.title}" },
                        undo = { calls += "undo ${it.completion.title}" },
                        dismiss = { calls += "dismiss" },
                    ),
                )
            }
        }

    @Test
    fun `shows the overdue, due and scheduled sections with their tasks`() {
        show(loaded)
        TodaySectionKind.entries.forEach { composeRule.onNodeWithTag(TodayTags.section(it)).assertIsDisplayed() }
        composeRule.onNodeWithText("Overdue").assertIsDisplayed()
        composeRule.onNodeWithText("Scheduled for today").assertIsDisplayed()
        composeRule.onNodeWithText("1 day overdue").assertIsDisplayed()
        composeRule.onNodeWithText("Due today 17:00").assertIsDisplayed()
        composeRule.onNodeWithText("Book the train").assertIsDisplayed()
    }

    @Test
    fun `the checkbox completes the task`() {
        show(loaded)
        composeRule.onNodeWithTag(TaskListTags.complete(due.id)).performClick()
        assertEquals(listOf("complete Return library books"), calls)
    }

    @Test
    fun `the undo snackbar reopens the task`() {
        show(loaded.copy(snackbar = CompletionSnackbar.Undo(Completion(overdue.id, overdue.title, 2))))
        composeRule.onNodeWithText("Completed Pay the electricity bill").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("undo Pay the electricity bill"), calls)
    }

    @Test
    fun `offline the banner shows and completing is disabled`() {
        show(loaded.copy(offline = true))
        composeRule.onNodeWithTag(TaskListTags.OFFLINE).assertIsDisplayed()
        composeRule.onNodeWithTag(TaskListTags.complete(overdue.id)).assertIsNotEnabled()
    }

    @Test
    fun `empty and loading states`() {
        show(TodayUiState(refreshedAt = Instant.parse("2026-10-20T09:00:00Z"), today = today))
        composeRule.onNodeWithTag(TodayTags.EMPTY).assertIsDisplayed()
    }

    @Test
    fun `shows loading before anything was loaded`() {
        show(TodayUiState(today = today))
        composeRule.onNodeWithTag(TodayTags.LOADING).assertIsDisplayed()
    }

    @Test
    fun `section headers count their tasks and rows show due texts and contexts`() {
        val errands = GtdContext(id(80), "@errands", 0, 1)
        val dueNoTime = due.copy(dueTime = null, contextIds = listOf(errands.id))
        val dueSecond = GtdTask(id(4), "Pick up the parcel", TaskList.NEXT, 3, 1, dueDate = today, timeEstimateMin = 15)
        show(
            loaded.copy(
                sections = listOf(TodaySection(TodaySectionKind.DUE, listOf(dueNoTime, dueSecond))),
                contexts = listOf(errands),
            ),
        )
        composeRule.onNode(hasText("2") and hasAnyAncestor(hasTestTag(TodayTags.section(TodaySectionKind.DUE)))).assertIsDisplayed()
        composeRule.onNodeWithTag(TodayTags.section(TodaySectionKind.OVERDUE)).assertDoesNotExist()
        // The section header and both rows say "Due today".
        composeRule.onAllNodesWithText("Due today").assertCountEquals(3)
        composeRule.onNodeWithText("@errands").assertIsDisplayed()
        composeRule.onNodeWithText("15 min").assertIsDisplayed()
    }

    @Test
    fun `a scheduled task due later shows its due date`() {
        val later = scheduled.copy(dueDate = LocalDate.of(2026, 10, 24))
        show(loaded.copy(sections = listOf(TodaySection(TodaySectionKind.SCHEDULED, listOf(later)))))
        composeRule.onNodeWithText("Due Oct 24, 2026").assertIsDisplayed()
    }

    @Test
    fun `offline with nothing cached shows the banner and the empty message`() {
        show(TodayUiState(offline = true, today = today))
        composeRule.onNodeWithText("Offline: nothing loaded yet.").assertIsDisplayed()
        composeRule.onNodeWithTag(TodayTags.EMPTY).assertIsDisplayed()
        composeRule.onNodeWithTag(TodayTags.LOADING).assertDoesNotExist()
    }

    @Test
    fun `a refused completion shows why`() {
        show(loaded.copy(snackbar = CompletionSnackbar.Failed(CompletionMessage.CONFLICT)))
        composeRule.onNodeWithText("This task was changed elsewhere. The list now shows its current state.").assertIsDisplayed()
    }

    @Test
    fun `outside a Hilt activity the Today tab shows the placeholder`() {
        composeRule.setContent { CockpitTheme { TodayScreen() } }
        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText("This part of your cockpit is not built yet.").assertIsDisplayed()
    }
}
