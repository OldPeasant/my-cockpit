package ch.sonensei.cockpit.gtd.inbox

import ch.sonensei.cockpit.core.network.generated.models.ConvertToEventRequest
import ch.sonensei.cockpit.core.settings.UserSettings
import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectRepository
import ch.sonensei.cockpit.gtd.data.RefreshResult
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.gtd.next.TaskCompletionRepository
import ch.sonensei.cockpit.gtd.task.CalendarChoice
import ch.sonensei.cockpit.gtd.task.ConvertedItem
import ch.sonensei.cockpit.gtd.task.ConvertedType
import ch.sonensei.cockpit.gtd.task.EventPlan
import ch.sonensei.cockpit.gtd.task.TargetCalendar
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

/** "To reference" and "To calendar" in Inbox processing (SPEC.md §5.3): the prompt, its calendars, and where the item went. */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxConvertViewModelTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val first = GtdTask(id(1), "Book the concert", TaskList.INBOX, 0, 1)
    private val second = GtdTask(id(2), "Renew passport", TaskList.INBOX, 1, 1)
    private val loadedAt = Instant.parse("2026-10-20T09:00:00Z")
    private val work = TargetCalendar(id(90), "Work")
    private val home = TargetCalendar(id(91), "Home", isDefault = true)
    private val note = ConvertedItem(ConvertedType.NOTE, id(70), "Book the concert")
    private val event = ConvertedItem(ConvertedType.EVENT, id(71), "Book the concert", LocalDate.of(2026, 10, 20))

    private val inboxFlow = MutableStateFlow(Cached(listOf(first, second), offline = false, refreshedAt = loadedAt))
    private val inbox = mockk<InboxRepository>()
    private val tasks = mockk<TaskRepository> { every { observe(TaskView.INBOX) } returns inboxFlow }
    private val projects = mockk<ProjectRepository> { every { observe() } returns MutableStateFlow(Cached(emptyList(), false, loadedAt)) }
    private val contexts = mockk<ContextRepository> { every { observe() } returns MutableStateFlow(Cached(emptyList(), false, loadedAt)) }
    private val completion = mockk<TaskCompletionRepository>()

    /** 10:15 in Zurich. */
    private val clock = Clock.fixed(Instant.parse("2026-10-20T08:15:00Z"), ZoneId.of("Europe/Zurich"))
    private var uptime = 0L

    /** The synced settings; no time zone known unless a test sets one. */
    private val settings = MutableStateFlow<UserSettings?>(null)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { inbox.process(any(), any(), any()) } returns ProcessResult.Processed
        coEvery { inbox.calendars() } returns listOf(home, work)
        coEvery { tasks.refresh(TaskView.INBOX) } returns RefreshResult.Refreshed
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend (InboxViewModel) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel =
                InboxViewModel(inbox, tasks, projects, contexts, completion, clock, settings) {
                    uptime += 1_000L
                    uptime
                }
            backgroundScope.launch { viewModel.uiState.collect {} }
            block(viewModel)
        }

    @Test
    fun `to reference runs at once and remembers where the item went`() =
        test { vm ->
            coEvery { inbox.process(any(), any(), InboxAction.ToReference) } returns ProcessResult.Converted(note)
            vm.startProcessing()
            vm.setEnergy(Energy.HIGH)

            vm.choose(first.id, InboxActionKind.REFERENCE)

            coVerify { inbox.process(first, any(), InboxAction.ToReference) }
            val state = vm.uiState.value
            assertEquals(note, state.converted)
            assertEquals(1, state.processed)
            assertNull(state.message)
            assertNull(state.prompt)
        }

    @Test
    fun `a refused conversion explains why and remembers nothing`() =
        test { vm ->
            coEvery { inbox.process(any(), any(), any()) } returns ProcessResult.Conflict
            vm.startProcessing()

            vm.choose(first.id, InboxActionKind.REFERENCE)

            assertEquals(InboxMessage.CONFLICT, vm.uiState.value.message)
            assertNull(vm.uiState.value.converted)
            assertEquals(0, vm.uiState.value.processed)
        }

    @Test
    fun `to calendar opens a prompt at the next full hour and loads the calendars, the default chosen`() =
        test { vm ->
            vm.startProcessing()

            vm.choose(first.id, InboxActionKind.CALENDAR)

            val prompt = vm.uiState.value.prompt!!
            assertEquals(InboxActionKind.CALENDAR, prompt.kind)
            assertEquals(EventPlan(home.id, LocalDate.of(2026, 10, 20), LocalTime.of(11, 0)), prompt.plan)
            assertEquals(CalendarChoice(listOf(home, work)), prompt.calendars)
            assertTrue(prompt.isValid(LocalDate.of(2026, 10, 20)))
            coVerify(exactly = 0) { inbox.process(any(), any(), any()) }
        }

    @Test
    fun `confirming to calendar sends the edited plan in the clock's zone`() =
        test { vm ->
            coEvery { inbox.process(any(), any(), any()) } returns ProcessResult.Converted(event)
            vm.startProcessing()
            vm.choose(first.id, InboxActionKind.CALENDAR)
            vm.updatePrompt { it.copy(plan = it.plan?.copy(calendarId = work.id, time = LocalTime.of(19, 30), durationMin = 120)) }

            vm.confirmPrompt(first.id)

            val expected =
                ConvertToEventRequest(
                    allDay = false,
                    calendarId = work.id,
                    start = OffsetDateTime.parse("2026-10-20T19:30:00+02:00"),
                    end = OffsetDateTime.parse("2026-10-20T21:30:00+02:00"),
                    timeZone = "Europe/Zurich",
                )
            coVerify { inbox.process(first, any(), InboxAction.ToCalendar(expected)) }
            assertEquals(event, vm.uiState.value.converted)
            assertNull(vm.uiState.value.prompt)
        }

    @Test
    fun `the synced time zone, not the device's, gives the default start and the event's zone`() =
        test { vm ->
            // 10:15 on the device in Zurich is 04:15 in New York, the account's zone.
            settings.value = UserSettings(timeZone = ZoneId.of("America/New_York"))
            vm.startProcessing()
            vm.choose(first.id, InboxActionKind.CALENDAR)
            assertEquals(
                LocalTime.of(5, 0),
                vm.uiState.value.prompt
                    ?.plan
                    ?.time,
            )
            vm.updatePrompt { it.copy(plan = it.plan?.copy(time = LocalTime.of(19, 30))) }

            vm.confirmPrompt(first.id)

            val expected =
                ConvertToEventRequest(
                    allDay = false,
                    calendarId = home.id,
                    start = OffsetDateTime.parse("2026-10-20T19:30:00-04:00"),
                    end = OffsetDateTime.parse("2026-10-20T20:30:00-04:00"),
                    timeZone = "America/New_York",
                )
            coVerify { inbox.process(first, any(), InboxAction.ToCalendar(expected)) }
        }

    @Test
    fun `calendars that fail to load block confirming until a retry loads them`() =
        test { vm ->
            coEvery { inbox.calendars() } returns null
            vm.startProcessing()
            vm.choose(first.id, InboxActionKind.CALENDAR)
            assertEquals(
                CalendarChoice(failed = true),
                vm.uiState.value.prompt
                    ?.calendars,
            )
            assertNull(
                vm.uiState.value.prompt
                    ?.plan
                    ?.calendarId,
            )

            vm.confirmPrompt(first.id)
            coVerify(exactly = 0) { inbox.process(any(), any(), any()) }

            coEvery { inbox.calendars() } returns listOf(work)
            vm.retryCalendars()

            assertEquals(
                CalendarChoice(listOf(work)),
                vm.uiState.value.prompt
                    ?.calendars,
            )
            assertEquals(
                work.id,
                vm.uiState.value.prompt
                    ?.plan
                    ?.calendarId,
            )
            vm.confirmPrompt(first.id)
            coVerify { inbox.process(first, any(), match { it is InboxAction.ToCalendar }) }
        }

    @Test
    fun `without a writable calendar the prompt cannot be confirmed`() =
        test { vm ->
            coEvery { inbox.calendars() } returns emptyList()
            vm.startProcessing()
            vm.choose(first.id, InboxActionKind.CALENDAR)
            vm.updatePrompt { it.copy(plan = it.plan?.copy(calendarId = work.id)) }

            vm.confirmPrompt(first.id)

            assertFalse(
                vm.uiState.value.prompt!!
                    .isValid(LocalDate.of(2026, 10, 20)),
            )
            coVerify(exactly = 0) { inbox.process(any(), any(), any()) }
        }

    @Test
    fun `calendars answering after the prompt was cancelled or changed are dropped`() =
        test { vm ->
            val pending = CompletableDeferred<List<TargetCalendar>?>()
            coEvery { inbox.calendars() } coAnswers { pending.await() }
            vm.startProcessing()
            vm.choose(first.id, InboxActionKind.CALENDAR)
            assertEquals(
                CalendarChoice(),
                vm.uiState.value.prompt
                    ?.calendars,
            )

            vm.cancelPrompt()
            vm.choose(first.id, InboxActionKind.WAITING)
            pending.complete(listOf(work))

            val prompt = vm.uiState.value.prompt!!
            assertEquals(InboxActionKind.WAITING, prompt.kind)
            assertEquals(CalendarChoice(), prompt.calendars)
            assertNull(prompt.plan)
        }

    @Test
    fun `calendars of an earlier prompt answering last do not replace those of the reopened one`() =
        test { vm ->
            val older = CompletableDeferred<List<TargetCalendar>?>()
            val newer = CompletableDeferred<List<TargetCalendar>?>()
            val answers = ArrayDeque(listOf(older, newer))
            coEvery { inbox.calendars() } coAnswers { answers.removeFirst().await() }
            vm.startProcessing()
            vm.choose(first.id, InboxActionKind.CALENDAR)
            vm.cancelPrompt()
            vm.choose(first.id, InboxActionKind.CALENDAR)

            newer.complete(listOf(work))
            older.complete(null)

            val prompt = vm.uiState.value.prompt!!
            assertEquals(CalendarChoice(listOf(work)), prompt.calendars)
            assertEquals(work.id, prompt.plan?.calendarId)
        }

    @Test
    fun `a double tap on To reference converts the item once`() =
        test { vm ->
            val pending = CompletableDeferred<ProcessResult>()
            coEvery { inbox.process(any(), any(), any()) } coAnswers { pending.await() }
            vm.startProcessing()

            vm.choose(first.id, InboxActionKind.REFERENCE)
            vm.choose(first.id, InboxActionKind.REFERENCE)
            pending.complete(ProcessResult.Converted(note))

            coVerify(exactly = 1) { inbox.process(any(), any(), any()) }
            assertEquals(note, vm.uiState.value.converted)
        }

    @Test
    fun `retrying without an open prompt loads nothing`() =
        test { vm ->
            vm.retryCalendars()

            coVerify(exactly = 0) { inbox.calendars() }
            assertNull(vm.uiState.value.prompt)
        }

    @Test
    fun `offline the calendar prompt does not run`() =
        test { vm ->
            vm.startProcessing()
            vm.choose(first.id, InboxActionKind.CALENDAR)
            inboxFlow.value = inboxFlow.value.copy(offline = true)

            vm.confirmPrompt(first.id)
            vm.choose(first.id, InboxActionKind.REFERENCE)

            coVerify(exactly = 0) { inbox.process(any(), any(), any()) }
        }

    @Test
    fun `dismissing the converted item clears it, an older one does not clear a newer one`() =
        test { vm ->
            coEvery { inbox.process(first, any(), any()) } returns ProcessResult.Converted(note)
            coEvery { inbox.process(second, any(), any()) } returns ProcessResult.Converted(event)
            vm.startProcessing()
            vm.choose(first.id, InboxActionKind.REFERENCE)
            inboxFlow.value = inboxFlow.value.copy(data = listOf(second))
            vm.choose(second.id, InboxActionKind.REFERENCE)
            assertEquals(event, vm.uiState.value.converted)

            vm.dismissConverted(note)
            assertEquals(event, vm.uiState.value.converted)

            vm.dismissConverted(event)
            assertNull(vm.uiState.value.converted)
        }

    @Test
    fun `an older conversion answering last does not replace where the newer one went`() =
        test { vm ->
            // The first item leaves the queue at once, so the second can be converted before the first one answers.
            val older = CompletableDeferred<ProcessResult>()
            val newer = CompletableDeferred<ProcessResult>()
            val passport = ConvertedItem(ConvertedType.EVENT, id(72), "Renew passport", LocalDate.of(2026, 10, 22))
            coEvery { inbox.process(first, any(), any()) } coAnswers { older.await() }
            coEvery { inbox.process(second, any(), any()) } coAnswers { newer.await() }
            vm.startProcessing()
            vm.choose(first.id, InboxActionKind.REFERENCE)
            assertEquals(second, vm.uiState.value.current)
            vm.choose(second.id, InboxActionKind.REFERENCE)

            newer.complete(ProcessResult.Converted(passport))
            assertEquals(passport, vm.uiState.value.converted)
            older.complete(ProcessResult.Converted(note))

            assertEquals(passport, vm.uiState.value.converted)
        }

    @Test
    fun `a conversion answering after a newer plain action started is not shown, as on the web`() =
        test { vm ->
            val older = CompletableDeferred<ProcessResult>()
            coEvery { inbox.process(first, any(), any()) } coAnswers { older.await() }
            vm.startProcessing()
            vm.choose(first.id, InboxActionKind.REFERENCE)
            vm.choose(second.id, InboxActionKind.NEXT)
            assertEquals(1, vm.uiState.value.processed)

            older.complete(ProcessResult.Converted(note))

            // "Where it went" describes only the latest action; both items still count as processed.
            assertNull(vm.uiState.value.converted)
            assertEquals(2, vm.uiState.value.processed)
        }

    @Test
    fun `an older conversion answering after the newer one was refused shows nothing and keeps the refusal`() =
        test { vm ->
            val older = CompletableDeferred<ProcessResult>()
            val newer = CompletableDeferred<ProcessResult>()
            coEvery { inbox.process(first, any(), any()) } coAnswers { older.await() }
            coEvery { inbox.process(second, any(), any()) } coAnswers { newer.await() }
            vm.startProcessing()
            vm.choose(first.id, InboxActionKind.REFERENCE)
            vm.choose(second.id, InboxActionKind.REFERENCE)

            newer.complete(ProcessResult.Conflict)
            older.complete(ProcessResult.Converted(note))

            val state = vm.uiState.value
            assertNull(state.converted)
            assertEquals(InboxMessage.CONFLICT, state.message)
            assertEquals(1, state.processed)
        }

    @Test
    fun `a later plain action keeps the converted item until it was shown`() =
        test { vm ->
            coEvery { inbox.process(first, any(), any()) } returns ProcessResult.Converted(note)
            vm.startProcessing()
            vm.choose(first.id, InboxActionKind.REFERENCE)
            inboxFlow.value = inboxFlow.value.copy(data = listOf(second))

            vm.choose(second.id, InboxActionKind.NEXT)

            assertEquals(note, vm.uiState.value.converted)
            assertEquals(2, vm.uiState.value.processed)
        }

    @Test
    fun `a calendar prompt's action uses the plan, other prompts ignore it`() {
        val plan = EventPlan(work.id, LocalDate.of(2026, 10, 21), LocalTime.of(8, 0), allDay = true)
        val prompt =
            InboxPrompt(InboxActionKind.CALENDAR, LocalDate.of(2026, 10, 21), plan = plan, calendars = CalendarChoice(listOf(work)))

        assertEquals(InboxAction.ToCalendar(plan.request(ZoneId.of("UTC"))), prompt.action(ZoneId.of("UTC")))
        assertTrue(prompt.isValid(LocalDate.of(2026, 10, 20)))
        assertFalse(prompt.copy(calendars = CalendarChoice()).isValid(LocalDate.of(2026, 10, 20)))
        assertFalse(prompt.copy(plan = null).isValid(LocalDate.of(2026, 10, 20)))
        assertEquals(InboxAction.Tickler(LocalDate.of(2026, 10, 21)), prompt.copy(kind = InboxActionKind.TICKLER).action(ZoneId.of("UTC")))
    }
}
