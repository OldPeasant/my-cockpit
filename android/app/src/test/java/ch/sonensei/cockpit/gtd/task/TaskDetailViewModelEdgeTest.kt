package ch.sonensei.cockpit.gtd.task

import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import java.util.UUID

/** The task detail view model's refusals, guards and less common paths (the main flows: [TaskDetailViewModelTest]). */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailViewModelEdgeTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val garden = GtdProject(id(20), "Garden", ProjectStatus.ACTIVE, stalled = false, version = 1)
    private val archive = GtdProject(id(21), "Old archive", ProjectStatus.COMPLETED, stalled = false, version = 1)
    private val task = GtdTask(id(1), "Buy seeds", TaskList.NEXT, 0, version = 3, projectId = garden.id, timeEstimateMin = 15)
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

    private fun viewModel(open: UUID? = task.id) =
        TaskDetailViewModel(source, flowOf(listOf(garden, archive)), flowOf(emptyList()), flowOf(null)).also { vm ->
            open?.let { vm.open(it) }
        }

    private fun TestScope.collect(vm: TaskDetailViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    private fun TestScope.editing(change: (TaskDraft) -> TaskDraft): TaskDetailViewModel {
        val vm = viewModel()
        collect(vm)
        vm.startEditing()
        vm.edit(change)
        return vm
    }

    // --- loading ---

    @Test
    fun `opening the same task again does not reload it, another task starts afresh`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.open(task.id)
            assertEquals(1, source.loads)
            val other = task.copy(id = id(2), title = "Water the plants")
            source.loadResult = TaskResult.Ok(TaskDetail(other))
            vm.open(other.id)
            assertEquals(2, source.loads)
            assertEquals("Water the plants", vm.uiState.value.detail?.task?.title)
        }

    @Test
    fun `a task in a finished project still shows its project`() =
        runTest {
            source.loadResult = TaskResult.Ok(TaskDetail(task.copy(projectId = archive.id)))
            val vm = viewModel()
            collect(vm)
            assertEquals(listOf(garden, archive), vm.uiState.value.projects)
        }

    @Test
    fun `a refused load without a copy shows the load error with a message`() =
        runTest {
            source.loadResult = TaskResult.Failed(500)
            val vm = viewModel()
            collect(vm)
            val state = vm.uiState.value
            assertFalse(state.loading)
            assertTrue(state.loadFailed)
            assertNull(state.detail)
            assertEquals(DetailMessage.Failed(), state.message)
        }

    @Test
    fun `going offline after the task was loaded keeps the loaded task read-only`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            source.loadResult = TaskResult.Offline
            vm.reload()
            val state = vm.uiState.value
            assertTrue(state.offline)
            assertTrue(state.readOnly)
            assertFalse(state.loadFailed)
            assertEquals(detail, state.detail)
        }

    @Test
    fun `retrying from the offline copy leaves offline once the server answers`() =
        runTest {
            source.loadResult = TaskResult.Offline
            source.cachedTask = CachedTask(task, null)
            val vm = viewModel()
            collect(vm)
            assertTrue(vm.uiState.value.offline)
            assertTrue(
                vm.uiState.value.detail!!
                    .checklist
                    .isEmpty(),
            )
            source.loadResult = TaskResult.Ok(detail)
            vm.reload()
            assertFalse(vm.uiState.value.offline)
            assertFalse(vm.uiState.value.readOnly)
            assertEquals(detail, vm.uiState.value.detail)
        }

    // --- editing ---

    @Test
    fun `a completed task cannot be edited`() =
        runTest {
            source.loadResult = TaskResult.Ok(detail.copy(completedAt = Instant.parse("2026-10-20T10:00:00Z")))
            val vm = viewModel()
            collect(vm)
            vm.startEditing()
            assertFalse(vm.uiState.value.editing)
        }

    @Test
    fun `cancel drops the form and its problems without a request`() =
        runTest {
            val vm = editing { it.copy(title = " ") }
            vm.save()
            assertTrue(vm.uiState.value.problems.isNotEmpty())
            vm.cancelEditing()
            assertNull(vm.uiState.value.draft)
            assertTrue(vm.uiState.value.problems.isEmpty())
            assertTrue(source.updates.isEmpty())
            assertEquals("Buy seeds", vm.uiState.value.detail?.task?.title)
        }

    @Test
    fun `edits outside the form are ignored`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.edit { it.copy(title = "Sneaky") }
            vm.save()
            assertNull(vm.uiState.value.draft)
            assertTrue(source.updates.isEmpty())
        }

    @Test
    fun `server problems at known and unknown fields show at the field and as a message`() =
        runTest {
            val vm = editing { it.copy(title = "Buy seeds", estimate = "30") }
            source.updateResult = TaskResult.Invalid(mapOf("timeEstimateMin" to "too long", "position" to "bad"), "Invalid task")
            vm.save()
            val state = vm.uiState.value
            assertEquals(mapOf(TaskField.ESTIMATE to FieldProblem.Server("too long")), state.problems)
            assertEquals(DetailMessage.Failed("Invalid task"), state.message)
            assertTrue(state.editing)
            assertFalse(state.busy)
        }

    @Test
    fun `problem details without fields are shown as a message and the form stays`() =
        runTest {
            val vm = editing { it.copy(title = "Buy seeds and soil") }
            source.updateResult = TaskResult.Invalid(emptyMap(), "Task is read-only")
            vm.save()
            assertEquals(DetailMessage.Failed("Task is read-only"), vm.uiState.value.message)
            assertTrue(vm.uiState.value.problems.isEmpty())
            assertEquals(
                "Buy seeds and soil",
                vm.uiState.value.draft
                    ?.title,
            )
        }

    @Test
    fun `changing a field clears only that field's server problem`() =
        runTest {
            val vm = editing { it.copy(title = "Buy seeds and soil", estimate = "30") }
            source.updateResult =
                TaskResult.Invalid(mapOf("title" to "already used", "timeEstimateMin" to "too long"), "Invalid task")
            vm.save()
            assertEquals(setOf(TaskField.TITLE, TaskField.ESTIMATE), vm.uiState.value.problems.keys)
            vm.edit { it.copy(estimate = "20") }
            assertEquals(setOf(TaskField.TITLE), vm.uiState.value.problems.keys)
        }

    @Test
    fun `saving a task trashed elsewhere shows not found and closes the form`() =
        runTest {
            val vm = editing { it.copy(title = "Buy seeds and soil") }
            source.updateResult = TaskResult.NotFound
            vm.save()
            val state = vm.uiState.value
            assertTrue(state.notFound)
            assertTrue(state.readOnly)
            assertNull(state.draft)
            assertEquals(DetailMessage.NotFound, state.message)
            assertFalse(state.busy)
            assertEquals(1, source.changes)
        }

    @Test
    fun `another refused save keeps the form and says it failed`() =
        runTest {
            val vm = editing { it.copy(title = "Buy seeds and soil") }
            source.updateResult = TaskResult.Failed(403)
            vm.save()
            assertEquals(DetailMessage.Failed(), vm.uiState.value.message)
            assertTrue(vm.uiState.value.editing)
            assertFalse(vm.uiState.value.busy)
            assertEquals(0, source.changes)
        }

    @Test
    fun `a second save while the first is sent is ignored`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val vm = viewModel()
            collect(vm)
            advanceUntilIdle()
            vm.startEditing()
            vm.edit { it.copy(title = "Buy seeds and soil") }
            vm.save()
            // The first save waits on the dispatcher: everything below arrives while it is sent.
            vm.save()
            vm.complete()
            vm.delete()
            advanceUntilIdle()
            assertEquals(1, source.updates.size)
            assertTrue(source.calls.isEmpty())
            assertFalse(vm.uiState.value.busy)
        }

    // --- actions ---

    @Test
    fun `completing a task changed elsewhere reloads it and says so`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            source.actionResult = TaskResult.Conflict
            source.loadResult = TaskResult.Ok(detail.copy(task = task.copy(title = "Buy seeds (theirs)", version = 4)))
            vm.complete()
            val state = vm.uiState.value
            assertEquals(DetailMessage.Conflict, state.message)
            assertEquals("Buy seeds (theirs)", state.detail?.task?.title)
            assertFalse(state.detail!!.completed)
            assertFalse(state.busy)
            assertEquals(1, source.changes)
        }

    @Test
    fun `moving while offline says so and changes nothing`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            source.moveResult = TaskResult.Offline
            vm.move(MoveTarget.SOMEDAY)
            assertEquals(Triple(task, MoveTarget.SOMEDAY, null), source.moves.single())
            assertEquals(DetailMessage.Offline, vm.uiState.value.message)
            assertEquals(TaskList.NEXT, vm.uiState.value.detail?.task?.list)
            assertFalse(vm.uiState.value.busy)
            assertEquals(0, source.changes)
        }

    @Test
    fun `moving to Next or Someday needs no person`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.move(MoveTarget.NEXT)
            vm.move(MoveTarget.SOMEDAY)
            assertEquals(listOf(MoveTarget.NEXT, MoveTarget.SOMEDAY), source.moves.map { it.second })
            assertEquals(DetailMessage.Moved(MoveTarget.SOMEDAY), vm.uiState.value.message)
            assertEquals(2, source.changes)
        }

    @Test
    fun `a delete refused offline keeps the task and offers no undo`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            source.actionResult = TaskResult.Offline
            vm.delete()
            assertFalse(vm.uiState.value.deleted)
            assertFalse(vm.uiState.value.readOnly)
            assertEquals(DetailMessage.Offline, vm.uiState.value.message)
            vm.undoDelete()
            assertEquals(listOf("delete"), source.calls)
        }

    @Test
    fun `deleting a task already gone shows not found`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            source.actionResult = TaskResult.NotFound
            vm.delete()
            assertTrue(vm.uiState.value.notFound)
            assertFalse(vm.uiState.value.deleted)
            assertEquals(DetailMessage.NotFound, vm.uiState.value.message)
        }

    @Test
    fun `an undo that cannot reach the server keeps the task deleted and says so`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.delete()
            source.restoreResult = TaskResult.Offline
            vm.undoDelete()
            assertTrue(vm.uiState.value.deleted)
            assertEquals(DetailMessage.Offline, vm.uiState.value.message)
            assertFalse(vm.uiState.value.busy)
            source.restoreResult = TaskResult.Failed(409)
            vm.undoDelete()
            assertEquals(DetailMessage.Failed(), vm.uiState.value.message)
            assertEquals(listOf("delete", "restore", "restore"), source.calls)
            assertEquals(1, source.changes)
        }

    @Test
    fun `undo restores the task's state, so it can be changed again`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.delete()
            vm.undoDelete()
            val state = vm.uiState.value
            assertFalse(state.readOnly)
            assertEquals(detail, state.detail)
            assertEquals(2, source.loads)
        }

    // --- checklist ---

    @Test
    fun `renaming to the same or a blank text sends nothing`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.renameChecklistItem(tick, " Tomatoes ")
            vm.renameChecklistItem(tick, "   ")
            assertTrue(source.itemPatches.isEmpty())
            assertEquals(0, source.checklistLoads)
        }

    @Test
    fun `unticking sends done false`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.toggleChecklistItem(tick.copy(done = true))
            assertEquals(JsonObject(mapOf("done" to JsonPrimitive(false))), source.itemPatches.single().second)
        }

    @Test
    fun `a refused new item shows the server's message and the checklist stays as loaded when it cannot be reloaded`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            source.itemResult = TaskResult.Invalid(mapOf("text" to "must be at most 500 characters"), "Invalid item")
            source.checklistResult = TaskResult.Offline
            vm.addChecklistItem("A very long item")
            assertEquals(DetailMessage.Failed("must be at most 500 characters"), vm.uiState.value.message)
            assertEquals(
                listOf(tick),
                vm.uiState.value.detail
                    ?.checklist,
            )
            assertFalse(vm.uiState.value.busy)
        }

    @Test
    fun `the checklist cannot be changed after the task was deleted`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.delete()
            vm.addChecklistItem("Basil")
            vm.deleteChecklistItem(tick)
            vm.renameChecklistItem(tick, "Cherry tomatoes")
            assertTrue(source.added.isEmpty())
            assertTrue(source.deletedItems.isEmpty())
            assertTrue(source.itemPatches.isEmpty())
        }

    // --- messages ---

    @Test
    fun `dismissing a message hides only that message`() =
        runTest {
            val vm = editing { it.copy(title = "Buy seeds and soil") }
            vm.save()
            vm.dismiss(DetailMessage.Offline)
            assertEquals(DetailMessage.Saved, vm.uiState.value.message)
            vm.dismiss(DetailMessage.Saved)
            assertNull(vm.uiState.value.message)
        }
}
