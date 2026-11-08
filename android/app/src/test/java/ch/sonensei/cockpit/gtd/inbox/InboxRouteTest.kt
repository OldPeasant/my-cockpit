package ch.sonensei.cockpit.gtd.inbox

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectRepository
import ch.sonensei.cockpit.gtd.data.RefreshResult
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.gtd.next.Completion
import ch.sonensei.cockpit.gtd.next.CompletionResult
import ch.sonensei.cockpit.gtd.next.TaskCompletionRepository
import ch.sonensei.cockpit.gtd.next.TaskListTags
import ch.sonensei.cockpit.gtd.task.LocalTaskOpener
import ch.sonensei.cockpit.gtd.task.TaskOpener
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The screen wired to a real view model (repositories faked): taps reach the repository through [InboxCallbacks.of]. */
@RunWith(AndroidJUnit4::class)
class InboxRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val first = GtdTask(id(1), "Call the plumber", TaskList.INBOX, 0, 1)
    private val second = GtdTask(id(2), "Buy tiles", TaskList.INBOX, 1, 1)
    private val loadedAt = Instant.parse("2026-10-20T09:00:00Z")

    private val inboxFlow = MutableStateFlow(Cached(listOf(first, second), offline = false, refreshedAt = loadedAt))
    private val inbox = mockk<InboxRepository> { coEvery { process(any(), any(), any()) } returns ProcessResult.Processed }
    private val tasks = mockk<TaskRepository> { every { observe(TaskView.INBOX) } returns inboxFlow }
    private val projects = mockk<ProjectRepository> { every { observe() } returns MutableStateFlow(Cached(emptyList(), false, loadedAt)) }
    private val contexts = mockk<ContextRepository> { every { observe() } returns MutableStateFlow(Cached(emptyList(), false, loadedAt)) }
    private val completion = mockk<TaskCompletionRepository>()
    private var uptime = 0L

    private fun show(opener: TaskOpener? = null) {
        val viewModel =
            InboxViewModel(
                inbox,
                tasks,
                projects,
                contexts,
                completion,
                Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC),
            ) {
                uptime += 1_000L
                uptime
            }
        composeRule.setContent {
            CockpitTheme {
                CompositionLocalProvider(LocalTaskOpener provides opener) { InboxRoute(viewModel = viewModel) }
            }
        }
    }

    @Test
    fun `outside a Hilt activity the inbox tab shows the placeholder instead of failing`() {
        composeRule.setContent { CockpitTheme { InboxScreen() } }

        composeRule.onNodeWithText("This part of your cockpit is not built yet.").assertExists()
        composeRule.onNodeWithTag(InboxTags.PROCESS).assertDoesNotExist()
    }

    @Test
    fun `starting processing and tapping an action processes the shown item`() {
        show()

        composeRule.onNodeWithTag(InboxTags.PROCESS).performClick()
        composeRule.onNodeWithTag(InboxTags.action(InboxActionKind.DONE)).performScrollTo().performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 1) { inbox.process(first, Clarification.of(first), InboxAction.Done) }
    }

    @Test
    fun `a prompted action runs with the prompt's value`() {
        show()

        composeRule.onNodeWithTag(InboxTags.PROCESS).performClick()
        composeRule.onNodeWithTag(InboxTags.action(InboxActionKind.SCHEDULE)).performScrollTo().performClick()
        composeRule.onNodeWithTag(InboxTags.PROMPT_CONFIRM).performScrollTo().performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 1) { inbox.process(first, any(), InboxAction.Schedule(LocalDate.of(2026, 10, 21))) }
    }

    @Test
    fun `offline disables processing and the banner's retry reloads the inbox`() {
        inboxFlow.value = inboxFlow.value.copy(offline = true)
        coEvery { tasks.refresh(TaskView.INBOX) } answers {
            inboxFlow.value = inboxFlow.value.copy(offline = false)
            RefreshResult.Refreshed
        }
        show()

        composeRule.onNodeWithTag(InboxTags.PROCESS).assertIsNotEnabled()
        composeRule.onNodeWithTag(InboxTags.RETRY).performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 1) { tasks.refresh(TaskView.INBOX) }
        composeRule.onNodeWithTag(InboxTags.OFFLINE).assertDoesNotExist()
        composeRule.onNodeWithTag(InboxTags.PROCESS).performClick()
        composeRule.onNodeWithTag(InboxTags.action(InboxActionKind.TRASH)).performScrollTo().performClick()
        composeRule.waitForIdle()
        coVerify(exactly = 1) { inbox.process(first, any(), InboxAction.Trash) }
    }

    @Test
    fun `the checkbox of a row completes the task and the snackbar offers undo`() {
        val completion = Completion(first.id, first.title, 2)
        coEvery { this@InboxRouteTest.completion.complete(first) } returns CompletionResult.Completed(completion)
        show()

        composeRule.onNodeWithTag(TaskListTags.complete(first.id)).performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 1) { this@InboxRouteTest.completion.complete(first) }
        composeRule.onNodeWithTag(TaskListTags.row(first.id)).assertDoesNotExist()
        composeRule.onNodeWithText("Completed Call the plumber", substring = true).assertExists()
    }

    @Test
    fun `tapping a row opens the task detail with the list's copy`() {
        val opened = mutableListOf<Pair<UUID, GtdTask?>>()
        show(TaskOpener { id, snapshot, _ -> opened += id to snapshot })

        composeRule.onNodeWithText("Buy tiles").performClick()

        assertEquals(listOf(second.id to second), opened)
    }

    @Test
    fun `offline the row checkbox is disabled`() {
        inboxFlow.value = inboxFlow.value.copy(offline = true)
        show()

        composeRule.onNodeWithTag(TaskListTags.complete(first.id)).assertIsNotEnabled()
    }
}
