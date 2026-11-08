package ch.sonensei.cockpit.gtd.today

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
    fun `event times leave out the side outside today`() {
        val zone = ZoneId.of("UTC")
        assertEquals("09:00–09:15", eventTime(standup, today, zone, Locale.GERMANY))
        val fromYesterday = standup.copy(start = Instant.parse("2026-10-19T22:00:00Z"))
        assertEquals("–09:15", eventTime(fromYesterday, today, zone, Locale.GERMANY))
        val intoTomorrow = standup.copy(end = Instant.parse("2026-10-21T01:00:00Z"))
        assertEquals("09:00–", eventTime(intoTomorrow, today, zone, Locale.GERMANY))
        assertNull(eventTime(fromYesterday.copy(end = Instant.parse("2026-10-21T01:00:00Z")), today, zone, Locale.GERMANY))
        assertEquals("09:00", eventTime(standup.copy(end = null), today, zone, Locale.GERMANY))
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
