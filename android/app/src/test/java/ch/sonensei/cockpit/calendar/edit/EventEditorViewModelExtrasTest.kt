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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
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
import java.time.ZoneId
import java.util.UUID

/** Guests, suggestions, invitations after a save and on request, and the linked items of the edited event. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventEditorViewModelExtrasTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<EventEditRepository>()
    private val extras = mockk<EventExtrasRepository>()
    private val zone = ZoneId.of("Europe/Zurich")
    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:20:00Z"), zone)
    private val today = LocalDate.parse("2026-10-21")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(90), "Work", null, true, false, 3)
    private val google = CalendarInfo(id(91), "Google", null, true, false, 4)
    private val calendars = listOf(work, google)

    private val ada = Attendee("ada@example.org", "Ada Example", AttendeeStatus.ACCEPTED)
    private val bob = Attendee("bob@example.org", null, AttendeeStatus.NEEDS_ACTION)
    private val account = MailAccount(id(70), "Work mail", "me@example.org")
    private val other = MailAccount(id(71), "Private mail", "me@example.net")

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

    private val local = occurrence(1, work)
    private val synced = occurrence(2, google)

    private fun details(
        occurrence: Occurrence,
        attendees: List<Attendee> = emptyList(),
        reminders: List<Int> = emptyList(),
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
        version = occurrence.version,
        reminderMinutes = reminders,
        attendees = attendees,
    )

    private fun viewModel() = EventEditorViewModel(repo, clock, MutableStateFlow(null), extras)

    private val link = LinkedItem(id(50), LinkedType.TASK, id(10), "Prepare slides", "Inbox")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { extras.localCalendars() } returns CalendarResult.Ok(setOf(work.id))
        coEvery { extras.links(any()) } returns CalendarResult.Ok(emptyList())
        coEvery { extras.invitationCalendars() } returns CalendarResult.Ok(emptySet())
        coEvery { extras.ownAddresses() } returns CalendarResult.Ok(emptySet())
        coEvery { extras.mailAccounts() } returns CalendarResult.Ok(listOf(account, other))
        coEvery { repo.event(local.eventId) } returns CalendarResult.Ok(details(local, listOf(ada, bob), listOf(60, 10)))
        coEvery { repo.event(synced.eventId) } returns CalendarResult.Ok(details(synced, listOf(ada)))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.editing(occurrence: Occurrence = local): EventEditorViewModel {
        val vm = viewModel()
        vm.edit(occurrence, calendars)
        advanceUntilIdle()
        return vm
    }

    // --- Guests

    @Test
    fun `a loaded event shows its reminders and guests`() =
        runTest(dispatcher) {
            val vm = editing()
            val state = vm.uiState.value
            assertEquals(listOf(10, 60), state.form!!.reminders)
            assertEquals(listOf(ada, bob), state.form!!.attendees)
            assertEquals(listOf(ada.email, bob.email), state.savedAttendees)
            assertTrue(state.attendeesEditable)
            assertTrue(state.invitable)
        }

    @Test
    fun `guests are edited only in local calendars and only once those are known`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<CalendarResult<Set<UUID>>>()
            coEvery { extras.localCalendars() } coAnswers { pending.await() }
            val vm = viewModel()
            vm.edit(local, calendars)
            advanceUntilIdle()
            assertFalse("unknown sources: read-only", vm.uiState.value.attendeesEditable)
            vm.removeGuest(ada.email)
            assertEquals(
                listOf(ada, bob),
                vm.uiState.value.form!!
                    .attendees,
            )
            pending.complete(CalendarResult.Ok(setOf(work.id)))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.attendeesEditable)

            val google = editing(synced)
            assertFalse(google.uiState.value.attendeesEditable)
            assertFalse(google.uiState.value.invitable)
            google.setGuestQuery("carol@example.org")
            google.addGuest()
            google.removeGuest(ada.email)
            assertEquals(
                listOf(ada),
                google.uiState.value.form!!
                    .attendees,
            )
            coVerify(exactly = 0) { extras.suggestions(any()) }
        }

    @Test
    fun `when the local calendars can't be loaded guests stay read-only`() =
        runTest(dispatcher) {
            coEvery { extras.localCalendars() } returns CalendarResult.Offline
            val vm = editing()
            assertNull(vm.uiState.value.localCalendars)
            assertFalse(vm.uiState.value.attendeesEditable)
        }

    @Test
    fun `a change of a synced event never sends its guests`() =
        runTest(dispatcher) {
            val patch = slot<JsonObject>()
            coEvery { repo.update(any(), any(), any(), capture(patch)) } returns CalendarResult.Ok(details(synced, listOf(ada)))
            val vm = editing(synced)
            vm.update { it.copy(title = "Planning (moved)").withReminder(5) }
            vm.save()
            advanceUntilIdle()
            assertEquals(setOf("title", "reminderMinutes"), patch.captured.keys)
            // No invitation prompt for a synced calendar: the provider tells the guests.
            assertFalse(vm.uiState.value.open)
            coVerify(exactly = 0) { extras.mailAccounts() }
        }

    @Test
    fun `suggestions wait for a pause and two characters, and leave out invited guests`() =
        runTest(dispatcher) {
            coEvery { extras.suggestions(any()) } returns
                CalendarResult.Ok(listOf(Attendee("ADA@example.org", "Ada"), Attendee("adam@example.org", "Adam")))
            val vm = editing()
            vm.setGuestQuery("a")
            advanceUntilIdle()
            coVerify(exactly = 0) { extras.suggestions(any()) }

            vm.setGuestQuery(" ad")
            advanceTimeBy(100)
            vm.setGuestQuery(" ada ")
            advanceTimeBy(249)
            runCurrent()
            coVerify(exactly = 0) { extras.suggestions(any()) }
            advanceTimeBy(2)
            runCurrent()
            coVerify(exactly = 1) { extras.suggestions("ada") }
            coVerify(exactly = 0) { extras.suggestions("ad") }
            assertEquals(listOf(Attendee("adam@example.org", "Adam")), vm.uiState.value.suggestions)
            assertEquals(" ada ", vm.uiState.value.guestQuery)

            // Shortening the query below two characters drops the suggestions at once.
            vm.setGuestQuery("a")
            assertEquals(emptyList<Attendee>(), vm.uiState.value.suggestions)
        }

    @Test
    fun `a query cleared while suggestions load shows none`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<CalendarResult<List<Attendee>>>()
            coEvery { extras.suggestions(any()) } coAnswers { pending.await() }
            val vm = editing()
            vm.setGuestQuery("carol")
            advanceTimeBy(300)
            runCurrent()
            vm.setGuestQuery("")
            pending.complete(CalendarResult.Ok(listOf(Attendee("carol@example.org", "Carol"))))
            advanceUntilIdle()
            assertEquals(emptyList<Attendee>(), vm.uiState.value.suggestions)
        }

    @Test
    fun `failed suggestions show none`() =
        runTest(dispatcher) {
            coEvery { extras.suggestions(any()) } returns CalendarResult.Offline
            val vm = editing()
            vm.setGuestQuery("carol")
            advanceUntilIdle()
            assertEquals(emptyList<Attendee>(), vm.uiState.value.suggestions)
        }

    @Test
    fun `a typed guest must be an email address, a picked suggestion is added with its name`() =
        runTest(dispatcher) {
            coEvery { extras.suggestions(any()) } returns CalendarResult.Ok(listOf(Attendee("dave@example.org", "Dave")))
            val vm = editing()
            vm.setGuestQuery("carol")
            vm.addGuest()
            assertTrue(vm.uiState.value.guestInvalid)
            assertEquals(
                2,
                vm.uiState.value.form!!
                    .attendees.size,
            )
            vm.setGuestQuery("carol@")
            assertFalse("typing clears the error", vm.uiState.value.guestInvalid)
            vm.setGuestQuery("   ")
            vm.addGuest()
            assertFalse("an empty field is no error", vm.uiState.value.guestInvalid)

            vm.setGuestQuery(" carol@example.org ")
            vm.addGuest()
            assertEquals("", vm.uiState.value.guestQuery)
            assertEquals(
                Attendee("carol@example.org"),
                vm.uiState.value.form!!
                    .attendees
                    .last(),
            )

            vm.setGuestQuery("dave")
            advanceUntilIdle()
            vm.addGuest(
                vm.uiState.value.suggestions
                    .single(),
            )
            assertEquals(
                Attendee("dave@example.org", "Dave"),
                vm.uiState.value.form!!
                    .attendees
                    .last(),
            )
            assertEquals(emptyList<Attendee>(), vm.uiState.value.suggestions)

            // An already invited address is not added twice.
            vm.setGuestQuery("ADA@example.org")
            vm.addGuest()
            assertEquals(
                4,
                vm.uiState.value.form!!
                    .attendees.size,
            )

            vm.removeGuest("Bob@Example.org")
            assertEquals(
                listOf(ada.email, "carol@example.org", "dave@example.org"),
                vm.uiState.value.form!!
                    .attendees
                    .map { it.email },
            )
        }

    // --- Invitations after a save

    @Test
    fun `a new event with guests in a local calendar is created with them and offers to invite them`() =
        runTest(dispatcher) {
            val body = slot<JsonObject>()
            coEvery { repo.create(capture(body)) } answers { CalendarResult.Ok(details(local, listOf(ada))) }
            val vm = viewModel()
            vm.create(today, calendars)
            advanceUntilIdle()
            vm.update { it.copy(title = "Planning").withReminder(15) }
            vm.setGuestQuery(ada.email)
            vm.addGuest()
            vm.save()
            advanceUntilIdle()

            assertEquals(JsonArray(listOf(JsonPrimitive(15))), body.captured["reminderMinutes"])
            assertEquals(1, (body.captured["attendees"] as JsonArray).size)
            val state = vm.uiState.value
            assertTrue(state.open)
            assertFalse(state.saving)
            assertFalse("no save while the prompt is shown", state.canSave)
            val prompt = state.invitation!!
            assertTrue(prompt.afterSave)
            assertEquals(local.eventId, prompt.eventId)
            assertEquals(listOf(ada.email), prompt.recipients)
            assertEquals(emptyList<String>(), prompt.removed)
            assertEquals(listOf(account, other), prompt.accounts)
            assertEquals(account.id, prompt.accountId)
            assertTrue(prompt.canSend)
        }

    @Test
    fun `a save without guests or removed guests closes the editor`() =
        runTest(dispatcher) {
            coEvery { repo.create(any()) } returns CalendarResult.Ok(details(local))
            val vm = viewModel()
            vm.create(today, calendars)
            advanceUntilIdle()
            vm.update { it.copy(title = "Alone") }
            vm.save()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.open)
            coVerify(exactly = 0) { extras.mailAccounts() }
        }

    @Test
    fun `removing every guest offers to tell them, and sends them a cancellation`() =
        runTest(dispatcher) {
            val patch = slot<JsonObject>()
            coEvery { repo.update(any(), any(), any(), capture(patch)) } returns CalendarResult.Ok(details(local))
            coEvery { extras.sendInvitations(any(), any(), any(), any()) } returns CalendarResult.Ok(2)
            val vm = editing()
            vm.removeGuest(ada.email)
            vm.removeGuest(bob.email)
            vm.save()
            advanceUntilIdle()

            assertEquals(JsonArray(emptyList()), patch.captured["attendees"])
            val prompt = vm.uiState.value.invitation!!
            assertEquals(emptyList<String>(), prompt.recipients)
            assertEquals(listOf(ada.email, bob.email), prompt.removed)
            assertTrue(prompt.canSend)
            assertFalse(prompt.copy(tellRemoved = false).canSend)

            vm.sendInvitation()
            advanceUntilIdle()
            coVerify(
                exactly = 1,
            ) { extras.sendInvitations(local.eventId, InvitationMethod.CANCEL, account.id, listOf(ada.email, bob.email)) }
            coVerify(exactly = 0) { extras.sendInvitations(any(), any(), any(), null) }
            assertFalse("sent after a save: the editor closes", vm.uiState.value.open)
        }

    @Test
    fun `a sent invitation goes to the guests over the chosen account and also cancels for removed guests`() =
        runTest(dispatcher) {
            coEvery { repo.update(any(), any(), any(), any()) } returns CalendarResult.Ok(details(local, listOf(ada)))
            coEvery { extras.sendInvitations(any(), any(), any(), any()) } returns CalendarResult.Ok(1)
            val vm = editing()
            vm.removeGuest(bob.email)
            vm.save()
            advanceUntilIdle()
            vm.updateInvitation { it.copy(accountId = other.id) }
            vm.sendInvitation()
            advanceUntilIdle()

            coVerify(exactly = 1) { extras.sendInvitations(local.eventId, InvitationMethod.REQUEST, other.id, null) }
            coVerify(exactly = 1) { extras.sendInvitations(local.eventId, InvitationMethod.CANCEL, other.id, listOf(bob.email)) }
            assertFalse(vm.uiState.value.open)
        }

    @Test
    fun `removed guests are not told when that is unchecked`() =
        runTest(dispatcher) {
            coEvery { repo.update(any(), any(), any(), any()) } returns CalendarResult.Ok(details(local, listOf(ada)))
            coEvery { extras.sendInvitations(any(), any(), any(), any()) } returns CalendarResult.Ok(1)
            val vm = editing()
            vm.removeGuest(bob.email)
            vm.save()
            advanceUntilIdle()
            vm.updateInvitation { it.copy(tellRemoved = false, method = InvitationMethod.CANCEL) }
            vm.sendInvitation()
            advanceUntilIdle()
            coVerify(exactly = 1) { extras.sendInvitations(local.eventId, InvitationMethod.CANCEL, account.id, null) }
            coVerify(exactly = 1) { extras.sendInvitations(any(), any(), any(), any()) }
        }

    @Test
    fun `a failed send keeps the prompt for a retry, also when only the cancellation failed`() =
        runTest(dispatcher) {
            coEvery { repo.update(any(), any(), any(), any()) } returns CalendarResult.Ok(details(local, listOf(ada)))
            coEvery { extras.sendInvitations(any(), InvitationMethod.REQUEST, any(), null) } returns
                CalendarResult.Offline andThen CalendarResult.Ok(1)
            coEvery { extras.sendInvitations(any(), InvitationMethod.CANCEL, any(), any()) } returns
                CalendarResult.Failed(422) andThen CalendarResult.Ok(1)
            val vm = editing()
            vm.removeGuest(bob.email)
            vm.save()
            advanceUntilIdle()

            vm.sendInvitation()
            advanceUntilIdle()
            var prompt = vm.uiState.value.invitation!!
            assertTrue(vm.uiState.value.open)
            assertTrue(prompt.failed)
            assertFalse(prompt.sending)
            coVerify(exactly = 0) { extras.sendInvitations(any(), InvitationMethod.CANCEL, any(), any()) }

            vm.sendInvitation()
            advanceUntilIdle()
            prompt = vm.uiState.value.invitation!!
            assertTrue("the cancellation failed", prompt.failed)

            vm.sendInvitation()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.open)
        }

    @Test
    fun `while sending the prompt can neither change nor be dismissed, nor send twice`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<CalendarResult<Int>>()
            coEvery { repo.update(any(), any(), any(), any()) } returns CalendarResult.Ok(details(local, listOf(ada, bob)))
            coEvery { extras.sendInvitations(any(), any(), any(), any()) } coAnswers { pending.await() }
            val vm = editing()
            vm.update { it.copy(title = "Planning v2") }
            vm.save()
            advanceUntilIdle()
            vm.sendInvitation()
            runCurrent()
            assertTrue(
                vm.uiState.value.invitation!!
                    .sending,
            )
            assertFalse(
                vm.uiState.value.invitation!!
                    .canSend,
            )
            vm.updateInvitation { it.copy(method = InvitationMethod.CANCEL) }
            vm.dismissInvitation()
            vm.sendInvitation()
            runCurrent()
            assertEquals(
                InvitationMethod.REQUEST,
                vm.uiState.value.invitation!!
                    .method,
            )
            coVerify(exactly = 1) { extras.sendInvitations(any(), any(), any(), any()) }
            pending.complete(CalendarResult.Ok(2))
            advanceUntilIdle()
            assertFalse(vm.uiState.value.open)
        }

    @Test
    fun `skipping the prompt after a save closes the editor without sending`() =
        runTest(dispatcher) {
            coEvery { repo.update(any(), any(), any(), any()) } returns CalendarResult.Ok(details(local, listOf(ada, bob)))
            val vm = editing()
            vm.update { it.copy(title = "Planning v2") }
            vm.save()
            advanceUntilIdle()
            vm.dismissInvitation()
            assertFalse(vm.uiState.value.open)
            coVerify(exactly = 0) { extras.sendInvitations(any(), any(), any(), any()) }
        }

    @Test
    fun `mail accounts that fail to load can be loaded again, nothing is sent without one`() =
        runTest(dispatcher) {
            coEvery { extras.mailAccounts() } returns CalendarResult.Offline andThen CalendarResult.Ok(emptyList()) andThen
                CalendarResult.Ok(listOf(other))
            coEvery { repo.update(any(), any(), any(), any()) } returns CalendarResult.Ok(details(local, listOf(ada, bob)))
            val vm = editing()
            vm.update { it.copy(title = "Planning v2") }
            vm.save()
            advanceUntilIdle()
            var prompt = vm.uiState.value.invitation!!
            assertTrue(prompt.loadFailed)
            assertFalse(prompt.canSend)
            vm.sendInvitation()

            vm.loadAccounts()
            assertNull(
                vm.uiState.value.invitation!!
                    .accounts,
            )
            assertFalse(
                vm.uiState.value.invitation!!
                    .loadFailed,
            )
            advanceUntilIdle()
            prompt = vm.uiState.value.invitation!!
            assertEquals(emptyList<MailAccount>(), prompt.accounts)
            assertNull(prompt.accountId)
            assertFalse(prompt.canSend)

            vm.loadAccounts()
            advanceUntilIdle()
            assertEquals(
                other.id,
                vm.uiState.value.invitation!!
                    .accountId,
            )
            assertTrue(
                vm.uiState.value.invitation!!
                    .canSend,
            )
            coVerify(exactly = 0) { extras.sendInvitations(any(), any(), any(), any()) }
        }

    // --- Invitations on request

    @Test
    fun `send invitations asks to save unsaved changes first`() =
        runTest(dispatcher) {
            val vm = editing()
            vm.update { it.withReminder(5) }
            vm.invite()
            assertTrue(vm.uiState.value.saveBeforeInviting)
            assertNull(vm.uiState.value.invitation)
            coVerify(exactly = 0) { extras.mailAccounts() }
        }

    @Test
    fun `send invitations for the saved event sends to its guests and keeps the editor open`() =
        runTest(dispatcher) {
            coEvery { extras.sendInvitations(any(), any(), any(), any()) } returns CalendarResult.Ok(2)
            val vm = editing()
            vm.invite()
            advanceUntilIdle()
            val prompt = vm.uiState.value.invitation!!
            assertFalse(prompt.afterSave)
            assertEquals(listOf(ada.email, bob.email), prompt.recipients)
            assertEquals("Planning", prompt.title)

            vm.sendInvitation()
            advanceUntilIdle()
            val state = vm.uiState.value
            assertTrue(state.open)
            assertNull(state.invitation)
            assertEquals(InvitationSent(InvitationMethod.REQUEST, 2), state.invitationSent)
            coVerify(exactly = 1) { extras.sendInvitations(local.eventId, InvitationMethod.REQUEST, account.id, null) }

            // Cancelling the prompt of a saved event only closes the prompt.
            vm.invite()
            advanceUntilIdle()
            assertNull(vm.uiState.value.invitationSent)
            vm.dismissInvitation()
            assertTrue(vm.uiState.value.open)
            assertNull(vm.uiState.value.invitation)
        }

    @Test
    fun `send invitations is not offered for a new event, a synced one or one without guests`() =
        runTest(dispatcher) {
            coEvery { repo.event(local.eventId) } returns CalendarResult.Ok(details(local))
            val alone = editing()
            assertFalse(alone.uiState.value.invitable)
            alone.invite()
            assertNull(alone.uiState.value.invitation)

            val google = editing(synced)
            google.invite()
            assertNull(google.uiState.value.invitation)

            val fresh = viewModel()
            fresh.create(today, calendars)
            advanceUntilIdle()
            fresh.invite()
            assertFalse(fresh.uiState.value.invitable)
            assertNull(fresh.uiState.value.invitation)
        }

    // --- Linked items

    @Test
    fun `the links of an edited event are loaded, a failure can be retried, a new event has none`() =
        runTest(dispatcher) {
            coEvery { extras.links(local.eventId) } returns CalendarResult.Offline andThen CalendarResult.Ok(listOf(link))
            val vm = editing()
            assertEquals(LinksUiState(failed = true), vm.uiState.value.links)
            vm.loadLinks()
            assertTrue(
                vm.uiState.value.links!!
                    .loading,
            )
            advanceUntilIdle()
            assertEquals(LinksUiState(items = listOf(link)), vm.uiState.value.links)

            val fresh = viewModel()
            fresh.create(today, calendars)
            advanceUntilIdle()
            assertNull(fresh.uiState.value.links)
            fresh.createLinked(LinkedKind.TASK)
            coVerify(exactly = 0) { extras.createTask(any(), any()) }
        }

    @Test
    fun `a task from the event is titled with the event and its date, then the links reload`() =
        runTest(dispatcher) {
            coEvery { extras.links(local.eventId) } returns CalendarResult.Ok(emptyList()) andThen CalendarResult.Ok(listOf(link))
            coEvery { extras.createTask(any(), any()) } returns LinkedCreateResult.Created("Planning (2026-10-22)")
            val vm = editing()
            vm.createLinked(LinkedKind.TASK)
            assertEquals(
                LinkedKind.TASK,
                vm.uiState.value.links!!
                    .creating,
            )
            vm.createLinked(LinkedKind.NOTE)
            advanceUntilIdle()
            coVerify(exactly = 1) { extras.createTask("Planning (2026-10-22)", local.eventId) }
            coVerify(exactly = 0) { extras.createNote(any(), any()) }
            val links = vm.uiState.value.links!!
            assertNull(links.creating)
            assertEquals(LinkedResult(LinkedKind.TASK, LinkedCreateResult.Created("Planning (2026-10-22)")), links.result)
            assertEquals(listOf(link), links.items)
        }

    @Test
    fun `a note whose link failed still reloads the links, a failed create does not`() =
        runTest(dispatcher) {
            coEvery { extras.createNote(any(), any()) } returns LinkedCreateResult.NotLinked("Planning (2026-10-22)") andThen
                LinkedCreateResult.Failed
            val vm = editing()
            coVerify(exactly = 1) { extras.links(local.eventId) }
            vm.createLinked(LinkedKind.NOTE)
            advanceUntilIdle()
            coVerify(exactly = 2) { extras.links(local.eventId) }
            assertEquals(
                LinkedCreateResult.NotLinked("Planning (2026-10-22)"),
                vm.uiState.value.links!!
                    .result!!
                    .result,
            )

            vm.createLinked(LinkedKind.NOTE)
            advanceUntilIdle()
            coVerify(exactly = 2) { extras.links(local.eventId) }
            assertEquals(
                LinkedResult(LinkedKind.NOTE, LinkedCreateResult.Failed),
                vm.uiState.value.links!!
                    .result,
            )
        }

    @Test
    fun `an older links answer arriving last does not hide a newly created task`() =
        runTest(dispatcher) {
            val first = CompletableDeferred<CalendarResult<List<LinkedItem>>>()
            coEvery { extras.links(local.eventId) } coAnswers { first.await() } andThen CalendarResult.Ok(listOf(link))
            coEvery { extras.createTask(any(), any()) } returns LinkedCreateResult.Created("Planning (2026-10-22)")
            val vm = editing()
            // The first load is still pending when the task is created and the links reload.
            vm.createLinked(LinkedKind.TASK)
            advanceUntilIdle()
            assertEquals(
                listOf(link),
                vm.uiState.value.links!!
                    .items,
            )
            first.complete(CalendarResult.Ok(emptyList()))
            advanceUntilIdle()
            assertEquals(
                listOf(link),
                vm.uiState.value.links!!
                    .items,
            )
        }

    @Test
    fun `a links reload while one is pending shows the newer answer`() =
        runTest(dispatcher) {
            val first = CompletableDeferred<CalendarResult<List<LinkedItem>>>()
            coEvery { extras.links(local.eventId) } returns CalendarResult.Offline
            val vm = editing()
            assertTrue(
                vm.uiState.value.links!!
                    .failed,
            )
            coEvery { extras.links(local.eventId) } coAnswers { first.await() } andThen CalendarResult.Ok(listOf(link))
            vm.loadLinks()
            runCurrent()
            vm.loadLinks()
            advanceUntilIdle()
            first.complete(CalendarResult.Offline)
            advanceUntilIdle()
            val links = vm.uiState.value.links!!
            assertEquals(listOf(link), links.items)
            assertFalse("an older failure must not replace a newer answer", links.failed)
        }

    // --- Reminders and time zone, saved through the editor

    @Test
    fun `a custom reminder and a removed preset are saved as the new reminder list`() =
        runTest(dispatcher) {
            val patch = slot<JsonObject>()
            coEvery { repo.update(any(), any(), any(), capture(patch)) } returns CalendarResult.Ok(details(local, listOf(ada, bob)))
            val vm = editing()
            val custom = customReminderMinutes(" 45 ")!!
            vm.update { it.withReminder(custom).withoutReminder(60) }
            vm.save()
            advanceUntilIdle()
            assertEquals(JsonArray(listOf(JsonPrimitive(10), JsonPrimitive(45))), patch.captured["reminderMinutes"])
            assertFalse("guests were not changed", "attendees" in patch.captured)
        }

    @Test
    fun `removing every reminder saves an empty list`() =
        runTest(dispatcher) {
            val patch = slot<JsonObject>()
            coEvery { repo.update(any(), any(), any(), capture(patch)) } returns CalendarResult.Ok(details(local, listOf(ada, bob)))
            val vm = editing()
            vm.update { it.withoutReminder(10).withoutReminder(60) }
            vm.save()
            advanceUntilIdle()
            assertEquals(JsonArray(emptyList()), patch.captured["reminderMinutes"])
        }

    @Test
    fun `a changed time zone keeps the clock times and saves them in the new zone`() =
        runTest(dispatcher) {
            val patch = slot<JsonObject>()
            coEvery { repo.update(any(), any(), any(), capture(patch)) } returns CalendarResult.Ok(details(local, listOf(ada, bob)))
            val vm = editing()
            assertEquals(
                zone,
                vm.uiState.value.form!!
                    .zone,
            )
            vm.update { it.copy(zone = ZoneId.of("America/New_York")) }
            vm.save()
            advanceUntilIdle()
            // 10:00-11:00 on 2026-10-22, now New York time (UTC-4).
            assertEquals(JsonPrimitive("America/New_York"), patch.captured["timeZone"])
            assertEquals(JsonPrimitive("2026-10-22T14:00:00Z"), patch.captured["start"])
            assertEquals(JsonPrimitive("2026-10-22T15:00:00Z"), patch.captured["end"])
        }

    @Test
    fun `a new event is created in the picked time zone`() =
        runTest(dispatcher) {
            val body = slot<JsonObject>()
            coEvery { repo.create(capture(body)) } returns CalendarResult.Ok(details(local))
            val vm = viewModel()
            vm.create(today.plusDays(1), calendars)
            advanceUntilIdle()
            vm.update { it.copy(title = "Call", zone = ZoneId.of("UTC")) }
            vm.save()
            advanceUntilIdle()
            assertEquals(JsonPrimitive("UTC"), body.captured["timeZone"])
            assertEquals(JsonPrimitive("2026-10-22T09:00:00Z"), body.captured["start"])
        }

    // --- A series

    @Test
    fun `a changed occurrence of a series with guests asks the scope, then offers invitations`() =
        runTest(dispatcher) {
            val master = id(4)
            val override =
                occurrence(3, work).copy(seriesId = master, occurrence = "2026-10-22T08:00:00Z")
            coEvery { repo.event(override.eventId) } returns
                CalendarResult.Ok(details(override, listOf(ada)).copy(seriesId = master))
            coEvery { repo.event(master) } returns
                CalendarResult.Ok(details(override, listOf(ada)).copy(id = master, rrule = "FREQ=WEEKLY"))
            val patch = slot<JsonObject>()
            coEvery { repo.update(any(), any(), EditScope.THIS, capture(patch)) } returns
                CalendarResult.Ok(details(override, listOf(ada, bob)))
            val vm = editing(override)
            vm.setGuestQuery(bob.email)
            vm.addGuest()
            vm.save()
            advanceUntilIdle()
            assertEquals(EditScope.entries.toList(), vm.uiState.value.scopes)
            assertNull(vm.uiState.value.invitation)

            vm.chooseScope(EditScope.THIS)
            advanceUntilIdle()
            assertEquals(2, (patch.captured["attendees"] as JsonArray).size)
            val prompt = vm.uiState.value.invitation!!
            assertEquals(override.eventId, prompt.eventId)
            assertEquals(listOf(ada.email, bob.email), prompt.recipients)
            assertTrue(prompt.afterSave)
        }

    @Test
    fun `closing the editor drops guests, prompts and links`() =
        runTest(dispatcher) {
            val vm = editing()
            vm.setGuestQuery("carol")
            vm.close()
            advanceUntilIdle()
            assertEquals(EventEditorUiState(), vm.uiState.value)
            coVerify(exactly = 0) { extras.suggestions(any()) }
        }

    // --- Answers arriving late or out of order

    @Test
    fun `the links answer of a closed editor never shows in the next event`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<CalendarResult<List<LinkedItem>>>()
            coEvery { extras.links(local.eventId) } coAnswers { pending.await() }
            coEvery { extras.links(synced.eventId) } returns CalendarResult.Ok(emptyList())
            val vm = editing()
            assertTrue(
                vm.uiState.value.links!!
                    .loading,
            )
            vm.close()
            vm.edit(synced, calendars)
            advanceUntilIdle()
            assertEquals(LinksUiState(), vm.uiState.value.links)

            pending.complete(CalendarResult.Ok(listOf(link)))
            advanceUntilIdle()
            assertEquals("the closed event's links stay out", LinksUiState(), vm.uiState.value.links)
        }

    @Test
    fun `an older suggestions answer arriving after a newer query is dropped`() =
        runTest(dispatcher) {
            val older = CompletableDeferred<CalendarResult<List<Attendee>>>()
            val carol = Attendee("carol@example.org", "Carol")
            val dave = Attendee("dave@example.org", "Dave")
            coEvery { extras.suggestions("ca") } coAnswers { older.await() }
            coEvery { extras.suggestions("da") } returns CalendarResult.Ok(listOf(dave))
            val vm = editing()
            vm.setGuestQuery("ca")
            advanceTimeBy(300)
            runCurrent()
            vm.setGuestQuery("da")
            advanceUntilIdle()
            assertEquals(listOf(dave), vm.uiState.value.suggestions)

            older.complete(CalendarResult.Ok(listOf(carol)))
            advanceUntilIdle()
            assertEquals(listOf(dave), vm.uiState.value.suggestions)
        }

    @Test
    fun `suggestions still loading when a guest is added are not shown afterwards`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<CalendarResult<List<Attendee>>>()
            coEvery { extras.suggestions(any()) } coAnswers { pending.await() }
            val vm = editing()
            vm.setGuestQuery("carol@example.org")
            advanceTimeBy(300)
            runCurrent()
            vm.addGuest()
            pending.complete(CalendarResult.Ok(listOf(Attendee("carol.other@example.org", "Carol Other"))))
            advanceUntilIdle()
            val state = vm.uiState.value
            assertEquals(emptyList<Attendee>(), state.suggestions)
            assertEquals("", state.guestQuery)
            assertEquals(
                listOf(ada.email, bob.email, "carol@example.org"),
                state.form!!.attendees.map { it.email },
            )
        }

    @Test
    fun `a reopened invitation prompt shows the newer accounts answer, not an older failure`() =
        runTest(dispatcher) {
            val older = CompletableDeferred<CalendarResult<List<MailAccount>>>()
            coEvery { extras.mailAccounts() } coAnswers { older.await() } andThen CalendarResult.Ok(listOf(other))
            val vm = editing()
            vm.invite()
            runCurrent()
            vm.dismissInvitation()
            vm.invite()
            advanceUntilIdle()
            older.complete(CalendarResult.Offline)
            advanceUntilIdle()
            val prompt = vm.uiState.value.invitation!!
            assertFalse("an older failure must not replace a newer answer", prompt.loadFailed)
            assertEquals(listOf(other), prompt.accounts)
            assertEquals(other.id, prompt.accountId)
            assertTrue(prompt.canSend)
        }

    @Test
    fun `a save tapped twice while it runs creates the event once and offers one prompt`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<CalendarResult<EventDetails>>()
            coEvery { repo.create(any()) } coAnswers { pending.await() }
            val vm = viewModel()
            vm.create(today.plusDays(1), calendars)
            advanceUntilIdle()
            vm.update { it.copy(title = "Kick-off") }
            vm.setGuestQuery(ada.email)
            vm.addGuest()
            vm.save()
            runCurrent()
            vm.save()
            vm.addGuest(bob)
            pending.complete(CalendarResult.Ok(details(local, listOf(ada))))
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.create(any()) }
            assertEquals(
                listOf(ada.email),
                vm.uiState.value.invitation!!
                    .recipients,
            )
        }
}
