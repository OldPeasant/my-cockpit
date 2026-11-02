package ch.sonensei.cockpit.calendar.edit

import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.calendar.agenda.CalendarResult
import ch.sonensei.cockpit.calendar.agenda.Occurrence
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import java.time.ZoneId
import java.util.UUID

/** Deleting events: the confirmation of a single event, the scope prompt of a series, and failures. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventEditorViewModelDeleteTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<EventEditRepository>()
    private val zone = ZoneId.of("Europe/Zurich")
    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:20:00Z"), zone)

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val feed = CalendarInfo(id(89), "Holidays", null, true, true, 1)
    private val work = CalendarInfo(id(90), "Work", null, true, false, 3)
    private val calendars = listOf(feed, work)

    private val single =
        Occurrence(
            id(1),
            work.id,
            "Dentist",
            null,
            false,
            Instant.parse("2026-10-22T08:00:00Z"),
            Instant.parse("2026-10-22T09:00:00Z"),
            null,
            null,
            version = 4,
        )

    /** A generated occurrence of a weekly series (master [id] 2). */
    private val weekly =
        Occurrence(
            id(2),
            work.id,
            "Standup",
            null,
            false,
            Instant.parse("2026-10-23T07:00:00Z"),
            Instant.parse("2026-10-23T07:15:00Z"),
            null,
            null,
            seriesId = id(2),
            occurrence = "2026-10-23T07:00:00Z",
            version = 7,
        )

    private val feedEvent = single.copy(eventId = id(3), calendarId = feed.id)

    private fun details(
        occurrence: Occurrence,
        rrule: String? = null,
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
        rrule = rrule,
        seriesId = null,
        version = occurrence.version,
    )

    private fun viewModel() = EventEditorViewModel(repo, clock)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.event(single.eventId) } returns CalendarResult.Ok(details(single))
        coEvery { repo.event(weekly.eventId) } returns CalendarResult.Ok(details(weekly, rrule = "FREQ=WEEKLY"))
        coEvery { repo.event(feedEvent.eventId) } returns CalendarResult.Ok(details(feedEvent))
        coEvery { repo.reread(weekly) } returns CalendarResult.Ok(weekly)
        coEvery { repo.delete(any(), any(), any()) } returns CalendarResult.Ok(Unit)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a single event is deleted after a confirmation, then the editor closes`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.edit(single, calendars)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.canDelete)
            vm.delete()
            assertTrue(vm.uiState.value.confirmingDelete)
            assertNull(vm.uiState.value.scopes)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.delete(any(), any(), any()) }
            vm.confirmDelete()
            assertTrue(vm.uiState.value.saving)
            assertFalse(vm.uiState.value.confirmingDelete)
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.delete(single, 4, null) }
            assertFalse(vm.uiState.value.open)
        }

    @Test
    fun `a cancelled confirmation deletes nothing and keeps the form`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.edit(single, calendars)
            advanceUntilIdle()
            vm.delete()
            vm.dismissDelete()
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.delete(any(), any(), any()) }
            val state = vm.uiState.value
            assertTrue(state.open)
            assertFalse(state.confirmingDelete)
            assertTrue(state.canDelete)
            // A confirm after the cancel does nothing either.
            vm.confirmDelete()
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.delete(any(), any(), any()) }
        }

    @Test
    fun `a series occurrence asks which occurrences to delete and sends each scope`() =
        runTest(dispatcher) {
            for (scope in EditScope.entries) {
                val vm = viewModel()
                vm.edit(weekly, calendars)
                advanceUntilIdle()
                vm.delete()
                val asked = vm.uiState.value
                assertEquals(EditScope.entries.toList(), asked.scopes)
                assertTrue(asked.deleting)
                assertFalse(asked.confirmingDelete)
                vm.chooseScope(scope)
                advanceUntilIdle()
                coVerify(exactly = 1) { repo.delete(weekly, 7, scope) }
                assertFalse(vm.uiState.value.open)
            }
        }

    @Test
    fun `a cancelled scope prompt deletes nothing, and a later save asks for a change again`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.edit(weekly, calendars)
            advanceUntilIdle()
            vm.delete()
            vm.dismissScope()
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.delete(any(), any(), any()) }
            assertNull(vm.uiState.value.scopes)
            assertFalse(vm.uiState.value.deleting)
            assertTrue(vm.uiState.value.open)
            vm.update { it.copy(title = "Daily") }
            vm.save()
            assertFalse(vm.uiState.value.deleting)
            assertEquals(EditScope.entries.toList(), vm.uiState.value.scopes)
        }

    @Test
    fun `a delete answered with 412 says the event changed elsewhere and offers a reload`() =
        runTest(dispatcher) {
            coEvery { repo.delete(any(), any(), any()) } returns CalendarResult.Failed(412)
            val vm = viewModel()
            vm.edit(single, calendars)
            advanceUntilIdle()
            vm.delete()
            vm.confirmDelete()
            advanceUntilIdle()
            val state = vm.uiState.value
            assertTrue(state.open)
            assertEquals(EditorMessage.CONFLICT, state.message)
            assertFalse(state.canDelete)
            assertFalse(state.saving)
            vm.reload()
            advanceUntilIdle()
            assertNull(vm.uiState.value.message)
            assertTrue(vm.uiState.value.canDelete)
        }

    @Test
    fun `a delete answered with 404 says the event is gone`() =
        runTest(dispatcher) {
            coEvery { repo.delete(any(), any(), any()) } returns CalendarResult.Failed(404)
            val vm = viewModel()
            vm.edit(weekly, calendars)
            advanceUntilIdle()
            vm.delete()
            vm.chooseScope(EditScope.THIS)
            advanceUntilIdle()
            val state = vm.uiState.value
            assertTrue(state.open)
            assertEquals(EditorMessage.GONE, state.message)
            assertFalse(state.canDelete)
        }

    @Test
    fun `an offline delete says the server can't be reached and keeps the event`() =
        runTest(dispatcher) {
            coEvery { repo.delete(any(), any(), any()) } returns CalendarResult.Offline
            val vm = viewModel()
            vm.edit(single, calendars)
            advanceUntilIdle()
            vm.delete()
            vm.confirmDelete()
            advanceUntilIdle()
            val state = vm.uiState.value
            assertTrue(state.open)
            assertEquals(EditorMessage.OFFLINE, state.message)
            assertEquals("Dentist", state.form?.title)
            // A retry is possible.
            assertTrue(state.canDelete)
        }

    @Test
    fun `another refusal says the event could not be deleted`() =
        runTest(dispatcher) {
            coEvery { repo.delete(any(), any(), any()) } returns CalendarResult.Failed(400)
            val vm = viewModel()
            vm.edit(single, calendars)
            advanceUntilIdle()
            vm.delete()
            vm.confirmDelete()
            advanceUntilIdle()
            assertEquals(EditorMessage.DELETE_FAILED, vm.uiState.value.message)
        }

    @Test
    fun `new events, read-only calendars and a running delete offer no delete`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.create(LocalDate.parse("2026-10-21"), calendars)
            assertFalse(vm.uiState.value.canDelete)
            vm.delete()
            assertFalse(vm.uiState.value.confirmingDelete)

            vm.edit(feedEvent, calendars)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.readOnly)
            assertFalse(vm.uiState.value.canDelete)
            vm.delete()
            assertFalse(vm.uiState.value.confirmingDelete)

            vm.edit(single, calendars)
            assertFalse("not while loading", vm.uiState.value.canDelete)
            advanceUntilIdle()
            vm.delete()
            vm.confirmDelete()
            assertTrue(vm.uiState.value.saving)
            assertFalse(vm.uiState.value.canDelete)
            vm.delete()
            assertFalse(vm.uiState.value.confirmingDelete)
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.delete(any(), any(), any()) }
        }

    @Test
    fun `a generated occurrence that became an override is deleted as that override, with the chosen scope`() =
        runTest(dispatcher) {
            val override = weekly.copy(eventId = id(6), title = "Standup (late)", version = 2)
            coEvery { repo.reread(weekly) } returns CalendarResult.Ok(override)
            coEvery { repo.event(override.eventId) } returns CalendarResult.Ok(details(override))
            val vm = viewModel()
            vm.edit(weekly, calendars)
            advanceUntilIdle()
            vm.delete()
            assertEquals(EditScope.entries.toList(), vm.uiState.value.scopes)
            vm.chooseScope(EditScope.FOLLOWING)
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.delete(override, 2, EditScope.FOLLOWING) }
            coVerify(exactly = 0) { repo.delete(weekly, any(), any()) }
            assertFalse(vm.uiState.value.open)
        }

    @Test
    fun `a delete answered with 409 says the event changed elsewhere`() =
        runTest(dispatcher) {
            coEvery { repo.delete(any(), any(), any()) } returns CalendarResult.Failed(409)
            val vm = viewModel()
            vm.edit(weekly, calendars)
            advanceUntilIdle()
            vm.delete()
            vm.chooseScope(EditScope.ALL)
            advanceUntilIdle()
            val state = vm.uiState.value
            assertTrue(state.open)
            assertEquals(EditorMessage.CONFLICT, state.message)
            assertFalse(state.canDelete)
        }

    @Test
    fun `while the confirmation is shown a save does nothing, and the editor cannot be closed during the delete`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.edit(single, calendars)
            advanceUntilIdle()
            vm.update { it.copy(title = "Dentist (moved)") }
            vm.delete()
            vm.save()
            assertTrue(vm.uiState.value.confirmingDelete)
            assertFalse(vm.uiState.value.saving)
            vm.confirmDelete()
            vm.close()
            assertTrue("the delete may still be committed", vm.uiState.value.open)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.update(any(), any(), any(), any()) }
            coVerify(exactly = 1) { repo.delete(single, 4, null) }
            assertFalse(vm.uiState.value.open)
        }

    @Test
    fun `a scope chosen for a delete that was not offered deletes nothing`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.edit(weekly, calendars)
            advanceUntilIdle()
            // No prompt is open: a stray choice is ignored.
            vm.chooseScope(EditScope.ALL)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.delete(any(), any(), any()) }
            assertTrue(vm.uiState.value.open)
        }
}
