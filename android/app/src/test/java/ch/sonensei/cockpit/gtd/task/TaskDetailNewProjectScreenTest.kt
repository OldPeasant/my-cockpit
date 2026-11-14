package ch.sonensei.cockpit.gtd.task

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** "New project…" in the task detail project picker: the menu item, its title dialog, and the picker afterwards. */
@RunWith(AndroidJUnit4::class)
class TaskDetailNewProjectScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val garden = GtdProject(id(20), "Garden", ProjectStatus.ACTIVE, stalled = false, version = 1)
    private val seeds = GtdTask(id(1), "Buy seeds", TaskList.NEXT, 0, version = 3, projectId = garden.id)

    private val created = mutableListOf<String>()
    private val edits = mutableListOf<TaskDraft>()

    /** The edit form with the task in Garden; the callbacks record what was asked for. */
    private fun showForm() {
        val draft = TaskDraft.of(seeds)
        val state = TaskDetailUiState(loading = false, detail = TaskDetail(seeds), projects = listOf(garden), draft = draft)
        val callbacks = TaskDetailCallbacks(newProject = { created += it }, edit = { edits += it(draft) })
        composeRule.setContent { CockpitTheme { TaskDetailContent(state, callbacks) } }
    }

    private fun openDialog() {
        composeRule.onNodeWithTag(TaskDetailTags.PROJECT).performScrollTo().performClick()
        composeRule.onNodeWithTag(TaskDetailTags.NEW_PROJECT).assertTextContains("New project…").performClick()
    }

    @Test
    fun `the picker offers New project after the projects`() {
        showForm()
        composeRule.onNodeWithTag(TaskDetailTags.PROJECT).performScrollTo().performClick()
        composeRule.onNodeWithTag(TaskDetailTags.NO_PROJECT).assertExists()
        composeRule.onNodeWithTag(TaskDetailTags.projectOption(garden.id)).assertExists()
        composeRule.onNodeWithTag(TaskDetailTags.NEW_PROJECT).assertExists()
    }

    @Test
    fun `the dialog asks only for a title and Create is disabled while it is blank`() {
        showForm()
        openDialog()
        composeRule.onNodeWithText("New project").assertExists()
        composeRule.onNodeWithTag(TaskDetailTags.NEW_PROJECT_CONFIRM).assertIsNotEnabled()
        composeRule.onNodeWithTag(TaskDetailTags.NEW_PROJECT_INPUT).performTextInput("   ")
        composeRule.onNodeWithTag(TaskDetailTags.NEW_PROJECT_CONFIRM).assertIsNotEnabled()
        assertTrue(created.isEmpty())
    }

    @Test
    fun `Create passes the trimmed title and closes the dialog`() {
        showForm()
        openDialog()
        composeRule.onNodeWithTag(TaskDetailTags.NEW_PROJECT_INPUT).performTextInput("  Kitchen ")
        composeRule.onNodeWithTag(TaskDetailTags.NEW_PROJECT_CONFIRM).assertIsEnabled().performClick()

        assertEquals(listOf("Kitchen"), created)
        composeRule.onNodeWithTag(TaskDetailTags.NEW_PROJECT_INPUT).assertDoesNotExist()
        assertTrue(edits.isEmpty())
    }

    @Test
    fun `Cancel closes the dialog and leaves the task's project`() {
        showForm()
        openDialog()
        composeRule.onNodeWithTag(TaskDetailTags.NEW_PROJECT_INPUT).performTextInput("Kitchen")
        composeRule.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()

        composeRule.onNodeWithTag(TaskDetailTags.NEW_PROJECT_INPUT).assertDoesNotExist()
        assertTrue(created.isEmpty())
        assertTrue(edits.isEmpty())
        composeRule.onNodeWithTag(TaskDetailTags.PROJECT).assertTextContains("Garden")
    }

    @Test
    fun `with the view model the new project shows in the picker and is saved with the task`() {
        val source = FakeTaskDetailSource(TaskDetail(seeds))
        val vm = TaskDetailViewModel(source, flowOf(listOf(garden)), flowOf(emptyList()), flowOf(null))
        vm.open(seeds.id)
        composeRule.setContent {
            CockpitTheme {
                val state = vm.uiState.collectAsStateWithLifecycle().value
                TaskDetailContent(state, TaskDetailCallbacks.of(vm))
            }
        }
        composeRule.onNodeWithTag(TaskDetailTags.EDIT).performClick()
        openDialog()
        composeRule.onNodeWithTag(TaskDetailTags.NEW_PROJECT_INPUT).performTextInput("Kitchen")
        composeRule.onNodeWithTag(TaskDetailTags.NEW_PROJECT_CONFIRM).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TaskDetailTags.PROJECT).performScrollTo().assertTextContains("Kitchen")
        composeRule.onNodeWithTag(TaskDetailTags.SAVE).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("Kitchen"), source.createdProjects)
        val kitchen = UUID.nameUUIDFromBytes("Kitchen".toByteArray())
        assertEquals(JsonPrimitive(kitchen.toString()), source.updates.single().second["projectId"])
    }

    @Test
    fun `a failed create shows the error and the task keeps its project`() {
        createWithError(TaskResult.Failed(500))
        composeRule.onNodeWithText("Could not create the project", substring = true).assertExists()
        composeRule.onNodeWithTag(TaskDetailTags.PROJECT).performScrollTo().assertTextContains("Garden")
    }

    @Test
    fun `a refused create shows the server's reason`() {
        createWithError(TaskResult.Invalid(mapOf("title" to "must be at most 200 characters"), "Invalid project"))
        composeRule.onNodeWithText("Project not created: must be at most 200 characters").assertExists()
        composeRule.onNodeWithTag(TaskDetailTags.PROJECT).performScrollTo().assertTextContains("Garden")
    }

    /** Opens the form with a view model whose project creation answers [result] and asks for "Kitchen". */
    private fun createWithError(result: TaskResult<GtdProject>) {
        val source = FakeTaskDetailSource(TaskDetail(seeds))
        source.createProjectResult = result
        val vm = TaskDetailViewModel(source, flowOf(listOf(garden)), flowOf(emptyList()), flowOf(null))
        vm.open(seeds.id)
        composeRule.setContent {
            CockpitTheme {
                val state = vm.uiState.collectAsStateWithLifecycle().value
                TaskDetailContent(state, TaskDetailCallbacks.of(vm))
            }
        }
        composeRule.onNodeWithTag(TaskDetailTags.EDIT).performClick()
        openDialog()
        composeRule.onNodeWithTag(TaskDetailTags.NEW_PROJECT_INPUT).performTextInput("Kitchen")
        composeRule.onNodeWithTag(TaskDetailTags.NEW_PROJECT_CONFIRM).performClick()
        composeRule.waitForIdle()
    }
}
