package ch.sonensei.cockpit.calendar.edit

import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.calendar.agenda.CalendarResult
import ch.sonensei.cockpit.calendar.agenda.Occurrence
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Overrides of a series, failures while loading and saving, the order of answers, guards of the scope prompt and the
 * calendar a new event lands in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventEditorViewModelEdgeCasesTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<EventEditRepository>()
    private val zone = ZoneId.of("Europe/Zurich")

    // Wednesday 21 October 2026, 10:20 in Zurich.
    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:20:00Z"), zone)
    private val today = LocalDate.parse("2026-10-21")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val feed = CalendarInfo(id(89), "Holidays", null, true, true, 1)
    private val work = CalendarInfo(id(90), "Work", null, true, false, 3)
    private val hidden = CalendarInfo(id(91), "Side project", null, false, false, 2)
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

    /** A moved occurrence of the weekly series [id] 2: an override event of its own ([id] 5). */
    private val override =
        Occurrence(
            id(5),
            work.id,
            "Standup",
            null,
            false,
            Instant.parse("2026-10-30T08:00:00Z"),
            Instant.parse("2026-10-30T08:15:00Z"),
            null,
            null,
            seriesId = id(2),
            occurrence = "2026-10-30T07:00:00Z",
            version = 3,
        )

    private fun details(
        id: UUID,
        title: String,
        start: String,
        end: String,
        rrule: String? = null,
        seriesId: UUID? = null,
        version: Long = 1,
        timeZone: String? = "Europe/Zurich",
    ) = EventDetails(
        id = id,
        calendarId = work.id,
        title = title,
        location = null,
        description = null,
        allDay = false,
        start = Instant.parse(start),
        end = Instant.parse(end),
        startDate = null,
        endDate = null,
        timeZone = timeZone,
        rrule = rrule,
        seriesId = seriesId,
        version = version,
    )

    private val singleDetails = details(id(1), "Dentist", "2026-10-22T08:00:00Z", "2026-10-22T09:00:00Z", version = 4)
    private val master = details(id(2), "Standup", "2026-10-02T07:00:00Z", "2026-10-02T07:15:00Z", rrule = "FREQ=WEEKLY", version = 7)
    private val overrideDetails =
        details(id(5), "Standup", "2026-10-30T08:00:00Z", "2026-10-30T08:15:00Z", seriesId = id(2), version = 3)

    private fun viewModel() = EventEditorViewModel(repo, clock)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.event(single.eventId) } returns CalendarResult.Ok(singleDetails)
        coEvery { repo.event(override.eventId) } returns CalendarResult.Ok(overrideDetails)
        coEvery { repo.event(id(2)) } returns CalendarResult.Ok(master)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an override shows its own times and the rule of its series, and is saved with its own version`() =
        runTest(dispatcher) {
            val patch = slot<JsonObject>()
            coEvery { repo.update(override, 3, EditScope.THIS, capture(patch)) } returns CalendarResult.Ok(overrideDetails)
            val vm = viewModel()
            vm.edit(override, calendars)
            advanceUntilIdle()
            val state = vm.uiState.value
            val form = state.form!!
            // 30 October is after the change to winter time: 08:00Z is 09:00 in Zurich.
            assertEquals(LocalDate.parse("2026-10-30") to LocalTime.of(9, 0), form.startDate to form.startTime)
            assertEquals(Repeat.WEEKLY, form.repeat)
            assertTrue(state.series)
            // An existing event stays in its calendar.
            assertEquals(listOf(work), state.targetCalendars)

            vm.update { it.copy(title = "Standup (moved)") }
            vm.save()
            assertEquals(EditScope.entries, vm.uiState.value.scopes)
            vm.chooseScope(EditScope.THIS)
            advanceUntilIdle()
            assertEquals(JsonObject(mapOf("title" to JsonPrimitive("Standup (moved)"))), patch.captured)
            coVerify(exactly = 1) { repo.update(override, 3, EditScope.THIS, any()) }
            assertFalse(vm.uiState.value.open)
        }

    @Test
    fun `a series that can't be loaded fails the load, which can be retried`() =
        runTest(dispatcher) {
            val answers = listOf(CalendarResult.Failed(404), CalendarResult.Offline, CalendarResult.Ok(master))
            coEvery { repo.event(id(2)) } returnsMany answers
            val vm = viewModel()
            vm.edit(override, calendars)
            advanceUntilIdle()
            assertEquals(EditorMessage.LOAD_FAILED, vm.uiState.value.message)
            assertNull(vm.uiState.value.form)
            assertFalse(vm.uiState.value.canSave)

            vm.reload()
            advanceUntilIdle()
            assertEquals(EditorMessage.LOAD_OFFLINE, vm.uiState.value.message)
            assertFalse(vm.uiState.value.loading)

            vm.reload()
            advanceUntilIdle()
            assertNull(vm.uiState.value.message)
            assertEquals(
                Repeat.WEEKLY,
                vm.uiState.value.form
                    ?.repeat,
            )
            assertTrue(vm.uiState.value.canSave)
        }

    @Test
    fun `a 409 is a conflict too`() =
        runTest(dispatcher) {
            coEvery { repo.update(single, 4, null, any()) } returns CalendarResult.Failed(409)
            val vm = viewModel()
            vm.edit(single, calendars)
            advanceUntilIdle()
            vm.update { it.copy(title = "Changed here") }
            vm.save()
            advanceUntilIdle()
            assertEquals(EditorMessage.CONFLICT, vm.uiState.value.message)
            assertFalse(vm.uiState.value.canSave)
            // Edits are blocked until the event is reloaded.
            vm.update { it.copy(title = "Changed again") }
            assertEquals(
                "Changed here",
                vm.uiState.value.form
                    ?.title,
            )
        }

    @Test
    fun `other refusals keep the form, report a failure and can be saved again`() =
        runTest(dispatcher) {
            val answers = listOf(CalendarResult.Failed(422), CalendarResult.Failed(null), CalendarResult.Ok(singleDetails))
            coEvery { repo.update(single, 4, null, any()) } returnsMany answers
            val vm = viewModel()
            vm.edit(single, calendars)
            advanceUntilIdle()
            vm.update { it.copy(title = "Changed here") }
            vm.save()
            advanceUntilIdle()
            assertEquals(EditorMessage.FAILED, vm.uiState.value.message)
            assertFalse(vm.uiState.value.saving)
            assertTrue(vm.uiState.value.canSave)
            assertEquals(
                "Changed here",
                vm.uiState.value.form
                    ?.title,
            )

            vm.save()
            assertNull(vm.uiState.value.message)
            assertTrue(vm.uiState.value.saving)
            advanceUntilIdle()
            assertEquals(EditorMessage.FAILED, vm.uiState.value.message)

            vm.save()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.open)
            coVerify(exactly = 3) { repo.update(single, 4, null, any()) }
        }

    @Test
    fun `a second save while the first is running is not sent, and edits wait`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<CalendarResult<EventDetails>>()
            coEvery { repo.create(any()) } coAnswers { answer.await() }
            val vm = viewModel()
            vm.create(today, calendars)
            vm.update { it.copy(title = "Lunch") }
            vm.save()
            runCurrent()
            assertTrue(vm.uiState.value.saving)
            assertFalse(vm.uiState.value.canSave)

            vm.save()
            vm.update { it.copy(title = "Dinner") }
            runCurrent()
            coVerify(exactly = 1) { repo.create(any()) }
            assertEquals(
                "Lunch",
                vm.uiState.value.form
                    ?.title,
            )

            answer.complete(CalendarResult.Ok(singleDetails))
            advanceUntilIdle()
            assertFalse(vm.uiState.value.open)
        }

    @Test
    fun `closing while a save is running is ignored, the answer arrives and a retry keeps the id`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<CalendarResult<EventDetails>>()
            val bodies = mutableListOf<JsonObject>()
            coEvery { repo.create(capture(bodies)) } coAnswers { answer.await() } andThen CalendarResult.Ok(singleDetails)
            val vm = viewModel()
            vm.create(today, calendars)
            vm.update { it.copy(title = "Lunch") }
            vm.save()
            runCurrent()
            vm.close()
            runCurrent()
            // The request may already have reached the server: it is not cancelled and the form stays.
            assertTrue(vm.uiState.value.open)
            assertTrue(vm.uiState.value.saving)

            answer.complete(CalendarResult.Offline)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.open)
            assertEquals(EditorMessage.OFFLINE, vm.uiState.value.message)

            vm.save()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.open)
            assertEquals(2, bodies.size)
            assertEquals(bodies[0]["id"], bodies[1]["id"])
        }

    @Test
    fun `closing after a failed save closes the editor`() =
        runTest(dispatcher) {
            coEvery { repo.create(any()) } returns CalendarResult.Failed(422)
            val vm = viewModel()
            vm.create(today, calendars)
            vm.update { it.copy(title = "Lunch") }
            vm.save()
            advanceUntilIdle()
            vm.close()
            assertEquals(EventEditorUiState(), vm.uiState.value)
        }

    @Test
    fun `an older load answering last does not replace the event opened since`() =
        runTest(dispatcher) {
            val first = CompletableDeferred<CalendarResult<EventDetails>>()
            coEvery { repo.event(single.eventId) } coAnswers { first.await() }
            val yoga = single.copy(eventId = id(4), title = "Yoga")
            coEvery { repo.event(yoga.eventId) } returns
                CalendarResult.Ok(details(id(4), "Yoga", "2026-10-22T16:00:00Z", "2026-10-22T17:00:00Z", version = 2))
            val vm = viewModel()
            vm.edit(single, calendars)
            runCurrent()
            vm.edit(yoga, calendars)
            advanceUntilIdle()
            first.complete(CalendarResult.Ok(singleDetails))
            advanceUntilIdle()
            assertEquals(
                "Yoga",
                vm.uiState.value.form
                    ?.title,
            )
            assertNull(vm.uiState.value.message)
            assertFalse(vm.uiState.value.loading)
        }

    @Test
    fun `closing while the event loads keeps the editor closed`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<CalendarResult<EventDetails>>()
            coEvery { repo.event(single.eventId) } coAnswers { answer.await() }
            val vm = viewModel()
            vm.edit(single, calendars)
            runCurrent()
            assertTrue(vm.uiState.value.loading)
            vm.close()
            answer.complete(CalendarResult.Offline)
            advanceUntilIdle()
            assertEquals(EventEditorUiState(), vm.uiState.value)
        }

    @Test
    fun `the scope prompt ignores a second save and a choice nobody was asked for`() =
        runTest(dispatcher) {
            coEvery { repo.event(id(2)) } returns CalendarResult.Ok(master)
            val generated = override.copy(eventId = id(2), version = 7)
            coEvery { repo.reread(generated) } returns CalendarResult.Ok(generated)
            val vm = viewModel()
            vm.edit(generated, calendars)
            advanceUntilIdle()
            // No prompt yet: a choice does nothing.
            vm.chooseScope(EditScope.ALL)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.update(any(), any(), any(), any()) }

            vm.update { it.copy(location = "Room 4") }
            vm.save()
            val offered = vm.uiState.value.scopes
            vm.save()
            assertEquals(offered, vm.uiState.value.scopes)
            assertEquals(EditScope.entries, offered)
            coVerify(exactly = 0) { repo.update(any(), any(), any(), any()) }
        }

    @Test
    fun `a new event goes to the first visible writable calendar, else to any writable one`() {
        val vm = viewModel()
        vm.create(today, listOf(feed, hidden, work))
        assertEquals(
            work.id,
            vm.uiState.value.form
                ?.calendarId,
        )
        assertEquals(listOf(hidden, work), vm.uiState.value.targetCalendars)

        vm.create(today, listOf(feed, hidden))
        assertEquals(
            hidden.id,
            vm.uiState.value.form
                ?.calendarId,
        )
    }

    @Test
    fun `without a writable calendar a new event asks for one and is not sent`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.create(today, listOf(feed))
            assertNull(
                vm.uiState.value.form
                    ?.calendarId,
            )
            assertEquals(emptyList<CalendarInfo>(), vm.uiState.value.targetCalendars)
            vm.update { it.copy(title = "Lunch") }
            vm.save()
            advanceUntilIdle()
            assertEquals(setOf(FormError.CALENDAR_MISSING), vm.uiState.value.errors)
            coVerify(exactly = 0) { repo.create(any()) }
        }

    @Test
    fun `a new event late in the evening starts at midnight, one on the hour at the next hour`() {
        val late = newForm(today, ZonedDateTime.of(today, LocalTime.of(23, 20), zone), null)
        assertEquals(today.plusDays(1) to LocalTime.MIDNIGHT, late.startDate to late.startTime)
        assertEquals(today.plusDays(1) to LocalTime.of(1, 0), late.endDate to late.endTime)
        val onTheHour = newForm(today, ZonedDateTime.of(today, LocalTime.of(10, 0), zone), null)
        assertEquals(LocalTime.of(11, 0), onTheHour.startTime)
        assertEquals(zone, onTheHour.zone)
    }

    @Test
    fun `a generated all-day occurrence shows its own days with the last day inclusive`() {
        val occurrence =
            Occurrence(
                id(2),
                work.id,
                "Holiday",
                null,
                true,
                null,
                null,
                LocalDate.parse("2026-10-24"),
                LocalDate.parse("2026-10-26"),
                seriesId = id(2),
                occurrence = "2026-10-24",
                version = 7,
            )
        val event =
            EventDetails(
                id = id(2),
                calendarId = work.id,
                title = "Holiday",
                location = "Lake",
                description = null,
                allDay = true,
                start = null,
                end = null,
                startDate = LocalDate.parse("2026-10-03"),
                endDate = LocalDate.parse("2026-10-05"),
                timeZone = null,
                rrule = "RRULE:FREQ=WEEKLY",
                seriesId = null,
                version = 7,
            )
        val form = editForm(occurrence, event, null, zone)
        assertTrue(form.allDay)
        assertEquals(LocalDate.parse("2026-10-24") to LocalDate.parse("2026-10-25"), form.startDate to form.endDate)
        assertEquals(Repeat.WEEKLY, form.repeat)
        assertEquals("FREQ=WEEKLY", form.rule)
        assertEquals("Lake", form.location)
        assertEquals("", form.description)
    }

    @Test
    fun `an unknown zone falls back to the device zone and seconds are dropped`() {
        val event = details(id(1), "Dentist", "2026-10-22T08:00:42Z", "2026-10-22T09:00:42Z", timeZone = "Not/AZone")
        val utc = ZoneId.of("UTC")
        val form = editForm(single, event, null, utc)
        assertEquals(utc, form.zone)
        assertEquals(LocalTime.of(8, 0) to LocalTime.of(9, 0), form.startTime to form.endTime)
        // The loaded form compared with itself sends nothing, even though the seconds were dropped.
        assertEquals(emptySet<String>(), patchBody(form, form).keys)
    }

    private fun allDaySingle(
        startDate: String,
        endDate: String?,
    ) = EventDetails(
        id = id(6),
        calendarId = work.id,
        title = "Conference",
        location = null,
        description = "Fictitious agenda",
        allDay = true,
        start = null,
        end = null,
        startDate = LocalDate.parse(startDate),
        endDate = endDate?.let(LocalDate::parse),
        timeZone = null,
        rrule = null,
        seriesId = null,
        version = 2,
    )

    private fun allDayOccurrence(event: EventDetails) =
        Occurrence(event.id, work.id, event.title, null, true, null, null, event.startDate, event.endDate, version = event.version)

    @Test
    fun `a single all-day event shows its stored days with the last day inclusive, and is sent back exclusive`() {
        // Stored as 24 October up to (excluding) 27 October: the form shows 24 to 26.
        val event = allDaySingle("2026-10-24", "2026-10-27")
        // The occurrence the view held is not generated, so the event's own dates count, even if the view's differ.
        val shown = allDayOccurrence(event).copy(startDate = LocalDate.parse("2026-10-01"), endDate = LocalDate.parse("2026-10-02"))
        val form = editForm(shown, event, null, zone)
        assertTrue(form.allDay)
        assertEquals(LocalDate.parse("2026-10-24") to LocalDate.parse("2026-10-26"), form.startDate to form.endDate)
        assertEquals(Repeat.NONE, form.repeat)
        assertEquals("Fictitious agenda", form.description)
        assertEquals(emptySet<String>(), patchBody(form, form).keys)

        // One more day: the end goes back as the exclusive day after the new last day.
        val longer = patchBody(form, form.copy(endDate = LocalDate.parse("2026-10-27")))
        assertEquals(
            JsonObject(mapOf("startDate" to JsonPrimitive("2026-10-24"), "endDate" to JsonPrimitive("2026-10-28"))),
            longer,
        )
    }

    @Test
    fun `a one-day all-day event ends on its first day, also when its end is missing or not after the start`() {
        val day = LocalDate.parse("2026-10-24")
        for (end in listOf("2026-10-25", null, "2026-10-24")) {
            val event = allDaySingle("2026-10-24", end)
            val form = editForm(allDayOccurrence(event), event, null, zone)
            assertEquals("end $end", day to day, form.startDate to form.endDate)
            assertTrue("end $end", form.errors.isEmpty())
        }
    }

    @Test
    fun `an override without a zone of its own uses the zone of its series`() {
        val newYork = ZoneId.of("America/New_York")
        val form =
            editForm(
                override,
                overrideDetails.copy(timeZone = null),
                master.copy(timeZone = "America/New_York"),
                ZoneId.of("UTC"),
            )
        assertEquals(newYork, form.zone)
        // 08:00Z on 30 October is 04:00 in New York (still summer time there).
        assertEquals(LocalTime.of(4, 0) to LocalTime.of(4, 15), form.startTime to form.endTime)
        assertEquals(Repeat.WEEKLY, form.repeat)
    }
}
