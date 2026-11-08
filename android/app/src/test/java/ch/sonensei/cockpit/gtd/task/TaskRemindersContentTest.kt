package ch.sonensei.cockpit.gtd.task

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/** The reminders section on the task detail screen: listing, presets, removing and messages. */
@RunWith(AndroidJUnit4::class)
class TaskRemindersContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val now = Instant.parse("2026-11-09T08:00:00Z")
    private val anchors = ReminderAnchors(dueDate = LocalDate.of(2026, 11, 10), dueTime = LocalTime.of(14, 30))
    private val first = TaskReminder(UUID.fromString("0192f000-0000-7000-8000-000000000001"), Instant.parse("2026-11-09T10:00:00Z"))
    private val sent =
        TaskReminder(
            UUID.fromString("0192f000-0000-7000-8000-000000000002"),
            Instant.parse("2026-11-08T10:00:00Z"),
            firedAt = Instant.parse("2026-11-08T10:00:02Z"),
        )

    private val added = mutableListOf<Instant>()
    private val removed = mutableListOf<TaskReminder>()
    private var retries = 0

    private fun show(
        state: TaskRemindersUiState,
        enabled: Boolean = true,
        taskAnchors: ReminderAnchors = anchors,
    ) = composeRule.setContent {
        CockpitTheme {
            TaskRemindersContent(
                state = state,
                anchors = taskAnchors,
                enabled = enabled,
                add = { added += it },
                remove = { removed += it },
                retry = { retries++ },
                zone = ZoneOffset.UTC,
                now = now,
            )
        }
    }

    @Test
    fun `a task without reminders says so and offers its presets and a custom time`() {
        show(TaskRemindersUiState())

        composeRule.onNodeWithText("Reminders").assertIsDisplayed()
        composeRule.onNodeWithTag(TaskReminderTags.EMPTY).assertTextEquals("No reminders")
        composeRule.onNodeWithTag(TaskReminderTags.PRESET + "AT_DUE").assertIsEnabled()
        composeRule.onNodeWithTag(TaskReminderTags.PRESET + "HOUR_BEFORE_DUE").assertIsEnabled()
        composeRule.onNodeWithTag(TaskReminderTags.PRESET + "DAY_BEFORE_DUE").assertIsEnabled()
        composeRule.onAllNodesWithTag(TaskReminderTags.PRESET + "AT_SCHEDULED").assertCountEquals(0)
        composeRule.onNodeWithTag(TaskReminderTags.CUSTOM).assertIsEnabled()
    }

    @Test
    fun `tapping a preset adds a reminder at its time`() {
        show(TaskRemindersUiState())

        composeRule.onNodeWithText("1 hour before due").performClick()

        assertEquals(listOf(Instant.parse("2026-11-10T13:30:00Z")), added)
    }

    @Test
    fun `existing reminders are listed, a sent one marked, and can be removed`() {
        show(TaskRemindersUiState(reminders = listOf(sent, first)))

        composeRule.onAllNodesWithTag(TaskReminderTags.ITEM).assertCountEquals(2)
        composeRule.onAllNodesWithTag(TaskReminderTags.EMPTY).assertCountEquals(0)
        composeRule.onNodeWithText("(sent)", substring = true).assertIsDisplayed()

        composeRule.onAllNodesWithTag(TaskReminderTags.REMOVE)[1].performClick()

        assertEquals(listOf(first), removed)
    }

    @Test
    fun `while busy nothing can be added or removed`() {
        show(TaskRemindersUiState(reminders = listOf(first), busy = true))

        composeRule.onNodeWithTag(TaskReminderTags.REMOVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(TaskReminderTags.PRESET + "AT_DUE").assertIsNotEnabled()
        composeRule.onNodeWithTag(TaskReminderTags.CUSTOM).assertIsNotEnabled()
    }

    @Test
    fun `a read-only task lists its reminders without add choices`() {
        show(TaskRemindersUiState(reminders = listOf(first)), enabled = false)

        composeRule.onAllNodesWithTag(TaskReminderTags.ITEM).assertCountEquals(1)
        composeRule.onNodeWithTag(TaskReminderTags.REMOVE).assertIsNotEnabled()
        composeRule.onAllNodesWithTag(TaskReminderTags.CUSTOM).assertCountEquals(0)
        composeRule.onAllNodesWithTag(TaskReminderTags.PRESET + "AT_DUE").assertCountEquals(0)
    }

    @Test
    fun `a task without dates offers only the custom time`() {
        show(TaskRemindersUiState(), taskAnchors = ReminderAnchors())

        composeRule.onNodeWithTag(TaskReminderTags.CUSTOM).assertIsEnabled()
        ReminderPreset.entries.forEach { composeRule.onAllNodesWithTag(TaskReminderTags.PRESET + it.name).assertCountEquals(0) }
    }

    @Test
    fun `offline shows its message with a retry, and no empty text`() {
        show(TaskRemindersUiState(error = ReminderError.OFFLINE))

        composeRule.onNodeWithTag(TaskReminderTags.ERROR).assertTextEquals("Offline: reminders need a connection.")
        composeRule.onAllNodesWithTag(TaskReminderTags.EMPTY).assertCountEquals(0)
        composeRule.onNodeWithText("Retry").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun `a rejected and a failed change have their own messages`() {
        var state by mutableStateOf(TaskRemindersUiState(error = ReminderError.INVALID))
        composeRule.setContent {
            CockpitTheme {
                TaskRemindersContent(state, anchors, true, {}, {}, {}, ZoneOffset.UTC, now)
            }
        }
        composeRule.onNodeWithTag(TaskReminderTags.ERROR).assertTextEquals("The reminder was not accepted.")

        state = TaskRemindersUiState(error = ReminderError.FAILED)

        composeRule.onNodeWithTag(TaskReminderTags.ERROR).assertTextEquals("The reminders could not be loaded or changed.")
    }

    @Test
    fun `a past time shows its message without a retry`() {
        show(TaskRemindersUiState(pastTime = true))

        composeRule.onNodeWithTag(TaskReminderTags.ERROR).assertTextEquals("That time has already passed. Pick a time in the future.")
        composeRule.onAllNodesWithText("Retry").assertCountEquals(0)
    }

    @Test
    fun `the custom time asks for a date then a time and adds it`() {
        show(TaskRemindersUiState())

        composeRule.onNodeWithTag(TaskReminderTags.CUSTOM).performClick()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.onNodeWithText("OK").performClick()

        // Today (the picker's preset date) at the default nine o'clock, in the zone of the screen.
        assertEquals(listOf(Instant.parse("2026-11-09T09:00:00Z")), added)
    }

    @Test
    fun `the section loads the task's reminders through its view model and adds through it`() {
        val calls = mutableListOf<String>()
        val source =
            object : TaskReminderSource {
                override suspend fun list(taskId: UUID): Result<List<TaskReminder>> {
                    calls += "list $taskId"
                    return Result.success(listOf(first))
                }

                override suspend fun add(
                    taskId: UUID,
                    at: Instant,
                ): Result<TaskReminder> {
                    calls += "add $taskId $at"
                    return Result.success(TaskReminder(UUID.fromString("0192f000-0000-7000-8000-000000000003"), at))
                }

                override suspend fun remove(id: UUID): Result<Unit> = Result.success(Unit)
            }
        val taskId = UUID.fromString("0192f000-0000-7000-9000-000000000001")
        // A due date far ahead, so the presets stay in the future on the real clock the section uses.
        val farAhead = ReminderAnchors(dueDate = LocalDate.of(2099, 1, 10))
        composeRule.setContent { CockpitTheme { TaskRemindersSection(taskId, farAhead, true, TaskRemindersViewModel(source)) } }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag(TaskReminderTags.ITEM).assertCountEquals(1)
        composeRule.onNodeWithTag(TaskReminderTags.PRESET + "AT_DUE").performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag(TaskReminderTags.ITEM).assertCountEquals(2)
        assertEquals("list $taskId", calls.first())
        assertTrue(calls[1], calls[1].startsWith("add $taskId 2099-01-"))
    }

    @Test
    fun `the task detail shows the reminders panel with the task and whether it can be changed`() {
        val task = GtdTask(UUID.fromString("0192f000-0000-7000-9000-000000000001"), "File tax return", TaskList.NEXT, 0, 1)
        val panels = mutableListOf<Pair<GtdTask, Boolean>>()
        val callbacks = TaskDetailCallbacks()
        var offline by mutableStateOf(false)
        composeRule.setContent {
            CockpitTheme {
                TaskDetailContent(
                    // Offline makes the task read-only.
                    TaskDetailUiState(loading = false, detail = TaskDetail(task), offline = offline),
                    callbacks,
                    remindersPanel = { detail, editable -> panels += detail.task to editable },
                )
            }
        }
        composeRule.waitForIdle()
        offline = true
        composeRule.waitForIdle()

        assertEquals(task to true, panels.first())
        assertEquals(task to false, panels.last())
    }

    @Test
    fun `cancelling the custom date adds nothing`() {
        show(TaskRemindersUiState())

        composeRule.onNodeWithTag(TaskReminderTags.CUSTOM).performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertTrue(added.isEmpty())
        composeRule.onAllNodesWithText("OK").assertCountEquals(0)
    }
}
