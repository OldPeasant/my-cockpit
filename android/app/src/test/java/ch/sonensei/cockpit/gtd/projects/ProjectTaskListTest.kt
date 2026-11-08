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
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
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
        // On the clickable row a screen reader focuses, not on the item around it, which it does not focus.
        assertTrue(
            composeRule
                .onNodeWithTag(TaskListTags.row(hotel.id))
                .fetchSemanticsNode()
                .config
                .contains(SemanticsActions.OnClick),
        )
        assertNull(
            composeRule
                .onNodeWithTag(ProjectTaskTags.item(hotel.id))
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsActions.CustomActions),
        )
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

    @Test
    fun `a blank title adds nothing`() {
        show(detail(holiday, listOf(book, hotel)))
        composeRule.onNodeWithTag(ProjectTaskTags.ADD).performTextInput("   ")
        composeRule.onNodeWithTag(ProjectTaskTags.ADD_BUTTON).assertIsNotEnabled()
        composeRule.onNodeWithTag(ProjectTaskTags.ADD).performImeAction()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `the first task of a project without tasks is added from the field`() {
        show(detail(garden, emptyList()))
        composeRule.onNodeWithTag(LiveListTags.EMPTY).assertExists()
        composeRule.onNodeWithTag(ProjectTaskTags.ADD).performTextInput("Buy seeds")
        composeRule.onNodeWithTag(ProjectTaskTags.ADD_BUTTON).performClick()
        assertEquals(listOf("add Buy seeds next=false"), calls)
    }

    @Test
    fun `the typed text belongs to the open project`() {
        show(detail(holiday, listOf(book, hotel)))
        composeRule.onNodeWithTag(ProjectTaskTags.ADD).performTextInput("Pack the bags")
        composeRule.onNodeWithTag(ProjectTaskTags.ADD_BUTTON).assertIsEnabled()
        state = detail(garden, listOf(hotel))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ProjectTaskTags.ADD_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun `a single task can neither be dragged nor moved`() {
        show(detail(holiday, listOf(book)))
        assertEquals(emptyList<String>(), actions(book))
        composeRule.onNodeWithTag(ProjectTaskTags.item(book.id)).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(6) { moveBy(Offset(0f, height * 0.15f)) }
            up()
        }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), calls.filter { it.startsWith("reorder") })
    }

    @Test
    fun `a task dragged back to its place saves nothing`() {
        show(detail(holiday, listOf(book, hotel, visa)))
        val rowHeight = height(book)
        val list = composeRule.onNodeWithTag(LiveListTags.LIST)
        // Down past the middle of the next row (the rows swap), then back up to where it started.
        pickUpFirstAndDragDownOneRow()
        repeat(6) {
            list.performTouchInput { moveBy(Offset(0f, -rowHeight * 0.15f)) }
            composeRule.waitForIdle()
        }
        list.performTouchInput { up() }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), calls)
        assertTrue(top(book) < top(hotel))
    }

    @Test
    fun `a dropped order stays in the list, and a refused one goes back`() {
        show(detail(holiday, listOf(book, hotel, visa)))
        composeRule.onNodeWithTag(ProjectTaskTags.item(book.id)).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(6) { moveBy(Offset(0f, height * 0.15f)) }
            up()
        }
        composeRule.waitForIdle()
        assertEquals(listOf("reorder [Compare hotels, Book the flights, Visa from the embassy]"), calls)
        // Before the state has the new order the row does not jump back, and the move actions follow the new order.
        assertTrue(top(hotel) < top(book))
        assertEquals(listOf("Move down"), actions(hotel))
        assertEquals(listOf("Move up", "Move down"), actions(book))
        // The view model shows the order while saving it ...
        state = detail(holiday, listOf(hotel, book, visa))
        composeRule.waitForIdle()
        assertTrue(top(hotel) < top(book))
        // ... and the server's order when the save was refused.
        state = detail(holiday, listOf(book, hotel, visa))
        composeRule.waitForIdle()
        assertTrue(top(book) < top(hotel))
        assertEquals(listOf("Move down"), actions(book))
    }

    /** Picks the first row up on the list and drags it past the middle of the second row, one frame per step. */
    private fun pickUpFirstAndDragDownOneRow() {
        val rowHeight = height(book)
        val list = composeRule.onNodeWithTag(LiveListTags.LIST)
        list.performTouchInput {
            down(Offset(centerX, rowHeight / 2))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(0f, 1f))
        }
        repeat(6) {
            list.performTouchInput { moveBy(Offset(0f, rowHeight * 0.15f)) }
            composeRule.waitForIdle()
        }
    }

    @Test
    fun `a drag interrupted by a change of the tasks is not saved`() {
        show(detail(holiday, listOf(book, hotel, visa)))
        pickUpFirstAndDragDownOneRow()
        // A task completed elsewhere arrives while the finger is still down: the drag ends without a drop.
        state = detail(holiday, listOf(book, hotel))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(LiveListTags.LIST).performTouchInput { up() }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), calls.filter { it.startsWith("reorder") })
        assertTrue(top(book) < top(hotel))
    }

    @Test
    fun `a drag interrupted by going read-only is not saved and the list keeps the state's order`() {
        show(detail(holiday, listOf(book, hotel, visa)))
        pickUpFirstAndDragDownOneRow()
        state = state.copy(offline = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(LiveListTags.LIST).performTouchInput { up() }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), calls.filter { it.startsWith("reorder") })
        assertTrue(top(book) < top(hotel))
    }

    @Test
    fun `the add field and the move actions are labelled in English`() {
        show(detail(holiday, listOf(book, hotel)))
        composeRule.onNodeWithText("New task", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Add task", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(ProjectTaskTags.ADD_MAKE_NEXT).assertTextContains("Make it the next step")
        assertEquals(listOf("Move up"), actions(hotel))
    }

    @Test
    @Config(qualifiers = "de")
    fun `the add field and the move actions are labelled in German`() {
        show(detail(holiday, listOf(book, hotel, visa)))
        composeRule.onNodeWithText("Neue Aufgabe", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Aufgabe hinzufügen", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(ProjectTaskTags.ADD_MAKE_NEXT).assertTextContains("Zum nächsten Schritt machen")
        assertEquals(listOf("Nach oben verschieben", "Nach unten verschieben"), actions(hotel))
    }

    private fun height(task: GtdTask): Float =
        composeRule
            .onNodeWithTag(ProjectTaskTags.item(task.id))
            .fetchSemanticsNode()
            .size.height
            .toFloat()

    private fun top(task: GtdTask): Float =
        composeRule
            .onNodeWithTag(ProjectTaskTags.item(task.id))
            .fetchSemanticsNode()
            .boundsInRoot.top

    /** The move actions on the row's merged, clickable node: the one a screen reader focuses and lists actions of. */
    private fun actions(task: GtdTask): List<String> =
        composeRule
            .onNodeWithTag(TaskListTags.row(task.id))
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
            .onNodeWithTag(TaskListTags.row(task.id))
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .first { it.label == label }
            .action()
    }
}
