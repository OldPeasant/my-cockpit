package ch.sonensei.cockpit.calendar.edit

import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.calendar.agenda.CalendarResult
import ch.sonensei.cockpit.calendar.agenda.Occurrence
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
import java.time.ZoneId
import java.util.UUID

/** Answering the invitation to the edited event: when it can be answered, the current answer, and the server's reply. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventEditorViewModelRespondTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<EventEditRepository>()
    private val extras = mockk<EventExtrasRepository>()
    private val zone = ZoneId.of("Europe/Zurich")
    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:20:00Z"), zone)

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(90), "Work", null, true, false, 3)
    private val google = CalendarInfo(id(91), "Google", null, true, false, 4)
    private val calendars = listOf(work, google)

    private val ada = Attendee("ada@example.org", "Ada Example", AttendeeStatus.ACCEPTED)
    private val me = Attendee("Me@Example.org", "Me", AttendeeStatus.NEEDS_ACTION)

    private fun occurrence(
        n: Int,
        calendar: CalendarInfo,
    ) = Occurrence(
        id(n),
        calendar.id,
        "Planning",
        null,
        false,
        Instant.parse("2026-10-22T08:00:00Z"),
        Instant.parse("2026-10-22T09:00:00Z"),
        null,
        null,
        version = 4,
    )

    private val synced = occurrence(2, google)
    private val local = occurrence(1, work)

    private fun details(
        occurrence: Occurrence,
        attendees: List<Attendee>,
        version: Long = occurrence.version,
    ) = EventDetails(
        id = occurrence.eventId,
        calendarId = occurrence.calendarId,
        title = occurrence.title,
        location = null,
        description = null,
        allDay = false,
        start = occurrence.start,
        end = occurrence.end,
        startDate = null,
        endDate = null,
        timeZone = "Europe/Zurich",
        rrule = null,
        seriesId = null,
        version = version,
        reminderMinutes = emptyList(),
        attendees = attendees,
    )

    private fun viewModel() = EventEditorViewModel(repo, clock, MutableStateFlow(null), extras)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { extras.localCalendars() } returns CalendarResult.Ok(setOf(work.id))
        coEvery { extras.links(any()) } returns CalendarResult.Ok(emptyList())
        coEvery { extras.invitationCalendars() } returns CalendarResult.Ok(setOf(google.id))
        coEvery { extras.ownAddresses() } returns CalendarResult.Ok(setOf("me@example.org"))
        coEvery { repo.event(synced.eventId) } returns CalendarResult.Ok(details(synced, listOf(ada, me)))
        coEvery { repo.event(local.eventId) } returns CalendarResult.Ok(details(local, listOf(ada, me)))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.editing(occurrence: Occurrence = synced): EventEditorViewModel {
        val vm = viewModel()
        vm.edit(occurrence, calendars)
        advanceUntilIdle()
        return vm
    }

    private fun accepted(version: Long = 5) = details(synced, listOf(ada, me.copy(status = AttendeeStatus.ACCEPTED)), version)

    // --- When an invitation can be answered

    @Test
    fun `an existing event with guests in a Google or Microsoft calendar can be answered, the own guest shows the answer`() =
        runTest(dispatcher) {
            val state = editing().uiState.value
            assertEquals(setOf(google.id), state.invitationCalendars)
            assertEquals(setOf("me@example.org"), state.ownAddresses)
            assertTrue(state.respondable)
            assertTrue(state.canRespond)
            assertEquals("the address matches regardless of case", AttendeeStatus.NEEDS_ACTION, state.ownStatus)
        }

    @Test
    fun `a local event, one without guests or a new one can't be answered`() =
        runTest(dispatcher) {
            assertFalse(editing(local).uiState.value.respondable)
            assertFalse(editing(local).uiState.value.canRespond)

            coEvery { repo.event(synced.eventId) } returns CalendarResult.Ok(details(synced, emptyList()))
            assertFalse(editing().uiState.value.respondable)

            val fresh = viewModel()
            fresh.create(LocalDate.parse("2026-10-22"), calendars)
            advanceUntilIdle()
            assertFalse(fresh.uiState.value.respondable)
            // Only the three edited events asked which calendars answer invitations, the new one did not.
            coVerify(exactly = 3) { extras.invitationCalendars() }
        }

    @Test
    fun `while the invitation calendars are unknown or failed to load nothing can be answered`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<CalendarResult<Set<UUID>>>()
            coEvery { extras.invitationCalendars() } coAnswers { pending.await() }
            val vm = editing()
            assertNull(vm.uiState.value.invitationCalendars)
            assertFalse(vm.uiState.value.respondable)
            vm.respond(AttendeeStatus.ACCEPTED)
            pending.complete(CalendarResult.Offline)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.respondable)
            coVerify(exactly = 0) { repo.respond(any(), any(), any()) }
        }

    @Test
    fun `a retry after the sources failed to load fetches them again, so the invitation can be answered`() =
        runTest(dispatcher) {
            coEvery { extras.invitationCalendars() } returns CalendarResult.Offline andThen CalendarResult.Ok(setOf(google.id))
            coEvery { extras.ownAddresses() } returns CalendarResult.Offline andThen CalendarResult.Ok(setOf("me@example.org"))
            val vm = editing()
            assertFalse(vm.uiState.value.respondable)
            vm.reload()
            advanceUntilIdle()
            val state = vm.uiState.value
            assertTrue(state.respondable)
            assertEquals(AttendeeStatus.NEEDS_ACTION, state.ownStatus)
        }

    @Test
    fun `a reload of a new event neither fetches the sources again nor drops the form`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.create(LocalDate.parse("2026-10-22"), calendars)
            advanceUntilIdle()
            vm.update { it.copy(title = "Team lunch") }
            vm.reload()
            advanceUntilIdle()
            val state = vm.uiState.value
            assertEquals("Team lunch", state.form?.title)
            assertFalse(state.respondable)
            coVerify(exactly = 1) { extras.localCalendars() }
            coVerify(exactly = 0) { extras.invitationCalendars() }
            coVerify(exactly = 0) { repo.event(any()) }
        }

    @Test
    fun `without a matching own address there is no current answer, but the invitation can still be answered`() =
        runTest(dispatcher) {
            coEvery { extras.ownAddresses() } returns CalendarResult.Offline
            val state = editing().uiState.value
            assertEquals(emptySet<String>(), state.ownAddresses)
            assertNull(state.ownStatus)
            assertTrue(state.canRespond)
        }

    // --- Sending an answer

    @Test
    fun `an answer is sent with the event version, then the guests and version are updated and unsaved changes stay`() =
        runTest(dispatcher) {
            coEvery { repo.respond(synced.eventId, 4, AttendeeStatus.ACCEPTED) } returns CalendarResult.Ok(accepted(5))
            coEvery { repo.respond(synced.eventId, 5, AttendeeStatus.DECLINED) } returns
                CalendarResult.Ok(details(synced, listOf(ada, me.copy(status = AttendeeStatus.DECLINED)), 6))
            val vm = editing()
            vm.update { it.copy(title = "Planning (draft)") }
            vm.respond(AttendeeStatus.ACCEPTED)
            advanceUntilIdle()
            var state = vm.uiState.value
            assertNull(state.responding)
            assertNull(state.message)
            assertEquals(AttendeeStatus.ACCEPTED, state.ownStatus)
            assertEquals(listOf(ada, me.copy(status = AttendeeStatus.ACCEPTED)), state.form!!.attendees)
            assertEquals(listOf(ada.email, me.email), state.savedAttendees)
            assertEquals("Planning (draft)", state.form!!.title)

            // The next answer uses the new version.
            vm.respond(AttendeeStatus.DECLINED)
            advanceUntilIdle()
            state = vm.uiState.value
            assertEquals(AttendeeStatus.DECLINED, state.ownStatus)
            coVerify(exactly = 1) { repo.respond(synced.eventId, 5, AttendeeStatus.DECLINED) }
        }

    @Test
    fun `while an answer is sent nothing else can be answered or saved`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<CalendarResult<EventDetails>>()
            coEvery { repo.respond(any(), any(), any()) } coAnswers { pending.await() }
            val vm = editing()
            vm.respond(AttendeeStatus.TENTATIVE)
            runCurrent()
            var state = vm.uiState.value
            assertEquals(AttendeeStatus.TENTATIVE, state.responding)
            assertFalse(state.canRespond)
            assertFalse(state.canSave)
            assertFalse(state.canDelete)
            vm.respond(AttendeeStatus.DECLINED)
            runCurrent()
            coVerify(exactly = 1) { repo.respond(any(), any(), any()) }

            pending.complete(CalendarResult.Ok(accepted()))
            advanceUntilIdle()
            state = vm.uiState.value
            assertNull(state.responding)
            assertTrue(state.canRespond)
        }

    @Test
    fun `only accept, maybe and decline are sent`() =
        runTest(dispatcher) {
            val vm = editing()
            vm.respond(AttendeeStatus.NEEDS_ACTION)
            vm.respond(AttendeeStatus.DELEGATED)
            advanceUntilIdle()
            assertNull(vm.uiState.value.responding)
            coVerify(exactly = 0) { repo.respond(any(), any(), any()) }
        }

    @Test
    fun `no connection shows offline and the answer can be sent again`() =
        runTest(dispatcher) {
            coEvery { repo.respond(any(), any(), any()) } returns CalendarResult.Offline andThen CalendarResult.Ok(accepted())
            val vm = editing()
            vm.respond(AttendeeStatus.ACCEPTED)
            advanceUntilIdle()
            var state = vm.uiState.value
            assertEquals(EditorMessage.OFFLINE, state.message)
            assertNull(state.responding)
            assertEquals(AttendeeStatus.NEEDS_ACTION, state.ownStatus)
            assertTrue(state.canRespond)

            vm.respond(AttendeeStatus.ACCEPTED)
            advanceUntilIdle()
            state = vm.uiState.value
            assertNull(state.message)
            assertEquals(AttendeeStatus.ACCEPTED, state.ownStatus)
        }

    @Test
    fun `a stale version is a conflict that blocks further answers and saves`() =
        runTest(dispatcher) {
            coEvery { repo.respond(any(), any(), any()) } returns CalendarResult.Failed(412)
            val vm = editing()
            vm.respond(AttendeeStatus.ACCEPTED)
            advanceUntilIdle()
            val state = vm.uiState.value
            assertEquals(EditorMessage.CONFLICT, state.message)
            assertNull(state.responding)
            assertFalse(state.canRespond)
            assertFalse(state.canSave)
            assertEquals(AttendeeStatus.NEEDS_ACTION, state.ownStatus)
        }

    @Test
    fun `a missing event is gone`() =
        runTest(dispatcher) {
            coEvery { repo.respond(any(), any(), any()) } returns CalendarResult.Failed(404)
            val vm = editing()
            vm.respond(AttendeeStatus.DECLINED)
            advanceUntilIdle()
            assertEquals(EditorMessage.GONE, vm.uiState.value.message)
            assertFalse(vm.uiState.value.canRespond)
        }

    @Test
    fun `other refusals, like not invited or organizer, report that the answer failed and allow another try`() =
        runTest(dispatcher) {
            for (status in listOf(409, 422, null)) {
                coEvery { repo.respond(any(), any(), any()) } returns CalendarResult.Failed(status)
                val vm = editing()
                vm.respond(AttendeeStatus.ACCEPTED)
                advanceUntilIdle()
                val state = vm.uiState.value
                assertEquals("status $status", EditorMessage.RESPOND_FAILED, state.message)
                assertNull(state.responding)
                assertTrue(state.canRespond)
                assertTrue("a refused answer does not block saving", state.canSave)
            }
        }

    // --- Answers arriving late

    @Test
    fun `the answer to a closed editor never shows in the next event`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<CalendarResult<EventDetails>>()
            coEvery { repo.respond(any(), any(), any()) } coAnswers { pending.await() }
            val vm = editing()
            vm.respond(AttendeeStatus.ACCEPTED)
            runCurrent()
            vm.close()
            vm.edit(local, calendars)
            advanceUntilIdle()
            assertNull(vm.uiState.value.responding)

            pending.complete(CalendarResult.Ok(accepted()))
            advanceUntilIdle()
            val state = vm.uiState.value
            assertEquals(listOf(ada, me), state.form!!.attendees)
            assertNull(state.message)
        }

    @Test
    fun `a reload while an answer is sent drops the answer's result`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<CalendarResult<EventDetails>>()
            coEvery { repo.respond(any(), any(), any()) } coAnswers { pending.await() }
            val vm = editing()
            vm.respond(AttendeeStatus.ACCEPTED)
            runCurrent()
            vm.reload()
            advanceUntilIdle()
            assertNull(vm.uiState.value.responding)
            pending.complete(CalendarResult.Failed(412))
            advanceUntilIdle()
            assertNull(vm.uiState.value.message)
            assertTrue(vm.uiState.value.canRespond)
        }
}
