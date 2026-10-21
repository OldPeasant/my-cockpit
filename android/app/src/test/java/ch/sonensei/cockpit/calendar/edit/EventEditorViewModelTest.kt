package ch.sonensei.cockpit.calendar.edit

import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.calendar.agenda.CalendarResult
import ch.sonensei.cockpit.calendar.agenda.Occurrence
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Creating, loading and saving events: validation, the scope prompt of a series and conflicts. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<EventEditRepository>()
    private val zone = ZoneId.of("Europe/Zurich")

    // Wednesday 21 October 2026, 10:20 in Zurich.
    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:20:00Z"), zone)
    private val today = LocalDate.parse("2026-10-21")

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

    private fun details(
        occurrence: Occurrence,
        rrule: String? = null,
        version: Long = occurrence.version,
    ) = EventDetails(
        id = occurrence.eventId,
        calendarId = occurrence.calendarId,
        title = occurrence.title,
        location = null,
        description = null,
        allDay = false,
        // A series master has the times of its first occurrence.
        start = Instant.parse("2026-10-02T07:00:00Z").takeIf { rrule != null } ?: occurrence.start,
        end = Instant.parse("2026-10-02T07:15:00Z").takeIf { rrule != null } ?: occurrence.end,
        startDate = null,
        endDate = null,
        timeZone = "Europe/Zurich",
        rrule = rrule,
        seriesId = null,
        version = version,
    )

    private fun viewModel() = EventEditorViewModel(repo, clock)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.event(single.eventId) } returns CalendarResult.Ok(details(single))
        coEvery { repo.event(weekly.eventId) } returns CalendarResult.Ok(details(weekly, rrule = "FREQ=WEEKLY"))
        // Unchanged since the view showed it.
        coEvery { repo.reread(weekly) } returns CalendarResult.Ok(weekly)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a new event starts at the next full hour today in the first writable calendar`() {
        val vm = viewModel()
        vm.create(today, calendars)
        val state = vm.uiState.value
        assertTrue(state.open && state.creating)
        val form = state.form!!
        assertEquals(work.id, form.calendarId)
        assertEquals(today to LocalTime.of(11, 0), form.startDate to form.startTime)
        assertEquals(LocalTime.of(12, 0), form.endTime)
        assertEquals(listOf(work), state.targetCalendars)
        assertEquals(LocalTime.of(9, 0), newForm(today.plusDays(1), clock.instant().atZone(zone), null).startTime)
    }

    @Test
    fun `an invalid form shows its errors and is not sent`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.create(today, calendars)
            assertEquals(emptySet<FormError>(), vm.uiState.value.errors)
            vm.update { it.copy(title = " ") }
            vm.save()
            advanceUntilIdle()
            assertEquals(setOf(FormError.TITLE_MISSING), vm.uiState.value.errors)
            assertTrue(vm.uiState.value.open)
            coVerify(exactly = 0) { repo.create(any()) }
        }

    @Test
    fun `a valid new event is created with one id across retries, then the editor closes`() =
        runTest(dispatcher) {
            val bodies = mutableListOf<JsonObject>()
            coEvery { repo.create(capture(bodies)) } returns CalendarResult.Offline andThen CalendarResult.Ok(details(single))
            val vm = viewModel()
            vm.create(today, calendars)
            vm.update { it.copy(title = "Lunch", repeat = Repeat.WEEKLY) }
            vm.save()
            advanceUntilIdle()
            assertEquals(EditorMessage.OFFLINE, vm.uiState.value.message)
            vm.save()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.open)
            assertEquals(2, bodies.size)
            assertEquals(bodies[0]["id"], bodies[1]["id"])
            assertEquals(JsonPrimitive("FREQ=WEEKLY"), bodies[1]["rrule"])
        }

    @Test
    fun `a single event is changed without a scope prompt`() =
        runTest(dispatcher) {
            val patch = slot<JsonObject>()
            coEvery { repo.update(single, 4, null, capture(patch)) } returns CalendarResult.Ok(details(single))
            val vm = viewModel()
            vm.edit(single, calendars)
            advanceUntilIdle()
            assertEquals(
                "Dentist",
                vm.uiState.value.form
                    ?.title,
            )
            assertFalse(vm.uiState.value.series)
            vm.update { it.copy(title = "Dentist (moved)") }
            vm.save()
            assertNull(vm.uiState.value.scopes)
            advanceUntilIdle()
            assertEquals(setOf("title"), patch.captured.keys)
            assertFalse(vm.uiState.value.open)
        }

    @Test
    fun `an unchanged form closes without a request`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.edit(single, calendars)
            advanceUntilIdle()
            vm.save()
            assertFalse(vm.uiState.value.open)
            coVerify(exactly = 0) { repo.update(any(), any(), any(), any()) }
        }

    @Test
    fun `a series occurrence shows its own times and asks THIS, FOLLOWING or ALL`() =
        runTest(dispatcher) {
            val patch = slot<JsonObject>()
            coEvery { repo.update(weekly, 7, EditScope.FOLLOWING, capture(patch)) } returns CalendarResult.Ok(details(weekly))
            val vm = viewModel()
            vm.edit(weekly, calendars)
            advanceUntilIdle()
            val form = vm.uiState.value.form!!
            assertEquals(LocalDate.parse("2026-10-23") to LocalTime.of(9, 0), form.startDate to form.startTime)
            assertEquals(Repeat.WEEKLY, form.repeat)
            assertTrue(vm.uiState.value.series)

            vm.update { it.copy(startTime = LocalTime.of(9, 30), endTime = LocalTime.of(9, 45)) }
            vm.save()
            assertEquals(EditScope.entries, vm.uiState.value.scopes)
            coVerify(exactly = 0) { repo.update(any(), any(), any(), any()) }

            vm.chooseScope(EditScope.FOLLOWING)
            assertNull(vm.uiState.value.scopes)
            advanceUntilIdle()
            assertEquals(JsonPrimitive("2026-10-23T07:30:00Z"), patch.captured["start"])
            assertFalse(vm.uiState.value.open)
        }

    @Test
    fun `a new repeat rule offers only FOLLOWING and ALL, and a cancelled prompt saves nothing`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.edit(weekly, calendars)
            advanceUntilIdle()
            vm.update { it.copy(repeat = Repeat.DAILY) }
            vm.save()
            assertEquals(listOf(EditScope.FOLLOWING, EditScope.ALL), vm.uiState.value.scopes)
            vm.chooseScope(EditScope.THIS)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.update(any(), any(), any(), any()) }

            vm.save()
            vm.dismissScope()
            advanceUntilIdle()
            assertNull(vm.uiState.value.scopes)
            assertTrue(vm.uiState.value.open)
            assertEquals(
                Repeat.DAILY,
                vm.uiState.value.form
                    ?.repeat,
            )
            coVerify(exactly = 0) { repo.update(any(), any(), any(), any()) }
        }

    @Test
    fun `a conflict blocks saving until the event is reloaded`() =
        runTest(dispatcher) {
            coEvery { repo.update(single, 4, null, any()) } returns CalendarResult.Failed(412)
            val vm = viewModel()
            vm.edit(single, calendars)
            advanceUntilIdle()
            vm.update { it.copy(title = "Changed here") }
            vm.save()
            advanceUntilIdle()
            assertEquals(EditorMessage.CONFLICT, vm.uiState.value.message)
            assertFalse(vm.uiState.value.canSave)

            coEvery { repo.event(single.eventId) } returns CalendarResult.Ok(details(single, version = 5).copy(title = "Changed there"))
            vm.reload()
            advanceUntilIdle()
            assertNull(vm.uiState.value.message)
            assertEquals(
                "Changed there",
                vm.uiState.value.form
                    ?.title,
            )
            assertTrue(vm.uiState.value.canSave)
        }

    @Test
    fun `an event of a read-only calendar can't be saved`() =
        runTest(dispatcher) {
            val holiday = single.copy(eventId = id(3), calendarId = feed.id)
            coEvery { repo.event(holiday.eventId) } returns CalendarResult.Ok(details(holiday))
            val vm = viewModel()
            vm.edit(holiday, calendars)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.readOnly)
            assertFalse(vm.uiState.value.canSave)
        }

    @Test
    fun `a generated occurrence moved since it was shown is edited at its current time`() =
        runTest(dispatcher) {
            val moved = weekly.copy(start = Instant.parse("2026-10-23T08:00:00Z"), end = Instant.parse("2026-10-23T08:15:00Z"))
            coEvery { repo.reread(weekly) } returns CalendarResult.Ok(moved)
            coEvery { repo.update(moved, 7, EditScope.THIS, any()) } returns CalendarResult.Ok(details(weekly))
            val vm = viewModel()
            vm.edit(weekly, calendars)
            advanceUntilIdle()
            val form = vm.uiState.value.form!!
            assertEquals(LocalTime.of(10, 0) to LocalTime.of(10, 15), form.startTime to form.endTime)

            vm.update { it.copy(title = "Standup (moved)") }
            vm.save()
            vm.chooseScope(EditScope.THIS)
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.update(moved, 7, EditScope.THIS, any()) }
            assertFalse(vm.uiState.value.open)
        }

    @Test
    fun `a generated occurrence that became an override is edited as that override`() =
        runTest(dispatcher) {
            val override =
                weekly.copy(
                    eventId = id(6),
                    title = "Standup (late)",
                    start = Instant.parse("2026-10-23T07:30:00Z"),
                    end = Instant.parse("2026-10-23T07:45:00Z"),
                    version = 2,
                )
            coEvery { repo.reread(weekly) } returns CalendarResult.Ok(override)
            coEvery { repo.event(override.eventId) } returns CalendarResult.Ok(details(override))
            coEvery { repo.update(override, 2, EditScope.ALL, any()) } returns CalendarResult.Ok(details(override))
            val vm = viewModel()
            vm.edit(weekly, calendars)
            advanceUntilIdle()
            val form = vm.uiState.value.form!!
            assertEquals("Standup (late)", form.title)
            assertEquals(LocalTime.of(9, 30), form.startTime)
            // The rule comes from the series master.
            assertEquals(Repeat.WEEKLY, form.repeat)
            assertTrue(vm.uiState.value.series)

            vm.update { it.copy(location = "Room 4") }
            vm.save()
            vm.chooseScope(EditScope.ALL)
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.update(override, 2, EditScope.ALL, any()) }
            coVerify(exactly = 0) { repo.update(weekly, any(), any(), any()) }
        }

    @Test
    fun `a generated occurrence its series no longer has says it is gone and shows no form`() =
        runTest(dispatcher) {
            coEvery { repo.reread(weekly) } returns CalendarResult.Ok(null)
            val vm = viewModel()
            vm.edit(weekly, calendars)
            advanceUntilIdle()
            val state = vm.uiState.value
            assertEquals(EditorMessage.GONE, state.message)
            assertNull(state.form)
            assertFalse(state.loading)
            assertFalse(state.canSave)
            coVerify(exactly = 0) { repo.event(any()) }

            vm.close()
            assertFalse(vm.uiState.value.open)
        }

    @Test
    fun `a generated occurrence that can't be read again fails the load`() =
        runTest(dispatcher) {
            coEvery { repo.reread(weekly) } returns CalendarResult.Offline andThen CalendarResult.Failed(400)
            val vm = viewModel()
            vm.edit(weekly, calendars)
            advanceUntilIdle()
            assertEquals(EditorMessage.LOAD_OFFLINE, vm.uiState.value.message)
            vm.reload()
            advanceUntilIdle()
            assertEquals(EditorMessage.LOAD_FAILED, vm.uiState.value.message)
            assertNull(vm.uiState.value.form)
            coVerify(exactly = 0) { repo.event(any()) }
        }

    @Test
    fun `a save answered with 404 says the event is gone and blocks saving`() =
        runTest(dispatcher) {
            coEvery { repo.update(single, 4, null, any()) } returns CalendarResult.Failed(404)
            val vm = viewModel()
            vm.edit(single, calendars)
            advanceUntilIdle()
            vm.update { it.copy(title = "Changed here") }
            vm.save()
            advanceUntilIdle()
            assertEquals(EditorMessage.GONE, vm.uiState.value.message)
            assertFalse(vm.uiState.value.saving)
            assertFalse(vm.uiState.value.canSave)
            vm.save()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.update(any(), any(), any(), any()) }
        }

    @Test
    fun `a custom rule stays a choice after a preset was picked, and choosing it again changes nothing`() =
        runTest(dispatcher) {
            coEvery { repo.event(weekly.eventId) } returns CalendarResult.Ok(details(weekly, rrule = "FREQ=WEEKLY;BYDAY=MO,WE"))
            val vm = viewModel()

            fun form() = vm.uiState.value.form!!
            vm.edit(weekly, calendars)
            advanceUntilIdle()
            assertEquals(Repeat.CUSTOM, form().repeat)
            assertEquals(REPEAT_PRESETS + Repeat.CUSTOM, vm.uiState.value.repeatChoices)

            vm.update { it.copy(repeat = Repeat.DAILY) }
            assertEquals(REPEAT_PRESETS + Repeat.CUSTOM, vm.uiState.value.repeatChoices)
            vm.update { it.copy(repeat = Repeat.CUSTOM) }
            assertEquals("FREQ=WEEKLY;BYDAY=MO,WE", form().rrule)
            vm.save()
            assertFalse(vm.uiState.value.open)
            coVerify(exactly = 0) { repo.update(any(), any(), any(), any()) }
        }

    @Test
    fun `a preset rule offers no custom choice`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.edit(weekly, calendars)
            advanceUntilIdle()
            assertEquals(REPEAT_PRESETS, vm.uiState.value.repeatChoices)
        }

    @Test
    fun `a load that fails is reported and can be retried`() =
        runTest(dispatcher) {
            coEvery { repo.event(single.eventId) } returns CalendarResult.Offline
            val vm = viewModel()
            vm.edit(single, calendars)
            advanceUntilIdle()
            assertEquals(EditorMessage.LOAD_OFFLINE, vm.uiState.value.message)
            assertNull(vm.uiState.value.form)
        }
}
