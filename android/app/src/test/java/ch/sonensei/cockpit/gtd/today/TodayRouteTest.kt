package ch.sonensei.cockpit.gtd.today

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
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The Today screen wired to a real view model: completing and undoing reach the server and the sections follow. */
@RunWith(AndroidJUnit4::class)
class TodayRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val loadedAt = Instant.parse("2026-10-20T09:00:00Z")
    private val today = LocalDate.of(2026, 10, 20)
    private val overdue = GtdTask(id(1), "Pay the electricity bill", TaskList.NEXT, 0, 1, dueDate = today.minusDays(2))
    private val tasks =
        mockk<TaskRepository> {
            every { observe(TaskView.TODAY) } returns MutableStateFlow(Cached(listOf(overdue), offline = false, refreshedAt = loadedAt))
            every { observe(TaskView.NEXT) } returns MutableStateFlow(Cached(emptyList(), offline = false, refreshedAt = loadedAt))
        }
    private val contexts =
        mockk<ContextRepository> {
            every { observe() } returns
                MutableStateFlow(Cached(emptyList(), offline = false, refreshedAt = loadedAt))
        }
    private val completion =
        mockk<TaskCompletionRepository> {
            coEvery { complete(any()) } answers {
                val task = firstArg<GtdTask>()
                CompletionResult.Completed(Completion(task.id, task.title, task.version + 1))
            }
            coEvery { undo(any()) } returns CompletionResult.Reopened
        }

    @Test
    fun `completing empties the section, and undo brings the task back`() {
        val viewModel = TodayViewModel(tasks, contexts, completion, Clock.fixed(loadedAt, ZoneOffset.UTC))
        composeRule.setContent { CockpitTheme { TodayRoute(viewModel = viewModel) } }
        composeRule.onNodeWithTag(TodayTags.section(TodaySectionKind.OVERDUE)).assertIsDisplayed()

        composeRule.onNodeWithTag(TaskListTags.complete(overdue.id)).performClick()
        composeRule.onNodeWithText("Completed Pay the electricity bill").assertIsDisplayed()
        composeRule.onNodeWithTag(TodayTags.EMPTY).assertIsDisplayed()

        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()

        coVerify { completion.undo(Completion(overdue.id, overdue.title, 2)) }
        composeRule.onNodeWithTag(TaskListTags.row(overdue.id)).assertIsDisplayed()
    }
}
