package ch.sonensei.cockpit.gtd.today

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.calendar.agenda.CalendarRepository
import ch.sonensei.cockpit.calendar.agenda.CalendarResult
import ch.sonensei.cockpit.calendar.agenda.Occurrence
import ch.sonensei.cockpit.habits.Habit
import ch.sonensei.cockpit.habits.HabitSchedule
import ch.sonensei.cockpit.habits.HabitsRepository
import ch.sonensei.cockpit.habits.HabitsResult
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/** Today's events and due habits: loading, the check semantics, the rows and the empty day. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TodayExtrasTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 10, 20)
    private val clock = Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC)
    private val work = CalendarInfo(id(1), "Work", 0xFF3366CCL, visible = true, readOnly = false, version = 1)
    private val hidden = CalendarInfo(id(2), "Hidden", null, visible = false, readOnly = false, version = 1)
    private val standup =
        Occurrence(
            id(10),
            work.id,
            "Team standup",
            "Room 2",
            allDay = false,
            start = Instant.parse("2026-10-20T09:00:00Z"),
            end = Instant.parse("2026-10-20T09:15:00Z"),
            startDate = null,
            endDate = null,
        )
    private val holiday = Occurrence(id(11), work.id, "Company day", null, true, null, null, today, today.plusDays(1))
    private val secret = standup.copy(eventId = id(12), calendarId = hidden.id, title = "Hidden thing")

    private fun habit(
        n: Int,
        name: String,
        count: Int = 0,
        target: Int = 1,
        due: Boolean = true,
    ) = Habit(
        id(n),
        name,
        HabitSchedule.DAILY,
        null,
        emptyList(),
        target,
        today,
        count,
        due,
        count >= target,
        0,
        false,
        null,
    )

    private val water = habit(20, "Drink water", count = 1, target = 3)
    private val calendar = mockk<CalendarRepository>()
    private val habits = mockk<HabitsRepository>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { calendar.calendars() } returns CalendarResult.Ok(listOf(work, hidden))
        coEvery { calendar.occurrences(today, today.plusDays(1), ZoneOffset.UTC) } returns
            CalendarResult.Ok(listOf(standup, secret, holiday))
        coEvery { habits.list() } returns HabitsResult.Ok(listOf(water, habit(21, "Not today", due = false)))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `load lists today's visible events all-day first and only the habits due today`() {
        val vm = TodayExtrasViewModel(calendar, habits, clock)
        vm.load()
        val state = vm.uiState.value
        assertEquals(listOf(holiday, standup), state.events)
        assertEquals(listOf(water), state.habits)
    }

    @Test
    fun `a check sends the next count and shows the habit the server returns`() {
        val checked = water.copy(todayCount = 2)
        coEvery { habits.check(water) } returns HabitsResult.Ok(checked)
        val vm = TodayExtrasViewModel(calendar, habits, clock)
        vm.load()
        vm.check(water)
        coVerify { habits.check(water) }
        assertEquals(listOf(checked), vm.uiState.value.habits)
        assertTrue(
            vm.uiState.value.checking
                .isEmpty(),
        )
        assertFalse(vm.uiState.value.checkFailed)
    }

    @Test
    fun `a failed check keeps the habit and says so`() {
        coEvery { habits.check(water) } returns HabitsResult.Offline
        val vm = TodayExtrasViewModel(calendar, habits, clock)
        vm.load()
        vm.check(water)
        assertEquals(listOf(water), vm.uiState.value.habits)
        assertTrue(vm.uiState.value.checkFailed)
    }

    @Test
    fun `a failed load keeps what was shown`() {
        val vm = TodayExtrasViewModel(calendar, habits, clock)
        vm.load()
        coEvery { calendar.occurrences(any(), any(), any()) } returns CalendarResult.Offline
        coEvery { habits.list() } returns HabitsResult.Failed(500)
        vm.load()
        assertEquals(2, vm.uiState.value.events.size)
        assertEquals(1, vm.uiState.value.habits.size)
    }

    @Test
    fun `a second tap while the check is being sent is ignored`() {
        val pending = CompletableDeferred<HabitsResult<Habit>>()
        coEvery { habits.check(water) } coAnswers { pending.await() }
        val vm = TodayExtrasViewModel(calendar, habits, clock)
        vm.load()
        vm.check(water)
        vm.check(water)
        assertEquals(setOf(water.id), vm.uiState.value.checking)
        val checked = water.copy(todayCount = 2)
        pending.complete(HabitsResult.Ok(checked))
        coVerify(exactly = 1) { habits.check(water) }
        assertEquals(listOf(checked), vm.uiState.value.habits)
        assertTrue(
            vm.uiState.value.checking
                .isEmpty(),
        )
    }

    @Test
    fun `a successful check clears the message of an earlier failed one`() {
        val checked = water.copy(todayCount = 2)
        coEvery { habits.check(water) } returnsMany listOf(HabitsResult.Failed(400), HabitsResult.Ok(checked))
        val vm = TodayExtrasViewModel(calendar, habits, clock)
        vm.load()
        vm.check(water)
        assertTrue(vm.uiState.value.checkFailed)
        vm.check(water)
        assertFalse(vm.uiState.value.checkFailed)
        assertEquals(listOf(checked), vm.uiState.value.habits)
    }

    @Test
    fun `archived habits are left out and no events show while the calendars cannot be loaded`() {
        val archived = habit(22, "Old routine").copy(archived = true)
        coEvery { habits.list() } returns HabitsResult.Ok(listOf(archived, water))
        coEvery { calendar.calendars() } returns CalendarResult.Offline
        val vm = TodayExtrasViewModel(calendar, habits, clock)
        vm.load()
        assertEquals(listOf(water), vm.uiState.value.habits)
        assertTrue(
            vm.uiState.value.events
                .isEmpty(),
        )
    }

    @Test
    fun `event times leave out the side outside today`() {
        val zone = ZoneId.of("UTC")
        assertEquals("09:00–09:15", eventTime(standup, today, zone, Locale.GERMANY))
        val fromYesterday = standup.copy(start = Instant.parse("2026-10-19T22:00:00Z"))
        assertEquals("–09:15", eventTime(fromYesterday, today, zone, Locale.GERMANY))
        val intoTomorrow = standup.copy(end = Instant.parse("2026-10-21T01:00:00Z"))
        assertEquals("09:00–", eventTime(intoTomorrow, today, zone, Locale.GERMANY))
        assertNull(eventTime(fromYesterday.copy(end = Instant.parse("2026-10-21T01:00:00Z")), today, zone, Locale.GERMANY))
        assertEquals("09:00", eventTime(standup.copy(end = null), today, zone, Locale.GERMANY))
        val wholeDay = standup.copy(start = Instant.parse("2026-10-20T00:00:00Z"), end = Instant.parse("2026-10-21T00:00:00Z"))
        assertNull(eventTime(wholeDay, today, zone, Locale.GERMANY))
        assertNull(eventTime(wholeDay.copy(start = Instant.parse("2026-10-19T12:00:00Z")), today, zone, Locale.GERMANY))
        assertEquals("00:00–23:00", eventTime(wholeDay.copy(end = Instant.parse("2026-10-20T23:00:00Z")), today, zone, Locale.GERMANY))
    }

    @Test
    fun `only the newest load's events show, also when an older load answers last`() {
        val older = CompletableDeferred<CalendarResult<List<Occurrence>>>()
        val newer = CompletableDeferred<CalendarResult<List<Occurrence>>>()
        val answers = ArrayDeque(listOf(older, newer))
        coEvery { calendar.occurrences(today, today.plusDays(1), ZoneOffset.UTC) } coAnswers { answers.removeFirst().await() }
        val vm = TodayExtrasViewModel(calendar, habits, clock)
        vm.load()
        vm.load()
        newer.complete(CalendarResult.Ok(listOf(standup)))
        older.complete(CalendarResult.Ok(listOf(standup, holiday)))
        assertEquals(listOf(standup), vm.uiState.value.events)
    }

    @Test
    fun `only the newest load's habits show, also when an older load answers last`() {
        val older = CompletableDeferred<HabitsResult<List<Habit>>>()
        val newer = CompletableDeferred<HabitsResult<List<Habit>>>()
        val answers = ArrayDeque(listOf(older, newer))
        coEvery { habits.list() } coAnswers { answers.removeFirst().await() }
        val vm = TodayExtrasViewModel(calendar, habits, clock)
        vm.load()
        vm.load()
        val updated = water.copy(todayCount = 2)
        newer.complete(HabitsResult.Ok(listOf(updated)))
        older.complete(HabitsResult.Ok(listOf(water)))
        assertEquals(listOf(updated), vm.uiState.value.habits)
    }

    @Test
    fun `a habit list requested before a check finished does not undo the check`() {
        val vm = TodayExtrasViewModel(calendar, habits, clock)
        vm.load()
        val pendingList = CompletableDeferred<HabitsResult<List<Habit>>>()
        coEvery { habits.list() } coAnswers { pendingList.await() }
        val checked = water.copy(todayCount = 2)
        coEvery { habits.check(water) } returns HabitsResult.Ok(checked)
        vm.load()
        vm.check(water)
        assertEquals(listOf(checked), vm.uiState.value.habits)
        val other = habit(23, "Read")
        pendingList.complete(HabitsResult.Ok(listOf(water, other)))
        assertEquals(listOf(checked, other), vm.uiState.value.habits)
    }

    @Test
    fun `a habit list requested while a check is on its way keeps the checked habit`() {
        val vm = TodayExtrasViewModel(calendar, habits, clock)
        vm.load()
        val pendingCheck = CompletableDeferred<HabitsResult<Habit>>()
        coEvery { habits.check(water) } coAnswers { pendingCheck.await() }
        val pendingList = CompletableDeferred<HabitsResult<List<Habit>>>()
        coEvery { habits.list() } coAnswers { pendingList.await() }
        vm.check(water)
        vm.load()
        val checked = water.copy(todayCount = 2)
        pendingCheck.complete(HabitsResult.Ok(checked))
        pendingList.complete(HabitsResult.Ok(listOf(water)))
        assertEquals(listOf(checked), vm.uiState.value.habits)
    }

    @Test
    fun `a later load shows habits checked elsewhere and clears the failed check message`() {
        coEvery { habits.check(water) } returns HabitsResult.Offline
        val vm = TodayExtrasViewModel(calendar, habits, clock)
        vm.load()
        vm.check(water)
        assertTrue(vm.uiState.value.checkFailed)
        val elsewhere = water.copy(todayCount = 3, doneToday = true)
        coEvery { habits.list() } returns HabitsResult.Ok(listOf(elsewhere))
        vm.load()
        assertFalse(vm.uiState.value.checkFailed)
        assertEquals(listOf(elsewhere), vm.uiState.value.habits)
    }

    @Test
    fun `all-day events of other days are left out`() {
        val tomorrow = holiday.copy(eventId = id(13), title = "Tomorrow", startDate = today.plusDays(1), endDate = today.plusDays(2))
        val endedYesterday = holiday.copy(eventId = id(14), title = "Ended", startDate = today.minusDays(2), endDate = today)
        val trip = holiday.copy(eventId = id(15), title = "Trip", startDate = today.minusDays(1), endDate = today.plusDays(2))
        val noEnd = holiday.copy(eventId = id(16), title = "No end", endDate = null)
        coEvery { calendar.occurrences(today, today.plusDays(1), ZoneOffset.UTC) } returns
            CalendarResult.Ok(listOf(standup, tomorrow, endedYesterday, trip, holiday, noEnd))
        val vm = TodayExtrasViewModel(calendar, habits, clock)
        vm.load()
        assertEquals(listOf(holiday, noEnd, trip, standup), vm.uiState.value.events)
    }

    private fun show(
        extras: TodayExtrasUiState,
        tasks: TodayUiState = TodayUiState(refreshedAt = Instant.EPOCH, today = today),
        callbacks: TodayCallbacks = TodayCallbacks(),
        extrasCallbacks: TodayExtrasCallbacks = TodayExtrasCallbacks(),
    ) = composeRule.setContent { CockpitTheme { TodayContent(tasks, callbacks, extras = extras, extrasCallbacks = extrasCallbacks) } }

    @Test
    fun `events show their time and title, and a tap opens the event`() {
        var opened: Occurrence? = null
        show(
            TodayExtrasUiState(events = listOf(holiday, standup), calendars = listOf(work), zone = ZoneOffset.UTC),
            extrasCallbacks = TodayExtrasCallbacks(openEvent = { opened = it }),
        )
        composeRule.onNodeWithTag(TodayExtrasTags.EVENTS).assertIsDisplayed()
        composeRule.onNodeWithText("Team standup").assertIsDisplayed()
        composeRule.onNodeWithText("All day").assertIsDisplayed()
        composeRule.onNodeWithTag(TodayExtrasTags.event(standup)).performClick()
        assertEquals(standup, opened)
    }

    @Test
    fun `habits due today show their count and the check button checks them`() {
        var checked: Habit? = null
        show(TodayExtrasUiState(habits = listOf(water)), extrasCallbacks = TodayExtrasCallbacks(checkHabit = { checked = it }))
        composeRule.onNodeWithTag(TodayExtrasTags.HABITS).assertIsDisplayed()
        composeRule.onNodeWithText("1/3").assertIsDisplayed()
        composeRule.onNodeWithTag(TodayExtrasTags.check(water)).performClick()
        assertEquals(water, checked)
    }

    @Test
    fun `a done habit offers undo without a count and an event shows its location`() {
        val stretch = habit(22, "Stretch", count = 1, target = 1)
        show(
            TodayExtrasUiState(events = listOf(standup), calendars = listOf(work), habits = listOf(stretch), zone = ZoneOffset.UTC),
            tasks = TodayUiState(today = today),
        )
        composeRule.onNodeWithText("Room 2").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Undo Stretch").assertIsDisplayed()
        composeRule.onNodeWithText("1/1").assertDoesNotExist()
    }

    @Test
    fun `a habit being checked has its button disabled and a failed check shows a message`() {
        show(TodayExtrasUiState(habits = listOf(water), checking = setOf(water.id), checkFailed = true))
        composeRule.onNodeWithTag(TodayExtrasTags.check(water)).assertIsNotEnabled()
        composeRule.onNodeWithTag(TodayExtrasTags.CHECK_FAILED).assertIsDisplayed()
    }

    @Test
    fun `an empty day offers to pick a next action, also with events`() {
        var picked = false
        show(
            TodayExtrasUiState(events = listOf(standup), calendars = listOf(work), zone = ZoneOffset.UTC),
            callbacks = TodayCallbacks(openNext = { picked = true }),
        )
        composeRule.onNodeWithTag(TodayTags.EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText("Pick a next action").performClick()
        assertTrue(picked)
    }

    @Test
    fun `the empty day action shows without events or habits`() {
        show(TodayExtrasUiState())
        composeRule.onNodeWithTag(TodayTags.PICK_NEXT).assertIsDisplayed()
    }
}
