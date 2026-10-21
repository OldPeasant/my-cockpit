package ch.sonensei.cockpit.gtd.next

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdContext
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

/** The screen alone, driven by a state the test controls. */
@RunWith(AndroidJUnit4::class)
class NextScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 10, 20)
    private val phone = GtdContext(id(81), "@phone", 0, 1)
    private val call =
        GtdTask(
            id(1),
            "Call the dentist",
            TaskList.NEXT,
            0,
            1,
            contextIds = listOf(phone.id),
            timeEstimateMin = 5,
            dueDate = today.minusDays(3),
        )
    private val water = GtdTask(id(2), "Water the plants", TaskList.NEXT, 1, 1)
    private val loaded =
        NextUiState(
            tasks = listOf(call, water),
            total = 2,
            contexts = listOf(phone),
            refreshedAt = Instant.parse("2026-10-20T09:00:00Z"),
            today = today,
        )

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(loaded)

    private fun show(initial: NextUiState) {
        state = initial
        val callbacks =
            NextCallbacks(
                toggleContext = { calls += "context $it" },
                toggleEnergy = { calls += "energy $it" },
                toggleCreativity = { calls += "creativity $it" },
                toggleMaxMinutes = { calls += "max $it" },
                clearFilter = { calls += "clear" },
                complete = { calls += "complete ${it.title}" },
                undo = { calls += "undo ${it.completion.title}" },
                dismiss = { calls += "dismiss" },
            )
        composeRule.setContent { CockpitTheme { NextContent(state, callbacks) } }
    }

    @Test
    fun `lists the tasks with context, estimate and how late they are`() {
        show(loaded)
        composeRule.onNodeWithText("2 next actions").assertIsDisplayed()
        composeRule.onNodeWithText("Call the dentist").assertIsDisplayed()
        composeRule.onNodeWithText("3 days overdue").assertIsDisplayed()
        composeRule.onNodeWithText("@phone   5 min").assertIsDisplayed()
    }

    @Test
    fun `each filter chip calls its toggle`() {
        show(loaded)
        composeRule.onNodeWithTag(NextTags.FILTER_TOGGLE).performClick()
        composeRule.onNodeWithTag(NextTags.context(phone.id)).performClick()
        composeRule.onNodeWithTag(NextTags.energy(Energy.LOW)).performClick()
        composeRule.onNodeWithTag(NextTags.creativity(Creativity.MIXED)).performClick()
        composeRule.onNodeWithTag(NextTags.estimate(15)).performClick()
        assertEquals(listOf("context ${phone.id}", "energy LOW", "creativity MIXED", "max 15"), calls)
    }

    @Test
    fun `shows the active filter and a way to clear it`() {
        show(loaded.copy(tasks = listOf(call), filter = NextFilter(contextId = phone.id, maxMinutes = 15)))
        composeRule.onNodeWithText("1 of 2 next actions").assertIsDisplayed()
        composeRule.onNodeWithText("Filter (2)").performClick()
        composeRule.onNodeWithTag(NextTags.context(phone.id)).assertIsSelected()
        composeRule.onNodeWithTag(NextTags.estimate(15)).assertIsSelected()
        composeRule.onNodeWithTag(NextTags.CLEAR).performClick()
        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun `no match under a filter offers to clear it`() {
        show(loaded.copy(tasks = emptyList(), filter = NextFilter(energy = Energy.HIGH)))
        composeRule.onNodeWithText("No next action matches these filters.").assertIsDisplayed()
        composeRule.onNodeWithText("Clear filters").performClick()
        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun `the checkbox completes the task`() {
        show(loaded)
        composeRule.onNodeWithTag(TaskListTags.complete(call.id)).assertIsEnabled().performClick()
        assertEquals(listOf("complete Call the dentist"), calls)
    }

    @Test
    fun `offline the banner shows and completing is disabled`() {
        show(loaded.copy(offline = true))
        composeRule.onNodeWithTag(TaskListTags.OFFLINE).assertIsDisplayed()
        composeRule.onNodeWithTag(TaskListTags.complete(call.id)).assertIsNotEnabled()
    }

    @Test
    fun `the undo snackbar reopens the task`() {
        show(loaded)
        state = state.copy(snackbar = CompletionSnackbar.Undo(Completion(water.id, water.title, 2)))
        composeRule.onNodeWithText("Completed Water the plants").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("undo Water the plants"), calls)
    }

    @Test
    fun `a failure shows in the snackbar`() {
        show(loaded.copy(snackbar = CompletionSnackbar.Failed(CompletionMessage.CONFLICT)))
        composeRule.onNodeWithText("This task was changed elsewhere. The list now shows its current state.").assertIsDisplayed()
    }
}
