package ch.sonensei.cockpit.dashboard

import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.calendar.agenda.Occurrence
import ch.sonensei.cockpit.gtd.projects.ProjectOpenRequests
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Editing the layout (optimistic, saved one after the other, rolled back on failure) and opening tapped items. */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardCustomizeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<DashboardRepository>()
    private val projects = ProjectOpenRequests()

    private val review = WidgetData.WeeklyReview(daysSinceLastReview = 3, openStep = null)
    private val inbox = WidgetData.Inbox(Count(2, more = false), Count(1, more = false))
    private val notes = WidgetData.PinnedNotes(emptyList(), more = false)

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    /** What each save sent, in order. */
    private val saved = mutableListOf<List<WidgetSlot>>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A view model that loaded [layout]; every widget loads [review] unless set up otherwise; saves echo what they got. */
    private fun TestScope.loaded(layout: List<WidgetSlot> = layoutOf(WidgetId.INBOX, WidgetId.WEEKLY_REVIEW)): DashboardViewModel {
        coEvery { repo.layout() } returns DashboardResult.Ok(layout)
        coEvery { repo.load(any()) } returns DashboardResult.Ok(review)
        coEvery { repo.load(WidgetId.INBOX) } returns DashboardResult.Ok(inbox)
        coEvery { repo.saveLayout(any()) } coAnswers {
            val sent = firstArg<List<WidgetSlot>>()
            saved += sent
            DashboardResult.Ok(sent)
        }
        val vm = DashboardViewModel(repo, projects)
        vm.refresh()
        advanceUntilIdle()
        return vm
    }

    private fun occurrence() =
        Occurrence(
            eventId = id(5),
            calendarId = id(90),
            title = "Dentist",
            location = null,
            allDay = false,
            start = Instant.parse("2026-10-21T08:00:00Z"),
            end = Instant.parse("2026-10-21T09:00:00Z"),
            startDate = null,
            endDate = null,
        )

    private val calendars = listOf(CalendarInfo(id(90), "Personal", color = null, visible = true, readOnly = false, version = 1))

    @Test
    fun `the layout cannot be edited before it is loaded`() =
        runTest(dispatcher) {
            coEvery { repo.layout() } returns DashboardResult.Offline
            coEvery { repo.load(any()) } returns DashboardResult.Ok(review)
            val vm = DashboardViewModel(repo, projects)
            assertFalse(vm.uiState.value.layoutLoaded)

            vm.customize(true)
            vm.setVisible(WidgetId.TODAY, false)
            vm.move(WidgetId.INBOX, 0)
            vm.reset()
            vm.refresh()
            advanceUntilIdle()
            vm.customize(true)
            vm.setVisible(WidgetId.TODAY, false)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.layoutLoaded)
            assertFalse(vm.uiState.value.customizing)
            assertEquals(WidgetId.entries, vm.uiState.value.widgets)
            coVerify(exactly = 0) { repo.saveLayout(any()) }
        }

    @Test
    fun `the loaded layout stays editable after a reload that fails`() =
        runTest(dispatcher) {
            val vm = loaded()
            assertTrue(vm.uiState.value.layoutLoaded)
            assertEquals(layoutOf(WidgetId.INBOX, WidgetId.WEEKLY_REVIEW), vm.uiState.value.layout)

            coEvery { repo.layout() } returns DashboardResult.Offline
            vm.refresh()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.layoutLoaded)
        }

    @Test
    fun `customize shows and hides the editor and clears a layout error`() =
        runTest(dispatcher) {
            val vm = loaded()
            coEvery { repo.saveLayout(any()) } returns DashboardResult.Failed
            vm.setVisible(WidgetId.INBOX, false)
            advanceUntilIdle()
            assertEquals(WidgetError.FAILED, vm.uiState.value.layoutError)

            vm.customize(true)
            assertTrue(vm.uiState.value.customizing)
            assertNull(vm.uiState.value.layoutError)
            vm.customize(false)
            assertFalse(vm.uiState.value.customizing)
        }

    @Test
    fun `hiding a widget shows at once, is saved and loads nothing`() =
        runTest(dispatcher) {
            val vm = loaded()

            vm.setVisible(WidgetId.INBOX, false)
            assertEquals(listOf(WidgetId.WEEKLY_REVIEW), vm.uiState.value.widgets)
            advanceUntilIdle()

            assertEquals(listOf(layoutOf(WidgetId.WEEKLY_REVIEW).moved(WidgetId.INBOX, 0)), saved)
            assertEquals(listOf(WidgetId.WEEKLY_REVIEW), vm.uiState.value.widgets)
            assertNull(vm.uiState.value.layoutError)
            coVerify(exactly = 1) { repo.load(WidgetId.INBOX) }
        }

    @Test
    fun `a newly shown widget loads its data`() =
        runTest(dispatcher) {
            val vm = loaded()
            val slow = CompletableDeferred<DashboardResult<WidgetData>>()
            coEvery { repo.load(WidgetId.PINNED_NOTES) } coAnswers { slow.await() }

            vm.setVisible(WidgetId.PINNED_NOTES, true)
            advanceUntilIdle()

            assertEquals(listOf(WidgetId.INBOX, WidgetId.WEEKLY_REVIEW, WidgetId.PINNED_NOTES), vm.uiState.value.widgets)
            assertTrue(
                vm.uiState.value
                    .state(WidgetId.PINNED_NOTES)
                    .loading,
            )
            slow.complete(DashboardResult.Ok(notes))
            advanceUntilIdle()
            assertEquals(WidgetState(notes, loading = false), vm.uiState.value.state(WidgetId.PINNED_NOTES))
            coVerify(exactly = 1) { repo.load(WidgetId.PINNED_NOTES) }
            coVerify(exactly = 1) { repo.load(WidgetId.INBOX) }
        }

    @Test
    fun `moving a widget reorders the cards and saves the whole layout`() =
        runTest(dispatcher) {
            val vm = loaded()

            vm.move(WidgetId.WEEKLY_REVIEW, 0)
            advanceUntilIdle()

            assertEquals(listOf(WidgetId.WEEKLY_REVIEW, WidgetId.INBOX), vm.uiState.value.widgets)
            assertEquals(layoutOf(WidgetId.WEEKLY_REVIEW, WidgetId.INBOX), saved.single())
            // Only the order changed: no widget loads again.
            coVerify(exactly = 1) { repo.load(WidgetId.WEEKLY_REVIEW) }
        }

    @Test
    fun `reset shows every widget in the default order and loads the ones that were hidden`() =
        runTest(dispatcher) {
            val vm = loaded()

            vm.reset()
            advanceUntilIdle()

            assertEquals(DEFAULT_LAYOUT, vm.uiState.value.layout)
            assertEquals(WidgetId.entries, vm.uiState.value.widgets)
            assertEquals(listOf(DEFAULT_LAYOUT), saved)
            WidgetId.entries.forEach { coVerify(exactly = 1) { repo.load(it) } }
            WidgetId.entries.forEach {
                assertFalse(
                    it.name,
                    vm.uiState.value
                        .state(it)
                        .loading,
                )
            }
        }

    @Test
    fun `the layout the server stored is shown after a save`() =
        runTest(dispatcher) {
            val vm = loaded()
            val stored = layoutOf(WidgetId.TODAY)
            coEvery { repo.saveLayout(any()) } returns DashboardResult.Ok(stored)

            vm.move(WidgetId.WEEKLY_REVIEW, 0)
            advanceUntilIdle()

            assertEquals(stored, vm.uiState.value.layout)
            assertEquals(listOf(WidgetId.TODAY), vm.uiState.value.widgets)
        }

    @Test
    fun `a failed save rolls back to the stored layout and says why`() =
        runTest(dispatcher) {
            val vm = loaded()
            val before = vm.uiState.value.layout
            coEvery { repo.saveLayout(any()) } returns DashboardResult.Offline

            vm.setVisible(WidgetId.INBOX, false)
            assertEquals(listOf(WidgetId.WEEKLY_REVIEW), vm.uiState.value.widgets)
            advanceUntilIdle()

            assertEquals(before, vm.uiState.value.layout)
            assertEquals(listOf(WidgetId.INBOX, WidgetId.WEEKLY_REVIEW), vm.uiState.value.widgets)
            assertEquals(WidgetError.OFFLINE, vm.uiState.value.layoutError)

            coEvery { repo.saveLayout(any()) } returns DashboardResult.Failed
            vm.reset()
            advanceUntilIdle()
            assertEquals(before, vm.uiState.value.layout)
            assertEquals(WidgetError.FAILED, vm.uiState.value.layoutError)
        }

    @Test
    fun `the next change clears the error of a failed one`() =
        runTest(dispatcher) {
            val vm = loaded()
            coEvery { repo.saveLayout(any()) } returns DashboardResult.Failed
            vm.move(WidgetId.WEEKLY_REVIEW, 0)
            advanceUntilIdle()
            assertEquals(WidgetError.FAILED, vm.uiState.value.layoutError)

            coEvery { repo.saveLayout(any()) } coAnswers { DashboardResult.Ok(firstArg()) }
            vm.move(WidgetId.WEEKLY_REVIEW, 0)
            assertNull(vm.uiState.value.layoutError)
            advanceUntilIdle()

            assertNull(vm.uiState.value.layoutError)
            assertEquals(listOf(WidgetId.WEEKLY_REVIEW, WidgetId.INBOX), vm.uiState.value.widgets)
        }

    @Test
    fun `quick changes are saved one after the other and the latest stays shown`() =
        runTest(dispatcher) {
            val vm = loaded()
            val first = CompletableDeferred<Unit>()
            var calls = 0
            coEvery { repo.saveLayout(any()) } coAnswers {
                val sent = firstArg<List<WidgetSlot>>()
                saved += sent
                if (calls++ == 0) first.await()
                DashboardResult.Ok(sent)
            }

            vm.move(WidgetId.WEEKLY_REVIEW, 0)
            advanceUntilIdle()
            vm.setVisible(WidgetId.INBOX, false)
            advanceUntilIdle()
            // The second save waits for the first.
            assertEquals(1, saved.size)

            first.complete(Unit)
            advanceUntilIdle()

            val latest = layoutOf(WidgetId.WEEKLY_REVIEW, WidgetId.INBOX).withVisible(WidgetId.INBOX, false)
            assertEquals(listOf(layoutOf(WidgetId.WEEKLY_REVIEW, WidgetId.INBOX), latest), saved)
            assertEquals(latest, vm.uiState.value.layout)
            assertEquals(listOf(WidgetId.WEEKLY_REVIEW), vm.uiState.value.widgets)
        }

    @Test
    fun `when a later change fails the layout saved before it stays`() =
        runTest(dispatcher) {
            val vm = loaded()
            coEvery { repo.saveLayout(any()) } coAnswers { DashboardResult.Ok(firstArg()) } coAndThen { DashboardResult.Failed }

            vm.move(WidgetId.WEEKLY_REVIEW, 0)
            advanceUntilIdle()
            vm.setVisible(WidgetId.INBOX, false)
            advanceUntilIdle()

            assertEquals(layoutOf(WidgetId.WEEKLY_REVIEW, WidgetId.INBOX), vm.uiState.value.layout)
            assertEquals(WidgetError.FAILED, vm.uiState.value.layoutError)
        }

    @Test
    fun `a failed save drops only its own change, a later pending one is still shown and saved`() =
        runTest(dispatcher) {
            val vm = loaded()
            val first = CompletableDeferred<DashboardResult<List<WidgetSlot>>>()
            var calls = 0
            coEvery { repo.saveLayout(any()) } coAnswers {
                val sent = firstArg<List<WidgetSlot>>()
                saved += sent
                if (calls++ == 0) first.await() else DashboardResult.Ok(sent)
            }

            vm.move(WidgetId.WEEKLY_REVIEW, 0)
            advanceUntilIdle()
            vm.setVisible(WidgetId.PINNED_NOTES, true)
            advanceUntilIdle()
            first.complete(DashboardResult.Failed)
            advanceUntilIdle()

            // The refused move is gone; the later change is applied to the stored layout and saved.
            val latest = layoutOf(WidgetId.INBOX, WidgetId.WEEKLY_REVIEW).withVisible(WidgetId.PINNED_NOTES, true)
            assertEquals(listOf(layoutOf(WidgetId.WEEKLY_REVIEW, WidgetId.INBOX), latest), saved)
            assertEquals(latest, vm.uiState.value.layout)
            assertEquals(listOf(WidgetId.INBOX, WidgetId.WEEKLY_REVIEW, WidgetId.PINNED_NOTES), vm.uiState.value.widgets)
            assertEquals(WidgetError.FAILED, vm.uiState.value.layoutError)
        }

    @Test
    fun `right after an earlier save fails the later pending change stays shown`() =
        runTest(dispatcher) {
            val vm = loaded()
            val first = CompletableDeferred<DashboardResult<List<WidgetSlot>>>()
            val second = CompletableDeferred<DashboardResult<List<WidgetSlot>>>()
            coEvery { repo.saveLayout(any()) } coAnswers { first.await() } coAndThen { second.await() }

            vm.move(WidgetId.WEEKLY_REVIEW, 0)
            vm.setVisible(WidgetId.INBOX, false)
            advanceUntilIdle()
            first.complete(DashboardResult.Offline)
            advanceUntilIdle()

            assertEquals(listOf(WidgetId.WEEKLY_REVIEW), vm.uiState.value.widgets)
            assertEquals(WidgetError.OFFLINE, vm.uiState.value.layoutError)
            second.complete(DashboardResult.Ok(layoutOf(WidgetId.INBOX, WidgetId.WEEKLY_REVIEW).withVisible(WidgetId.INBOX, false)))
            advanceUntilIdle()
            assertEquals(listOf(WidgetId.WEEKLY_REVIEW), vm.uiState.value.widgets)
        }

    @Test
    fun `a refresh during an unsaved change keeps the shown layout`() =
        runTest(dispatcher) {
            val vm = loaded()
            val pending = CompletableDeferred<DashboardResult<List<WidgetSlot>>>()
            coEvery { repo.saveLayout(any()) } coAnswers { pending.await() }

            vm.move(WidgetId.WEEKLY_REVIEW, 0)
            vm.refresh()
            advanceUntilIdle()

            assertEquals(listOf(WidgetId.WEEKLY_REVIEW, WidgetId.INBOX), vm.uiState.value.widgets)
            pending.complete(DashboardResult.Ok(layoutOf(WidgetId.WEEKLY_REVIEW, WidgetId.INBOX)))
            advanceUntilIdle()
            assertEquals(listOf(WidgetId.WEEKLY_REVIEW, WidgetId.INBOX), vm.uiState.value.widgets)
        }

    /**
     * A reload whose (older) layout answer arrives only after a change was already saved must not bring the old layout
     * back: the user's latest layout wins.
     */
    @Test
    fun `a layout read before a saved change does not undo it when it answers late`() =
        runTest(dispatcher) {
            val vm = loaded()
            val late = CompletableDeferred<DashboardResult<List<WidgetSlot>>>()
            coEvery { repo.layout() } coAnswers { late.await() }

            vm.refresh()
            advanceUntilIdle()
            vm.move(WidgetId.WEEKLY_REVIEW, 0)
            advanceUntilIdle()
            assertEquals(listOf(WidgetId.WEEKLY_REVIEW, WidgetId.INBOX), vm.uiState.value.widgets)

            late.complete(DashboardResult.Ok(layoutOf(WidgetId.INBOX, WidgetId.WEEKLY_REVIEW)))
            advanceUntilIdle()

            assertEquals(listOf(WidgetId.WEEKLY_REVIEW, WidgetId.INBOX), vm.uiState.value.widgets)
        }

    @Test
    fun `an event without an occurrence opens the calendar at once`() =
        runTest(dispatcher) {
            val vm = DashboardViewModel(repo, projects)

            vm.openEvent(DashboardEvent("e:1", "Dentist", LocalDate.of(2026, 10, 21), start = null))

            assertEquals(DashboardOpen.Screen(CockpitDestination.Calendar), vm.uiState.value.open)
            coVerify(exactly = 0) { repo.calendars() }
        }

    @Test
    fun `an event opens in the editor with the calendars`() =
        runTest(dispatcher) {
            coEvery { repo.calendars() } returns DashboardResult.Ok(calendars)
            val vm = DashboardViewModel(repo, projects)
            val occurrence = occurrence()

            vm.openEvent(DashboardEvent("e:1", "Dentist", LocalDate.of(2026, 10, 21), occurrence.start, occurrence))
            assertNull(vm.uiState.value.open)
            advanceUntilIdle()

            assertEquals(DashboardOpen.Event(occurrence, calendars), vm.uiState.value.open)
        }

    @Test
    fun `an event whose calendars cannot be loaded opens the calendar`() =
        runTest(dispatcher) {
            coEvery { repo.calendars() } returnsMany listOf(DashboardResult.Offline, DashboardResult.Failed)
            val vm = DashboardViewModel(repo, projects)
            val event = DashboardEvent("e:1", "Dentist", LocalDate.of(2026, 10, 21), null, occurrence())

            vm.openEvent(event)
            advanceUntilIdle()
            assertEquals(DashboardOpen.Screen(CockpitDestination.Calendar), vm.uiState.value.open)

            vm.opened(vm.uiState.value.open!!)
            vm.openEvent(event)
            advanceUntilIdle()
            assertEquals(DashboardOpen.Screen(CockpitDestination.Calendar), vm.uiState.value.open)
        }

    @Test
    fun `a project is requested for the Projects screen which then opens`() =
        runTest(dispatcher) {
            val vm = DashboardViewModel(repo, projects)

            vm.openProject(id(7))

            assertEquals(id(7), projects.requests.value)
            assertEquals(DashboardOpen.Screen(CockpitDestination.Projects), vm.uiState.value.open)
        }

    @Test
    fun `opened clears what was opened, but not something newer`() =
        runTest(dispatcher) {
            val vm = DashboardViewModel(repo, projects)
            vm.openProject(id(7))
            val first = vm.uiState.value.open!!

            vm.opened(first)
            assertNull(vm.uiState.value.open)

            vm.openProject(id(8))
            vm.opened(DashboardOpen.Screen(CockpitDestination.Calendar))
            assertEquals(DashboardOpen.Screen(CockpitDestination.Projects), vm.uiState.value.open)
        }
}
