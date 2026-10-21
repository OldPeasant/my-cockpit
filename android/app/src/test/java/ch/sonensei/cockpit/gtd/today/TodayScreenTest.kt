package ch.sonensei.cockpit.gtd.today

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.next.Completion
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
}
