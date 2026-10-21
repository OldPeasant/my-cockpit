package ch.sonensei.cockpit.gtd.projects

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectRepository
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.lists.ActionOutcome
import ch.sonensei.cockpit.gtd.lists.Live
import ch.sonensei.cockpit.gtd.lists.TaskListsRepository
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
import java.time.ZoneOffset
import java.util.UUID

/**
 * The Projects route wired to a real view model: each control of the screen reaches the view model action it names
 * (open, back, show finished, make next step, complete, the prompt's choose/add/skip), and the changes reach the server.
 */
@RunWith(AndroidJUnit4::class)
class ProjectsRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val loadedAt = Instant.parse("2026-10-20T09:00:00Z")
    private val home = Area(id(90), "Home", 0)
    private val book = GtdTask(id(1), "Book the flights", TaskList.NEXT, 0, 1, projectId = id(10))
    private val hotel = GtdTask(id(2), "Compare hotels", TaskList.NEXT, 1, 1, projectId = id(10))
    private val holiday =
        GtdProject(id(10), "Plan holiday", ProjectStatus.ACTIVE, stalled = false, version = 2, areaId = home.id, nextTaskId = book.id)
    private val garden = GtdProject(id(11), "Redo the garden", ProjectStatus.ACTIVE, stalled = true, version = 2, areaId = home.id)
    private val move = GtdProject(id(13), "Move house", ProjectStatus.COMPLETED, stalled = false, version = 2)

    private val projects =
        mockk<ProjectRepository> {
            every { observe() } returns MutableStateFlow(Cached(listOf(holiday, garden, move), offline = false, refreshedAt = loadedAt))
        }
    private val contexts =
        mockk<ContextRepository> { every { observe() } returns MutableStateFlow(Cached(emptyList(), false, loadedAt)) }
    private val lists =
        mockk<TaskListsRepository> {
            every { observeProject(holiday.id, any()) } returns MutableStateFlow(Live(listOf(book, hotel)))
            every { observeProject(garden.id, any()) } returns MutableStateFlow(Live(emptyList()))
        }
    private val work =
        mockk<ProjectWorkRepository> {
            every { observeAreas(any()) } returns MutableStateFlow(Live(listOf(home)))
            coEvery { setNextStep(any(), any()) } returns ActionOutcome.DONE
            coEvery { complete(book) } returns ProjectCompletion.Completed(needsNextStep = true)
            coEvery { addNextStep(any(), any(), any()) } returns NextStepAdded(ActionOutcome.DONE, id(3))
        }

    private fun show() {
        val viewModel = ProjectsViewModel(projects, contexts, lists, work, Clock.fixed(loadedAt, ZoneOffset.UTC))
        composeRule.setContent { CockpitTheme { ProjectsRoute(viewModel = viewModel) } }
    }

    @Test
    fun `the list opens a project, shows finished projects, and back returns to the list`() {
        show()
        composeRule.onNodeWithTag(ProjectsTags.stalled(garden.id), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.project(move.id)).assertDoesNotExist()

        composeRule.onNodeWithTag(ProjectsTags.SHOW_FINISHED).performClick()
        composeRule.onNodeWithTag(ProjectsTags.project(move.id)).assertIsDisplayed()

        composeRule.onNodeWithTag(ProjectsTags.project(holiday.id)).performClick()
        composeRule.onNodeWithTag(ProjectsTags.DETAIL).assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.nextStepMarker(book.id), useUnmergedTree = true).assertIsDisplayed()

        composeRule.onNodeWithTag(ProjectsTags.BACK).performClick()
        composeRule.onNodeWithTag(ProjectsTags.DETAIL).assertDoesNotExist()
        composeRule.onNodeWithTag(ProjectsTags.LIST).assertIsDisplayed()
    }

    @Test
    fun `making a task the next step sends it with the project's version and confirms it`() {
        show()
        composeRule.onNodeWithTag(ProjectsTags.project(holiday.id)).performClick()

        composeRule.onNodeWithTag(ProjectsTags.makeNext(hotel.id)).performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 1) { work.setNextStep(holiday, hotel.id) }
        composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText("Next step: Compare hotels").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `completing the next step prompts for a new one, and choosing a task there sets it`() {
        show()
        composeRule.onNodeWithTag(ProjectsTags.project(holiday.id)).performClick()

        composeRule.onNodeWithTag(TaskListTags.complete(book.id)).performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 1) { work.complete(book) }
        composeRule.onNodeWithTag(ProjectsTags.PROMPT).assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.choice(hotel.id)).performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 1) { work.setNextStep(holiday, hotel.id) }
        composeRule.onNodeWithTag(ProjectsTags.PROMPT).assertDoesNotExist()
    }

    @Test
    fun `a stalled project's choose button opens the prompt, which adds a new next step or is skipped`() {
        show()
        composeRule.onNodeWithTag(ProjectsTags.project(garden.id)).performClick()

        composeRule.onNodeWithTag(ProjectsTags.CHOOSE).performClick()
        composeRule.onNodeWithTag(ProjectsTags.PROMPT_SKIP).performClick()
        composeRule.onNodeWithTag(ProjectsTags.PROMPT).assertDoesNotExist()
        coVerify(exactly = 0) { work.addNextStep(any(), any(), any()) }

        composeRule.onNodeWithTag(ProjectsTags.CHOOSE).performClick()
        composeRule.onNodeWithTag(ProjectsTags.PROMPT_NEW).performTextInput("  Buy seeds ")
        composeRule.onNodeWithTag(ProjectsTags.PROMPT_ADD).performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 1) { work.addNextStep(garden, "Buy seeds", null) }
        composeRule.onNodeWithTag(ProjectsTags.PROMPT).assertDoesNotExist()
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
