package ch.sonensei.cockpit.gtd.task

import ch.sonensei.cockpit.core.network.generated.models.ConvertToEventRequest
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/** "To reference" and "To calendar" on the task detail screen (SPEC.md §5.3): the dialog, the conversion and its refusals. */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailConvertViewModelTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val task = GtdTask(id(1), "Dentist appointment", TaskList.NEXT, 0, version = 3)
    private val detail = TaskDetail(task)
    private val source = FakeTaskDetailSource(detail)
    private val work = TargetCalendar(id(90), "Work")
    private val home = TargetCalendar(id(91), "Home", isDefault = true)
    private val note = ConvertedItem(ConvertedType.NOTE, id(70), "Dentist appointment")
    private val event = ConvertedItem(ConvertedType.EVENT, id(71), "Dentist appointment", LocalDate.of(2026, 10, 20))

    /** 10:15 in Zurich. */
    private val zurich = ZoneId.of("Europe/Zurich")
    private val clock = Clock.fixed(Instant.parse("2026-10-20T08:15:00Z"), zurich)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        source.calendarsResult = TaskResult.Ok(listOf(home, work))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(of: TaskDetailSource = source) =
        TaskDetailViewModel(of, flowOf(emptyList()), flowOf(emptyList()), flowOf(null), null, clock).also { it.open(task.id) }

    private fun TestScope.collect(vm: TaskDetailViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    // --- To reference ---

    @Test
    fun `to reference converts the task, makes the screen read-only and says where it went`() =
        runTest {
            source.convertResult = TaskResult.Ok(note)
            val vm = viewModel()
            collect(vm)

            vm.toReference()

            val state = vm.uiState.value
            assertEquals(listOf("toNote"), source.calls)
            assertEquals(note, state.converted)
            assertEquals(DetailMessage.Converted(note), state.message)
            assertFalse(state.busy)
            assertTrue(state.readOnly)
            assertEquals(1, source.changes)
        }

    @Test
    fun `a converted task cannot be converted again or edited`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.toReference()

            vm.toReference()
            vm.openToCalendar()
            vm.startEditing()

            assertEquals(listOf("toNote"), source.calls)
            assertNull(vm.uiState.value.calendarDialog)
            assertNull(vm.uiState.value.draft)
        }

    @Test
    fun `converting discards an unsaved edit`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.startEditing()
            vm.edit { it.copy(title = "Changed") }

            vm.toReference()

            assertNull(vm.uiState.value.draft)
            assertTrue(source.updates.isEmpty())
        }

    @Test
    fun `a task changed elsewhere is reloaded with the conflict message, nothing converted`() =
        runTest {
            source.convertResult = TaskResult.Conflict
            val vm = viewModel()
            collect(vm)
            val loads = source.loads

            vm.toReference()

            val state = vm.uiState.value
            assertNull(state.converted)
            assertEquals(DetailMessage.Conflict, state.message)
            assertEquals(loads + 1, source.loads)
            assertFalse(state.busy)
            assertFalse(state.readOnly)
        }

    @Test
    fun `a task gone elsewhere says so`() =
        runTest {
            source.convertResult = TaskResult.NotFound
            val vm = viewModel()
            collect(vm)

            vm.toReference()

            assertTrue(vm.uiState.value.notFound)
            assertEquals(DetailMessage.NotFound, vm.uiState.value.message)
            assertNull(vm.uiState.value.converted)
        }

    @Test
    fun `offline or refused conversions keep the task and explain why`() =
        runTest {
            val vm = viewModel()
            collect(vm)

            source.convertResult = TaskResult.Offline
            vm.toReference()
            assertEquals(DetailMessage.Offline, vm.uiState.value.message)

            source.convertResult = TaskResult.Failed(500)
            vm.toReference()
            assertEquals(DetailMessage.Failed(), vm.uiState.value.message)

            source.convertResult = TaskResult.Invalid(mapOf("title" to "too long"), null)
            vm.toReference()
            assertEquals(DetailMessage.Failed("too long"), vm.uiState.value.message)

            assertNull(vm.uiState.value.converted)
            assertFalse(vm.uiState.value.busy)
            assertEquals(0, source.changes)
        }

    @Test
    fun `a completed task cannot be converted`() =
        runTest {
            source.loadResult = TaskResult.Ok(detail.copy(completedAt = Instant.parse("2026-10-19T08:00:00Z")))
            val vm = viewModel()
            collect(vm)

            vm.toReference()
            vm.openToCalendar()

            assertTrue(source.calls.isEmpty())
            assertNull(vm.uiState.value.calendarDialog)
        }

    // --- To calendar ---

    @Test
    fun `the dialog starts at the next full hour in the default calendar once the calendars are loaded`() =
        runTest {
            val vm = viewModel()
            collect(vm)

            vm.openToCalendar()

            val dialog = vm.uiState.value.calendarDialog!!
            assertEquals(EventPlan(home.id, LocalDate.of(2026, 10, 20), LocalTime.of(11, 0)), dialog.plan)
            assertEquals(CalendarChoice(listOf(home, work)), dialog.choice)
            assertEquals(listOf("calendars"), source.calls)
        }

    @Test
    fun `while the calendars load the dialog has no calendar and cannot convert`() =
        runTest {
            val pending = CompletableDeferred<TaskResult<List<TargetCalendar>>>()
            val slow = slowCalendars(pending)
            val vm = viewModel(slow)
            collect(vm)

            vm.openToCalendar()
            assertEquals(
                CalendarChoice(),
                vm.uiState.value.calendarDialog
                    ?.choice,
            )
            assertNull(
                vm.uiState.value.calendarDialog
                    ?.plan
                    ?.calendarId,
            )
            vm.confirmToCalendar()
            assertTrue(source.eventRequests.isEmpty())

            pending.complete(TaskResult.Ok(listOf(work)))
            assertEquals(
                work.id,
                vm.uiState.value.calendarDialog
                    ?.plan
                    ?.calendarId,
            )
        }

    @Test
    fun `calendars that fail to load can be loaded again, keeping the plan`() =
        runTest {
            source.calendarsResult = TaskResult.Offline
            val vm = viewModel()
            collect(vm)
            vm.openToCalendar()
            assertEquals(
                CalendarChoice(failed = true),
                vm.uiState.value.calendarDialog
                    ?.choice,
            )
            vm.editPlan { it.copy(durationMin = 30) }

            source.calendarsResult = TaskResult.Ok(listOf(work, home))
            vm.retryCalendars()

            val dialog = vm.uiState.value.calendarDialog!!
            assertEquals(CalendarChoice(listOf(work, home)), dialog.choice)
            assertEquals(30, dialog.plan.durationMin)
            assertEquals(home.id, dialog.plan.calendarId)
            assertEquals(listOf("calendars", "calendars"), source.calls)
        }

    @Test
    fun `a calendar chosen before a reload is kept while it is still offered`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.openToCalendar()
            vm.editPlan { it.copy(calendarId = work.id) }

            vm.retryCalendars()

            assertEquals(
                work.id,
                vm.uiState.value.calendarDialog
                    ?.plan
                    ?.calendarId,
            )
        }

    @Test
    fun `without a writable calendar nothing can be converted`() =
        runTest {
            source.calendarsResult = TaskResult.Ok(emptyList())
            val vm = viewModel()
            collect(vm)
            vm.openToCalendar()
            vm.editPlan { it.copy(calendarId = work.id) }

            vm.confirmToCalendar()

            assertTrue(source.eventRequests.isEmpty())
            assertEquals(
                CalendarChoice(emptyList()),
                vm.uiState.value.calendarDialog
                    ?.choice,
            )
        }

    @Test
    fun `calendars answering after the dialog was cancelled open nothing`() =
        runTest {
            val pending = CompletableDeferred<TaskResult<List<TargetCalendar>>>()
            val vm = viewModel(slowCalendars(pending))
            collect(vm)
            vm.openToCalendar()

            vm.cancelToCalendar()
            pending.complete(TaskResult.Ok(listOf(work)))

            assertNull(vm.uiState.value.calendarDialog)
        }

    @Test
    fun `confirming converts with the edited plan in the clock's zone and closes the dialog`() =
        runTest {
            source.convertResult = TaskResult.Ok(event)
            val vm = viewModel()
            collect(vm)
            vm.openToCalendar()
            vm.editPlan { it.copy(calendarId = work.id, time = LocalTime.of(14, 0), durationMin = 90) }

            vm.confirmToCalendar()

            assertEquals(
                ConvertToEventRequest(
                    allDay = false,
                    calendarId = work.id,
                    start = OffsetDateTime.parse("2026-10-20T14:00:00+02:00"),
                    end = OffsetDateTime.parse("2026-10-20T15:30:00+02:00"),
                    timeZone = "Europe/Zurich",
                ),
                source.eventRequests.single(),
            )
            val state = vm.uiState.value
            assertNull(state.calendarDialog)
            assertEquals(event, state.converted)
            assertEquals(DetailMessage.Converted(event), state.message)
            assertEquals(1, source.changes)
        }

    @Test
    fun `an all-day plan converts to an all-day event`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.openToCalendar()
            vm.editPlan { it.copy(allDay = true) }

            vm.confirmToCalendar()

            val request = source.eventRequests.single()
            assertEquals(true, request.allDay)
            assertEquals(LocalDate.of(2026, 10, 20), request.startDate)
            assertEquals(LocalDate.of(2026, 10, 21), request.endDate)
            assertNull(request.start)
        }

    @Test
    fun `an invalid plan does not convert`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.openToCalendar()
            vm.editPlan { it.copy(durationMin = 0) }

            vm.confirmToCalendar()

            assertTrue(source.eventRequests.isEmpty())
        }

    @Test
    fun `an offline conversion keeps the dialog open to try again`() =
        runTest {
            source.convertResult = TaskResult.Offline
            val vm = viewModel()
            collect(vm)
            vm.openToCalendar()
            vm.editPlan { it.copy(durationMin = 30) }

            vm.confirmToCalendar()

            val state = vm.uiState.value
            assertEquals(30, state.calendarDialog?.plan?.durationMin)
            assertEquals(DetailMessage.Offline, state.message)
            assertFalse(state.busy)

            source.convertResult = TaskResult.Ok(event)
            vm.confirmToCalendar()
            assertEquals(2, source.eventRequests.size)
            assertNull(vm.uiState.value.calendarDialog)
            assertEquals(event, vm.uiState.value.converted)
        }

    @Test
    fun `a refusal the dialog cannot fix closes it`() =
        runTest {
            listOf<TaskResult<ConvertedItem>>(TaskResult.Conflict, TaskResult.Failed(403)).forEach { refusal ->
                source.convertResult = refusal
                val vm = viewModel()
                collect(vm)
                vm.openToCalendar()

                vm.confirmToCalendar()

                assertNull("$refusal", vm.uiState.value.calendarDialog)
                assertNull("$refusal", vm.uiState.value.converted)
            }
        }

    @Test
    fun `cancelling closes the dialog without converting, editing without a dialog changes nothing`() =
        runTest {
            val vm = viewModel()
            collect(vm)
            vm.openToCalendar()

            vm.cancelToCalendar()
            vm.editPlan { it.copy(durationMin = 15) }
            vm.confirmToCalendar()

            assertNull(vm.uiState.value.calendarDialog)
            assertTrue(source.eventRequests.isEmpty())
        }

    @Test
    fun `the dialog does not open while offline`() =
        runTest {
            source.loadResult = TaskResult.Offline
            source.cachedTask = CachedTask(task, loadedAt = null)
            val vm = viewModel()
            collect(vm)

            vm.openToCalendar()
            vm.toReference()

            assertNull(vm.uiState.value.calendarDialog)
            assertTrue(source.calls.isEmpty())
        }

    @Test
    fun `dismissing the converted message keeps the screen read-only`() =
        runTest {
            source.convertResult = TaskResult.Ok(note)
            val vm = viewModel()
            collect(vm)
            vm.toReference()

            vm.dismiss(DetailMessage.Converted(note))

            assertNull(vm.uiState.value.message)
            assertEquals(note, vm.uiState.value.converted)
            assertTrue(vm.uiState.value.readOnly)
        }

    @Test
    fun `a second tap while a conversion runs converts only once`() =
        runTest {
            val pending = CompletableDeferred<TaskResult<ConvertedItem>>()
            val slow =
                object : TaskDetailSource by source {
                    override suspend fun convertToNote(task: GtdTask): TaskResult<ConvertedItem> {
                        source.calls += "toNote"
                        return pending.await()
                    }

                    override suspend fun convertToEvent(
                        task: GtdTask,
                        request: ConvertToEventRequest,
                    ): TaskResult<ConvertedItem> {
                        source.calls += "toEvent"
                        return pending.await()
                    }
                }
            val vm = viewModel(slow)
            collect(vm)

            vm.toReference()
            assertTrue(vm.uiState.value.busy)
            vm.toReference()
            vm.openToCalendar()
            vm.confirmToCalendar()

            assertEquals(listOf("toNote"), source.calls)
            assertNull(vm.uiState.value.calendarDialog)

            pending.complete(TaskResult.Ok(note))
            assertEquals(note, vm.uiState.value.converted)
            assertFalse(vm.uiState.value.busy)
        }

    @Test
    fun `confirming the dialog twice while the request runs sends one event`() =
        runTest {
            val pending = CompletableDeferred<TaskResult<ConvertedItem>>()
            val slow =
                object : TaskDetailSource by source {
                    override suspend fun convertToEvent(
                        task: GtdTask,
                        request: ConvertToEventRequest,
                    ): TaskResult<ConvertedItem> {
                        source.eventRequests += request
                        return pending.await()
                    }
                }
            val vm = viewModel(slow)
            collect(vm)
            vm.openToCalendar()

            vm.confirmToCalendar()
            // The dialog stays open (disabled) until the server answered.
            assertTrue(vm.uiState.value.busy)
            assertEquals(
                home.id,
                vm.uiState.value.calendarDialog
                    ?.plan
                    ?.calendarId,
            )
            vm.confirmToCalendar()

            assertEquals(1, source.eventRequests.size)
            pending.complete(TaskResult.Ok(event))
            assertNull(vm.uiState.value.calendarDialog)
            assertEquals(event, vm.uiState.value.converted)
        }

    @Test
    fun `calendars of an earlier dialog answering last do not replace those of the reopened one`() =
        runTest {
            val first = CompletableDeferred<TaskResult<List<TargetCalendar>>>()
            val second = CompletableDeferred<TaskResult<List<TargetCalendar>>>()
            val answers = ArrayDeque(listOf(first, second))
            val slow =
                object : TaskDetailSource by source {
                    override suspend fun calendars(): TaskResult<List<TargetCalendar>> = answers.removeFirst().await()
                }
            val vm = viewModel(slow)
            collect(vm)
            vm.openToCalendar()
            vm.cancelToCalendar()
            vm.openToCalendar()

            second.complete(TaskResult.Ok(listOf(work)))
            first.complete(TaskResult.Offline)

            val dialog = vm.uiState.value.calendarDialog!!
            assertEquals(CalendarChoice(listOf(work)), dialog.choice)
            assertEquals(work.id, dialog.plan.calendarId)
        }

    /** [source], but the calendars answer only once [pending] completes. */
    private fun slowCalendars(pending: CompletableDeferred<TaskResult<List<TargetCalendar>>>): TaskDetailSource =
        object : TaskDetailSource by source {
            override suspend fun calendars(): TaskResult<List<TargetCalendar>> = pending.await()
        }
}
