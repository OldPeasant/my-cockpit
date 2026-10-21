package ch.sonensei.cockpit.calendar.agenda

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The agenda, day and week views alone, driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
class CalendarScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.parse("2026-10-21")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(90), "Work", 0xFF3366FFL, true, false, 1)
    private val home = CalendarInfo(id(91), "Home", null, false, false, 1)

    private val standup =
        Occurrence(
            id(1),
            work.id,
            "Standup",
            "Room 2",
            false,
            Instant.parse("2026-10-21T09:00:00Z"),
            Instant.parse("2026-10-21T09:15:00Z"),
            null,
            null,
        )
    private val holiday = Occurrence(id(2), work.id, "Holiday", null, true, null, null, today.plusDays(2), today.plusDays(3))
    private val calls = mutableListOf<String>()

    private fun show(state: CalendarUiState) =
        composeRule.setContent {
            CockpitTheme {
                CalendarContent(
                    state,
                    CalendarCallbacks(
                        select = { calls += "select $it" },
                        move = { calls += "move $it" },
                        today = { calls += "today" },
                        openDay = { calls += "day $it" },
                        loadMore = { calls += "more" },
                        setVisible = { calendar, visible -> calls += "visible $calendar $visible" },
                        retry = { calls += "retry" },
                        open = { calls += "open ${it.title}" },
                    ),
                )
            }
        }

    private fun state(
        view: CalendarView = CalendarView.AGENDA,
        occurrences: List<Occurrence> = listOf(standup, holiday),
        message: CalendarMessage? = null,
    ) = CalendarUiState(
        view = view,
        today = today,
        anchor = today,
        firstDayOfWeek = DayOfWeek.MONDAY,
        zone = utc,
        calendars = listOf(work, home),
        from = if (view == CalendarView.WEEK) LocalDate.parse("2026-10-19") else today,
        to = if (view == CalendarView.AGENDA) today.plusDays(30) else today.plusDays(1),
        occurrences = occurrences,
        loaded = true,
        message = message,
    )

    @Test
    fun `the agenda shows the days with events, their times, and opens a day or an event`() {
        show(state())

        composeRule.onNodeWithTag(CalendarTags.day(today)).assertTextContains("Today", substring = true)
        composeRule.onNodeWithTag(CalendarTags.entry(standup.key, today)).assertTextContains("Standup", substring = true)
        composeRule.onNodeWithTag(CalendarTags.entry(standup.key, today)).assertTextContains("Room 2", substring = true)
        composeRule.onNodeWithTag(CalendarTags.entry(holiday.key, today.plusDays(2))).assertTextContains("All day", substring = true)
        composeRule.onNodeWithTag(CalendarTags.day(today.plusDays(1))).assertDoesNotExist()

        composeRule.onNodeWithTag(CalendarTags.entry(standup.key, today)).performClick()
        composeRule.onNodeWithTag(CalendarTags.day(today.plusDays(2))).performClick()
        composeRule.onNodeWithTag(CalendarTags.AGENDA).performScrollToNode(hasTestTag(CalendarTags.MORE))
        composeRule.onNodeWithTag(CalendarTags.MORE).performClick()
        // The list does not fit the screen, and its end is in view: the next days were asked for by themselves.
        assertEquals("more", calls.first())
        assertEquals(listOf("open Standup", "day ${today.plusDays(2)}", "more"), calls.drop(1))
    }

    @Test
    fun `an empty agenda says until when and offers later days`() {
        show(state(occurrences = emptyList()))
        composeRule.onNodeWithTag(CalendarTags.EMPTY).assertExists()
        composeRule.onNodeWithTag(CalendarTags.MORE).performClick()
        assertEquals(listOf("more"), calls)
    }

    @Test
    fun `the view switch, today and the calendar toggles call back`() {
        show(state())

        composeRule.onNodeWithTag(CalendarTags.view(CalendarView.AGENDA)).assertIsSelected()
        composeRule.onNodeWithTag(CalendarTags.view(CalendarView.WEEK)).performClick()
        composeRule.onNodeWithTag(CalendarTags.TODAY).performClick()
        composeRule.onNodeWithTag(CalendarTags.CALENDARS).performClick()
        composeRule.onNodeWithTag(CalendarTags.calendar(work.id)).assertIsSelected()
        composeRule.onNodeWithTag(CalendarTags.calendar(home.id)).assertIsNotSelected().performClick()
        assertEquals(listOf("select WEEK", "today", "visible ${home.id} true"), calls.filter { it != "more" })
    }

    @Test
    fun `the day view places timed events in the grid and all-day ones above it`() {
        val allDayToday = holiday.copy(eventId = id(3), title = "Birthday", startDate = today, endDate = today.plusDays(1))
        show(state(view = CalendarView.DAY, occurrences = listOf(standup, allDayToday)))

        composeRule.onNodeWithTag(CalendarTags.TIME_GRID).assertExists()
        composeRule.onNodeWithTag(CalendarTags.allDay(today)).assertExists()
        composeRule.onNodeWithTag(CalendarTags.entry(allDayToday.key, today)).assertTextContains("Birthday", substring = true)
        composeRule.onNodeWithTag(CalendarTags.entry(standup.key, today)).assertTextContains("Standup", substring = true)
        composeRule.onNodeWithTag(CalendarTags.NOW).assertExists()

        composeRule.onNodeWithTag(CalendarTags.PREVIOUS).performClick()
        composeRule.onNodeWithTag(CalendarTags.NEXT).performClick()
        composeRule.onNodeWithTag(CalendarTags.entry(standup.key, today)).performClick()
        assertEquals(listOf("move -1", "move 1", "open Standup"), calls)
    }

    @Test
    fun `an empty day says so`() {
        show(state(view = CalendarView.DAY, occurrences = emptyList()))
        composeRule.onNodeWithTag(CalendarTags.EMPTY).assertExists()
    }

    @Test
    fun `the week view shows seven days from the first day of the week, a day opens the day view`() {
        show(state(view = CalendarView.WEEK))

        val monday = LocalDate.parse("2026-10-19")
        (0L until 7L).forEach { composeRule.onNodeWithTag(CalendarTags.day(monday.plusDays(it))).assertExists() }
        composeRule.onNodeWithTag(CalendarTags.entry(holiday.key, today.plusDays(2))).assertExists()
        composeRule.onNodeWithTag(CalendarTags.entry(standup.key, today)).assertExists()
        composeRule.onNodeWithTag(CalendarTags.RANGE).assertTextContains("2026", substring = true)

        composeRule.onNodeWithTag(CalendarTags.day(monday)).performClick()
        assertEquals(listOf("day $monday"), calls)
    }

    @Test
    fun `a failed load shows why and retries`() {
        show(state(message = CalendarMessage.FAILED))
        composeRule.onNodeWithText("The events could not be loaded.").assertExists()
        composeRule.onNodeWithTag(CalendarTags.RETRY).performClick()
        assertTrue("retry" in calls)
    }
}
