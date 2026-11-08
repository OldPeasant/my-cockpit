package ch.sonensei.cockpit.gtd.task

import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import java.time.LocalDate
import java.util.UUID

/** Saving the form's list, dates and repeat (a move, then a PATCH) and reordering the checklist. */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailViewModelRelistTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today: LocalDate = LocalDate.now()
    private val task = GtdTask(id(1), "Buy seeds", TaskList.NEXT, 0, version = 3, projectId = id(20))
    private val tomatoes = ChecklistItem(id(40), "Tomatoes", done = false, position = 0, version = 1)
    private val basil = ChecklistItem(id(41), "Basil", done = false, position = 1, version = 1)
    private val soil = ChecklistItem(id(42), "Soil", done = true, position = 2, version = 1)

    private var detail = TaskDetail(task, checklist = listOf(tomatoes, basil, soil))
    private lateinit var source: FakeTaskDetailSource

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(start: TaskDetail = detail): TaskDetailViewModel {
        source = FakeTaskDetailSource(start)
        val vm = TaskDetailViewModel(source, flowOf(emptyList()), flowOf(emptyList()), flowOf(null))
        vm.open(task.id)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private fun TestScope.editing(
        start: TaskDetail = detail,
        change: (TaskDraft) -> TaskDraft,
    ): TaskDetailViewModel =
        viewModel(start).also { vm ->
            vm.startEditing()
            vm.edit(change)
        }

    // --- save: move, then PATCH ---

    @Test
    fun `a new list and title move the task first, then patch the moved version`() =
        runTest {
            val tickler = today.plusDays(10)
            val vm = editing { it.onList(TaskList.SOMEDAY).copy(title = "Buy seeds in spring", ticklerDate = tickler) }
            vm.save()

            val (movedFrom, move) = source.relists.single()
            assertEquals(3L, movedFrom.version)
            assertEquals(TaskMove(TaskList.SOMEDAY, ticklerDate = tickler), move)
            val (patched, patch) = source.updates.single()
            // The PATCH goes to the task the move answered with (its new version), not the loaded one.
            assertEquals(4L, patched.version)
            assertEquals(TaskList.SOMEDAY, patched.list)
            assertEquals(JsonObject(mapOf("title" to JsonPrimitive("Buy seeds in spring"))), patch)

            val state = vm.uiState.value
            assertNull(state.draft)
            assertEquals(DetailMessage.Saved, state.message)
            assertEquals(TaskList.SOMEDAY, state.detail?.task?.list)
            assertEquals("Buy seeds in spring", state.detail?.task?.title)
            assertEquals(tickler, state.detail?.ticklerDate)
            assertFalse(state.busy)
            assertEquals(1, source.changes)
        }

    @Test
    fun `a move alone sends no PATCH`() =
        runTest {
            val date = today.plusDays(3)
            val vm = editing { it.scheduledOn(date) }
            vm.save()
            assertEquals(TaskMove(TaskList.SCHEDULED, scheduledDate = date), source.relists.single().second)
            assertTrue(source.updates.isEmpty())
            val state = vm.uiState.value
            assertEquals(TaskList.SCHEDULED, state.detail?.task?.list)
            assertEquals(date, state.detail?.task?.scheduledDate)
            assertEquals(DetailMessage.Saved, state.message)
            assertEquals(1, source.changes)
        }

    @Test
    fun `a change without a move only patches`() =
        runTest {
            val vm = editing { it.copy(repeat = RepeatDraft(RepeatPreset.MONTHLY, "2", RecurrenceMode.AFTER_COMPLETION)) }
            vm.save()
            assertTrue(source.relists.isEmpty())
            assertEquals(
                JsonObject(
                    mapOf(
                        "recurrenceRule" to JsonPrimitive("FREQ=MONTHLY;INTERVAL=2"),
                        "recurrenceMode" to JsonPrimitive("AFTER_COMPLETION"),
                    ),
                ),
                source.updates.single().second,
            )
            // The read-only view shows the new repeat at once.
            val shown = vm.uiState.value.detail
            assertEquals("FREQ=MONTHLY;INTERVAL=2", shown?.recurrenceRule)
            assertEquals(RecurrenceMode.AFTER_COMPLETION, shown?.recurrenceMode)
        }

    @Test
    fun `removing the repeat shows no repeat afterwards`() =
        runTest {
            val vm =
                editing(
                    detail.copy(recurrenceRule = "FREQ=DAILY", recurrenceMode = RecurrenceMode.FIXED),
                ) { it.copy(repeat = RepeatDraft()) }
            vm.save()
            assertEquals(
                2,
                source.updates
                    .single()
                    .second.size,
            )
            assertNull(
                vm.uiState.value.detail
                    ?.recurrenceRule,
            )
            assertNull(
                vm.uiState.value.detail
                    ?.recurrenceMode,
            )
        }

    @Test
    fun `an unchanged custom rule closes the form without a request`() =
        runTest {
            val vm = editing(detail.copy(recurrenceRule = "FREQ=WEEKLY;BYDAY=SA", recurrenceMode = RecurrenceMode.FIXED)) { it }
            vm.save()
            assertTrue(source.relists.isEmpty())
            assertTrue(source.updates.isEmpty())
            assertNull(vm.uiState.value.draft)
        }

    @Test
    fun `problems found before sending are shown and nothing is sent`() =
        runTest {
            val vm =
                editing {
                    it.onList(TaskList.WAITING).copy(ticklerDate = today, repeat = RepeatDraft(RepeatPreset.CUSTOM, custom = "BYDAY=MO"))
                }
            vm.save()
            assertTrue(source.relists.isEmpty())
            assertTrue(source.updates.isEmpty())
            assertEquals(
                mapOf(
                    TaskField.TICKLER to FieldProblem.NotFuture,
                    TaskField.WAITING_FOR to FieldProblem.Required,
                    TaskField.RECURRENCE to FieldProblem.Invalid,
                ),
                vm.uiState.value.problems,
            )
            // Typing the person clears only its problem.
            vm.edit { it.copy(waitingFor = "Anna Example") }
            assertEquals(setOf(TaskField.TICKLER, TaskField.RECURRENCE), vm.uiState.value.problems.keys)
        }

    @Test
    fun `choosing Scheduled without a date is refused at the scheduled field, a date clears it`() =
        runTest {
            val vm = editing { it.onList(TaskList.SCHEDULED) }
            vm.save()
            assertEquals(mapOf(TaskField.SCHEDULED to FieldProblem.Required), vm.uiState.value.problems)
            vm.edit { it.scheduledOn(today.plusDays(1)) }
            assertTrue(
                vm.uiState.value.problems
                    .isEmpty(),
            )
        }

    @Test
    fun `a move refused with field errors shows them at the fields and sends no PATCH`() =
        runTest {
            val vm = editing { it.onList(TaskList.SOMEDAY).copy(title = "Buy seeds later", ticklerDate = today.plusDays(2)) }
            source.relistResult = TaskResult.Invalid(mapOf("ticklerDate" to "must be in the future"), "Invalid move")
            vm.save()

            assertTrue(source.updates.isEmpty())
            val state = vm.uiState.value
            assertEquals(mapOf(TaskField.TICKLER to FieldProblem.Server("must be in the future")), state.problems)
            assertNull(state.message)
            assertFalse(state.busy)
            // The form stays with what was typed; the task is still the loaded one.
            assertEquals("Buy seeds later", state.draft?.title)
            assertEquals(task, state.detail?.task)
            assertEquals(0, source.changes)
        }

    @Test
    fun `a move refused at an unknown field also says so as a message`() =
        runTest {
            val vm = editing { it.onList(TaskList.INBOX) }
            source.relistResult = TaskResult.Invalid(mapOf("list" to "not allowed", "position" to "bad"), "Invalid move")
            vm.save()
            val state = vm.uiState.value
            assertEquals(mapOf(TaskField.LIST to FieldProblem.Server("not allowed")), state.problems)
            assertEquals(DetailMessage.Failed("Invalid move"), state.message)
        }

    @Test
    fun `a move refused offline keeps the form and says so`() =
        runTest {
            val vm = editing { it.onList(TaskList.SOMEDAY) }
            source.relistResult = TaskResult.Offline
            vm.save()
            val state = vm.uiState.value
            assertEquals(DetailMessage.Offline, state.message)
            assertEquals(TaskList.SOMEDAY, state.draft?.list)
            assertFalse(state.busy)
            assertTrue(source.updates.isEmpty())
        }

    @Test
    fun `a move of a task changed elsewhere reloads it instead of overwriting`() =
        runTest {
            val vm = editing { it.onList(TaskList.SOMEDAY) }
            source.relistResult = TaskResult.Conflict
            val newer = task.copy(title = "Buy seeds (changed)", version = 9)
            source.loadResult = TaskResult.Ok(detail.copy(task = newer))
            vm.save()
            val state = vm.uiState.value
            assertNull(state.draft)
            assertEquals(DetailMessage.Conflict, state.message)
            assertEquals(newer, state.detail?.task)
            assertTrue(source.updates.isEmpty())
        }

    @Test
    fun `a PATCH refused after the move keeps the moved task, so saving again does not move twice`() =
        runTest {
            val vm = editing { it.onList(TaskList.SOMEDAY).copy(title = "x".repeat(10)) }
            source.updateResult = TaskResult.Invalid(mapOf("title" to "already taken"), "Invalid task")
            vm.save()

            val state = vm.uiState.value
            assertEquals(1, source.relists.size)
            assertEquals(TaskList.SOMEDAY, state.detail?.task?.list)
            assertEquals(4L, state.detail?.task?.version)
            assertEquals(mapOf(TaskField.TITLE to FieldProblem.Server("already taken")), state.problems)
            assertEquals(TaskList.SOMEDAY, state.draft?.list)
            assertFalse(state.busy)
            // The lists are told about the move that went through.
            assertEquals(1, source.changes)

            source.updateResult = null
            vm.edit { it.copy(title = "Buy seeds in spring") }
            vm.save()
            assertEquals(1, source.relists.size)
            val (patched, patch) = source.updates.last()
            assertEquals(4L, patched.version)
            assertEquals(JsonObject(mapOf("title" to JsonPrimitive("Buy seeds in spring"))), patch)
            assertEquals(DetailMessage.Saved, vm.uiState.value.message)
        }

    @Test
    fun `moving to Waiting sends the person with the move and patches nothing more`() =
        runTest {
            val vm = editing { it.onList(TaskList.WAITING).copy(waitingFor = "  Anna Example ") }
            vm.save()
            assertEquals(TaskMove(TaskList.WAITING, waitingForText = "Anna Example"), source.relists.single().second)
            assertTrue(source.updates.isEmpty())
            assertEquals(
                "Anna Example",
                vm.uiState.value.detail
                    ?.task
                    ?.waitingForText,
            )
        }

    @Test
    fun `a waiting task's person and day are patched without a move`() =
        runTest {
            val waiting = task.copy(list = TaskList.WAITING, waitingForText = "Anna Example", waitingSince = today.minusDays(5))
            val vm = editing(detail.copy(task = waiting)) { it.copy(waitingFor = "Ben Example", waitingSince = today.minusDays(7)) }
            vm.save()
            assertTrue(source.relists.isEmpty())
            assertEquals(
                JsonObject(
                    mapOf(
                        "waitingForText" to JsonPrimitive("Ben Example"),
                        "waitingSince" to JsonPrimitive(today.minusDays(7).toString()),
                    ),
                ),
                source.updates.single().second,
            )
        }

    @Test
    fun `clearing the tickler of a Someday task resends the move without it`() =
        runTest {
            val someday = task.copy(list = TaskList.SOMEDAY)
            val vm = editing(detail.copy(task = someday, ticklerDate = today.plusDays(30))) { it.copy(ticklerDate = null) }
            vm.save()
            assertEquals(TaskMove(TaskList.SOMEDAY), source.relists.single().second)
            assertNull(
                vm.uiState.value.detail
                    ?.ticklerDate,
            )
        }

    @Test
    fun `server problems at the list, waiting-since and mode clear when their field changes`() =
        runTest {
            val waiting = task.copy(list = TaskList.WAITING, waitingForText = "Anna Example", waitingSince = today.minusDays(5))
            val vm = editing(detail.copy(task = waiting)) { it.copy(waitingSince = today.minusDays(6)) }
            source.updateResult =
                TaskResult.Invalid(
                    mapOf("list" to "bad list", "waitingSince" to "in the future", "recurrenceMode" to "needs a rule"),
                    "Invalid task",
                )
            vm.save()
            assertEquals(setOf(TaskField.LIST, TaskField.WAITING_SINCE, TaskField.RECURRENCE_MODE), vm.uiState.value.problems.keys)
            vm.edit { it.copy(waitingSince = today.minusDays(4)) }
            assertEquals(setOf(TaskField.LIST, TaskField.RECURRENCE_MODE), vm.uiState.value.problems.keys)
            vm.edit { it.copy(repeat = RepeatDraft(RepeatPreset.DAILY)) }
            assertEquals(setOf(TaskField.LIST), vm.uiState.value.problems.keys)
            // Changing the person does not touch the list's problem; changing the list does.
            vm.edit { it.copy(waitingFor = "Ben Example") }
            assertEquals(setOf(TaskField.LIST), vm.uiState.value.problems.keys)
            vm.edit { it.onList(TaskList.NEXT) }
            assertTrue(
                vm.uiState.value.problems
                    .isEmpty(),
            )
        }

    // --- moveChecklistItem ---

    @Test
    fun `moving an item saves the positions that changed as 0 to n`() =
        runTest {
            val vm = viewModel()
            vm.moveChecklistItem(soil, 0)
            assertEquals(
                listOf(soil to 0, tomatoes to 1, basil to 2),
                source.itemPatches.map { (item, patch) -> item to (patch["position"] as JsonPrimitive).content.toInt() },
            )
            source.itemPatches.forEach { (_, patch) -> assertEquals(setOf("position"), patch.keys) }
            assertEquals(1, source.checklistLoads)
        }

    @Test
    fun `moving down by one saves only the two swapped items`() =
        runTest {
            val vm = viewModel()
            vm.moveChecklistItem(tomatoes, 1)
            assertEquals(
                listOf(basil to 0, tomatoes to 1),
                source.itemPatches.map { (item, patch) -> item to (patch["position"] as JsonPrimitive).content.toInt() },
            )
        }

    @Test
    fun `items with gaps in their positions are renumbered`() =
        runTest {
            val gappy = listOf(tomatoes.copy(position = 0), basil.copy(position = 5), soil.copy(position = 9))
            val vm = viewModel(detail.copy(checklist = gappy))
            vm.moveChecklistItem(gappy[0], 1)
            assertEquals(
                listOf(gappy[1].id to 0, gappy[0].id to 1, gappy[2].id to 2),
                source.itemPatches.map { (item, patch) -> item.id to (patch["position"] as JsonPrimitive).content.toInt() },
            )
        }

    @Test
    fun `the new order shows at once and the server's checklist replaces it afterwards`() =
        runTest {
            val vm = viewModel()
            val serverOrder = listOf(basil.copy(position = 0, version = 2), tomatoes.copy(position = 1, version = 2), soil)
            source.checklistResult = TaskResult.Ok(serverOrder)
            vm.moveChecklistItem(basil, 0)
            assertEquals(
                serverOrder,
                vm.uiState.value.detail
                    ?.checklist,
            )
            assertFalse(vm.uiState.value.busy)
        }

    @Test
    fun `a move to the same place or outside the list's ends does nothing more than clamping`() =
        runTest {
            val vm = viewModel()
            vm.moveChecklistItem(basil, 1)
            vm.moveChecklistItem(tomatoes, -1)
            vm.moveChecklistItem(soil, 7)
            assertTrue(source.itemPatches.isEmpty())
            assertEquals(0, source.checklistLoads)
            // Beyond the end clamps to the last place.
            vm.moveChecklistItem(tomatoes, 99)
            assertEquals(listOf(basil.id, soil.id, tomatoes.id), source.itemPatches.map { it.first.id })
        }

    @Test
    fun `an unknown item is not moved`() =
        runTest {
            val vm = viewModel()
            vm.moveChecklistItem(ChecklistItem(id(49), "Not here", false, 0, 1), 2)
            assertTrue(source.itemPatches.isEmpty())
        }

    @Test
    fun `a refused position stops saving the rest, says why and shows the server's checklist`() =
        runTest {
            val vm = viewModel()
            source.itemResult = TaskResult.Conflict
            vm.moveChecklistItem(soil, 0)
            assertEquals(1, source.itemPatches.size)
            val state = vm.uiState.value
            assertEquals(DetailMessage.Conflict, state.message)
            // The checklist is the server's again (the fake answers with the loaded order).
            assertEquals(listOf(tomatoes, basil, soil), state.detail?.checklist)
            assertFalse(state.busy)
        }

    @Test
    fun `the checklist cannot be reordered offline`() =
        runTest {
            val vm = viewModel()
            source.loadResult = TaskResult.Offline
            vm.reload()
            assertTrue(vm.uiState.value.offline)
            vm.moveChecklistItem(soil, 0)
            assertTrue(source.itemPatches.isEmpty())
            assertEquals(
                listOf(tomatoes, basil, soil),
                vm.uiState.value.detail
                    ?.checklist,
            )
        }

    // --- reordered ---

    @Test
    fun `reordered moves an item and clamps the index`() {
        val items = listOf(tomatoes, basil, soil)
        assertEquals(listOf(basil, soil, tomatoes), reordered(items, tomatoes, 2))
        assertEquals(listOf(soil, tomatoes, basil), reordered(items, soil, -3))
        assertEquals(listOf(tomatoes, soil, basil), reordered(items, basil, 10))
        assertEquals(items, reordered(items, ChecklistItem(id(49), "Not here", false, 0, 1), 0))
        assertEquals(emptyList<ChecklistItem>(), reordered(emptyList(), tomatoes, 0))
    }
}
