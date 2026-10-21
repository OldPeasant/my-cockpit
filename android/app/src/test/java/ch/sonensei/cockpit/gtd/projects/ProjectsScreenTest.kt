package ch.sonensei.cockpit.gtd.projects

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.lists.LiveListTags
import ch.sonensei.cockpit.gtd.lists.LoadError
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
class ProjectsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val home = Area(id(90), "Home", 0)
    private val book = GtdTask(id(1), "Book the flights", TaskList.NEXT, 0, 1, projectId = id(10))
    private val hotel = GtdTask(id(2), "Compare hotels", TaskList.NEXT, 1, 1, projectId = id(10))
    private val visa = GtdTask(id(3), "Visa from the embassy", TaskList.WAITING, 2, 1, projectId = id(10))
    private val holiday =
        GtdProject(id(10), "Plan holiday", ProjectStatus.ACTIVE, stalled = false, version = 2, areaId = home.id, nextTaskId = book.id)
    private val garden = GtdProject(id(11), "Redo the garden", ProjectStatus.ACTIVE, stalled = true, version = 2, areaId = home.id)
    private val taxes = GtdProject(id(12), "File taxes", ProjectStatus.ON_HOLD, stalled = false, version = 2)
    private val list =
        ProjectsUiState(
            groups = groupByArea(listOf(holiday, garden, taxes), listOf(home)),
            refreshedAt = Instant.parse("2026-10-20T09:00:00Z"),
            today = LocalDate.of(2026, 10, 20),
        )

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(list)

    private fun show(initial: ProjectsUiState) {
        state = initial
        val callbacks =
            ProjectsCallbacks(
                open = { calls += "open ${it.title}" },
                close = { calls += "close" },
                toggleFinished = { calls += "finished" },
                retry = { calls += "retry" },
                complete = { calls += "complete ${it.title}" },
                makeNextStep = { calls += "next ${it.title}" },
                addNextStep = { calls += "add $it" },
                choose = { calls += "choose" },
                skip = { calls += "skip" },
            )
        composeRule.setContent { CockpitTheme { ProjectsContent(state, callbacks) } }
    }

    private fun detail(
        project: GtdProject,
        tasks: List<GtdTask>,
        prompt: NextStepPrompt? = null,
    ) = list.copy(detail = ProjectDetailState(project, home, tasks, loading = false, loaded = true, prompt = prompt))

    @Test
    fun `groups projects by area and highlights the stalled one`() {
        show(list)
        composeRule.onNodeWithText("1 project has no next step").assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.area(home.id)).assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.area(null)).assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.stalled(garden.id), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.stalled(holiday.id), useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("On hold").assertIsDisplayed()
    }

    @Test
    fun `tapping a project opens it`() {
        show(list)
        composeRule.onNodeWithTag(ProjectsTags.project(holiday.id)).performClick()
        assertEquals(listOf("open Plan holiday"), calls)
    }

    @Test
    fun `the detail marks the next step and offers to make another task the next step`() {
        show(detail(holiday, listOf(book, hotel, visa)))
        composeRule.onNodeWithText("Next step: Book the flights").assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.nextStepMarker(book.id), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.makeNext(book.id)).assertDoesNotExist()
        composeRule.onNodeWithText("Waiting").assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.makeNext(hotel.id)).performClick()
        composeRule.onNodeWithTag(TaskListTags.complete(book.id)).performClick()
        composeRule.onNodeWithTag(ProjectsTags.BACK).performClick()
        assertEquals(listOf("next Compare hotels", "complete Book the flights", "close"), calls)
    }

    @Test
    fun `a stalled project's detail shows the badge and a way to choose the next step`() {
        show(detail(garden, listOf(hotel)))
        composeRule.onNodeWithTag(ProjectsTags.stalled(garden.id), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.CHOOSE).performClick()
        assertEquals(listOf("choose"), calls)
    }

    @Test
    fun `the next-step prompt offers the open tasks, a new one, and skip`() {
        show(detail(garden, listOf(hotel, visa), NextStepPrompt(done = "Book the flights")))
        composeRule.onNodeWithTag(ProjectsTags.PROMPT).assertIsDisplayed()
        composeRule.onNodeWithText("Completed Book the flights. What is the next step?").assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.choice(visa.id)).performClick()
        composeRule.onNodeWithTag(ProjectsTags.PROMPT_ADD).assertIsNotEnabled()
        composeRule.onNodeWithTag(ProjectsTags.PROMPT_NEW).performTextInput("Pack the bags")
        composeRule.onNodeWithTag(ProjectsTags.PROMPT_ADD).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(ProjectsTags.PROMPT_SKIP).performClick()
        assertEquals(listOf("next Visa from the embassy", "add Pack the bags", "skip"), calls)
    }

    @Test
    fun `the prompt without open tasks asks for a new one`() {
        show(detail(garden, emptyList(), NextStepPrompt()))
        composeRule.onNodeWithText("No open tasks left. Add the next step:").assertIsDisplayed()
    }

    @Test
    fun `offline the detail is read-only`() {
        show(detail(garden, listOf(hotel)).copy(offline = true))
        composeRule.onNodeWithTag(TaskListTags.OFFLINE).assertIsDisplayed()
        composeRule.onNodeWithTag(TaskListTags.complete(hotel.id)).assertIsNotEnabled()
        composeRule.onNodeWithTag(ProjectsTags.makeNext(hotel.id)).assertIsNotEnabled()
        composeRule.onNodeWithTag(ProjectsTags.CHOOSE).assertIsNotEnabled()
    }

    @Test
    fun `a detail that could not load offers a retry`() {
        show(list.copy(detail = ProjectDetailState(garden, loading = false, error = LoadError.FAILED)))
        composeRule.onNodeWithTag(LiveListTags.ERROR).assertIsDisplayed()
        composeRule.onNodeWithTag(LiveListTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `finished projects can be shown`() {
        show(list.copy(finished = 2))
        composeRule.onNodeWithText("Finished (2)").performClick()
        assertEquals(listOf("finished"), calls)
    }
}
