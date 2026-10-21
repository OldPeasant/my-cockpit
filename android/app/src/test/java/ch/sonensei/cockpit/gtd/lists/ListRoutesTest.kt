package ch.sonensei.cockpit.gtd.lists

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.next.Completion
import ch.sonensei.cockpit.gtd.next.CompletionResult
import ch.sonensei.cockpit.gtd.next.TaskCompletionRepository
import ch.sonensei.cockpit.gtd.next.TaskListTags
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The Waiting For and Someday routes wired to real view models: their actions reach the server and the list follows. */
@RunWith(AndroidJUnit4::class)
class ListRoutesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val now = Instant.parse("2026-10-20T09:00:00Z")
    private val today = LocalDate.of(2026, 10, 20)
    private val contract =
        GtdTask(id(1), "Contract", TaskList.WAITING, 0, 1, waitingForText = "Anna", waitingSince = today.minusDays(9))
    private val invoice = GtdTask(id(2), "Invoice", TaskList.WAITING, 1, 1, waitingSince = today.minusDays(2))
    private val italian = GtdTask(id(3), "Learn Italian", TaskList.SOMEDAY, 0, 1)

    private val repository =
        mockk<TaskListsRepository> {
            // Given newest first: the route must still show the longest waiting first.
            every { observe(TaskList.WAITING, any()) } returns MutableStateFlow(Live(listOf(invoice, contract)))
            every { observe(TaskList.SOMEDAY, any()) } returns MutableStateFlow(Live(listOf(italian)))
            coEvery { followUp(any(), any()) } returns ActionOutcome.DONE
            coEvery { activate(any()) } returns ActionOutcome.DONE
        }
    private val contexts = mockk<ContextRepository> { every { observe() } returns MutableStateFlow(Cached(emptyList(), false, now)) }
    private val completion =
        mockk<TaskCompletionRepository> {
            coEvery { complete(any()) } answers {
                val task = firstArg<GtdTask>()
                CompletionResult.Completed(Completion(task.id, task.title, task.version + 1))
            }
            coEvery { undo(any()) } returns CompletionResult.Reopened
        }
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `waiting shows the longest waiting first and follows up through the server`() {
        val viewModel = WaitingViewModel(repository, contexts, completion, clock)
        composeRule.setContent { CockpitTheme { WaitingRoute(viewModel = viewModel) } }

        val contractTop =
            composeRule
                .onNodeWithTag(TaskListTags.row(contract.id))
                .fetchSemanticsNode()
                .boundsInRoot.top
        val invoiceTop =
            composeRule
                .onNodeWithTag(TaskListTags.row(invoice.id))
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue("Contract (9 days) must be above Invoice (2 days)", contractTop < invoiceTop)

        composeRule.onNodeWithTag(LiveListTags.action(contract.id)).performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 1) { repository.followUp(contract, "Follow up with Anna: Contract") }
        composeRule.onNodeWithText("Added a next action to follow up on Contract").assertIsDisplayed()
    }

    @Test
    fun `waiting completes a task with undo through the completion repository`() {
        val viewModel = WaitingViewModel(repository, contexts, completion, clock)
        composeRule.setContent { CockpitTheme { WaitingRoute(viewModel = viewModel) } }

        composeRule.onNodeWithTag(TaskListTags.complete(invoice.id)).performClick()
        composeRule.onNodeWithTag(TaskListTags.row(invoice.id)).assertDoesNotExist()
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()

        coVerify { completion.undo(Completion(invoice.id, invoice.title, 2)) }
        composeRule.onNodeWithTag(TaskListTags.row(invoice.id)).assertIsDisplayed()
    }

    @Test
    fun `someday activates a task through the server`() {
        val viewModel = SomedayViewModel(repository, contexts, completion, clock)
        composeRule.setContent { CockpitTheme { SomedayRoute(viewModel = viewModel) } }

        composeRule.onNodeWithTag(LiveListTags.action(italian.id)).performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 1) { repository.activate(italian) }
    }
}
