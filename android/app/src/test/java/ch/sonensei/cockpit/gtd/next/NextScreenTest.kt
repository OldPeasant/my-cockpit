package ch.sonensei.cockpit.gtd.next

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
    fun `a filter on an empty Next list says the list is empty, not that nothing matches`() {
        show(NextUiState(filter = NextFilter(energy = Energy.LOW), refreshedAt = Instant.parse("2026-10-20T09:00:00Z"), today = today))
        composeRule.onNodeWithText("No next actions. Process your inbox to add some.").assertIsDisplayed()
        composeRule.onNodeWithText("No next action matches these filters.").assertDoesNotExist()
        composeRule.onNodeWithText("0 of 0 next actions").assertIsDisplayed()
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

    @Test
    fun `a task due today without a time says due today, a later one shows its date`() {
        val dueToday = water.copy(dueDate = today)
        val dueLater = GtdTask(id(3), "Renew the passport", TaskList.NEXT, 2, 1, dueDate = LocalDate.of(2026, 10, 24))
        show(loaded.copy(tasks = listOf(dueToday, dueLater), total = 2))
        composeRule.onNodeWithText("Due today").assertIsDisplayed()
        composeRule.onNodeWithText("Due Oct 24, 2026").assertIsDisplayed()
    }

    @Test
    fun `a due time shows without its seconds and one late day is singular`() {
        val dueAt = water.copy(dueDate = today, dueTime = "08:05:00")
        show(loaded.copy(tasks = listOf(call.copy(dueDate = today.minusDays(1)), dueAt)))
        composeRule.onNodeWithText("1 day overdue").assertIsDisplayed()
        composeRule.onNodeWithText("Due today 08:05").assertIsDisplayed()
    }

    @Test
    fun `a task without contexts, estimate or due date shows only its title`() {
        show(loaded.copy(tasks = listOf(water), total = 1))
        composeRule.onNodeWithText("1 next action").assertIsDisplayed()
        composeRule.onNodeWithText("Water the plants").assertIsDisplayed()
        composeRule.onNodeWithText("Due", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("min", substring = true).assertDoesNotExist()
    }

    @Test
    fun `contexts that no longer exist are left out of the details`() {
        show(loaded.copy(contexts = emptyList()))
        composeRule.onNodeWithText("5 min").assertIsDisplayed()
        composeRule.onNodeWithText("@phone", substring = true).assertDoesNotExist()
    }

    @Test
    fun `shows loading before the first load and no empty message`() {
        show(NextUiState(today = today))
        composeRule.onNodeWithText("Loading…").assertIsDisplayed()
        composeRule.onNodeWithTag(NextTags.EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag(NextTags.LIST).assertDoesNotExist()
    }

    @Test
    fun `an empty Next list says so, without a clear button`() {
        show(NextUiState(refreshedAt = Instant.parse("2026-10-20T09:00:00Z"), today = today))
        composeRule.onNodeWithText("0 next actions").assertIsDisplayed()
        composeRule.onNodeWithText("No next actions. Process your inbox to add some.").assertIsDisplayed()
        composeRule.onNodeWithText("Clear filters").assertDoesNotExist()
    }

    @Test
    fun `the filters open and close, and without contexts the context group is left out`() {
        show(loaded.copy(contexts = emptyList()))
        composeRule.onNodeWithTag(NextTags.FILTERS).assertDoesNotExist()
        composeRule.onNodeWithText("Filter").performClick()
        composeRule.onNodeWithTag(NextTags.FILTERS).assertIsDisplayed()
        composeRule.onNodeWithText("Context").assertDoesNotExist()
        composeRule.onNodeWithText("Time available").assertIsDisplayed()
        composeRule.onNodeWithTag(NextTags.CLEAR).assertDoesNotExist()
        composeRule.onNodeWithTag(NextTags.FILTER_TOGGLE).performClick()
        composeRule.onNodeWithTag(NextTags.FILTERS).assertDoesNotExist()
    }

    @Test
    fun `offline before anything was loaded the banner says so`() {
        show(NextUiState(offline = true, today = today))
        composeRule.onNodeWithText("Offline: nothing loaded yet.").assertIsDisplayed()
        composeRule.onNodeWithText("No next actions. Process your inbox to add some.").assertIsDisplayed()
    }

    @Test
    fun `offline with a cached list the banner names when it was loaded`() {
        show(loaded.copy(offline = true))
        composeRule.onNodeWithText("Offline: showing the list from", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the offline and refused messages show, and a timed out snackbar is dismissed`() {
        show(loaded.copy(snackbar = CompletionSnackbar.Failed(CompletionMessage.OFFLINE)))
        composeRule.onNodeWithText("Not saved: the server is unreachable. Try again when you are online.").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(SNACKBAR_TIMEOUT_MS)
        composeRule.waitForIdle()
        assertEquals(listOf("dismiss"), calls)

        state = state.copy(snackbar = CompletionSnackbar.Failed(CompletionMessage.FAILED))
        composeRule.onNodeWithText("The server refused the change. The list shows the task as it is.").assertIsDisplayed()
    }

    @Test
    fun `every energy, creativity and estimate chip is labelled with the value it toggles`() {
        show(loaded.copy(contexts = emptyList()))
        composeRule.onNodeWithTag(NextTags.FILTER_TOGGLE).performClick()
        val labels =
            listOf("Low", "Medium", "High", "Routine", "Mixed", "Creative") + NextFilter.ESTIMATES.map { "≤ $it min" }
        // The panel can be taller than the test screen, so the chips are clicked through their semantics action.
        labels.forEach { composeRule.onNodeWithText(it).performSemanticsAction(SemanticsActions.OnClick) }
        assertEquals(
            listOf("energy LOW", "energy MEDIUM", "energy HIGH", "creativity ROUTINE", "creativity MIXED", "creativity CREATIVE") +
                NextFilter.ESTIMATES.map { "max $it" },
            calls,
        )
    }

    @Test
    fun `an undo offer that times out is dismissed, not undone`() {
        val offer = CompletionSnackbar.Undo(Completion(water.id, water.title, 2))
        show(loaded.copy(snackbar = offer))
        composeRule.onNodeWithText("Completed Water the plants").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(UNDO_TIMEOUT_MS)
        composeRule.waitForIdle()
        assertEquals(listOf("dismiss"), calls)
        composeRule.onNodeWithText("Completed Water the plants").assertDoesNotExist()
    }

    @Test
    fun `outside a Hilt activity the Next tab shows the placeholder`() {
        composeRule.setContent { CockpitTheme { NextScreen() } }
        composeRule.onNodeWithText("Next").assertIsDisplayed()
        composeRule.onNodeWithText("This part of your cockpit is not built yet.").assertIsDisplayed()
    }

    private companion object {
        /** Longer than a short snackbar is shown. */
        const val SNACKBAR_TIMEOUT_MS = 10_000L

        /** Longer than a long snackbar (the undo offer) is shown. */
        const val UNDO_TIMEOUT_MS = 15_000L
    }
}
