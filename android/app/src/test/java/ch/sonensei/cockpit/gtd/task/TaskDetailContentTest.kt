package ch.sonensei.cockpit.gtd.task

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.lists.LogbookCallbacks
import ch.sonensei.cockpit.gtd.lists.LogbookContent
import ch.sonensei.cockpit.gtd.lists.LogbookUiState
import ch.sonensei.cockpit.gtd.lists.LoggedTask
import ch.sonensei.cockpit.gtd.next.TaskListTags
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The detail screen's content (shown fields, checklist, actions, undo of a delete) and the Logbook rows opening it. */
@RunWith(AndroidJUnit4::class)
class TaskDetailContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val garden = GtdProject(id(20), "Garden", ProjectStatus.ACTIVE, stalled = false, version = 1)
    private val home = GtdContext(id(80), "@home", 0, 1)
    private val tick = ChecklistItem(id(40), "Tomatoes", done = false, position = 0, version = 1)
    private val seeds =
        GtdTask(
            id(1),
            "Buy seeds",
            TaskList.NEXT,
            0,
            version = 3,
            projectId = garden.id,
            contextIds = listOf(home.id),
            dueDate = LocalDate.of(2026, 10, 24),
            dueTime = "14:30:00",
            timeEstimateMin = 15,
            energy = Energy.HIGH,
            notesMd = "Tomatoes **and** basil",
        )
    private val contract =
        GtdTask(id(2), "Contract", TaskList.WAITING, 0, 1, waitingForText = "Anna Example", waitingSince = LocalDate.of(2026, 10, 17))

    private val calls = mutableListOf<String>()
    private val callbacks =
        TaskDetailCallbacks(
            close = { calls += "close" },
            complete = { calls += "complete" },
            move = { target, person -> calls += "move $target $person" },
            delete = { calls += "delete" },
            undoDelete = { calls += "undo" },
            toggleItem = { calls += "toggle ${it.text}" },
            renameItem = { item, text -> calls += "rename ${item.text} -> $text" },
            addItem = { calls += "add $it" },
            deleteItem = { calls += "delete item ${it.text}" },
            dismiss = { calls += "dismiss $it" },
        )

    private fun show(state: TaskDetailUiState) = composeRule.setContent { CockpitTheme { TaskDetailContent(state, callbacks) } }

    private fun loaded(detail: TaskDetail) =
        TaskDetailUiState(loading = false, detail = detail, projects = listOf(garden), contexts = listOf(home))

    private fun property(
        name: String,
        text: String,
    ) {
        val row = hasTestTag(TaskDetailTags.property(name)) and hasAnyDescendant(hasText(text, substring = true))
        composeRule.onNode(row).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `shows the task's fields, notes rendered as Markdown, and checklist`() {
        show(loaded(TaskDetail(seeds, checklist = listOf(tick), recurrenceRule = "FREQ=WEEKLY")))
        composeRule.onNodeWithTag(TaskDetailTags.TITLE).assertIsDisplayed()
        property("project", "Garden")
        property("contexts", "@home")
        property("estimate", "15 min")
        property("energy", "High")
        property("repeat", "Weekly")
        composeRule.onNodeWithTag(TaskDetailTags.NOTES).performScrollTo()
        composeRule.onNodeWithText("Tomatoes and basil", substring = true).assertExists()
        composeRule.onNodeWithText("Tomatoes **and** basil", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag(TaskDetailTags.item(tick.id)).performScrollTo().assertIsDisplayed()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a Waiting task shows whom it waits for`() {
        show(loaded(TaskDetail(contract)))
        property("waiting", "Anna Example")
        composeRule.onNodeWithTag(TaskDetailTags.property("tickler")).assertDoesNotExist()
    }

    @Test
    fun `a Someday task shows its tickler date and no waiting row`() {
        show(loaded(TaskDetail(seeds.copy(list = TaskList.SOMEDAY), ticklerDate = LocalDate.of(2026, 12, 1))))
        composeRule.onNodeWithTag(TaskDetailTags.property("tickler")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(TaskDetailTags.property("waiting")).assertDoesNotExist()
    }

    @Test
    fun `checklist items are ticked, renamed, deleted and added on the detail view`() {
        show(loaded(TaskDetail(seeds, checklist = listOf(tick))))
        composeRule.onNodeWithTag(TaskDetailTags.itemCheck(tick.id)).performScrollTo().performClick()
        composeRule.onNodeWithText("Tomatoes").performScrollTo().performClick()
        composeRule.onNodeWithTag(TaskDetailTags.RENAME_INPUT).performTextReplacement("Cherry tomatoes")
        composeRule.onNodeWithTag(TaskDetailTags.RENAME_CONFIRM).performClick()
        composeRule.onNodeWithTag(TaskDetailTags.itemDelete(tick.id)).performScrollTo().performClick()
        composeRule.onNodeWithTag(TaskDetailTags.ADD_ITEM_INPUT).performScrollTo().performTextInput("Basil")
        composeRule.onNodeWithTag(TaskDetailTags.ADD_ITEM).performClick()
        assertEquals(
            listOf("toggle Tomatoes", "rename Tomatoes -> Cherry tomatoes", "delete item Tomatoes", "add Basil"),
            calls,
        )
    }

    @Test
    fun `moving to Waiting asks for the person, a task cannot be moved to its own list`() {
        show(loaded(TaskDetail(seeds)))
        composeRule.onNodeWithTag(TaskDetailTags.MORE).performClick()
        composeRule.onNodeWithTag(TaskDetailTags.move(MoveTarget.NEXT)).assertDoesNotExist()
        composeRule.onNodeWithTag(TaskDetailTags.move(MoveTarget.SOMEDAY)).assertIsDisplayed()
        composeRule.onNodeWithTag(TaskDetailTags.move(MoveTarget.WAITING)).performClick()
        composeRule.onNodeWithTag(TaskDetailTags.WAITING_INPUT).performTextInput("  Anna Example ")
        composeRule.onNodeWithTag(TaskDetailTags.WAITING_CONFIRM).performClick()
        assertEquals(listOf("move WAITING Anna Example"), calls)
    }

    @Test
    fun `after a delete the snackbar offers undo`() {
        show(loaded(TaskDetail(seeds)).copy(deleted = true, message = DetailMessage.Deleted))
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("dismiss Deleted", "undo"), calls)
    }

    @Test
    fun `when the undo offer ends unused the screen closes`() {
        show(loaded(TaskDetail(seeds)).copy(deleted = true, message = DetailMessage.Deleted))
        composeRule.onNodeWithText("Undo").assertExists()
        composeRule.mainClock.advanceTimeBy(UNDO_TIMEOUT_MS)
        composeRule.waitForIdle()
        assertEquals(listOf("dismiss Deleted", "close"), calls)
    }

    @Test
    fun `a deleted task can no longer be changed`() {
        show(loaded(TaskDetail(seeds, checklist = listOf(tick))).copy(deleted = true))
        composeRule.onNodeWithTag(TaskDetailTags.COMPLETE).performClick()
        composeRule.onNodeWithTag(TaskDetailTags.MORE).performClick()
        composeRule.onNodeWithTag(TaskDetailTags.ADD_ITEM_INPUT).assertDoesNotExist()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a task gone elsewhere says so`() {
        show(TaskDetailUiState(loading = false, notFound = true))
        composeRule.onNodeWithTag(TaskDetailTags.NOT_FOUND).assertIsDisplayed()
    }

    @Test
    fun `in the shell a Logbook row opens the task detail instead of the Logbook's dialog`() {
        val opened = mutableListOf<Triple<UUID, GtdTask?, Instant?>>()
        val logged = LoggedTask(seeds, Instant.parse("2026-10-20T08:00:00Z"))
        val logbookCalls = mutableListOf<String>()
        val state =
            LogbookUiState(
                tasks = listOf(logged),
                loading = false,
                loaded = true,
                zone = ZoneOffset.UTC,
                today = LocalDate.of(2026, 10, 20),
            )
        composeRule.setContent {
            CockpitTheme {
                CompositionLocalProvider(LocalTaskOpener provides TaskOpener { id, snapshot, at -> opened += Triple(id, snapshot, at) }) {
                    LogbookContent(state, LogbookCallbacks(open = { logbookCalls += "open ${it.task.title}" }))
                }
            }
        }
        composeRule.onNodeWithTag(TaskListTags.row(seeds.id)).performClick()
        // The completion time goes along, so offline the detail shows the task as done.
        assertEquals(listOf(Triple(seeds.id, seeds, logged.completedAt)), opened)
        assertTrue(logbookCalls.isEmpty())
    }

    private companion object {
        /** Longer than a long snackbar (10 s). */
        const val UNDO_TIMEOUT_MS = 12_000L
    }
}
