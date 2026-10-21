package ch.sonensei.cockpit.gtd.next

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
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
import java.time.ZoneOffset
import java.util.UUID

/** The screen wired to a real view model: every filter chip and the completion reach it (what NextCallbacks.of wires). */
@RunWith(AndroidJUnit4::class)
class NextRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val loadedAt = Instant.parse("2026-10-20T09:00:00Z")
    private val home = GtdContext(id(80), "@home", 0, 1)
    private val call =
        GtdTask(id(1), "Call the dentist", TaskList.NEXT, 0, 1, energy = Energy.LOW, creativity = Creativity.ROUTINE, timeEstimateMin = 5)
    private val sketch =
        GtdTask(
            id(2),
            "Sketch the garden plan",
            TaskList.NEXT,
            1,
            1,
            contextIds = listOf(home.id),
            energy = Energy.HIGH,
            timeEstimateMin = 60,
        )
    private val tasks =
        mockk<TaskRepository> {
            every { observe(TaskView.NEXT) } returns MutableStateFlow(Cached(listOf(call, sketch), offline = false, refreshedAt = loadedAt))
        }
    private val contexts =
        mockk<ContextRepository> {
            every { observe() } returns
                MutableStateFlow(Cached(listOf(home), offline = false, refreshedAt = loadedAt))
        }
    private val completion =
        mockk<TaskCompletionRepository> {
            coEvery { complete(any()) } answers {
                val task = firstArg<GtdTask>()
                CompletionResult.Completed(Completion(task.id, task.title, task.version + 1))
            }
            coEvery { undo(any()) } returns CompletionResult.Reopened
        }

    private fun show() {
        val viewModel = NextViewModel(tasks, contexts, completion, Clock.fixed(loadedAt, ZoneOffset.UTC))
        composeRule.setContent { CockpitTheme { NextRoute(viewModel = viewModel) } }
    }

    /** Closes the filter panel (it fills the small test screen), checks the rows, and opens it again. */
    private fun assertShows(vararg titles: String) {
        composeRule.onNodeWithTag(NextTags.FILTER_TOGGLE).performClick()
        listOf(call, sketch).forEach {
            if (it.title in titles) {
                composeRule.onNodeWithText(it.title).assertIsDisplayed()
            } else {
                composeRule.onNodeWithText(it.title).assertDoesNotExist()
            }
        }
        composeRule.onNodeWithTag(NextTags.FILTER_TOGGLE).performClick()
    }

    @Test
    fun `each filter chip filters the list and clearing shows everything again`() {
        show()
        composeRule.onNodeWithText("2 next actions").assertIsDisplayed()
        composeRule.onNodeWithTag(NextTags.FILTER_TOGGLE).performClick()

        composeRule.onNodeWithTag(NextTags.context(home.id)).performClick()
        assertShows(sketch.title)
        composeRule.onNodeWithTag(NextTags.context(home.id)).performClick()

        composeRule.onNodeWithTag(NextTags.energy(Energy.LOW)).performClick()
        assertShows(call.title)
        composeRule.onNodeWithTag(NextTags.energy(Energy.LOW)).performClick()

        composeRule.onNodeWithTag(NextTags.creativity(Creativity.ROUTINE)).performClick()
        assertShows(call.title)
        composeRule.onNodeWithTag(NextTags.estimate(30)).performClick()
        assertShows(call.title)
        composeRule.onNodeWithText("1 of 2 next actions").assertIsDisplayed()

        composeRule.onNodeWithTag(NextTags.CLEAR).performClick()
        assertShows(call.title, sketch.title)
        composeRule.onNodeWithText("2 next actions").assertIsDisplayed()
    }

    @Test
    fun `completing hides the task, and undo in the snackbar brings it back`() {
        show()
        composeRule.onNodeWithTag(TaskListTags.complete(call.id)).performClick()
        composeRule.onNodeWithText("Completed Call the dentist").assertIsDisplayed()
        composeRule.onNodeWithTag(TaskListTags.row(call.id)).assertDoesNotExist()

        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()

        coVerify { completion.undo(Completion(call.id, call.title, 2)) }
        composeRule.onNodeWithTag(TaskListTags.row(call.id)).assertIsDisplayed()
    }
}
