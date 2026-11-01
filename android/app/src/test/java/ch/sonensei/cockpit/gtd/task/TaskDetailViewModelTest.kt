package ch.sonensei.cockpit.gtd.task

import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailViewModelTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val garden = GtdProject(id(20), "Garden", ProjectStatus.ACTIVE, stalled = false, version = 1)
    private val archive = GtdProject(id(21), "Old archive", ProjectStatus.COMPLETED, stalled = false, version = 1)
    private val home = GtdContext(id(80), "@home", 0, 1)
    private val task =
        GtdTask(
            id(1),
            "Buy seeds",
            TaskList.NEXT,
            0,
            version = 3,
            projectId = garden.id,
            dueDate = LocalDate.of(2026, 10, 24),
            dueTime = "14:30:00",
            timeEstimateMin = 15,
            notesMd = "Tomatoes **and** basil",
        )
    private val tick = ChecklistItem(id(40), "Tomatoes", done = false, position = 0, version = 1)
    private val detail = TaskDetail(task, checklist = listOf(tick))

    private val source = FakeTaskDetailSource(detail)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        TaskDetailViewModel(source, flowOf(listOf(garden, archive)), flowOf(listOf(home)), flowOf(null)).also { vm ->
            vm.open(task.id)
        }

    /** Keeps [vm]'s state collected, as the screen does. */
    private fun kotlinx.coroutines.test.TestScope.collect(vm: TaskDetailViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    @Test
    fun `loads the task with its checklist and offers only active projects plus its own`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            val state = vm.uiState.value
            assertFalse(state.loading)
            assertEquals(detail, state.detail)
            assertFalse(state.readOnly)
            assertEquals(listOf(garden), state.projects)
            assertEquals(listOf(home), state.contexts)
        }

    @Test
    fun `saving sends only the changed fields with the loaded version`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.startEditing()
            vm.edit { it.copy(title = "  Buy seeds and soil ", contextIds = setOf(home.id), energy = Energy.LOW) }
            vm.save()
            val (sent, patch) = source.updates.single()
            assertEquals(3L, sent.version)
            assertEquals(
                JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("Buy seeds and soil"),
                        "contextIds" to JsonArray(listOf(JsonPrimitive(home.id.toString()))),
                        "energy" to JsonPrimitive("LOW"),
                    ),
                ),
                patch,
            )
            val state = vm.uiState.value
            assertNull(state.draft)
            assertEquals("Buy seeds and soil", state.detail?.task?.title)
            assertEquals(DetailMessage.Saved, state.message)
            assertEquals(1, source.changes)
        }

    @Test
    fun `clearing the due date clears its time and the project is cleared with null`() {
        val patch =
            TaskDraft.of(task).copy(dueDate = null, projectId = null, estimate = "").patch(task)
        assertEquals(JsonNull, patch["dueDate"])
        assertEquals(JsonNull, patch["dueTime"])
        assertEquals(JsonNull, patch["projectId"])
        assertEquals(JsonNull, patch["timeEstimateMin"])
        assertEquals(4, patch.size)
    }

    @Test
    fun `a new due time is sent with seconds`() {
        val patch = TaskDraft.of(task).copy(dueTime = LocalTime.of(9, 5)).patch(task)
        assertEquals(JsonObject(mapOf("dueTime" to JsonPrimitive("09:05:00"))), patch)
    }

    @Test
    fun `nothing changed closes the form without a request`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.startEditing()
            vm.save()
            assertTrue(source.updates.isEmpty())
            assertNull(vm.uiState.value.draft)
        }

    @Test
    fun `a blank title and a bad estimate are shown at the fields without sending`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.startEditing()
            vm.edit { it.copy(title = " ", estimate = "99999") }
            vm.save()
            assertTrue(source.updates.isEmpty())
            assertEquals(
                mapOf(TaskField.TITLE to FieldProblem.Required, TaskField.ESTIMATE to FieldProblem.NotANumber),
                vm.uiState.value.problems,
            )
            vm.edit { it.copy(title = "Buy seeds") }
            assertEquals(setOf(TaskField.ESTIMATE), vm.uiState.value.problems.keys)
        }

    @Test
    fun `a 412 reloads the task and says so instead of overwriting it`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.startEditing()
            vm.edit { it.copy(title = "Mine") }
            val theirs = detail.copy(task = task.copy(title = "Theirs", version = 4))
            source.loadResult = TaskResult.Ok(theirs)
            source.updateResult = TaskResult.Conflict
            vm.save()
            val state = vm.uiState.value
            assertEquals(1, source.updates.size)
            assertEquals("Theirs", state.detail?.task?.title)
            assertNull(state.draft)
            assertEquals(DetailMessage.Conflict, state.message)
            assertEquals(2, source.loads)
            assertFalse(state.busy)
        }

    @Test
    fun `problem details are shown at their fields, unknown fields as a message`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.startEditing()
            vm.edit { it.copy(title = "x") }
            source.updateResult = TaskResult.Invalid(mapOf("title" to "is too short"), "Invalid task")
            vm.save()
            assertEquals(mapOf(TaskField.TITLE to FieldProblem.Server("is too short")), vm.uiState.value.problems)
            assertNull(vm.uiState.value.message)
            assertTrue(vm.uiState.value.editing)

            source.updateResult = TaskResult.Invalid(mapOf("position" to "must be positive"), "Invalid task")
            vm.save()
            assertEquals(DetailMessage.Failed("Invalid task"), vm.uiState.value.message)
        }

    @Test
    fun `checklist changes show the server's checklist afterwards`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            val ticked = tick.copy(done = true, version = 2)
            source.checklistResult = TaskResult.Ok(listOf(ticked))
            vm.toggleChecklistItem(tick)
            assertEquals(JsonObject(mapOf("done" to JsonPrimitive(true))), source.itemPatches.single().second)
            assertEquals(
                listOf(ticked),
                vm.uiState.value.detail
                    ?.checklist,
            )

            vm.renameChecklistItem(ticked, " Cherry tomatoes ")
            assertEquals(JsonObject(mapOf("text" to JsonPrimitive("Cherry tomatoes"))), source.itemPatches.last().second)

            vm.addChecklistItem("  Basil ")
            assertEquals(listOf("Basil"), source.added)
            vm.addChecklistItem("   ")
            assertEquals(1, source.added.size)

            vm.deleteChecklistItem(ticked)
            assertEquals(listOf(ticked), source.deletedItems)
            assertEquals(4, source.checklistLoads)
        }

    @Test
    fun `a refused checklist change says why and still shows the current checklist`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            source.itemResult = TaskResult.Conflict
            source.checklistResult = TaskResult.Ok(emptyList())
            vm.toggleChecklistItem(tick)
            assertEquals(DetailMessage.Conflict, vm.uiState.value.message)
            assertEquals(
                emptyList<ChecklistItem>(),
                vm.uiState.value.detail
                    ?.checklist,
            )
        }

    @Test
    fun `moving to waiting needs a person and reloads the task`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.move(MoveTarget.WAITING, " ")
            assertTrue(source.moves.isEmpty())
            source.loadResult = TaskResult.Ok(detail.copy(task = task.copy(list = TaskList.WAITING, waitingForText = "Anna", version = 4)))
            vm.move(MoveTarget.WAITING, "Anna")
            assertEquals(Triple(task, MoveTarget.WAITING, "Anna"), source.moves.single())
            val state = vm.uiState.value
            assertEquals(TaskList.WAITING, state.detail?.task?.list)
            assertEquals(DetailMessage.Moved(MoveTarget.WAITING), state.message)
            assertEquals(1, source.changes)
        }

    @Test
    fun `complete and reopen reload the task`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            source.loadResult = TaskResult.Ok(detail.copy(completedAt = Instant.parse("2026-10-20T10:00:00Z")))
            vm.complete()
            assertEquals(listOf("complete"), source.calls)
            assertTrue(
                vm.uiState.value.detail!!
                    .completed,
            )
            assertEquals(DetailMessage.Completed, vm.uiState.value.message)
            source.loadResult = TaskResult.Ok(detail)
            vm.reopen()
            assertEquals(listOf("complete", "reopen"), source.calls)
            assertFalse(
                vm.uiState.value.detail!!
                    .completed,
            )
        }

    @Test
    fun `delete offers undo, and undo restores and reloads the task`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.delete()
            assertEquals(listOf("delete"), source.calls)
            assertTrue(vm.uiState.value.deleted)
            assertTrue(vm.uiState.value.readOnly)
            assertEquals(DetailMessage.Deleted, vm.uiState.value.message)
            vm.undoDelete()
            assertEquals(listOf("delete", "restore"), source.calls)
            assertFalse(vm.uiState.value.deleted)
            assertEquals(DetailMessage.Restored, vm.uiState.value.message)
            assertEquals(2, source.changes)
        }

    @Test
    fun `offline the task opens read-only from the cached copy and nothing can be changed`() =
        runTest {
            val loadedAt = Instant.parse("2026-10-20T09:00:00Z")
            source.loadResult = TaskResult.Offline
            source.cachedTask = CachedTask(task, loadedAt)
            val vm = viewModel()
            collect(vm)
            val state = vm.uiState.value
            assertTrue(state.offline)
            assertTrue(state.readOnly)
            assertEquals(task, state.detail?.task)
            assertEquals(loadedAt, state.cachedAt)
            vm.startEditing()
            vm.complete()
            vm.delete()
            vm.toggleChecklistItem(tick)
            assertNull(vm.uiState.value.draft)
            assertTrue(source.calls.isEmpty())
            assertTrue(source.itemPatches.isEmpty())
        }

    @Test
    fun `offline without any copy shows the load error, a later reload shows the task`() =
        runTest {
            source.loadResult = TaskResult.Offline
            val vm = viewModel()
            collect(vm)
            assertTrue(vm.uiState.value.loadFailed)
            source.loadResult = TaskResult.Ok(detail)
            vm.reload()
            assertFalse(vm.uiState.value.loadFailed)
            assertFalse(vm.uiState.value.offline)
            assertEquals(detail, vm.uiState.value.detail)
        }

    @Test
    fun `a task gone elsewhere shows not found`() =
        runTest {
            source.loadResult = TaskResult.NotFound
            val vm = viewModel()
            collect(vm)
            assertTrue(vm.uiState.value.notFound)
            assertTrue(vm.uiState.value.readOnly)
        }

    @Test
    fun `an offline save keeps the form and says so`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.startEditing()
            vm.edit { it.copy(title = "Later") }
            source.updateResult = TaskResult.Offline
            vm.save()
            assertEquals(DetailMessage.Offline, vm.uiState.value.message)
            assertEquals(
                "Later",
                vm.uiState.value.draft
                    ?.title,
            )
            assertEquals(0, source.changes)
        }
}
