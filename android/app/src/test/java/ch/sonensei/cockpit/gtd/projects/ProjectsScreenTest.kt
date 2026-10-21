package ch.sonensei.cockpit.gtd.projects

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.lists.ActionOutcome
import ch.sonensei.cockpit.gtd.lists.ActionSnackbar
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
                dismiss = { calls += "dismiss ${it.title}" },
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
        show(detail(garden, listOf(hotel, visa), NextStepPrompt(garden.id, done = "Book the flights")))
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
        show(detail(garden, emptyList(), NextStepPrompt(garden.id)))
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
    fun `a detail whose reload failed keeps its tasks read-only, says so and offers a retry`() {
        show(list.copy(detail = ProjectDetailState(garden, home, listOf(hotel), loading = false, error = LoadError.FAILED, loaded = true)))
        composeRule.onNodeWithTag(LiveListTags.STALE).assertIsDisplayed()
        composeRule.onNodeWithText("This list could not be refreshed and may be outdated.").assertIsDisplayed()
        composeRule.onNodeWithTag(TaskListTags.complete(hotel.id)).assertIsNotEnabled()
        composeRule.onNodeWithTag(ProjectsTags.CHOOSE).assertIsNotEnabled()
        composeRule.onNodeWithTag(LiveListTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `a detail whose reload failed without open tasks still offers a retry`() {
        show(list.copy(detail = ProjectDetailState(garden, home, emptyList(), loading = false, error = LoadError.OFFLINE, loaded = true)))
        composeRule.onNodeWithTag(LiveListTags.EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText("Offline: this list may be outdated. Changes need a connection.").assertIsDisplayed()
        composeRule.onNodeWithTag(LiveListTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `without loaded areas the projects with an area are not listed under No area, with a retry`() {
        show(list.copy(groups = groupByArea(listOf(holiday, garden, taxes), null), areasError = LoadError.FAILED))
        composeRule.onNodeWithText("The areas could not be loaded, so projects are not grouped by area.").assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.AREA_UNLOADED).assertIsDisplayed()
        composeRule.onNodeWithText("In an area (areas not loaded)").assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.area(home.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(ProjectsTags.area(null)).assertIsDisplayed()
        composeRule.onNodeWithTag(LiveListTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `while areas load the list shows loading`() {
        show(list.copy(groups = emptyList(), areasLoading = true))
        composeRule.onNodeWithTag(ProjectsTags.LOADING).assertIsDisplayed()
        composeRule.onNodeWithTag(LiveListTags.STALE).assertDoesNotExist()
    }

    @Test
    fun `finished projects can be shown`() {
        show(list.copy(finished = 2))
        composeRule.onNodeWithText("Finished (2)").performClick()
        assertEquals(listOf("finished"), calls)
    }

    @Test
    fun `the list shows loading, then the empty state`() {
        show(ProjectsUiState(today = LocalDate.of(2026, 10, 20)))
        composeRule.onNodeWithTag(ProjectsTags.LOADING).assertIsDisplayed()
        state = list.copy(groups = emptyList())
        composeRule.onNodeWithTag(ProjectsTags.EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText("No projects yet. Turn an inbox item into one with “To project”.").assertIsDisplayed()
    }

    @Test
    fun `without stalled projects the header says every project has a next step`() {
        show(list.copy(groups = groupByArea(listOf(holiday), listOf(home))))
        composeRule.onNodeWithText("Every active project has a next step").assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.SHOW_FINISHED).assertDoesNotExist()
    }

    @Test
    fun `a detail still loading its tasks shows loading, and its description`() {
        show(
            list.copy(
                detail = ProjectDetailState(holiday.copy(descriptionMd = "Two weeks in Sicily"), home, loading = true),
            ),
        )
        composeRule.onNodeWithTag(LiveListTags.LOADING).assertIsDisplayed()
        composeRule.onNodeWithText("Two weeks in Sicily").assertIsDisplayed()
    }

    @Test
    fun `a refused next step is reported in a snackbar, then dismissed`() {
        val refused = ActionSnackbar(ActionSnackbar.Kind.NEXT_STEP, "Compare hotels", ActionOutcome.CONFLICT)
        show(detail(garden, listOf(hotel)).copy(message = refused))
        val text = "This task was changed elsewhere. The list now shows its current state."
        composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitUntil(TIMEOUT) { "dismiss Compare hotels" in calls }
    }

    @Test
    fun `a next step that was set is confirmed in a snackbar`() {
        val set = ActionSnackbar(ActionSnackbar.Kind.NEXT_STEP, "Compare hotels", ActionOutcome.DONE)
        show(detail(garden, listOf(hotel)).copy(message = set))
        composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText("Next step: Compare hotels").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `the system back gesture in a detail returns to the list`() {
        state = detail(holiday, listOf(book))
        var dispatcher: OnBackPressedDispatcher? = null
        composeRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CockpitTheme { ProjectsContent(state, ProjectsCallbacks(close = { calls += "close" })) }
        }
        composeRule.runOnIdle { dispatcher!!.onBackPressed() }
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun `while a new next step is being added the prompt cannot add it again`() {
        show(detail(garden, listOf(hotel), NextStepPrompt(garden.id)).copy(busy = setOf(garden.id)))
        composeRule.onNodeWithTag(ProjectsTags.PROMPT_NEW).performTextInput("Buy seeds")
        composeRule.onNodeWithTag(ProjectsTags.PROMPT_ADD).assertIsNotEnabled()
        composeRule.onNodeWithTag(ProjectsTags.choice(hotel.id)).assertIsEnabled()
    }

    @Test
    fun `a prompt over a detail whose reload failed changes nothing, but can be skipped`() {
        val stale = ProjectDetailState(garden, home, listOf(hotel), loading = false, error = LoadError.FAILED, loaded = true)
        show(list.copy(detail = stale.copy(prompt = NextStepPrompt(garden.id))))
        composeRule.onNodeWithTag(ProjectsTags.choice(hotel.id)).assertIsNotEnabled()
        composeRule.onNodeWithTag(ProjectsTags.PROMPT_NEW).performTextInput("Buy seeds")
        composeRule.onNodeWithTag(ProjectsTags.PROMPT_ADD).assertIsNotEnabled()
        composeRule.onNodeWithTag(ProjectsTags.PROMPT_SKIP).performClick()
        assertEquals(listOf("skip"), calls)
    }

    @Test
    fun `shown finished projects carry their status`() {
        val moved = GtdProject(id(13), "Move flat", ProjectStatus.COMPLETED, stalled = false, version = 2)
        val band = GtdProject(id(14), "Start a band", ProjectStatus.DROPPED, stalled = false, version = 2)
        val novel = GtdProject(id(15), "Write a novel", ProjectStatus.SOMEDAY, stalled = false, version = 2)
        show(list.copy(groups = groupByArea(listOf(moved, band, novel), listOf(home)), showFinished = true, finished = 2))
        composeRule.onNodeWithText("Completed", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Dropped", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Someday", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `project tasks on other lists say which list they are on`() {
        val inbox = GtdTask(id(4), "Sort the receipts", TaskList.INBOX, 3, 1, projectId = holiday.id)
        val later = GtdTask(id(5), "Learn some Italian", TaskList.SOMEDAY, 4, 1, projectId = holiday.id)
        val dated = GtdTask(id(6), "Check in online", TaskList.SCHEDULED, 5, 1, projectId = holiday.id)
        show(detail(holiday, listOf(book, inbox, later, dated)))
        composeRule.onNodeWithText("Inbox", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Someday", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Scheduled", useUnmergedTree = true).assertIsDisplayed()
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
