package ch.sonensei.cockpit.gtd.lists

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.next.TaskListTags
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** The Waiting For and Someday screens alone, driven by a state the test controls. */
@RunWith(AndroidJUnit4::class)
class ListScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 10, 20)
    private val contract =
        GtdTask(id(1), "Contract", TaskList.WAITING, 0, 1, waitingForText = "Anna", waitingSince = today.minusDays(9))
    private val invoice = GtdTask(id(2), "Invoice", TaskList.WAITING, 1, 1, waitingSince = today)
    private val italian = GtdTask(id(3), "Learn Italian", TaskList.SOMEDAY, 0, 1)
    private val loaded = TaskListUiState(tasks = listOf(contract, invoice), loading = false, loaded = true, today = today)

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(loaded)

    private val callbacks =
        LiveListCallbacks(
            retry = { calls += "retry" },
            complete = { calls += "complete ${it.title}" },
            dismissAction = { calls += "dismiss ${it.title}" },
        )

    private fun showWaiting(initial: TaskListUiState) {
        state = initial
        composeRule.setContent {
            CockpitTheme { WaitingContent(state, callbacks, { task, title -> calls += "follow ${task.title} as $title" }) }
        }
    }

    private fun showSomeday(initial: TaskListUiState) {
        state = initial
        composeRule.setContent { CockpitTheme { SomedayContent(state, callbacks, { calls += "activate ${it.title}" }) } }
    }

    @Test
    fun `waiting shows who and how long, and follows up with a titled next action`() {
        showWaiting(loaded)
        composeRule.onNodeWithText("Anna, for 9 days").assertIsDisplayed()
        composeRule.onNodeWithText("since today").assertIsDisplayed()
        composeRule.onNodeWithTag(LiveListTags.action(contract.id)).performClick()
        composeRule.onNodeWithTag(LiveListTags.action(invoice.id)).performClick()
        composeRule.onNodeWithTag(TaskListTags.complete(invoice.id)).performClick()
        assertEquals(
            listOf("follow Contract as Follow up with Anna: Contract", "follow Invoice as Follow up: Invoice", "complete Invoice"),
            calls,
        )
    }

    @Test
    fun `someday activates a task`() {
        showSomeday(loaded.copy(tasks = listOf(italian)))
        composeRule.onNodeWithTag(LiveListTags.action(italian.id)).performClick()
        assertEquals(listOf("activate Learn Italian"), calls)
    }

    @Test
    fun `loading, then empty`() {
        showSomeday(TaskListUiState(today = today))
        composeRule.onNodeWithTag(LiveListTags.LOADING).assertIsDisplayed()
        state = TaskListUiState(loading = false, loaded = true, today = today)
        composeRule.onNodeWithText("No someday ideas. Park things here that you may do later.").assertIsDisplayed()
    }

    @Test
    fun `a first load that failed offers a retry instead of loading forever`() {
        showWaiting(TaskListUiState(loading = false, error = LoadError.OFFLINE, today = today))
        composeRule.onNodeWithTag(LiveListTags.ERROR).assertIsDisplayed()
        composeRule.onNodeWithTag(LiveListTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `a failed reload keeps the list read-only with a banner`() {
        showWaiting(loaded.copy(error = LoadError.OFFLINE))
        composeRule.onNodeWithTag(LiveListTags.STALE).assertIsDisplayed()
        composeRule.onNodeWithTag(TaskListTags.complete(contract.id)).assertIsNotEnabled()
        composeRule.onNodeWithTag(LiveListTags.action(contract.id)).assertIsNotEnabled()
    }

    @Test
    fun `a running action disables its row`() {
        showSomeday(loaded.copy(tasks = listOf(italian), busy = setOf(italian.id)))
        composeRule.onNodeWithTag(LiveListTags.action(italian.id)).assertIsNotEnabled()
        composeRule.onNodeWithTag(TaskListTags.complete(italian.id)).assertIsNotEnabled()
    }

    @Test
    fun `a follow up that was added is confirmed in a snackbar, then dismissed`() {
        showWaiting(loaded.copy(action = ActionSnackbar(ActionSnackbar.Kind.FOLLOW_UP, "Contract", ActionOutcome.DONE)))
        val text = "Added a next action to follow up on Contract"
        composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitUntil(TIMEOUT) { "dismiss Contract" in calls }
    }

    @Test
    fun `an activation while offline says it was not saved`() {
        showSomeday(
            loaded.copy(
                tasks = listOf(italian),
                action = ActionSnackbar(ActionSnackbar.Kind.ACTIVATED, "Learn Italian", ActionOutcome.OFFLINE),
            ),
        )
        val text = "Not saved: the server is unreachable. Try again when you are online."
        composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `waiting without a date shows only who`() {
        showWaiting(loaded.copy(tasks = listOf(GtdTask(id(5), "Parcel", TaskList.WAITING, 0, 1, waitingForText = "Post office"))))
        composeRule.onNodeWithText("Post office", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `waiting with a blank who follows up without a name, and a date in the future counts as today`() {
        val ticket = GtdTask(id(6), "Ticket", TaskList.WAITING, 0, 1, waitingForText = "   ", waitingSince = today.plusDays(2))
        showWaiting(loaded.copy(tasks = listOf(ticket)))
        composeRule.onNodeWithText("since today", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(LiveListTags.action(ticket.id)).performClick()
        assertEquals(listOf("follow Ticket as Follow up: Ticket"), calls)
    }

    @Test
    fun `waiting without who and date still lists the task and can follow up`() {
        val parcel = GtdTask(id(7), "Parcel", TaskList.WAITING, 0, 1)
        showWaiting(loaded.copy(tasks = listOf(parcel)))
        composeRule.onNodeWithText("Parcel", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(LiveListTags.action(parcel.id)).performClick()
        assertEquals(listOf("follow Parcel as Follow up: Parcel"), calls)
    }

    @Test
    fun `someday on a failed reload is read-only with a banner and a retry`() {
        showSomeday(loaded.copy(tasks = listOf(italian), error = LoadError.FAILED))
        composeRule.onNodeWithTag(LiveListTags.STALE).assertIsDisplayed()
        composeRule.onNodeWithTag(LiveListTags.action(italian.id)).assertIsNotEnabled()
        composeRule.onNodeWithTag(TaskListTags.complete(italian.id)).assertIsNotEnabled()
        composeRule.onNodeWithTag(LiveListTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `an activation that worked is confirmed in a snackbar`() {
        showSomeday(
            loaded.copy(
                tasks = listOf(italian),
                action = ActionSnackbar(ActionSnackbar.Kind.ACTIVATED, "Learn Italian", ActionOutcome.DONE),
            ),
        )
        val text = "Moved Learn Italian to Next"
        composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitUntil(TIMEOUT) { "dismiss Learn Italian" in calls }
    }

    @Test
    fun `a follow up the server refused says so`() {
        showWaiting(loaded.copy(action = ActionSnackbar(ActionSnackbar.Kind.FOLLOW_UP, "Contract", ActionOutcome.FAILED)))
        val text = "The server refused the change. The list shows the task as it is."
        composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
