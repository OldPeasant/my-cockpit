package ch.sonensei.cockpit.gtd.projects

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
                newProject = { calls += "new" },
                editProject = { calls += "edit" },
                editForm = { change -> calls += "form ${change(state.form!!.draft)}" },
                togglePreview = { calls += "preview" },
                cancelForm = { calls += "cancel" },
                saveForm = { calls += "save" },
                setStatus = { calls += "status $it" },
                requestDelete = { calls += "delete" },
                confirm = { calls += "confirm" },
                cancelConfirm = { calls += "cancel confirm" },
                dismissProjectMessage = { calls += "dismiss project ${it.title}" },
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
    fun `offline, a detail whose reload failed offline shows only the offline banner`() {
        val stale = ProjectDetailState(garden, home, listOf(hotel), loading = false, error = LoadError.OFFLINE, loaded = true)
        show(list.copy(offline = true, detail = stale))
        composeRule.onNodeWithTag(TaskListTags.OFFLINE).assertIsDisplayed()
        composeRule.onNodeWithTag(LiveListTags.STALE).assertDoesNotExist()
        composeRule.onNodeWithTag(TaskListTags.complete(hotel.id)).assertIsNotEnabled()
    }

    @Test
    fun `offline, a detail whose reload failed for another reason still says so and offers a retry`() {
        val stale = ProjectDetailState(garden, home, listOf(hotel), loading = false, error = LoadError.FAILED, loaded = true)
        show(list.copy(offline = true, detail = stale))
        composeRule.onNodeWithTag(TaskListTags.OFFLINE).assertIsDisplayed()
        composeRule.onNodeWithTag(LiveListTags.STALE).assertIsDisplayed()
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
    fun `while areas load the cached projects show without their areas and without an error`() {
        show(list.copy(groups = groupByArea(listOf(holiday, garden, taxes), null), areasLoading = true))
        composeRule.onNodeWithText("1 project has no next step").assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.AREA_UNLOADED).assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.LOADING).assertDoesNotExist()
        composeRule.onNodeWithTag(LiveListTags.STALE).assertDoesNotExist()
    }

    @Test
    fun `while areas load without projects the list shows loading`() {
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
        composeRule
            .onNodeWithText(
                "No projects yet. Create one with + or turn an inbox item into one with “To project”.",
            ).assertIsDisplayed()
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

    @Test
    fun `a project with a due date shows it next to the stalled badge`() {
        val party =
            GtdProject(id(16), "Plan the party", ProjectStatus.ACTIVE, stalled = true, version = 2, dueDate = LocalDate.of(2026, 11, 2))
        show(list.copy(groups = groupByArea(listOf(party), listOf(home))))
        composeRule.onNodeWithTag(ProjectsTags.stalled(party.id), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Due ", substring = true, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("2026", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a detail that could not load while offline says the server is unreachable and offers a retry`() {
        show(list.copy(detail = ProjectDetailState(garden, loading = false, error = LoadError.OFFLINE)))
        composeRule
            .onNodeWithText("The server is unreachable, so this list could not be loaded. Try again when you are online.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag(LiveListTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    private fun form(
        form: ProjectFormState,
        base: ProjectsUiState = list,
    ) = base.copy(areas = listOf(home), form = form)

    @Test
    fun `the list header's plus opens a new project, offline it is disabled`() {
        show(list)
        composeRule.onNodeWithContentDescription("New project").assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectFormTags.NEW).performClick()
        assertEquals(listOf("new"), calls)
        state = list.copy(offline = true)
        composeRule.onNodeWithTag(ProjectFormTags.NEW).assertIsNotEnabled()
    }

    @Test
    fun `an active project's menu offers edit, complete, drop and delete`() {
        show(detail(holiday, listOf(book)))
        composeRule.onNodeWithTag(ProjectFormTags.MENU).performClick()
        composeRule.onNodeWithText("Edit").assertIsDisplayed()
        composeRule.onNodeWithText("Complete project").assertIsDisplayed()
        composeRule.onNodeWithText("Drop project").assertIsDisplayed()
        composeRule.onNodeWithText("Delete project").assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectFormTags.MENU_REACTIVATE).assertDoesNotExist()
        composeRule.onNodeWithTag(ProjectFormTags.MENU_COMPLETE).performClick()
        composeRule.onNodeWithTag(ProjectFormTags.MENU).performClick()
        composeRule.onNodeWithTag(ProjectFormTags.MENU_DROP).performClick()
        composeRule.onNodeWithTag(ProjectFormTags.MENU).performClick()
        composeRule.onNodeWithTag(ProjectFormTags.MENU_EDIT).performClick()
        composeRule.onNodeWithTag(ProjectFormTags.MENU).performClick()
        composeRule.onNodeWithTag(ProjectFormTags.MENU_DELETE).performClick()
        assertEquals(listOf("status COMPLETED", "status DROPPED", "edit", "delete"), calls)
    }

    @Test
    fun `a completed project's menu offers to make it active again instead of completing it`() {
        show(detail(holiday.copy(status = ProjectStatus.COMPLETED), emptyList()))
        composeRule.onNodeWithTag(ProjectFormTags.MENU).performClick()
        composeRule.onNodeWithTag(ProjectFormTags.MENU_COMPLETE).assertDoesNotExist()
        composeRule.onNodeWithTag(ProjectFormTags.MENU_DROP).assertIsDisplayed()
        composeRule.onNodeWithText("Make active again").performClick()
        assertEquals(listOf("status ACTIVE"), calls)
    }

    @Test
    fun `a dropped project's menu cannot drop it again, an on-hold one offers every status change`() {
        show(detail(holiday.copy(status = ProjectStatus.DROPPED), emptyList()))
        composeRule.onNodeWithTag(ProjectFormTags.MENU).performClick()
        composeRule.onNodeWithTag(ProjectFormTags.MENU_DROP).assertDoesNotExist()
        composeRule.onNodeWithTag(ProjectFormTags.MENU_COMPLETE).assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectFormTags.MENU_REACTIVATE).assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectFormTags.MENU_REACTIVATE).performClick()
        state = detail(taxes, emptyList())
        composeRule.onNodeWithTag(ProjectFormTags.MENU).performClick()
        composeRule.onNodeWithTag(ProjectFormTags.MENU_COMPLETE).assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectFormTags.MENU_DROP).assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectFormTags.MENU_REACTIVATE).assertIsDisplayed()
    }

    @Test
    fun `offline the menu's actions are disabled`() {
        show(detail(holiday, listOf(book)).copy(offline = true))
        composeRule.onNodeWithTag(ProjectFormTags.MENU).performClick()
        composeRule.onNodeWithTag(ProjectFormTags.MENU_EDIT).assertIsNotEnabled()
        composeRule.onNodeWithTag(ProjectFormTags.MENU_COMPLETE).assertIsNotEnabled()
        composeRule.onNodeWithTag(ProjectFormTags.MENU_DELETE).assertIsNotEnabled()
    }

    @Test
    fun `completing with open tasks asks with the count, and confirming confirms`() {
        show(detail(holiday, listOf(book, hotel)).copy(confirm = ProjectConfirm.Finish(ProjectStatus.COMPLETED, 2, viaForm = false)))
        composeRule.onNodeWithTag(ProjectFormTags.CONFIRM).assertIsDisplayed()
        composeRule.onNodeWithText("Complete “Plan holiday”?").assertIsDisplayed()
        composeRule.onNodeWithText("2 tasks are still open. They stay open and keep the project.").assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectFormTags.CONFIRM_OK).assertTextEquals("Complete").performClick()
        assertEquals(listOf("confirm"), calls)
    }

    @Test
    fun `dropping with one open task asks in the singular, cancelling cancels`() {
        show(detail(holiday, listOf(book)).copy(confirm = ProjectConfirm.Finish(ProjectStatus.DROPPED, 1, viaForm = true)))
        composeRule.onNodeWithText("Drop “Plan holiday”?").assertIsDisplayed()
        composeRule.onNodeWithText("1 task is still open. It stays open and keeps the project.").assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectFormTags.CONFIRM_OK).assertTextEquals("Drop")
        composeRule.onNodeWithTag(ProjectFormTags.CONFIRM_CANCEL).performClick()
        assertEquals(listOf("cancel confirm"), calls)
    }

    @Test
    fun `deleting asks first, says where the project goes and what happens to its open tasks`() {
        show(detail(holiday, listOf(book, hotel)).copy(confirm = ProjectConfirm.Delete(2)))
        composeRule.onNodeWithText("Delete “Plan holiday”?").assertIsDisplayed()
        composeRule.onNodeWithText("The project moves to the Trash, where you can restore it.").assertIsDisplayed()
        composeRule.onNodeWithText("Its 2 open tasks stay open and keep the project.").assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectFormTags.CONFIRM_OK).assertTextEquals("Delete")
        state = detail(holiday, emptyList()).copy(confirm = ProjectConfirm.Delete(0))
        composeRule.onNodeWithText("The project moves to the Trash, where you can restore it.").assertIsDisplayed()
        composeRule.onNodeWithText("keep the project", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("keeps the project", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the new project form has no status, asks for a missing title, and saves or cancels`() {
        show(form(ProjectFormState(titleMissing = true)))
        composeRule.onNodeWithTag(ProjectFormTags.FORM).assertIsDisplayed()
        composeRule.onNodeWithText("New project").assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectFormTags.STATUS).assertDoesNotExist()
        composeRule.onNodeWithTag(ProjectFormTags.TITLE_ERROR, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Give the project a title.", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectFormTags.TITLE).performTextInput("Learn Italian")
        composeRule.onNodeWithTag(ProjectFormTags.SAVE).assertTextEquals("Create").performClick()
        composeRule.onNodeWithTag(ProjectFormTags.CANCEL).performClick()
        assertEquals(listOf("form ${ProjectDraft(title = "Learn Italian")}", "save", "cancel"), calls)
    }

    @Test
    fun `without a missing title there is no title error`() {
        show(form(ProjectFormState()))
        composeRule.onNodeWithTag(ProjectFormTags.TITLE_ERROR, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(ProjectFormTags.CONFLICT).assertDoesNotExist()
    }

    @Test
    fun `the edit form offers the status, the area and clearing the dates`() {
        val dated = holiday.copy(dueDate = LocalDate.of(2026, 12, 1), reviewDate = LocalDate.of(2026, 11, 15))
        val edit = ProjectFormState.edit(dated)
        show(form(edit, detail(dated, listOf(book))))
        composeRule.onNodeWithText("Edit project").assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectFormTags.SAVE).assertTextEquals("Save")
        composeRule.onNodeWithTag(ProjectFormTags.STATUS).assertTextEquals("Active").performClick()
        composeRule.onNodeWithTag(ProjectFormTags.status(ProjectStatus.ON_HOLD)).performClick()
        composeRule.onNodeWithTag(ProjectFormTags.AREA).assertTextEquals("Home").performClick()
        composeRule.onNodeWithTag(ProjectFormTags.NO_AREA).performClick()
        composeRule.onNodeWithTag(ProjectFormTags.DUE_CLEAR).performScrollTo().performClick()
        composeRule.onNodeWithTag(ProjectFormTags.REVIEW_CLEAR).performScrollTo().performClick()
        assertEquals(
            listOf(
                "form ${edit.draft.copy(status = ProjectStatus.ON_HOLD)}",
                "form ${edit.draft.copy(areaId = null)}",
                "form ${edit.draft.copy(dueDate = null)}",
                "form ${edit.draft.copy(reviewDate = null)}",
            ),
            calls,
        )
    }

    @Test
    fun `an area can be picked from the known areas`() {
        show(form(ProjectFormState()))
        composeRule.onNodeWithTag(ProjectFormTags.AREA).assertTextEquals("No area").performClick()
        composeRule.onNodeWithTag(ProjectFormTags.area(home.id)).performClick()
        assertEquals(listOf("form ${ProjectDraft(areaId = home.id)}"), calls)
    }

    @Test
    fun `the description is edited as text and previewed as rendered Markdown`() {
        val draft = ProjectDraft(title = "Plan holiday", description = "Two weeks in **Sicily**")
        show(form(ProjectFormState(projectId = holiday.id, version = 2, initial = draft)))
        composeRule.onNodeWithTag(ProjectFormTags.DESCRIPTION).performScrollTo().assertTextContains("Two weeks in **Sicily**")
        composeRule.onNodeWithTag(ProjectFormTags.PREVIEW).assertDoesNotExist()
        composeRule.onNodeWithTag(ProjectFormTags.DESCRIPTION_EDIT).performClick()
        assertEquals(emptyList<String>(), calls)
        composeRule.onNodeWithTag(ProjectFormTags.DESCRIPTION_PREVIEW).performClick()
        assertEquals(listOf("preview"), calls)

        state = state.copy(form = state.form!!.copy(preview = true))
        composeRule.onNodeWithTag(ProjectFormTags.DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithTag(ProjectFormTags.PREVIEW).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Two weeks in Sicily", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("**", substring = true, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(ProjectFormTags.DESCRIPTION_EDIT).performClick()
        assertEquals(listOf("preview", "preview"), calls)
    }

    @Test
    fun `an empty description previews a hint`() {
        show(form(ProjectFormState(preview = true)))
        composeRule.onNodeWithText("No description yet.", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a form reloaded after a conflict says so`() {
        show(form(ProjectFormState.edit(holiday).copy(conflict = true), detail(holiday, listOf(book))))
        composeRule.onNodeWithTag(ProjectFormTags.CONFLICT).assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "This project was changed elsewhere and has been reloaded. Your edits are kept: check them and save again.",
            ).assertIsDisplayed()
    }

    @Test
    fun `while saving, or offline, the form cannot be saved`() {
        show(form(ProjectFormState(saving = true)))
        composeRule.onNodeWithTag(ProjectFormTags.SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(ProjectFormTags.TITLE).assertIsNotEnabled()
        state = form(ProjectFormState()).copy(offline = true)
        composeRule.onNodeWithTag(ProjectFormTags.SAVE).assertIsNotEnabled()
    }

    @Test
    fun `the form replaces the detail and hides the next-step prompt`() {
        val withPrompt = detail(garden, listOf(hotel), NextStepPrompt(garden.id))
        show(form(ProjectFormState.edit(garden), withPrompt))
        composeRule.onNodeWithTag(ProjectFormTags.FORM).assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.DETAIL).assertDoesNotExist()
        composeRule.onNodeWithTag(ProjectsTags.PROMPT).assertDoesNotExist()
    }

    @Test
    fun `the system back gesture in the form cancels it`() {
        state = form(ProjectFormState())
        var dispatcher: OnBackPressedDispatcher? = null
        composeRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CockpitTheme { ProjectsContent(state, ProjectsCallbacks(cancelForm = { calls += "cancel" }, close = { calls += "close" })) }
        }
        composeRule.runOnIdle { dispatcher!!.onBackPressed() }
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun `the detail renders the description as Markdown`() {
        show(detail(holiday.copy(descriptionMd = "## Plan\n\n- Book **flights**"), listOf(book)))
        composeRule.onNodeWithTag(ProjectFormTags.DESCRIPTION_VIEW).assertIsDisplayed()
        composeRule.onNodeWithText("Plan", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Book flights", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("**", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a saved project is confirmed in a snackbar, then dismissed`() {
        show(list.copy(projectMessage = ProjectMessage(ProjectMessage.Kind.SAVED, "Plan holiday", ActionOutcome.DONE)))
        composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText("Saved Plan holiday").fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitUntil(TIMEOUT) { "dismiss project Plan holiday" in calls }
    }

    @Test
    fun `a project change refused because it changed elsewhere says it was reloaded`() {
        show(list.copy(projectMessage = ProjectMessage(ProjectMessage.Kind.COMPLETED, "Plan holiday", ActionOutcome.CONFLICT)))
        val text = "Plan holiday was changed elsewhere and has been reloaded. Try again."
        composeRule.waitUntil(TIMEOUT) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a deleted project says it moved to the Trash`() {
        show(list.copy(projectMessage = ProjectMessage(ProjectMessage.Kind.DELETED, "Plan holiday", ActionOutcome.DONE)))
        composeRule.waitUntil(TIMEOUT) {
            composeRule.onAllNodesWithText("Moved Plan holiday to the Trash").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
