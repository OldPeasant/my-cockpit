package ch.sonensei.cockpit.gtd.inbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The screen alone, driven by a state the test controls. */
@RunWith(AndroidJUnit4::class)
class InboxScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val first = GtdTask(id(1), "Call the plumber", TaskList.INBOX, 0, 1, notesMd = "About the leak")
    private val second = GtdTask(id(2), "Buy tiles", TaskList.INBOX, 1, 1)
    private val today = LocalDate.of(2026, 10, 20)
    private val loaded =
        InboxUiState(
            tasks = listOf(first, second),
            refreshedAt = Instant.parse("2026-10-20T09:00:00Z"),
            contexts = listOf(GtdContext(id(80), "home", 0, 1)),
            today = today,
        )

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(loaded)

    private fun show(initial: InboxUiState) {
        state = initial
        val callbacks =
            InboxCallbacks(
                startProcessing = { calls += "start" },
                stopProcessing = { calls += "stop" },
                setEnergy = { calls += "energy $it" },
                setCreativity = { calls += "creativity $it" },
                setEstimate = { calls += "estimate $it" },
                toggleContext = { calls += "context $it" },
                choose = { calls += "choose $it" },
                updatePrompt = { change -> state = state.copy(prompt = state.prompt?.let(change)) },
                confirmPrompt = { calls += "confirm" },
                cancelPrompt = { calls += "cancel" },
                dismissMessage = { calls += "dismiss" },
            )
        composeRule.setContent { CockpitTheme { InboxContent(state, callbacks) } }
    }

    private fun action(kind: InboxActionKind) = composeRule.onNodeWithTag(InboxTags.action(kind)).performScrollTo()

    @Test
    fun `the list shows the items, their count and starts processing`() {
        show(loaded)

        composeRule.onNodeWithText("2 items to process").assertExists()
        composeRule.onNodeWithText("Call the plumber").assertExists()
        composeRule.onNodeWithText("Buy tiles").assertExists()
        composeRule.onNodeWithTag(InboxTags.OFFLINE).assertDoesNotExist()
        composeRule.onNodeWithTag(InboxTags.PROCESS).assertIsEnabled().performClick()

        assertEquals(listOf("start"), calls)
    }

    @Test
    fun `an empty inbox says so and cannot be processed`() {
        show(loaded.copy(tasks = emptyList()))

        composeRule.onNodeWithText("Inbox is empty. Everything you capture lands here.").assertExists()
        composeRule.onNodeWithTag(InboxTags.PROCESS).assertIsNotEnabled()
    }

    @Test
    fun `processing shows one item, how many are left, and every action`() {
        show(loaded.copy(processing = true))

        composeRule.onNodeWithTag(InboxTags.CURRENT, useUnmergedTree = true).onChildAt(0).assertTextContains("Call the plumber")
        composeRule.onNodeWithText("Buy tiles").assertDoesNotExist()
        composeRule.onNodeWithTag(InboxTags.LEFT).assertTextContains("2 items left")
        InboxActionKind.entries.forEach { kind ->
            action(kind).assertIsEnabled().performClick()
        }

        assertEquals(InboxActionKind.entries.map { "choose $it" }, calls)
    }

    @Test
    fun `attributes are set inline`() {
        show(loaded.copy(processing = true, clarification = Clarification(energy = Energy.LOW)))

        composeRule
            .onNodeWithText("Low")
            .assertIsSelected()
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("High").performScrollTo().performClick()
        composeRule.onNodeWithText("15 min").performScrollTo().performClick()
        composeRule.onNodeWithText("home").performScrollTo().performClick()

        assertEquals(listOf("energy null", "energy HIGH", "estimate 15", "context ${id(80)}"), calls)
    }

    @Test
    fun `offline shows the banner and disables processing and every action`() {
        show(loaded.copy(offline = true))
        composeRule.onNodeWithTag(InboxTags.OFFLINE).assertExists()
        composeRule.onNodeWithTag(InboxTags.PROCESS).assertIsNotEnabled()

        state = state.copy(processing = true)
        InboxActionKind.entries.forEach { action(it).assertIsNotEnabled() }
        composeRule.onNodeWithText("Low").assertIsNotEnabled()
        composeRule.onNodeWithText("home").assertIsNotEnabled()
    }

    @Test
    fun `offline also disables a prompt's confirmation`() {
        show(loaded.copy(processing = true, offline = true, prompt = InboxPrompt(InboxActionKind.SCHEDULE, today.plusDays(1))))

        composeRule.onNodeWithTag(InboxTags.PROMPT_CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun `the waiting prompt takes a name and confirms`() {
        show(loaded.copy(processing = true, prompt = InboxPrompt(InboxActionKind.WAITING, today.plusDays(1))))

        composeRule.onNodeWithTag(InboxTags.PROMPT_TEXT).performTextReplacement("Alex")
        composeRule.onNodeWithTag(InboxTags.PROMPT_CONFIRM).performScrollTo().performClick()

        assertEquals("Alex", state.prompt?.text)
        assertEquals(listOf("confirm"), calls)
    }

    @Test
    fun `the tickler prompt refuses today and offers quick dates`() {
        show(loaded.copy(processing = true, prompt = InboxPrompt(InboxActionKind.TICKLER, today)))

        composeRule.onNodeWithTag(InboxTags.PROMPT_CONFIRM).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("In a week").performScrollTo().performClick()

        assertEquals(today.plusWeeks(1), state.prompt?.date)
        composeRule.onNodeWithTag(InboxTags.PROMPT_CONFIRM).assertIsEnabled()
    }

    @Test
    fun `a failed action is explained and can be dismissed`() {
        show(loaded.copy(message = InboxMessage.CONFLICT))

        composeRule.onNodeWithTag(InboxTags.MESSAGE).assertExists()
        composeRule.onNodeWithText("Dismiss").performClick()

        assertEquals(listOf("dismiss"), calls)
    }

    @Test
    fun `before the first load it says it is loading, not that the inbox is empty`() {
        show(loaded.copy(tasks = emptyList(), refreshedAt = null))

        composeRule.onNodeWithText("Loading the Inbox…").assertExists()
        composeRule.onNodeWithText("Inbox is empty. Everything you capture lands here.").assertDoesNotExist()
        composeRule.onNodeWithTag(InboxTags.PROCESS).assertIsNotEnabled()
    }

    @Test
    fun `offline before any load says the inbox could not be loaded`() {
        show(loaded.copy(tasks = emptyList(), refreshedAt = null, offline = true))

        composeRule.onNodeWithText("Offline: the Inbox could not be loaded yet.").assertExists()
        composeRule.onNodeWithText("Loading the Inbox…").assertDoesNotExist()
    }

    @Test
    fun `offline and failed messages are explained`() {
        show(loaded.copy(message = InboxMessage.OFFLINE))
        composeRule.onNodeWithText("The server could not be reached. The item is back in the Inbox.").assertExists()

        state = state.copy(message = InboxMessage.FAILED)
        composeRule.onNodeWithText("The server refused this action. The item is back in the Inbox.").assertExists()
    }

    @Test
    fun `creativity and a chosen estimate toggle inline`() {
        show(loaded.copy(processing = true, clarification = Clarification(timeEstimateMin = 15)))

        composeRule.onNodeWithText("Creative").performScrollTo().performClick()
        composeRule
            .onNodeWithText("15 min")
            .assertIsSelected()
            .performScrollTo()
            .performClick()

        assertEquals(listOf("creativity CREATIVE", "estimate null"), calls)
    }

    @Test
    fun `the project prompt offers the projects and a new one that needs a title`() {
        val kitchen = GtdProject(id(51), "Kitchen", ProjectStatus.ACTIVE, stalled = false, version = 1)
        show(
            loaded.copy(
                processing = true,
                projects = listOf(kitchen),
                prompt = InboxPrompt(InboxActionKind.PROJECT, today.plusDays(1), projectId = kitchen.id),
            ),
        )
        composeRule.onNodeWithText("Kitchen").assertIsSelected()
        composeRule.onNodeWithTag(InboxTags.PROMPT_TEXT).assertDoesNotExist()
        composeRule.onNodeWithTag(InboxTags.PROMPT_CONFIRM).performScrollTo().assertIsEnabled()

        composeRule.onNodeWithText("New project").performScrollTo().performClick()
        assertNull(state.prompt?.projectId)
        composeRule.onNodeWithTag(InboxTags.PROMPT_CONFIRM).performScrollTo().assertIsNotEnabled()

        composeRule.onNodeWithTag(InboxTags.PROMPT_TEXT).performTextReplacement("Bathroom")
        composeRule
            .onNodeWithTag(InboxTags.PROMPT_CONFIRM)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        assertEquals("Bathroom", state.prompt?.text)
        assertEquals(listOf("confirm"), calls)
    }

    @Test
    fun `the schedule prompt picks a quick date and can be cancelled`() {
        show(loaded.copy(processing = true, prompt = InboxPrompt(InboxActionKind.SCHEDULE, today.plusDays(1))))

        composeRule.onNodeWithText("Tomorrow").assertIsSelected()
        composeRule.onNodeWithText("In a month").performScrollTo().performClick()
        assertEquals(today.plusMonths(1), state.prompt?.date)
        composeRule.onNodeWithText("Cancel").performScrollTo().performClick()

        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun `processing can be stopped from the top bar`() {
        show(loaded.copy(processing = true))

        composeRule.onNodeWithContentDescription("Stop processing").performClick()

        assertEquals(listOf("stop"), calls)
    }

    @Test
    fun `when everything is processed it says so`() {
        show(loaded.copy(tasks = emptyList(), processing = true, processed = 2))

        composeRule.onNodeWithText("Inbox processed. Nothing left to decide.").assertExists()
        composeRule.onNodeWithText("Back to the list").performClick()

        assertEquals(listOf("stop"), calls)
    }
}
