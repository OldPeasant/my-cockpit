package ch.sonensei.cockpit.gtd.projects

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.lists.LiveListTags
import ch.sonensei.cockpit.gtd.next.TaskListTags
import ch.sonensei.cockpit.gtd.task.LocalTaskOpener
import ch.sonensei.cockpit.gtd.task.TaskOpener
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** The project detail's "Add task" field and its reorderable task list, driven by a state the test controls. */
@RunWith(AndroidJUnit4::class)
class ProjectTaskListTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val book = GtdTask(id(1), "Book the flights", TaskList.NEXT, 0, 1, projectId = id(10))
    private val hotel = GtdTask(id(2), "Compare hotels", TaskList.NEXT, 1, 1, projectId = id(10))
    private val visa = GtdTask(id(3), "Visa from the embassy", TaskList.WAITING, 2, 1, projectId = id(10))
    private val guide = GtdTask(id(4), "Read a travel guide", TaskList.SOMEDAY, 3, 1, projectId = id(10))
    private val tickets = GtdTask(id(5), "Print the tickets", TaskList.SCHEDULED, 4, 1, projectId = id(10))
    private val holiday =
        GtdProject(id(10), "Plan holiday", ProjectStatus.ACTIVE, stalled = false, version = 2, nextTaskId = book.id)
    private val garden = GtdProject(id(11), "Redo the garden", ProjectStatus.ACTIVE, stalled = true, version = 2)

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(ProjectsUiState())

    private fun detail(
        project: GtdProject,
        tasks: List<GtdTask>,
    ) = ProjectsUiState(
        today = LocalDate.of(2026, 10, 20),
        detail = ProjectDetailState(project, tasks = tasks, loading = false, loaded = true),
    )

    private fun show(initial: ProjectsUiState) {
        state = initial
        val callbacks =
            ProjectsCallbacks(
                addTask = { title, next -> calls += "add $title next=$next" },
                reorder = { ids -> calls += "reorder ${ids.map { id -> listOf(book, hotel, visa).first { it.id == id }.title }}" },
                move = { task, by -> calls += "move ${task.title} $by" },
            )
        val opener = TaskOpener { taskId, _, _ -> calls += "open $taskId" }
        composeRule.setContent {
            CockpitTheme { CompositionLocalProvider(LocalTaskOpener provides opener) { ProjectsContent(state, callbacks) } }
        }
    }

    @Test
    fun `the add field adds a task with the button and with the keyboard, and stays open`() {
        show(detail(holiday, listOf(book, hotel)))
        composeRule.onNodeWithTag(ProjectTaskTags.ADD_BUTTON).assertIsNotEnabled()
        composeRule.onNodeWithTag(ProjectTaskTags.ADD).performTextInput("Pack the bags")
        composeRule.onNodeWithTag(ProjectTaskTags.ADD_BUTTON).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(ProjectTaskTags.ADD).performImeAction()
        composeRule.onNodeWithTag(ProjectTaskTags.ADD_MAKE_NEXT).performClick()
        composeRule.onNodeWithTag(ProjectTaskTags.ADD_BUTTON).performClick()
        assertEquals(
            listOf("add Pack the bags next=false", "add Pack the bags next=false", "add Pack the bags next=true"),
            calls,
        )
        composeRule.onNodeWithTag(ProjectTaskTags.ADD).assertIsDisplayed()
    }

    @Test
    fun `the field clears once the task exists, but keeps other text`() {
        show(detail(holiday, listOf(book, hotel)))
        composeRule.onNodeWithTag(ProjectTaskTags.ADD).performTextInput("Pack the bags")
        composeRule.onNodeWithTag(ProjectTaskTags.ADD_MAKE_NEXT).performClick()
        state = state.copy(detail = state.detail!!.copy(added = AddedTask(holiday.id, "Buy a guide", 1)))
        composeRule.onNodeWithTag(ProjectTaskTags.ADD).assertTextContains("Pack the bags")
        state = state.copy(detail = state.detail!!.copy(added = AddedTask(holiday.id, "Pack the bags", 2)))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ProjectTaskTags.ADD_BUTTON).assertIsNotEnabled()
        // The next-step choice was for that task only.
        composeRule.onNodeWithTag(ProjectTaskTags.ADD).performTextInput("Buy a guide")
        composeRule.onNodeWithTag(ProjectTaskTags.ADD_BUTTON).performClick()
        assertEquals(listOf("add Buy a guide next=false"), calls)
    }

    @Test
    fun `a project without a next step does not offer the choice, its new task becomes it anyway`() {
        show(detail(garden, listOf(hotel)))
        composeRule.onNodeWithTag(ProjectTaskTags.ADD_MAKE_NEXT).assertDoesNotExist()
        composeRule.onNodeWithTag(ProjectTaskTags.ADD).performTextInput("Buy seeds")
        composeRule.onNodeWithTag(ProjectTaskTags.ADD_BUTTON).performClick()
        assertEquals(listOf("add Buy seeds next=false"), calls)
    }

    @Test
    fun `adding is off while read-only or while an add runs`() {
        show(detail(holiday, listOf(book, hotel)).copy(offline = true))
        composeRule.onNodeWithTag(ProjectTaskTags.ADD).assertIsNotEnabled()
        composeRule.onNodeWithTag(ProjectTaskTags.ADD_BUTTON).assertIsNotEnabled()
        state = detail(holiday, listOf(book, hotel)).copy(busy = setOf(holiday.id))
        composeRule.onNodeWithTag(ProjectTaskTags.ADD).performTextInput("Pack the bags")
        composeRule.onNodeWithTag(ProjectTaskTags.ADD_BUTTON).assertIsNotEnabled()
        composeRule.onNodeWithTag(ProjectTaskTags.ADD).performImeAction()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `the move actions are offered where a task can move, and not while read-only`() {
        show(detail(holiday, listOf(book, hotel, visa)))
        assertEquals(listOf("Move down"), actions(book))
        assertEquals(listOf("Move up", "Move down"), actions(hotel))
        assertEquals(listOf("Move up"), actions(visa))
        composeRule.runOnIdle { action(hotel, "Move up") }
        composeRule.runOnIdle { action(hotel, "Move down") }
        assertEquals(listOf("move Compare hotels -1", "move Compare hotels 1"), calls)

        state = state.copy(offline = true)
        assertEquals(emptyList<String>(), actions(hotel))
    }

    @Test
    fun `a long press and drag moves a task and saves the new order, without opening it`() {
        show(detail(holiday, listOf(book, hotel, visa)))
        composeRule.onNodeWithTag(ProjectTaskTags.item(book.id)).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            // In steps, like a finger: past the middle of the next row.
            repeat(6) { moveBy(Offset(0f, height * 0.15f)) }
            up()
        }
        composeRule.waitForIdle()
        assertEquals(listOf("reorder [Compare hotels, Book the flights, Visa from the embassy]"), calls)
    }

    @Test
    fun `a drag over several frames moves a task past several rows`() {
        show(detail(holiday, listOf(book, hotel, visa)))
        val rowHeight =
            composeRule
                .onNodeWithTag(ProjectTaskTags.item(book.id))
                .fetchSemanticsNode()
                .size.height
                .toFloat()
        // On the list, which stays put while the row moves: the first row's middle.
        val list = composeRule.onNodeWithTag(LiveListTags.LIST)
        list.performTouchInput {
            down(Offset(centerX, rowHeight / 2))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            // An event after the timeout, below the touch slop, so the long press is seen before the list could scroll.
            moveBy(Offset(0f, 1f))
        }
        repeat(15) {
            list.performTouchInput { moveBy(Offset(0f, rowHeight * 0.15f)) }
            composeRule.waitForIdle()
        }
        list.performTouchInput { up() }
        composeRule.waitForIdle()
        assertEquals(listOf("reorder [Compare hotels, Visa from the embassy, Book the flights]"), calls)
    }

    @Test
    fun `a long press without moving neither saves nor opens the task`() {
        show(detail(holiday, listOf(book, hotel)))
        composeRule.onNodeWithTag(ProjectTaskTags.item(book.id)).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a task row opens task detail, and tasks of other lists keep their list label`() {
        show(detail(holiday, listOf(book, visa, guide, tickets)))
        composeRule.onNodeWithText("Waiting").assertExists()
        composeRule.onNodeWithText("Someday").assertExists()
        composeRule.onNodeWithTag(LiveListTags.LIST).performScrollToNode(hasText("Scheduled"))
        composeRule.onNodeWithText("Scheduled").assertIsDisplayed()
        composeRule.onNodeWithTag(TaskListTags.row(visa.id)).performClick()
        assertEquals(listOf("open ${visa.id}"), calls)
    }

    @Test
    fun `while read-only a long press does not pick a task up`() {
        show(detail(holiday, listOf(book, hotel)).copy(offline = true))
        composeRule.onNodeWithTag(ProjectTaskTags.item(book.id)).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(10) { moveBy(Offset(0f, height * 0.15f)) }
            up()
        }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), calls.filter { it.startsWith("reorder") })
    }

    private fun actions(task: GtdTask): List<String> =
        composeRule
            .onNodeWithTag(ProjectTaskTags.item(task.id))
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.CustomActions)
            .orEmpty()
            .map { it.label }

    private fun action(
        task: GtdTask,
        label: String,
    ) {
        composeRule
            .onNodeWithTag(ProjectTaskTags.item(task.id))
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .first { it.label == label }
            .action()
    }
}
